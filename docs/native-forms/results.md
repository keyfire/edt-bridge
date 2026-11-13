---
title: "Results"
description: "What test-form returns: result statuses, screenshots, event log errors and setup diagnostics."
sidebar:
  label: "Results"
  order: 3
---

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
| `client_environment` | No `conf.cfg` the client reads sets `DisableUnsafeActionProtection`, so the client would stop at the security warning; see `clientEnvironment` and [Prepare the environment](/native-forms#prepare-the-environment). |
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

When the manager infobase cannot be prepared, `setup_failed` names the stage and quotes what
the platform wrote: the designer's log (`designerLog`), the output of the designer process
(`designerProcessLog`) and the log of `ibcmd` (`setupLog`). The manager infobase is disposable,
so a failed setup is tried once more in a clean directory before the check gives up;
`setupRetries` records the stage and the error of the failed attempt. A refused license or
access is not retried.

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
