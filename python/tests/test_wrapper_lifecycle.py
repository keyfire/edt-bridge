"""Offline discovery and target-specific launches remain useful with several EDT instances."""
import json
import os
import sys
import subprocess
import urllib.error
from pathlib import Path

import pytest

from edt_bridge_mcp import server, update


@pytest.fixture(autouse=True)
def clean_environment(monkeypatch):
    for name in tuple(__import__("os").environ):
        if name.startswith("EDT_BRIDGE_"):
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(server, "load_plugin_tools", lambda: ({}, None))


def recorder(monkeypatch, backend):
    srv = server.StdioServer(backend)
    frames = []
    monkeypatch.setattr(srv, "_send", frames.append)
    monkeypatch.setattr(srv, "_kick_background_start", lambda: None)
    return srv, frames


def test_offline_catalog_exposes_java_tools_with_their_arguments(monkeypatch):
    backend = server.Backend()
    backend.autostart = False
    monkeypatch.setattr(backend, "is_up", lambda: False)
    srv, frames = recorder(monkeypatch, backend)
    srv.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    tools = {tool["name"]: tool for tool in frames[0]["result"]["tools"]}
    assert {"edt_projects", "edt_project_errors", "edt_metadata_details", "edt_open_gui"} <= tools.keys()
    assert tools["edt_project_errors"]["inputSchema"]["properties"]["projectName"]["type"] == "string"
    assert tools["edt_metadata_details"]["inputSchema"]["properties"]["fqn"]["type"] == "string"


def test_an_offline_tool_call_names_a_supported_start_command(monkeypatch):
    backend = server.Backend()
    backend.autostart = False
    backend.workspace = "D:/requested workspace"
    monkeypatch.setattr(backend, "is_up", lambda: False)
    monkeypatch.setattr(backend, "_launch_headless", lambda: pytest.fail("autostart is disabled"))
    srv, frames = recorder(monkeypatch, backend)
    srv.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "edt_projects"}})
    result = frames[0]["result"]
    assert result["isError"] is True
    text = result["content"][0]["text"]
    assert "autostart is disabled" in text
    assert "edt-bridge-mcp" in text and "--workspace" in text
    assert "EDT_BRIDGE_AUTOSTART=1" in text


def test_a_token_protected_bridge_does_not_hide_the_catalog(monkeypatch):
    backend = server.Backend()
    monkeypatch.setattr(backend, "is_up", lambda: True)
    def refused(payload):
        raise urllib.error.HTTPError("http://localhost/mcp", 401, "Unauthorized", {}, None)
    monkeypatch.setattr(backend, "forward", refused)
    srv, frames = recorder(monkeypatch, backend)
    srv.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert "edt_projects" in {tool["name"] for tool in frames[0]["result"]["tools"]}
    srv.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "edt_projects"}})
    result = frames[-1]["result"]
    assert result["isError"] is True
    assert "EDT_BRIDGE_TOKEN" in result["content"][0]["text"]


def test_status_skips_another_workspace_even_on_the_requested_port(monkeypatch, tmp_path):
    backend = server.Backend()
    backend.workspace = str(tmp_path / "requested")
    backend.scan_range = 1
    monkeypatch.setattr(backend, "_status_on", lambda port: {
        "workspace": str(tmp_path / ("other" if port == 8770 else "requested")),
        "openProjects": ["APP"],
    })
    assert backend.status()["workspace"] == str(tmp_path / "requested")
    assert backend._active_port == 8771


def _launch_target(tmp_path, monkeypatch, process_command):
    monkeypatch.setattr(server, "_WINDOWS", True)
    install = tmp_path / "edt"
    install.mkdir()
    (install / server.CLI_IMAGE).write_bytes(b"")
    backend = server.Backend()
    backend.workspace = str(tmp_path / "workspace")
    backend.edt_dir = str(install)
    backend.port = 9001
    monkeypatch.setattr(backend, "_pids_of", lambda image: [31] if image == server.CLI_IMAGE else [])
    def processes(command, **kwargs):
        if command[0] == "powershell":
            return subprocess.CompletedProcess(command, 0, json.dumps([{
                "ProcessId": 31,
                "CommandLine": process_command(install, backend.workspace),
            }]).encode("utf-8"))
        return subprocess.CompletedProcess(command, 0, b"")
    monkeypatch.setattr(server.subprocess, "run", processes)
    monkeypatch.setattr(backend, "_ensure_plugin_jar", lambda path: (True, "present"))
    launches = []
    monkeypatch.setattr(server.subprocess, "Popen", lambda args, **kwargs: launches.append((args, kwargs)))
    return backend, launches


@pytest.mark.parametrize("other_workspace,other_install", [(True, False), (True, True)])
def test_an_unrelated_headless_process_does_not_suppress_the_requested_launch(
        tmp_path, monkeypatch, other_workspace, other_install):
    backend, launches = _launch_target(tmp_path, monkeypatch, lambda install, ws:
        f'"{(install / "other-version" if other_install else install) / server.CLI_IMAGE}" '
        f'-data "{ws + "-other" if other_workspace else ws}" -vmargs -Dedt.bridge.port=9001')
    ok, message = backend._launch_headless()
    assert ok and message == "launched"
    assert len(launches) == 1


def test_the_same_workspace_on_another_port_is_not_treated_as_the_target(tmp_path, monkeypatch):
    backend, launches = _launch_target(tmp_path, monkeypatch, lambda install, ws:
        f'"{install / server.CLI_IMAGE}" -data "{ws}" -vmargs -Dedt.bridge.port=9002')
    assert backend._headless_cli_running() is False


def test_an_existing_target_is_waited_for_without_a_duplicate_launch(tmp_path, monkeypatch):
    backend, launches = _launch_target(tmp_path, monkeypatch, lambda install, ws:
        f'"{install / server.CLI_IMAGE}" -data "{ws}" -vmargs -Dedt.bridge.port=9001')
    ok, message = backend._launch_headless()
    assert ok and "already starting" in message
    assert launches == []


def test_the_requested_port_reaches_the_windows_launch_file(tmp_path):
    target = server.write_headless_batch(tmp_path / "launch.bat", Path("C:/edt/1cedtcli.exe"),
                                         Path("D:/workspace"), port=9001)
    text = target.read_text(encoding="ascii")
    assert 'set "EDT_BRIDGE_PORT=9001"' in text
    assert "-Dedt.bridge.port=9001" in text


def test_update_warns_when_workspace_configuration_still_pins_the_old_jar(tmp_path, monkeypatch, capsys):
    dropins = tmp_path / "edt" / "dropins"
    dropins.mkdir(parents=True)
    (dropins / "io.github.keyfire.edtbridge_0.28.0.202610280845.jar").write_bytes(b"jar")
    workspace = tmp_path / "workspace"
    info = workspace / "eclipsecfg" / "org.eclipse.equinox.simpleconfigurator" / "bundles.info"
    info.parent.mkdir(parents=True)
    info.write_text("io.github.keyfire.edtbridge,0.27.0.202610270845,"
                    "file:/old/dropins/io.github.keyfire.edtbridge_0.27.0.202610270845.jar,4,true\n",
                    encoding="utf-8")
    monkeypatch.setenv("EDT_BRIDGE_WORKSPACE", str(workspace))
    monkeypatch.setattr(update, "_find_dropins", lambda: dropins)
    monkeypatch.setattr(update, "install_latest_jar", lambda path: True)
    monkeypatch.setattr(update, "running_bridge_version", lambda: None)
    assert update.update_jar() is True
    output = capsys.readouterr().out
    assert str(info) in output
    assert "0.27.0.202610270845" in output and "0.28.0.202610280845" in output
    assert "configuration" in output


def test_self_update_connection_flags_identify_the_configuration_to_check(monkeypatch):
    monkeypatch.setattr(server.sys, "argv", ["edt-bridge-mcp", "--workspace", "D:/target",
                                           "self-update", "--jar-only", "--port", "9001"])
    seen = {}
    def run(arguments):
        seen["workspace"] = server.os.environ.get("EDT_BRIDGE_WORKSPACE")
        seen["port"] = server.os.environ.get("EDT_BRIDGE_PORT")
        seen["arguments"] = arguments
        return 0
    monkeypatch.setattr(update, "run", run)
    assert server.main() == 0
    assert seen == {"workspace": "D:/target", "port": "9001", "arguments": ["--jar-only"]}


@pytest.mark.parametrize("other_port,other_install", [(True, False), (False, True)])
def test_the_same_workspace_is_never_relaunched_for_another_port_or_installation(
        tmp_path, monkeypatch, other_port, other_install):
    backend, launches = _launch_target(tmp_path, monkeypatch, lambda install, ws:
        f'"{(install / "other-version" if other_install else install) / server.CLI_IMAGE}" '
        f'-data "{ws}" -vmargs -Dedt.bridge.port={9002 if other_port else 9001}')
    lock = Path(backend.workspace) / ".metadata" / ".lock"
    lock.parent.mkdir(parents=True)
    lock.write_bytes(b"original workspace lock")
    ok, message = backend._launch_headless()
    assert ok is False
    assert "workspace" in message and "31" in message
    assert launches == []
    assert lock.read_bytes() == b"original workspace lock"


def test_a_free_workspace_lock_is_preserved_for_edt_to_lock(tmp_path, monkeypatch):
    backend, launches = _launch_target(tmp_path, monkeypatch, lambda install, ws:
        f'"{install / server.CLI_IMAGE}" -data "{ws}-other" -vmargs -Dedt.bridge.port=9001')
    lock = Path(backend.workspace) / ".metadata" / ".lock"
    lock.parent.mkdir(parents=True)
    lock.write_bytes(b"existing inode")
    original_inode = lock.stat().st_ino
    ok, message = backend._launch_headless()
    assert ok and launches
    assert lock.exists()
    assert lock.stat().st_ino == original_inode


def test_a_lock_held_by_an_unidentified_process_refuses_launch_without_unlinking(tmp_path, monkeypatch):
    lock = tmp_path / "workspace" / ".metadata" / ".lock"
    lock.parent.mkdir(parents=True)
    lock.write_bytes(b"locked workspace")
    original_inode = lock.stat().st_ino
    code = """
import os,sys
with open(sys.argv[1], "r+b", buffering=0) as handle:
    if os.name == "nt":
        import msvcrt
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        import fcntl
        fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1)
    print("locked", flush=True)
    sys.stdin.read()
"""
    holder = subprocess.Popen([sys.executable, "-c", code, str(lock)], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              text=True, encoding="utf-8")
    try:
        assert holder.stdout.readline().strip() == "locked"
        backend, launches = _launch_target(tmp_path, monkeypatch, lambda install, ws:
            f'"{install / server.CLI_IMAGE}" -data "{ws}-other" -vmargs -Dedt.bridge.port=9001')
        ok, message = backend._launch_headless()
        assert ok is False
        assert "workspace lock" in message
        assert launches == []
        assert lock.exists() and lock.stat().st_ino == original_inode
    finally:
        holder.communicate("", timeout=10)
