import contextlib
import json
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from edt_bridge_mcp import cli, native_form


def test_native_form_command_requires_explicit_test_base_and_scope():
    assert "test-form" in cli.COMMANDS
    parser = cli.build_parser("test-form")
    required = {action.dest for action in parser._actions if action.required}
    assert {"test_infobase", "scope", "form", "output_dir", "platform_bin"} <= required
    groups = [{action.dest for action in group._group_actions}
              for group in parser._mutually_exclusive_groups if group.required]
    assert {"epf", "erf", "navigation_link"} in groups
    assert {"button", "scenario"} in groups
    scope = next(action for action in parser._actions if action.dest == "scope")
    assert set(scope.choices) == {"empty", "fixture", "application"}


def test_form_check_refuses_an_output_directory_in_the_selected_base(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args = _fixture_args(tmp_path)
    args.output_dir = args.test_infobase / "result"

    result = native_form.run(args)

    assert not result["ok"]
    assert result["status"] == "invalid_input"
    assert not args.output_dir.exists()


def test_empty_button_selector_is_rejected_before_starting_a_client(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args = _fixture_args(tmp_path)
    args.button = ""

    result = native_form.run(args)

    assert result["status"] == "invalid_input"
    assert not args.output_dir.exists()


def test_early_client_exit_preserves_process_diagnostics(monkeypatch, tmp_path):
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    monkeypatch.setattr(native_form, "_owns_listener", lambda *args: False)
    monkeypatch.setattr(native_form, "_capture", lambda *args: {"screenshotError": "Display unavailable."})

    def spawn(_stack, argv, env, log):
        log.write_text("Unable to initialize GTK+ or connect to the windowing system.", encoding="utf-8")
        return SimpleNamespace(poll=lambda: 255)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    result = native_form._run_session(args, args.test_infobase, args.epf, args.output_dir,
                                      tmp_path / "session", tmp_path / "manager", {},
                                      ["1cv8", "ENTERPRISE"], "Xvfb", time.monotonic() + 5, None)
    assert result["status"] == "startup_failed"
    assert not result["ok"]
    assert result["clientExitCode"] == 255
    assert "Unable to initialize GTK+" in result["clientProcessLog"]
    assert result["cleanupComplete"] is True


@pytest.mark.parametrize(("first_result", "listener_still_owned", "expected_status", "attempts"), [
    ({"status": "driver_failed", "stage": "connect",
      "error": "Cannot find a test client with matching version and ID."}, True, "command_completed", 2),
    ({"status": "driver_failed", "stage": "connect",
      "error": "Cannot find a test client with matching version and ID."}, False, "driver_failed", 1),
    ({"status": "command_error", "stage": "click", "error": "Command failed."}, True, "command_error", 1),
])
def test_retries_connection_without_repeating_form_actions(
        monkeypatch, tmp_path, first_result, listener_still_owned, expected_status, attempts):
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    args.expect_message = "Done"
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    listener_states = iter((True, listener_still_owned))
    monkeypatch.setattr(native_form, "_owns_listener", lambda *args: next(listener_states))
    monkeypatch.setattr(native_form, "_capture", lambda *args: {"screenshot": "screenshot.png"})
    monkeypatch.setattr(native_form.time, "sleep", lambda _: None)
    responses = iter((first_result, {
        "status": "command_completed", "stage": "after_observation",
        "clickAttempted": True, "clickReturned": True, "messages": ["Done"],
    }))

    def spawn(_stack, argv, _env, _log):
        if "/TestManager" in argv:
            request = Path(next(arg[2:] for arg in argv if arg.startswith("/C")))
            result = Path(json.loads(request.read_text(encoding="utf-8"))["result"])
            assert not result.exists()
            result.write_text(json.dumps(next(responses)), encoding="utf-8")
        return SimpleNamespace(poll=lambda: None)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    report = native_form._run_session(args, args.test_infobase, args.epf, args.output_dir,
                                      tmp_path / "session", tmp_path / "manager", {},
                                      ["1cv8", "ENTERPRISE"], "Xvfb", time.monotonic() + 5, None)

    assert report["status"] == expected_status
    assert report["ok"] is (expected_status == "command_completed")
    assert report["connectAttempts"] == attempts
    assert report["cleanupComplete"] is True


@pytest.mark.skipif(sys.platform != "linux", reason="POSIX process groups and Linux socket ownership")
def test_client_readiness_timeout_reaps_the_owned_process(tmp_path):
    port = native_form._free_port()
    with contextlib.ExitStack() as processes:
        client = native_form._spawn(
            processes, [sys.executable, "-B", "-c", "import time; time.sleep(30)"],
            None, tmp_path / "client.log",
        )
        with pytest.raises(native_form._CheckError) as timeout:
            native_form._wait_for_client(client, port, time.monotonic() + 0.2)
        assert timeout.value.status == "timeout"
        assert client.poll() is None
    assert client.poll() is not None


def _fixture_args(tmp_path):
    platform = tmp_path / "platform"
    platform.mkdir()
    for program in ("1cv8", "ibcmd"):
        (platform / program).touch()
    base = tmp_path / "test-infobase"
    base.mkdir()
    (base / "1Cv8.1CD").touch()
    epf = tmp_path / "sample.epf"
    epf.touch()
    return cli.build_parser("test-form").parse_args([
        "--platform-bin", str(platform),
        "--test-infobase", str(base), "--scope", "empty", "--epf", str(epf),
        "--form", "ExternalDataProcessor.Sample.Form.Main", "--form-title", "Sample",
        "--button", "Check", "--output-dir", str(tmp_path / "result"),
    ])


def _substitute_metadata_export(monkeypatch, *, default_form=None, refusal=None):
    # Only the native processes are replaced. The Python runner still reads their
    # exported files and produces its report through the normal entry point.
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    monkeypatch.setattr(native_form.shutil, "which", lambda name: name)
    monkeypatch.setattr(native_form, "_display", lambda *args: {})

    def spawn(_stack, argv, _env, _log):
        if Path(argv[0]).name == "ibcmd":
            directory = Path(next(arg.split("=", 1)[1] for arg in argv if arg.startswith("--db-path=")))
            directory.mkdir()
            (directory / "1Cv8.1CD").touch()
            return SimpleNamespace(wait=lambda timeout: 0)
        if "/DumpExternalDataProcessorOrReportToFiles" in argv:
            if refusal:
                Path(argv[argv.index("/Out") + 1]).write_text(refusal, encoding="utf-8", newline="")
                return SimpleNamespace(wait=lambda timeout: 1)
            xml = Path(argv[argv.index("/DumpExternalDataProcessorOrReportToFiles") + 1])
            property_xml = f"<DefaultForm>{default_form}</DefaultForm>" if default_form else ""
            xml.write_text(
                '<MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses">'
                f"<ExternalDataProcessor><Properties>{property_xml}</Properties>"
                "</ExternalDataProcessor></MetaDataObject>", encoding="utf-8", newline="",
            )
            return SimpleNamespace(wait=lambda timeout: 0)
        pytest.fail(f"Metadata rejection must happen before another platform process starts: {argv[1]}")

    monkeypatch.setattr(native_form, "_spawn", spawn)


@pytest.mark.parametrize("default_form", [None, "ExternalDataProcessor.Sample.Form.Other"])
def test_preflight_rejects_absent_or_mismatched_default_form(monkeypatch, tmp_path, default_form):
    _substitute_metadata_export(monkeypatch, default_form=default_form)

    report = native_form.run(_fixture_args(tmp_path))

    assert report["status"] == "form_not_default"
    assert report["stage"] == "target_metadata"
    assert report["defaultForm"] == (default_form or "")
    assert report["ok"] is False


@pytest.mark.parametrize("refusal", ["Access denied", "Нарушение прав доступа"])
def test_native_platform_refusal_is_reported_as_access_denied(monkeypatch, tmp_path, refusal):
    _substitute_metadata_export(monkeypatch, refusal=refusal)

    report = native_form.run(_fixture_args(tmp_path))

    assert report["status"] == "access_denied"
    assert report["designerLog"] == refusal
    assert report["ok"] is False


def test_os_permission_error_is_reported_as_access_denied(monkeypatch, tmp_path):
    class UnreadablePath:
        def resolve(self):
            raise PermissionError("Test EPF cannot be read.")

    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args = _fixture_args(tmp_path)
    args.epf = UnreadablePath()

    report = native_form.run(args)

    assert report["status"] == "access_denied"
    assert report["error"] == "Test EPF cannot be read."
    assert not args.output_dir.exists()


def test_failed_reopen_does_not_reuse_the_previous_phase_evidence(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.shutil, "which", lambda name: name)
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    args._scenario = {
        "version": 1,
        "steps": [{"action": "assert_field", "field": "Date", "equals": "07.11.2026"}],
        "reopen": [{"action": "assert_field", "field": "Date", "equals": "07.11.2026"}],
    }

    def spawn(_stack, argv, _env, _log):
        if Path(argv[0]).name == "ibcmd":
            directory = Path(next(x.split("=", 1)[1] for x in argv if x.startswith("--db-path=")))
            directory.mkdir()
            (directory / "1Cv8.1CD").touch()
        elif "/DumpExternalDataProcessorOrReportToFiles" in argv:
            xml = Path(argv[argv.index("/DumpExternalDataProcessorOrReportToFiles") + 1])
            xml.write_text("<MetaDataObject><ExternalDataProcessor><Properties>"
                           f"<DefaultForm>{args.form}</DefaultForm>"
                           "</Properties></ExternalDataProcessor></MetaDataObject>", encoding="utf-8")
        elif "/DumpConfigToFiles" in argv:
            (Path(argv[argv.index("/DumpConfigToFiles") + 1]) / "Configuration.xml").touch()
        else:
            assert "/LoadConfigFromFiles" in argv or "/UpdateDBCfg" in argv
        return SimpleNamespace(wait=lambda timeout: 0)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    phases = iter([
        {"ok": True, "status": "scenario_completed", "stage": "form_interaction",
         "screenshot": "first-phase.png", "interaction": {"observations": ["first"]},
         "assertions": [{"ok": True}], "cleanupComplete": True},
        {"ok": False, "status": "startup_failed", "stage": "client_startup", "cleanupComplete": True},
    ])
    monkeypatch.setattr(native_form, "_run_session", lambda *args: next(phases))
    report = {"ok": False}

    native_form._native_check(args, args.test_infobase, args.epf, args.output_dir, report)

    assert report["status"] == "startup_failed"
    assert report["phase"] == "reopen"
    assert "screenshot" not in report
    assert "interaction" not in report
    assert "assertions" not in report
    assert report["phases"][0]["screenshot"] == "first-phase.png"
    assert report["cleanupComplete"] is True
