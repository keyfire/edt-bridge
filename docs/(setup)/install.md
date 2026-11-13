---
title: "Installation"
description: "Requirements, manual install without the wrapper, and starting EDT with the repository scripts."
sidebar:
  label: "Install"
  order: 1
---

**To use the bridge**

- **1C:EDT** with your project open, or let `edt-bridge-mcp` start a headless EDT for you.
- A locally installed **1C:Enterprise platform** matching the project's version, for
  `edt_dump_external_object` (building `.epf` and `.erf`) and for `edt_update_infobase`. EDT drives
  that platform to compile the binary and to update the infobase.

## Manual install (without the wrapper)

The pipx wrapper delivers the jar and starts EDT for you. To run the plugin yourself instead:

1. Get the jar from the [Releases page](https://github.com/keyfire/edt-bridge/releases), which also
   includes `SHA256SUMS.txt`, or [build it from source](/build).
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

## Settings, plugins and build

The other parts of the setup have pages of their own:

- <a id="environment-variables"></a><a id="write-scope"></a>[Settings](/settings) – the preference page in EDT, the `EDT_BRIDGE_*` environment variables and the write scope.
- <a id="a-ready-workspace"></a>[Headless EDT](/headless) – auto-start, waiting for the project models in a CI job and moving a ready workspace.
- <a id="wrapper-plugins"></a><a id="when-pipx-runs-on-uv-pipx--115"></a>[Wrapper plugins](/plugins) – installing, updating and writing plugins.
- <a id="build-from-source"></a>[Build from source](/build) – the JDK and the bundle pool the build needs, the build scripts and releases.
