---
title: "Native form checks"
description: "Check EPF and ERF forms and saved configuration objects through the platform's TestClient, with JSON assertions and screenshots."
sidebar:
  label: Native forms
  order: 5.5
---

`edt-bridge-mcp test-form` opens a managed form in a native 1C:Enterprise test client,
performs UI actions, and records the observed result. A JSON scenario can check field
presentations, spreadsheet cells, and user messages. An optional second session checks
the state after reopening the same target.

The command runs independently of EDT. It creates a temporary manager infobase and
starts separate TestManager and TestClient processes. It uses the explicitly selected
file infobase for the target; it does not create a copy of that infobase. UI commands
can change its data.

## Prepare the environment

1. Use an isolated Linux environment with a full platform installation containing `1cv8`
   and `ibcmd`, a platform license, `Xvfb`, and `libX11`.
2. Prepare a disposable file infobase containing `1Cv8.1CD`, the required configuration,
   and the test data. For an extension check, install the `.cfe` beforehand. `test-form`
   does not install extensions or update the target configuration.
3. Prepare a test user that can start the client without an interactive login dialog.
   `--user` selects a user name; the CLI does not accept a password or answer login dialogs.
4. Allow the intended external files to run in this test environment. The CLI does not
   disable the platform's unsafe action protection or confirm its security prompt, and it
   checks the protection setting before it starts the client (see below).

For an unattended disposable runner, the environment owner can configure protection
before running the check. In the Linux platform installation used by `--platform-bin`,
the file is `<platform-bin>/conf/conf.cfg`. For example, restrict the setting to
infobases below `/srv/test-bases/`:

```ini
DisableUnsafeActionProtection=.*/srv/test-bases/.*
```

This is a POSIX Basic Regular Expression matched against an infobase connection string,
not an external file path. It disables protection for all users of matching infobases.
Keep this setting in the disposable test environment and retain the surrounding `.*`
when adapting the example to its base directory. A `conf.cfg` on the Windows host does
not configure the Linux platform inside a container. If a security prompt remains,
the check leaves it visible and captures it when the client screen is available.

Before the client starts, `test-form` reads `<platform-bin>/conf/conf.cfg`, the directory its
`ConfLocation` names and the installation root's `conf/conf.cfg` (`/opt/1cv8/conf/conf.cfg` for
`/opt/1cv8/x86_64/<version>`); a setting is taken from the first file that has it. The user's
own `~/.1cv8/1C/1cv8/conf/conf.cfg` does not count, because the check gives the client a home
directory of its own.

When an `.epf` or `.erf` is checked without `--user` and none of these files sets
`DisableUnsafeActionProtection`, the command stops with `client_environment` before any
platform process starts. The message names the setting, the file and a pattern for this
infobase. When patterns are set but none of them matches, the check runs and records a
`warning`. The patterns are matched by the platform's rules as the check understands
them: a POSIX basic expression against the whole connection string `File="<test infobase>";`.
A bare path without the surrounding `.*` does not match: the platform kept its security
warning for one (8.5.1.1529). With `--user`, the user's own *Unsafe action protection*
flag can disable the protection, so the check runs and records a note. `edt_test_form`
works on a copy of the test infobase in a temporary directory named
`edt-bridge-form-run-*` under `TMPDIR`; the pattern `.*/edt-bridge-form-run-.*` covers
every copy.

The result reports what the files say in `clientEnvironment`: the files read, the
connection string, the patterns and the one that matched, and the `SystemLanguage`
value. `SystemLanguage` sets the platform interface language. The captions of the
platform's standard forms, such as the buttons of the standard report form, are in that
language, so set it, for example `SystemLanguage=RU`, when a scenario presses such a
button by its caption.

## Select a target

Every run requires `--platform-bin`, `--test-infobase`, `--scope`, `--form` and
`--output-dir`; `--form-title` is optional. Choose exactly one target:

| Option | Target |
| --- | --- |
| `--epf FILE` | An existing external data processor and its main managed form, such as `ExternalDataProcessor.Check.Form.Main`. |
| `--erf FILE` | An existing external report with an assigned main form or a main data composition schema. |
| `--navigation-link LINK` | An existing document or catalog item in the selected infobase. Requires `--scenario`. |

For an EPF or ERF with an assigned main form, use its full name, such as
`ExternalReport.PlannedShipments.Form.Main`. If an ERF has a main data composition
schema and no assigned form, select the platform-generated form with
`ExternalReport.PlannedShipments.Form`.

For a configuration object, use its actual form FQN, such as
`Document.CustomerOrder.Form.Main` or `Catalog.Items.Form.Main`. Russian names such
as `ВнешнийОтчет.PlannedShipments.Форма` are accepted.

Without `--form-title` the check waits for the form by its full name and takes it when
one form of that name is open. `--form-title` is the window title shown by the client.
The window of an object form adds the object presentation to the form title, so a form
titled "Shipment check" opens in a window "Shipment check: ShipmentCheck" and the exact
title of the form does not match; pass a pattern such as `'Shipment check*'`. The platform reads `*` as
any run of characters and `?` as one character, so a title that carries a date or
a number can be given as a pattern, for example `'Customer order 7 dated *'`. The
date in a document title follows the regional settings of the session, and an exact
title taken in another locale does not match. The form is still selected by its
full name: windows that match the pattern but have another form name are ignored,
and two matching windows with the same form name give `ambiguous_form`. A title
that consists of wildcards only is refused. `--button` stays an exact caption.

On `form_not_found` the JSON lists the open forms in `observedForms` and their
titles in `observedTitles`. A found form is reported in `observedForm` and
`observedTitle`.

An ERF is opened by a fixed temporary EPF in the same test client using
`ExternalReports.Connect` and `OpenForm`, with `SafeMode=True`. The test user must be allowed to run
that launcher. This programmatic route checks report execution and data access;
permission to use the interactive File/Open command requires a separate check.

A configuration link must have this form:

```text
e1cib/data/Document.CustomerOrder?ref=0123456789abcdef0123456789abcdef
```

Use the real object's link from the disposable infobase. The reference is 32 hexadecimal
characters; the object type and metadata name must match `--form`. Only internal
`e1cib/data/` links to documents and catalog items are accepted. External links, links
to commands, and additional query parameters are rejected.

`--scope` records the caller's declared coverage: `empty`, `fixture`, or `application`.
It does not inspect the database to verify that classification. `--output-dir` must be
a new directory outside the infobase; missing parent directories are created. `--timeout`
is 1–900 seconds, with a default of 120; it covers setup and all scenario phases. Process
cleanup has a separate short deadline. See the [CLI reference](cli.md#edt-bridge-mcp-test-form) for all options.

## Scenario format

Pass a UTF-8 JSON file with `--scenario`. Its top-level fields are `version: 1`,
`steps`, and an optional `reopen` array. Each phase contains 1–128 steps and must end
with an assertion or a read after its last UI action. Unknown fields and duplicate JSON keys
are rejected; the refusal names the step, its unexpected and missing keys and the keys its
action takes, for example `Invalid 'input' step 1 in steps: unexpected value; missing text;
input takes action, field, text.` The `edt_test_form` schema describes every kind of step. The file limit is 1 MiB.

Each step contains the required fields listed below. Use `field` for the internal
name of a visible form element. A `click` takes either `button` with the exact visible
title or `button_name` with the internal name of the button element. Use `button_name`
when several buttons share a title. A selector must match exactly one element.
Wildcards are rejected. A `click` can also include `expect_error_dialog` or `expect_warning`.

| `action` | Other required fields | Operation |
| --- | --- | --- |
| `input` | `field`, `text` | Replaces the input field text and moves to the next form element. Empty text invokes the field's `Clear()` action. |
| `click` | `button` or `button_name` | Clicks an available button. Reads the message panel before and after the click when a following `assert_messages` checks it. |
| `assert_field` | `field`, `equals` | Reads `GetDataPresentation()` and compares the returned string. |
| `assert_cell` | `field`, `cell`, `equals` | Reads one spreadsheet cell, for example `"cell": "R3C6"`, and compares its text. |
| `assert_table` | `field`, `equals` | Reads the spreadsheet rectangle from `R1C1` to its last populated row and column, or every row of a form table; compares the complete array of row arrays. For a form table the result also names the columns. |
| `assert_table_size` | `field`, `rows`, `columns` | Compares the last populated row and column numbers of the spreadsheet. |
| `assert_messages` | `text`, `count` | Checks the exact number of occurrences of a message after the preceding click, with a zero count before it. |
| `read_form` | – | Returns the values of the whole form: every visible field with `name`, `title` and `presentation`, every visible form table with its `columns` and `rows`, and every spreadsheet field as rows. Compares nothing. |
| `read_table` | `field` | Returns one form table, with its columns, or one spreadsheet field as rows. Compares nothing. |

`text` and scalar `equals` values are strings, including dates and numbers. They are
limited to 4096 characters. Comparisons preserve case, whitespace, and formatting.
Use the actual input format and displayed values of the test client. The API does not
return the underlying typed attribute value. If the returned presentation omits the
time, that comparison cannot prove that the time was preserved.

`assert_cell` and `assert_table_size` apply to a spreadsheet document field only.
`assert_table` and `read_table` take a spreadsheet document field or a form table,
dynamic lists included, by its item name. A form table is read row by row from the
first row through the test client, with the cell text of every visible column.
`assert_table` accepts a rectangular array of strings,
up to 200 rows by 50 columns; larger observed documents fail the check. It includes
headers, totals, and empty cells inside the rectangle. `assert_table_size` measures
the populated bounds, not the number of business records. `rows` can be 0–999999 and
`columns` 0–99999. A `cell` address uses positive row and column numbers within those
limits. An empty spreadsheet can be checked with `"equals": []`.

`assert_messages` requires a preceding `click` with no intervening `input`. It uses
the snapshots of the panel taken before and after that click, even if other assertions
follow it. `count` is an integer from 0 to 10000: the number of messages with that
exact text the click added; zero checks that it added none. Messages already on the
panel are not counted, so a second click of the same command is checked with
`count` 1 again. A command adds messages after the earlier ones, and clearing the
panel removes them all: when the earlier messages no longer lead the panel, it was
cleared and everything on it counts as new. `panel` in the assertion says which it
was: `appended`, `cleared` or `unchanged`. A panel that did not change while the text
was already on it is `message_baseline_not_empty`: the command either wrote nothing
or cleared the panel and wrote the same messages again, and the two cannot be told
apart. Give the repeated message a distinct text, or check it in a separate run, when
the command clears the panel before it writes. These checks cover the
message panel, not modal warnings or transient notifications. Read messages before
closing their form; a "Save and close" command can remove the panel.

`read_form` and `read_table` put what they read into `reads` of the result, with the
step number, and leave the judgment to the caller. The test client sees form items
only: an attribute that has no item on the form is not read, and a hidden item is left
out. To check such a value, show it on the form, for example as a read-only field. A
field whose presentation cannot be read carries `error` instead of `presentation`.
A read cuts what exceeds the limits, 500 items of a form and 200 rows by 50 columns of a
table, and sets `truncated` on the form, the table or the read; `assert_table` refuses a
table over the limits instead, because it compares complete tables only. A form table is
read from its first row down. The read takes a failed move to the next row for the end of
the table only when the cursor stands on the last row; otherwise the step fails, so a
broken read does not look like a short table.

The manager records observations; Python compares expectations after the whole phase
finishes. A failed intermediate assertion does not stop later UI actions in the same
phase. An error while performing an action stops the phase. A phase that fails its
assertions prevents `reopen` from starting. Scenarios contain no arbitrary BSL or
custom success predicate.

## Check an external report

The following example assumes a prepared report with internal field names `StartDate`,
`EndDate`, and `Result`, and the Russian captions shown below. Replace the names, titles,
and expected rows with those of your report and fixture. Save this as
`/srv/scenarios/shipments.json`:

```json
{
  "version": 1,
  "steps": [
    {"action": "input", "field": "StartDate", "text": "01.11.2026"},
    {"action": "input", "field": "EndDate", "text": "30.11.2026"},
    {"action": "click", "button": "Сформировать"},
    {"action": "assert_table_size", "field": "Result", "rows": 2, "columns": 7},
    {"action": "assert_cell", "field": "Result", "cell": "R2C6", "equals": "100,00"},
    {
      "action": "assert_table",
      "field": "Result",
      "equals": [
        ["Дата отгрузки", "Номер", "Дата заказа", "Организация", "Контрагент", "Сумма", "Валюта"],
        ["05.11.2026", "000001", "04.11.2026", "Тестовая организация", "Тестовый покупатель", "100,00", "RUB"]
      ]
    }
  ]
}
```

```bash
edt-bridge-mcp test-form \
  --platform-bin /opt/1cv8/x86_64/8.5.1.1529 \
  --test-infobase /srv/test-bases/orders --scope fixture \
  --erf /srv/artifacts/PlannedShipments.erf \
  --form ExternalReport.PlannedShipments.Form.Main \
  --form-title 'Плановые отгрузки' --user Tester \
  --scenario /srv/scenarios/shipments.json \
  --output-dir /srv/results/shipments-01 --timeout 300
```

`assert_table` compares actual cell text and row order. Neither clicking "Сформировать"
nor observing a completion message substitutes for checking the report contents.

## Check an installed extension and saved values

Prepare the document, install the extension, and record the fixture's original values.
The example below expects a warning when the planned shipment precedes the order date,
then checks the saved dates in another session. Save it as `/srv/scenarios/order.json`:

```json
{
  "version": 1,
  "steps": [
    {"action": "assert_field", "field": "OrderDate", "equals": "06.11.2026 12:00:00"},
    {"action": "input", "field": "PlannedShipmentDate", "text": "05.11.2026"},
    {"action": "click", "button": "Записать"},
    {"action": "assert_messages", "text": "Плановая дата отгрузки раньше даты заказа.", "count": 1},
    {"action": "assert_field", "field": "PlannedShipmentDate", "equals": "05.11.2026"}
  ],
  "reopen": [
    {"action": "assert_field", "field": "OrderDate", "equals": "06.11.2026 12:00:00"},
    {"action": "assert_field", "field": "PlannedShipmentDate", "equals": "05.11.2026"}
  ]
}
```

```bash
edt-bridge-mcp test-form \
  --platform-bin /opt/1cv8/x86_64/8.5.1.1529 \
  --test-infobase /srv/test-bases/orders --scope application \
  --navigation-link 'e1cib/data/Document.CustomerOrder?ref=0123456789abcdef0123456789abcdef' \
  --form Document.CustomerOrder.Form.Main \
  --form-title 'Заказ покупателя 000001' --user Tester \
  --scenario /srv/scenarios/order.json \
  --output-dir /srv/results/order-01 --timeout 300
```

`reopen` starts a new TestClient and TestManager session for the same infobase, user,
and target after the first phase ends. It permits only `assert_field`, `assert_cell`,
`assert_table`, and `assert_table_size`. It does not reuse the edited form or save it
on behalf of the scenario. Opening a form still runs its application handlers.

To check a native refusal to save, use a fixture that triggers the configuration's
own validation. Attempt a change, assert the native refusal message, and expect the
original persisted values in `reopen`. Include a value that was actually changed in
the first phase so that a missing save cannot look like a successful update. A field
assertion in the first phase observes the open form and does not prove persistence.
For cases that must not add the extension warning, use a separate run with
`assert_messages` and `count: 0`.

If refusal opens the platform's standard error dialog, add `expect_error_dialog`
to the `click` step. Its value is the exact displayed message, for example:

```json
{"action": "click", "button": "Save", "expect_error_dialog": "Could not save the order."}
```

The manager checks the `ErrorInfo` text of `ErrorWindow` before pressing its `OK`
button. A different message fails the check and leaves the dialog open for the
screenshot. The observed text and closing result are recorded. This option does not
handle other dialogs or replace assertions on messages and persisted values.

If the command answers with a warning window (`ShowMessageBox`) instead of a message, add
`expect_warning` with the exact text of the window:

```json
{"action": "click", "button_name": "FormWrite", "expect_warning": "Check the shipment date."}
```

The manager finds the window over the form, the `MessageBox` system form, which is numbered
(`MessageBox85`) when an object form raises it. It compares the `Message` text, presses `OK`
and goes on with the next step, so a warning shown in `BeforeWrite` does not stop the check
of the written values. A different text gives `assertion_failed` with the observed text, and
a click that shows no warning fails the step. A click expects one window: `expect_error_dialog`
and `expect_warning` exclude each other.

## Existing single-button checks

The EPF profile remains available with `--epf`, `--button`, and `--expect-message`.
The expected message must gain an occurrence after the click. Without
`--expect-message`, the result is `command_observed`, `ok=false`, and exit code 2.
For exact message counts, absence checks, or data assertions, use `--scenario`.
`--button` and `--scenario` are mutually exclusive; `--expect-message` cannot be
combined with a scenario.

## Results

If the test manager cannot connect during startup, `test-form` retries within the
remaining `--timeout` while the same client owns the port. Retries stop when the
manager connects or the platform reports an access or license error.
`connectAttempts` records the number of attempts for the phase.

The command prints JSON and writes `result.json` when it has created the output
directory. A scenario result contains `phases`, observations under each phase's
`interaction`, and `assertions` with `expected`, `actual`, and `ok`. Message assertions
also include `baselineCount`, the copies of the text on the panel before the click,
and `panel`. Screenshots are saved as `steps/screenshot.png` and,
when that phase runs, `reopen/screenshot.png`. A single-button run uses
`screenshot.png` in the output directory. The JSON records screenshot paths.

| Result | Meaning |
| --- | --- |
| `scenario_completed`, `ok=true` | All assertions in all requested phases passed and the required screenshots were captured. |
| `assertion_failed` | An observed value or message count differs from the expectation. |
| `message_baseline_not_empty` | The message panel did not change and already held the expected text, so whether the click wrote it again is not established. |
| `scenario_error`, `command_error` | An action, element lookup, or platform operation failed. |
| `unexpected_dialog` | A click raised a warning or a question window over the form that the step did not expect. `dialog` holds its `kind` (`warning`, `question`), `text` and visible `buttons`; the scenario stops there and a question is left unanswered, so the caller decides whether the window is an acceptable answer. |
| `dependency_missing`, `license_unavailable`, `access_denied`, `setup_failed` | The environment or permissions do not allow the check to run. |
| `client_environment` | No `conf.cfg` the client reads sets `DisableUnsafeActionProtection`, so the client would stop at the security warning; see `clientEnvironment` and [Prepare the environment](#prepare-the-environment). |
| `timeout` | The overall time limit expired. The recorded progress and available screenshot show where execution stopped. |
| `screenshot_failed` | Observations passed but the required screenshot was not obtained. |

`eventLog.errors` lists the errors the platform wrote to the event log of the test
infobase while the check ran. A server call that fails shows the client only an error
window, while the log keeps the description: each entry has `message`, `module` and
`line` of the place the error was raised, `stack` with the calling lines, `event`
(`_$PerformError$_` for a runtime error) and `time`. An error the code catches in
`Try` is not logged and is not listed. The log is read in the platform's text format
(`1Cv8Log/*.lgf`, `*.lgp`); for an infobase that keeps it in SQLite (`1Cv8.lgd`),
`eventLog.read` is `false` with an explanation.

When a step's `field` matches no field or several, the result carries `observedFields`:
the fields and form tables the test client sees on the form, each with `name`, `title`,
`kind`, `visible` and `enabled`, the visible ones first, up to 200, and `total`. Fields
that the form creates as it opens, such as the quick settings of a standard report form,
are named nowhere else.

Other diagnostic statuses, including form-selection and driver errors, are retained
in the JSON. Exit code 0 means success, 1 means failure during input validation, and 2
means a later failure or an unconfirmed result. Read `status`, `stage`, and the phase
details to distinguish an application failure from an environment failure. A click
without a successful assertion is not a passed scenario.
