---
title: "Scenario examples"
description: "Two test-form scenarios: an external report, and an installed extension with values checked after reopening."
sidebar:
  label: "Scenario examples"
  order: 2
---

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
