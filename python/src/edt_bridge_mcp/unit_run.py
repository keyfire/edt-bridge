"""edt_unit_tests: run the YAXUnit tests of built extensions in a copy of the test infobase, as a tool.

A form scenario checks what a person sees; a unit test checks the logic under it, faster and
without the interface. The model builds the extension with its tests (edt_build_extension), and this
tool loads it, with the extension under test when that one is separate, into a fresh copy of the
test infobase and runs YAXUnit there: the answer names every test with its status, time, message
and the lines of the tested code it failed on.

The test infobase is configured by whoever runs the wrapper, not by the caller, and needs YAXUnit
loaded once (the extension YAXUNIT); the run never changes it.
"""
from __future__ import annotations

import argparse
import os
import tempfile
from pathlib import Path

from . import form_run, jobs, native_unit

DESCRIPTOR = {
    "name": "edt_unit_tests",
    "description": (
        "Run the YAXUnit tests of built configuration extensions (.cfe from edt_build_extension) in a "
        "fresh copy of the configured test infobase (EDT_BRIDGE_TEST_INFOBASE, platform "
        "EDT_BRIDGE_TEST_PLATFORM), on Linux with Xvfb. The extensions are loaded into the copy in the "
        "order given - the one with the test modules and, when separate, the one under test - and YAXUnit "
        "runs the tests of these extensions, narrowed by modules or tests (Module.Test or "
        "Module.Test.Context). The answer lists every test with status (passed, failed, error, "
        "skipped), time, message, expected and actual values and trace - the lines of the tested code, "
        "without YAXUnit's own frames - plus summary and eventLog.errors, the errors the platform logged "
        "during the run. status no_tests: the filter selected nothing; yaxunit_failed: YAXUnit stopped "
        "before its report, yaxunitErrors says why (a test module that does not compile, for one); "
        "yaxunit_missing: the test infobase has no YAXUnit; extension_name_mismatch: name differs from "
        "the name inside the file. The infobase itself is never changed. Served by the wrapper; no EDT "
        "needed."
    ),
    "descriptionRu": (
        "Выполнить тесты YAXUnit собранных расширений (.cfe из edt_build_extension) в свежей копии "
        "тестовой базы (EDT_BRIDGE_TEST_INFOBASE, платформа EDT_BRIDGE_TEST_PLATFORM), на Linux с Xvfb. "
        "Расширения загружаются в копию в указанном порядке: расширение с тестовыми модулями и, если оно "
        "отдельное, проверяемое расширение. YAXUnit выполняет тесты этих расширений с отбором по modules "
        "или tests (Модуль.Тест или Модуль.Тест.Контекст). Ответ перечисляет тесты со статусом (passed, "
        "failed, error, skipped), временем, сообщением, ожидаемым и фактическим значением и trace - "
        "строками проверяемого кода без кадров самого YAXUnit, - а также summary и eventLog.errors, "
        "ошибки журнала регистрации за время запуска. Статус no_tests: отбор ничего не выбрал; "
        "yaxunit_failed: YAXUnit остановился до отчета, причина в yaxunitErrors (например, тестовый "
        "модуль не компилируется); yaxunit_missing: в тестовой базе нет YAXUnit; "
        "extension_name_mismatch: name не совпадает с именем внутри файла. Сама база не меняется. "
        "Обслуживает обвязка, EDT не нужна."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "extensions": {
                "type": "array",
                "minItems": 1,
                "items": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "description": "Absolute path of the built .cfe"},
                        "name": {"type": "string",
                                 "description": "Name of the extension in its project, e.g. tests"},
                    },
                    "required": ["path", "name"],
                },
                "description": "Extensions to load into the copy, in this order: the one with the test "
                               "modules and, when separate, the one under test",
            },
            "modules": {"type": "array", "items": {"type": "string"},
                        "description": "Run only the tests of these modules, e.g. [\"ОМ_Заказы\"]"},
            "tests": {"type": "array", "items": {"type": "string"},
                      "description": "Run only these tests: Module.Test or Module.Test.Context, e.g. "
                                     "[\"ОМ_Заказы.СуммаЗаказа\"]"},
            "timeoutSeconds": {"type": "integer",
                               "description": "Whole run limit, 60-900 seconds (default 300)"},
            "async": {"type": "boolean", "description": jobs.ASYNC_DESCRIPTION},
        },
        "required": ["extensions"],
    },
    # The tool sets that carry it (EDT_BRIDGE_TOOLSET): the jobs that build a .cfe.
    "toolsets": ["extension"],
}


def _refusal(status: str, message: str) -> dict:
    return {"ok": False, "status": status, "error": message}


def run_tool(arguments: dict, *, runner=native_unit.run, environ=os.environ) -> dict:
    """Validate the call and run the tests; the run copies the test infobase itself."""
    infobase, platform = environ.get(form_run.ENV_INFOBASE), environ.get(form_run.ENV_PLATFORM)
    if not infobase or not platform:
        return _refusal("not_configured", f"{form_run.ENV_INFOBASE} and {form_run.ENV_PLATFORM} must name the "
                        "test infobase directory and the platform bin directory of this host")
    if not (Path(infobase) / form_run.DATA_FILE).is_file():
        return _refusal("not_configured", f"{form_run.ENV_INFOBASE} points to {infobase}, which has no "
                        f"{form_run.DATA_FILE}")
    extensions = arguments.get("extensions")
    if not isinstance(extensions, list) or not extensions:
        return _refusal("invalid_input", "extensions must list at least one {path, name} of a built .cfe")
    specs = []
    for extension in extensions:
        if not isinstance(extension, dict) or not all(isinstance(extension.get(key), str) for key in ("path", "name")):
            return _refusal("invalid_input", "each extension is an object with the string keys path and name")
        path = Path(extension["path"])
        if path.suffix.lower() != ".cfe" or not path.is_file():
            return _refusal("invalid_input", f"path must name an existing .cfe file: {extension['path']}")
        specs.append(f"{extension['name']}={path}")
    for key in ("modules", "tests"):
        value = arguments.get(key)
        if value is not None and (not isinstance(value, list) or not all(isinstance(item, str) for item in value)):
            return _refusal("invalid_input", f"{key} must be a list of strings")
    try:
        timeout = int(arguments.get("timeoutSeconds") or 300)
    except (TypeError, ValueError):
        return _refusal("invalid_input", "timeoutSeconds must be an integer")
    if not 60 <= timeout <= 900:
        return _refusal("invalid_input", "timeoutSeconds must be between 60 and 900")
    with tempfile.TemporaryDirectory(prefix="edt-bridge-unit-run-") as temporary:
        root = Path(temporary)
        args = argparse.Namespace(
            platform_bin=Path(platform), test_infobase=Path(infobase), extension=specs,
            module=list(arguments.get("modules") or []), test=list(arguments.get("tests") or []), user=environ.get(form_run.ENV_USER), output_dir=root / "output", timeout=timeout)
        report = runner(args)
    # A path into the directory that is gone now is of no use to the caller.
    return {key: value for key, value in report.items()
            if key not in ("testInfobase", "reports") and not (isinstance(value, str) and value.startswith(str(root)))}
