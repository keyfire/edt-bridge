---
title: "Scenario format"
description: "The JSON scenario of test-form: steps, assertions, reads and their limits."
sidebar:
  label: "Scenario format"
  order: 1
---

Pass a UTF-8 JSON file with `--scenario`. Its top-level fields are `version: 1`,
`steps`, and an optional `reopen` array. Each phase contains 1–128 steps and must end
with an assertion or a read after its last UI action. Unknown fields and duplicate JSON keys
are rejected; the refusal names the step, its unexpected and missing keys and the keys its
action takes, for example `Invalid 'input' step 1 in steps: unexpected value; missing text;
input takes action, field, text.` The `edt_test_form` schema describes every kind of step. The file limit is 1 MiB.

## Steps

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

## Message checks

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

## Reading values

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

## Order of evaluation

The manager records observations; Python compares expectations after the whole phase
finishes. A failed intermediate assertion does not stop later UI actions in the same
phase. An error while performing an action stops the phase. A phase that fails its
assertions prevents `reopen` from starting. Scenarios contain no arbitrary BSL or
custom success predicate.
