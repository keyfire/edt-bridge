---
title: "Installation and build"
description: "Requirements, manual install without the wrapper, and building the plugin from source."
sidebar:
  label: Install
  order: 4
---

**To use the bridge**

- **1C:EDT** with your project open – or let `edt-bridge-mcp` auto-start a headless EDT.
- For `edt_dump_external_object` (building `.epf`/`.erf`) and `edt_update_infobase`: a locally
  installed **1C:Enterprise platform** matching the project's version – EDT drives it to compile
  the binary and to update the infobase.

**To build the plugin from source** (contributors – end users install via pipx)

- A **JDK matching the EDT bundles** – EDT 2026.2 ships Java 25 class files, so compiling against
  them needs a JDK 25 (the build script reads the level from the pool and finds a suitable JDK by
  itself, including the one installed alongside EDT). The jar keeps targeting Java 17, so a single
  build also loads in EDT versions that still run on Java 17.
- The local **EDT bundle pool**. On **Windows** the p2 pool `%USERPROFILE%\.p2\pool\plugins`; on
  **macOS** the pool inside the installed component
  `.../1C/1CE/components/1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/plugins`
  (the shell build auto-detects it).

## Manual install (without the wrapper)

The pipx wrapper delivers the jar and starts EDT for you. To run the plugin yourself instead:

1. Get the jar – from the [Releases page](https://github.com/keyfire/edt-bridge/releases) (with a
   `SHA256SUMS.txt`), or build it (below).
2. Copy it into EDT's `dropins/` – Windows `.../installations/<EDT>/1cedt/dropins/`, **macOS**
   `.../1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/dropins/` (create it if absent). Keep
   only one EDT-Bridge jar there – two make Equinox load an arbitrary one.
3. **Restart EDT.** The plugin starts the MCP server on `http://127.0.0.1:8770/mcp` (or the next
   free port if 8770 is busy).

To run EDT **headless** (no GUI): `scripts/run-headless.ps1 -Workspace <ws>` (Windows) or
`scripts/run-headless.sh --workspace <ws>` (macOS / Linux); `scripts/toggle-headless.ps1` starts/stops it in one
action. A running GUI EDT is never touched. To start the **GUI** on a workspace:
`scripts/run-gui.ps1 -Workspace <ws>`.

Both launchers refuse to start a second EDT on a workspace that is already in use, and neither
removes a lock that a live instance holds – the shared check lives in `scripts/edt-common.ps1`.
Starting `1cedt.exe` by hand skips that check: the second instance dies with "workspace is already
in use", and doing it twice leaves a pile of half-started windows.

An MCP client can also talk to the plugin over HTTP directly (no wrapper) – add
`{ "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" } }` to its `.mcp.json`. The
server speaks plain JSON-RPC over HTTP (`initialize` / `tools/list` / `tools/call`).

## Environment variables

The wrapper and the plugin take their settings under the same `EDT_BRIDGE_*` names, but different
sides read them: the wrapper at its own start, the plugin inside EDT. Most wrapper settings have a
flag twin, documented in [Commands](/cli).

**Read by the `edt-bridge-mcp` wrapper**

| Variable | Flag | Default | What it sets |
|----------|------|---------|--------------|
| `EDT_BRIDGE_PORT` | `--port` | `8770` | the port the wrapper looks for the bridge on |
| `EDT_BRIDGE_PORT_SCAN` | – | `20` | how many consecutive ports from that one to scan: a busy port makes the plugin take the next free one, and the bridge is then looked for across that same window |
| `EDT_BRIDGE_TOKEN` | – | empty | the write-tools token: sent as an `Authorization: Bearer` header and passed into the headless instance's JVM |
| `EDT_BRIDGE_WORKSPACE` | `--workspace` | – | the EDT workspace to serve when auto-starting headless |
| `EDT_BRIDGE_EDT_DIR` | `--edt-dir` | the newest install | the EDT install directory (`.../1cedt`) |
| `EDT_BRIDGE_START_TIMEOUT` | `--start-timeout` | `360` | seconds to wait for a starting bridge |
| `EDT_BRIDGE_AUTOSTART` | `--no-autostart` | on | `0` – never launch anything, act as a proxy only |
| `EDT_BRIDGE_WINDOW_WAIT` | – | `90` | seconds the `gui` command waits for the EDT window to appear: a large workspace loads for minutes, so a miss is not an error but a reason to run the command again |
| `EDT_BRIDGE_LANG` | – | the system locale | the language of the wrapper's help and messages (`ru` / `en`) |
| `EDT_BRIDGE_NO_PLUGINS` | – | off | `1` – do not discover [wrapper plugins](#wrapper-plugins): a run with the wrapper's own capabilities only |

**Read by the plugin inside EDT**

| Variable | Launch property | Default | What it sets |
|----------|-----------------|---------|--------------|
| `EDT_BRIDGE_PORT` | `-Dedt.bridge.port` | `8770` | the MCP server's port; the next free one is taken when it is busy |
| `EDT_BRIDGE_TOKEN` | `-Dedt.bridge.token` | from the preference page | the shared secret every write tool requires |
| `EDT_BRIDGE_ALLOW_EVALUATE` | – | off | `1` enables `edt_evaluate` – arbitrary BSL executed against a live infobase; the preference page carries the same switch |
| `EDT_BRIDGE_AGENT_IDLE_MINUTES` | `-Dedt.bridge.agent-idle-minutes` | `30` | after how many idle minutes a configurator agent stops itself; `off` keeps agents forever |

What is given at launch wins: environment variables and `-Dedt.bridge.*` properties take precedence
over EDT's preference page – which is how the wrapper drives a headless instance.

### Build from source

No Maven (quickest – pure local JDK + the EDT pool, no network):

```powershell
# Windows – defaults: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File scripts/build-nomaven.ps1
```

```bash
# macOS / Linux – --pool auto-detected from the installed 1C:EDT component pool
./scripts/build-nomaven.sh
```

Produces `build/io.github.keyfire.edtbridge_<version>.<timestamp>.jar`. Maven + Tycho
(`mvn -f pom.xml clean verify`, edit `edt-bridge.target` first) is available for CI.

Releases are cut from a locally built jar – CI cannot compile it (the 1C:EDT SDK bundles are
proprietary and cannot be fetched anonymously). The maintainer runs `scripts/build-nomaven.ps1 -Dist`,
commits the jar under `dist/`, tags `vX.Y.Z` and pushes the tag; `.github/workflows/release.yml`
attaches the jar + checksum. Verify an asset by rebuilding from the tagged source and comparing.

## Wrapper plugins

Not everything a team runs next to the bridge belongs in a public repository – reference
material under somebody's license, tools wired to an internal service. Those live in separate
packages installed into the wrapper's own environment, and the wrapper discovers them through
the `edt_bridge.tools` entry-point group – the tools they declare are listed next to the
bridge's tools and dispatched by the wrapper itself, so they answer even while no EDT is
running.

Installing one:

```bash
pipx inject edt-bridge-mcp <package>
```

### When pipx runs on uv (pipx >= 1.15)

A pipx backed by uv builds the venv WITHOUT pip, and three familiar moves stop working the
way they read:

- `--pip-args="--no-deps"` written as one token reaches uv pre-split and dies with uv's own
  usage screen. Pass the flag as a separate argument: `pipx inject edt-bridge-mcp <package>
  --pip-args "--no-deps"`.
- `<venv>/Scripts/python -m pip` answers "No module named pip". `pipx runpip edt-bridge-mcp`
  still works - pipx routes it into `uv pip` itself - and is the supported way to reach the
  environment.
- reinstalling a plugin WITHOUT `--no-deps` also reinstalls the core package and fails to
  replace `edt-bridge-mcp.exe` while a live MCP session holds it ("failed to persist ...
  Access denied"). The plugin itself usually lands before the failure - judge the outcome by
  `edt-bridge-mcp plugins`, not by the exit code.

The form that updates a plugin without touching the busy core:

```bash
python -m pipx runpip edt-bridge-mcp -- install --upgrade --no-deps <package>
```

(add `--index-url <your index>` when the plugin lives in a private registry).

`edt-bridge-mcp plugins` lists what is plugged in – packages, entry points and the tools they
add – and prints the loader's message when a plugin is broken. `EDT_BRIDGE_NO_PLUGINS=1` turns
the discovery off.

A plugin declares its tools in `pyproject.toml`:

```toml
[project.entry-points."edt_bridge.tools"]
package-name = "my_package.tools:tools"
```

The value is a `Tool` (from `edt_bridge_mcp.plugins`), a list of them, or a zero-argument
callable returning either. A `Tool` carries the MCP descriptor – `name`, `description`, the
JSON schema of the arguments – and the handler the wrapper calls; a str return becomes the
text result, any other JSON-serializable value is pretty-printed as JSON, and an exception
becomes the tool's error message. A failing entry point, a duplicated tool name or a name
that shadows the wrapper's own tools refuses loudly at discovery – a silently dropped plugin
would leave an agent without its tools and without an explanation.
