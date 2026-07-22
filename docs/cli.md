---
title: "Commands"
description: "Reference of the edt-bridge-mcp wrapper commands: call a tool from a shell, list tools, report the bridge, self-update."
sidebar:
  label: Commands
  order: 4
---

<!-- Собрано из вывода `edt-bridge-mcp --help` скриптом scripts/gen-cli-docs.py. Не редактировать вручную. -->

The `edt-bridge-mcp` wrapper is not only a stdio server for an MCP client. The same executable drives the bridge from a shell: whether it is up, which tools it serves, and what one of them answers. That beats starting an agent when all you want is to check that things are alive.

With no command the wrapper runs as an MCP server – that is how a client launches it. The commands below address that same bridge.

The exit code separates two different failures: **1** – the call never happened (no bridge, bad arguments), **2** – the tool ran and reported an error. In a script that is the difference between "the bridge is down" and "the bridge is up, but the job failed".

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

Tool arguments come from `--json`, a file (`--json-file`) or standard input (`--stdin`). On Windows, non-ASCII arguments travel most reliably in a file: the console code page mangles them on the command line.

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

Report the running bridge (does not start one).

```bash
usage: edt-bridge-mcp status [-h] [--workspace WORKSPACE] [--edt-dir EDT_DIR]
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

Refresh the plugin jar in EDT's dropins and this wrapper in its own environment.

```bash
usage: edt-bridge-mcp self-update [options]
```

**Options**

| Option | Description |
|---|---|
| `--jar-only` | only the plugin jar (GitHub Releases) |
| `--pip-only` | only the wrapper (PyPI, or `--from`) |
| `--from <path>` | install the wrapper from a checkout instead of PyPI |
| `-h, --help` | show this message |

The jar applies on EDT's next restart. The wrapper is replaced inside site-packages; the exes in Scripts are left alone, so restart any running edt-bridge-mcp afterwards.

