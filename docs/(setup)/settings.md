---
title: "Settings"
description: "The EDT-Bridge preference page in EDT, the EDT_BRIDGE_* environment variables of the wrapper and the plugin, and the write scope."
sidebar:
  label: "Settings"
  order: 2
---

The plugin reads its settings from the EDT-Bridge preference page in EDT, from `EDT_BRIDGE_*`
environment variables and from `-Dedt.bridge.*` launch properties. The wrapper reads
environment variables and command-line flags.

## Settings inside EDT

The plugin has a preference page of its own, **Window ▸ Preferences ▸ EDT-Bridge**. That is where the
token comes from when the bridge runs inside a GUI EDT:

| Setting | What it is |
|---------|------------|
| **Token for write tools** | The shared secret every write tool requires. An empty token means writes are refused, not that they are open to anyone. The client gets the same value as `EDT_BRIDGE_TOKEN`. |
| **MCP server port** | 8770 by default. **It takes effect after EDT restarts**, so the running server keeps the old port until then. |
| **Allow arbitrary BSL evaluation while debugging** | Off by default. It guards `edt_evaluate`, which executes code against a live infobase. Test stands only. |

**Launch parameters take precedence over this page.** `-Dedt.bridge.*` system properties and `EDT_BRIDGE_*`
environment variables given at startup take precedence over the stored values. That is how the
wrapper drives a headless EDT, and it is also why a token set here can look ignored when one was
passed on the command line as well.

## Environment variables

The wrapper and the plugin take their settings under the same `EDT_BRIDGE_*` names, but different
sides read them: the wrapper at its own start, the plugin inside EDT. Most wrapper settings have a
flag twin, documented in [Commands](/cli).

**Read by the `edt-bridge-mcp` wrapper**

| Variable | Flag | Default | What it sets |
|----------|------|---------|--------------|
| `EDT_BRIDGE_PORT` | `--port` | `8770` | the port the wrapper looks for the bridge on |
| `EDT_BRIDGE_PORT_SCAN` | – | `20` | how many consecutive ports from that one to scan. A busy port makes the plugin take the next free one, and the bridge is then looked for across that same window |
| `EDT_BRIDGE_TOKEN` | – | empty | the write-tools token. It is sent as an `Authorization: Bearer` header and passed into the headless instance's JVM |
| `EDT_BRIDGE_WORKSPACE` | `--workspace` | – | the EDT workspace to serve when starting a headless EDT |
| `EDT_BRIDGE_EDT_DIR` | `--edt-dir` | the newest install | the EDT install directory (the folder with 1cedtcli and dropins). Without it the newest install is the one with the newest `eclipse.buildId` in `configuration/config.ini`, or by the version in the directory name when the file has none. When the ini of that EDT names no `-vm` and the java on PATH is older than EDT requires, the JDK from `JAVA_HOME` or from the JDK components of the 1C installer goes first on PATH of the EDT process |
| `EDT_BRIDGE_START_TIMEOUT` | `--start-timeout` | `360` | how many seconds to wait for a starting bridge |
| `EDT_BRIDGE_AUTOSTART` | `--no-autostart` | on | `0` never launches anything and only forwards requests |
| `EDT_BRIDGE_HEAP` | `--heap` | from the EDT installation | the maximum JVM heap of an auto-started headless EDT: a number with `k`, `m` or `g`, e.g. `6g`. It is passed as `-Xmx` and replaces the value from the installation's ini file |
| `EDT_BRIDGE_VMARGS` | `--vmargs` | – | further JVM options of an auto-started headless EDT, separated by spaces, e.g. `-XX:+ExitOnOutOfMemoryError -Xlog:gc:file=gc.log`. Each option starts with a dash, and the bridge port is set with `EDT_BRIDGE_PORT` |
| `EDT_BRIDGE_WINDOW_WAIT` | – | `90` | how many seconds the `gui` command waits for the EDT window. A large workspace loads for minutes, so a miss here is not an error but a reason to run the command again |
| `EDT_BRIDGE_LANG` | – | the system locale | the language of the wrapper's help and messages (`ru` or `en`) |
| `EDT_BRIDGE_NO_PLUGINS` | – | off | `1` skips the discovery of [wrapper plugins](/plugins), leaving the wrapper's own capabilities only |
| `EDT_BRIDGE_TOOLSET` | – | every tool | one tool set or several, separated by commas: `processor`, `report`, `extension`. The wrapper then lists and runs only their tools, its own and plugin tools included. An unknown name refuses the list and every call |
| `EDT_BRIDGE_WRITE_PROJECTS`, `EDT_BRIDGE_READ_PROJECTS`, `EDT_BRIDGE_OUTPUT_DIR` | – | – | the [write scope](#write-scope) the wrapper expects. An auto-started EDT inherits it, and a call to an EDT that reports another scope is refused until the two agree |
| `EDT_BRIDGE_PLUGIN_INDEX` | – | – | the package index URL `self-update` names to pip for plugins installed by project name. A plugin installed from git records its source |

**Read by the plugin inside EDT**

| Variable | Launch property | Default | What it sets |
|----------|-----------------|---------|--------------|
| `EDT_BRIDGE_PORT` | `-Dedt.bridge.port` | `8770` | the MCP server's port. A busy one makes it take the next free port |
| `EDT_BRIDGE_TOKEN` | `-Dedt.bridge.token` | from the preference page | the shared secret every write tool requires |
| `EDT_BRIDGE_ALLOW_EVALUATE` | – | off | `1` enables `edt_evaluate`, arbitrary BSL executed against a live infobase. The preference page has the same switch |
| `EDT_BRIDGE_AGENT_IDLE_MINUTES` | `-Dedt.bridge.agent-idle-minutes` | `30` | after how many idle minutes a configurator agent stops itself. `off` keeps agents forever |
| `EDT_BRIDGE_DUMP_INFOBASE` | – | – | a file infobase (its directory, its `1Cv8.1CD` or `File="...";`) that carries the base configuration of external objects. The disk route of `edt_dump_external_object` builds in a copy of it, so types of the base configuration resolve; without it the build runs in an empty infobase. `edt_build_extension` also imports the extension into a copy of it: a borrowed form whose excluded commands (Post, Write) belong to its main attribute, and the base configuration's types, are checked against that configuration |
| `EDT_BRIDGE_TEST_INFOBASE` | – | – | a file infobase with test data for `edt_test_form` and `edt_unit_tests`; every run works on a fresh copy of its `1Cv8.1CD`; `edt_unit_tests` needs the YAXUNIT extension in it |
| `EDT_BRIDGE_TEST_PLATFORM` | – | – | the platform `bin` directory with `1cv8` and `ibcmd` that `edt_test_form` and `edt_unit_tests` run |
| `EDT_BRIDGE_TEST_USER` | – | – | the infobase user `edt_test_form` and `edt_unit_tests` sign in as, when the test infobase has users |
| `EDT_BRIDGE_TEST_CACHE` | – | `~/.cache/edt-bridge/test-manager` | where `test-form` and `edt_test_form` keep the prepared manager infobase of each platform build, so only the first check of a build creates it; `off` prepares it for every check. A runner image can carry a filled one; see [Prepare the environment](/native-forms#prepare-the-environment) |
| `EDT_BRIDGE_TOOLSET` | `-Dedt.bridge.toolset` | every tool | the same tool sets on the bridge side: `tools/list` returns only their tools, and a call of another tool is refused with the sets that hold it. `edt_tool_help` names the sets of any tool, and `/status` reports the active ones |
| `EDT_BRIDGE_WRITE_PROJECTS` | `-Dedt.bridge.write.projects` | every project | the projects the tools may change, separated by commas; see [Write scope](#write-scope) |
| `EDT_BRIDGE_READ_PROJECTS` | `-Dedt.bridge.read.projects` | – | the projects the tools may only read |
| `EDT_BRIDGE_OUTPUT_DIR` | `-Dedt.bridge.output.dir` | anywhere | the absolute directory the files the tools write must lie in |
| `EDT_BRIDGE_PII` | `-Dedt.bridge.pii` | `off` | `redact` or `pseudonym` filters personal data out of the answers that carry infobase data, in the bridge and in the wrapper; see [Personal data](#personal-data) |
| `EDT_BRIDGE_PII_SALT` | `-Dedt.bridge.pii.salt` | per process | the secret salt of the pseudonyms: with it the same value has the same pseudonym in every session |
| `EDT_BRIDGE_PII_RULES` | `-Dedt.bridge.pii.rules` | – | a JSON file with rules of your own: `values` (`kind` and `pattern`) and `names` (`kind` and a field name `pattern`) |

## Write scope

A tool set limits which tools run, not where they write. Three settings bound an agent: the
projects it may change (`EDT_BRIDGE_WRITE_PROJECTS`), the projects it may only read
(`EDT_BRIDGE_READ_PROJECTS`) and the directory for the files the tools write
(`EDT_BRIDGE_OUTPUT_DIR`). The bridge checks every call before the tool runs, so a client that
calls the port directly is held to the same scope. A refusal names what is allowed.

With the project lists set, a tool marked WRITE may change only a project from the first list, and
any tool reads only the projects of both lists; a tool that reads every project when none is named
needs `projectName`. A new extension or external object takes a name from the first list. The files
the tools write – `targetPath`, `logPath`, `outputPath`, `outPath` and the folder of a new infobase
– must lie under the output directory; once any of the settings is set, a file goes nowhere else.
A `modulePath` may not leave its project. `/status` reports the scope the bridge holds.

The settings reach EDT through the environment of its process: an EDT the wrapper starts inherits
them. When the wrapper has them and the running EDT reports another scope, the wrapper refuses its
calls until EDT is restarted with the same settings.

## Personal data

A test infobase is easily a copy of a working one, and what a tool returns from it goes to the
model. With `EDT_BRIDGE_PII` set to `redact` or `pseudonym`, the answers that carry infobase data
are filtered before they leave: in the bridge `edt_debug_inspect`, `edt_evaluate`,
`edt_infobase_sessions`, `edt_infobase_maintenance` and `edt_designer_agent`, in the wrapper `edt_test_form`, `edt_unit_tests` and their commands, with
`result.json` and the reports for CI. Module texts, schemas and templates are never filtered: a
model writes them back, and their `sourceHash` stays the same.

The filter finds personal data by its shape: an e-mail, a phone, a SNILS and an INN with their
check digits, a card number by the Luhn check and a passport; a number that is part of a fraction
or reads as a date stamp is left alone. By the name of a field or a variable it replaces every
value of `ФИО`, `Фамилия`, `Имя`, `Отчество`, `ДатаРождения`, `Паспорт...`, `Телефон...`, `Адрес...`,
`ЭлектроннаяПочта` and their English names, since a person's name cannot be told by its value. The
user and the computer of a session or an event are replaced whole.

`redact` writes the kind of the value, `[inn]`; `pseudonym` writes the kind and a pseudonym,
`[inn#icbbphoh]`, an HMAC of the value under `EDT_BRIDGE_PII_SALT`: the same value gets the same
pseudonym, and there is no table that leads back. A filtered answer says so in `piiFilter` with the
number of values replaced. A check still judges by the real values; only what it answers is masked,
and its screenshots are not kept (`screenshotWithheld`). Settings that cannot be read refuse the
call instead of answering unfiltered. A filter that fails on an answer withholds it with
`pii_filter_failed` and leaves nothing of the run in its output directory, and a file the filter
cannot read, such as an image a test attached, is not kept.
