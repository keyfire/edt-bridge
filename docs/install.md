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

- A **JDK 17+** (the bundle targets Java 17, the EDT runtime).
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
