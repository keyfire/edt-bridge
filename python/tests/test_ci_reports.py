"""Reports of a form check for CI: JUnit XML for GitLab and Allure 2 results, one test per phase."""
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from edt_bridge_mcp import native_form, native_reports, native_unit

from test_form_timings import _manager_setup  # noqa: F401  (the same fake setup the timings use)
from test_native_unit import PASSED, Platform as UnitPlatform, _args as unit_args, _client


def _scenario_report(tmp_path, *, steps_phase=None, reopen=True):
    shot = tmp_path / "steps" / "screenshot.png"
    shot.parent.mkdir(parents=True)
    shot.write_bytes(b"\x89PNG\r\n\x1a\nsteps")
    steps = steps_phase or {"name": "steps", "ok": True, "status": "scenario_completed",
                            "assertions": [{"step": 2, "action": "assert_field", "expected": "Товар 2",
                                            "actual": "Товар 2", "ok": True}],
                            "screenshot": str(shot)}
    phases = [steps]
    phase_timings = [{"name": "steps", "seconds": 12.5, "clientStart": 9.0, "manager": 3.0, "screenshot": 0.3,
                      "cleanup": 0.2, "managerStartup": 1.0,
                      "driver": {"connect": 0.1, "formLookup": 0.4,
                                 "steps": [{"step": 1, "action": "input", "seconds": 0.5},
                                           {"step": 2, "action": "assert_field", "seconds": 1.0}],
                                 "total": 2.0}}]
    if reopen and steps.get("ok"):
        phases.append({"name": "reopen", "ok": True, "status": "scenario_completed", "assertions": []})
        phase_timings.append({"name": "reopen", "seconds": 10.5, "clientStart": 9.25, "manager": 1.25})
    scenario = {"version": 1, "steps": [{"action": "input"}, {"action": "assert_field"}]}
    if reopen:
        scenario["reopen"] = [{"action": "assert_field"}]
    ok = all(phase.get("ok") for phase in phases)
    return {"ok": ok, "status": phases[-1]["status"], "form": "Catalog.Товары.Form.ФормаЭлемента",
            "navigationLink": "e1cib/data/Catalog.Товары?ref=00", "scenario": scenario, "phases": phases,
            "timings": {"totalSeconds": 40.0, "stages": {"preparation": 0.1, "managerInfobase": 0.4,
                                                         "targetMetadata": 0.9, "phases": 22.5, "cleanup": 0.1},
                        "phases": phase_timings}}


def _junit(output):
    return ET.parse(output / native_reports.JUNIT).getroot()


def _allure(output):
    results = [json.loads(path.read_text(encoding="utf-8"))
               for path in (output / native_reports.ALLURE).glob("*-result.json")]
    return sorted(results, key=lambda result: result["start"])


def test_a_passed_scenario_gives_one_passed_test_per_phase(tmp_path):
    report = _scenario_report(tmp_path)

    paths = native_reports.write_form_reports(report, tmp_path, finished=1_800_000_000.0)

    junit = _junit(tmp_path)
    assert paths["junit"] == str(tmp_path / "junit.xml")
    assert (junit.get("tests"), junit.get("failures"), junit.get("errors"), junit.get("skipped")) == ("2", "0", "0", "0")
    suite = junit.find("testsuite")
    assert suite.get("name") == "Catalog.Товары.Form.ФормаЭлемента"
    cases = suite.findall("testcase")
    assert [case.get("name") for case in cases] == ["steps", "reopen"]
    # The first test carries the setup the check paid for: 1.4 s before the 12.5 s of its phase.
    assert [case.get("time") for case in cases] == ["13.900", "10.500"]
    assert all(case.find("failure") is None and case.find("error") is None for case in cases)
    assert "step 2: assert_field: 1.00 s" in cases[0].find("system-out").text

    steps, reopen = _allure(tmp_path)
    assert (steps["status"], reopen["status"]) == ("passed", "passed")
    assert [step["name"] for step in steps["steps"]] == [
        "setup", "client start", "test manager start", "connect", "form lookup",
        "step 1: input", "step 2: assert_field", "screenshot"]
    assert [step["name"] for step in steps["steps"][0]["steps"]] == ["preparation", "managerInfobase", "targetMetadata"]
    assert steps["steps"][6]["parameters"] == [{"name": "expected", "value": '"Товар 2"'},
                                               {"name": "actual", "value": '"Товар 2"'}]
    attachment = steps["attachments"][0]
    assert attachment["type"] == "image/png"
    assert (tmp_path / "allure-results" / attachment["source"]).read_bytes() == b"\x89PNG\r\n\x1a\nsteps"
    assert steps["start"] == (1_800_000_000 - 40) * 1000
    assert {label["name"]: label["value"] for label in steps["labels"]}["framework"] == "edt-bridge test-form"


def test_a_failed_assertion_fails_its_phase_and_skips_the_next(tmp_path):
    failed = {"name": "steps", "ok": False, "status": "assertion_failed",
              "assertions": [{"step": 2, "action": "assert_field", "expected": "Товар 2", "actual": "Товар 1",
                              "ok": False}]}
    report = _scenario_report(tmp_path, steps_phase=failed)

    native_reports.write_form_reports(report, tmp_path)

    steps, reopen = _junit(tmp_path).find("testsuite").findall("testcase")
    failure = steps.find("failure")
    assert failure.get("type") == "assertion_failed"
    assert "expected 'Товар 2', actual 'Товар 1' - failed" in failure.get("message")
    assert reopen.find("skipped") is not None
    results = _allure(tmp_path)
    assert [result["status"] for result in results] == ["failed", "skipped"]
    assert [step["status"] for step in results[0]["steps"] if step["name"].startswith("step 2")] == ["failed"]


def test_a_check_that_stopped_in_the_setup_is_one_broken_test(tmp_path):
    report = {"ok": False, "status": "setup_failed", "stage": "manager_setup", "form": "ExternalDataProcessor.A.Form.F",
              "epf": "/srv/a.epf", "error": "Could not export the empty manager configuration (exit code 1).",
              "timings": {"totalSeconds": 6.0, "stages": {"preparation": 0.1, "managerInfobase": 5.9}, "phases": []}}

    native_reports.write_form_reports(report, tmp_path)

    case = _junit(tmp_path).find("testsuite/testcase")
    assert case.get("name") == "setup" and case.get("time") == "6.000"
    assert case.find("error").get("message").startswith("setup_failed; Could not export")
    assert [result["status"] for result in _allure(tmp_path)] == ["broken"]


def test_a_button_check_is_named_by_its_click(tmp_path):
    report = {"ok": True, "status": "command_completed", "phase": "legacy", "button": "Сообщить",
              "form": "ExternalDataProcessor.A.Form.F", "epf": "/srv/a.epf",
              "timings": {"totalSeconds": 20.0, "stages": {"preparation": 0.1, "phases": 19.9},
                          "phases": [{"name": "legacy", "seconds": 19.9, "clientStart": 9.0, "manager": 10.9,
                                      "driver": {"connect": 0.1, "formLookup": 0.5, "command": 1.0, "total": 1.6},
                                      "managerStartup": 9.3}]}}

    native_reports.write_form_reports(report, tmp_path)

    case = _junit(tmp_path).find("testsuite/testcase")
    assert case.get("name") == "click Сообщить" and case.find("failure") is None
    assert "command" in [step["name"] for step in _allure(tmp_path)[0]["steps"]]


def test_a_timeout_breaks_the_phase_at_the_part_that_was_running(tmp_path):
    broken = {"name": "steps", "ok": False, "status": "timeout", "error": "The native form check reached its time limit."}
    report = _scenario_report(tmp_path, steps_phase=broken, reopen=False)
    report["timings"]["phases"][0]["driver"]["steps"] = [{"step": 1, "action": "input", "seconds": 0.5}]

    native_reports.write_form_reports(report, tmp_path)

    assert _junit(tmp_path).find("testsuite/testcase/error").get("type") == "timeout"
    result = _allure(tmp_path)[0]
    assert result["status"] == "broken"
    assert [step["status"] for step in result["steps"]][-2:] == ["broken", "passed"]  # step 1, then the screenshot


def test_the_command_writes_the_reports_next_to_its_result(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args, _, _ = _manager_setup(monkeypatch, tmp_path, [None])
    args.output_dir.rmdir()

    report = native_form.run(args)

    assert report["reports"] == {"junit": str(args.output_dir / "junit.xml"),
                                 "allure": str(args.output_dir / "allure-results")}
    assert _junit(args.output_dir).find("testsuite/testcase").get("name") == "click Check"
    assert json.loads((args.output_dir / "result.json").read_text(encoding="utf-8"))["reports"] == report["reports"]


def test_unit_tests_ask_yaxunit_for_both_reports_and_keep_them(monkeypatch, tmp_path):
    platform = UnitPlatform()

    def client(config, argv):
        allure = Path(config["reports"][1]["path"])
        allure.mkdir()
        (allure / "a-result.json").write_text("{}", encoding="utf-8")
        return _client(PASSED, "0")(config, argv)

    platform.client = client
    monkeypatch.setattr(native_unit.sys, "platform", "linux")
    monkeypatch.setattr(native_unit.shutil, "which", lambda name: name)
    monkeypatch.setattr(native_unit, "_display", lambda *args: {})
    monkeypatch.setattr(native_unit, "_spawn", platform.spawn)

    report = native_unit.run(unit_args(tmp_path))

    assert [entry["format"] for entry in platform.configs[0]["reports"]] == ["jUnit", "allure"]
    output = tmp_path / "result"
    assert report["reports"] == {"junit": str(output / "junit.xml"), "allure": str(output / "allure-results")}
    assert (output / "allure-results" / "a-result.json").is_file()
