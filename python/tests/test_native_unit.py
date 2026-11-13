"""unit-tests: YAXUnit tests of built extensions in a copy of a test infobase.

ibcmd and the client are replaced by Platform below, which answers each call the way the platform
8.5.1.1529 and YAXUnit 25.12 did on 13.11.2026: ibcmd loads a file under any name and renames the
extension to the name inside the file when it is applied; the client started with
/C RunUnitTests=<file> writes the jUnit report, the exit code file and the log, and exits.
"""
import json
import subprocess
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest

from edt_bridge_mcp import cli, native_unit

REPORT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
\t<testsuite id="0" name="ОМ_Пример [Сервер]" classname="ОМ_Пример" tests="3" errors="1" skipped="0" failures="1" time="0.033" package="tests" context="Сервер">
\t\t<testcase name="Сложение" classname="ОМ_Пример.Сложение" time="0.004" context="Сервер"/>
\t\t<testcase name="Падение" classname="ОМ_Пример.Падение" time="0.006" context="Сервер">
\t\t\t<failure message="Сумма: ожидали, что проверяемое значение `4` равно `5`, но это не так." type="Утверждений">[Failed] &lt;Сумма: ожидали, что проверяемое значение `4` равно `5`, но это не так.&gt;
{YAXUNIT ОбщийМодуль.ЮТУтверждения.Модуль(2239)}:ВызватьИсключение ТекстИсключения;
{tests ОбщийМодуль.ОМ_Пример.Модуль(15)}:ЮТест.ОжидаетЧто(2 + 2, "сумма").Равно(5);
{(1)}:Объект.Падение()
{YAXUNIT ОбщийМодуль.ЮТМетодыСлужебный.Модуль(228)}:Выполнить(Выражение);

[ОшибкаВоВремяВыполненияВстроенногоЯзыка, ИсключениеВызванноеИзВстроенногоЯзыка]<expected>{
"#value": 5
}</expected>
\t\t\t\t<actual>{
"#value": 4
}</actual>
\t\t\t</failure>
\t\t</testcase>
\t\t<testcase name="СбойКода" classname="ОМ_Пример.СбойКода" time="0" context="Сервер">
\t\t\t<error message="Исполнения: Сбой в коде" type="Исполнения">Сбой в коде
{tests ОбщийМодуль.ОМ_Пример.Модуль(19)}:ВызватьИсключение "Сбой в коде";
{(1)}:Объект.СбойКода()
{YAXUNIT ОбщийМодуль.ЮТМетодыСлужебный.Модуль(228)}:Выполнить(Выражение);
</error>
\t\t</testcase>
\t</testsuite>
\t<properties>
\t\t<property name="ТестовыйДвижок" value="YAXUNIT"/>
\t\t<property name="ВерсияТестовогоДвижка" value="25.12"/>
\t</properties>
</testsuites>
"""

PASSED = """<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
\t<testsuite id="0" name="ОМ_Пример [Сервер]" classname="ОМ_Пример" tests="1" errors="0" skipped="0" failures="0" package="tests" context="Сервер">
\t\t<testcase name="Сложение" classname="ОМ_Пример.Сложение" time="0.005" context="Сервер"/>
\t</testsuite>
</testsuites>
"""

EMPTY = """<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
\t<properties><property name="ТестовыйДвижок" value="YAXUNIT"/></properties>
</testsuites>
"""

COMPILE_ERROR_LOG = """15:27:24.401 [Клиент][DBG]: Анализ модуля: ЮТРаботаСТекстом
15:27:24.411 [Клиент][ERR]: ЗагрузкаТестов:
Ошибка инициализации модуля: tests ОбщийМодуль.ОМ_Пример.Модуль
по причине:
{tests ОбщийМодуль.ОМ_Пример.Модуль(18,11)}: Ожидается имя процедуры
Процедура <<?>>Исключение() Экспорт
[ОшибкаКомпиляцииВстроенногоЯзыка]
15:27:24.411 [Клиент][ERR]: Невосстановимая ошибка. Работа будет прекращена.
"""


def _client(report=None, exit_code="0", log="15:27:40.173 [Клиент][INF]: Завершение работы YAxUnit\n",
            code=0, client_log=""):
    """A client that writes what YAXUnit writes and exits with `code`; report None writes none."""
    def run(config, argv):
        if report is not None:
            Path(config["reportPath"]).write_text(report, encoding="utf-8")
            Path(config["exitCode"]).write_text("﻿" + exit_code + "\r\n", encoding="utf-8")
        Path(config["logging"]["file"]).write_text("﻿" + log, encoding="utf-8")
        if client_log:
            Path(argv[argv.index("/Out") + 1]).write_text(client_log, encoding="utf-8")
        return code
    return run


class Platform:
    """ibcmd and the 1C:Enterprise client of one run, as the run starts them through _spawn."""

    def __init__(self, *, extensions=("YAXUNIT",), inside=None, refuse=None, client=None):
        self.extensions = list(extensions)
        self.inside = inside or {}      # file name -> the extension name the file holds
        self.refuse = refuse or {}      # "mode command" -> what ibcmd prints before it exits with 1
        self.client = client or _client(REPORT, "1")
        self.calls = []
        self.configs = []
        self._loading = None

    def spawn(self, _stack, argv, _env, log, **_kwargs):
        self.calls.append(list(argv))
        if Path(argv[0]).name == "ibcmd":
            return self._ibcmd(argv, log)
        config = json.loads(Path(argv[argv.index("/C") + 1].split("=", 1)[1]).read_text(encoding="utf-8"))
        self.configs.append(config)
        result = self.client(config, argv)
        if isinstance(result, BaseException):
            def wait(timeout=None):
                raise result
            return SimpleNamespace(wait=wait, poll=lambda: None, pid=0)
        return SimpleNamespace(wait=lambda timeout=None: result, poll=lambda: result, pid=0)

    def _ibcmd(self, argv, log):
        words = [word for word in argv[1:] if not word.startswith("-")]
        action = " ".join(words[:2])
        option = {word.split("=", 1)[0]: word.split("=", 1)[1] for word in argv if word.startswith("--") and "=" in word}
        text, code = "", 0
        if action in self.refuse:
            text, code = self.refuse[action], 1
        elif action == "extension list":
            text = "".join(f'name                         : "{name}"\nsafe-mode                    : yes\n\n'
                           for name in self.extensions)
        elif action == "config load":
            name = option["--extension"]
            self._loading = (name, Path(words[2]).name)
            if name not in self.extensions:
                self.extensions.append(name)
        elif action == "config apply":
            name, file = self._loading
            held = self.inside.get(file, name)
            self.extensions = [held if existing == name else existing for existing in self.extensions]
        log.write_text(text, encoding="utf-8")
        return SimpleNamespace(wait=lambda timeout=None: code, poll=lambda: code, pid=0)

    def ibcmd_actions(self):
        return [" ".join([word for word in call[1:] if not word.startswith("-")][:2])
                for call in self.calls if Path(call[0]).name == "ibcmd"]

    def clients(self):
        return [call for call in self.calls if "ENTERPRISE" in call]


def _args(tmp_path, *extra, extensions=("tests=tests.cfe",)):
    platform = tmp_path / "platform"
    platform.mkdir(exist_ok=True)
    for program in ("1cv8", "ibcmd"):
        (platform / program).touch()
    base = tmp_path / "test-infobase"
    base.mkdir(exist_ok=True)
    (base / "1Cv8.1CD").write_bytes(b"original")
    specs = []
    for spec in extensions:
        name, separator, file = spec.partition("=")
        if separator:
            (tmp_path / file).write_bytes(b"cfe")
            spec = f"{name}={tmp_path / file}"
        specs += ["--extension", spec]
    return cli.build_parser("unit-tests").parse_args([
        "--platform-bin", str(platform), "--test-infobase", str(base), *specs,
        "--output-dir", str(tmp_path / "result"), *extra])


@pytest.fixture
def platform(monkeypatch, tmp_path):
    # tmp_path comes first: pytest makes it with calls that do not exist on the platform faked here.
    fake = Platform()
    monkeypatch.setattr(native_unit.sys, "platform", "linux")
    monkeypatch.setattr(native_unit.shutil, "which", lambda name: name)
    monkeypatch.setattr(native_unit, "_display", lambda *args: {})
    monkeypatch.setattr(native_unit, "_capture", lambda *args: {"screenshot": "screenshot.png"})
    monkeypatch.setattr(native_unit, "_spawn", fake.spawn)
    return fake


def test_the_command_requires_the_infobase_the_platform_an_extension_and_an_output_directory():
    assert "unit-tests" in cli.COMMANDS
    parser = cli.build_parser("unit-tests")
    required = {action.dest for action in parser._actions if action.required}
    assert {"platform_bin", "test_infobase", "extension", "output_dir"} <= required


def test_a_run_reports_every_test_with_its_own_frames(platform, tmp_path):
    report = native_unit.run(_args(tmp_path))

    assert report["ok"] is False and report["status"] == "failed"
    assert report["summary"] == {"tests": 3, "passed": 1, "failed": 1, "errors": 1, "skipped": 0}
    assert report["yaxunitVersion"] == "25.12"
    passed, failed, broken = report["tests"]
    assert passed == {"module": "ОМ_Пример", "name": "Сложение", "context": "Сервер", "extension": "tests",
                      "status": "passed", "time": 0.004}
    assert failed["status"] == "failed"
    assert failed["message"] == "Сумма: ожидали, что проверяемое значение `4` равно `5`, но это не так."
    assert failed["expected"] == 5 and failed["actual"] == 4
    # The frames of YAXUnit itself and of its expression wrapper say nothing about the code under test.
    assert failed["trace"] == ['{tests ОбщийМодуль.ОМ_Пример.Модуль(15)}:ЮТест.ОжидаетЧто(2 + 2, "сумма").Равно(5);']
    assert broken["status"] == "error" and broken["message"] == "Исполнения: Сбой в коде"
    assert broken["trace"] == ['{tests ОбщийМодуль.ОМ_Пример.Модуль(19)}:ВызватьИсключение "Сбой в коде";']
    assert report["yaxunitExitCode"] == 1
    assert report["eventLog"] == {"read": True, "errors": []}
    assert (tmp_path / "result" / "result.json").is_file()
    assert (tmp_path / "result" / "junit.xml").read_text(encoding="utf-8") == REPORT


def test_the_extensions_are_loaded_into_a_copy_and_yaxunit_runs_only_them(platform, tmp_path):
    args = _args(tmp_path, "--module", "ОМ_Пример", "--test", "ОМ_Пример.Падение.Сервер",
                 extensions=("tests=tests.cfe", "Доработка=work.cfe"))

    native_unit.run(args)

    assert platform.ibcmd_actions() == [
        "extension list",
        "config load", "config apply", "extension list",
        "config load", "config apply", "extension list",
        "extension update", "extension update", "extension update"]
    copies = {word for call in platform.calls for word in call if word.startswith("--db-path=")}
    assert len(copies) == 1 and copies != {f"--db-path={args.test_infobase}"}
    assert (args.test_infobase / "1Cv8.1CD").read_bytes() == b"original"
    updates = [call for call in platform.calls if "update" in call]
    assert [next(word for word in call if word.startswith("--name=")) for call in updates] == [
        "--name=YAXUNIT", "--name=tests", "--name=Доработка"]
    assert all("--safe-mode=no" in call and "--unsafe-action-protection=no" in call for call in updates)
    config = platform.configs[0]
    assert config["filter"] == {"extensions": ["tests", "Доработка"], "modules": ["ОМ_Пример"],
                                "tests": ["ОМ_Пример.Падение.Сервер"]}
    assert config["reportFormat"] == "jUnit" and config["closeAfterTests"] is True
    assert config["showReport"] is False
    client = platform.clients()[0]
    assert client[client.index("/C") + 1].startswith("RunUnitTests=")
    assert next(word for word in client if word.startswith("/F")) == "/F" + next(iter(copies)).split("=", 1)[1]


def test_all_tests_passing_is_ok(platform, tmp_path):
    platform.client = _client(PASSED, "0")

    report = native_unit.run(_args(tmp_path))

    assert report["ok"] is True and report["status"] == "passed"
    assert report["summary"]["passed"] == 1


def test_a_filter_that_selects_nothing_is_not_a_pass(platform, tmp_path):
    platform.client = _client(EMPTY, "0")

    report = native_unit.run(_args(tmp_path, "--module", "НетТакого"))

    assert report["ok"] is False and report["status"] == "no_tests"
    assert report["tests"] == []


def test_a_failure_outside_the_tests_is_not_a_pass(platform, tmp_path):
    platform.client = _client(PASSED, "1", log="15:27:40.100 [Клиент][ERR]: Ошибка в событии ПослеВсехТестов\n")

    report = native_unit.run(_args(tmp_path))

    assert report["ok"] is False and report["status"] == "failed"
    assert report["yaxunitErrors"] == ["Ошибка в событии ПослеВсехТестов"]


def test_an_infobase_without_yaxunit_is_refused_before_anything_is_loaded(platform, tmp_path):
    platform.extensions = ["Прочее"]

    report = native_unit.run(_args(tmp_path))

    assert report["status"] == "yaxunit_missing"
    assert "YAXUNIT" in report["error"] and "Прочее" in report["error"]
    assert platform.ibcmd_actions() == ["extension list"]
    assert platform.clients() == []


def test_a_file_that_holds_another_extension_is_named(platform, tmp_path):
    platform.inside = {"tests.cfe": "Тесты"}

    report = native_unit.run(_args(tmp_path))

    assert report["status"] == "extension_name_mismatch"
    assert "Тесты" in report["error"] and "tests" in report["error"]
    assert platform.clients() == []


def test_a_name_that_differs_only_in_case_is_taken_as_the_platform_has_it(platform, tmp_path):
    platform.inside = {"tests.cfe": "Tests"}

    report = native_unit.run(_args(tmp_path))

    assert report["status"] == "failed"
    assert platform.configs[0]["filter"]["extensions"] == ["Tests"]
    assert "--name=Tests" in [word for call in platform.calls if "update" in call for word in call]


def test_an_extension_the_platform_refuses_carries_what_ibcmd_said(platform, tmp_path):
    platform.refuse = {"config apply": "Расширение не может быть применено: объект Справочник.Товары не найден"}

    report = native_unit.run(_args(tmp_path))

    assert report["status"] == "extension_load_failed"
    assert "Справочник.Товары не найден" in report["error"]
    assert platform.clients() == []


def test_yaxunit_that_stops_before_a_report_says_why(platform, tmp_path):
    platform.client = _client(None, log=COMPILE_ERROR_LOG, code=1,
                              client_log="Невосстановимая ошибка. Работа будет прекращена.")

    report = native_unit.run(_args(tmp_path))

    assert report["ok"] is False and report["status"] == "yaxunit_failed"
    assert "Ожидается имя процедуры" in report["error"]
    first, second = report["yaxunitErrors"]
    assert first.startswith("ЗагрузкаТестов:") and "Ожидается имя процедуры" in first
    assert second == "Невосстановимая ошибка. Работа будет прекращена."
    assert report["clientLog"] == "Невосстановимая ошибка. Работа будет прекращена."
    assert report["clientExitCode"] == 1


def test_a_client_that_does_not_finish_in_time_leaves_a_screenshot(platform, tmp_path):
    platform.client = lambda config, argv: subprocess.TimeoutExpired("1cv8", 1)

    report = native_unit.run(_args(tmp_path, "--timeout", "5"))

    assert report["status"] == "timeout"
    assert report["screenshot"] == "screenshot.png"


def test_a_license_refusal_is_named(platform, tmp_path):
    platform.client = _client(None, code=1, client_log="Не обнаружено свободной лицензии!")

    report = native_unit.run(_args(tmp_path))

    assert report["status"] == "license_unavailable"


def test_the_user_of_the_infobase_reaches_ibcmd_and_the_client(platform, tmp_path):
    native_unit.run(_args(tmp_path, "--user", "Тестировщик"))

    assert all("--user=Тестировщик" in call for call in platform.calls if Path(call[0]).name == "ibcmd")
    assert "/NТестировщик" in platform.clients()[0]


def test_failures_come_first_when_the_list_is_cut(platform, tmp_path, monkeypatch):
    monkeypatch.setattr(native_unit, "MAX_LISTED", 2)

    report = native_unit.run(_args(tmp_path))

    assert [entry["status"] for entry in report["tests"]] == ["failed", "error"]
    assert report["testsListed"] == 2 and report["summary"]["tests"] == 3


@pytest.mark.parametrize("extra, extensions", [
    ((), ("tests",)),                                  # no file
    ((), ("tests=tests.epf",)),                        # not an extension file
    ((), ("1tests=tests.cfe",)),                       # not a name
    ((), ("tests=a.cfe", "Tests=b.cfe")),              # the same name twice
    ((), ("YAXUNIT=tests.cfe",)),                      # the engine itself
    (("--test", "ОМ_Пример"), ("tests=tests.cfe",)),   # a test needs its module
    (("--module", "ОМ Пример"), ("tests=tests.cfe",)),
    (("--timeout", "0"), ("tests=tests.cfe",)),
])
def test_bad_calls_are_refused_before_anything_starts(platform, tmp_path, extra, extensions):
    report = native_unit.run(_args(tmp_path, *extra, extensions=extensions))

    assert report["status"] == "invalid_input" and report["stage"] == "validation"
    assert platform.calls == []


def test_an_output_directory_inside_the_infobase_is_refused(platform, tmp_path):
    args = _args(tmp_path)
    args.output_dir = args.test_infobase / "result"

    assert native_unit.run(args)["status"] == "invalid_input"


def test_the_run_needs_linux(monkeypatch, tmp_path):
    monkeypatch.setattr(native_unit.sys, "platform", "win32")

    assert native_unit.run(_args(tmp_path))["status"] == "unsupported"


def test_the_run_works_in_a_worker_thread(platform, tmp_path):
    # The MCP wrapper serves a tool call in a worker thread, and Python installs signal handlers
    # in the main thread only.
    reports = []
    worker = threading.Thread(target=lambda: reports.append(native_unit.run(_args(tmp_path))))
    worker.start()
    worker.join(30)

    assert not worker.is_alive()
    assert reports and reports[0]["status"] == "failed"
