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
   disable the platform's unsafe action protection or confirm its security prompt.

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

## Select a target

Every run requires `--platform-bin`, `--test-infobase`, `--scope`, `--form`,
`--form-title`, and `--output-dir`. Choose exactly one target:

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
as `ВнешнийОтчет.PlannedShipments.Форма` are accepted. Pass the exact title shown
by the client in `--form-title`.

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
a new directory outside the infobase. `--timeout` is 1–900 seconds, with a default of
120; it covers setup and all scenario phases. Process cleanup has a separate short
deadline. See the [CLI reference](cli.md#edt-bridge-mcp-test-form) for all options.

## Scenario format

Pass a UTF-8 JSON file with `--scenario`. Its top-level fields are `version: 1`,
`steps`, and an optional `reopen` array. Each phase contains 1–128 steps and must end
with an assertion after its last UI action. Unknown fields and duplicate JSON keys
are rejected. The file limit is 1 MiB.

Each step contains the required fields listed below. Use `field` for the internal
name of a visible form element. A `click` takes either `button` with the exact visible
title or `button_name` with the internal name of the button element. Use `button_name`
when several buttons share a title. A selector must match exactly one element.
Wildcards are rejected. A `click` can also include `expect_error_dialog`.

| `action` | Other required fields | Operation |
| --- | --- | --- |
| `input` | `field`, `text` | Replaces the input field text and moves to the next form element. Empty text invokes the field's `Clear()` action. |
| `click` | `button` or `button_name` | Clicks an available button. Reads the message panel before and after the click when a following `assert_messages` checks it. |
| `assert_field` | `field`, `equals` | Reads `GetDataPresentation()` and compares the returned string. |
| `assert_cell` | `field`, `cell`, `equals` | Reads one spreadsheet cell, for example `"cell": "R3C6"`, and compares its text. |
| `assert_table` | `field`, `equals` | Reads the spreadsheet rectangle from `R1C1` to its last populated row and column; compares the complete array of row arrays. |
| `assert_table_size` | `field`, `rows`, `columns` | Compares the last populated row and column numbers of the spreadsheet. |
| `assert_messages` | `text`, `count` | Checks the exact number of occurrences of a message after the preceding click, with a zero count before it. |

`text` and scalar `equals` values are strings, including dates and numbers. They are
limited to 4096 characters. Comparisons preserve case, whitespace, and formatting.
Use the actual input format and displayed values of the test client. The API does not
return the underlying typed attribute value. If the returned presentation omits the
time, that comparison cannot prove that the time was preserved.

The three spreadsheet assertions apply to a spreadsheet document field, not to a
form table or dynamic list. `assert_table` accepts a rectangular array of strings,
up to 200 rows by 50 columns; larger observed documents fail the check. It includes
headers, totals, and empty cells inside the rectangle. `assert_table_size` measures
the populated bounds, not the number of business records. `rows` can be 0–999999 and
`columns` 0–99999. A `cell` address uses positive row and column numbers within those
limits. An empty spreadsheet can be checked with `"equals": []`.

`assert_messages` requires a preceding `click` with no intervening `input`. It uses
the snapshots from that click, even if other assertions follow it. `count` is an
integer from 0 to 10000; zero checks absence of that exact message. If that text was
already present before the click, the result is `message_baseline_not_empty`. The
command does not subtract old messages or clear the panel. Start a separate run when
repeated saves would leave the expected text in the panel. These checks cover the
message panel, not modal warnings or transient notifications. Read messages before
closing their form; a "Save and close" command can remove the panel.

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

## Existing single-button checks

The EPF profile remains available with `--epf`, `--button`, and `--expect-message`.
The expected message must gain an occurrence after the click. Without
`--expect-message`, the result is `command_observed`, `ok=false`, and exit code 2.
For exact message counts, absence checks, or data assertions, use `--scenario`.
`--button` and `--scenario` are mutually exclusive; `--expect-message` cannot be
combined with a scenario.

## Results

If the test manager cannot connect during startup, the command retries for up to
30 seconds within the run timeout while the same client owns the port.
`connectAttempts` records the number of attempts for the phase.

The command prints JSON and writes `result.json` when it has created the output
directory. A scenario result contains `phases`, observations under each phase's
`interaction`, and `assertions` with `expected`, `actual`, and `ok`. Message assertions
also include `baselineCount`. Screenshots are saved as `steps/screenshot.png` and,
when that phase runs, `reopen/screenshot.png`. A single-button run uses
`screenshot.png` in the output directory. The JSON records screenshot paths.

| Result | Meaning |
| --- | --- |
| `scenario_completed`, `ok=true` | All assertions in all requested phases passed and the required screenshots were captured. |
| `assertion_failed` | An observed value or message count differs from the expectation. |
| `message_baseline_not_empty` | The expected message was already present before the click; freshness is not established. |
| `scenario_error`, `command_error` | An action, element lookup, or platform operation failed. |
| `dependency_missing`, `license_unavailable`, `access_denied`, `setup_failed` | The environment or permissions do not allow the check to run. |
| `timeout` | The overall time limit expired. The recorded progress and available screenshot show where execution stopped. |
| `screenshot_failed` | Observations passed but the required screenshot was not obtained. |

Other diagnostic statuses, including form-selection and driver errors, are retained
in the JSON. Exit code 0 means success, 1 means failure during input validation, and 2
means a later failure or an unconfirmed result. Read `status`, `stage`, and the phase
details to distinguish an application failure from an environment failure. A click
without a successful assertion is not a passed scenario.
