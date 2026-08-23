"""Handing the workspace from a headless EDT to the GUI one.

The manual routine this replaces is: find 1cedtcli in the task manager, kill it, start EDT. So the
properties that matter here are the ones that routine gets wrong. The port going quiet is NOT the
end of a headless session - the runtime is still there holding the workspace lock, and that is the
process a person hunts. Nothing may auto-start a headless EDT on the way, least of all the tool
whose whole job is to end one. And the tool has to be served by the WRAPPER: a tool living inside
EDT cannot report on the EDT it just stopped.
"""
import pytest

from edt_bridge_mcp import cli, server


@pytest.fixture(autouse=True)
def _clean_environment(monkeypatch):
    for name in ("EDT_BRIDGE_PORT", "EDT_BRIDGE_TOKEN", "EDT_BRIDGE_AUTOSTART",
                 "EDT_BRIDGE_WORKSPACE", "EDT_BRIDGE_EDT_DIR", "EDT_BRIDGE_START_TIMEOUT"):
        monkeypatch.delenv(name, raising=False)


class _Edt:
    """A fake installation: which processes live, and what stopping them does."""

    def __init__(self, gui=(), headless=(), keepalive=(), bridge=True,
                 dies_on_shutdown=False, dies_on_keepalive=True):
        self.gui = list(gui)
        self.headless = list(headless)
        self.keepalive = list(keepalive)
        self.bridge = bridge
        # The live default: /shutdown stops the framework, the CLI stays because its stdin pipe
        # is still open, and it exits when the keepalive that holds that pipe is closed.
        self.dies_on_shutdown = dies_on_shutdown
        self.dies_on_keepalive = dies_on_keepalive
        self.clock = 0.0
        self.shutdowns = []
        self.killed = []
        self.launched = []

    def install(self, backend, monkeypatch):
        monkeypatch.setattr(backend, "gui_pids", lambda: list(self.gui))
        monkeypatch.setattr(backend, "headless_pids", lambda: list(self.headless))
        monkeypatch.setattr(backend, "status", lambda: {"port": 8770} if self.bridge else None)
        monkeypatch.setattr(backend, "shutdown", self._shutdown)
        # Not merely a stub: unpatched, this one reads the REAL process table of the machine
        # running the tests - the first run of the force test listed a live keepalive shell.
        monkeypatch.setattr(backend, "_keepalive_pids", lambda: list(self.keepalive))
        monkeypatch.setattr(backend, "_kill", self._kill)
        monkeypatch.setattr(backend, "launch_gui", self._launch)
        # An artificial clock: the waits are bounded by monotonic(), so a no-op sleep would
        # spin on the real one for the whole timeout (the first run took 90 seconds).
        monkeypatch.setattr(server.time, "sleep", self._sleep)
        monkeypatch.setattr(server.time, "monotonic", lambda: self.clock)
        return self

    def _sleep(self, seconds):
        self.clock += seconds

    def _shutdown(self, force=False):
        """/shutdown stops the framework - the PORT. Whether the process follows is a separate
        question, and on a keepalive-started session it does not (see dies_on_shutdown)."""
        self.shutdowns.append(force)
        self.bridge = False
        if self.dies_on_shutdown:
            self.headless = []
        return {"message": "shutting down"}

    def _kill(self, pid):
        self.killed.append(pid)
        if pid in self.keepalive:
            self.keepalive = [p for p in self.keepalive if p != pid]
            if self.dies_on_keepalive:
                self.headless = []  # EOF on stdin - the CLI ends by itself
            return
        self.headless = [p for p in self.headless if p != pid]

    def _launch(self, report=None):
        self.launched.append(True)
        self.gui = [4242]
        return True, "the GUI EDT is starting (pid 4242)"


def test_a_running_gui_is_brought_to_the_front():
    """Not "nothing to do": the workbench is launched without foreground rights and comes up
    BEHIND everything else - which looks exactly like it never started. Running the command
    again is how a person asks for that window, so it raises it instead of shrugging."""
    backend = server.Backend()
    with pytest.MonkeyPatch.context() as patch:
        edt = _Edt(gui=[100], headless=[], bridge=False).install(backend, patch)
        patch.setattr(backend, "raise_gui_window", lambda wait=0: True)
        ok, lines = backend.open_gui()
    assert ok
    assert not edt.launched
    assert "brought to the front" in "\n".join(lines)


def test_a_gui_that_has_no_window_yet_says_so():
    backend = server.Backend()
    with pytest.MonkeyPatch.context() as patch:
        _Edt(gui=[100], headless=[], bridge=False).install(backend, patch)
        patch.setattr(backend, "raise_gui_window", lambda wait=0: False)
        ok, lines = backend.open_gui()
    assert ok and "still loading" in "\n".join(lines)


def test_the_window_is_looked_for_across_the_whole_process_tree(monkeypatch):
    """The window belongs to the javaw the launcher starts, and that javaw runs from whatever
    JDK the installation resolved - on this machine one outside the EDT folder entirely. The
    first attempt matched by the installation path and found nothing while the window was open."""
    backend = server.Backend()
    monkeypatch.setattr(backend, "gui_pids", lambda: [14648])
    monkeypatch.setattr(backend, "_parents", lambda: {17036: 14648, 22012: 17036, 999: 1})
    assert backend._gui_family() == {14648, 17036, 22012}


def test_a_parent_loop_does_not_spin(monkeypatch):
    backend = server.Backend()
    monkeypatch.setattr(backend, "gui_pids", lambda: [10])
    monkeypatch.setattr(backend, "_parents", lambda: {10: 11, 11: 10})
    assert backend._gui_family() == {10, 11}


def test_headless_is_stopped_before_the_gui_starts():
    """The graceful path in full, as it really goes: /shutdown quiets the port, closing the
    keepalive frees the CLI's stdin, the CLI ends by itself - no force anywhere."""
    backend = server.Backend()
    with pytest.MonkeyPatch.context() as patch:
        edt = _Edt(headless=[17276, 18448], keepalive=[27796]).install(backend, patch)
        ok, lines = backend.open_gui()
    assert ok
    assert edt.shutdowns == [False]
    assert edt.killed == [27796]  # the keepalive only - EDT stopped itself
    assert edt.launched
    assert "the headless EDT is down" in lines


def test_shutdown_alone_does_not_end_a_keepalive_started_session():
    """Proven live: the port falls silent and 1cedtcli keeps running, because it was started
    with a keepalive pipe on stdin. Waiting on the port would report success over a session
    that is still holding the workspace."""
    backend = server.Backend()
    with pytest.MonkeyPatch.context() as patch:
        edt = _Edt(headless=[17276], keepalive=[27796],
                   dies_on_keepalive=False).install(backend, patch)
        stopped, survivors = backend.stop_headless(timeout=0)
    assert edt.shutdowns == [False]
    assert not edt.bridge  # the port is down...
    assert not stopped and survivors == [17276]  # ...and the session is not


def test_a_surviving_process_is_reported_and_the_gui_does_not_start():
    """The port is quiet, the runtime is not: the exact case that sends a person to the task
    manager. Without --force the command says which pids are left rather than kill them."""
    backend = server.Backend()
    with pytest.MonkeyPatch.context() as patch:
        edt = _Edt(headless=[18448], keepalive=[27796],
                   dies_on_keepalive=False).install(backend, patch)
        ok, lines = backend.open_gui(timeout=0)
    assert not ok
    assert not edt.launched
    assert edt.killed == [27796]  # the keepalive is closed on the graceful path, the CLI is not
    assert "18448" in "\n".join(lines) and "--force" in "\n".join(lines)


def test_force_kills_the_survivors_and_then_starts_the_gui():
    """The keepalive shell goes first - it is the CLI's parent, so a tree kill from below
    never reaches it, and while it lives it holds the dropins jar."""
    backend = server.Backend()
    with pytest.MonkeyPatch.context() as patch:
        edt = _Edt(headless=[17276, 18448], keepalive=[27796],
                   dies_on_keepalive=False).install(backend, patch)
        ok, _lines = backend.open_gui(force=True, timeout=0)
    assert ok
    assert edt.killed == [27796, 17276, 18448]  # keepalive first, then what survived it
    assert edt.launched


def test_a_stale_workspace_lock_is_removed(tmp_path, monkeypatch):
    """A killed session leaves .metadata/.lock behind, and EDT then refuses the workspace."""
    lock = tmp_path / ".metadata" / ".lock"
    lock.parent.mkdir()
    lock.write_bytes(b"")
    monkeypatch.setenv("EDT_BRIDGE_WORKSPACE", str(tmp_path))
    backend = server.Backend()
    _Edt(headless=[17276], keepalive=[27796]).install(backend, monkeypatch)
    ok, _lines = backend.open_gui()
    assert ok
    assert not lock.exists()


# -- reading the process table ------------------------------------------------


def _tasklist(monkeypatch, payload: bytes):
    """Substitute tasklist with a canned answer - in BYTES, as the real one speaks."""
    monkeypatch.setattr(server, "_WINDOWS", True)
    monkeypatch.setattr(server.os, "name", "nt")

    class _Done:
        stdout = payload

    monkeypatch.setattr(server.subprocess, "run", lambda *a, **k: _Done())


def test_a_localized_console_does_not_hide_the_processes(monkeypatch):
    """The defect a live run exposed: tasklist answers in the OEM codepage, and decoding it as
    UTF-8 blows up in the reader thread and yields an EMPTY listing - which every caller then
    read as "no such process". A headless EDT was running at the time."""
    payload = '"1cedtcli.exe","17276","Консоль","1","1 500 000 КБ"\r\n'.encode("cp866")
    _tasklist(monkeypatch, payload)
    assert server.Backend()._pids_of("1cedtcli.exe") == [17276]


def test_nothing_running_reads_as_an_empty_list(monkeypatch):
    """A miss prints a localized info line, not a CSV row."""
    _tasklist(monkeypatch, "ИНФОРМАЦИЯ: нет задач, соответствующих условиям.\r\n".encode("cp866"))
    assert server.Backend()._pids_of("1cedtcli.exe") == []


# -- the MCP surface ----------------------------------------------------------


class _Recorder(server.StdioServer):
    """A stdio server that collects frames instead of writing them to stdout."""

    def __init__(self, backend):
        super().__init__(backend)
        self.sent = []

    def _send(self, message):
        self.sent.append(message)


def test_tools_list_offers_the_wrapper_tool_next_to_the_bridge_tools(monkeypatch):
    backend = server.Backend()
    monkeypatch.setattr(backend, "status", lambda: {"port": 8770})
    monkeypatch.setattr(backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [{"name": "edt_projects"}]}})
    stdio = _Recorder(backend)
    stdio.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
    names = [tool["name"] for tool in stdio.sent[0]["result"]["tools"]]
    assert names == ["edt_projects", "edt_open_gui"]


def test_the_wrapper_tool_is_offered_even_with_no_bridge_up(monkeypatch):
    """Without it the answer is an empty tool list - and opening the GUI is exactly what one
    wants when nothing is running."""
    backend = server.Backend()
    monkeypatch.setattr(backend, "status", lambda: None)
    stdio = _Recorder(backend)
    monkeypatch.setattr(stdio, "_kick_background_start", lambda: None)
    stdio.handle({"jsonrpc": "2.0", "id": 7, "method": "tools/list", "params": {}})
    assert [tool["name"] for tool in stdio.sent[0]["result"]["tools"]] == ["edt_open_gui"]


def test_calling_the_wrapper_tool_never_starts_a_headless_edt(monkeypatch):
    """ensure() is the autostart path: reaching it here would launch the very session the call
    is meant to end."""
    backend = server.Backend()
    calls = {}

    def _forbidden(**_kwargs):
        calls["ensure"] = True
        return True, "up"

    monkeypatch.setattr(backend, "ensure", _forbidden)
    monkeypatch.setattr(backend, "open_gui", lambda force, timeout: (True, [f"ok {force} {timeout}"]))
    stdio = _Recorder(backend)
    monkeypatch.setattr(stdio, "_announce_when_ready", lambda: None)
    stdio.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                  "params": {"name": "edt_open_gui", "arguments": {"force": True,
                                                                   "timeoutSeconds": 30}}})
    assert "ensure" not in calls
    result = stdio.sent[0]["result"]
    assert result["content"][0]["text"] == "ok True 30"
    assert not result.get("isError")
    # The client has to re-read the tool list: the bridge went down with the EDT it served.
    assert any(frame.get("method") == "notifications/tools/list_changed" for frame in stdio.sent)


def test_a_failed_switch_comes_back_as_a_tool_error(monkeypatch):
    backend = server.Backend()
    monkeypatch.setattr(backend, "open_gui", lambda force, timeout: (False, ["still alive"]))
    stdio = _Recorder(backend)
    stdio.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                  "params": {"name": "edt_open_gui", "arguments": {}}})
    assert stdio.sent[0]["result"]["isError"]
    assert "still alive" in stdio.sent[0]["result"]["content"][0]["text"]


# -- the shell command --------------------------------------------------------


def test_the_gui_command_takes_force_and_timeout():
    args = cli._parse("gui", ["--force", "--timeout", "45"])
    assert args.force and args.timeout == 45
    assert cli._parse("gui", []).timeout == 90


def test_an_argument_the_schema_does_not_know_refuses_the_call(monkeypatch):
    """A dropped name reads as success for a call that did something else: the bridge refuses such
    a name for its own tools, and the wrapper has to do the same for the one it serves itself."""
    backend = server.Backend()
    opened = {}
    monkeypatch.setattr(backend, "open_gui",
                        lambda force, timeout: opened.setdefault("called", True) or (True, ["ok"]))
    stdio = _Recorder(backend)
    stdio.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                  "params": {"name": "edt_open_gui", "arguments": {"timeoutSecond": 30}}})
    result = stdio.sent[0]["result"]
    assert result["isError"] is True
    text = result["content"][0]["text"]
    assert "'timeoutSecond'" in text
    assert "did you mean 'timeoutSeconds'" in text
    assert "force, timeoutSeconds" in text
    assert "called" not in opened


def test_the_names_the_schema_knows_are_let_through():
    assert server.unknown_arguments("edt_open_gui", {"force": True, "timeoutSeconds": 5}) is None
    assert server.unknown_arguments("edt_open_gui", {}) is None
    assert server.unknown_arguments("edt_projects", {"whatever": 1}) is None


class _Silent:
    """A backend whose port answers nothing while the headless processes are still there."""

    def __init__(self, survivors):
        self.survivors = list(survivors)
        self.asked = []

    def status(self):
        return None

    def headless_pids(self):
        return list(self.survivors)

    def stop_headless(self, force=False, report=None):
        self.asked.append(force)
        if force:
            self.survivors = []
            return True, []
        return False, list(self.survivors)


def test_a_silent_port_with_a_live_session_is_not_reported_as_nothing_to_do(capsys):
    """The CLI outlives the framework it hosted and keeps the workspace lock; answering
    "nothing to shut down" leaves the next start failing on a lock nobody is looking at."""
    backend = _Silent([4242])
    code = cli._shutdown(backend, cli._parse("shutdown", ["--force"]))
    assert code == 0
    assert backend.asked == [True]
    assert "4242" in capsys.readouterr().out


def test_without_force_the_survivors_are_named_and_the_call_fails(capsys):
    backend = _Silent([4242])
    code = cli._shutdown(backend, cli._parse("shutdown", []))
    assert code == 2
    assert "--force" in capsys.readouterr().err


def test_a_silent_port_with_no_session_is_nothing_to_do(capsys):
    backend = _Silent([])
    assert cli._shutdown(backend, cli._parse("shutdown", [])) == 0
    assert "nothing to shut down" in capsys.readouterr().out
