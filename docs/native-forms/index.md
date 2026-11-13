---
title: "Native form checks"
description: "Check EPF and ERF forms and saved configuration objects through the platform's TestClient, with JSON assertions and screenshots."
sidebar:
  label: "Preparation and run"
  order: 0
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
cleanup has a separate short deadline. See the [CLI reference](/cli#edt-bridge-mcp-test-form) for all options.

## Existing single-button checks

The EPF profile remains available with `--epf`, `--button`, and `--expect-message`.
The expected message must gain an occurrence after the click. Without
`--expect-message`, the result is `command_observed`, `ok=false`, and exit code 2.
For exact message counts, absence checks, or data assertions, use `--scenario`.
`--button` and `--scenario` are mutually exclusive; `--expect-message` cannot be
combined with a scenario.

## Scenario, examples and results

The rest of the guide is on separate pages:

- <a id="scenario-format"></a>[Scenario format](/native-forms/scenario) – the steps of a JSON scenario and their limits.
- <a id="check-an-external-report"></a><a id="check-an-installed-extension-and-saved-values"></a>[Scenario examples](/native-forms/examples) – an external report, an installed extension and saved values.
- <a id="results"></a>[Results](/native-forms/results) – the result statuses, the event log and the diagnostics of a failed run.
