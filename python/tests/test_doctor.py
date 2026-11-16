"""doctor: the checks that answer when the bridge does not.

They follow one breakage: a jar EDT could not read after an elevated self-update, a per-user
configuration still registering a replaced jar, and an EDT started from an elevated console.
`status` had nothing to say then - no bridge answered - and EDT was reinstalled for nothing.
"""
import json
import os
import subprocess

import pytest

from edt_bridge_mcp import cli, doctor, server, update

_NEW = update.JAR_PREFIX + "0.48.1.202611161200.jar"
_OLD = update.JAR_PREFIX + "0.47.2.202611151649.jar"
_DENIED = ("java.io.FileNotFoundException: C:\\EDT\\dropins\\" + _OLD + " (Access is denied)\n")


@pytest.fixture
def edt(tmp_path, monkeypatch):
    """An EDT install with the current jar, a home without configurations, no EDT running."""
    for name in tuple(os.environ):
        if name.startswith("EDT_BRIDGE_"):
            monkeypatch.delenv(name, raising=False)
    install = tmp_path / "edt"
    (install / "dropins").mkdir(parents=True)
    (install / server.CLI_IMAGE).write_bytes(b"")
    (install / "dropins" / _NEW).write_bytes(b"jar")
    home = tmp_path / "home"
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("USERPROFILE", str(home))
    monkeypatch.setenv("EDT_BRIDGE_EDT_DIR", str(install))
    monkeypatch.setattr(server.Backend, "_edt_processes", lambda self: [])
    monkeypatch.setattr(server.Backend, "status", lambda self: None)
    return install


def _user_area(install, jar):
    home = os.environ["HOME"]
    name = f"org.eclipse.platform_4.38.0_{update._java_hash(str(install.resolve()))}_win32_win32_x86_64"
    area = os.path.join(home, ".eclipse", name, "configuration")
    info = os.path.join(area, "org.eclipse.equinox.simpleconfigurator", "bundles.info")
    os.makedirs(os.path.dirname(info))
    version = jar[len(update.JAR_PREFIX):-len(".jar")]
    with open(info, "w", encoding="utf-8") as handle:
        handle.write(f"io.github.keyfire.edtbridge,{version},dropins/{jar},4,false\n")
    return area


def test_a_healthy_install_has_no_problems(edt):
    report = doctor.diagnose(server.Backend())

    assert report["problems"] == []
    assert report["edtInstall"] == str(edt)
    assert [jar["version"] for jar in report["jars"]] == ["0.48.1.202611161200"]
    assert report["jars"][0]["readable"] is True
    assert report["bridge"] is None


def test_a_user_configuration_registering_the_old_jar_is_a_problem(edt):
    area = _user_area(edt, _OLD)

    report = doctor.diagnose(server.Backend())

    assert {"kind": "user", "path": area, "registers": ["0.47.2.202611151649"],
            "startList": []} in report["configurations"]
    assert any(area in problem and "-clean" in problem for problem in report["problems"])


def test_a_missing_jar_and_several_jars_are_problems(edt):
    (edt / "dropins" / _NEW).unlink()
    assert any("no edt-bridge jar" in p for p in doctor.diagnose(server.Backend())["problems"])

    (edt / "dropins" / _NEW).write_bytes(b"jar")
    (edt / "dropins" / _OLD).write_bytes(b"jar")
    problems = doctor.diagnose(server.Backend())["problems"]
    assert any("2 edt-bridge jars" in p and _NEW in p for p in problems)


def test_no_edt_is_named_as_such(edt, monkeypatch):
    monkeypatch.setenv("EDT_BRIDGE_EDT_DIR", str(edt.parent / "nowhere" / "edt"))

    report = doctor.diagnose(server.Backend())

    assert report["edtInstall"] is None
    assert any("EDT is not found" in p for p in report["problems"])


@pytest.mark.skipif(os.name != "nt" and os.geteuid() == 0, reason="root reads any file")
def test_an_unreadable_jar_is_a_problem_with_its_fix(edt):
    jar = edt / "dropins" / _NEW
    if os.name == "nt":
        subprocess.run(["icacls", str(jar), "/deny", "*S-1-1-0:(R)"], capture_output=True,
                       check=True, stdin=subprocess.DEVNULL)
    else:
        jar.chmod(0)
    try:
        report = doctor.diagnose(server.Backend())
    finally:
        if os.name == "nt":
            subprocess.run(["icacls", str(jar), "/reset"], capture_output=True,
                           stdin=subprocess.DEVNULL)
        else:
            jar.chmod(0o644)

    assert report["jars"][0]["readable"] is False
    assert any(f"cannot read the plugin jar {jar}" in p for p in report["problems"])


def test_an_edt_running_as_administrator_is_a_problem(edt, monkeypatch):
    executable = edt / server.GUI_IMAGE
    monkeypatch.setattr(server.Backend, "_edt_processes", lambda self: [
        {"ProcessId": 29416, "ExecutablePath": str(executable),
         "CommandLine": f'"{executable}" -clean -data "D:\\ws"'}])
    monkeypatch.setattr(doctor, "process_elevated", lambda pid: pid == 29416)

    report = doctor.diagnose(server.Backend())

    assert report["edtProcesses"] == [{"pid": 29416, "image": server.GUI_IMAGE,
                                       "workspace": "D:\\ws", "elevated": True}]
    assert any("29416" in p and "administrator" in p for p in report["problems"])
    assert any("no bridge answers" in p for p in report["problems"])


def test_an_edt_of_another_install_is_left_out(edt, monkeypatch):
    other = edt.parent / "other-edt" / server.GUI_IMAGE
    monkeypatch.setattr(server.Backend, "_edt_processes", lambda self: [
        {"ProcessId": 7, "ExecutablePath": str(other), "CommandLine": f'"{other}"'}])
    monkeypatch.setattr(doctor, "process_elevated", lambda pid: True)

    report = doctor.diagnose(server.Backend())

    assert report["edtProcesses"] == [] and report["problems"] == []


@pytest.mark.skipif(os.name != "nt", reason="token elevation is a Windows notion")
def test_the_elevation_of_this_process_matches_the_console_check():
    assert doctor.process_elevated(os.getpid()) is update._elevated()


@pytest.mark.skipif(os.name == "nt", reason="token elevation is a Windows notion")
def test_elevation_has_no_answer_off_windows():
    assert doctor.process_elevated(os.getpid()) is None


def _log(workspace, text):
    path = workspace / ".metadata" / ".log"
    path.parent.mkdir(parents=True)
    path.write_text(text, encoding="utf-8")


def test_a_plugin_failure_in_the_last_session_is_a_problem(edt, tmp_path, monkeypatch):
    workspace = tmp_path / "ws"
    _log(workspace, "!SESSION 2026-11-16 02:00:00.000 ---\n"
                    "!ENTRY org.eclipse.osgi 4 0 2026-11-16 02:02:26.100\n" + _DENIED)
    monkeypatch.setenv("EDT_BRIDGE_WORKSPACE", str(workspace))

    report = doctor.diagnose(server.Backend())

    failure = report["workspaceLog"]["lastPluginFailure"]
    assert failure["inLastSession"] is True and failure["logged"] == "2026-11-16 02:02:26"
    assert any("did not load the edt-bridge plugin" in p and _OLD in p for p in report["problems"])


def test_a_plugin_failure_of_an_earlier_session_is_history(edt, tmp_path, monkeypatch):
    workspace = tmp_path / "ws"
    _log(workspace, "!SESSION 2026-11-16 02:00:00.000 ---\n"
                    "!ENTRY org.eclipse.osgi 4 0 2026-11-16 02:02:26.100\n" + _DENIED
                    + "!SESSION 2026-11-16 03:11:28.000 ---\n")
    monkeypatch.setenv("EDT_BRIDGE_WORKSPACE", str(workspace))

    report = doctor.diagnose(server.Backend())

    assert report["workspaceLog"]["lastSession"] == "2026-11-16 03:11:28.000"
    assert report["workspaceLog"]["lastPluginFailure"]["inLastSession"] is False
    assert report["problems"] == []


def test_the_command_prints_the_report_and_the_problems(edt, capsys):
    _user_area(edt, _OLD)

    code = cli.run("doctor", [])

    captured = capsys.readouterr()
    assert code == 1
    assert json.loads(captured.out)["configurations"][0]["kind"] == "user"
    assert "-clean" in captured.err
    assert cli.run("doctor", ["--edt-dir", str(edt.parent / "absent")]) == 1
