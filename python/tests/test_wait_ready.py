"""JVM options of the headless auto-start and the wait for the project models (wait-ready)."""
import json
from pathlib import Path
import subprocess
import sys

import pytest

from edt_bridge_mcp import cli, server


# -- JVM options ---------------------------------------------------------------------------------


def test_the_heap_becomes_xmx_and_the_options_follow_it():
    assert server.jvm_options("6g", "-XX:+ExitOnOutOfMemoryError -Xss4m") == [
        "-Xmx6g", "-XX:+ExitOnOutOfMemoryError", "-Xss4m"]
    assert server.jvm_options(None, None) == []
    assert server.jvm_options(None, "   ") == []


@pytest.mark.parametrize("heap", ["4 GB", "0", "-Xmx4g", "4096mb", "g", "4096"])
def test_a_heap_that_is_no_size_is_refused(heap):
    with pytest.raises(ValueError, match="EDT_BRIDGE_HEAP"):
        server.jvm_options(heap, None)


def test_a_windows_path_in_an_option_keeps_its_backslashes(monkeypatch):
    monkeypatch.setattr(server, "_WINDOWS", True)
    options = server.jvm_options(None, r'-XX:+ExitOnOutOfMemoryError "-Xlog:gc:file=C:\ci logs\gc.log"')
    assert options == ["-XX:+ExitOnOutOfMemoryError", r"-Xlog:gc:file=C:\ci logs\gc.log"]


def test_posix_options_are_split_like_a_shell(monkeypatch):
    monkeypatch.setattr(server, "_WINDOWS", False)
    options = server.jvm_options(None, "-Xlog:gc:file='/workspace/runtime logs/gc.log' -Dedt.bridge.headless=1")
    assert options == ["-Xlog:gc:file=/workspace/runtime logs/gc.log", "-Dedt.bridge.headless=1"]


@pytest.mark.parametrize("vmargs, message", [
    ("-Xss4m extra.jar", "is not a JVM option"),
    ("-Dedt.bridge.port=8771", "must not set the bridge port"),
])
def test_options_that_would_break_the_start_are_refused(monkeypatch, vmargs, message):
    monkeypatch.setattr(server, "_WINDOWS", False)
    with pytest.raises(ValueError, match=message):
        server.jvm_options(None, vmargs)


def test_the_auto_start_passes_the_options_after_the_bridge_port(monkeypatch, tmp_path):
    monkeypatch.setenv("EDT_BRIDGE_HEAP", "3g")
    monkeypatch.setenv("EDT_BRIDGE_VMARGS", "-XX:+ExitOnOutOfMemoryError")
    backend = server.Backend()
    backend.workspace = str(tmp_path / "ws")
    backend.port = 19002
    for name, value in (("_headless_cli_running", False), ("_workspace_owner", None),
                        ("_workspace_locked", False), ("_gui_edt_running", False)):
        monkeypatch.setattr(backend, name, lambda value=value: value)
    monkeypatch.setattr(backend, "_find_cli", lambda: tmp_path / "1cedtcli")
    monkeypatch.setattr(backend, "_ensure_plugin_jar", lambda path: (True, "present"))
    launched = []
    monkeypatch.setattr(server.subprocess, "Popen", lambda command, **kwargs: launched.append(command) or object())

    assert backend._launch_headless() == (True, "launched")
    command = launched[0]
    vmargs = command[command.index("-vmargs") + 1:]
    assert vmargs == ["-Dedt.bridge.port=19002", "-Xmx3g", "-XX:+ExitOnOutOfMemoryError"]


def test_a_bad_heap_refuses_the_start_and_launches_nothing(monkeypatch, tmp_path):
    monkeypatch.setenv("EDT_BRIDGE_HEAP", "lots")
    backend = server.Backend()
    backend.workspace = str(tmp_path / "ws")
    for name, value in (("_headless_cli_running", False), ("_workspace_owner", None),
                        ("_workspace_locked", False), ("_gui_edt_running", False)):
        monkeypatch.setattr(backend, name, lambda value=value: value)
    monkeypatch.setattr(backend, "_find_cli", lambda: tmp_path / "1cedtcli")
    monkeypatch.setattr(server.subprocess, "Popen", lambda *args, **kwargs: pytest.fail("nothing may start"))

    ok, message = backend._launch_headless()

    assert not ok and "EDT_BRIDGE_HEAP" in message


def test_the_flags_reach_the_environment_the_backend_reads(monkeypatch):
    monkeypatch.delenv("EDT_BRIDGE_HEAP", raising=False)
    monkeypatch.delenv("EDT_BRIDGE_VMARGS", raising=False)
    args = cli._parse("wait-ready", ["--heap", "5g", "--vmargs=-XX:+ExitOnOutOfMemoryError -Xss4m",
                                     "--project", "Demo", "--project", "Base"])
    server.apply_connection_options(args)
    backend = server.Backend()

    assert (backend.heap, backend.vmargs) == ("5g", "-XX:+ExitOnOutOfMemoryError -Xss4m")
    assert args.project == ["Demo", "Base"]
    assert server.split_command(["--heap", "5g", "wait-ready"]) == ("wait-ready", ["--heap", "5g"])


# -- wait-ready ----------------------------------------------------------------------------------


class FakeBackend:
    """Statuses in turn; the last one repeats. None is a silent port."""

    def __init__(self, statuses, *, started=(True, "up"), alive=True, failure=None):
        self.statuses = list(statuses)
        self.started = started
        self.alive = alive
        self.failure = failure
        self.port = 8770
        self.workspace = None
        self.polls = 0

    def ensure(self, wait):
        assert wait is False, "wait-ready polls by itself; a blocking start waits for openProjects only"
        return self.started

    def status(self):
        self.polls += 1
        return self.statuses.pop(0) if len(self.statuses) > 1 else self.statuses[0]

    def startup_failure(self):
        return self.failure

    def launched_alive(self):
        return False

    def headless_pids(self):
        return [4242] if self.alive else []

    def gui_pids(self):
        return []


def _status(*rows):
    return {"name": "edt-bridge", "version": "0.43.0", "projects": [
        {"name": name, "open": True, "dtProject": True, "modelReady": ready} for name, ready in rows]}


def _wait(backend, *argv, monkeypatch, capsys):
    monkeypatch.setattr(cli.time, "sleep", lambda seconds: None)
    code = cli._wait_ready(backend, cli._parse("wait-ready", list(argv)))
    out, err = capsys.readouterr()
    return code, json.loads(out), err


def test_the_named_projects_are_awaited_until_their_models_are_ready(monkeypatch, capsys):
    backend = FakeBackend([None, _status(("Base", False)), _status(("Base", True), ("Demo", False)),
                           _status(("Base", True), ("Demo", True))])

    code, summary, err = _wait(backend, "--project", "Base", "--project", "Demo",
                               monkeypatch=monkeypatch, capsys=capsys)

    assert code == 0
    assert summary["ready"] is True
    assert summary["projects"] == [{"name": "Base", "modelReady": True}, {"name": "Demo", "modelReady": True}]
    assert "Demo (not in the workspace)" in err
    assert backend.polls == 4


def test_without_names_every_open_edt_project_counts(monkeypatch, capsys):
    plain = {"name": "notes", "open": True, "dtProject": False, "modelReady": False}
    backend = FakeBackend([{"projects": []}, {"projects": [plain, *_status(("Base", True))["projects"]]}])

    code, summary, _ = _wait(backend, monkeypatch=monkeypatch, capsys=capsys)

    assert code == 0
    assert summary["projects"] == [{"name": "Base", "modelReady": True}]


def test_the_deadline_ends_the_wait_with_code_2_and_names_what_is_missing(monkeypatch, capsys):
    clock = iter(range(0, 10_000, 30))
    monkeypatch.setattr(cli.time, "monotonic", lambda: next(clock))
    backend = FakeBackend([_status(("Base", True), ("Demo", False))])

    code, summary, err = _wait(backend, "--project", "Demo", "--timeout", "100",
                               monkeypatch=monkeypatch, capsys=capsys)

    assert code == 2
    assert summary["ready"] is False
    assert "not ready after 100 s: Demo" in err


def test_an_edt_that_exited_ends_the_wait_at_once_with_code_1(monkeypatch, capsys):
    backend = FakeBackend([_status(("Base", False)), None], alive=False)

    code, summary, err = _wait(backend, "--project", "Base", monkeypatch=monkeypatch, capsys=capsys)

    assert code == 1
    assert "EDT is not running" in summary["reason"]
    assert backend.polls == 2


def test_the_exit_of_the_launched_edt_is_reported_with_its_output(monkeypatch, capsys):
    backend = FakeBackend([None], started=(False, "starting"),
                          failure="headless EDT exited with exit code 3 before becoming ready\n"
                                  "Last EDT output:\nInvalid maximum heap size: -Xmx1q")

    code, summary, err = _wait(backend, monkeypatch=monkeypatch, capsys=capsys)

    assert code == 1
    assert "Invalid maximum heap size" in err


def test_a_start_that_cannot_happen_is_refused_without_polling(monkeypatch, capsys):
    backend = FakeBackend([None], started=(False, "bridge is not running and autostart is disabled"))
    monkeypatch.setattr(cli.time, "sleep", lambda seconds: pytest.fail("nothing to wait for"))

    code = cli._wait_ready(backend, cli._parse("wait-ready", []))

    assert code == 1
    assert backend.polls == 0
    assert "autostart is disabled" in capsys.readouterr().err


def test_a_bridge_without_readiness_rows_is_refused(monkeypatch, capsys):
    backend = FakeBackend([{"name": "edt-bridge", "version": "0.20.0", "openProjects": ["Base"]}])

    code, summary, err = _wait(backend, "--project", "Base", monkeypatch=monkeypatch, capsys=capsys)

    assert code == 1
    assert "does not report per-project readiness" in err


def test_the_command_is_reachable_from_the_shell(tmp_path):
    # --no-autostart and a port nobody listens on: nothing runs, so the command says so with code 1.
    done = subprocess.run([sys.executable, "-c", "import sys; from edt_bridge_mcp.server import main; "
                           "sys.argv[0] = 'edt-bridge-mcp'; sys.exit(main())",
                           "wait-ready", "--no-autostart", "--port", "1", "--timeout", "1"],
                          capture_output=True, text=True, encoding="utf-8", timeout=120,
                          cwd=tmp_path, env={**__import__("os").environ,
                                             "PYTHONPATH": str(Path(cli.__file__).parents[1]),
                                             "EDT_BRIDGE_PORT_SCAN": "0"})
    assert done.returncode == 1, done.stdout + done.stderr
    assert "autostart is disabled" in done.stderr
