import json

import pytest

from edt_bridge_mcp import cli, native_form, native_scenario


def _scenario(*steps, **extra):
    return {"version": 1, "steps": list(steps), **extra}


@pytest.mark.parametrize("selector", [{"button": "Generate"}, {"button_name": "GenerateButton"}])
def test_table_results_are_compared_with_actual_cells_and_order(selector):
    steps = [{"action": "click", **selector},
             {"action": "assert_table", "field": "Result", "equals": [["Number", "Amount"], ["2", "10.00"]]}]
    native_scenario.validate(_scenario(*steps))
    observations = [{"action": "click", "completed": True},
                    {"action": "assert_table", "actual": [["Number", "Amount"], ["2", "10.00"]]}]
    assert native_scenario.evaluate(steps, observations)["status"] == "scenario_completed"
    observations[1]["actual"][1][1] = "20.00"
    result = native_scenario.evaluate(steps, observations)
    assert result["status"] == "assertion_failed"
    assert result["assertions"][0]["actual"][1][1] == "20.00"
    assert not result["ok"]


@pytest.mark.parametrize("before,after,count,status", [
    ([], ["Warning"], 1, "scenario_completed"),
    ([], [], 0, "scenario_completed"),
    ([], ["Warning", "Warning"], 1, "assertion_failed"),
    ([], ["Other"], 1, "assertion_failed"),
    (["Warning"], ["Warning"], 1, "message_baseline_not_empty"),
    (["Warning"], [], 0, "message_baseline_not_empty"),
])
def test_message_count_requires_a_clean_baseline(before, after, count, status):
    steps = [{"action": "click", "button": "Save"},
             {"action": "assert_messages", "text": "Warning", "count": count}]
    observed = [{"action": "click", "completed": True},
                {"action": "assert_messages", "before": before, "actual": after}]
    result = native_scenario.evaluate(steps, observed)
    assert result["status"] == status
    assert result["ok"] == (status == "scenario_completed")


@pytest.mark.parametrize("value", [
    _scenario({"action": "click", "button": "Save"}),
    _scenario({"action": "click"}, {"action": "assert_field", "field": "Result", "equals": "Saved"}),
    _scenario({"action": "click", "button": "Save", "button_name": "SaveButton"},
              {"action": "assert_field", "field": "Result", "equals": "Saved"}),
    _scenario({"action": "click", "button_name": "Save*"},
              {"action": "assert_field", "field": "Result", "equals": "Saved"}),
    _scenario({"action": "click", "button_name": "Save button"},
              {"action": "assert_field", "field": "Result", "equals": "Saved"}),
    _scenario({"action": "assert_field", "field": "Date", "equals": "yesterday"}, {"action": "click", "button": "Save"}),
    _scenario({"action": "execute", "code": "Quit()"}),
    _scenario({"action": "assert_messages", "text": "Warning", "count": 0}),
    _scenario({"action": "assert_cell", "field": "Result", "cell": "R0C1", "equals": ""}),
    _scenario({"action": "assert_field", "field": "Date*", "equals": ""}),
    _scenario({"action": "assert_table", "field": "Result", "equals": [["a"], ["b", "c"]]}),
    _scenario({"action": "assert_field", "field": "Date", "equals": "", "predicate": "true"}),
    _scenario({"action": "assert_field", "field": "Date", "equals": ""}, reopen=[{"action": "click", "button": "Save"}]),
    {"version": True, "steps": []},
])
def test_invalid_or_unverifiable_scenarios_are_rejected(value):
    with pytest.raises(native_scenario.ScenarioError):
        native_scenario.validate(value)


def test_reopen_is_a_separate_read_only_observation():
    value = _scenario({"action": "input", "field": "Date", "text": "07.11.2026"},
                      {"action": "click", "button": "Save"},
                      {"action": "assert_field", "field": "Date", "equals": "07.11.2026"},
                      reopen=[{"action": "assert_field", "field": "Date", "equals": "07.11.2026"}])
    native_scenario.validate(value)
    result = native_scenario.evaluate(value["reopen"], [{"action": "assert_field", "actual": "06.11.2026"}])
    assert result["status"] == "assertion_failed"


@pytest.mark.parametrize("observations", [[], [{"action": "assert_field", "actual": "x"}, {}],
                                         [{"action": "assert_field", "actual": True}],
                                         [{"action": "assert_cell", "actual": "x"}]])
def test_missing_or_malformed_observations_cannot_pass(observations):
    with pytest.raises(native_scenario.DriverResultError):
        native_scenario.evaluate([{"action": "assert_field", "field": "Date", "equals": "x"}], observations)


def test_scenario_file_rejects_duplicate_keys_and_excessive_size(tmp_path):
    path = tmp_path / "scenario.json"
    path.write_text('{"version":1,"version":2,"steps":[]}', encoding="utf-8")
    with pytest.raises(native_scenario.ScenarioError):
        native_scenario.load(path)
    path.write_bytes(b" " * 1_048_577)
    with pytest.raises(native_scenario.ScenarioError):
        native_scenario.load(path)


def test_cli_accepts_erf_scenario_and_keeps_legacy_epf():
    parser = cli.build_parser("test-form")
    common = ["--platform-bin", "/platform", "--test-infobase", "/base", "--scope", "fixture",
              "--form", "ExternalReport.Report.Form.Main", "--form-title", "Report", "--output-dir", "/output"]
    args = parser.parse_args([*common, "--erf", "/report.erf", "--scenario", "/scenario.json"])
    assert args.erf.name == "report.erf"
    assert args.epf is None
    assert native_form._form_key(args.form) == ("ExternalReport", "report", "main")


@pytest.mark.parametrize("link", [
    "e1c://server/production#e1cib/data/Document.Order?ref=" + "a" * 32,
    "e1cib/data/Document.Other?ref=" + "a" * 32,
    "e1cib/data/Document.Bad-Name?ref=" + "a" * 32,
    "e1cib/data/Document.Order?ref=" + "a" * 32 + "&other=1",
    "e1cib/command/Document.Order.Write",
])
def test_configuration_link_cannot_change_base_or_metadata_object(link):
    with pytest.raises(native_form._CheckError):
        native_form._validate_navigation_link(link, "Document.Order.Form.Main")


def test_configuration_link_is_normalized_to_the_matching_local_object():
    link = "e1cib/data/Документ.Заказ?ref=" + "a" * 32
    assert native_form._validate_navigation_link(link, "Document.Заказ.Form.Main") == link


def test_deeply_nested_scenario_is_an_input_error(tmp_path):
    path = tmp_path / "nested.json"
    path.write_text('[' * 3000 + ']' * 3000, encoding="utf-8")
    with pytest.raises(native_scenario.ScenarioError):
        native_scenario.load(path)


@pytest.mark.parametrize("selector", [{"button": "Save"}, {"button_name": "SaveButton"}])
def test_expected_native_error_dialog_requires_observed_text_and_closed_state(selector):
    steps = [{"action": "click", **selector, "expect_error_dialog": "Could not save Order."},
             {"action": "assert_messages", "text": "Native refusal", "count": 1}]
    native_scenario.validate(_scenario(*steps))
    observations = [{"action": "click", "completed": True, "errorDialog": "Could not save Order.",
                     "dialogClosed": True},
                    {"action": "assert_messages", "before": [], "actual": ["Native refusal"]}]
    assert native_scenario.evaluate(steps, observations)["ok"]
    observations[0]["errorDialog"] = "Unexpected error."
    assert native_scenario.evaluate(steps, observations)["status"] == "assertion_failed"
    observations[0].pop("errorDialog")
    with pytest.raises(native_scenario.DriverResultError):
        native_scenario.evaluate(steps, observations)
    observations[0]["errorDialog"] = "Could not save Order."
    observations[0]["dialogClosed"] = False
    with pytest.raises(native_scenario.DriverResultError):
        native_scenario.evaluate(steps, observations)


@pytest.mark.parametrize("steps", [
    [{"action": "click", "button": "Save", "expect_error_dialog": "Could not save."}],
    [{"action": "click", "button": "Save", "expect_error_dialog": ""},
     {"action": "assert_messages", "text": "Refusal", "count": 1}],
    [{"action": "click", "button": "Save", "expect_error_dialog": {"text": "Refusal"}},
     {"action": "assert_messages", "text": "Refusal", "count": 1}],
    [{"action": "assert_field", "field": "Date", "equals": "", "expect_error_dialog": "Refusal"}],
])
def test_error_dialog_cannot_replace_result_assertions_or_accept_invalid_expectations(steps):
    with pytest.raises(native_scenario.ScenarioError):
        native_scenario.validate(_scenario(*steps))
