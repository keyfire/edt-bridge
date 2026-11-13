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

With `EDT_BRIDGE_PII` set, the answer, `result.json` and the reports for CI are filtered of
personal data, and `piiFilter` gives the mode and the number of values replaced. Screenshots are
not kept, since a picture of a form shows its data unmasked: `screenshotWithheld` stands in place
of the path. The verdict is still taken on the real values; see [Personal data](/personal-data).

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
| `pii_filter_failed` | The [personal data](/personal-data) filter failed with an error. The answer is withheld and the output directory is emptied; `notRemoved` lists a file that could not be deleted. |

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
`setupRetries` records the stage, the error and the `seconds` of the failed attempt. A refused
license or access is not retried.

`timings` says where the time of the run went. `totalSeconds` is the whole run and `stages` its
parts in seconds, which add up to it: `preparation` (the input and the client environment),
`managerDisplay`, `managerInfobase` (creating the manager infobase and loading the test driver into
it, the same for every run of a platform build), `targetMetadata` and `reportLauncher` (what the
target needs), `phases`, `cleanup` (stopping the processes and removing the temporary directory)
and `eventLog`. `edt_test_form` adds `infobaseCopy`, the copy of the test infobase. Each entry of
`timings.phases` has the phase `name`, its `seconds` and their parts: `clientStart`, `manager` (from
the start of the test manager to its result, earlier connection attempts included), `screenshot`
and `cleanup`. `driver` holds what the test manager measured itself: `connect`, `formLookup`, the
`command` of a button check or `steps` with the `seconds` of each scenario step, and `total`;
`managerStartup` is the rest of `manager`, the start of the manager's platform. A step that was
still running when the check stopped has no time of its own. With a `managerCache` hit,
`managerInfobase` is only the copy of the cached manager infobase.

When a step's `field` matches no field or several, the result carries `observedFields`:
the fields and form tables the test client sees on the form, each with `name`, `title`,
`kind`, `visible` and `enabled`, the visible ones first, up to 200, and `total`. Fields
that the form creates as it opens, such as the quick settings of a standard report form,
are named nowhere else.

The metadata of the selected EPF or ERF (its main form, name and main data composition
schema) is read by `ibcmd config export --file`, which takes about a second where the designer
takes about ten. When `ibcmd` cannot read the file, the designer reads it as before;
`targetMetadata.source` says which one did, with `ibcmdError` when it was the designer.

## Reports for CI

Next to `result.json` the command writes `junit.xml`, a JUnit XML report that GitLab shows in a
merge request (`artifacts:reports:junit`), and `allure-results/`, the results Allure builds its
report from. Each phase is a test: `steps` and `reopen` of a scenario, or `click <button>` of a
button check; a check that stopped before its first phase is one test named `setup`. A phase whose
solution did not behave (`assertion_failed`, `scenario_error`, `unexpected_dialog` and the like) is
a JUnit `failure` and an Allure `failed` test; a phase the check could not finish (`setup_failed`,
`timeout`, `license_unavailable` and the like) is an `error` and `broken`; a phase that was not
reached is `skipped`. The time of a test is the time of its phase from `timings`, and the first test
also carries the setup. In Allure the steps of a test are the setup stages, the client start, the
start of the test manager, the connection, the form lookup, every scenario step with its expected
and actual values, and the screenshot, which is attached. `reports` in the result names both paths.

```yaml
form-check:
  script:
    - edt-bridge-mcp test-form --platform-bin /opt/1cv8/x86_64/8.5.1.1529 --test-infobase /srv/test-bases/orders
      --scope fixture --epf build/Orders.epf --form ExternalDataProcessor.Orders.Form.Form
      --scenario checks/orders.json --output-dir out/orders
  artifacts:
    when: always
    paths: [out/]
    reports:
      junit: out/*/junit.xml
```

`allure generate out/*/allure-results` builds one Allure report of every check.

Other diagnostic statuses, including form-selection and driver errors, are retained
in the JSON. Exit code 0 means success, 1 means failure during input validation, and 2
means a later failure or an unconfirmed result. Read `status`, `stage`, and the phase
details to distinguish an application failure from an environment failure. A click
without a successful assertion is not a passed scenario.
