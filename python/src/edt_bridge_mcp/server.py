"""stdio MCP front-end for the edt-bridge 1C:EDT plugin.

The Java plugin serves plain JSON-RPC over HTTP on 127.0.0.1:8770 – but an MCP client
configured with an HTTP URL simply loses the server whenever EDT is not running. This
wrapper is what the client talks to instead (stdio, install via pipx):

- if the bridge port is alive (a GUI EDT or a headless one), every request is forwarded;
- if not, it AUTO-STARTS a headless EDT (1cedtcli with a keepalive pipe – the same recipe
  as scripts/run-headless.ps1) and forwards once it is ready;
- `initialize` / `tools/list` never block a client session: while the backend is starting,
  `tools/list` returns an empty list, and a `notifications/tools/list_changed` follows as
  soon as the backend is up – the client re-pulls the real tool list then.

Configuration (CLI flags override the environment):
    EDT_BRIDGE_PORT           bridge port (default 8770)
    EDT_BRIDGE_TOKEN          write-tools token, forwarded as Authorization: Bearer
    EDT_BRIDGE_WORKSPACE      EDT workspace path – required for the headless auto-start
    EDT_BRIDGE_EDT_DIR        EDT install dir (.../1cedt); auto-detected when omitted
    EDT_BRIDGE_START_TIMEOUT  seconds to wait for a starting backend (default 360)
    EDT_BRIDGE_AUTOSTART      set to 0/false to never launch anything (proxy-only)

Registration in Claude Code:
    claude mcp add edt-bridge -- edt-bridge-mcp --workspace <path-to-edt-workspace>
"""

from __future__ import annotations

import argparse
import csv
import difflib
import io
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from . import __version__
from . import cli, i18n, plugins

PROTOCOL_FALLBACK = "2024-11-05"

_WINDOWS = os.name == "nt"


def _console_encoding() -> str:
    """The codepage console tools answer in - the OEM one on Windows, not UTF-8 (see _pids_of)."""
    if not _WINDOWS:
        return "utf-8"
    try:
        import ctypes

        return f"cp{ctypes.windll.kernel32.GetOEMCP()}"
    except Exception:  # no ctypes, or a stub kernel32 - the fields we read are ASCII anyway
        return "cp437"


_CONSOLE_ENCODING = _console_encoding()
#: Process images of an EDT installation. The GUI workbench is one process; a headless session
#: is two - the CLI launcher and the runtime it spawns - and both have to go before the GUI can
#: take the workspace.
GUI_IMAGE = "1cedt.exe" if _WINDOWS else "1cedt"
CLI_IMAGE = "1cedtcli.exe" if _WINDOWS else "1cedtcli"
HEADLESS_IMAGES = (CLI_IMAGE, "1cedtc.exe" if _WINDOWS else "1cedtc")
#: How long to wait for the workbench WINDOW after the process is up. The window is what a
#: person is waiting for, but a large workspace loads for minutes - waiting that out would hang
#: the command, so a miss is reported and the next run brings the window forward.
WINDOW_WAIT = int(os.environ.get("EDT_BRIDGE_WINDOW_WAIT", "90"))


def force_utf8_streams() -> None:
    """Pin the standard streams to UTF-8 – MCP stdio frames are UTF-8 by spec.

    Without this, Windows opens them with the ANSI code page and a single character the
    code page cannot represent (e.g. the "→" in a tool description, under cp1251) aborts
    the whole frame – `tools/list` then fails and the client registers no tools at all.
    Input and diagnostics replace undecodable bytes rather than raise: a mangled request
    line is already handled as non-JSON, and a log message must never kill the process.
    """
    for stream, errors in ((sys.stdin, "replace"), (sys.stdout, None), (sys.stderr, "replace")):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:  # not a TextIOWrapper (redirected in tests, embedded host)
            continue
        if errors is None:
            reconfigure(encoding="utf-8")
        else:
            reconfigure(encoding="utf-8", errors=errors)


def log(message: str) -> None:
    """Diagnostics go to stderr – stdout carries only JSON-RPC frames."""
    print(f"[edt-bridge-mcp] {message}", file=sys.stderr, flush=True)


class Backend:
    """The HTTP side: probe / start / wait for the Java bridge, and forward requests."""

    def __init__(self) -> None:
        self.port = int(os.environ.get("EDT_BRIDGE_PORT", "8770"))
        self.token = (os.environ.get("EDT_BRIDGE_TOKEN") or "").strip() or None
        self.workspace = (os.environ.get("EDT_BRIDGE_WORKSPACE") or "").strip() or None
        self.edt_dir = (os.environ.get("EDT_BRIDGE_EDT_DIR") or "").strip() or None
        self.start_timeout = int(os.environ.get("EDT_BRIDGE_START_TIMEOUT", "360"))
        autostart = (os.environ.get("EDT_BRIDGE_AUTOSTART") or "1").strip().lower()
        self.autostart = autostart not in ("0", "false", "no")
        self.scan_range = int(os.environ.get("EDT_BRIDGE_PORT_SCAN", "20"))
        self._active_port = self.port  # the port the live bridge was last found on
        self._start_lock = threading.Lock()
        self._starting = False

    # -- probing ---------------------------------------------------------

    def _status_on(self, port: int) -> dict | None:
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/status", timeout=3
            ) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (OSError, ValueError):
            return None

    def status(self) -> dict | None:
        """The bridge /status payload, or None when no bridge is up in the scan range.

        The Java server binds the configured port or the next free one (a second EDT instance),
        so probe the configured port, then scan upward; the port that answers is remembered and
        used for forwarding."""
        s = self._status_on(self._active_port)
        if s is not None:
            return s
        for port in range(self.port, self.port + self.scan_range + 1):
            if port == self._active_port:
                continue
            s = self._status_on(port)
            if s is not None:
                self._active_port = port
                return s
        return None

    def is_ready(self) -> bool:
        """Ready = server up AND the model serves at least one open project."""
        s = self.status()
        return bool(s and s.get("openProjects"))

    def is_up(self) -> bool:
        return self.status() is not None

    # -- forwarding ------------------------------------------------------

    def forward(self, payload: dict) -> dict:
        """POST one JSON-RPC message to the live bridge (the port status() found)."""
        data = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(
            f"http://127.0.0.1:{self._active_port}/mcp", data=data, headers=headers, method="POST"
        )
        with urllib.request.urlopen(req, timeout=600) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def call_tool(self, name: str, arguments: dict) -> str:
        """One tools/call to the live bridge; the text of its result.

        The `bridge` callable handed to plugin handlers (see plugins.Tool).
        Deliberately never starts an EDT: a plugin tool answers fast by
        contract, and a headless start takes minutes - the caller degrades
        with this error's message instead, and the bridge tool called
        directly keeps its usual autostart.
        """
        if not self.is_up():
            raise RuntimeError(
                "no EDT bridge is running - start one (any bridge tool autostarts it) "
                "or call the bridge tool directly"
            )
        try:
            answer = self.forward({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                   "params": {"name": name, "arguments": arguments}})
        except (OSError, ValueError) as exc:
            raise RuntimeError(f"the bridge did not answer: {exc}") from exc
        error = answer.get("error")
        if error is not None:
            message = error.get("message") if isinstance(error, dict) else None
            raise RuntimeError(str(message or error))
        result = answer.get("result") or {}
        parts = [part.get("text", "") for part in result.get("content") or []
                 if isinstance(part, dict)]
        text = "\n".join(part for part in parts if part)
        if result.get("isError"):
            raise RuntimeError(text or f"{name} reported an error without a message")
        return text

    def shutdown(self, force: bool = False) -> dict:
        """POST /shutdown to the live bridge - the graceful stop of the EDT behind it.

        The bridge refuses (HTTP 409) when a GUI workbench is running and force is not set,
        so a script cannot close somebody's window by accident. HTTP errors propagate to the
        caller, which turns them into readable messages and exit codes."""
        data = json.dumps({"force": bool(force)}).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(
            f"http://127.0.0.1:{self._active_port}/shutdown", data=data, headers=headers,
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))

    # -- headless auto-start --------------------------------------------

    def ensure(self, wait: bool) -> tuple[bool, str]:
        """Make sure a backend is reachable.

        wait=True blocks until ready (or timeout); wait=False only kicks off a background
        start. Returns (ready, message) – message explains a False.
        """
        if self.is_up():
            return True, "up"
        if not self.autostart:
            return False, "bridge is not running and autostart is disabled (EDT_BRIDGE_AUTOSTART=0)"
        with self._start_lock:
            already = self._starting
            if not already:
                ok, msg = self._launch_headless()
                if not ok:
                    return False, msg
                self._starting = True
        if not wait:
            return False, "starting"
        deadline = time.monotonic() + self.start_timeout
        while time.monotonic() < deadline:
            if self.is_ready():
                self._starting = False
                return True, "started"
            time.sleep(3)
        return False, (
            f"headless EDT did not become ready in {self.start_timeout}s "
            "(first model load of a big workspace can take longer – retry, or raise "
            "EDT_BRIDGE_START_TIMEOUT)"
        )

    def _pids_of(self, image: str) -> list[int]:
        """Live pids of one EDT image – a list, not a flag: a hung process is killed by pid.

        The name is matched exactly, so asking for the GUI (1cedt) never returns the headless
        CLI (1cedtcli).
        """
        try:
            if os.name == "nt":
                # BYTES, decoded by hand: tasklist prints in the console OEM codepage, and
                # text=True decodes as UTF-8 - on a localized Windows that raises inside the
                # reader thread and leaves the output EMPTY. The old boolean checks read that
                # emptiness as "no such process": the GUI guard never fired here.
                raw = subprocess.run(
                    ["tasklist", "/FI", f"IMAGENAME eq {image}", "/FO", "CSV", "/NH"],
                    capture_output=True, timeout=15, check=False,
                ).stdout
                out = raw.decode(_CONSOLE_ENCODING, errors="replace")
                pids = []
                for row in csv.reader(io.StringIO(out)):
                    # A miss prints an info line, not a CSV row - it simply does not parse.
                    if len(row) >= 2 and row[0].lower() == image.lower():
                        try:
                            pids.append(int(row[1]))
                        except ValueError:
                            pass
                return pids
            out = subprocess.run(
                ["pgrep", "-x", image], capture_output=True, text=True, timeout=15, check=False
            ).stdout
            return [int(item) for item in out.split() if item.isdigit()]
        except OSError:
            return []

    def gui_pids(self) -> list[int]:
        return self._pids_of(GUI_IMAGE)

    def headless_pids(self) -> list[int]:
        """Both halves of a headless session: the CLI launcher and the runtime it spawns."""
        pids = []
        for image in HEADLESS_IMAGES:
            pids.extend(self._pids_of(image))
        return pids

    def _gui_edt_running(self) -> bool:
        """True when a GUI EDT (1cedt) process exists – we then refuse to launch headless
        (the GUI holds the workspace lock; the user likely just lacks the plugin there)."""
        return bool(self.gui_pids())

    def _headless_cli_running(self) -> bool:
        return bool(self._pids_of(HEADLESS_IMAGES[0]))

    def _find_exe(self, exe: str) -> Path | None:
        """Locate one executable of the EDT installation – the CLI and the GUI live side by side."""
        if self.edt_dir:
            p = Path(self.edt_dir) / exe
            return p if p.exists() else None
        if os.name == "nt":
            base = Path(os.environ.get("LOCALAPPDATA", "")) / "1C" / "1cedtstart" / "installations"
            if base.is_dir():
                for inst in sorted(base.iterdir(), reverse=True):
                    p = inst / "1cedt" / exe
                    if p.exists():
                        return p
        else:
            for pattern in ("~/1C/1CE/components/*/1cedt*/Contents/Eclipse", "~/1cedt"):
                for cand in sorted(Path().glob(os.path.expanduser(pattern)), reverse=True):
                    p = cand / exe
                    if p.exists():
                        return p
        return None

    def _find_cli(self) -> Path | None:
        return self._find_exe(CLI_IMAGE)

    def _ensure_plugin_jar(self, cli_dir: Path) -> tuple[bool, str]:
        """Deliver the plugin jar into EDT's dropins when it is missing: the wrapper installs
        from PyPI, the jar comes from the latest GitHub release – so a bare
        `pipx install edt-bridge-mcp` is enough to get a working bridge."""
        from . import update

        dropins = cli_dir / "dropins"
        if update.has_jar(dropins):
            return True, "jar present"
        log("plugin jar is missing in dropins – delivering the latest release jar")
        if update.install_latest_jar(dropins, emit=log):
            return True, "jar delivered"
        return False, (
            "the edt-bridge plugin jar is missing in EDT's dropins and could not be "
            "downloaded from GitHub Releases – install it manually (see the repository README)"
        )

    def _launch_headless(self) -> tuple[bool, str]:
        """Start 1cedtcli with the keepalive pipe (the run-headless recipe), detached."""
        if self._headless_cli_running():
            return True, "a headless 1cedtcli is already starting – waiting for it"
        if self._gui_edt_running():
            gui_hint = ("bridge port is dead but a GUI EDT is running – refusing to start a "
                        "headless one (workspace lock).")
            cli = self._find_cli()
            if cli is not None:
                delivered, _ = self._ensure_plugin_jar(cli.parent)
                if delivered:
                    return False, gui_hint + (" The plugin jar is in dropins – restart that "
                                              "EDT to activate the bridge, or close it.")
            return False, gui_hint + (" Install/enable the edt-bridge plugin in that EDT, "
                                      "or close it.")
        if not self.workspace:
            return False, (
                "bridge is not running and no workspace is configured for auto-start – "
                "pass --workspace or set EDT_BRIDGE_WORKSPACE (or open EDT with the "
                "edt-bridge plugin yourself)"
            )
        cli = self._find_cli()
        if cli is None:
            return False, (
                "1cedtcli not found – pass --edt-dir or set EDT_BRIDGE_EDT_DIR to the "
                ".../1cedt install folder"
            )
        jar_ok, jar_msg = self._ensure_plugin_jar(cli.parent)
        if not jar_ok:
            return False, jar_msg
        ws = Path(self.workspace)
        lock = ws / ".metadata" / ".lock"
        if lock.exists():
            try:
                lock.unlink()
            except OSError:
                return False, f"workspace lock is held and cannot be removed: {lock}"
        log(f"starting headless EDT: {cli} -data {ws}")
        try:
            if os.name == "nt":
                lines = ["@echo off"]
                if self.token:
                    lines.append(f'set "EDT_BRIDGE_TOKEN={self.token}"')
                if (os.environ.get("EDT_BRIDGE_ALLOW_EVALUATE") or "").strip():
                    lines.append(
                        f'set "EDT_BRIDGE_ALLOW_EVALUATE={os.environ["EDT_BRIDGE_ALLOW_EVALUATE"].strip()}"'
                    )
                lines.append(
                    f'(echo version& ping -n 999999 127.0.0.1 >nul) | "{cli}" -data "{ws}" -nl en_US'
                )
                bat = Path(tempfile.gettempdir()) / "edtbridge-headless.bat"
                bat.write_text("\r\n".join(lines) + "\r\n", encoding="ascii")
                creation = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
                subprocess.Popen(
                    ["cmd", "/c", str(bat)], cwd=str(cli.parent),
                    creationflags=creation,
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            else:
                shell = (
                    f'(echo version; while :; do sleep 3600; done) | "{cli}" -data "{ws}" -nl en_US'
                )
                subprocess.Popen(
                    ["sh", "-c", shell], cwd=str(cli.parent), start_new_session=True,
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
        except OSError as exc:
            return False, f"failed to launch 1cedtcli: {exc}"
        return True, "launched"

    # -- handing the workspace over to the GUI ---------------------------

    def _keepalive_pids(self) -> list[int]:
        """The shell that feeds the headless CLI its keepalive stdin, and its ping.

        It is the PARENT of 1cedtcli, so killing the tree below the CLI does not reach it, and
        while it lives it keeps the dropins jar locked. Matched by command line - the same
        marker scripts/toggle-headless.ps1 uses.
        """
        if not _WINDOWS:
            return []
        # $PID excludes the query itself: the pattern we look for is part of THIS command line,
        # so without it the shell reports - and force would kill - its own process.
        query = (
            "Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and "
            "$_.CommandLine -match 'edtbridge-headless|999999 127\\.0\\.0\\.1' } | "
            "ForEach-Object { $_.ProcessId }"
        )
        try:
            raw = subprocess.run(["powershell", "-NoProfile", "-Command", query],
                                 capture_output=True, timeout=30, check=False).stdout
        except (OSError, subprocess.SubprocessError):
            return []
        return [int(item) for item in raw.decode(_CONSOLE_ENCODING, errors="replace").split()
                if item.isdigit()]

    def _kill(self, pid: int) -> None:
        """Kill one process and its children – the headless CLI is started from a keepalive shell."""
        try:
            if _WINDOWS:
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                               capture_output=True, timeout=30, check=False)
            else:
                os.kill(pid, signal.SIGKILL)
        except (OSError, subprocess.SubprocessError):
            pass

    def _wait(self, until, deadline: float) -> bool:
        while time.monotonic() < deadline:
            if until():
                return True
            time.sleep(1)
        return until()

    def stop_headless(self, force: bool = False, timeout: int = 90,
                      report=None) -> tuple[bool, list[int]]:
        """Stop the headless EDT and wait until its processes are really gone.

        Two steps, because /shutdown alone provably does not end the session. It stops the OSGi
        framework - the port falls silent - but 1cedtcli keeps running: it was started with a
        keepalive pipe on stdin, and it lives as long as that pipe is open. So once the bridge is
        down, the keepalive shell is closed. That is not killing EDT (it has already stopped
        itself); it is closing the pipe that holds the process, after which the CLI reaches EOF
        and exits on its own. Only what survives even that needs force.

        Returns (stopped, survivors).
        """
        say = report or log
        deadline = time.monotonic() + timeout
        if self.status() is not None:
            try:
                answer = self.shutdown(force=force)
                say(answer.get("message", "shutdown requested"))
            except urllib.error.HTTPError as http:
                if http.code == 409:
                    return False, []  # a GUI workbench answers here - not ours to stop
                say(f"the bridge answered HTTP {http.code} to the shutdown request")
            except urllib.error.URLError as url:
                say(f"could not ask the bridge to stop: {url.reason}")
            self._wait(lambda: self.status() is None, deadline)
        if self.headless_pids():
            keepalive = self._keepalive_pids()
            if keepalive:
                say(f"closing the keepalive that holds the CLI's stdin open: {keepalive}")
                for pid in keepalive:
                    self._kill(pid)
        if self._wait(lambda: not self.headless_pids(), deadline):
            return self.status() is None, []
        survivors = self.headless_pids()
        if not force:
            return False, survivors
        say(f"killing what is left of the headless session: {survivors}")
        for pid in survivors:
            self._kill(pid)
        if self._wait(lambda: not self.headless_pids(), time.monotonic() + 10):
            return True, []
        return False, self.headless_pids()

    def _clear_stale_lock(self) -> None:
        """Drop the workspace lock left by a killed session – EDT refuses the workspace with it."""
        if not self.workspace:
            return
        lock = Path(self.workspace) / ".metadata" / ".lock"
        if lock.exists():
            try:
                lock.unlink()
            except OSError:
                pass  # still held: the GUI will say so itself, and it is right to

    def _parents(self) -> dict[int, int]:
        """pid -> parent pid for every process, in one query."""
        if not _WINDOWS:
            return {}
        query = ("Get-CimInstance Win32_Process | ForEach-Object "
                 "{ \"$($_.ProcessId) $($_.ParentProcessId)\" }")
        try:
            raw = subprocess.run(["powershell", "-NoProfile", "-Command", query],
                                 capture_output=True, timeout=30, check=False).stdout
        except (OSError, subprocess.SubprocessError):
            return {}
        table = {}
        for line in raw.decode(_CONSOLE_ENCODING, errors="replace").splitlines():
            pair = line.split()
            if len(pair) == 2 and pair[0].isdigit() and pair[1].isdigit():
                table[int(pair[0])] = int(pair[1])
        return table

    def _gui_family(self) -> set[int]:
        """The launcher pids and everything under them.

        The workbench WINDOW belongs to the javaw the launcher starts, and that javaw runs from
        whatever JDK the installation resolved - here a Liberica under Program Files, nowhere
        near the EDT folder. So the family is walked by process tree: no assumption about where
        the runtime lives, and none about the language of the window title.
        """
        family = set(self.gui_pids())
        if not family:
            return family
        parents = self._parents()
        for _ in range(8):  # depth cap - a pid loop must not spin
            grown = {pid for pid, parent in parents.items() if parent in family}
            if grown <= family:
                break
            family |= grown
        return family

    def _window_of(self, pids: set[int]):
        """A visible titled window owned by one of these processes, or None."""
        if not _WINDOWS or not pids:
            return None
        import ctypes
        from ctypes import wintypes

        user32 = ctypes.windll.user32
        found = []

        @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        def _visit(hwnd, _lparam):
            if not user32.IsWindowVisible(hwnd) or not user32.GetWindowTextLengthW(hwnd):
                return True
            pid = wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            if pid.value in pids:
                found.append(hwnd)
                return False
            return True

        user32.EnumWindows(_visit, 0)
        return found[0] if found else None

    def raise_gui_window(self, wait: int = 0) -> bool:
        """Bring the EDT window to the front, waiting for it to appear if asked.

        A detached launch has no foreground rights, so the workbench came up BEHIND everything
        else and looked as if it had not started at all. Loading a large workspace takes minutes,
        so the wait is bounded and a miss is reported rather than endured.
        """
        if not _WINDOWS:
            return False
        import ctypes

        user32 = ctypes.windll.user32
        deadline = time.monotonic() + max(0, wait)
        while True:
            window = self._window_of(self._gui_family())
            if window:
                user32.ShowWindow(window, 9)  # SW_RESTORE - also un-minimizes
                user32.BringWindowToTop(window)
                user32.SetForegroundWindow(window)
                return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(2)

    def launch_gui(self, report=None) -> tuple[bool, str]:
        """Start the GUI EDT on the configured workspace, detached from this process."""
        say = report or log
        exe = self._find_exe(GUI_IMAGE)
        if exe is None:
            return False, (
                f"{GUI_IMAGE} not found - pass --edt-dir or set EDT_BRIDGE_EDT_DIR to the "
                ".../1cedt install folder"
            )
        # Same care as the launch scripts: one jar in dropins, and it is the current one -
        # otherwise the GUI comes up without the bridge, or with two versions of it.
        self._ensure_plugin_jar(exe.parent)
        command = [str(exe)]
        if self.workspace:
            command += ["-data", self.workspace]
        say(f"starting the GUI EDT: {' '.join(command)}")
        try:
            if _WINDOWS:
                # NOT detached: a detached process gets no foreground rights, and the workbench
                # came up BEHIND every other window - indistinguishable from "it did not start".
                # Its own process group only keeps a Ctrl+C in our console away from EDT.
                subprocess.Popen(
                    command, cwd=str(exe.parent),
                    creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            else:
                subprocess.Popen(
                    command, cwd=str(exe.parent), start_new_session=True,
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
        except OSError as exc:
            return False, f"failed to launch {GUI_IMAGE}: {exc}"
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            pids = self.gui_pids()
            if pids:
                if self.raise_gui_window(wait=WINDOW_WAIT):
                    return True, f"the GUI EDT is up and in front (pid {pids[0]})"
                return True, (
                    f"the GUI EDT is starting (pid {pids[0]}); its window has not appeared within "
                    f"{WINDOW_WAIT} s - loading a large workspace takes minutes, and it may open "
                    "behind the other windows. Run the command again to bring it to the front."
                )
            time.sleep(1)
        return False, ("the GUI EDT was launched but no process showed up within 60 s - "
                       "check the installation")

    def open_gui(self, force: bool = False, timeout: int = 90) -> tuple[bool, list[str]]:
        """Hand the workspace over: stop the headless EDT, then open the GUI one on it.

        The whole point of doing this in the wrapper rather than in a bridge tool: the wrapper
        outlives the EDT it stops, so it can report what happened and start the next one.
        """
        lines: list[str] = []
        say = lines.append
        already = self.gui_pids()
        if already and not self.headless_pids():
            # Not "nothing to do": a workbench that is still loading, or simply buried under
            # other windows, is exactly why the command gets run a second time.
            raised = self.raise_gui_window()
            say(f"a GUI EDT is already running (pid {already[0]}) - "
                + ("brought to the front" if raised
                   else "its window has not appeared yet, it is still loading"))
            return True, lines
        if self.headless_pids() or self.status() is not None:
            stopped, survivors = self.stop_headless(force=force, timeout=timeout, report=say)
            if not stopped:
                if survivors:
                    say(f"the headless EDT is still alive (pid {survivors}) after {timeout} s - "
                        "rerun with --force to kill it")
                else:
                    say("the bridge refused to stop - it belongs to a GUI EDT; "
                        "close that window or rerun with --force")
                return False, lines
            say("the headless EDT is down")
            self._clear_stale_lock()
        if already:
            say(f"a GUI EDT is already running (pid {already[0]})")
            return True, lines
        ok, message = self.launch_gui(report=say)
        say(message)
        return ok, lines


#: Tools the WRAPPER serves itself, on top of whatever the bridge inside EDT offers. The one
#: thing they have in common: they must survive the EDT they act on, which a tool running
#: inside that EDT cannot. They are listed alongside the bridge tools and never forwarded.
LOCAL_TOOLS = [
    {
        "name": "edt_open_gui",
        "description": (
            "Stop the headless EDT that serves the bridge and open the GUI EDT on the same "
            "workspace. Use it when a person needs the EDT window: a headless session holds the "
            "workspace lock, so it has to end first, and its processes have to be gone - not just "
            "its port. Served by the wrapper, which is why it can still answer after the EDT it "
            "stopped is gone. The bridge comes back by itself once the GUI EDT has loaded the "
            "plugin; until then no bridge tool is available."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "force": {
                    "type": "boolean",
                    "description": "kill the headless session when it does not stop in time "
                                   "(default false)",
                },
                "timeoutSeconds": {
                    "type": "integer",
                    "description": "how long to wait for the headless EDT to stop, default 90",
                },
            },
        },
    },
]
LOCAL_TOOL_NAMES = {tool["name"] for tool in LOCAL_TOOLS}


def load_plugin_tools() -> tuple[dict[str, plugins.Tool], str | None]:
    """Plugin tools by name, and the discovery failure if there was one.

    A broken plugin must not take the bridge's own tools down with it: the MCP
    server keeps serving, reports the failure on stderr, and the `plugins`
    command shows the same message to a person. Loud where it can be, alive
    where it must be.
    """
    try:
        found = plugins.plugin_tools(reserved=frozenset(LOCAL_TOOL_NAMES))
    except plugins.PluginError as exc:
        return {}, str(exc)
    return {tool.name: tool for tool in found}, None


def unknown_arguments(name: str, arguments: dict, tools: list[dict] | None = None) -> str | None:
    """The refusal for a local call carrying names the tool does not declare, or None.

    The bridge itself judges the names of its own tools; the wrapper serves edt_open_gui
    and the plugin tools by itself, so the same judgement has to be made here. Dropping a
    name in silence is worse than refusing: the answer then reads as a done deed for a
    call that did something else.
    """
    declared: set[str] = set()
    for tool in (LOCAL_TOOLS if tools is None else tools):
        if tool["name"] == name:
            declared = set(tool.get("inputSchema", {}).get("properties", {}))
            break
    else:
        return None
    unknown = [key for key in arguments if key not in declared]
    if not unknown:
        return None
    named = []
    for key in unknown:
        near = difflib.get_close_matches(key, sorted(declared), n=1, cutoff=0.7)
        named.append(f"'{key}'" + (f" (did you mean '{near[0]}'?)" if near else ""))
    return (f"{name}: unknown argument{'s' if len(unknown) > 1 else ''} " + ", ".join(named)
            + ". Nothing was done - an argument that is not in the schema would be dropped, and "
            "the answer would read as success. Arguments this tool takes: "
            + (", ".join(sorted(declared)) or "(none)"))


class StdioServer:
    """Newline-delimited JSON-RPC over stdio; forwards to the Backend."""

    def __init__(self, backend: Backend) -> None:
        self.backend = backend
        self._out_lock = threading.Lock()
        self._announced_ready = False
        self._plugin_tools, self._plugin_error = load_plugin_tools()
        if self._plugin_error:
            log(f"plugins are unavailable: {self._plugin_error}")
        elif self._plugin_tools:
            log(f"plugins add {len(self._plugin_tools)} tool(s): "
                + ", ".join(sorted(self._plugin_tools)))

    def _local_descriptors(self) -> list[dict]:
        """What the wrapper serves by itself: its own tools plus the plugin tools."""
        return list(LOCAL_TOOLS) + [t.descriptor() for t in self._plugin_tools.values()]

    # -- frames ----------------------------------------------------------

    def _send(self, message: dict) -> None:
        with self._out_lock:
            sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
            sys.stdout.flush()

    def _result(self, req_id, result: dict) -> None:
        self._send({"jsonrpc": "2.0", "id": req_id, "result": result})

    def _error(self, req_id, code: int, message: str) -> None:
        self._send({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})

    def _tool_error(self, req_id, message: str) -> None:
        """A tools/call failure as an in-band tool result (isError), not a protocol error."""
        self._result(req_id, {
            "content": [{"type": "text", "text": message}],
            "isError": True,
        })

    def _notify_tools_changed(self) -> None:
        self._send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})

    # -- background readiness announcement ------------------------------

    def _announce_when_ready(self) -> None:
        deadline = time.monotonic() + self.backend.start_timeout
        while time.monotonic() < deadline:
            if self.backend.is_ready():
                if not self._announced_ready:
                    self._announced_ready = True
                    log("backend is ready – announcing tools/list_changed")
                    self._notify_tools_changed()
                return
            time.sleep(3)
        log("backend did not become ready – no tools to announce")

    def _kick_background_start(self) -> None:
        ready, msg = self.backend.ensure(wait=False)
        if ready:
            if not self._announced_ready:
                self._announced_ready = True
                self._notify_tools_changed()
            return
        log(f"backend not up ({msg})")
        if msg in ("starting", "a headless 1cedtcli is already starting – waiting for it"):
            threading.Thread(target=self._announce_when_ready, daemon=True).start()

    # -- dispatch --------------------------------------------------------

    def handle(self, message: dict) -> None:
        method = message.get("method")
        req_id = message.get("id")
        params = message.get("params") or {}

        if method == "initialize":
            proto = params.get("protocolVersion") or PROTOCOL_FALLBACK
            self._result(req_id, {
                "protocolVersion": proto,
                "capabilities": {"tools": {"listChanged": True}},
                "serverInfo": {"name": "edt-bridge-mcp", "version": __version__},
            })
            return
        if method == "notifications/initialized":
            threading.Thread(target=self._kick_background_start, daemon=True).start()
            return
        if method == "ping":
            self._result(req_id, {})
            return
        if method == "tools/list":
            if self.backend.is_up():
                self._forward_tools_list(message, req_id)
                self._announced_ready = True
                return
            self._kick_background_start()
            self._result(req_id, {"tools": self._local_descriptors()})
            return
        if method == "tools/call":
            if params.get("name") in LOCAL_TOOL_NAMES:
                # Before ensure(): this tool ENDS a headless session, and autostarting one
                # first would only give it something new to stop.
                self._call_local(req_id, params.get("name"), params.get("arguments") or {})
                return
            if params.get("name") in self._plugin_tools:
                # Also before ensure(): a plugin tool runs in the wrapper and needs no EDT,
                # so a call must not spend minutes starting one.
                self._call_plugin(req_id, self._plugin_tools[params.get("name")],
                                  params.get("arguments") or {})
                return
            ready, msg = self.backend.ensure(wait=True)
            if not ready:
                self._tool_error(req_id, f"edt-bridge backend is unavailable: {msg}")
                return
            self._forward_or_error(message, req_id)
            return
        if method is None:
            return
        if req_id is None:
            return  # unknown notification – drop
        if self.backend.is_up():
            self._forward_or_error(message, req_id)
        else:
            self._error(req_id, -32601, f"method not available while the backend is down: {method}")

    def _call_local(self, req_id, name: str, arguments: dict) -> None:
        """Run a wrapper-served tool and report as a normal tool result."""
        if name != "edt_open_gui":
            self._tool_error(req_id, f"unknown local tool: {name}")
            return
        misnamed = unknown_arguments(name, arguments)
        if misnamed:
            self._tool_error(req_id, misnamed)
            return
        try:
            seconds = max(1, int(arguments.get("timeoutSeconds")))
        except (TypeError, ValueError):
            seconds = 90
        ok, lines = self.backend.open_gui(force=bool(arguments.get("force")), timeout=seconds)
        text = "\n".join(lines) or ("done" if ok else "nothing happened")
        if not ok:
            self._tool_error(req_id, text)
            return
        self._result(req_id, {"content": [{"type": "text", "text": text}]})
        # The tool set just changed under the client: the bridge went down with the headless
        # EDT and comes back when the GUI one has loaded the plugin.
        self._announced_ready = False
        self._notify_tools_changed()
        threading.Thread(target=self._announce_when_ready, daemon=True).start()

    def _call_plugin(self, req_id, tool: plugins.Tool, arguments: dict) -> None:
        """Run a plugin tool and report as a normal tool result."""
        misnamed = unknown_arguments(tool.name, arguments, tools=[tool.descriptor()])
        if misnamed:
            self._tool_error(req_id, misnamed)
            return
        try:
            if plugins.wants_bridge(tool.handler):
                answer = tool.handler(arguments, bridge=self.backend.call_tool)
            else:
                answer = tool.handler(arguments)
        except Exception as exc:  # a plugin must not take the server loop down
            self._tool_error(req_id, f"{tool.name} failed: {exc}")
            return
        text = answer if isinstance(answer, str) else json.dumps(
            answer, ensure_ascii=False, indent=2)
        self._result(req_id, {"content": [{"type": "text", "text": text}]})

    def _forward_tools_list(self, message: dict, req_id) -> None:
        """Forward tools/list and add the tools the wrapper serves itself.

        The wrapper's own and plugin descriptors win over a bridge tool of the same
        name: the dispatch never forwards those names, so the listing has to match it.
        """
        try:
            reply = self.backend.forward(message)
        except (OSError, ValueError) as exc:
            self._error(req_id, -32000, f"edt-bridge request failed: {exc}")
            return
        result = reply.get("result")
        if isinstance(result, dict) and isinstance(result.get("tools"), list):
            local = self._local_descriptors()
            local_names = {tool["name"] for tool in local}
            shadowed = sorted(tool.get("name") for tool in result["tools"]
                              if tool.get("name") in local_names)
            if shadowed:
                log("bridge tools shadowed by the wrapper or a plugin: " + ", ".join(shadowed))
            result["tools"] = [tool for tool in result["tools"]
                               if tool.get("name") not in local_names] + local
        reply.setdefault("jsonrpc", "2.0")
        reply["id"] = req_id
        self._send(reply)

    def _forward_or_error(self, message: dict, req_id) -> None:
        try:
            reply = self.backend.forward(message)
        except (OSError, ValueError) as exc:
            if message.get("method") == "tools/call":
                self._tool_error(req_id, f"edt-bridge request failed: {exc}")
            else:
                self._error(req_id, -32000, f"edt-bridge request failed: {exc}")
            return
        if req_id is not None:
            reply.setdefault("jsonrpc", "2.0")
            reply["id"] = req_id
            self._send(reply)

    # -- main loop -------------------------------------------------------

    def run(self) -> int:
        for raw in sys.stdin:
            raw = raw.strip()
            if not raw:
                continue
            try:
                message = json.loads(raw)
            except ValueError:
                log(f"dropping a non-JSON line ({len(raw)} chars)")
                continue
            try:
                self.handle(message)
            except Exception as exc:  # keep serving no matter what one request does
                log(f"handler crashed: {exc!r}")
                if message.get("id") is not None:
                    self._error(message.get("id"), -32000, f"internal error: {exc}")
        return 0


def apply_connection_options(args) -> None:
    """Move the shared connection flags into the environment Backend reads at construction."""
    if getattr(args, "workspace", None):
        os.environ["EDT_BRIDGE_WORKSPACE"] = args.workspace
    if getattr(args, "edt_dir", None):
        os.environ["EDT_BRIDGE_EDT_DIR"] = args.edt_dir
    if getattr(args, "port", None):
        os.environ["EDT_BRIDGE_PORT"] = str(args.port)
    if getattr(args, "start_timeout", None):
        os.environ["EDT_BRIDGE_START_TIMEOUT"] = str(args.start_timeout)
    if getattr(args, "no_autostart", False):
        os.environ["EDT_BRIDGE_AUTOSTART"] = "0"


def build_parser() -> argparse.ArgumentParser:
    """The server-mode parser (no command); separate from main so tests can walk it."""
    parser = i18n.ArgumentParser(
        prog="edt-bridge-mcp",
        usage=i18n.t("server.usage"),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=i18n.t("server.description"),
        epilog=i18n.t("server.epilog"),
    )
    cli.add_connection_flags(parser)
    parser.add_argument("--version", action="version", help=i18n.t("version"),
                        version=f"%(prog)s {__version__}")
    return parser


def main() -> int:
    force_utf8_streams()
    if len(sys.argv) > 1 and sys.argv[1] == "self-update":
        from . import update
        return update.run(sys.argv[2:])
    if len(sys.argv) > 1 and sys.argv[1] in cli.COMMANDS:
        return cli.run(sys.argv[1], sys.argv[2:])
    args = build_parser().parse_args()
    apply_connection_options(args)

    backend = Backend()
    log(f"port {backend.port}, autostart {'on' if backend.autostart else 'off'}, "
        f"workspace {backend.workspace or '<unset>'}")
    return StdioServer(backend).run()


if __name__ == "__main__":
    raise SystemExit(main())
