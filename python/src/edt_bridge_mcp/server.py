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
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from . import __version__
from . import cli

PROTOCOL_FALLBACK = "2024-11-05"


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

    def _gui_edt_running(self) -> bool:
        """True when a GUI EDT (1cedt) process exists – we then refuse to launch headless
        (the GUI holds the workspace lock; the user likely just lacks the plugin there)."""
        try:
            if os.name == "nt":
                out = subprocess.run(
                    ["tasklist", "/FI", "IMAGENAME eq 1cedt.exe", "/FO", "CSV", "/NH"],
                    capture_output=True, text=True, timeout=15, check=False,
                ).stdout
                return "1cedt.exe" in out
            out = subprocess.run(
                ["pgrep", "-x", "1cedt"], capture_output=True, text=True, timeout=15, check=False
            ).stdout
            return bool(out.strip())
        except OSError:
            return False

    def _headless_cli_running(self) -> bool:
        try:
            if os.name == "nt":
                out = subprocess.run(
                    ["tasklist", "/FI", "IMAGENAME eq 1cedtcli.exe", "/FO", "CSV", "/NH"],
                    capture_output=True, text=True, timeout=15, check=False,
                ).stdout
                return "1cedtcli.exe" in out
            out = subprocess.run(
                ["pgrep", "-f", "1cedtcli"], capture_output=True, text=True, timeout=15, check=False
            ).stdout
            return bool(out.strip())
        except OSError:
            return False

    def _find_cli(self) -> Path | None:
        exe = "1cedtcli.exe" if os.name == "nt" else "1cedtcli"
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


class StdioServer:
    """Newline-delimited JSON-RPC over stdio; forwards to the Backend."""

    def __init__(self, backend: Backend) -> None:
        self.backend = backend
        self._out_lock = threading.Lock()
        self._announced_ready = False

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
                self._forward_or_error(message, req_id)
                self._announced_ready = True
                return
            self._kick_background_start()
            self._result(req_id, {"tools": []})
            return
        if method == "tools/call":
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


def main() -> int:
    force_utf8_streams()
    if len(sys.argv) > 1 and sys.argv[1] == "self-update":
        from . import update
        return update.run(sys.argv[2:])
    if len(sys.argv) > 1 and sys.argv[1] in cli.COMMANDS:
        return cli.run(sys.argv[1], sys.argv[2:])
    parser = argparse.ArgumentParser(
        prog="edt-bridge-mcp",
        usage="%(prog)s [options]           (no command: run as an MCP server)\n"
              "       %(prog)s <command> [options]",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="stdio MCP front-end for the edt-bridge 1C:EDT plugin: proxies to a running\n"
                    "EDT, or auto-starts a headless one.\n\n"
                    "With no command it speaks JSON-RPC over stdin/stdout - that is how an MCP\n"
                    "client launches it. The commands below drive the same bridge from a shell.",
        epilog="commands:\n"
               "  call <tool>   call one bridge tool and print what it returned\n"
               "  tools         list the tools the running bridge serves\n"
               "  status        report the running bridge (never starts one)\n"
               "  self-update   refresh the plugin jar and this wrapper\n"
               "\n"
               "Run 'edt-bridge-mcp <command> --help' for a command's own options.\n"
               "The options above apply to the MCP-server mode and to every command.",
    )
    parser.add_argument("--workspace", help="EDT workspace path for the headless auto-start")
    parser.add_argument("--edt-dir", help="EDT install dir (.../1cedt); auto-detected when omitted")
    parser.add_argument("--port", type=int, help="bridge port (default 8770)")
    parser.add_argument("--start-timeout", type=int, help="seconds to wait for a starting backend")
    parser.add_argument("--no-autostart", action="store_true", help="never launch a headless EDT")
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    args = parser.parse_args()
    apply_connection_options(args)

    backend = Backend()
    log(f"port {backend.port}, autostart {'on' if backend.autostart else 'off'}, "
        f"workspace {backend.workspace or '<unset>'}")
    return StdioServer(backend).run()


if __name__ == "__main__":
    raise SystemExit(main())
