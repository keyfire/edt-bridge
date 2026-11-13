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


@pytest.mark.parametrize("before,after,count,status,actual,panel", [
    ([], ["Warning"], 1, "scenario_completed", 1, "appended"),
    ([], [], 0, "scenario_completed", 0, "unchanged"),
    ([], ["Warning", "Warning"], 1, "assertion_failed", 2, "appended"),
    ([], ["Other"], 1, "assertion_failed", 0, "appended"),
    # a repeated click: the earlier message stays and the new one is counted
    (["Warning"], ["Warning", "Warning"], 1, "scenario_completed", 1, "appended"),
    (["Warning", "Other"], ["Warning", "Other", "Other"], 0, "scenario_completed", 0, "appended"),
    (["Warning"], ["Warning", "Warning", "Warning"], 1, "assertion_failed", 2, "appended"),
    # the command cleared the panel: everything on it is new
    (["Warning"], [], 0, "scenario_completed", 0, "cleared"),
    (["Warning", "Other"], ["Warning"], 1, "scenario_completed", 1, "cleared"),
    (["Other"], ["Warning"], 1, "scenario_completed", 1, "cleared"),
    # unchanged with the text already there: nothing written, or cleared and written again
    (["Warning"], ["Warning"], 1, "message_baseline_not_empty", 0, "unchanged"),
    (["Warning"], ["Warning"], 0, "message_baseline_not_empty", 0, "unchanged"),
    (["Other"], ["Other"], 0, "scenario_completed", 0, "unchanged"),
])
def test_message_count_is_the_messages_the_click_added(before, after, count, status, actual, panel):
    steps = [{"action": "click", "button": "Save"},
             {"action": "assert_messages", "text": "Warning", "count": count}]
    observed = [{"action": "click", "completed": True},
                {"action": "assert_messages", "before": before, "actual": after}]
    result = native_scenario.evaluate(steps, observed)
    assert result["status"] == status
    assert result["ok"] == (status == "scenario_completed")
    assertion = result["assertions"][0]
    assert (assertion["actual"], assertion["panel"]) == (actual, panel)
    assert assertion["baselineCount"] == before.count("Warning")


def test_two_clicks_of_the_same_command_each_add_one_message():
    # EDT-TEST-10: the second click used to answer message_baseline_not_empty
    steps = [{"action": "click", "button": "Check"},
             {"action": "assert_messages", "text": "Done", "count": 1},
             {"action": "click", "button": "Check"},
             {"action": "assert_messages", "text": "Done", "count": 1}]
    native_scenario.validate(_scenario(*steps))
    observed = [{"action": "click", "completed": True},
                {"action": "assert_messages", "before": [], "actual": ["Done"]},
                {"action": "click", "completed": True},
                {"action": "assert_messages", "before": ["Done"], "actual": ["Done", "Done"]}]
    result = native_scenario.evaluate(steps, observed)
    assert result["status"] == "scenario_completed"
    assert [a["actual"] for a in result["assertions"]] == [1, 1]


def test_the_count_key_says_earlier_messages_are_not_counted():
    schema = next(s for s in native_scenario.step_schemas() if s["properties"]["action"]["const"] == "assert_messages")
    assert "before the click are not counted" in schema["properties"]["count"]["description"]


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


# -- reading the whole form ------------------------------------------------------------------------

_FORM = {
    "fields": [{"name": "Article", "title": "Article", "presentation": "A-100"},
               {"name": "Picture", "title": "", "error": "The field has no data presentation"}],
    "tables": [{"name": "Goods", "title": "Goods", "columns": [{"name": "GoodsItem", "title": "Item"},
                                                               {"name": "GoodsQuantity", "title": "Quantity"}],
                "rows": [["Chair", "2"], ["Desk", "1"]], "truncated": False}],
    "spreadsheets": [{"name": "Result", "title": "", "rows": [["Total", "3"]], "truncated": False}],
    "truncated": False,
}


def test_read_steps_are_valid_scenario_endings_and_read_only_steps():
    scenario = {"version": 1, "steps": [{"action": "click", "button": "Fill"}, {"action": "read_form"}],
                "reopen": [{"action": "read_table", "field": "Goods"}]}
    assert native_scenario.validate(scenario) is scenario
    for bad in ({"action": "read_form", "field": "Goods"}, {"action": "read_table"},
                {"action": "read_table", "field": "Goods*"}):
        with pytest.raises(native_scenario.ScenarioError):
            native_scenario.validate({"version": 1, "steps": [bad]})


def test_read_form_returns_the_values_without_judging_them():
    steps = [{"action": "click", "button": "Fill"}, {"action": "read_form"},
             {"action": "read_table", "field": "Goods"}]
    observations = [{"action": "click", "completed": True}, {"action": "read_form", "actual": _FORM},
                    {"action": "read_table", "actual": [["Chair", "2"]],
                     "columns": [{"name": "GoodsItem", "title": "Item"}, {"name": "GoodsQuantity", "title": "Quantity"}]}]

    result = native_scenario.evaluate(steps, observations)

    assert (result["ok"], result["status"], result["assertions"]) == (True, "scenario_completed", [])
    assert result["reads"][0] == {"step": 2, "action": "read_form", "actual": _FORM}
    assert result["reads"][1]["field"] == "Goods" and result["reads"][1]["columns"][0]["title"] == "Item"
    assert result["reads"][1]["truncated"] is False


def test_a_read_cut_at_the_observation_limit_says_so():
    long_table = [[str(index)] for index in range(native_scenario.MAX_ROWS)]
    form = {**_FORM, "truncated": True,
            "tables": [{**_FORM["tables"][0], "columns": [_FORM["tables"][0]["columns"][0]],
                        "rows": long_table, "truncated": True}]}
    result = native_scenario.evaluate(
        [{"action": "read_form"}, {"action": "read_table", "field": "Goods"}],
        [{"action": "read_form", "actual": form},
         {"action": "read_table", "actual": long_table, "truncated": True}])
    assert result["ok"] and result["reads"][0]["actual"]["tables"][0]["truncated"] is True
    assert result["reads"][1]["truncated"] is True


def test_assert_table_on_a_form_table_compares_rows_and_returns_the_columns():
    columns = [{"name": "GoodsItem", "title": "Item"}]
    steps = [{"action": "assert_table", "field": "Goods", "equals": [["Chair"], ["Desk"]]}]

    same = native_scenario.evaluate(steps, [{"action": "assert_table", "actual": [["Chair"], ["Desk"]],
                                             "columns": columns}])
    other = native_scenario.evaluate(steps, [{"action": "assert_table", "actual": [["Chair"]],
                                              "columns": columns}])

    assert same["ok"] and same["assertions"][0]["columns"] == columns
    assert (other["ok"], other["status"]) == (False, "assertion_failed")
    assert other["assertions"][0]["actual"] == [["Chair"]]


@pytest.mark.parametrize("broken", [
    {"fields": [], "tables": []},
    {**_FORM, "truncated": "no"},
    {key: value for key, value in _FORM.items() if key != "truncated"},
    {**_FORM, "fields": [{"name": "Article", "title": "Article"}]},
    {**_FORM, "fields": [{"name": "Article", "title": "A", "presentation": "x", "error": "y"}]},
    {**_FORM, "tables": [{**_FORM["tables"][0], "rows": [["Chair"]]}]},
    {**_FORM, "fields": [{"name": "F", "title": "F", "presentation": "x"}] * (native_scenario.MAX_ITEMS + 1)},
])
def test_a_malformed_form_read_is_a_driver_failure(broken):
    with pytest.raises(native_scenario.DriverResultError):
        native_scenario.evaluate([{"action": "read_form"}], [{"action": "read_form", "actual": broken}])


@pytest.mark.parametrize("step, words", [
    ({"action": "input", "field": "Article", "value": "1"}, ["unexpected value", "missing text",
                                                             "input takes action, field, text"]),
    ({"action": "input", "item": "Article", "text": "1"}, ["unexpected item", "missing field"]),
    ({"action": "click"}, ["missing button or button_name"]),
    ({"action": "click", "button": "Fill", "button_name": "FillButton"}, ["both button and button_name"]),
    ({"action": "assert_table", "table": "Goods", "equals": []}, ["unexpected table", "missing field"]),
    ({"action": "assert_table", "field": "Goods", "equals": "x"}, ["equals must be a rectangular list"]),
    ({"action": "assert_cell", "field": "Result", "cell": "A1", "equals": "1"}, ["cell must be", "R3C6"]),
    ({"action": "fill"}, ["unknown action", "read_form"]),
    ({"action": "input", "field": "Article*", "text": "1"}, ["field must be the internal name"]),
])
def test_a_refused_step_names_the_keys_it_lacks_and_the_ones_it_should_not_have(step, words):
    with pytest.raises(native_scenario.ScenarioError) as refusal:
        native_scenario.validate(_scenario(step, {"action": "read_form"}))
    message = str(refusal.value)
    assert message.startswith(f"Invalid {step['action']!r} step 1 in steps: ")
    for word in words:
        assert word in message, message


def test_a_scenario_built_from_the_schema_passes_the_check():
    steps = []
    for schema in native_scenario.step_schemas():
        action = schema["properties"]["action"]["const"]
        sample = {"field": "Goods", "button": "Fill", "button_name": "FillButton", "text": "Done",
                  "cell": "R1C1", "rows": 1, "columns": 1, "count": 0, "expect_error_dialog": "Error"}
        step = {"action": action}
        for key in schema["required"]:
            if key == "equals":
                step[key] = [["a"]] if action == "assert_table" else "a"
            elif key != "action":
                step[key] = sample[key]
        steps.append(step)
    assert native_scenario.validate(_scenario(*steps)) is not None
