# edt-bridge – design (Phase 1, read-only)

> Historical Phase 1 design note. Phase 2 (write: attribute CRUD, rename-with-cascade,
> create-object) is now implemented – see the [README](../README.md) for the current tool set.

## Goal

Give tools and AI agents 1C:EDT's **live** semantic model for things a static parser cannot do:
real definition/reference resolution, **query validation** against project metadata, EDT's own
validation markers, metadata details, and symbol/type info.

## Why a plugin

EDT's semantic model (the BM model / AST, content assist, validation, the query language) lives
**inside the EDT JVM** and is reachable only from an Eclipse/OSGi plugin via the public
`com._1c.g5.v8.dt.*` APIs. An external process cannot reach it – so the bridge is a small Java/EDT
plugin that embeds an MCP server.

## Shape

- An **Eclipse/OSGi plugin** loaded by 1C:EDT (`org.eclipse.ui.startup`), running in EDT's JVM.
- It embeds a minimal **MCP server over HTTP**, bound to `127.0.0.1` (default port `8770`).
- Prerequisite: EDT running with the workspace open. If the model is not available, tools return a
  clear message rather than failing opaquely.

## Tool surface (read-only)

| Tool | Why it must be live |
|------|---------------------|
| `edt_validate_query` | Query syntax **and** semantics against project metadata – impossible statically. |
| `edt_project_errors` | EDT's own validation markers by severity. |
| `edt_find_references` | Real semantic references (type resolution, metadata + BSL), not text match. |
| `edt_metadata_details` | Object properties **and** structure (attributes / tabular sections / forms / ...) with types. |
| `edt_metadata_objects` | Enumerate top objects by type and name. |
| `edt_go_to_definition`, `edt_symbol_info` | *(planned)* semantic definition resolution and dynamic BSL typing. |

## Architecture

- All EDT access sits behind a single thin adapter, `EdtModelGateway`, that wraps
  `com._1c.g5.v8.dt.*`. An EDT API change touches only this one class.
- The live-model read path: `ServiceAccess.get(IBmModelManager)` → `getModel(IProject)` →
  `executeReadonlyTask` → `IBmTransaction` (`getTopObjectByFqn`, `getReferences(uri)`).
- Query validation reuses EDT's own QL services: the wired QL injector creates a transient query
  resource under a `platform:/resource/<project>/...` URI (no file on disk), then runs EDT's
  `IResourceValidator`. The query scope resolves the project's metadata from that URI.

## Transport & protocol

- MCP via plain **JSON-RPC 2.0 over HTTP** (`initialize` / `tools/list` / `tools/call`), served by
  the JDK's `com.sun.net.httpserver`. No SSE / extra SDK needed for the current clients.

## Security

- Bind `127.0.0.1` only – never `0.0.0.0`.
- Read-only: no write/exec tools in Phase 1; `validate_query` only parses/validates.
- Optional shared-secret token via env (`EDT_BRIDGE_TOKEN`) / system property; never logged.
- Development workspace only; no production data path.

## Build & stack

- Java: bundle **BREE / bytecode = 17** (the EDT runtime). The no-Maven build may use JDK 21 with
  `--release 17`.
- Target platform: the **local EDT p2 bundle pool** (`<your-home>/.p2/pool/plugins`) referenced as a
  PDE/Tycho **Directory** location – offline, no p2 URL.
- Two builds: `scripts/build-nomaven.ps1` (local JDK + pool) and Maven/Tycho (`pom.xml` + `edt-bridge.target`).

## Phases

- **Phase 1: read-only** – the tools above.
- **Phase 2 (now implemented):** write/refactor – attribute CRUD, rename-with-cascade, create-object
  (token-gated, dry-run by default). See the [README](../README.md).
- **Later phases (separate scope):** debug/forms/test runs. Each adds a non-inert surface and should
  be designed and reviewed on its own.

## Risks & mitigations

- **EDT internal API drift across versions** → the `EdtModelGateway` adapter contains it; re-test
  after EDT upgrades.
- **MCP-over-HTTP lifecycle** (EDT must be up) → graceful "model unavailable" responses; documented
  prerequisite.
- **Java maintenance in a mostly BSL/Python setting** → keep the plugin tiny; one adapter layer.
