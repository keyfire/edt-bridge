"""The personal data filter (EDT-PII-01): the shared cases, the settings and the walk over an answer."""
import json
from pathlib import Path

import pytest

from edt_bridge_mcp import pii

CASES = Path(__file__).resolve().parents[2] / "tests" / "pii" / "cases.tsv"


def _cases():
    cases = []
    for number, line in enumerate(CASES.read_text(encoding="utf-8").splitlines(), 1):
        if line and not line.startswith("#"):
            mode, name, value, expected = [field.replace("<empty>", "") for field in line.split("\t")]
            cases.append(pytest.param(mode, None if name == "-" else name, value, expected, id=f"line {number}"))
    return cases


@pytest.mark.parametrize("mode, name, value, expected", _cases())
def test_the_shared_cases(mode, name, value, expected):
    # The bridge answers the same cases in Java (core/PersonalDataTest): the two must not drift apart.
    assert pii.Filter(mode, "test-salt").named(name, value) == expected


def test_a_filter_that_is_off_changes_nothing():
    answer = {"fields": [{"name": "ФИО", "presentation": "Иванов Иван"}], "messages": ["ИНН 7707083893"]}
    flt = pii.Filter.from_env({})

    assert flt.active is False
    assert pii.mask(answer, flt) is answer


def test_the_settings_come_from_the_environment(tmp_path):
    rules = tmp_path / "rules.json"
    rules.write_text(json.dumps({"values": [{"kind": "contract", "pattern": "Д-[0-9]{6}"}],
                                 "names": [{"kind": "person", "pattern": "Сотрудник"}]}, ensure_ascii=False),
                     encoding="utf-8")
    flt = pii.Filter.from_env({pii.ENV_MODE: "Redact", pii.ENV_RULES: str(rules)})

    assert flt.text("по договору Д-123456") == "по договору [contract]"
    assert flt.named("Сотрудник", "Петров") == "[person]"
    with pytest.raises(pii.RulesError):
        pii.Filter.from_env({pii.ENV_MODE: "mask"})
    rules.write_text("{not json", encoding="utf-8")
    with pytest.raises(pii.RulesError):
        pii.Filter.from_env({pii.ENV_MODE: "redact", pii.ENV_RULES: str(rules)})


def test_a_salt_keeps_pseudonyms_across_processes_and_none_keeps_them_within_one():
    first, second = pii.Filter("pseudonym", "salt"), pii.Filter("pseudonym", "salt")
    assert first.text("a@b.ru") == second.text("a@b.ru")
    unsalted = pii.Filter("pseudonym")
    assert unsalted.text("a@b.ru") == unsalted.text("A@B.RU") != first.text("a@b.ru")


def test_the_walk_masks_data_by_field_and_by_shape_and_leaves_the_check_alone():
    answer = {
        "status": "scenario_completed", "screenshot": "/srv/out/7707083893/steps/screenshot.png",
        "reads": [{"fields": [{"name": "ФИО", "title": "ФИО", "presentation": "Иванов Иван"},
                              {"name": "Комментарий", "title": "Комментарий", "presentation": "звонить +7 912 345-67-89"}],
                   "tables": [{"name": "Сотрудники", "columns": [{"name": "Фамилия", "title": "Фамилия"},
                                                                 {"name": "Оклад", "title": "Оклад"}],
                               "rows": [["Петров", "50000"]]}]}],
        "messages": ["Контрагент с ИНН 7707083893 записан"],
        "eventLog": {"errors": [{"user": "Иванов И.И.", "comment": "ошибка для ivanov@example.com",
                                 "module": "ОбщийМодуль.Заказы.Модуль", "line": 7}]},
    }
    flt = pii.Filter("redact")

    masked = pii.mask(answer, flt)

    assert masked["status"] == "scenario_completed"
    assert masked["screenshot"] == answer["screenshot"]
    fields = masked["reads"][0]["fields"]
    assert fields[0] == {"name": "ФИО", "title": "ФИО", "presentation": "[person]"}
    assert fields[1]["presentation"] == "звонить [phone]"
    assert masked["reads"][0]["tables"][0]["rows"] == [["[person]", "50000"]]
    assert masked["messages"] == ["Контрагент с ИНН [inn] записан"]
    error = masked["eventLog"]["errors"][0]
    assert error["user"] == "[user]" and error["comment"] == "ошибка для [email]"
    assert error["module"] == "ОбщийМодуль.Заказы.Модуль" and error["line"] == 7
    assert flt.marker() == {"mode": "redact", "masked": 6}
