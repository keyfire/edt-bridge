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
   carries a `SHA256SUMS.txt`, or build it as described below.
2. Copy it into EDT's `dropins/`: on Windows `.../installations/<EDT>/1cedt/dropins/`, on **macOS**
   `.../1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/dropins/`, creating the folder if it
   is not there. Keep only one EDT-Bridge jar in it, because two make Equinox load an arbitrary one.
3. **Restart EDT.** The plugin starts the MCP server on `http://127.0.0.1:8770/mcp`, or on the next
   free port if 8770 is busy.

To run EDT **headless**, without a window: `scripts/run-headless.ps1 -Workspace <ws>` on Windows, or
`scripts/run-headless.sh --workspace <ws>` on macOS and Linux. `scripts/toggle-headless.ps1` starts
and stops it in one action, and a running GUI EDT is never touched. To start the **GUI** on a
workspace: `scripts/run-gui.ps1 -Workspace <ws>`.

Both scripts refuse to start a second EDT on a workspace that is already in use, and neither removes
a lock that a live instance holds. The shared check lives in `scripts/edt-common.ps1`. Starting
`1cedt.exe` by hand skips that check: the second instance dies with "workspace is already in use",
and doing it twice leaves a pile of half-started windows.

An MCP client can also talk to the plugin over HTTP directly, with no wrapper. Add
`{ "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" } }` to its `.mcp.json`. The
server speaks plain JSON-RPC over HTTP: `initialize`, `tools/list`, `tools/call`.

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
| `EDT_BRIDGE_EDT_DIR` | `--edt-dir` | the newest install | the EDT install directory (`.../1cedt`) |
| `EDT_BRIDGE_START_TIMEOUT` | `--start-timeout` | `360` | how many seconds to wait for a starting bridge |
| `EDT_BRIDGE_AUTOSTART` | `--no-autostart` | on | `0` never launches anything and only forwards requests |
| `EDT_BRIDGE_WINDOW_WAIT` | – | `90` | how many seconds the `gui` command waits for the EDT window. A large workspace loads for minutes, so a miss here is not an error but a reason to run the command again |
| `EDT_BRIDGE_LANG` | – | the system locale | the language of the wrapper's help and messages (`ru` or `en`) |
| `EDT_BRIDGE_NO_PLUGINS` | – | off | `1` skips the discovery of [wrapper plugins](#wrapper-plugins), leaving the wrapper's own capabilities only |
| `EDT_BRIDGE_PLUGIN_INDEX` | – | – | the package index URL `self-update` names to pip for plugins installed by project name. A plugin installed from git carries its own source |

**Read by the plugin inside EDT**

| Variable | Launch property | Default | What it sets |
|----------|-----------------|---------|--------------|
| `EDT_BRIDGE_PORT` | `-Dedt.bridge.port` | `8770` | the MCP server's port. A busy one makes it take the next free port |
| `EDT_BRIDGE_TOKEN` | `-Dedt.bridge.token` | from the preference page | the shared secret every write tool requires |
| `EDT_BRIDGE_ALLOW_EVALUATE` | – | off | `1` enables `edt_evaluate`, arbitrary BSL executed against a live infobase. The preference page carries the same switch |
| `EDT_BRIDGE_AGENT_IDLE_MINUTES` | `-Dedt.bridge.agent-idle-minutes` | `30` | after how many idle minutes a configurator agent stops itself. `off` keeps agents forever |

What is given at launch wins. Environment variables and `-Dedt.bridge.*` properties take precedence
over EDT's preference page, and that is how the wrapper drives a headless instance.

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

Releases are cut from a locally built jar, because CI cannot compile it: the 1C:EDT SDK bundles are
proprietary and cannot be fetched anonymously. The maintainer runs
`scripts/build-nomaven.ps1 -Dist`, commits the jar under `dist/`, tags `vX.Y.Z` and pushes the tag.
`.github/workflows/release.yml` attaches the jar and its checksum. To verify an asset, rebuild from
the tagged source and compare.

## Wrapper plugins

Not everything a team runs next to the bridge belongs in a public repository: reference material
under somebody's license, tools wired to an internal service. Those live in separate packages
installed into the wrapper's own environment. The wrapper finds them through the `edt_bridge.tools`
entry-point group, lists the tools they declare next to the bridge's own, and dispatches them
itself, so they answer even while no EDT is running.

Installing one:

```bash
pipx inject edt-bridge-mcp <package>
```

### When pipx runs on uv (pipx >= 1.15)

A pipx backed by uv builds the venv without pip, and three familiar moves stop working the way they
read:

- `--pip-args="--no-deps"` written as one token reaches uv already split and dies with uv's own
  usage screen. Pass the flag as a separate argument:
  `pipx inject edt-bridge-mcp <package> --pip-args "--no-deps"`.
- `<venv>/Scripts/python -m pip` answers "No module named pip". `pipx runpip edt-bridge-mcp` still
  works, because pipx routes it into `uv pip` itself, and that is the supported way to reach the
  environment.
- reinstalling a plugin without `--no-deps` also reinstalls the core package, and it cannot replace
  `edt-bridge-mcp.exe` while a live MCP session holds it ("failed to persist ... Access denied").
  The plugin itself usually lands before the failure, so judge the outcome by
  `edt-bridge-mcp plugins` rather than by the exit code.

Here is the form that updates a plugin by hand without touching the busy core:

```bash
python -m pipx runpip edt-bridge-mcp -- install --upgrade --no-deps <package>
```

Add `--index-url <your index>` when the plugin lives in a private registry.

`edt-bridge-mcp self-update` takes that same route for every installed plugin on its own, and
`--plugins-only` limits it to the plugins. Each one is updated from the source it was installed
from: its git repository, or a package index by project name, with `EDT_BRIDGE_PLUGIN_INDEX` naming
the index for registry installs.

`edt-bridge-mcp plugins` lists what is plugged in – packages, entry points and the tools they add –
and prints the loader's message when a plugin is broken. `EDT_BRIDGE_NO_PLUGINS=1` turns the
discovery off.

A plugin declares its tools in `pyproject.toml`:

```toml
[project.entry-points."edt_bridge.tools"]
package-name = "my_package.tools:tools"
```

The value is a `Tool` from `edt_bridge_mcp.plugins`, a list of them, or a zero-argument callable
returning either. A `Tool` carries the MCP descriptor – `name`, `description`, the JSON schema of
the arguments – and the handler the wrapper calls. A str return becomes the text result, any other
JSON-serializable value is pretty-printed as JSON, and an exception becomes the tool's error
message. A failing entry point, a duplicated tool name and a name that shadows the wrapper's own
tools are all refused loudly at discovery, because a silently dropped plugin would leave an agent
without its tools and without an explanation.

A handler that also declares a `bridge` parameter – `handler(arguments, bridge)` – receives a
callable `bridge(tool_name, arguments) -> str` that forwards one call to the live bridge and returns
the text of its result. The wrapper never starts an EDT for it. With no bridge up the callable
raises `RuntimeError` with a readable message, and the plugin answers with a note instead of hanging
its caller through a minutes-long headless start. Older wrappers call such handlers with the single
argument as before, so give `bridge` a default of `None`.
