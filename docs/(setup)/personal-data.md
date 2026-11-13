---
title: "Personal data"
description: "The EDT_BRIDGE_PII filter: which answers it masks, how it finds personal data, the redact and pseudonym modes, rules of your own and the fields of a filtered answer."
sidebar:
  label: "Personal data"
  order: 3
---

A test infobase is often a copy of a working one, and whatever a tool returns from it goes to the
model. With `EDT_BRIDGE_PII` set to `redact` or `pseudonym`, the answers that carry infobase data
are filtered before they leave. The filter is off by default.

## Turn it on

The filter runs on two sides, and each side reads the settings of its own process:

- the wrapper `edt-bridge-mcp` filters `edt_test_form`, `edt_unit_tests` and their commands
  `test-form` and `unit-tests`;
- the plugin inside EDT filters its own tools.

Set the variables in the server entry of the MCP client; the wrapper reads them at start:

```bash
claude mcp add edt-bridge -e EDT_BRIDGE_PII=pseudonym -e EDT_BRIDGE_PII_SALT=<secret> -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

A headless EDT that the wrapper starts inherits its environment, so this one entry covers both
sides. An EDT you start yourself, with the GUI or headless, reads the variables of its own process
or the launch properties `-Dedt.bridge.pii`, `-Dedt.bridge.pii.salt` and `-Dedt.bridge.pii.rules`
after `-vmargs`; a launch property takes precedence over a variable. `scripts/run-headless.ps1` and
`scripts/run-headless.sh` pass the three variables on to EDT. All three are listed in
[Settings](/settings#environment-variables).

## What is filtered

| Side | Tools | What is filtered |
|------|-------|------------------|
| plugin | `edt_debug_inspect`, `edt_evaluate` | the values of variables and expressions in a suspended frame |
| plugin | `edt_infobase_sessions`, `edt_infobase_maintenance`, `edt_designer_agent` | the users and computers of sessions, and any personal data in the rest of the answer |
| wrapper | `edt_test_form`, `test-form` | the answer and `result.json`, `junit.xml` and `allure-results/` in the output directory; screenshots are deleted |
| wrapper | `edt_unit_tests`, `unit-tests` | the answer and `result.json`, `junit.xml`, `allure-results/` and `yaxunit.log`; files that are not text are deleted |

The answers of other tools do not change. Module texts, data composition schemas and templates
are never filtered: the model writes them back, so their `sourceHash` stays the same.

A check still compares the real values. Its status and the result of every assertion come from
what the client saw; only the answer is masked. In `redact` mode two different values can
therefore look the same in `expected` and `actual`; `pseudonym` keeps them apart.

## How the filter finds personal data

**By the shape of a value**, anywhere in a string:

| Kind | What matches |
|------|--------------|
| `email` | an e-mail address |
| `card` | 13 to 19 digits, with spaces or dashes, that pass the Luhn check |
| `snils` | a SNILS whose check number is correct |
| `passport` | a passport series and number, `45 01 123456` or `4501 № 123456` |
| `inn` | an INN of 10 or 12 digits whose check digits are correct; a number that is part of a fraction or reads as a date and time, `2026111319`, is left alone |
| `phone` | a Russian phone number starting with `+7` or `8` |

**By the name of a field.** A person's name cannot be told by its value, so every value of a field
named like personal data is replaced. The name comes from the `field`, `name` or `expression` next
to the value, or from the column of a table. Only the last part of a dotted name is compared, whole
and with case ignored: `Контрагент.Телефон` and `ТелефонРабочий` are phones, `ИмяФайла` is not a name.

| Kind | Field names |
|------|-------------|
| `person` | `ФИО...`, `Фамилия...`, `Имя`, `Отчество...`, `FullName...`, `LastName...`, `FirstName...`, `MiddleName...`, `Surname...` |
| `birthdate` | `ДатаРождения...`, `BirthDate...`, `DateOfBirth...` |
| `passport` | `Паспорт...`, `Passport...` |
| `phone` | `Телефон...`, `Phone...` |
| `address` | `Адрес...`, `Address...` |
| `email` | `ЭлектроннаяПочта...`, `ЭлПочта...`, `Email...`, `E-mail...` |

The user and the computer of a session or an event (`user`, `userName`, `computer`, `host`) are
replaced whole.

A name in free text, such as the message `Записан Иванов Иван`, is not found: it has neither a
recognizable shape nor a field name.

## The two modes

`redact` writes the kind of the value: `[inn]`. `pseudonym` writes the kind and a pseudonym:
`[inn#icbbphoh]`. The pseudonym is an HMAC-SHA256 of the value under a salt. The same value gets
the same pseudonym however it is written: digits with or without spaces, a phone with `8` or `+7`,
text in any case. The value cannot be recovered from its pseudonym: there is no lookup table.

Without `EDT_BRIDGE_PII_SALT` each process generates a random salt at start. Pseudonyms then stay
the same within one run of the wrapper or of EDT, but differ between the plugin and the wrapper and
from one session to the next. Set the salt to compare them across sessions, and keep it out of the
repository.

## Rules of your own

`EDT_BRIDGE_PII_RULES` names a JSON file with rules that are added to the built-in ones:

```json
{
  "values": [
    {"kind": "contract", "pattern": "ДГ-[0-9]{6}"}
  ],
  "names": [
    {"kind": "person", "pattern": "Сотрудник.*"},
    {"kind": "account", "pattern": "ЛицевойСчет"}
  ]
}
```

A `values` pattern is searched for in the text of every value. A `names` pattern is compared with
the last part of the field name, whole and with case ignored. The plugin reads the file in Java and
the wrapper in Python, so use only the syntax both accept: character classes, quantifiers, groups
and lookarounds. If the file cannot be read or a pattern does not compile, the call is refused
instead of answering unfiltered.

## Fields of a filtered answer

| Field | Meaning |
|-------|---------|
| `piiFilter` | the mode and the number of values replaced: `{"mode": "pseudonym", "masked": 3}` |
| `screenshotWithheld` | `true` in place of `screenshot`: the file is deleted, since a picture of a form shows its data unmasked |
| `filesWithheld` | how many files of a YAXUnit run were deleted because the filter cannot read them, an image a test attached for example |

When the filter fails with an error, the wrapper returns the status `pii_filter_failed` instead:
the answer is withheld and the output directory of the run is emptied. `notRemoved` lists any file
that could not be deleted. The error message gives only the class of the failure, because the text
of the exception may contain the value the filter stopped on.

When the plugin cannot read its settings, the tool returns an error that ends in
"the answer of ... is withheld".
