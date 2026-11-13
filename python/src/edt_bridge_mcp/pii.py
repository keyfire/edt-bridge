"""Personal data in the answers that carry infobase data (EDT-PII-01).

A test infobase is easily a copy of a working one, and what a tool returns from it goes to a cloud
model: the values of a form, the actual values of a check, messages, error windows, the event log,
expected and actual values of a unit test. With EDT_BRIDGE_PII set, those answers are filtered before
they leave: personal data found by its shape (e-mail, phone, SNILS and INN with their check digits, a
card number by Luhn, a passport) and every value of a field named like personal data (ФИО, Фамилия...)
is replaced with its kind, `[inn]`, or with a pseudonym, `[inn#icbbphoh]`: an HMAC of the value under a
salt, the same for the same value, with no table that leads back.

Only the channels of data are filtered. Module texts, schemas and templates are what a model writes
back; masking them would break the code and its sourceHash, so this module never touches them.

The bridge applies the same rules in Java (core/PersonalData); tests/pii/cases.tsv holds the cases both
implementations must answer alike.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
from pathlib import Path

ENV_MODE = "EDT_BRIDGE_PII"
ENV_SALT = "EDT_BRIDGE_PII_SALT"
ENV_RULES = "EDT_BRIDGE_PII_RULES"
MODES = ("off", "redact", "pseudonym")
#: The letters a pseudonym is written in: no digit, so no rule finds a number inside one.
_LETTERS = "abcdefghijklmnop"
_NUMERIC = {"card", "snils", "passport", "inn", "phone"}

#: Personal data by its shape, applied in this order; a replacement holds no digit for the next rule.
_VALUES = [
    ("email", r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(?![A-Za-z0-9-])"),
    ("card", r"(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])"),
    ("snils", r"(?<![0-9])[0-9]{3}-?[0-9]{3}-?[0-9]{3}[ -]?[0-9]{2}(?![0-9])"),
    ("passport", r"(?<![0-9])[0-9]{2} ?[0-9]{2} (?:№ ?)?[0-9]{6}(?![0-9])"),
    ("inn", r"(?<![0-9.,])(?:[0-9]{12}|[0-9]{10})(?![0-9]|[.,][0-9])"),
    ("phone", r"(?<![0-9+])(?:\+7|8)[ (-]*[0-9]{3}[ )-]*[0-9]{3}[ -]*[0-9]{2}[ -]*[0-9]{2}(?![0-9])"),
]
#: Fields whose every value is personal data, by the last segment of their name.
_NAMES = [
    ("person", r"ФИО.*|Фамилия.*|Имя|Отчество.*|FullName.*|LastName.*|FirstName.*|MiddleName.*|Surname.*"),
    ("birthdate", r"ДатаРождения.*|BirthDate.*|DateOfBirth.*"),
    ("passport", r"Паспорт.*|Passport.*"),
    ("phone", r"Телефон.*|Phone.*"),
    ("address", r"Адрес.*|Address.*"),
    ("email", r"ЭлектроннаяПочта.*|ЭлПочта.*|E-?mail.*"),
]


def _digits(text):
    return [int(char) for char in text if char.isdigit()]


def _inn(digits):
    def check(weights, values):
        return sum(weight * value for weight, value in zip(weights, values)) % 11 % 10
    if len(digits) == 10:
        return check([2, 4, 10, 3, 5, 9, 4, 6, 8], digits) == digits[9]
    return (len(digits) == 12 and check([7, 2, 4, 10, 3, 5, 9, 4, 6, 8], digits) == digits[10]
            and check([3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8], digits) == digits[11])


def _date_like(digits):
    """A stamp YYYYMMDDhh or YYYYMMDDhhmm: a date that looks like an INN is not one."""
    if len(digits) not in (10, 12):
        return False
    text = "".join(map(str, digits))
    year, month, day, hour = int(text[:4]), int(text[4:6]), int(text[6:8]), int(text[8:10])
    valid = 1900 <= year <= 2099 and 1 <= month <= 12 and 1 <= day <= 31 and hour <= 23
    return valid and (len(text) == 10 or int(text[10:12]) <= 59)


def _snils(digits):
    if len(digits) != 11:
        return False
    total = sum(value * (9 - index) for index, value in enumerate(digits[:9]))
    check = total if total < 100 else 0 if total in (100, 101) else total % 101
    return (0 if check == 100 else check) == digits[9] * 10 + digits[10]


def _luhn(digits):
    total = 0
    for index, value in enumerate(reversed(digits)):
        if index % 2:
            value *= 2
            value -= 9 if value > 9 else 0
        total += value
    return total % 10 == 0


_VALID = {
    "card": lambda digits: 13 <= len(digits) <= 19 and _luhn(digits),
    "snils": _snils,
    "inn": lambda digits: _inn(digits) and not _date_like(digits),
}


#: The salt of a process without EDT_BRIDGE_PII_SALT: its pseudonyms hold until it exits.
_PROCESS_SALT = secrets.token_hex(16)


class RulesError(ValueError):
    pass


class Filter:
    """The filter of one answer: its mode, salt and rules, and how many values it replaced."""

    def __init__(self, mode="off", salt=None, values=(), names=()):
        if mode not in MODES:
            raise RulesError(f"{ENV_MODE} must be one of {', '.join(MODES)}, not {mode!r}")
        self.mode = mode
        # Without a salt a pseudonym is stable for this process only.
        self.salt = (salt or _PROCESS_SALT).encode("utf-8")
        self.values = [(kind, re.compile(pattern)) for kind, pattern in _VALUES] + list(values)
        self.names = [(kind, re.compile(pattern, re.IGNORECASE)) for kind, pattern in _NAMES] + list(names)
        self.count = 0

    @property
    def active(self):
        return self.mode != "off"

    @classmethod
    def from_env(cls, environ=os.environ):
        mode = (environ.get(ENV_MODE) or "off").strip().lower() or "off"
        values, names = [], []
        rules = (environ.get(ENV_RULES) or "").strip()
        if rules and mode != "off":
            try:
                data = json.loads(Path(rules).read_text(encoding="utf-8-sig"))
                values = [(str(item["kind"]), re.compile(item["pattern"])) for item in data.get("values", [])]
                names = [(str(item["kind"]), re.compile(item["pattern"], re.IGNORECASE))
                         for item in data.get("names", [])]
            except (OSError, ValueError, KeyError, TypeError, AttributeError, re.error) as error:
                raise RulesError(f"{ENV_RULES} {rules}: {error}") from error
        return cls(mode, environ.get(ENV_SALT), values, names)

    def _token(self, kind, value):
        self.count += 1
        if self.mode == "redact":
            return f"[{kind}]"
        if kind in _NUMERIC:
            normal = "".join(char for char in value if char.isdigit())
            if kind == "phone" and len(normal) == 11 and normal[0] == "8":
                normal = "7" + normal[1:]
        else:
            normal = " ".join(value.lower().split())
        digest = hmac.new(self.salt, f"{kind}:{normal}".encode("utf-8"), hashlib.sha256).hexdigest()[:8]
        return f"[{kind}#{''.join(_LETTERS[int(char, 16)] for char in digest)}]"

    def text(self, value):
        """A value with the personal data found by its shape replaced."""
        if not self.active or not isinstance(value, str) or not value:
            return value
        for kind, pattern in self.values:
            check = _VALID.get(kind)

            def replace(match, kind=kind, check=check):
                found = match.group(0)
                if check is not None and not check(_digits(found)):
                    return found
                return self._token(kind, found)
            value = pattern.sub(replace, value)
        return value

    def kind_of(self, name):
        """The kind of personal data a field of this name holds, or None."""
        if not isinstance(name, str) or not name.strip():
            return None
        last = name.strip().rsplit(".", 1)[-1]
        return next((kind for kind, pattern in self.names if pattern.fullmatch(last)), None)

    def named(self, name, value):
        """A value of the field `name`: whole when the name says it is personal data, else by its shape."""
        if not self.active or not isinstance(value, str) or not value:
            return value
        kind = self.kind_of(name)
        return self._token(kind, value) if kind else self.text(value)

    def marker(self):
        return {"mode": self.mode, "masked": self.count}


#: Keys whose values are paths, statuses and names of the check, never data.
_TECHNICAL = frozenset({
    "screenshot", "screenshotError", "junit", "allure", "testInfobase", "epf", "erf", "navigationLink", "form",
    "status", "stage", "phase", "scope", "scopeSource", "action", "kind", "formName", "module", "extension",
    "context", "name", "field", "button", "button_name", "file", "path", "key", "source", "event", "application",
    "severity", "time", "type", "framework", "piiFilter", "expression", "signature", "session", "infobase", "appId",
    "startedAt",
})
#: Keys whose values belong to the field a sibling `field`, `name` or `expression` names.
_VALUE_KEYS = frozenset({"value", "presentation", "text", "actual", "expected", "equals"})
_OWNER_KEYS = ("field", "name", "expression")
#: Keys that are personal data by themselves: the user and the computer of a session or an event.
_WHOLE = {"user": "user", "userName": "user", "computer": "computer", "host": "computer"}


def mask(node, flt, name=None, data=False):
    """The answer `node` with every value of data filtered; the structure and the technical keys stay.

    `name` is the field the node belongs to; `data` says the node is the value of a field (under
    `value`, `actual`...), so a number there is filtered too. Elsewhere a number is a count, a line or a
    time, and only the name of its field can make it personal data."""
    if not flt.active:
        return node
    if isinstance(node, str):
        return flt.named(name, node)
    if isinstance(node, list):
        return [mask(item, flt, name, data) for item in node]
    if isinstance(node, (int, float)) and not isinstance(node, bool):
        kind = flt.kind_of(name)
        if kind:
            return flt._token(kind, str(node))
        if data:
            text = str(node)
            masked = flt.text(text)
            return node if masked == text else masked
        return node
    if not isinstance(node, dict):
        return node
    owner = next((node[key] for key in _OWNER_KEYS if isinstance(node.get(key), str)), None)
    columns = node.get("columns") if isinstance(node.get("columns"), list) else None
    result = {}
    for key, value in node.items():
        if key in _TECHNICAL:
            result[key] = value
        elif key in _WHOLE and isinstance(value, str) and value:
            result[key] = flt._token(_WHOLE[key], value)
        elif key == "rows" and columns is not None and isinstance(value, list):
            names = [column.get("name") if isinstance(column, dict) else None for column in columns]
            result[key] = [[mask(cell, flt, names[index] if index < len(names) else None, True)
                            for index, cell in enumerate(row)] if isinstance(row, list) else mask(row, flt)
                           for row in value]
        elif key in _VALUE_KEYS and owner in _WHOLE and isinstance(value, str) and value:
            result[key] = flt._token(_WHOLE[owner], value)
        elif key in _VALUE_KEYS:
            result[key] = mask(value, flt, owner, True)
        else:
            result[key] = mask(value, flt, key)
    return result


def withhold_screenshots(node):
    """Remove the screenshots a filtered answer names: a picture of a form shows its data unmasked."""
    if isinstance(node, list):
        for item in node:
            withhold_screenshots(item)
    elif isinstance(node, dict):
        path = node.pop("screenshot", None)
        if isinstance(path, str) and path:
            try:
                Path(path).unlink(missing_ok=True)
            except OSError:
                pass
            node["screenshotWithheld"] = True
        for value in node.values():
            withhold_screenshots(value)


def mask_files(paths, flt):
    """Filter text files written by another program (the reports of YAXUnit).

    A JSON file is read and filtered value by value: the shape rules over its raw text would take a
    number of its structure, a time in milliseconds, for a card and break the file. Other text is
    filtered by the shape of the data."""
    for path in paths:
        path = Path(path)
        if not flt.active or not path.is_file():
            continue
        text = path.read_text(encoding="utf-8-sig", errors="replace")
        if path.suffix.lower() == ".json":
            try:
                data = json.loads(text)
            except ValueError:
                data = None
            if data is not None:
                masked_data = mask(data, flt)
                if masked_data != data:
                    path.write_text(json.dumps(masked_data, ensure_ascii=False, indent=1), encoding="utf-8",
                                    newline="")
                continue
        masked = flt.text(text)
        if masked != text:
            path.write_text(masked, encoding="utf-8", newline="")
