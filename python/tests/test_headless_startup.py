"""Exercise auto-start with real child processes, without launching EDT."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import pytest

from edt_bridge_mcp import server


@pytest.fixture
def launcher(tmp_path, monkeypatch):
    script = tmp_path / "fake_cli.py"
    backend = server.Backend()
    backend.workspace = str(tmp_path / "workspace with spaces")
    backend.port = 19001
    backend.start_timeout = 4
    backend.autostart = True
    monkeypatch.setattr(backend, "is_up", lambda: False)
    monkeypatch.setattr(backend, "is_ready", lambda: False)
    monkeypatch.setattr(backend, "_headless_cli_running", lambda: False)
    monkeypatch.setattr(backend, "_workspace_owner", lambda: None)
    monkeypatch.setattr(backend, "_workspace_locked", lambda: False)
    monkeypatch.setattr(backend, "_gui_edt_running", lambda: False)
    monkeypatch.setattr(backend, "_find_cli", lambda: Path(sys.executable))
    monkeypatch.setattr(backend, "_ensure_plugin_jar", lambda path: (True, "present"))
    real_popen = subprocess.Popen
    processes = []

    def launch(args, **kwargs):
        args = list(args)
        if "--" in args:
            args.insert(args.index("--") + 2, str(script))
        else:  # The previous shell pipeline has no exit supervision.
            args = [sys.executable, str(script)]
        process = real_popen(args, **kwargs)
        processes.append(process)
        return process

    monkeypatch.setattr(server.subprocess, "Popen", launch)
    yield backend, script, processes
    for process in processes:
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=10)


@pytest.mark.parametrize("code", [0, 23])
def test_an_exited_cli_reports_its_code_and_output_without_waiting_for_timeout(launcher, code):
    backend, script, processes = launcher
    script.write_text("import os,sys\nos.write(1,b'x'*100000)\n"
                      "os.write(2,'JDK missing: проверка'.encode('utf-8'))\n"
                      f"sys.exit({code})\n", encoding="utf-8")
    started = time.monotonic()
    ok, message = backend.ensure(wait=True)
    assert not ok
    assert f"exit code {code}" in message
    assert "JDK missing: проверка" in message
    assert len(message) < 20000
    assert time.monotonic() - started < backend.start_timeout
    assert backend._starting is False
    processes[0].wait(timeout=5)


def test_a_failed_start_can_be_retried_and_a_healthy_cli_keeps_stdin_open(launcher, tmp_path, monkeypatch):
    backend, script, processes = launcher
    script.write_text("import sys\nprint('bad JDK',flush=True)\nsys.exit(12)\n", encoding="utf-8")
    assert "exit code 12" in backend.ensure(wait=True)[1]
    ready = tmp_path / "ready.json"
    script.write_text("import json,os,sys\nfrom pathlib import Path\n"
                      "assert sys.stdin.readline().strip()=='version'\n"
                      f"Path({str(ready)!r}).write_text(json.dumps([sys.argv,os.environ.get('EDT_BRIDGE_TOKEN')]))\n"
                      "sys.stdin.read()\n", encoding="utf-8")
    backend.token = "fixture-token"
    monkeypatch.setattr(backend, "is_ready", ready.exists)
    assert backend.ensure(wait=True) == (True, "started")
    assert len(processes) == 2
    args, token = json.loads(ready.read_text())
    assert args[args.index("-data") + 1] == backend.workspace
    assert "-Dedt.bridge.port=19001" in args
    assert token == "fixture-token"
    assert processes[-1].poll() is None


def test_a_live_cli_reaches_the_real_timeout_and_is_not_relaunched(launcher):
    backend, script, processes = launcher
    backend.start_timeout = 1
    script.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")
    ok, message = backend.ensure(wait=True)
    assert not ok and "did not become ready" in message
    assert processes[0].poll() is None
    assert backend.ensure(wait=False) == (False, "starting")
    assert len(processes) == 1


def test_a_background_start_reports_early_failure_on_the_next_wait(launcher):
    backend, script, processes = launcher
    script.write_text("import sys\nprint('launcher stopped',flush=True)\nsys.exit(7)\n", encoding="utf-8")
    assert backend.ensure(wait=False) == (False, "starting")
    processes[0].wait(timeout=10)
    ok, message = backend.ensure(wait=True)
    assert not ok and "exit code 7" in message and "launcher stopped" in message
    assert len(processes) == 1
    assert backend._starting is False



def test_the_supervisor_keeps_the_cli_alive_after_its_original_caller_exits(tmp_path):
    helper = Path(server.__file__).with_name("headless.py")
    state, output = tmp_path / "state.json", tmp_path / "output.log"
    alive, stopped = tmp_path / "alive", tmp_path / "stopped"
    child = tmp_path / "child.py"
    child.write_text("import sys\nfrom pathlib import Path\n"
                     "assert sys.stdin.readline().strip()=='version'\n"
                     f"Path({str(alive)!r}).touch()\nsys.stdin.read()\n"
                     f"Path({str(stopped)!r}).touch()\n", encoding="utf-8")
    command = [sys.executable, str(helper), "--state", str(state), "--output", str(output),
               "--", sys.executable, str(child)]
    parent = "import json,os,subprocess,sys\n" + (
        "p=subprocess.Popen(json.loads(sys.argv[1]),stdin=subprocess.DEVNULL,"
        "stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,"
        "**({'creationflags':subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP}"
        " if os.name=='nt' else {'start_new_session':True}))\nprint(p.pid)\n"
    )
    result = subprocess.run([sys.executable, "-c", parent, json.dumps(command)],
                            capture_output=True, text=True, encoding="utf-8", timeout=10, check=True,
                            stdin=subprocess.DEVNULL)
    pid = int(result.stdout.strip())
    try:
        deadline = time.monotonic() + 8
        while not alive.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        assert alive.exists(), "the detached CLI must start even after its caller exits"
        time.sleep(0.1)
        assert not stopped.exists(), "the supervisor must keep the CLI stdin open"
        assert "exitCode" not in json.loads(state.read_text(encoding="utf-8"))
    finally:
        os.kill(pid, 15)
    deadline = time.monotonic() + 5
    while not stopped.exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    assert stopped.exists(), "ending the supervisor must close the CLI stdin"


def test_a_missing_cli_is_reported_without_an_unhandled_supervisor_error(tmp_path):
    from edt_bridge_mcp import headless
    state, output = tmp_path / "state.json", tmp_path / "output.log"
    assert headless.supervise([str(tmp_path / "missing-cli")], state, output) == 1
    assert json.loads(state.read_text(encoding="utf-8"))["error"]



def test_a_failed_background_start_is_reported_and_can_be_restarted_without_waiting(launcher):
    backend, script, processes = launcher
    script.write_text("import sys\nsys.exit(23)\n", encoding="utf-8")
    assert backend.ensure(wait=False) == (False, "starting")
    processes[0].wait(timeout=10)
    ok, message = backend.ensure(wait=False)
    assert not ok and "exit code 23" in message
    assert backend._starting is False
    script.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")
    assert backend.ensure(wait=False) == (False, "starting")
    assert len(processes) == 2


def test_child_exit_code_wins_when_the_supervisor_exits_during_a_status_probe(tmp_path):
    from types import SimpleNamespace
    backend = server.Backend()
    backend._headless_state = tmp_path / "state.json"
    backend._headless_output = tmp_path / "output.log"
    backend._headless_state.write_text('{"pid": 123}', encoding="utf-8")

    def poll():
        backend._headless_state.write_text('{"pid": 123, "exitCode": -9}', encoding="utf-8")
        return 247  # POSIX encodes the supervisor's SystemExit(-9) as 247.

    backend._headless_process = SimpleNamespace(poll=poll)
    message = backend._startup_failure()
    assert "exit code -9" in message
    assert "247" not in message


@pytest.mark.skipif(os.name != "nt", reason="Windows prevents replacing a concurrently opened file")
def test_final_state_survives_a_concurrent_windows_reader(launcher, tmp_path):
    backend, script, processes = launcher
    gate = tmp_path / "exit-now"
    script.write_text("import sys,time\nfrom pathlib import Path\n"
                      f"while not Path({str(gate)!r}).exists(): time.sleep(0.01)\n"
                      "sys.exit(23)\n", encoding="utf-8")
    assert backend.ensure(wait=False) == (False, "starting")
    deadline = time.monotonic() + 8
    while not backend._headless_state.exists() and time.monotonic() < deadline:
        time.sleep(0.02)
    with backend._headless_state.open("r", encoding="utf-8"):
        gate.touch()
        time.sleep(0.3)
    assert processes[0].wait(timeout=8) == 23
    assert json.loads(backend._headless_state.read_text(encoding="utf-8"))["exitCode"] == 23



def test_transient_state_sharing_errors_do_not_replace_the_child_exit(tmp_path, monkeypatch):
    import signal
    from edt_bridge_mcp import headless
    state, output = tmp_path / "state.json", tmp_path / "output.log"
    original_replace = Path.replace
    denied = [False, False]

    def replace(staged, destination):
        if Path(destination) == state and "exitCode" in json.loads(staged.read_text(encoding="utf-8")) and denied:
            denied.pop()
            raise PermissionError("a concurrent Windows reader prevents replacement")
        return original_replace(staged, destination)

    monkeypatch.setattr(Path, "replace", replace)
    previous = signal.getsignal(signal.SIGTERM)
    try:
        assert headless.supervise([sys.executable, "-c", "raise SystemExit(23)"], state, output) == 23
    finally:
        signal.signal(signal.SIGTERM, previous)
    assert json.loads(state.read_text(encoding="utf-8"))["exitCode"] == 23
