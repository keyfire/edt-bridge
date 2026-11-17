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


# -- one headless EDT per workspace, and a start that will not serve ---------------------


def _supervisor(tmp_path, lock, child, name="first"):
    helper = Path(server.__file__).with_name("headless.py")
    state, output = tmp_path / f"{name}.json", tmp_path / f"{name}.log"
    return [sys.executable, str(helper), "--state", str(state), "--output", str(output),
            "--lock", str(lock), "--", sys.executable, str(child)], state


def _until(condition, seconds=10):
    deadline = time.monotonic() + seconds
    while not condition() and time.monotonic() < deadline:
        time.sleep(0.05)
    return condition()


def test_the_supervisor_holds_the_workspace_lock_while_it_lives(tmp_path):
    from edt_bridge_mcp import headless

    lock, child = tmp_path / "workspace.lock", tmp_path / "child.py"
    child.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")
    command, _ = _supervisor(tmp_path, lock, child)
    supervisor = subprocess.Popen(command, stdin=subprocess.DEVNULL)
    try:
        assert _until(lambda: headless.lock_held(lock))
    finally:
        supervisor.terminate()
        supervisor.wait(timeout=10)
    assert _until(lambda: not headless.lock_held(lock))


def test_a_second_supervisor_for_the_workspace_never_starts_its_cli(tmp_path):
    from edt_bridge_mcp import headless

    lock, child, started = tmp_path / "workspace.lock", tmp_path / "child.py", tmp_path / "started"
    child.write_text(f"import sys\nfrom pathlib import Path\nPath({str(started)!r}).touch()\n"
                     "sys.stdin.read()\n", encoding="utf-8")
    first_command, _ = _supervisor(tmp_path, lock, child)
    first = subprocess.Popen(first_command, stdin=subprocess.DEVNULL)
    try:
        assert _until(lambda: headless.lock_held(lock) and started.exists())
        started.unlink()
        second_command, state = _supervisor(tmp_path, lock, child, "second")
        result = subprocess.run(second_command, stdin=subprocess.DEVNULL, timeout=30)
        assert result.returncode == 1
        assert "already running" in json.loads(state.read_text(encoding="utf-8"))["error"]
        assert not started.exists()
    finally:
        first.terminate()
        first.wait(timeout=10)


def test_a_running_session_is_waited_for_even_when_the_process_list_comes_back_empty(launcher):
    """The process list of Windows comes from PowerShell, and a query that runs out of its 30 s
    reads as no EDT at all. The lock of the supervisor still says the workspace is taken."""
    backend, script, processes = launcher
    script.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")
    assert backend.ensure(wait=False) == (False, "starting")

    other = server.Backend()
    other.workspace = backend.workspace
    other._edt_processes = lambda: []
    ok, message = other._launch_headless()

    assert ok and "already running" in message
    assert len(processes) == 1


@pytest.mark.parametrize("different", ["port", "installation"])
def test_a_locked_workspace_with_an_incompatible_owner_is_reported(launcher, tmp_path, different):
    backend, script, processes = launcher
    script.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")
    assert backend.ensure(wait=False) == (False, "starting")

    other = server.Backend()
    other.workspace = backend.workspace
    other.port = backend.port if different == "installation" else backend.port + 1
    other.edt_dir = str(tmp_path / ("other" if different == "installation" else "install"))
    executable = tmp_path / "install" / server.CLI_IMAGE
    other._edt_processes = lambda: [{
        "ExecutablePath": str(executable), "ProcessId": 123,
        "CommandLine": f'"{executable}" -data "{backend.workspace}" -Dedt.bridge.port={backend.port}',
    }]

    ok, message = other._launch_headless()

    assert not ok and "workspace" in message and "123" in message
    assert len(processes) == 1


def test_a_waiting_wrapper_can_start_after_the_other_supervisor_exits(launcher, monkeypatch):
    backend, script, processes = launcher
    script.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")
    assert backend.ensure(wait=False) == (False, "starting")

    other = server.Backend()
    other.workspace, other.port = backend.workspace, backend.port
    other.autostart = True
    monkeypatch.setattr(other, "is_up", lambda: False)
    monkeypatch.setattr(other, "_edt_processes", lambda: [])
    monkeypatch.setattr(other, "_find_cli", lambda: Path(sys.executable))
    monkeypatch.setattr(other, "_ensure_plugin_jar", lambda path: (True, "present"))
    assert other.ensure(wait=False) == (False, "starting")
    assert len(processes) == 1

    processes[0].terminate()
    processes[0].wait(timeout=10)
    # Windows drops the locks of a dead process a moment after it is gone (LockFile: "the time
    # it takes ... depends upon available system resources"), here 1-17 ms after wait(). Asked
    # at once, the second wrapper still saw the workspace taken and failed most runs.
    from edt_bridge_mcp import headless

    assert _until(lambda: not headless.lock_held(other._session_lock()))
    assert other.ensure(wait=False) == (False, "starting")
    assert len(processes) == 2


_DENIED = ("java.io.FileNotFoundException: C:\\Program Files\\1C\\1CE\\components\\"
           "1c-edt-2026.2.2+3-x86_64\\dropins\\io.github.keyfire.edtbridge_0.47.1.202611151051.jar"
           " (Отказано в доступе)\n")


def test_a_plugin_that_cannot_read_its_jar_ends_the_wait_and_the_start(launcher):
    """EDT comes up without the bridge and nothing exits; the workspace log names the cause
    seconds after the start. The wrapper used to wait the whole start timeout and then advise
    to wait longer, while the headless EDT went on holding the workspace."""
    backend, script, processes = launcher
    backend.start_timeout = 60
    log_file = Path(backend.workspace) / ".metadata" / ".log"
    script.write_text("import sys\nfrom pathlib import Path\n"
                      "assert sys.stdin.readline().strip()=='version'\n"
                      f"log=Path({str(log_file)!r})\nlog.parent.mkdir(parents=True,exist_ok=True)\n"
                      f"log.write_text({_DENIED!r},encoding='utf-8')\nsys.stdin.read()\n",
                      encoding="utf-8")
    started = time.monotonic()

    ok, message = backend.ensure(wait=True)

    assert not ok
    assert "cannot read the plugin jar" in message and "Отказано в доступе" in message
    assert "io.github.keyfire.edtbridge_0.47.1.202611151051.jar" in message
    assert ("icacls" in message) == (os.name == "nt")
    assert "stopped the headless EDT" in message
    assert time.monotonic() - started < 30
    assert processes[0].wait(timeout=10) is not None
    assert backend._starting is False


def test_a_failure_logged_before_the_start_is_not_taken_for_this_one(launcher):
    backend, script, processes = launcher
    backend.start_timeout = 2
    log_file = Path(backend.workspace) / ".metadata" / ".log"
    log_file.parent.mkdir(parents=True)
    log_file.write_text(_DENIED, encoding="utf-8")
    script.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")

    ok, message = backend.ensure(wait=True)

    assert not ok and "cannot read" not in message and "has not opened yet" in message
    assert processes[0].poll() is None


def test_a_port_still_closed_after_the_console_answered_points_at_the_plugin(launcher):
    backend, script, processes = launcher
    backend.start_timeout = 3
    script.write_text("import sys\nassert sys.stdin.readline().strip()=='version'\n"
                      "print('1C:EDT> 2026.2.2.3',flush=True)\nsys.stdin.read()\n", encoding="utf-8")

    ok, message = backend.ensure(wait=True)

    assert not ok
    assert "never opened" in message and "plugin is not running" in message
    assert "first model load" not in message
    assert f"supervisor pid {processes[0].pid}" in message and "shutdown --force" in message
    assert processes[0].poll() is None


def test_a_bridge_that_answers_but_still_loads_keeps_the_advice_to_wait(launcher, monkeypatch):
    backend, script, _ = launcher
    backend.start_timeout = 2
    answers = iter([False])
    monkeypatch.setattr(backend, "is_up", lambda: next(answers, True))
    script.write_text("import sys\nsys.stdin.read()\n", encoding="utf-8")

    ok, message = backend.ensure(wait=True)

    assert not ok and "first model load" in message


def _unreadable(path):
    if os.name == "nt":
        subprocess.run(["icacls", str(path), "/deny", "*S-1-1-0:(R)"], capture_output=True,
                       check=True, stdin=subprocess.DEVNULL)
    else:
        path.chmod(0)


@pytest.mark.skipif(os.name != "nt" and os.geteuid() == 0, reason="root reads any file")
def test_an_unreadable_jar_is_named_before_edt_is_started(tmp_path):
    jar = tmp_path / "dropins" / "io.github.keyfire.edtbridge_0.47.2.202611151649.jar"
    jar.parent.mkdir()
    jar.write_bytes(b"jar")
    _unreadable(jar)
    try:
        ok, message = server.Backend()._ensure_plugin_jar(tmp_path)
    finally:
        if os.name == "nt":
            subprocess.run(["icacls", str(jar), "/reset"], capture_output=True,
                           stdin=subprocess.DEVNULL)
        else:
            jar.chmod(0o644)

    assert ok is False
    assert f"cannot read the plugin jar {jar}" in message
    assert ("icacls" in message) == (os.name == "nt")


def test_a_headless_start_keeps_the_existing_jar_when_the_newest_is_unreadable(tmp_path, monkeypatch):
    dropins = tmp_path / "dropins"
    dropins.mkdir()
    old = dropins / "io.github.keyfire.edtbridge_0.47.1.202611151051.jar"
    new = dropins / "io.github.keyfire.edtbridge_0.47.2.202611151649.jar"
    old.write_bytes(b"working jar")
    new.write_bytes(b"unreadable jar")

    def deny_read(path, mode):
        if path == new:
            raise PermissionError(13, "Permission denied")
        return open(path, mode)

    monkeypatch.setattr(server, "open", deny_read, raising=False)

    ok, message = server.Backend()._ensure_plugin_jar(tmp_path)

    assert not ok and f"cannot read the plugin jar {new}" in message
    assert old.read_bytes() == b"working jar"


def test_a_headless_start_keeps_a_newer_jar_delivered_during_the_readability_check(tmp_path, monkeypatch):
    dropins = tmp_path / "dropins"
    dropins.mkdir()
    old = dropins / "io.github.keyfire.edtbridge_0.47.1.202611151051.jar"
    new = dropins / "io.github.keyfire.edtbridge_0.47.2.202611151649.jar"
    old.write_bytes(b"working jar")

    def deliver_while_reading(path, mode):
        if path == old:
            new.write_bytes(b"new jar")
        return open(path, mode)

    monkeypatch.setattr(server, "open", deliver_while_reading, raising=False)

    assert server.Backend()._ensure_plugin_jar(tmp_path)[0]
    assert new.read_bytes() == b"new jar"


def test_a_running_gui_names_why_its_bridge_is_down(launcher, monkeypatch):
    """With an unreadable jar, restarting the GUI EDT - the advice it used to get - does not help."""
    backend, _, processes = launcher
    monkeypatch.setattr(backend, "_gui_edt_running", lambda: True)
    monkeypatch.setattr(backend, "_ensure_plugin_jar",
                        lambda path: (False, "EDT cannot read the plugin jar X (denied)"))

    ok, message = backend._launch_headless()

    assert not ok and "EDT cannot read the plugin jar X (denied)" in message
    assert "restart that EDT" not in message
    assert processes == []
