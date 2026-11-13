---
title: "YAXUnit tests"
description: "Run the YAXUnit tests of built extensions in a copy of a test infobase and read every test's status, message and failing lines."
sidebar:
  label: "YAXUnit tests"
  order: 4
---

`edt-bridge-mcp unit-tests` and the wrapper tool `edt_unit_tests` run the
[YAXUnit](https://github.com/bia-technologies/yaxunit) tests of built configuration extensions. A
unit test checks the logic under a form without the interface, faster and more precisely than a
form scenario.

The run copies the `1Cv8.1CD` of the selected test infobase, loads the given `.cfe` files into the
copy with `ibcmd` and starts the 1C:Enterprise client on a private Xvfb display with
`/C RunUnitTests=<file>`. YAXUnit runs the tests of these extensions, writes a jUnit report and
closes the client. The test infobase itself is never changed.

## Prepare the test infobase

The run needs the same environment as the [form check](/native-forms): isolated Linux, a platform
installation with `1cv8` and `ibcmd`, a platform license, `Xvfb` and `libX11`. The test infobase
also needs YAXUnit, loaded once as the extension `YAXUNIT`. Take `YAxUnit-<version>.cfe` from the
[YAXUnit releases](https://github.com/bia-technologies/yaxunit/releases) and load it:

```bash
ibcmd config load --db-path=/srv/test-bases/orders --extension=YAXUNIT YAxUnit-25.12.cfe
ibcmd config apply --db-path=/srv/test-bases/orders --extension=YAXUNIT --force
```

The run turns off the safe mode and the unsafe action protection of `YAXUNIT` in its copy, so the
infobase can keep the defaults. `edt_unit_tests` takes the infobase, the platform and the user from
`EDT_BRIDGE_TEST_INFOBASE`, `EDT_BRIDGE_TEST_PLATFORM` and `EDT_BRIDGE_TEST_USER`, the same
[variables](/settings) as `edt_test_form`.

## Extensions and the filter

- `--extension NAME=PATH` (in the tool: `extensions`, a list of `{path, name}`) is loaded into the
  copy in the order given: the extension with the test modules and, when the code under test is in
  a separate extension, that one too. `NAME` is the name the extension has in its project: `ibcmd`
  gives a loaded extension the name stored in the file, and a different `NAME` ends the run with
  `extension_name_mismatch`.
- The loaded extensions run with the safe mode and the unsafe action protection turned off.
- `--module` and `--test` (in the tool: `modules` and `tests`) narrow the run. A test is named
  `Module.Test` or `Module.Test.Context`, for example `ОМ_Заказы.СуммаЗаказа.Сервер`. Without them
  every test of the loaded extensions runs.

A test module is a common module of the extension with an exported `ИсполняемыеСценарии` procedure
that registers the tests:

```bsl
Процедура ИсполняемыеСценарии() Экспорт
	ЮТТесты.ДобавитьТест("СуммаЗаказа");
КонецПроцедуры

Процедура СуммаЗаказа() Экспорт
	ЮТест.ОжидаетЧто(Заказы.СуммаЗаказа(2, 3)).Равно(5);
КонецПроцедуры
```

A tool call that runs this one test:

```json
{"extensions": [{"path": "/work/out/tests.cfe", "name": "tests"}],
 "tests": ["ОМ_Заказы.СуммаЗаказа"]}
```

## Result

The command writes `result.json`, the YAXUnit reports `junit.xml` and `allure-results/` and its
log `yaxunit.log` to `--output-dir`; the tool answers with the object of `result.json`. Both reports
come from YAXUnit itself: `junit.xml` goes to GitLab as `artifacts:reports:junit` and
`allure-results/` to `allure generate`, the same way as the
[reports of a form check](/native-forms/results#reports-for-ci).

| Field | Content |
|-------|---------|
| `status` | the outcome, see below |
| `summary` | the numbers of tests, passed, failed, errors and skipped |
| `tests` | each test: `module`, `name`, `context`, `extension`, `status` (`passed`, `failed`, `error`, `skipped`), `time` in seconds, `message`, `expected`, `actual` and `trace` |
| `yaxunitErrors` | the error records of the YAXUnit log |
| `eventLog.errors` | the errors the platform logged in the copy during the run, with module and line |
| `yaxunitVersion` | the YAXUnit version that ran the tests |

`trace` keeps the lines of the code under test and leaves out the frames of YAXUnit itself.
`failed` is an assertion that did not hold, `error` an exception in the test or in the tested code.
With more than 500 tests the list keeps the failed ones first, and `testsListed` gives its length.

| `status` | Meaning |
|----------|---------|
| `passed` | at least one test ran and none failed |
| `failed` | a test failed, or YAXUnit reported a failure outside the tests |
| `no_tests` | the filter selected no tests |
| `yaxunit_failed` | YAXUnit stopped before its report; `yaxunitErrors` says why, for example a test module that does not compile |
| `yaxunit_missing` | the test infobase has no `YAXUNIT` extension |
| `extension_load_failed` | `ibcmd` could not load or apply an extension; `error` quotes it |
| `extension_name_mismatch` | the file holds an extension with another name |
| `timeout` | the run reached its time limit; `screenshot` shows what the client was waiting for |
| `license_unavailable`, `access_denied` | the platform refused to start the client |

The exit code of the command is **0** for `passed`, **1** for a refused call and **2** otherwise.
