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
    "read_form": {"action"},
    "read_table": {"action", "field"},
}
#: Steps that return what the form shows without comparing it: the caller judges the values.
_READS = {"read_form", "read_table"}
_READ_ONLY = {"assert_field", "assert_cell", "assert_table", "assert_table_size"} | _READS
MAX_ROWS = 200
MAX_COLUMNS = 50
#: Fields, form tables and spreadsheet fields one read_form returns at most.
MAX_ITEMS = 500
#: Fields a selector that missed lists at most, the visible ones first.
MAX_LISTED = 200


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


def _columns(value):
    return (isinstance(value, list) and len(value) <= MAX_COLUMNS
            and all(isinstance(column, dict) and set(column) == {"name", "title"}
                    and _text(column["name"]) and _text(column["title"]) for column in value))


def _form_values(value):
    """The shape read_form returns: named fields, form tables and spreadsheet fields."""
    if (not isinstance(value, dict) or set(value) != {"fields", "tables", "spreadsheets", "truncated"}
            or not isinstance(value["truncated"], bool)):
        return False
    fields, tables, sheets = value["fields"], value["tables"], value["spreadsheets"]
    if not all(isinstance(group, list) for group in (fields, tables, sheets)):
        return False
    if len(fields) + len(tables) + len(sheets) > MAX_ITEMS:
        return False
    for field in fields:
        if (not isinstance(field, dict) or not _text(field.get("name")) or not _text(field.get("title"))
                or set(field) - {"name", "title", "presentation", "error"}
                or len({"presentation", "error"} & set(field)) != 1
                or not _text(field.get("presentation", field.get("error")))):
            return False
    for table in tables:
        if (not isinstance(table, dict) or set(table) != {"name", "title", "columns", "rows", "truncated"}
                or not isinstance(table["truncated"], bool)
                or not _text(table["name"]) or not _text(table["title"]) or not _columns(table["columns"])
                or not _table(table["rows"]) or any(len(row) != len(table["columns"]) for row in table["rows"])):
            return False
    for sheet in sheets:
        if (not isinstance(sheet, dict) or set(sheet) != {"name", "title", "rows", "truncated"}
                or not isinstance(sheet["truncated"], bool)
                or not _text(sheet["name"]) or not _text(sheet["title"]) or not _table(sheet["rows"])):
            return False
    return True


#: The value of each key a step takes, as a JSON schema and as the words of a refusal.
_VALUES = {
    "field": ({"type": "string", "description": "Internal name of a visible form item, not its caption"},
              "the internal name of a visible form item (an identifier, no * or ?)"),
    "button": ({"type": "string", "description": "Exact visible caption of the button"},
               "the exact visible caption of the button (no * or ?)"),
    "button_name": ({"type": "string", "description": "Internal name of the button item"},
                    "the internal name of the button item (an identifier)"),
    "expect_error_dialog": ({"type": "string", "description": "Exact text of the native error dialog the click "
                                                              "must show; the dialog is then closed"},
                            "the exact text of the error dialog"),
    "text": ({"type": "string", "description": "Text to type; an empty string clears the field"},
             "a string of up to 4096 characters"),
    "cell": ({"type": "string", "pattern": "^R[1-9][0-9]{0,5}C[1-9][0-9]{0,4}$",
              "description": "Spreadsheet cell address, e.g. R3C6"},
             "a cell address such as R3C6"),
    "rows": ({"type": "integer", "minimum": 0, "maximum": 999999}, "a whole number from 0"),
    "columns": ({"type": "integer", "minimum": 0, "maximum": 99999}, "a whole number from 0"),
    "count": ({"type": "integer", "minimum": 0, "maximum": 10000,
               "description": "How many messages with this text the preceding click added; messages already "
                              "on the panel before the click are not counted, 0 checks that none was added"},
              "a whole number from 0 to 10000"),
}
#: equals differs by action: a string for one value, rows of strings for a table.
_EQUALS = {
    "assert_field": ({"type": "string", "description": "Expected presentation, as the client shows it"},
                     "a string, written as the client shows the value"),
    "assert_cell": ({"type": "string", "description": "Expected cell text"}, "a string"),
    "assert_table": ({"type": "array", "items": {"type": "array", "items": {"type": "string"}},
                      "description": "Expected rows, each a list of cell texts"},
                     f"a rectangular list of rows of strings, up to {MAX_ROWS} rows by {MAX_COLUMNS} columns"),
}
_DESCRIPTIONS = {
    "input": "Type text into a field and move to the next item",
    "click": "Press a button by its caption (button) or item name (button_name)",
    "assert_field": "Compare the presentation of a field",
    "assert_cell": "Compare one cell of a spreadsheet field",
    "assert_table": "Compare a spreadsheet field or a form table with the expected rows",
    "assert_table_size": "Compare the populated size of a spreadsheet field",
    "assert_messages": "Count the messages with this text that the preceding click added to the message panel",
    "read_form": "Return every visible field, form table and spreadsheet of the form",
    "read_table": "Return one form table or spreadsheet field",
}
ACTIONS = ("input", "click", *(action for action in _KEYS if action != "input"))
#: A scenario of the shape the tool takes, for descriptions and refusals.
EXAMPLE = ('[{"action": "input", "field": "Article", "text": "A-100"}, '
           '{"action": "click", "button": "Check"}, '
           '{"action": "assert_messages", "text": "Done", "count": 1}]')


def _shapes(action):
    """The key sets a step of this action takes, and its optional keys."""
    if action == "click":
        return [{"action", "button"}, {"action", "button_name"}], {"expect_error_dialog"}
    return [_KEYS[action]], set()


def _shape_text(action):
    if action == "click":
        return "action and either button or button_name, optionally expect_error_dialog"
    return ", ".join(["action"] + sorted(_KEYS[action] - {"action"}))


def _value(action, key):
    return _EQUALS[action] if key == "equals" else _VALUES[key]


def step_schemas():
    """One JSON schema per kind of step: the value of action and the keys that go with it."""
    schemas = []
    for action in ACTIONS:
        alternatives, optional = _shapes(action)
        for required in alternatives:
            properties = {"action": {"const": action}}
            for key in sorted((required | optional) - {"action"}):
                properties[key] = _value(action, key)[0]
            schemas.append({"type": "object", "description": _DESCRIPTIONS[action], "properties": properties,
                            "required": sorted(required), "additionalProperties": False})
    return schemas


def _step_problem(phase, action, step, has_click):
    """What is wrong with one step, in words that name the keys; None for a valid step."""
    if action != "click" and action not in _KEYS:
        return f"unknown action; the actions are {', '.join(ACTIONS)}"
    alternatives, optional = _shapes(action)
    keys = set(step)
    if not any(keys - optional == required for required in alternatives):
        allowed = set().union(*alternatives) | optional
        parts = []
        extra = sorted(keys - allowed)
        if extra:
            parts.append("unexpected " + ", ".join(extra))
        if action == "click" and {"button", "button_name"} <= keys:
            parts.append("both button and button_name")
        elif action == "click":
            if not keys & {"button", "button_name"}:
                parts.append("missing button or button_name")
        else:
            missing = sorted(alternatives[0] - keys)
            if missing:
                parts.append("missing " + ", ".join(missing))
        return "; ".join(parts) + f"; {action} takes {_shape_text(action)}"
    if phase == "reopen" and action not in _READ_ONLY:
        return "reopen takes reads and assertions only: " + ", ".join(sorted(_READ_ONLY))
    for key in sorted(keys - {"action"}):
        value = step[key]
        if key in ("field", "button", "button_name"):
            valid = _text(value, selector=True) and (key == "button" or value.isidentifier())
        elif key in ("text", "expect_error_dialog"):
            valid = _text(value) and (key != "expect_error_dialog" or bool(value.strip()))
        elif key == "equals":
            valid = _table(value) if action == "assert_table" else _text(value)
        elif key == "cell":
            valid = isinstance(value, str) and re.fullmatch(r"R[1-9][0-9]{0,5}C[1-9][0-9]{0,4}", value)
        elif key == "rows":
            valid = _integer(value, 999999)
        elif key == "columns":
            valid = _integer(value, 99999)
        else:  # count
            valid = _integer(value, 10000)
        if not valid:
            return f"{key} must be {_value(action, key)[1]}"
    if action == "assert_messages":
        if not has_click:
            return "assert_messages checks the messages of a click, and no click precedes it"
        if not step["text"]:
            return "text must name the message to count"
    return None


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
                raise ScenarioError(f"Invalid step {index + 1} in {phase}: a step is an object with an action, "
                                    f"e.g. {EXAMPLE}.")
            action = step["action"]
            problem = _step_problem(phase, action, step, has_click)
            if problem:
                raise ScenarioError(f"Invalid {action!r} step {index + 1} in {phase}: {problem}.")
            if action in ("input", "click"):
                assertion_after_action = False
                has_click = action == "click"
            else:
                assertion_after_action = True
        if not assertion_after_action:
            raise ScenarioError(f"{phase} must confirm the result after its last action with an assertion or a "
                                "read.")
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


def _new_messages(before, after):
    """How a click changed the message panel, and the messages it added.

    A command appends to the panel, and clearing the panel removes every earlier message. So when the
    messages seen before the click still lead the panel, the rest is new ("appended"); when they do
    not, the panel was cleared and all of it is new ("cleared"). An unchanged panel is either a command
    that wrote nothing or one that cleared the panel and wrote the same messages again.
    """
    if after == before:
        return "unchanged", []
    if after[:len(before)] == before:
        return "appended", after[len(before):]
    return "cleared", list(after)


def evaluate(steps, observations):
    if not isinstance(observations, list) or len(observations) != len(steps):
        raise DriverResultError("The test manager did not observe every scenario step.")
    assertions = []
    reads = []
    status = "scenario_completed"
    for index, (step, observed) in enumerate(zip(steps, observations)):
        action = step["action"]
        if not isinstance(observed, dict) or observed.get("action") != action:
            raise DriverResultError("The test manager returned an unexpected observation.")
        if action in _READS:
            actual = observed.get("actual")
            columns = observed.get("columns")
            truncated = observed.get("truncated", False)
            if not (_form_values(actual) if action == "read_form" else _table(actual)) or (
                    columns is not None and not _columns(columns)) or not isinstance(truncated, bool):
                raise DriverResultError(f"The test manager returned invalid data for step {index + 1}.")
            read = {"step": index + 1, "action": action, "actual": actual}
            if action == "read_table":
                read.update(field=step["field"], truncated=truncated)
                if columns is not None:
                    read["columns"] = columns
            reads.append(read)
            continue
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
        baseline = panel = None
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
                panel, added = _new_messages(before, actual)
                actual = added.count(step["text"])
            expected = step["count"]
        if not valid:
            raise DriverResultError(f"The test manager returned invalid data for step {index + 1}.")
        undecided = panel == "unchanged" and baseline > 0
        passed = actual == expected and not undecided
        assertion = {"step": index + 1, "action": action, "expected": expected, "actual": actual, "ok": passed}
        if action == "assert_table" and observed.get("columns") is not None:
            if not _columns(observed["columns"]):
                raise DriverResultError(f"The test manager returned invalid data for step {index + 1}.")
            assertion["columns"] = observed["columns"]
        if baseline is not None:
            assertion.update(baselineCount=baseline, text=step["text"], panel=panel)
        assertions.append(assertion)
        if undecided:
            status = "message_baseline_not_empty"
        elif not passed and status == "scenario_completed":
            status = "assertion_failed"
    if not assertions and not reads:
        raise DriverResultError("The scenario produced no assertions.")
    result = {"ok": status == "scenario_completed", "status": status, "assertions": assertions}
    if reads:
        result["reads"] = reads
    return result
