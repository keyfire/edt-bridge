# Contributing to edt-bridge

**English** · [Русский](CONTRIBUTING.ru.md)

Thanks for your interest. edt-bridge is a 1C:EDT plugin plus a small Python wrapper; this guide
covers building it, the conventions, and how to propose changes.

## Ground rules

- **Clean-room.** edt-bridge is an independent, from-scratch implementation (see [ORIGIN.md](ORIGIN.md)).
  Contribute only code you wrote, built on the open MCP spec and the public Eclipse / 1C:EDT plugin
  APIs (`org.eclipse.*`, `com._1c.g5.v8.dt.*`). Do not paste decompiled or otherwise proprietary
  source from EDT or any other tool.
- **Scope.** The plugin exposes EDT's *live* model over MCP. A new tool should need something a
  static parser cannot answer (the running IDE's validation, real types, semantic references,
  native refactoring / build). If it does not, a standalone tool is a better home.
- **License.** By contributing you agree your work is licensed under [Apache-2.0](LICENSE)
  (inbound = outbound).

## Building

You need a **JDK 17+** and the local **1C:EDT bundle pool** – the plugin compiles against EDT's own
bundles, which are proprietary and provided by your local EDT install (so there is no network build).

```powershell
# Windows – defaults: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File scripts/build-nomaven.ps1
```

```bash
# macOS / Linux – --pool auto-detected from the installed 1C:EDT component pool
./scripts/build-nomaven.sh
```

The jar lands in `build/`. Maven + Tycho (`mvn -f pom.xml clean verify`) is available for a full
build – edit `edt-bridge.target` first. More detail: [README](README.md#build-from-source).

## Running your build

Copy the built jar into EDT's `dropins/` (keep exactly one edt-bridge jar there – two make Equinox
load an arbitrary one) and restart EDT, or let the wrapper deliver it. The MCP server comes up on
`http://127.0.0.1:8770/mcp`; the dashboard at `http://127.0.0.1:8770/` has a runner for every tool.
See [Manual install](README.md#manual-install-without-the-wrapper).

## Verifying a change

There is no offline unit suite for the Java plugin – it is verified against a **live EDT**: build,
deploy to `dropins/`, restart EDT, and exercise the affected tools (via the dashboard or an MCP
client). For write tools, check the dry-run plan (`apply=false`) before running `apply=true`. The
Python wrapper's tests live under [`python/`](python/).

## Code style

- **Java 17**, standard Eclipse / OSGi conventions. Keep all IDE-model access inside the gateway
  classes under `...edtbridge.edt` – an EDT API change should be contained there.
- **English** for all code, comments and identifiers.
- One tool per class under `...edtbridge.tools`, registered in `McpServer`.
- Tool names are `edt_*` (snake_case); parameters are camelCase. Keep names self-describing – an
  MCP host shows every server's tools as one flat list (see the README).

## Commits and pull requests

- Small, focused commits with a clear subject line.
- Open a pull request against `main` and fill in the template (what changed, how you verified it).
- Releases are cut by the maintainer from a locally built jar (CI cannot compile the plugin), so a
  merged change ships in the next tagged release.

## Reporting bugs and requesting features

Open a [GitHub issue](https://github.com/keyfire/edt-bridge/issues) using the templates. For
security-sensitive reports, follow [SECURITY.md](SECURITY.md) instead of a public issue.
