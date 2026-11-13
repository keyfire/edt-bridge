---
title: "Commands"
description: "Reference of the edt-bridge-mcp wrapper commands: call a tool from a shell, list tools, report the bridge, self-update."
sidebar:
  label: Commands
  order: 5
---

<!-- Собрано из вывода `edt-bridge-mcp --help` скриптом scripts/gen-cli-docs.py. Не редактировать вручную. -->

The `edt-bridge-mcp` wrapper runs as a stdio MCP server for a client. From the command line, you can check bridge status, list tools, and call a tool without starting an agent.

With no command, the wrapper runs as an MCP server. Bridge commands use the same EDT instance. `test-form` starts separate platform sessions.

For tool calls, exit code **1** means the bridge did not start or the arguments were invalid. Exit code **2** means the tool ran and returned an error.

## No command: running as an MCP server

stdio MCP front-end for the edt-bridge 1C:EDT plugin: proxies to a running EDT, or auto-starts a headless one.

```bash
usage: edt-bridge-mcp [options]           (no command: run as an MCP server)
       edt-bridge-mcp <command> [options]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

**Commands**

| Command | Description |
|---|---|
| `call <tool>` | call one bridge tool and print what it returned |
| `tools` | list the tools the running bridge serves |
| `status` | report the running bridge (never starts one) |
| `wait-ready` | wait until the project models are loaded |
| `shutdown` | shut down the EDT behind the bridge |
| `gui` | stop the headless EDT and open the GUI one |
| `plugins` | list the wrapper's plugins and their tools |
| `workspace` | pack, restore and compare an EDT workspace |
| `test-form` | check a form and scenario results in a test infobase |
| `self-update` | refresh the plugin jar and this wrapper |

Run 'edt-bridge-mcp &lt;command&gt; `--help`' for a command's own options. Connection options apply to the MCP server mode and the bridge commands.

## `edt-bridge-mcp call`

Call one bridge tool and print its result.

```bash
usage: edt-bridge-mcp call [-h] [--json JSON | --json-file PATH | --stdin] [--raw]
                           [--workspace WORKSPACE] [--edt-dir EDT_DIR] [--port PORT]
                           [--start-timeout START_TIMEOUT] [--no-autostart] [--heap
                           HEAP] [--vmargs OPTIONS] [--version] tool
```

**Arguments**

| Option | Description |
|---|---|
| `tool` | tool name, e.g. edt_projects (see: edt-bridge-mcp tools) |

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--json JSON` | tool arguments as a JSON object |
| `--json-file PATH` | read the JSON object from a UTF-8 file - the reliable route for arguments with non-ASCII text on Windows |
| `--stdin` | read the JSON object from standard input |
| `--raw` | print the raw JSON result instead of the text the tool returned |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

Tool arguments come from `--json`, a file (`--json-file`) or standard input (`--stdin`). On Windows, pass non-ASCII arguments in a file because the console code page can corrupt them on the command line.

## `edt-bridge-mcp tools`

List the tools the running bridge serves.

```bash
usage: edt-bridge-mcp tools [-h] [--raw] [--workspace WORKSPACE] [--edt-dir EDT_DIR]
                            [--port PORT] [--start-timeout START_TIMEOUT]
                            [--no-autostart] [--heap HEAP] [--vmargs OPTIONS]
                            [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--raw` | print the raw JSON result instead of the text the tool returned |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

## `edt-bridge-mcp status`

Report the running bridge and the version of the JAR in dropins. Version mismatches between the wrapper and the plugin are reported on stderr. With `--all`, check each bridge found.

```bash
usage: edt-bridge-mcp status [-h] [--all] [--ports PORTS] [--workspace WORKSPACE]
                             [--edt-dir EDT_DIR] [--port PORT] [--start-timeout
                             START_TIMEOUT] [--no-autostart] [--heap HEAP] [--vmargs
                             OPTIONS] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--all` | inspect every port in the scan range without filtering workspaces or starting EDT |
| `--ports PORTS` | ports for `--all`, comma-separated or ranges: 8770,8772-8774 (up to 128 ports) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

## `edt-bridge-mcp wait-ready`

Wait until the EDT models of the projects are loaded (modelReady), starting a headless EDT if needed. Exit code 0 the projects are ready, 1 EDT is not running or has exited, 2 the time ran out while EDT is still loading.

```bash
usage: edt-bridge-mcp wait-ready [-h] [--project NAME] [--timeout TIMEOUT] [--interval
                                 INTERVAL] [--workspace WORKSPACE] [--edt-dir EDT_DIR]
                                 [--port PORT] [--start-timeout START_TIMEOUT]
                                 [--no-autostart] [--heap HEAP] [--vmargs OPTIONS]
                                 [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--project NAME` | a project to wait for; repeat for several. Without it every open EDT project counts |
| `--timeout TIMEOUT` | seconds to wait (default 3600) |
| `--interval INTERVAL` | seconds between polls (default 5) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

## `edt-bridge-mcp shutdown`

Shut down the EDT behind the bridge - instead of killing its processes. A headless one is relaunched on the next call; a GUI is left alone without `--force`.

```bash
usage: edt-bridge-mcp shutdown [-h] [--force] [--no-wait] [--workspace WORKSPACE]
                               [--edt-dir EDT_DIR] [--port PORT] [--start-timeout
                               START_TIMEOUT] [--no-autostart] [--heap HEAP] [--vmargs
                               OPTIONS] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--force` | shut a GUI EDT down too: without this flag the bridge refuses rather than close somebody's window |
| `--no-wait` | do not wait for the bridge to go down (the default waits up to 60 seconds) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

## `edt-bridge-mcp gui`

Hand the workspace over to the GUI EDT: stop the headless one, wait until its processes are really gone, and open the EDT window on the same workspace. Never starts a headless EDT on the way.

```bash
usage: edt-bridge-mcp gui [-h] [--force] [--timeout TIMEOUT] [--workspace WORKSPACE]
                          [--edt-dir EDT_DIR] [--port PORT] [--start-timeout
                          START_TIMEOUT] [--no-autostart] [--heap HEAP] [--vmargs
                          OPTIONS] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--force` | kill the headless session when it does not stop within the timeout |
| `--timeout TIMEOUT` | seconds to wait for the headless EDT to stop (default 90) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

## `edt-bridge-mcp plugins`

List the wrapper's plugins: packages, entry points and the tools they add. Plugins are installed in the wrapper's environment (pipx inject); no bridge is needed for this command.

```bash
usage: edt-bridge-mcp plugins [-h] [--workspace WORKSPACE] [--edt-dir EDT_DIR] [--port
                              PORT] [--start-timeout START_TIMEOUT] [--no-autostart]
                              [--heap HEAP] [--vmargs OPTIONS] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

## `edt-bridge-mcp workspace`

A ready EDT workspace: pack archives the workspace of a stopped EDT as a tar with its modification times, restore unpacks it under the same path, checks saves the EDT checks of the projects to a file (`--output`) or compares them with one (`--compare`). The .metadata logs, which carry the EDT command line, stay out of the archive.

```bash
usage: edt-bridge-mcp workspace [-h] [--archive PATH] [--output PATH] [--compare PATH]
                                [--project NAME] [--object FQN] [--wait WAIT]
                                [--workspace WORKSPACE] [--edt-dir EDT_DIR] [--port
                                PORT] [--start-timeout START_TIMEOUT] [--no-autostart]
                                [--heap HEAP] [--vmargs OPTIONS] [--version]
                                {pack,restore,checks}
```

**Arguments**

| Option | Description |
|---|---|
| `{pack,restore,checks}` | action: pack, restore or checks |

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--archive PATH` | workspace archive: .tar, .tar.gz or .tar.zst (Python 3.14 or newer) |
| `--output PATH` | checks: a new file to save the checks to |
| `--compare PATH` | checks: a saved checks file to compare with; exit code 2 when they differ |
| `--project NAME` | checks: a project to check; repeat for several. Without it every open EDT project counts |
| `--object FQN` | checks: a metadata object whose problems are compared, e.g. Document.Order; repeat for several. Without it the whole project is checked, which on a large configuration runs into the problem limit |
| `--wait WAIT` | checks: seconds to wait for the validation of each project (default 600) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |
| `--version` | show the version and exit |

## `edt-bridge-mcp test-form`

Check an EPF, ERF or configuration object form in an explicitly selected file test infobase on isolated Linux.

```bash
usage: edt-bridge-mcp test-form [-h] --platform-bin PLATFORM_BIN --test-infobase
                                TEST_INFOBASE --scope {empty,fixture,application} (--epf
                                EPF | --erf ERF | --navigation-link NAVIGATION_LINK)
                                --form FORM [--form-title FORM_TITLE] (--button BUTTON |
                                --scenario SCENARIO) [--expect-message EXPECT_MESSAGE]
                                [--user USER] --output-dir OUTPUT_DIR [--timeout
                                TIMEOUT] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--platform-bin PLATFORM_BIN` | platform directory containing the 1cv8 and ibcmd executables |
| `--test-infobase TEST_INFOBASE` | dedicated file test infobase directory containing 1Cv8.1CD |
| `--scope {empty,fixture,application}` | declared test scope: empty infobase, fixture configuration, or application environment |
| `--epf EPF` | external data processor file to check |
| `--erf ERF` | external report file to check |
| `--navigation-link NAVIGATION_LINK` | internal e1cib/data link to a document or catalog item in the test infobase |
| `--form FORM` | fully qualified managed form name, for example ExternalReport.Sales.Form for a standard DCS form |
| `--form-title FORM_TITLE` | window title of the form in the client; * and ? are wildcards. The window of an object form adds the object presentation to the title, so pass a pattern such as "Title*". Without it the form is found by its `--form` name |
| `--button BUTTON` | exact title of the button that invokes the command |
| `--scenario SCENARIO` | JSON scenario containing inputs, commands and assertions of observed results |
| `--expect-message EXPECT_MESSAGE` | exact new user message required to confirm the command result |
| `--user USER` | test infobase user name for a login that does not require a password |
| `--output-dir OUTPUT_DIR` | new directory for the JSON report and PNG screenshot; missing parent directories are created |
| `--timeout TIMEOUT` | check time limit in seconds, default 120 |
| `--version` | show the version and exit |

Run the check in an isolated Linux environment with `1cv8`, `ibcmd`, `Xvfb`, and `libX11`. Select a disposable file infobase and one target: `--epf`, `--erf`, or `--navigation-link`. For an EPF or ERF with an assigned form, specify its full name. For an ERF with a main data composition schema and no assigned form, use `ExternalReport.<Name>.Form`. A configuration object requires `--scenario`.

A JSON scenario checks actual fields, spreadsheet cells, and fresh message counts. The `reopen` phase opens the same target in a new session to read its saved state. The command writes `result.json` and a screenshot of each phase when the client display is available. Unsafe action protection is configured by the test environment owner; the CLI does not change it.

In the `--button` profile, success requires `--expect-message`; without it, `command_observed` returns exit code **2**. The time limit covers setup and all phases; process cleanup has a separate short deadline. See the [native form guide](native-forms.md) for preparation, the scenario format, and examples.

## `edt-bridge-mcp self-update`

Update the plugin jar in EDT's dropins, the wrapper and its plugins.

```bash
usage: edt-bridge-mcp self-update [-h] [--jar-only | --pip-only | --plugins-only]
                                  [--from PATH | --version X.Y.Z] [--workspace
                                  WORKSPACE] [--edt-dir EDT_DIR] [--port PORT]
                                  [--start-timeout START_TIMEOUT] [--no-autostart]
                                  [--heap HEAP] [--vmargs OPTIONS]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--jar-only` | update only the plugin jar |
| `--pip-only` | update only the wrapper |
| `--plugins-only` | update only the wrapper plugins |
| `--from PATH` | install the wrapper from a checkout |
| `--version X.Y.Z` | install this release of the jar and wrapper, or just the selected component |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--heap HEAP` | maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g |
| `--vmargs OPTIONS` | further JVM options of an auto-started headless EDT, separated by spaces; join them to the flag with = because they start with a dash |

With `--version`, the command gets the jar from the vX.Y.Z tag on GitHub and the wrapper from PyPI. Both `--version` and `--from` require a mode other than `--plugins-only`. The `--from` option also requires a mode other than `--jar-only`.

Restart EDT to load the updated jar and restart the MCP client to load the wrapper.

Wrapper plugins are updated through pip from their installation sources. Use EDT_BRIDGE_PLUGIN_INDEX to set the package index URL.

Exit code 0 means all selected steps succeeded. Code 1 means the jar or wrapper update failed, or none of the selected steps succeeded. Code 2 means the arguments are invalid or only the wrapper plugin step failed.
