# EDT-Bridge – design (Phase 1, read-only)

> A design note from Phase 1, kept for the record. Phase 2 – writes: attribute CRUD,
> rename-with-cascade, create-object – is implemented now. The [README](../README.md) has the
> current tool set.

## Goal

Give tools and AI agents 1C:EDT's **live** semantic model for the things a static parser cannot do:
resolving definitions and references for real, **validating a query** against project metadata,
reading EDT's own validation markers, metadata details, and symbol and type info.

## Why a plugin

EDT's semantic model – the BM model and AST, content assist, validation, the query language – lives
**inside the EDT JVM**. The only way in is an Eclipse/OSGi plugin using the public
`com._1c.g5.v8.dt.*` APIs, and an external process cannot reach it at all. So the bridge is a small
Java/EDT plugin with an MCP server embedded in it.

## Shape

- An **Eclipse/OSGi plugin** loaded by 1C:EDT through `org.eclipse.ui.startup`, running in EDT's JVM.
- It embeds a minimal **MCP server over HTTP**, bound to `127.0.0.1`, on port `8770` by default.
- It needs EDT running with the workspace open. When the model is not available, the tools say so
  plainly instead of failing in some opaque way.

## Tool surface (read-only)

| Tool | Why it must be live |
|------|---------------------|
| `edt_validate_query` | Query syntax **and** semantics against project metadata, which is impossible statically. |
| `edt_project_errors` | EDT's own validation markers by severity. |
| `edt_find_references` | Real semantic references with type resolution, across metadata and BSL, not a text match. |
| `edt_metadata_details` | Object properties **and** structure – attributes, tabular sections, forms and the rest – with types. |
| `edt_metadata_objects` | Enumerate top objects by type and name. |
| `edt_go_to_definition`, `edt_symbol_info` | *(planned)* semantic definition resolution and dynamic BSL typing. |

## Architecture

- All EDT access sits behind one thin adapter, `EdtModelGateway`, wrapping `com._1c.g5.v8.dt.*`. An
  EDT API change touches that class and nothing else.
- The live-model read path is `ServiceAccess.get(IBmModelManager)` → `getModel(IProject)` →
  `executeReadonlyTask` → `IBmTransaction` (`getTopObjectByFqn`, `getReferences(uri)`).
- Query validation reuses EDT's own QL services. The wired QL injector creates a transient query
  resource under a `platform:/resource/<project>/...` URI, with no file on disk, and then runs EDT's
  `IResourceValidator`. The query scope resolves the project's metadata from that URI.

## Transport and protocol

- MCP travels as plain **JSON-RPC 2.0 over HTTP** – `initialize`, `tools/list`, `tools/call` –
  served by the JDK's `com.sun.net.httpserver`. The current clients need no SSE and no extra SDK.

## Security

- Bind `127.0.0.1` only, never `0.0.0.0`.
- Read-only. Phase 1 has no write or exec tools, and `validate_query` only parses and validates.
- An optional shared-secret token comes from the environment (`EDT_BRIDGE_TOKEN`) or a system
  property, and is never logged.
- Development workspaces only. There is no path to production data.

## Build and stack

- Java: the bundle's BREE and bytecode are **17**, so one jar loads in every supported EDT. The
  compiler still has to be new enough to read EDT's own bundles, which are Java 25 class files as of
  EDT 2026.2, so the no-Maven build resolves the level from the pool and compiles with a matching
  JDK and `--release 17`.
- Target platform: the **local EDT p2 bundle pool** (`<your-home>/.p2/pool/plugins`), referenced as a
  PDE/Tycho **Directory** location. Offline, with no p2 URL.
- Two builds: `scripts/build-nomaven.ps1` (local JDK plus the pool) and Maven/Tycho (`pom.xml` plus
  `edt-bridge.target`).

## Phases

- **Phase 1: read-only** – the tools above.
- **Phase 2, implemented now:** write and refactor – attribute CRUD, rename-with-cascade,
  create-object. Token-gated, and returning a plan by default. See the [README](../README.md).
- **Later phases, a separate scope:** debug, forms, test runs. Each one adds a surface that acts on
  something, so each should be designed and reviewed on its own.

## Risks and what answers them

- **EDT's internal API drifts between versions.** The `EdtModelGateway` adapter contains the drift;
  re-test after an EDT upgrade.
- **The MCP-over-HTTP lifecycle needs EDT to be up.** The answer is a graceful "model unavailable"
  response and a documented prerequisite.
- **Java maintenance in a mostly BSL and Python setting.** Keep the plugin tiny, with one adapter
  layer.
