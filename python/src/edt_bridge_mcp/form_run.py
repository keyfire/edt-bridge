"""edt_test_form: run a form of a built .epf/.erf in a copy of a test infobase, as a tool.

A static check passes code that fails the moment a person presses the button - a server method
that calls a method of the object module on the form data, for instance. The native check behind
`edt-bridge-mcp test-form` catches exactly that, and this tool lets a model run it on its own file:
open the form, press a command or play a short scenario, and read back the messages, the error
dialog and the exception text.

The test infobase is configured by whoever runs the wrapper, not by the caller: every call works on
a fresh copy of its data file, so a run can neither damage the infobase nor see an earlier run.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import tempfile
from pathlib import Path

from . import native_form

ENV_INFOBASE = "EDT_BRIDGE_TEST_INFOBASE"
ENV_PLATFORM = "EDT_BRIDGE_TEST_PLATFORM"
ENV_USER = "EDT_BRIDGE_TEST_USER"
DATA_FILE = "1Cv8.1CD"

DESCRIPTOR = {
    "name": "edt_test_form",
    "description": (
        "Run the main form of a built .epf or .erf in a fresh copy of the configured test infobase "
        "(EDT_BRIDGE_TEST_INFOBASE, platform EDT_BRIDGE_TEST_PLATFORM) through the platform's native "
        "test client, on Linux with Xvfb. Either press one command (button) and get the messages and "
        "any error, or play a scenario (steps: input, click, assert_messages, assert_field, "
        "assert_cell, assert_table, assert_table_size; the last action must be followed by an "
        "assertion). The answer carries status, the observed messages, the error dialog and the "
        "exception text - what a static check cannot see, e.g. a method of the object module called "
        "on form data. eventLog.errors adds the errors the platform logged during the run with "
        "message, module and line, which the client only shows as an error window. The infobase "
        "itself is never changed. Served by the wrapper; no EDT needed."
    ),
    "descriptionRu": (
        "Запустить основную форму собранного .epf или .erf в свежей копии тестовой базы "
        "(EDT_BRIDGE_TEST_INFOBASE, платформа EDT_BRIDGE_TEST_PLATFORM) штатным тестовым клиентом "
        "платформы, на Linux с Xvfb. Либо нажать одну команду (button) и получить сообщения и ошибку, "
        "либо проиграть сценарий (steps: input, click, assert_messages, assert_field, assert_cell, "
        "assert_table, assert_table_size; после последнего действия нужна проверка). Ответ содержит "
        "статус, сообщения, окно ошибки и текст исключения - то, чего не видит статическая проверка, "
        "например вызов метода модуля объекта у данных формы. eventLog.errors добавляет ошибки, "
        "записанные платформой в журнал регистрации за время запуска, с текстом, модулем и строкой: "
        "клиент показывает их только окном ошибки. Сама база не меняется. Обслуживает обвязка, EDT "
        "не нужна."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "path": {"type": "string",
                     "description": "Absolute path of the built .epf or .erf"},
            "form": {"type": "string",
                     "description": "Its main form: ExternalDataProcessor.Name.Form.FormName, or "
                                    "ExternalReport.Name.Form for a report opened with the standard "
                                    "report form"},
            "formTitle": {"type": "string",
                          "description": "Window title of the opened form. * (any run of characters) "
                                         "and ? (one character) are wildcards, e.g. \"Order 7 dated *\" "
                                         "for a title that carries a date"},
            "button": {"type": "string",
                       "description": "Caption of the command to press. Mutually exclusive with steps"},
            "expectMessage": {"type": "string",
                              "description": "Optional message text the button must produce"},
            "steps": {"type": "array", "items": {"type": "object"},
                      "description": "Scenario steps, e.g. [{\"action\": \"click\", \"button\": "
                                     "\"Check\"}, {\"action\": \"assert_messages\", \"text\": "
                                     "\"Done\", \"count\": 1}]"},
            "timeoutSeconds": {"type": "integer",
                               "description": "Whole run limit, 60-900 seconds (default 300)"},
        },
        "required": ["path", "form", "formTitle"],
    },
    # The tool sets that carry it (EDT_BRIDGE_TOOLSET): the jobs that build an .epf or an .erf.
    "toolsets": ["processor", "report"],
}


def _refusal(status: str, message: str) -> dict:
    return {"ok": False, "status": status, "error": message}


def run_tool(arguments: dict, *, runner=native_form.run, environ=os.environ) -> dict:
    """Validate the call, copy the test infobase and run the native check on the copy."""
    infobase, platform = environ.get(ENV_INFOBASE), environ.get(ENV_PLATFORM)
    if not infobase or not platform:
        return _refusal("not_configured", f"{ENV_INFOBASE} and {ENV_PLATFORM} must name the test "
                        "infobase directory and the platform bin directory of this host")
    source = Path(infobase)
    if not (source / DATA_FILE).is_file():
        return _refusal("not_configured", f"{ENV_INFOBASE} points to {source}, which has no {DATA_FILE}")
    path = Path(str(arguments.get("path") or ""))
    suffix = path.suffix.lower()
    if suffix not in (".epf", ".erf") or not path.is_file():
        return _refusal("invalid_input", "path must name an existing .epf or .erf file")
    button, steps = arguments.get("button"), arguments.get("steps")
    if (button is None) == (steps is None):
        return _refusal("invalid_input", "pass either button or steps")
    if steps is not None and not isinstance(steps, list):
        return _refusal("invalid_input", "steps must be a list of step objects")
    try:
        timeout = int(arguments.get("timeoutSeconds") or 300)
    except (TypeError, ValueError):
        return _refusal("invalid_input", "timeoutSeconds must be an integer")
    if not 60 <= timeout <= 900:
        return _refusal("invalid_input", "timeoutSeconds must be between 60 and 900")
    with tempfile.TemporaryDirectory(prefix="edt-bridge-form-run-") as temporary:
        root = Path(temporary)
        copy = root / "infobase"
        copy.mkdir()
        shutil.copyfile(source / DATA_FILE, copy / DATA_FILE)
        scenario = None
        if steps is not None:
            scenario = root / "scenario.json"
            # The scenario check of the native run requires the format version.
            scenario.write_text(json.dumps({"version": 1, "steps": steps}, ensure_ascii=False),
                                encoding="utf-8", newline="")
        args = argparse.Namespace(
            platform_bin=Path(platform), test_infobase=copy, scope="fixture",
            epf=path if suffix == ".epf" else None, erf=path if suffix == ".erf" else None,
            navigation_link=None, form=str(arguments.get("form") or ""),
            form_title=str(arguments.get("formTitle") or ""), button=button, scenario=scenario,
            expect_message=arguments.get("expectMessage"), user=environ.get(ENV_USER),
            output_dir=root / "output", timeout=timeout)
        report = runner(args)
    # The paths point into the copy that is gone now; the caller has no use for them.
    for key in ("testInfobase", "outputDir"):
        report.pop(key, None)
    return report
