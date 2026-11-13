import contextlib
import json
import sys
import threading
import time
from pathlib import Path, PurePosixPath
from types import SimpleNamespace

import pytest

from edt_bridge_mcp import cli, native_form, native_scenario


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


def test_missing_parents_of_the_output_directory_are_created(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    started = []
    monkeypatch.setattr(native_form, "_native_check",
                        lambda args, base, external, output, report: started.append(output))
    args = _fixture_args(tmp_path)
    args.output_dir = tmp_path / "job-41" / "checks" / "order-form"

    report = native_form.run(args)

    assert started == [args.output_dir.resolve()], report
    assert (args.output_dir / "result.json").is_file()


def test_output_directory_that_exists_is_still_refused(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    monkeypatch.setattr(native_form, "_native_check",
                        lambda *args: pytest.fail("an existing output directory must be refused first"))
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()

    report = native_form.run(args)

    assert (report["status"], report["stage"]) == ("invalid_input", "validation")
    assert list(args.output_dir.iterdir()) == []


def test_parent_that_cannot_be_created_is_named_in_the_refusal(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    monkeypatch.setattr(native_form, "_native_check",
                        lambda *args: pytest.fail("the check must not start without its output directory"))
    blocker = tmp_path / "results.txt"
    blocker.write_text("a file where a directory is expected", encoding="utf-8")
    args = _fixture_args(tmp_path)
    args.output_dir = blocker / "order-form"

    report = native_form.run(args)

    assert (report["status"], report["stage"]) == ("io_error", "validation")
    assert "parent directory of --output-dir" in report["error"]
    assert str(blocker) in report["error"]


def test_empty_button_selector_is_rejected_before_starting_a_client(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args = _fixture_args(tmp_path)
    args.button = ""

    result = native_form.run(args)

    assert result["status"] == "invalid_input"
    assert not args.output_dir.exists()


@pytest.mark.parametrize("title", ["Order 7 dated *", "Order ? dated 25.09.2026", "Delete the item?"])
def test_form_title_may_carry_wildcards(monkeypatch, tmp_path, title):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args = _fixture_args(tmp_path)
    args.form_title = title

    native_form._validated_paths(args)


@pytest.mark.parametrize(("option", "value"), [
    ("form_title", "*"), ("form_title", " ?* "), ("form_title", ""), ("button", "Che*"), ("button", "Check?"),
])
def test_wildcard_only_title_and_wildcard_button_are_rejected(monkeypatch, tmp_path, option, value):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args = _fixture_args(tmp_path)
    setattr(args, option, value)

    result = native_form.run(args)

    assert result["status"] == "invalid_input"
    assert not args.output_dir.exists()


@pytest.mark.parametrize(("answer", "status"), [
    ({"status": "command_completed", "stage": "after_observation", "clickAttempted": True,
      "clickReturned": True, "messages": ["Done"], "observedTitle": "Order 7 dated 9/25/2026"},
     "command_completed"),
    ({"status": "form_not_found", "stage": "form_lookup", "observedForms": ["Document.Order.Form.Main"],
      "observedTitles": ["Order 8 dated 25.09.2026"]}, "form_not_found"),
    ({"status": "ambiguous_form", "stage": "form_lookup"}, "ambiguous_form"),
])
def test_title_pattern_reaches_the_test_manager_unchanged(monkeypatch, tmp_path, answer, status):
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    args.form_title = "Order 7 dated *"
    args.expect_message = "Done"
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    monkeypatch.setattr(native_form, "_owns_listener", lambda *args: True)
    monkeypatch.setattr(native_form, "_capture", lambda *args: {"screenshot": "screenshot.png"})
    requests = []

    def spawn(_stack, argv, _env, _log):
        if "/TestManager" in argv:
            request = json.loads(Path(next(arg[2:] for arg in argv if arg.startswith("/C")))
                                 .read_text(encoding="utf-8"))
            requests.append(request)
            Path(request["result"]).write_text(json.dumps(answer), encoding="utf-8")
        return SimpleNamespace(poll=lambda: None)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    report = native_form._run_session(args, args.test_infobase, args.epf, args.output_dir,
                                      tmp_path / "session", tmp_path / "manager", {},
                                      ["1cv8", "ENTERPRISE"], "Xvfb", time.monotonic() + 5, None)

    assert [request["formTitle"] for request in requests] == ["Order 7 dated *"]
    assert report["status"] == status
    assert report["ok"] is (status == "command_completed")
    assert report["interaction"] == answer


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


@pytest.mark.parametrize(
    ("first_result", "listener_still_owned", "response_time", "timeout", "expected_status", "attempts"),
    [
        ({"status": "driver_failed", "stage": "connect",
          "error": "Cannot find a test client with matching version and ID."}, True, 0, 5, "command_completed", 2),
        ({"status": "driver_failed", "stage": "connect",
          "error": "Cannot find a test client with matching version and ID."}, True, 35, 60, "command_completed", 2),
        ({"status": "driver_failed", "stage": "connect",
          "error": "Cannot find a test client with matching version and ID."}, True, 35, 36, "timeout", 1),
        ({"status": "driver_failed", "stage": "connect",
          "error": "Cannot find a test client with matching version and ID."}, False, 0, 5, "driver_failed", 1),
        ({"status": "driver_failed", "stage": "connect", "error": "Access denied."},
         True, 0, 60, "access_denied", 1),
        ({"status": "driver_failed", "stage": "connect", "error": "License not found."},
         True, 0, 60, "license_unavailable", 1),
        ({"status": "command_error", "stage": "click", "error": "Command failed."}, True, 60, 60, "command_error", 1),
    ],
)
def test_retries_connection_without_repeating_form_actions(
        monkeypatch, tmp_path, first_result, listener_still_owned, response_time, timeout, expected_status, attempts):
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    args.expect_message = "Done"
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    listener_states = iter((True, listener_still_owned))
    monkeypatch.setattr(native_form, "_owns_listener", lambda *args: next(listener_states))
    monkeypatch.setattr(native_form, "_capture", lambda *args: {"screenshot": "screenshot.png"})
    now = 100.0

    def advance(seconds):
        nonlocal now
        now += seconds

    monkeypatch.setattr(native_form, "time", SimpleNamespace(monotonic=lambda: now, sleep=advance))
    responses = iter(((first_result, response_time), ({
        "status": "command_completed", "stage": "after_observation",
        "clickAttempted": True, "clickReturned": True, "messages": ["Done"],
    }, 0)))

    def spawn(_stack, argv, _env, _log):
        if "/TestManager" in argv:
            request = Path(next(arg[2:] for arg in argv if arg.startswith("/C")))
            result = Path(json.loads(request.read_text(encoding="utf-8"))["result"])
            assert not result.exists()
            response, elapsed = next(responses)
            advance(elapsed)
            result.write_text(json.dumps(response), encoding="utf-8")
        return SimpleNamespace(poll=lambda: None)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    report = native_form._run_session(args, args.test_infobase, args.epf, args.output_dir,
                                      tmp_path / "session", tmp_path / "manager", {},
                                      ["1cv8", "ENTERPRISE"], "Xvfb", now + timeout, None)

    assert report["status"] == expected_status
    assert report["ok"] is (expected_status == "command_completed")
    assert report["connectAttempts"] == attempts
    if expected_status == "timeout":
        assert report["interaction"] == first_result
        assert now == 100 + timeout
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
    # The client opens the external object without the unsafe action warning in this base.
    (platform / "conf").mkdir()
    (platform / "conf" / "conf.cfg").write_text("DisableUnsafeActionProtection=.*test-infobase.*\n",
                                                encoding="utf-8")
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


def test_the_check_runs_in_a_worker_thread(monkeypatch, tmp_path):
    # The MCP wrapper serves a tool call in a worker thread, and Python installs signal handlers
    # in the main thread only.
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    started = []
    monkeypatch.setattr(native_form, "_native_check",
                        lambda args, base, external, output, report: started.append(base))
    reports = []
    worker = threading.Thread(target=lambda: reports.append(native_form.run(_fixture_args(tmp_path))))
    worker.start()
    worker.join(30)

    assert not worker.is_alive()
    assert started, reports
    assert reports and reports[0]["eventLog"] == {"read": True, "errors": []}


# -- the client environment: conf.cfg of the platform ---------------------------------------------


def _environment_args(tmp_path, conf=None, *, root_conf=None, user=None, link=False):
    installation = tmp_path / "opt" / "1cv8"
    platform = installation / "x86_64" / "8.3.27.1859"
    platform.mkdir(parents=True)
    for location, text in ((platform / "conf", conf), (installation / "conf", root_conf)):
        if text is not None:
            location.mkdir(parents=True, exist_ok=True)
            (location / "conf.cfg").write_text(text, encoding="utf-8")
    base = tmp_path / "srv" / "tests" / "orders-base"
    base.mkdir(parents=True)
    args = SimpleNamespace(user=user)
    return args, platform, base, None if link else tmp_path / "sample.epf"


def test_a_platform_without_the_setting_is_refused_with_its_name_and_a_pattern(tmp_path):
    args, platform, base, external = _environment_args(tmp_path, "SystemLanguage=RU\n")
    report = {}

    with pytest.raises(native_form._CheckError) as refusal:
        native_form._require_client_environment(args, platform, base, external, report)

    assert refusal.value.status == "client_environment"
    message = str(refusal.value)
    assert "DisableUnsafeActionProtection" in message
    assert f"in {platform / 'conf' / 'conf.cfg'}" in message
    assert native_form._posix_bre(native_form._suggested_pattern(base)).fullmatch(f'File="{base}";')
    environment = report["clientEnvironment"]
    assert environment["unsafeActionProtection"]["disabledForTestInfobase"] is False
    assert environment["systemLanguage"] == {"setting": "SystemLanguage", "value": "RU",
                                             "source": str(platform / "conf" / "conf.cfg")}


def test_patterns_that_match_nothing_warn_and_name_the_file_that_holds_them(tmp_path):
    args, platform, base, external = _environment_args(
        tmp_path, "SystemLanguage=RU\n", root_conf="DisableUnsafeActionProtection=stage_only_[0-9]x\n")
    report = {}

    native_form._require_client_environment(args, platform, base, external, report)

    protection = report["clientEnvironment"]["unsafeActionProtection"]
    assert protection["disabledForTestInfobase"] is False
    assert f"in {platform.parents[1] / 'conf' / 'conf.cfg'}, or sign in" in protection["warning"]


def test_conf_location_of_the_version_is_read(tmp_path):
    shared = tmp_path / "etc" / "1c"
    shared.mkdir(parents=True)
    (shared / "conf.cfg").write_text("DisableUnsafeActionProtection=.*orders-base.*\n", encoding="utf-8")
    args, platform, base, external = _environment_args(tmp_path, f"ConfLocation={shared}\n")
    report = {}

    native_form._require_client_environment(args, platform, base, external, report)

    protection = report["clientEnvironment"]["unsafeActionProtection"]
    assert protection["disabledForTestInfobase"] is True
    assert protection["source"] == str(shared / "conf.cfg")


def test_no_conf_file_at_all_names_where_it_was_looked_for(tmp_path):
    args, platform, base, external = _environment_args(tmp_path)

    with pytest.raises(native_form._CheckError) as refusal:
        native_form._require_client_environment(args, platform, base, external, {})

    assert f"no conf.cfg in {platform / 'conf'}" in str(refusal.value)


@pytest.mark.parametrize("conf, root_conf", [
    # Separator-agnostic patterns: the tests also run on Windows, the check itself on Linux.
    ("DisableUnsafeActionProtection=.*srv.tests.orders-base.*\n", None),
    ("SystemLanguage=System\n", r"DisableUnsafeActionProtection=stage_.*;.*orders-\(base\|copy\).*;" + "\n"),
    ("disableunsafeactionprotection = .*orders-base.*\n", None),
])
def test_a_pattern_of_the_version_or_the_installation_conf_lets_the_check_run(tmp_path, conf, root_conf):
    args, platform, base, external = _environment_args(tmp_path, conf, root_conf=root_conf)
    report = {}

    native_form._require_client_environment(args, platform, base, external, report)

    protection = report["clientEnvironment"]["unsafeActionProtection"]
    assert protection["disabledForTestInfobase"] is True
    assert protection["matchedPattern"]


def test_a_pattern_must_match_the_whole_connection_string(tmp_path):
    # Measured with the platform: a bare path left the security warning, the same path inside .* did not.
    bare = native_form._bre_literal(str(tmp_path / "srv" / "tests" / "orders-base"))
    args, platform, base, external = _environment_args(tmp_path, "DisableUnsafeActionProtection=" + bare + "\n")
    report = {}

    native_form._require_client_environment(args, platform, base, external, report)

    protection = report["clientEnvironment"]["unsafeActionProtection"]
    assert protection["disabledForTestInfobase"] is False
    assert native_form._suggested_pattern(base) in protection["warning"]


def test_the_version_conf_wins_over_the_installation_conf(tmp_path):
    args, platform, base, external = _environment_args(
        tmp_path, "DisableUnsafeActionProtection=stage_only_[0-9]x\n",
        root_conf="DisableUnsafeActionProtection=.*orders-base.*\n")
    report = {}

    native_form._require_client_environment(args, platform, base, external, report)

    protection = report["clientEnvironment"]["unsafeActionProtection"]
    assert protection["source"] == str(platform / "conf" / "conf.cfg")
    assert protection["disabledForTestInfobase"] is False


def test_basic_expression_operators_are_escaped_and_plain_characters_stay_plain():
    assert native_form._posix_bre(r"base-\(a\|b\)").search('File="/x/base-b";')
    assert not native_form._posix_bre(r"base-(a|b)").search('File="/x/base-b";')
    assert native_form._posix_bre(r"c++").search('File="/x/c++";')
    assert native_form._posix_bre(r"a\{2\}").search("aa")


def test_a_user_or_a_configuration_form_is_not_refused(tmp_path):
    args, platform, base, external = _environment_args(tmp_path, "", user="Tester")
    report = {}
    native_form._require_client_environment(args, platform, base, external, report)
    assert "Tester" in report["clientEnvironment"]["unsafeActionProtection"]["note"]

    args, platform, base, external = _environment_args(tmp_path / "link", "", link=True)
    report = {}
    native_form._require_client_environment(args, platform, base, external, report)
    assert report["clientEnvironment"]["unsafeActionProtection"]["disabledForTestInfobase"] is False


def test_the_copy_of_edt_test_form_gets_a_pattern_for_every_copy(tmp_path):
    # The check runs on Linux, so the copy has a POSIX path.
    base = PurePosixPath("/tmp/edt-bridge-form-run-k2x9/infobase")
    pattern = native_form._suggested_pattern(base)
    assert pattern == ".*/edt-bridge-form-run-.*"
    assert native_form._posix_bre(pattern).search('File="/tmp/edt-bridge-form-run-77aa/infobase";')


def test_the_refusal_comes_before_any_platform_process(monkeypatch, tmp_path):
    _substitute_metadata_export(monkeypatch)
    monkeypatch.setattr(native_form, "_spawn", lambda *args: pytest.fail("no process may start"))
    args = _fixture_args(tmp_path)
    (args.platform_bin / "conf" / "conf.cfg").unlink()

    report = native_form.run(args)

    assert (report["status"], report["stage"]) == ("client_environment", "client_environment")
    assert "clientEnvironment" in report
    assert (args.output_dir / "result.json").is_file()


def test_a_selector_that_missed_brings_the_fields_of_the_open_form(monkeypatch, tmp_path):
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    monkeypatch.setattr(native_form, "_owns_listener", lambda *args: True)
    monkeypatch.setattr(native_form, "_capture", lambda *args: {"screenshot": "screenshot.png"})
    observed = {"total": 2, "fields": [
        {"name": "КомпоновщикНастроекПользовательскиеНастройкиЭлемент0ДатаНачала", "title": "Период",
         "kind": "Поле ввода", "visible": True, "enabled": True},
        {"name": "Период1ДатаНачала", "title": "", "kind": "Поле ввода", "visible": False, "enabled": True}]}
    requests = []

    def spawn(_stack, argv, _env, _log):
        if "/TestManager" in argv:
            request = json.loads(Path(next(arg[2:] for arg in argv if arg.startswith("/C"))).read_text(encoding="utf-8"))
            requests.append(request)
            Path(request["result"]).write_text(json.dumps({
                "status": "scenario_error", "stage": "input", "step": 1, "observations": [],
                "error": "The field selector must match exactly one field.", "observedFields": observed},
                ensure_ascii=False), encoding="utf-8")
        return SimpleNamespace(poll=lambda: None)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    steps = [{"action": "input", "field": "ПериодДатаНачала", "text": "01.01.2026"}, {"action": "read_form"}]
    report = native_form._run_session(args, args.test_infobase, args.epf, args.output_dir,
                                      tmp_path / "session", tmp_path / "manager", {},
                                      ["1cv8", "ENTERPRISE"], "Xvfb", time.monotonic() + 30, steps)

    assert requests[0]["maxListed"] == native_scenario.MAX_LISTED
    assert report["status"] == "scenario_error"
    assert report["observedFields"] == observed


def test_a_window_no_step_expected_is_reported_with_its_text(monkeypatch, tmp_path):
    """A warning over the form stops the scenario, and the caller sees what the solution showed."""
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    monkeypatch.setattr(native_form, "_owns_listener", lambda *args: True)
    monkeypatch.setattr(native_form, "_capture", lambda *args: {"screenshot": "screenshot.png"})
    dialog = {"kind": "warning", "formName": "MessageBox", "text": "Check the shipment date", "buttons": ["OK"]}

    def spawn(_stack, argv, _env, _log):
        if "/TestManager" in argv:
            request = json.loads(Path(next(arg[2:] for arg in argv if arg.startswith("/C"))).read_text(encoding="utf-8"))
            Path(request["result"]).write_text(json.dumps({
                "status": "unexpected_dialog", "stage": "click", "step": 1, "observations": [], "dialog": dialog},
                ensure_ascii=False), encoding="utf-8")
        return SimpleNamespace(poll=lambda: None)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    steps = [{"action": "click", "button": "Save"}, {"action": "read_form"}]
    report = native_form._run_session(args, args.test_infobase, args.epf, args.output_dir,
                                      tmp_path / "session", tmp_path / "manager", {},
                                      ["1cv8", "ENTERPRISE"], "Xvfb", time.monotonic() + 30, steps)

    assert report["status"] == "unexpected_dialog"
    assert report["dialog"] == dialog
    assert not report["ok"]


@pytest.mark.parametrize("refusal", ["Не обнаружено свободной лицензии!", "There are no free licenses!",
                                     "1C:Enterprise server did not detect any free license!"])
def test_a_taken_network_license_is_reported_as_unavailable(monkeypatch, tmp_path, refusal):
    # Seen on a shared network key: Designer wrote this and exited, and the run said setup_failed,
    # so nothing retried it.
    _substitute_metadata_export(monkeypatch, refusal=refusal)

    report = native_form.run(_fixture_args(tmp_path))

    assert report["status"] == "license_unavailable"
    assert report["designerLog"] == refusal
