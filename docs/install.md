---
title: "Installation and build"
description: "Requirements, manual install without the wrapper, and building the plugin from source."
sidebar:
  label: Install
  order: 4
---

**To use the bridge**

- **1C:EDT** with your project open, or let `edt-bridge-mcp` start a headless EDT for you.
- A locally installed **1C:Enterprise platform** matching the project's version, for
  `edt_dump_external_object` (building `.epf` and `.erf`) and for `edt_update_infobase`. EDT drives
  that platform to compile the binary and to update the infobase.

**To build the plugin from source.** This is for contributors; end users install through pipx.

- A **JDK that matches the EDT bundles**. EDT 2026.2 ships Java 25 class files, so compiling against
  them needs a JDK 25. The build script reads the level from the pool and finds a suitable JDK
  itself, including the one installed alongside EDT. The jar still targets Java 17, so one build
  also loads in EDT versions that run on Java 17.
- The local **EDT bundle pool**. On **Windows** that is the p2 pool
  `%USERPROFILE%\.p2\pool\plugins`. On **macOS** it sits inside the installed component:
  `.../1C/1CE/components/1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/plugins`, and the
  shell build finds it by itself.

## Manual install (without the wrapper)

The pipx wrapper delivers the jar and starts EDT for you. To run the plugin yourself instead:

1. Get the jar from the [Releases page](https://github.com/keyfire/edt-bridge/releases), which also
   includes `SHA256SUMS.txt`, or build it as described below.
2. Copy it into EDT's `dropins/`: on Windows `.../installations/<EDT>/1cedt/dropins/` or
   `.../1C/1CE/components/1c-edt-<ver>-x86_64/dropins/`, on **macOS**
   `.../1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/dropins/`, creating the folder if it
   is not there. Keep only one EDT-Bridge jar in it, because two make Equinox load an arbitrary one.
3. **Restart EDT.** The plugin starts the MCP server on `http://127.0.0.1:8770/mcp`, or on the next
   free port if 8770 is busy.

To run EDT **headless**, without a window: `scripts/run-headless.ps1 -Workspace <ws>` on Windows, or
`scripts/run-headless.sh --workspace <ws>` on macOS and Linux. `scripts/toggle-headless.ps1` starts
and stops it in one action, and a running GUI EDT is never touched. To start the **GUI** on a
workspace: `scripts/run-gui.ps1 -Workspace <ws>`.

Both scripts refuse to start a second EDT on a workspace that is already in use, and neither removes
a lock held by a running instance. The shared check is in `scripts/edt-common.ps1`. Starting
`1cedt.exe` manually bypasses the check: the second instance fails with "workspace is already in use".
Repeating the attempt can leave partially started windows.

An MCP client can also connect to the plugin directly over HTTP. Add
`{ "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" } }` to its `.mcp.json`. The
server uses JSON-RPC over HTTP: `initialize`, `tools/list`, `tools/call`.

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
| `EDT_BRIDGE_NO_PLUGINS` | – | off | `1` skips the discovery of [wrapper plugins](#wrapper-plugins), leaving the wrapper's own capabilities only |
| `EDT_BRIDGE_TOOLSET` | – | every tool | one tool set or several, separated by commas: `processor`, `report`, `extension`. The wrapper then lists and runs only their tools, its own and plugin tools included. An unknown name refuses the list and every call |
| `EDT_BRIDGE_PLUGIN_INDEX` | – | – | the package index URL `self-update` names to pip for plugins installed by project name. A plugin installed from git records its source |

**Read by the plugin inside EDT**

| Variable | Launch property | Default | What it sets |
|----------|-----------------|---------|--------------|
| `EDT_BRIDGE_PORT` | `-Dedt.bridge.port` | `8770` | the MCP server's port. A busy one makes it take the next free port |
| `EDT_BRIDGE_TOKEN` | `-Dedt.bridge.token` | from the preference page | the shared secret every write tool requires |
| `EDT_BRIDGE_ALLOW_EVALUATE` | – | off | `1` enables `edt_evaluate`, arbitrary BSL executed against a live infobase. The preference page has the same switch |
| `EDT_BRIDGE_AGENT_IDLE_MINUTES` | `-Dedt.bridge.agent-idle-minutes` | `30` | after how many idle minutes a configurator agent stops itself. `off` keeps agents forever |
| `EDT_BRIDGE_DUMP_INFOBASE` | – | – | a file infobase (its directory, its `1Cv8.1CD` or `File="...";`) that carries the base configuration of external objects. The disk route of `edt_dump_external_object` builds in a copy of it, so types of the base configuration resolve; without it the build runs in an empty infobase. `edt_build_extension` also imports the extension into a copy of it: a borrowed form whose excluded commands (Post, Write) belong to its main attribute, and the base configuration's types, are checked against that configuration |
| `EDT_BRIDGE_TEST_INFOBASE` | – | – | a file infobase with test data for `edt_test_form`; every run works on a fresh copy of its `1Cv8.1CD` |
| `EDT_BRIDGE_TEST_PLATFORM` | – | – | the platform `bin` directory with `1cv8` and `ibcmd` that `edt_test_form` runs |
| `EDT_BRIDGE_TEST_USER` | – | – | the infobase user `edt_test_form` signs in as, when the test infobase has users |
| `EDT_BRIDGE_TOOLSET` | `-Dedt.bridge.toolset` | every tool | the same tool sets on the bridge side: `tools/list` returns only their tools, and a call of another tool is refused with the sets that hold it. `edt_tool_help` names the sets of any tool, and `/status` reports the active ones |

Environment variables and `-Dedt.bridge.*` properties supplied at launch take precedence over
EDT's preference page. The wrapper uses them to configure a headless instance.


During auto-start, a detached supervisor keeps the CLI input open and tracks the launched
`1cedtcli` process. If the CLI exits before the bridge is ready, the wrapper reports its exit
code and up to 16 KiB from the end of its combined stdout/stderr, decoded as UTF-8 with replacement
for invalid bytes. The supervisor exits with the CLI, and a later call can retry the launch.
`--start-timeout` limits the readiness wait while the CLI is still running. A timeout leaves
the live process running; EDT also continues running after a short-lived wrapper command exits.

The bridge answers before a large project is indexed, and a tool called in between is refused
with "no BM model". In a CI job, wait for the models with `edt-bridge-mcp wait-ready --project
<name> --timeout <seconds>`. The command starts a headless EDT when none is running and polls
`/status` until every named project reports `modelReady`; without `--project` it waits for every
open EDT project. It exits with code 0 when the projects are ready, 1 when EDT is not running or
has exited, and 2 when the time runs out while EDT is still loading. A project imported after the
start is awaited as well.

### A ready workspace

Importing and indexing a large configuration takes a headless EDT tens of minutes, while a
workspace that EDT saved on a graceful stop opens in a few. `edt-bridge-mcp workspace` moves
such a workspace to the next machine or container:

1. Start EDT on the workspace, import the projects and wait for the models:
   `edt-bridge-mcp wait-ready --workspace <ws> --project <name>`.
2. Save the EDT checks of the projects: `edt-bridge-mcp workspace checks --workspace <ws>
   --object <fqn> --output before.json`. The command runs `edt_project_errors` with a refresh for
   every project, or for every object given with `--object`, and refuses a list that is incomplete,
   not settled or cut at its limit. On a large configuration check a few objects whose checks depend
   on the indexed model: the whole project can exceed the problem limit.
3. Stop EDT with `edt-bridge-mcp shutdown --workspace <ws>`, so that it saves its state.
4. Pack the workspace: `edt-bridge-mcp workspace pack --workspace <ws> --archive ready.tar.gz`.
5. On the next machine restore it: `edt-bridge-mcp workspace restore --archive ready.tar.gz`.
6. Start EDT there, wait for the models and compare: `edt-bridge-mcp workspace checks
   --workspace <ws> --compare before.json`.

The archive is a PAX tar, `.tar`, `.tar.gz` or, with Python 3.14 and newer, `.tar.zst`. It keeps
the modification times with their fraction of a second, because EDT compares them with the state
it saved. It leaves out the workspace lock, the logs of `.metadata` (EDT writes its command line
with the bridge token there) and the pid and log lock files. EDT keeps absolute paths in its
metadata, so `restore` unpacks the workspace only under the path it was packed from, into a
missing or empty directory, on the same kind of system. `pack` refuses while an EDT uses the
workspace, and both commands name the process or the lock that holds it. A project whose files
live outside the workspace directory is refused as well, because the archive would not carry it.
`checks --compare` exits with code 0 when the checks match and 2 when they differ, and lists
the problems found on one side only. Where the archives are kept and which inputs name one is
left to the job that builds them.

### Build from source

Without Maven this is the quickest route: a local JDK plus the EDT pool, no network.

```powershell
# Windows – defaults: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File scripts/build-nomaven.ps1
```

```bash
# macOS / Linux – --pool auto-detected from the installed 1C:EDT component pool
./scripts/build-nomaven.sh
```

It produces `build/io.github.keyfire.edtbridge_<version>.<timestamp>.jar`. Maven and Tycho
(`mvn -f pom.xml clean verify`, after editing `edt-bridge.target`) are there for CI.

EDT does not pick the new jar up by itself. On Windows `scripts/run-headless.ps1 -Workspace <ws>
-FromBuild` stops the headless EDT of that workspace, leaves the newest jar from `build/` as the
only bridge jar in `dropins/` and starts EDT again; `-Jar <path>` installs a given jar. The script
refuses while a GUI EDT holds the jar, and a jar that stays locked is reported without a copy. Once
the bridge answers, its `bridgeBundleVersion` must match the version in the jar name, otherwise
the script exits with code 3.

Releases are cut from a locally built jar, because CI cannot compile it: the 1C:EDT SDK bundles are
proprietary and cannot be fetched anonymously. The maintainer runs
`scripts/build-nomaven.ps1 -Dist`, commits the jar under `dist/`, tags `vX.Y.Z` and pushes the tag.
`.github/workflows/release.yml` attaches the jar and its checksum. To verify an asset, rebuild from
the tagged source and compare.

## Wrapper plugins

Wrapper plugins are distributed as separate packages and installed in the wrapper's environment.
They can contain reference material under another license or tools for internal services. The
wrapper discovers them through the `edt_bridge.tools` entry-point group, lists their tools alongside
its own, and executes them. These tools are available without a running EDT.

Installing one:

```bash
pipx inject edt-bridge-mcp <package>
```

### When pipx runs on uv (pipx >= 1.15)

When pipx uses uv, it creates the environment without pip. Note the following:

- When `--pip-args="--no-deps"` is written as one token, uv receives the argument split and displays
  its usage message. Pass the flag as a separate argument:
  `pipx inject edt-bridge-mcp <package> --pip-args "--no-deps"`.
- `<venv>/Scripts/python -m pip` returns "No module named pip". Use `pipx runpip edt-bridge-mcp`
  to access the environment: pipx routes the command to `uv pip`.
- Reinstalling a plugin without `--no-deps` also reinstalls the core package. If an MCP session
  holds `edt-bridge-mcp.exe` open, the file cannot be replaced ("failed to persist ... Access denied").
  The plugin is usually installed before the failure. Check `edt-bridge-mcp plugins` for the result
  instead of relying on the exit code.

To update a plugin without reinstalling the core package while it is in use, run:

```bash
python -m pipx runpip edt-bridge-mcp -- install --upgrade --no-deps <package>
```

Add `--index-url <your index>` when the plugin is hosted in a private registry.

`edt-bridge-mcp self-update` updates installed plugins. The `--plugins-only` flag limits the
update to plugins. Each one is updated from the source it was installed
from: its git repository, or a package index by project name, with `EDT_BRIDGE_PLUGIN_INDEX` naming
the index for registry installs.

`edt-bridge-mcp plugins` lists installed packages, entry points and their tools. If a plugin fails
to load, the command prints the loader's message. `EDT_BRIDGE_NO_PLUGINS=1` turns the
discovery off.

A plugin declares its tools in `pyproject.toml`:

```toml
[project.entry-points."edt_bridge.tools"]
package-name = "my_package.tools:tools"
```

The value is a `Tool` from `edt_bridge_mcp.plugins`, a list of them, or a zero-argument callable
returning either. A `Tool` contains the MCP descriptor – `name`, `description`, the JSON schema of
the arguments – and the handler the wrapper calls. A str return becomes the text result, any other
JSON-serializable value is pretty-printed as JSON, and an exception becomes the tool's error
message. A failing entry point, duplicate tool name, or name that shadows a wrapper tool produces an error
at discovery.

A handler that also declares a `bridge` parameter – `handler(arguments, bridge)` – receives a
callable `bridge(tool_name, arguments) -> str` that forwards one call to the live bridge and returns
the text of its result. This call does not start EDT. If the bridge is unavailable, the callable raises `RuntimeError` and
the plugin returns its message. Older wrappers call such handlers with one argument, so give
`bridge` a default of `None`.
