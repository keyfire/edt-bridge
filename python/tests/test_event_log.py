import datetime
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from edt_bridge_mcp import cli, event_log, native_form

DICTIONARY = (
    "﻿1CV8LOG(ver 2.0)\r\n0f1e2d3c-0000-4000-8000-000000000001\r\n\r\n"
    '{1,071523a4-516f-4fce-ba4b-0d11ab7a1893,"",1},\r\n'
    '{2,"build-host",1},\r\n'
    '{3,"1CV8C",1},\r\n'
    '{4,"_$Session$_.Start",1},\r\n'
    '{4,"_$PerformError$_",2},\r\n'
    '{5,8ba98294-d58e-4210-bbb4-b6a491119ab3,"ВнешняяОбработка.Проверка",1},\r\n'
)

ERROR = ('Деление на 0\n'
         '{ВнешняяОбработка.Проверка.МодульОбъекта(12)}:Результат = 1 / Делитель;\n'
         '{ВнешняяОбработка.Проверка.Форма.Форма.Форма(7)}:Вычислить();\n\n'
         '[ОшибкаВоВремяВыполненияВстроенногоЯзыка, ОшибкаИспользованияВстроенногоЯзыка]')


def _record(stamp, event, severity, comment, metadata=0):
    quoted = comment.replace('"', '""')
    return (f'{{{stamp},N,\r\n{{0,0}},1,1,1,1,{event},{severity},"{quoted}",{metadata},\r\n'
            '{"U"},"",0,0,0,1,0,\r\n{0}\r\n},\r\n')


def _write_log(base: Path, records: str) -> Path:
    folder = base / "1Cv8Log"
    folder.mkdir(parents=True)
    (folder / "1Cv8.lgf").write_text(DICTIONARY, encoding="utf-8", newline="")
    (folder / "20261111220000.lgp").write_text(
        "﻿1CV8LOG(ver 2.0)\r\n0f1e2d3c-0000-4000-8000-000000000001\r\n\r\n" + records,
        encoding="utf-8", newline="")
    return folder


def test_error_record_carries_message_module_line_and_stack(tmp_path):
    folder = _write_log(tmp_path, _record("20261111221000", 1, "I", "")
                        + _record("20261111221005", 2, "E", ERROR, metadata=1))

    errors = event_log.read(folder)

    assert len(errors) == 1
    error = errors[0]
    assert error["event"] == "_$PerformError$_"
    assert error["severity"] == "Error"
    assert error["application"] == "1CV8C"
    assert error["metadata"] == "ВнешняяОбработка.Проверка"
    assert error["message"] == "Деление на 0"
    assert (error["module"], error["line"]) == ("ВнешняяОбработка.Проверка.МодульОбъекта", 12)
    assert [frame["line"] for frame in error["stack"]] == [12, 7]
    assert error["stack"][1]["code"] == "Вычислить();"
    assert error["categories"] == ["ОшибкаВоВремяВыполненияВстроенногоЯзыка", "ОшибкаИспользованияВстроенногоЯзыка"]
    assert error["time"] == "2026-11-11 22:10:05"


def test_time_window_and_severity_filter(tmp_path):
    folder = _write_log(tmp_path, _record("20261111215959", 2, "E", "before")
                        + _record("20261111221000", 2, "W", "warning")
                        + _record("20261111221001", 2, "E", "inside"))

    inside = event_log.read(folder, since=datetime.datetime(2026, 11, 11, 22, 0, 0, 500000))

    assert [error["comment"] for error in inside] == ["inside"]
    both = event_log.read(folder, severities=("E", "W"))
    assert [error["comment"] for error in both] == ["before", "warning", "inside"]


def test_quotes_braces_and_line_breaks_inside_a_comment_survive(tmp_path):
    comment = 'Строка "в кавычках" и {скобки},\nвторая строка'
    folder = _write_log(tmp_path, _record("20261111221000", 2, "E", comment))

    assert event_log.read(folder)[0]["comment"] == comment


def test_a_log_without_dictionary_or_with_broken_braces_is_refused(tmp_path):
    folder = tmp_path / "1Cv8Log"
    folder.mkdir()
    with pytest.raises(event_log.EventLogError):
        event_log.read(folder)
    with pytest.raises(event_log.EventLogError):
        event_log.parse_lists('{1,"open"')
    assert event_log.read(tmp_path / "missing") == []


def _run_with_log(monkeypatch, tmp_path, records):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    base = tmp_path / "base"
    base.mkdir()
    (base / "1Cv8.1CD").touch()
    epf = tmp_path / "check.epf"
    epf.touch()
    args = cli.build_parser("test-form").parse_args([
        "--platform-bin", str(tmp_path), "--test-infobase", str(base), "--scope", "fixture", "--epf", str(epf),
        "--form", "ExternalDataProcessor.Check.Form.Main", "--form-title", "Check", "--button", "Run",
        "--output-dir", str(tmp_path / "result")])

    def check(_args, _base, _external, _output, report):
        if records is not None:
            stamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
            _write_log(base, records(stamp))
        report.update(ok=False, status="command_error", interaction={"error": "Непредвиденная ошибка"})

    monkeypatch.setattr(native_form, "_native_check", check)
    return native_form.run(args)


def test_form_check_reports_the_logged_error_of_its_run(monkeypatch, tmp_path):
    report = _run_with_log(monkeypatch, tmp_path, lambda stamp: _record(stamp, 2, "E", ERROR))

    log = report["eventLog"]
    assert log["read"] is True
    assert log["errors"][0]["module"] == "ВнешняяОбработка.Проверка.МодульОбъекта"
    assert log["errors"][0]["line"] == 12
    saved = json.loads((tmp_path / "result" / "result.json").read_text(encoding="utf-8"))
    assert saved["eventLog"]["errors"][0]["message"] == "Деление на 0"


def test_form_check_without_errors_reports_an_empty_list(monkeypatch, tmp_path):
    clean = _run_with_log(monkeypatch, tmp_path, lambda stamp: _record(stamp, 1, "I", ""))
    assert clean["eventLog"] == {"read": True, "errors": []}


def test_form_check_ignores_errors_logged_before_it_started(monkeypatch, tmp_path):
    report = _run_with_log(monkeypatch, tmp_path, lambda stamp: _record("20200101000000", 2, "E", ERROR))
    assert report["eventLog"]["errors"] == []


def test_form_check_without_event_log_folder(monkeypatch, tmp_path):
    report = _run_with_log(monkeypatch, tmp_path, None)
    assert report["eventLog"] == {"read": True, "errors": []}
