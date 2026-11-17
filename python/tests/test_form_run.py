"""edt_test_form: validation, the infobase copy and the hand-off to the native check."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from edt_bridge_mcp import form_run, native_scenario, server


@pytest.fixture
def setup(tmp_path):
    infobase = tmp_path / "test-ib"
    infobase.mkdir()
    (infobase / "1Cv8.1CD").write_bytes(b"original")
    artifact = tmp_path / "Check.epf"
    artifact.write_bytes(b"epf")
    environ = {form_run.ENV_INFOBASE: str(infobase), form_run.ENV_PLATFORM: str(tmp_path / "bin")}
    return infobase, artifact, environ


def call(arguments, environ, seen=None):
    def runner(args):
        if seen is not None:
            seen.append(args)
            # The run works on a copy: changing it must not reach the configured infobase.
            (args.test_infobase / "1Cv8.1CD").write_bytes(b"changed by the run")
            if args.scenario:
                seen.append(json.loads(Path(args.scenario).read_text(encoding="utf-8")))
        return {"ok": True, "status": "scenario_completed", "testInfobase": str(args.test_infobase)}
    return form_run.run_tool(arguments, runner=runner, environ=environ)


def test_unconfigured_host_is_refused(setup):
    _, artifact, _ = setup
    report = call({"path": str(artifact), "form": "F", "formTitle": "T", "button": "B"}, {})
    assert report["status"] == "not_configured"


def test_button_run_uses_a_copy_and_leaves_the_infobase_alone(setup):
    infobase, artifact, environ = setup
    seen = []
    report = call({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form",
                   "formTitle": "Check", "button": "Run"}, environ, seen)
    assert report["ok"] is True
    assert "testInfobase" not in report
    args = seen[0]
    assert args.epf == artifact and args.erf is None and args.button == "Run"
    assert args.test_infobase != infobase
    assert (infobase / "1Cv8.1CD").read_bytes() == b"original"


def test_steps_become_a_scenario_file(setup):
    _, artifact, environ = setup
    steps = [{"action": "click", "button": "Run"},
             {"action": "assert_messages", "text": "Done", "count": 1}]
    seen = []
    call({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form",
          "formTitle": "Check", "steps": steps}, environ, seen)
    assert seen[1] == {"version": 1, "steps": steps}
    # The native run loads the file through this check: what the tool writes has to pass it.
    native_scenario.validate(seen[1])


@pytest.mark.parametrize("arguments, status", [
    ({"path": "missing.epf", "form": "F", "formTitle": "T", "button": "B"}, "invalid_input"),
    ({"form": "F", "formTitle": "T"}, "invalid_input"),
])
def test_bad_calls_are_refused_before_any_run(setup, arguments, status):
    _, artifact, environ = setup
    if arguments.get("path") is None and "button" not in arguments:
        arguments = {**arguments, "path": str(artifact)}  # neither button nor steps
    assert call(arguments, environ)["status"] == status


def test_the_wrapper_lists_the_tool():
    assert "edt_test_form" in server.LOCAL_TOOL_NAMES


def test_the_form_title_may_be_left_out(setup):
    # The window of an object form adds the object presentation to the title; without a title the form
    # is found by its name.
    _, artifact, environ = setup
    seen = []
    call({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form", "button": "Run"}, environ, seen)
    assert seen[0].form_title is None
    assert "formTitle" not in form_run.DESCRIPTOR["inputSchema"]["required"]


def test_the_schema_describes_every_kind_of_step():
    items = form_run.DESCRIPTOR["inputSchema"]["properties"]["steps"]["items"]["oneOf"]
    kinds = {item["properties"]["action"]["const"] for item in items}
    assert kinds == set(native_scenario.ACTIONS)
    for item in items:
        action = item["properties"]["action"]["const"]
        # A step built from the schema's required keys is a step the check accepts, key for key.
        assert item["additionalProperties"] is False
        assert set(item["required"]) <= set(item["properties"])
        if action != "click":
            assert set(item["required"]) == native_scenario._KEYS[action]


def test_named_scenarios_become_a_scenario_file(setup):
    """EDT-TEST-20: several named scenarios in one call, played in one start of the client."""
    _, artifact, environ = setup
    scenarios = [{"name": "Empty period", "steps": [{"action": "read_form"}]},
                 {"name": "Two companies", "steps": [{"action": "click", "button": "Run"},
                                                     {"action": "assert_messages", "text": "Done", "count": 1}]}]
    seen = []
    call({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form", "scenarios": scenarios},
         environ, seen)
    assert seen[1] == {"version": 1, "scenarios": scenarios}
    native_scenario.validate(seen[1])


@pytest.mark.parametrize("selection", [
    {"button": "Run", "scenarios": [{"name": "A", "steps": [{"action": "read_form"}]}]},
    {"steps": [{"action": "read_form"}], "scenarios": [{"name": "A", "steps": [{"action": "read_form"}]}]},
])
def test_scenarios_go_alone(setup, selection):
    _, artifact, environ = setup
    report = call({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form", **selection}, environ)
    assert report["status"] == "invalid_input"
    assert "one of button, steps and scenarios" in report["error"]


def test_the_schema_describes_named_scenarios():
    schema = form_run.DESCRIPTOR["inputSchema"]["properties"]["scenarios"]
    assert schema["maxItems"] == native_scenario.MAX_SCENARIOS
    assert schema["items"]["required"] == ["name", "steps"]
    # The description says that the scenarios of one call share one copy of the infobase.
    assert "share one copy" in form_run.DESCRIPTOR["description"]
    assert "одной копии базы" in form_run.DESCRIPTOR["descriptionRu"]


# EDT-OUT-03: the answer of the tool keeps what the caller judges by; the diagnostics come on request.
def _two_step_report():
    """The report of a passed two-step scenario as the native check writes it (Docker, 8.5.1.1529)."""
    interaction = {"status": "scenario_observed", "stage": "assert_field",
                   "observedForm": "ExternalDataProcessor.Check.Form.Form", "observedTitle": "Check: Check",
                   "observations": [{"action": "click", "completed": True},
                                    {"action": "assert_field", "actual": "A-100"}], "step": 2}
    assertions = [{"step": 2, "action": "assert_field", "expected": "A-100", "actual": "A-100", "ok": True}]
    reads = [{"step": 3, "action": "read_form", "actual": {"fields": [
        {"name": "Article", "title": "Article", "presentation": "A-100"}], "tables": [], "spreadsheets": [],
        "truncated": False}}]
    phase = {"name": "steps", "ok": True, "stage": "form_interaction", "connectAttempts": 1,
             "interaction": interaction, "status": "scenario_completed", "assertions": assertions, "reads": reads,
             "screenshot": "/tmp/run/output/steps/screenshot.png", "clientLog": "Done",
             "displayLog": "_XSERVTransSocketUNIXCreateListener: ...SocketCreateListener() failed\n"
                           "_XSERVTransMakeAllCOTSServerListeners: server already running", "cleanupComplete": True}
    return {
        "ok": True, "scope": "fixture", "scopeSource": "caller", "stage": "form_interaction",
        "form": "ExternalDataProcessor.Check.Form.Form", "formTitle": None,
        "scenario": {"version": 1, "steps": [{"action": "click", "button": "Fill"},
                                             {"action": "assert_field", "field": "Article", "equals": "A-100"},
                                             {"action": "read_form"}]},
        "clientEnvironment": {"confFiles": ["/opt/1cv8/x86_64/8.5.1.1529/conf/conf.cfg", "/opt/1cv8/conf/conf.cfg"],
                              "connectionString": 'File="/tmp/edt-bridge-form-run-x/infobase";',
                              "unsafeActionProtection": {"setting": "DisableUnsafeActionProtection",
                                                         "patterns": [".*/edt-bridge-form-run-.*"],
                                                         "source": "/opt/1cv8/conf/conf.cfg",
                                                         "disabledForTestInfobase": True,
                                                         "matchedPattern": ".*/edt-bridge-form-run-.*"},
                              "systemLanguage": {"setting": "SystemLanguage", "value": "RU",
                                                 "source": "/opt/1cv8/conf/conf.cfg"}},
        "managerCache": {"key": "ab253a51393a40954cf7", "path": "/root/.cache/edt-bridge/test-manager/ab253a5",
                         "status": "hit"},
        "targetMetadata": {"source": "ibcmd"}, "phases": [phase], "phase": "steps",
        "timings": {"totalSeconds": 22.27, "stages": {"preparation": 0.01, "managerDisplay": 0.04,
                                                      "managerInfobase": 0.01, "targetMetadata": 0.92,
                                                      "phases": 21.25, "cleanup": 0.03, "eventLog": 0.01},
                    "phases": [{"name": "steps", "seconds": 21.25, "clientStart": 9.3, "manager": 11.72,
                                "screenshot": 0.17, "cleanup": 0.06, "managerStartup": 9.7,
                                "driver": {"connect": 0.12, "formLookup": 0.52,
                                           "steps": [{"step": 1, "action": "click", "seconds": 0.87},
                                                     {"step": 2, "action": "assert_field", "seconds": 0.51}],
                                           "total": 2.02}}]},
        "connectAttempts": 1, "interaction": interaction, "status": "scenario_completed", "assertions": assertions,
        "reads": reads, "screenshot": "/tmp/run/output/steps/screenshot.png", "clientLog": "Done",
        "displayLog": phase["displayLog"], "setupLog": "[INFO] Export of the configuration to XML...\n[INFO] Done",
        "cleanupComplete": True, "eventLog": {"read": True, "errors": []},
    }


def _answer(report, environ, artifact, **extra):
    return form_run.run_tool({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form",
                              "steps": [{"action": "read_form"}], **extra},
                             runner=lambda args: report, environ=environ)


def test_a_passed_scenario_answers_with_the_verdict_and_the_values_only(setup):
    _, artifact, environ = setup
    full = _two_step_report()

    answer = _answer(json.loads(json.dumps(full)), environ, artifact)

    text = json.dumps({key: value for key, value in answer.items() if key != "reads"}, ensure_ascii=False, indent=2)
    assert len(text) < 1500, text
    assert answer["ok"] is True and answer["status"] == "scenario_completed"
    assert answer["assertions"] == full["assertions"] and answer["reads"] == full["reads"]
    for key in ("phases", "timings", "clientEnvironment", "managerCache", "displayLog", "setupLog", "scenario",
                "interaction", "screenshot"):
        assert key not in answer, key
    # The way back to the diagnostics is named in the answer itself.
    assert "details" in answer["omitted"]


def test_details_bring_the_whole_report(setup):
    _, artifact, environ = setup
    answer = _answer(_two_step_report(), environ, artifact, details=True)
    assert {"phases", "timings", "clientEnvironment", "displayLog", "scenario"} <= set(answer)
    assert "omitted" not in answer


@pytest.mark.parametrize("status", ["setup_failed", "license_unavailable", "timeout", "startup_failed",
                                    "client_environment", "driver_failed"])
def test_a_run_that_did_not_reach_its_verdict_keeps_the_diagnostics(setup, status):
    _, artifact, environ = setup
    report = dict(_two_step_report(), ok=False, status=status, error="the platform refused")
    answer = _answer(report, environ, artifact)
    assert {"timings", "clientEnvironment", "displayLog", "setupLog"} <= set(answer)


@pytest.mark.parametrize("failure", [
    {"status": "assertion_failed"},
    {"status": "command_error", "interaction": {"status": "command_error", "error": "Division by zero {Form(13)}"}},
    {"status": "unexpected_dialog", "dialog": {"kind": "question", "text": "Save?", "buttons": ["Yes", "No"]}},
    {"status": "scenario_error", "observedFields": {"total": 1, "fields": [{"name": "Article"}]}},
])
def test_a_failed_solution_answers_with_what_it_did_short(setup, failure):
    _, artifact, environ = setup
    report = dict(_two_step_report(), ok=False, **failure,
                  eventLog={"read": True, "errors": [{"message": "Division by zero", "line": 13}]})
    answer = _answer(report, environ, artifact)
    assert "timings" not in answer and "clientEnvironment" not in answer
    assert answer["eventLog"]["errors"][0]["line"] == 13
    for key in ("dialog", "observedFields"):
        if key in failure:
            assert answer[key] == failure[key]
    if "interaction" in failure:
        assert answer["interaction"]["error"].startswith("Division by zero")


def test_the_descriptor_offers_details():
    assert form_run.DESCRIPTOR["inputSchema"]["properties"]["details"]["type"] == "boolean"


# EDT-TEST-21: refusals of steps name the reason and the candidates, and cost no run.
@pytest.mark.parametrize("selection, words", [
    ({"steps": [{"action": "input", "field": "Article"}, {"action": "read_form"}]}, "missing text"),
    ({"steps": [{"action": "click", "button": "Run"}]}, "must confirm the result"),
    ({"scenarios": [{"name": "A", "steps": [{"action": "read_from"}]}]}, "unknown action"),
])
def test_a_bad_scenario_is_refused_before_the_infobase_is_copied(setup, monkeypatch, selection, words):
    _, artifact, environ = setup
    copies = []
    monkeypatch.setattr(form_run.shutil, "copyfile", lambda *args: copies.append(args))
    report = form_run.run_tool({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form", **selection},
                               runner=lambda args: pytest.fail("no run for a refused scenario"), environ=environ)
    assert report["status"] == "invalid_input"
    assert words in report["error"]
    assert copies == []


def test_the_candidates_of_a_selector_stay_in_the_short_answer(setup):
    _, artifact, environ = setup
    matched = [{"name": "FormGenerate", "title": "Generate", "visible": True, "enabled": True},
               {"name": "GenerateMore", "title": "Generate", "visible": False, "enabled": True}]
    report = {"ok": False, "status": "ambiguous_button", "stage": "button_lookup", "matchedButtons": matched,
              "timings": {"stages": {}}, "clientEnvironment": {}}
    answer = form_run.run_tool({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form",
                                "button": "Generate"}, runner=lambda args: report, environ=environ)
    assert answer["matchedButtons"] == matched
    assert "clientEnvironment" not in answer
