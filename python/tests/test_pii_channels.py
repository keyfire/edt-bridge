"""The personal data filter on the channels of the wrapper: the form check and the unit tests."""
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from edt_bridge_mcp import native_form, native_unit, pii

from test_form_timings import _manager_setup
from test_native_unit import Platform as UnitPlatform, _args as unit_args, _client

PERSONAL = "Иванов Иван, ivanov@example.com, ИНН 7707083893"


def _form_check(monkeypatch, tmp_path, mode):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    if mode:
        monkeypatch.setenv(pii.ENV_MODE, mode)
    args, _, _ = _manager_setup(monkeypatch, tmp_path, [None])
    args.output_dir.rmdir()
    shot = tmp_path / "shot.png"
    shot.write_bytes(b"\x89PNG\r\n\x1a\nform")

    def session(*session_args):
        return {"ok": True, "status": "command_completed", "screenshot": str(shot), "cleanupComplete": True,
                "interaction": {"status": "command_completed", "messages": [f"Записан {PERSONAL}"],
                                "observedTitle": "Сотрудник"}}

    monkeypatch.setattr(native_form, "_run_session", session)
    return native_form.run(args), args, shot


def test_with_the_filter_off_the_form_check_answers_as_before(monkeypatch, tmp_path):
    report, _, shot = _form_check(monkeypatch, tmp_path, None)

    assert report["interaction"]["messages"] == [f"Записан {PERSONAL}"]
    assert report["screenshot"] == str(shot) and shot.is_file()
    assert "piiFilter" not in report


def test_the_form_check_masks_its_answer_its_files_and_keeps_its_verdict(monkeypatch, tmp_path):
    report, args, shot = _form_check(monkeypatch, tmp_path, "redact")

    assert report["ok"] is True and report["status"] == "command_completed"
    assert report["interaction"]["messages"] == ["Записан Иванов Иван, [email], ИНН [inn]"]
    # A picture of the form shows its data unmasked: it is not kept.
    assert "screenshot" not in report and report["screenshotWithheld"] is True
    assert not shot.exists()
    assert report["piiFilter"] == {"mode": "redact", "masked": 2}
    saved = json.loads((args.output_dir / "result.json").read_text(encoding="utf-8"))
    assert saved["interaction"]["messages"] == report["interaction"]["messages"]
    junit = (args.output_dir / "junit.xml").read_text(encoding="utf-8")
    assert "7707083893" not in junit and "ivanov@example.com" not in junit
    assert not list((args.output_dir / "allure-results").glob("*.png"))


def test_rules_that_cannot_be_read_refuse_the_check_before_it_starts(monkeypatch, tmp_path):
    monkeypatch.setenv(pii.ENV_MODE, "pseudonym")
    monkeypatch.setenv(pii.ENV_RULES, str(tmp_path / "missing.json"))
    report, _, _ = _form_check(monkeypatch, tmp_path, "pseudonym")

    assert report["status"] == "invalid_input" and pii.ENV_RULES in report["error"]


def test_the_unit_tests_mask_their_answer_and_the_files_of_yaxunit(monkeypatch, tmp_path):
    monkeypatch.setenv(pii.ENV_MODE, "redact")
    report_xml = ("<testsuites><testsuite classname=\"ОМ_Сотрудники\" package=\"tests\" context=\"Сервер\">"
                  "<testcase name=\"Почта\" time=\"0.1\"><failure message=\"ожидали ivanov@example.com\">"
                  "{tests ОбщийМодуль.ОМ_Сотрудники.Модуль(5)}:ЮТест.ОжидаетЧто(Почта).Равно(\"ivanov@example.com\");"
                  "</failure></testcase></testsuite></testsuites>")
    platform = UnitPlatform()
    platform.client = _client(report_xml, "1", log="10:00:00.000 [Клиент][ERR]: письмо ivanov@example.com\n")
    monkeypatch.setattr(native_unit.sys, "platform", "linux")
    monkeypatch.setattr(native_unit.shutil, "which", lambda name: name)
    monkeypatch.setattr(native_unit, "_display", lambda *args: {})
    monkeypatch.setattr(native_unit, "_spawn", platform.spawn)

    report = native_unit.run(unit_args(tmp_path))

    assert report["status"] == "failed"
    test = report["tests"][0]
    assert test["message"] == "ожидали [email]"
    assert test["trace"] == ['{tests ОбщийМодуль.ОМ_Сотрудники.Модуль(5)}:ЮТест.ОжидаетЧто(Почта).Равно("[email]");']
    assert report["yaxunitErrors"] == ["письмо [email]"]
    output = tmp_path / "result"
    for name in ("junit.xml", "yaxunit.log", "result.json"):
        assert "ivanov@example.com" not in (output / name).read_text(encoding="utf-8"), name
    ET.parse(output / "junit.xml")  # still a readable report
    assert report["piiFilter"]["mode"] == "redact"
