---
title: "Commands"
description: "Reference of the edt-bridge-mcp wrapper commands: call a tool from a shell, list tools, report the bridge, self-update."
sidebar:
  label: Commands
  order: 5
---

<!-- Собрано из вывода `edt-bridge-mcp --help` скриптом scripts/gen-cli-docs.py. Не редактировать вручную. -->

The `edt-bridge-mcp` wrapper runs as a stdio MCP server for a client. From the command line, you can check bridge status, list tools, and call a tool without starting an agent.

With no command, the wrapper runs as an MCP server. The commands below use the same bridge.

Exit code **1** means the call did not run because the bridge did not start or the arguments were invalid. Exit code **2** means the tool ran and returned an error.

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
| `--edt-dir EDT_DIR` | EDT install dir (.../1cedt); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--version` | show the version and exit |

**Commands**

| Command | Description |
|---|---|
| `call <tool>` | call one bridge tool and print what it returned |
| `tools` | list the tools the running bridge serves |
| `status` | report the running bridge (never starts one) |
| `shutdown` | shut down the EDT behind the bridge |
| `gui` | stop the headless EDT and open the GUI one |
| `plugins` | list the wrapper's plugins and their tools |
| `self-update` | refresh the plugin jar and this wrapper |

Run 'edt-bridge-mcp &lt;command&gt; `--help`' for a command's own options. The options above apply to the MCP-server mode and to every command.

## `edt-bridge-mcp call`

Call one bridge tool and print its result.

```bash
usage: edt-bridge-mcp call [-h] [--json JSON | --json-file PATH | --stdin] [--raw]
                           [--workspace WORKSPACE] [--edt-dir EDT_DIR] [--port PORT]
                           [--start-timeout START_TIMEOUT] [--no-autostart]
                           [--version]
                           tool
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
| `--edt-dir EDT_DIR` | EDT install dir (.../1cedt); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--version` | show the version and exit |

Tool arguments come from `--json`, a file (`--json-file`) or standard input (`--stdin`). On Windows, pass non-ASCII arguments in a file because the console code page can corrupt them on the command line.

## `edt-bridge-mcp tools`

List the tools the running bridge serves.

```bash
usage: edt-bridge-mcp tools [-h] [--raw] [--workspace WORKSPACE] [--edt-dir EDT_DIR]
                            [--port PORT] [--start-timeout START_TIMEOUT]
                            [--no-autostart] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--raw` | print the raw JSON result instead of the text the tool returned |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (.../1cedt); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--version` | show the version and exit |

## `edt-bridge-mcp status`

Report the running bridge (does not start one) and the version of the jar in dropins - the code EDT will load next time it starts.

```bash
usage: edt-bridge-mcp status [-h] [--all] [--ports PORTS] [--workspace WORKSPACE]
                             [--edt-dir EDT_DIR] [--port PORT]
                             [--start-timeout START_TIMEOUT] [--no-autostart]
                             [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--all` | inspect every port in the scan range without filtering workspaces or starting EDT |
| `--ports PORTS` | ports for `--all`, comma-separated or ranges: 8770,8772-8774 (up to 128 ports) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (.../1cedt); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--version` | show the version and exit |

## `edt-bridge-mcp shutdown`

Shut down the EDT behind the bridge - instead of killing its processes. A headless one is relaunched on the next call; a GUI is left alone without `--force`.

```bash
usage: edt-bridge-mcp shutdown [-h] [--force] [--no-wait] [--workspace WORKSPACE]
                               [--edt-dir EDT_DIR] [--port PORT]
                               [--start-timeout START_TIMEOUT] [--no-autostart]
                               [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--force` | shut a GUI EDT down too: without this flag the bridge refuses rather than close somebody's window |
| `--no-wait` | do not wait for the bridge to go down (the default waits up to 60 seconds) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (.../1cedt); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--version` | show the version and exit |

## `edt-bridge-mcp gui`

Hand the workspace over to the GUI EDT: stop the headless one, wait until its processes are really gone, and open the EDT window on the same workspace. Never starts a headless EDT on the way.

```bash
usage: edt-bridge-mcp gui [-h] [--force] [--timeout TIMEOUT] [--workspace WORKSPACE]
                          [--edt-dir EDT_DIR] [--port PORT]
                          [--start-timeout START_TIMEOUT] [--no-autostart] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--force` | kill the headless session when it does not stop within the timeout |
| `--timeout TIMEOUT` | seconds to wait for the headless EDT to stop (default 90) |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (.../1cedt); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--version` | show the version and exit |

## `edt-bridge-mcp plugins`

List the wrapper's plugins: packages, entry points and the tools they add. Plugins are installed in the wrapper's environment (pipx inject); no bridge is needed for this command.

```bash
usage: edt-bridge-mcp plugins [-h] [--workspace WORKSPACE] [--edt-dir EDT_DIR]
                              [--port PORT] [--start-timeout START_TIMEOUT]
                              [--no-autostart] [--version]
```

**Options**

| Option | Description |
|---|---|
| `-h, --help` | show this help message and exit |
| `--workspace WORKSPACE` | EDT workspace path for the headless auto-start |
| `--edt-dir EDT_DIR` | EDT install dir (.../1cedt); auto-detected when omitted |
| `--port PORT` | bridge port (default 8770) |
| `--start-timeout START_TIMEOUT` | seconds to wait for a starting backend |
| `--no-autostart` | never launch a headless EDT |
| `--version` | show the version and exit |

## `edt-bridge-mcp self-update`

Refresh the plugin jar in EDT's dropins, this wrapper in its own environment, and the wrapper plugins.

```bash
usage: edt-bridge-mcp self-update [options]
```

**Options**

| Option | Description |
|---|---|
| `--jar-only` | only the plugin jar (GitHub Releases) |
| `--pip-only` | only the wrapper (PyPI, or `--from`) |
| `--plugins-only` | only the wrapper plugins |
| `--from <path>` | install the wrapper from a checkout instead of PyPI |
| `-h, --help` | show this message |
| `0` | every step asked for succeeded |
| `1` | the bridge itself did not update (jar or wrapper), or nothing did |
| `2` | the bridge is current and only a wrapper plugin is not |

The jar applies on EDT's next restart. The wrapper is replaced inside site-packages; the exes in Scripts are left alone, so restart any running edt-bridge-mcp afterwards. Plugins are updated through pip from the source they were installed from (a git repository or a package index; set EDT_BRIDGE_PLUGIN_INDEX to name the index).

exit codes:

