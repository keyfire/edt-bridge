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
import base64
import json
import os
import shutil
import tempfile
import time
from pathlib import Path

from . import jobs, native_form, native_reports, native_scenario

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
        "assert_cell, assert_table, assert_table_size, read_form, read_table; the last action must "
        "be followed by an assertion or a read). read_form returns every visible field, form table "
        "and spreadsheet of the form in reads; assert_table and read_table take a form table too. "
        "assert_messages counts the messages with its text that the preceding click added, so a "
        "repeated click is checked with count 1 again. A field selector that matches no field or "
        "several answers with observedFields, the fields of the open form by name and title. The "
        "answer carries status, the observed messages, the error dialog and the "
        "exception text - what a static check cannot see, e.g. a method of the object module called "
        "on form data. eventLog.errors adds the errors the platform logged during the run with "
        "message, module and line, which the client only shows as an error window. scenarios plays "
        "several named scenarios in one start of the client, which takes most of a call: the form is "
        "opened again before each next one, and the answer has the result of each scenario apart, so "
        "one that fails does not hide the others. The scenarios of one call share one copy of the "
        "infobase: what one writes the next sees; a scenario that needs a clean copy goes to a call "
        "of its own. The configured infobase itself is never changed. Served by the wrapper; no EDT "
        "needed. webClient=true runs the form in the web client and adds webForm, the form after the last "
        "step as text for a model without vision: one line per visible item (name | kind | title | value | "
        "x,y wxh | flags, e.g. clipped for cut text, cut or offscreen for an item out of the window, "
        "current for the open page), the rows of form tables, the panel messages and the names of hidden "
        "items. With EDT_BRIDGE_PII its values pass the filter and the answer adds a masked screenshot; "
        "screenshot=false leaves the image out. In that mode use path and button or steps for an external "
        "file, or navigationLink and steps for a stored document or catalog item."
    ),
    "descriptionRu": (
        "Запустить основную форму собранного .epf или .erf в свежей копии тестовой базы "
        "(EDT_BRIDGE_TEST_INFOBASE, платформа EDT_BRIDGE_TEST_PLATFORM) штатным тестовым клиентом "
        "платформы, на Linux с Xvfb. Либо нажать одну команду (button) и получить сообщения и ошибку, "
        "либо проиграть сценарий (steps: input, click, assert_messages, assert_field, assert_cell, "
        "assert_table, assert_table_size, read_form, read_table; после последнего действия нужна "
        "проверка или чтение). read_form возвращает в reads все видимые поля, таблицы формы и "
        "табличные документы формы; assert_table и read_table принимают и таблицу формы. "
        "assert_messages считает сообщения со своим текстом, которые добавило предыдущее нажатие, "
        "поэтому повторное нажатие снова проверяется с count 1. Селектор поля, не нашедший ровно "
        "одно поле, отвечает с observedFields: поля открытой формы с именем и заголовком. Ответ "
        "содержит статус, сообщения, окно ошибки и текст исключения - то, чего не видит статическая проверка, "
        "например вызов метода модуля объекта у данных формы. eventLog.errors добавляет ошибки, "
        "записанные платформой в журнал регистрации за время запуска, с текстом, модулем и строкой: "
        "клиент показывает их только окном ошибки. scenarios проигрывает несколько именованных "
        "сценариев за один старт клиента, который занимает большую часть вызова: перед каждым "
        "следующим форма открывается заново, а ответ несет итог каждого сценария отдельно, так что "
        "отказ одного не скрывает остальные. Сценарии одного вызова работают в одной копии базы: "
        "записанное одним видит следующий; сценарий, которому нужна чистая копия, запускается "
        "отдельным вызовом. Настроенная база не меняется. Обслуживает обвязка, EDT не нужна. "
        "webClient=true запускает форму в веб-клиенте и добавляет webForm - форму после последнего шага "
        "текстом для модели без зрения: строка на каждый видимый элемент (name | kind | title | value | "
        "x,y wxh | flags, например clipped для обрезанного текста, cut или offscreen для элемента за "
        "краем окна, current для открытой страницы), строки таблиц формы, сообщения панели и имена "
        "скрытых элементов. При EDT_BRIDGE_PII значения проходят фильтр, а ответ добавляет снимок с "
        "маской; screenshot=false убирает изображение. Для внешнего файла укажите path и button или "
        "steps. Для существующего документа или элемента справочника укажите navigationLink и steps."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "path": {"type": "string",
                     "description": "Absolute path of the built .epf or .erf"},
            "webClient": {"type": "boolean",
                          "description": "Use the web client: the answer adds webForm, the form as text, and with "
                                         "EDT_BRIDGE_PII a masked screenshot. Pass button or steps"},
            "screenshot": {"type": "boolean",
                           "description": "With webClient: return the masked screenshot as an image (default "
                                          "true). Pass false for a model without vision"},
            "navigationLink": {"type": "string",
                               "description": "Internal e1cib/data link to the stored document or catalog item "
                                              "in the test infobase. Pass with webClient=true and steps"},
            "form": {"type": "string",
                     "description": "Its main form: ExternalDataProcessor.Name.Form.FormName, or "
                                    "ExternalReport.Name.Form for a report opened with the standard "
                                    "report form. With webClient, use Document.Name.Form.FormName or "
                                    "Catalog.Name.Form.FormName"},
            "formTitle": {"type": "string",
                          "description": "Window title of the opened form. Optional: without it the form is "
                                         "found by its name when one form of that name is open. The window of "
                                         "an object form adds the object presentation to the form title, e.g. "
                                         "\"Check: Products\" for a form titled \"Check\", so pass a pattern such as "
                                         "\"Check*\". * (any run of characters) and ? (one character) are wildcards, "
                                         "e.g. \"Order 7 dated *\" for a title that carries a date"},
            "button": {"type": "string",
                       "description": "Caption of the command to press. Pass one of button, steps and "
                                      "scenarios"},
            "expectMessage": {"type": "string",
                              "description": "Optional message text the button must produce"},
            "steps": {"type": "array", "items": {"oneOf": native_scenario.step_schemas()},
                      "description": "Scenario steps. Each step is one of the kinds in items, with exactly "
                                     "the keys of its kind, e.g. " + native_scenario.EXAMPLE + ". A refused step is "
                                     "named with its unexpected and missing keys."},
            "scenarios": {"type": "array", "minItems": 1, "maxItems": native_scenario.MAX_SCENARIOS,
                          "items": {"type": "object",
                                    "properties": {"name": {"type": "string",
                                                            "description": "Unique name of the scenario"},
                                                   "steps": {"type": "array", "items": {"type": "object"},
                                                             "description": "Its steps, of the same kinds "
                                                                            "as in steps"}},
                                    "required": ["name", "steps"], "additionalProperties": False},
                          "description": "Named scenarios played one after another in one start of the "
                                         "client, the form opened again before each next one, e.g. "
                                         '[{"name": "Empty period", "steps": [...]}, '
                                         '{"name": "Two companies", "steps": [...]}]. They share one copy '
                                         "of the infobase. The answer has scenarios[] with the result of "
                                         "each and summary"},
            "timeoutSeconds": {"type": "integer",
                               "description": "Whole run limit, 60-900 seconds (default 300)"},
            "details": {"type": "boolean",
                        "description": "Add the diagnostics of the run: the seconds of each stage, the client "
                                       "environment, the platform logs and the echo of the scenario. Without it "
                                       "they come only when the run did not reach a verdict (default false)"},
            "async": {"type": "boolean", "description": jobs.ASYNC_DESCRIPTION},
        },
        "required": ["form"],
        "oneOf": [
            {"required": ["path"],
             "not": {"anyOf": [
                 {"required": ["navigationLink"]},
                 {"required": ["webClient", "scenarios"], "properties": {"webClient": {"const": True}}},
             ]}},
            {"required": ["navigationLink", "webClient", "steps"], "properties": {"webClient": {"const": True}},
             "not": {"required": ["path"]}},
        ],
    },
    # The tool sets that carry it (EDT_BRIDGE_TOOLSET): the jobs that build an .epf or an .erf.
    "toolsets": ["processor", "report"],
}


#: The statuses of a run that reached its verdict: the form ran, and the solution passed or did not.
#: Any other status is the run's own failure, and its answer keeps the diagnostics.
VERDICTS = frozenset({"scenario_completed", "command_completed"}) | native_reports.FAILURES
#: What the answer keeps of a run that reached its verdict: what the caller judges the solution by.
_ANSWER_KEYS = ("ok", "status", "error", "stage", "step", "assertions", "reads", "messages", "dialog",
                "observedFields", "matchedButtons", "observedButtons", "matchedFields", "observedForms",
                "observedTitles", "defaultForm", "scenarios", "summary", "piiFilter", "clientType",
                "webForm", "webFormError", "screenshotMasked", "screenshotWithheld", "screenshotData")


def _refusal(status: str, message: str) -> dict:
    return {"ok": False, "status": status, "error": message}


def answer(report: dict, details: bool = False) -> dict:
    """The answer of the tool. A run that reached its verdict answers with the verdict, the assertions, the
    reads, the messages, the window and the errors the platform logged; the stages, the client environment,
    the logs and the echo of the scenario come with details or when the run itself failed."""
    if details or report.get("status") not in VERDICTS:
        return report
    short = {key: report[key] for key in _ANSWER_KEYS if key in report}
    if report.get("ok"):
        short.pop("stage", None)
    interaction = report.get("interaction")
    if isinstance(interaction, dict):
        if "messages" not in short and isinstance(interaction.get("messages"), list):
            # A button check: the messages the click left on the panel.
            short["messages"] = interaction["messages"]
        if not report.get("ok"):
            # What the client said: the exception of a failed command, the observations up to a failed step.
            kept = {key: value for key, value in interaction.items()
                    if key not in ("observedForm", "observedTitle", "timingMarks", "scenarios")}
            if kept:
                short["interaction"] = kept
    log = report.get("eventLog")
    if isinstance(log, dict) and (log.get("errors") or log.get("read") is False):
        short["eventLog"] = log
    short["omitted"] = "the stages, the client environment and the logs: pass details=true"
    return short


def run_tool(arguments: dict, *, runner=native_form.run, environ=os.environ, on_stage=None) -> dict:
    """Validate the call, copy the test infobase and run the native check on the copy."""
    infobase, platform = environ.get(ENV_INFOBASE), environ.get(ENV_PLATFORM)
    if not infobase or not platform:
        return _refusal("not_configured", f"{ENV_INFOBASE} and {ENV_PLATFORM} must name the test "
                        "infobase directory and the platform bin directory of this host")
    source = Path(infobase)
    if not (source / DATA_FILE).is_file():
        return _refusal("not_configured", f"{ENV_INFOBASE} points to {source}, which has no {DATA_FILE}")
    path = Path(str(arguments.get("path") or ""))
    web_client = arguments.get("webClient") is True
    navigation_link = arguments.get("navigationLink")
    suffix = path.suffix.lower()
    button, steps, scenarios = arguments.get("button"), arguments.get("steps"), arguments.get("scenarios")
    if navigation_link is not None:
        if (not web_client or arguments.get("path") is not None
                or not isinstance(navigation_link, str) or steps is None):
            return _refusal("invalid_input", "navigationLink requires webClient and steps.")
    elif suffix not in (".epf", ".erf") or not path.is_file():
        return _refusal("invalid_input", "path must name an existing .epf or .erf file")
    if web_client and scenarios is not None:
        return _refusal("invalid_input", "The web client accepts steps or a button. Use the desktop client "
                        "for named scenarios.")
    if sum(value is not None for value in (button, steps, scenarios)) != 1:
        return _refusal("invalid_input", "pass one of button, steps and scenarios")
    if steps is not None and not isinstance(steps, list):
        return _refusal("invalid_input", "steps must be a list of step objects")
    content = None
    if steps is not None or scenarios is not None:
        content = {"version": 1, "steps": steps} if steps is not None else {"version": 1, "scenarios": scenarios}
        try:
            # The steps are checked before the copy of the infobase and the start of the client: a refused
            # step costs a call, not a minute.
            native_scenario.validate(content)
        except native_scenario.ScenarioError as error:
            return _refusal("invalid_input", str(error))
    try:
        timeout = int(arguments.get("timeoutSeconds") or 300)
    except (TypeError, ValueError):
        return _refusal("invalid_input", "timeoutSeconds must be an integer")
    if not 60 <= timeout <= 900:
        return _refusal("invalid_input", "timeoutSeconds must be between 60 and 900")
    with tempfile.TemporaryDirectory(prefix="edt-bridge-form-run-") as temporary:
        root = Path(temporary)
        copy = root / "infobase"
        if on_stage is not None:
            on_stage("infobaseCopy")
        copy_started = time.monotonic()
        if not web_client:
            copy.mkdir()
            shutil.copyfile(source / DATA_FILE, copy / DATA_FILE)
        copy_seconds = time.monotonic() - copy_started
        scenario = None
        if content is not None:
            scenario = root / "scenario.json"
            # The scenario check of the native run requires the format version.
            scenario.write_text(json.dumps(content, ensure_ascii=False), encoding="utf-8", newline="")
        args = argparse.Namespace(
            platform_bin=Path(platform), test_infobase=source if web_client else copy,
            scope="application" if navigation_link is not None else "fixture",
            epf=path if suffix == ".epf" else None, erf=path if suffix == ".erf" else None,
            web_client=web_client, navigation_link=navigation_link, form=str(arguments.get("form") or ""),
            form_title=None if arguments.get("formTitle") is None else str(arguments["formTitle"]),
            button=button, scenario=scenario,
            expect_message=arguments.get("expectMessage"), user=environ.get(ENV_USER),
            output_dir=root / "output", timeout=timeout, _on_stage=on_stage,
            web_screenshot=arguments.get("screenshot") is not False)
        report = runner(args)
        if (web_client and arguments.get("screenshot") is not False and report.get("screenshotMasked") is True
                and isinstance(report.get("screenshot"), str)):
            screenshot = Path(report["screenshot"]).resolve()
            if screenshot.is_relative_to(args.output_dir.resolve()):
                try:
                    report["screenshotData"] = base64.b64encode(screenshot.read_bytes()).decode("ascii")
                except OSError:
                    report["screenshotWithheld"] = True
        if web_client:
            for phase in report.get("phases", []):
                phase.pop("screenshot", None)
            if report.get("ok") and report.get("webFormError") and "screenshotData" not in report:
                report.update(ok=False, status="form_text_failed", error=report["webFormError"])
    # The paths point into the copy that is gone now; the caller has no use for them.
    for key in ("testInfobase", "outputDir", "reports"):
        report.pop(key, None)
    # The screenshot lay in the output of the run, which is gone with the copy as well.
    report.pop("screenshot", None)
    timings = report.get("timings")
    if not web_client and isinstance(timings, dict) and isinstance(timings.get("stages"), dict):
        # The copy of a large infobase is part of the run the caller waits for.
        timings["stages"] = {"infobaseCopy": round(copy_seconds, 2), **timings["stages"]}
        timings["totalSeconds"] = round(timings.get("totalSeconds", 0) + copy_seconds, 2)
    return answer(report, arguments.get("details") is True)
