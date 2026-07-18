# edt-bridge

A small **1C:EDT plugin** that exposes EDT's **live semantic model** to AI agents and other
tools over the **Model Context Protocol (MCP)**.

Static parsers read source files; edt-bridge instead asks the running IDE. It answers things
that need the *live* model: EDT's own validation problems, real metadata structure and types,
semantic cross-references, **query validation against the project's actual metadata**, and the
**platform Syntax Helper** bundled with EDT – plus write tools that create, refactor, build and
deliver, all through EDT's own engine.

> Read **and** write. Localhost only; writes are token-gated and dry-run by default. The plugin
> runs inside EDT, so an EDT (GUI or headless) must be up with your project.

**English** · [Русский](README.ru.md)

Development notes and updates (in Russian): the [1C × AI: engineering workshop](https://t.me/ceh_1c_ai) Telegram channel.

![How the bridge is wired](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/architecture.png)

## Install (recommended: pipx)

One command sets up everything – the client wrapper AND the plugin. The
[**edt-bridge-mcp**](python/README.md) wrapper is a stdio MCP server that your client talks to; it
forwards to a running EDT, **auto-starts a headless EDT** when none is open, and **delivers the
plugin jar** into EDT's `dropins/` when it is missing. You do not copy any jar by hand.

```bash
pipx install edt-bridge-mcp
```

Then register it with your MCP client (Claude Code shown; `--workspace` is the EDT workspace to
serve when auto-starting headless):

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

That is the whole setup. Wrapper flags, the write-tools token and `self-update` are documented in
[python/README.md](python/README.md). Prefer to run the plugin yourself, without the wrapper? See
[Manual install](#manual-install-without-the-wrapper) below.

<details>
<summary>Don't have <code>pipx</code>?</summary>

`pipx` installs Python CLI apps into isolated environments. Install it once:

```bash
python -m pip install --user pipx
python -m pipx ensurepath      # then reopen the terminal
```

macOS: `brew install pipx && pipx ensurepath`. More: <https://pipx.pypa.io>.
</details>

## Tools

Tools are `edt_*` (snake_case); parameters are camelCase (`projectName`, `fqn`, `queryText`);
Cyrillic FQNs are supported (`Справочник.Контрагенты`). The `edt_` prefix is deliberate: an MCP
host presents every server's tools to the agent as one flat list, so a name must carry its context
itself – `edt_rename` stays unambiguous where a bare `rename` is dangerously generic.

### Read

| Tool | What it returns |
|------|-----------------|
| `edt_projects` | Open workspace projects – name, disk location, natures, and whether each is a 1C:EDT project (discover what is addressable). |
| `edt_project_errors` | EDT validation problems (errors/warnings) for a project: message, severity, resource, line. |
| `edt_metadata_details` | A metadata object's core properties **and structure** – attributes, tabular sections, forms, commands, templates, dimensions, resources, enum values – with each attribute's value type. |
| `edt_metadata_objects` | Top-level metadata objects, optionally filtered by type (`Catalog`, `Document`, ...) and a name substring. |
| `edt_find_references` | Inbound references to a metadata object, from EDT's cross-reference index. With `method` given: the BSL call sites of `CommonModule.X.method` (module + line + call text). |
| `edt_outgoing_calls` | The reverse: methods CALLED BY a module / method / form (one level out), aggregated `qualifier.method` with call-site counts and an ExtAPI-layer flag. |
| `edt_module_text` | BSL source of a module (or one method) + the module's procedure/function list with signatures, by FQN or modulePath. |
| `edt_validate_query` | Validates a 1C query against the project's live metadata: syntax **and** semantics (unknown tables/fields, type errors), with positions. |
| `edt_go_to_definition` | Resolve a BSL symbol's definition at a position: the target's kind, name, owning object and location. |
| `edt_symbol_info` | Type/symbol info at a position in a BSL module: the element under the cursor and the computed value type(s) of the expression. |
| `edt_form_structure` | A managed form's items tree (fields/groups/tables/buttons/decorations) with data bindings, static visible/enabled/readOnly, per-item event handlers, input-field props, button→command, and the form's conditional appearance; plus its attributes, commands, parameters and handlers. |
| `edt_form_render` | Renders a managed form to a PNG via EDT's native offscreen renderer; chooses the interface variant (Taxi / 8.5) and theme. |
| `edt_picture_export` | A CommonPicture's content from its Picture.zip: the variant list + a recommended pick, and a chosen variant's bytes as base64. |
| `edt_outgoing_structures` | **Best-effort companion to `edt_outgoing_calls`:** the top-level keys of the `Структура` passed to each qualified outgoing call. Optional `qualifier` prefix scopes to one layer. |
| `edt_infobases` | EDT's registered infobases (name, uuid, connection string) and the open projects' infobase associations – discovery for `edt_update_infobase`. |
| `edt_platform_help` | The 1C:Enterprise **platform Syntax Helper** bundled with EDT (real API reference – objects, methods, properties, events, Ru+En): search by name, or read a page as text. Consult the actual API instead of guessing signatures. |

### Write

Write tools mutate the model through EDT's own engine (not text edits). All are **token-gated**
and **dry-run by default** (`apply=false` returns a plan and changes nothing); `apply=true`
performs the change and serializes the `.mdo`.

| Write tool | What it does |
|------------|--------------|
| `edt_add_attribute` | Add an attribute to a metadata object (type / klass / synonym / comment), validated. |
| `edt_add_method` | Add a procedure/function to a module's BSL – model-guided insert; refuses any result that would not re-parse cleanly. |
| `edt_delete_method` | Delete a procedure/function from a module's BSL – model-guided cut plus adjacent doc comments; dry-run returns the exact removed text (`force` required – deleting code is destructive). |
| `edt_modify_attribute` | Change an existing attribute's type, synonym or comment. |
| `edt_remove_attribute` | Remove an attribute (reference-checked; refuses if referenced unless forced). |
| `edt_rename` | Rename an object or member and **cascade every reference in metadata AND BSL** via EDT's native refactoring engine (`force` required – a rename is breaking). |
| `edt_create_object` | Create a new top object (Catalog/Document/Enum/InformationRegister/...) via EDT's factory + per-type initializer, registered in the Configuration. |
| `edt_create_extension` | Create a new configuration-**extension project** against a base project via `IExtensionProjectManager`, stamping name prefix and purpose (Customization·AddOn·Patch). A fresh detached extension Configuration is built and attached. |
| `edt_create_external_object` | Create a new **external data processor project** (optionally linked to a base project) – the start of the "processor → .epf" cycle. |
| `edt_dump_external_object` | Compile an external data processor / report into a binary **`.epf`/`.erf`** via EDT's own dumper (**needs a locally installed 1C:Enterprise platform** matching the project version). |
| `edt_update_infobase` | Update an infobase's configuration **from an EDT project** (configuration or extension) via EDT's synchronization engine; db-structure changes auto-confirmed, a conflict aborts. |
| `edt_delete_object` | Delete an object or member and **cascade removal of every reference in metadata AND BSL**; removes the `.mdo` and updates the Configuration (`force` required). |

Together the create / develop / build / deliver tools close the full cycle without leaving MCP:

![Full delivery cycle over MCP](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/delivery.png)

### Debug

Attach to a **running** infobase's debug server (dbgs) and drive execution. Use a test stand, not
production. All are token-gated; `edt_evaluate` is gated hardest.

| Debug tool | What it does |
|------------|--------------|
| `edt_debug_attach` | Attach a debug session to a running infobase's debug server; returns a `sessionId` for the other debug tools. |
| `edt_debug_detach` | Detach (terminate) a debug session and free the infobase. |
| `edt_debug_inspect` | List a session's threads and, for **suspended** ones, their BSL stack frames + the top frame's variables. Read-only. |
| `edt_debug_control` | Control execution: `suspend`/`resume`, or `stepOver`/`stepInto`/`stepReturn` a suspended thread. |
| `edt_evaluate` | Evaluate an **arbitrary BSL expression** in a suspended frame – code execution against the live infobase. Needs the token **and** per-call `allowCodeExecution=true` **and** the server switch `EDT_BRIDGE_ALLOW_EVALUATE=1` (off by default). |

## Requirements

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
   only one edt-bridge jar there – two make Equinox load an arbitrary one.
3. **Restart EDT.** The plugin starts the MCP server on `http://127.0.0.1:8770/mcp` (or the next
   free port if 8770 is busy).

To run EDT **headless** (no GUI): `scripts/run-headless.ps1 -Workspace <ws>` (Windows) or
`scripts/run-headless.sh --workspace <ws>` (macOS / Linux); `scripts/toggle-headless.ps1` starts/stops it in one
action. A running GUI EDT is never touched.

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

## Dashboard

Open `http://127.0.0.1:8770/` in a browser for a built-in dashboard: server status, the open EDT
projects, and an interactive runner for every tool. Light/dark theme and an EN/RU language toggle.

## Reporting issues

edt-bridge is young and its tools may have bugs or rough edges. If something behaves unexpectedly,
fails, or you have a feature request, please open a **[GitHub issue](https://github.com/keyfire/edt-bridge/issues)** –
include what you did, what you expected, and the tool's response (or the relevant EDT log line).

## Security

- Binds **`127.0.0.1` only** – never a public interface.
- **Writes are gated**: every write tool requires a configured token, defaults to a dry-run, and
  operates only on your local EDT model; `edt_rename`, `edt_delete_object` and `edt_delete_method`
  additionally need an explicit `force`.
- Optional **shared-secret token** – set `EDT_BRIDGE_TOKEN` (or `-Dedt.bridge.token=`) and send
  `Authorization: Bearer <token>` (or `X-Edt-Bridge-Token: <token>`). Any local process can reach
  the port, so set a token on shared machines.
- Port: `EDT_BRIDGE_PORT` / `-Dedt.bridge.port=` (default 8770; the next free port is used if busy).
- Found a security problem? Report it **privately** – see [SECURITY.md](SECURITY.md), not a public issue.

## Contributing

Bug reports and pull requests are welcome – see [CONTRIBUTING.md](CONTRIBUTING.md) (build,
conventions, how to verify a change) and the [Code of Conduct](CODE_OF_CONDUCT.md). Notable changes
are tracked in the [CHANGELOG](CHANGELOG.md). edt-bridge is an independent, clean-room implementation
([ORIGIN.md](ORIGIN.md)).

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) and [ORIGIN.md](ORIGIN.md).
