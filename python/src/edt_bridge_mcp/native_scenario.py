"""Validate bounded native UI scenarios and compare their observed results."""

from __future__ import annotations

import json
import re


class ScenarioError(ValueError):
    status = "invalid_input"


class DriverResultError(ValueError):
    status = "driver_failed"


_KEYS = {
    "input": {"action", "field", "text"},
    "assert_field": {"action", "field", "equals"},
    "assert_cell": {"action", "field", "cell", "equals"},
    "assert_table": {"action", "field", "equals"},
    "assert_table_size": {"action", "field", "rows", "columns"},
    "assert_messages": {"action", "text", "count"},
}
_READ_ONLY = {"assert_field", "assert_cell", "assert_table", "assert_table_size"}
MAX_ROWS = 200
MAX_COLUMNS = 50


def _text(value, *, selector=False):
    return (isinstance(value, str) and len(value) <= 4096 and "\x00" not in value
            and (not selector or (bool(value.strip()) and not any(c in value for c in "*?"))))


def _integer(value, maximum):
    return type(value) is int and 0 <= value <= maximum


def _table(value):
    return (isinstance(value, list) and len(value) <= MAX_ROWS
            and all(isinstance(row, list) and len(row) <= MAX_COLUMNS for row in value)
            and len({len(row) for row in value}) <= 1
            and all(_text(cell) for row in value for cell in row))


def validate(value):
    if (not isinstance(value, dict) or set(value) - {"version", "steps", "reopen"}
            or type(value.get("version")) is not int or value["version"] != 1):
        raise ScenarioError("A scenario must contain version 1, steps, and optionally reopen.")
    for phase in ("steps", "reopen"):
        if phase == "reopen" and phase not in value:
            continue
        steps = value.get(phase)
        if not isinstance(steps, list) or not 1 <= len(steps) <= 128:
            raise ScenarioError(f"{phase} must contain between 1 and 128 steps.")
        has_click = False
        assertion_after_action = False
        for index, step in enumerate(steps):
            if not isinstance(step, dict) or not isinstance(step.get("action"), str):
                raise ScenarioError(f"Invalid step {index + 1} in {phase}.")
            action = step["action"]
            keys = set(step)
            if action == "click":
                valid = keys - {"expect_error_dialog"} in ({"action", "button"}, {"action", "button_name"})
            else:
                valid = keys == _KEYS.get(action)
            if "expect_error_dialog" in step:
                expected_dialog = step["expect_error_dialog"]
                valid = valid and _text(expected_dialog) and bool(expected_dialog.strip())
            if phase == "reopen" and action not in _READ_ONLY:
                valid = False
            for key in ("field", "button", "button_name"):
                if key in step:
                    selector = step[key]
                    if not _text(selector, selector=True) or (key != "button" and not selector.isidentifier()):
                        valid = False
            if "text" in step and not _text(step["text"]):
                valid = False
            if action == "assert_messages":
                valid = valid and has_click and bool(step.get("text")) and _integer(step.get("count"), 10000)
            if action in ("assert_field", "assert_cell") and not _text(step.get("equals")):
                valid = False
            if action == "assert_cell" and (not isinstance(step.get("cell"), str)
                    or not re.fullmatch(r"R[1-9][0-9]{0,5}C[1-9][0-9]{0,4}", step["cell"])):
                valid = False
            if action == "assert_table" and not _table(step.get("equals")):
                valid = False
            if action == "assert_table_size":
                valid = valid and _integer(step.get("rows"), 999999) and _integer(step.get("columns"), 99999)
            if not valid:
                raise ScenarioError(f"Invalid {action!r} step {index + 1} in {phase}.")
            if action in ("input", "click"):
                assertion_after_action = False
                has_click = action == "click"
            else:
                assertion_after_action = True
        if not assertion_after_action:
            raise ScenarioError(f"{phase} must confirm the result after its last action.")
    return value


def _unique_pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ScenarioError(f"Duplicate scenario key: {key}.")
        value[key] = item
    return value


def load(path):
    with path.open("rb") as stream:
        raw = stream.read(1_048_577)
    if len(raw) > 1_048_576:
        raise ScenarioError("The scenario must not exceed 1 MiB.")
    try:
        return validate(json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_unique_pairs))
    except RecursionError as error:
        raise ScenarioError("The scenario JSON is nested too deeply.") from error
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ScenarioError("The scenario must be valid UTF-8 JSON.") from error


def evaluate(steps, observations):
    if not isinstance(observations, list) or len(observations) != len(steps):
        raise DriverResultError("The test manager did not observe every scenario step.")
    assertions = []
    status = "scenario_completed"
    for index, (step, observed) in enumerate(zip(steps, observations)):
        action = step["action"]
        if not isinstance(observed, dict) or observed.get("action") != action:
            raise DriverResultError("The test manager returned an unexpected observation.")
        if action in ("input", "click"):
            if observed.get("completed") is not True:
                raise DriverResultError("The test manager did not complete a scenario action.")
            if "expect_error_dialog" in step:
                actual = observed.get("errorDialog")
                if not _text(actual) or observed.get("dialogClosed") is not True:
                    raise DriverResultError("The test manager did not observe and close the expected error dialog.")
                passed = actual == step["expect_error_dialog"]
                assertions.append({"step": index + 1, "action": "expect_error_dialog",
                                   "expected": step["expect_error_dialog"], "actual": actual, "ok": passed})
                if not passed:
                    status = "assertion_failed"
            continue
        actual = observed.get("actual")
        expected = step.get("equals")
        valid = False
        baseline = None
        if action in ("assert_field", "assert_cell"):
            valid = _text(actual)
        elif action == "assert_table":
            valid = _table(actual)
        elif action == "assert_table_size":
            valid = (isinstance(actual, dict) and set(actual) == {"rows", "columns"}
                     and _integer(actual["rows"], 999999) and _integer(actual["columns"], 99999))
            expected = {"rows": step["rows"], "columns": step["columns"]}
        elif action == "assert_messages":
            before = observed.get("before")
            valid = (isinstance(actual, list) and len(actual) <= 10000 and all(_text(x) for x in actual)
                     and isinstance(before, list) and len(before) <= 10000 and all(_text(x) for x in before))
            if valid:
                baseline = before.count(step["text"])
                actual = actual.count(step["text"])
            expected = step["count"]
        if not valid:
            raise DriverResultError(f"The test manager returned invalid data for step {index + 1}.")
        passed = actual == expected and not baseline
        assertion = {"step": index + 1, "action": action, "expected": expected, "actual": actual, "ok": passed}
        if baseline is not None:
            assertion.update(baselineCount=baseline, text=step["text"])
        assertions.append(assertion)
        if baseline:
            status = "message_baseline_not_empty"
        elif not passed and status == "scenario_completed":
            status = "assertion_failed"
    if not assertions:
        raise DriverResultError("The scenario produced no assertions.")
    return {"ok": status == "scenario_completed", "status": status, "assertions": assertions}
