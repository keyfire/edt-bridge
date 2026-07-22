---
title: "Tools"
description: "Every edt_* tool the bridge exposes: reading the live model, writing through EDT's own engine, and debugging a running infobase."
sidebar:
  label: Tools
  order: 3
---

Tools are `edt_*` (snake_case); parameters are camelCase (`projectName`, `fqn`, `queryText`);
Object names are Cyrillic as in the configuration, the type prefix is English (`Catalog.Контрагенты`, `Document.ЗаказКлиента`). The `edt_` prefix is deliberate: an MCP
host presents every server's tools to the agent as one flat list, so a name must carry its context
itself – `edt_rename` stays unambiguous where a bare `rename` is dangerously generic.

Together they close the full cycle without leaving MCP – create, develop, build, deliver, debug:

![Full delivery cycle over MCP](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/delivery.png)

### Read

| Tool | What it returns |
|------|-----------------|
| `edt_projects` · `edt_project_errors` | The open workspace projects – name, disk location, natures, and whether each is a 1C:EDT project – and a project's EDT validation problems (message, severity, resource, line). Start here to discover what is addressable. |
| `edt_check_info` | What a validation check MEANS: its description, the non-compliant and compliant examples, and the **links to the 1C development standards** it enforces – in English or Russian. The companion of `edt_project_errors`, which reports which check fired: that answers *what*, this answers *why*. Ask by check id, or paste the problem message – a check whose id is a short code is found by its title. |
| `edt_metadata_objects` · `edt_metadata_details` | Top-level metadata objects, optionally filtered by type (`Catalog`, `Document`, ...) and a name substring; then one object's core properties **and structure** – attributes, tabular sections, forms, commands, templates, dimensions, resources, enum values – with each attribute's value type. |
| `edt_find_references` · `edt_outgoing_calls` · `edt_outgoing_structures` | Which way the calls go. Inbound references to a metadata object from EDT's cross-reference index (with `method`: the BSL call sites of `CommonModule.X.method`); the reverse, methods CALLED BY a module / method / form one level out, with call-site counts and an ExtAPI-layer flag; and, best-effort, the top-level keys of the `Структура` passed to each qualified outgoing call. |
| `edt_module_text` · `edt_go_to_definition` · `edt_symbol_info` | Reading BSL: a module's source (or one method) plus its procedure/function list with signatures, by FQN or modulePath; a symbol's definition at a position (kind, name, owning object, location); and the type info at a position – the element under the cursor and the computed value type(s) of the expression. |
| `edt_search_modules` | Full-text search across a project's BSL modules – substring or regular expression, optional path filter. Reads through Eclipse's file buffers, so a module open in an editor is searched as it currently stands, unsaved edits included. Where `edt_find_references` answers "who calls this method", this answers "where does this text appear". |
| `edt_validate_query` | Validates a 1C query against the project's live metadata: syntax **and** semantics (unknown tables/fields, type errors), with positions. |
| `edt_form_structure` · `edt_form_render` · `edt_picture_export` | Forms and images: a managed form's items tree (fields/groups/tables/buttons/decorations) with data bindings, static visible/enabled/readOnly, per-item event handlers, input-field props, button→command and the form's conditional appearance, plus its attributes, commands, parameters and handlers; the same form rendered to a PNG by EDT's native offscreen renderer (interface variant and theme selectable); and a CommonPicture's content from its Picture.zip. |
| `edt_platform_help` | The 1C:Enterprise **platform Syntax Helper** bundled with EDT (real API reference – objects, methods, properties, events, Ru+En): search by name, or read a page as text. Consult the actual API instead of guessing signatures. |

### Write

Write tools mutate the model through EDT's own engine (not text edits). All are **token-gated**
and **dry-run by default** (`apply=false` returns a plan and changes nothing); `apply=true`
performs the change and serializes the `.mdo`.

| Write tool | What it does |
|------------|--------------|
| `edt_create_object` · `edt_delete_object` | Create a new top object (Catalog/Document/Enum/InformationRegister/...) via EDT's factory + per-type initializer, registered in the Configuration – or delete one, **cascading the removal of every reference in metadata AND BSL** (`force` required). |
| `edt_add_attribute` · `edt_modify_attribute` · `edt_remove_attribute` | Add an attribute to a metadata object (type / klass / synonym / comment, validated), change an existing one's type, synonym or comment, or remove it – removal is reference-checked and refuses while references remain unless forced. |
| `edt_rename` | Rename an object or member and **cascade every reference in metadata AND BSL** via EDT's native refactoring engine (`force` required – a rename is breaking). |
| `edt_add_method` · `edt_delete_method` | Add or delete a procedure/function in a module's BSL, both model-guided. The insert refuses any result that would not re-parse cleanly; the cut takes adjacent doc comments with it and its dry-run returns the exact removed text (`force` required – deleting code is destructive). Both address a module by FQN, including `HTTPService.X` / `WebService.X`. |
| `edt_add_route` | Add a route – a URL template plus one HTTP method – to an `HTTPService`, the write tool for HTTP service routes alongside the attribute and method writers. Generates the `uuid` of both the url template and its method (hand-writing which is exactly what the bridge exists to avoid), resolves the `httpMethod` enum, and with `createHandler` splices a `Функция <handler>(Запрос)` stub into the service module. |
| `edt_add_form` | Add a managed form to a metadata object through EDT's own form generator – the engine behind the "New form" wizard – so the form, its items and its module are generated rather than hand-written as XML. |
| `edt_add_form_attribute` · `edt_modify_form_attribute` · `edt_remove_form_attribute` | Add, change and remove a form attribute – or, with `columnOf`, a column of a value-table attribute. Ids come from EDT's form identifier service; besides the metadata type grammar these accept platform types a form may hold (`ТаблицаЗначений`, `СписокЗначений`, ...). Removal lists the items bound to the attribute and needs `force`. |
| `edt_add_form_command` · `edt_modify_form_command` · `edt_remove_form_command` | Add, change and remove a form command. Adding can also write the handler procedure's stub into the form module, creating that module when the form has none. Removal lists the buttons wired to the command and needs `force`. |
| `edt_add_form_item` · `edt_modify_form_item` · `edt_remove_form_item` | Add, change and remove a form's visual items – field, table, button, group, decoration – through EDT's own `IFormItemManagementService`, the service the form editor calls. A table bound to a value-table attribute gets its columns auto-filled. Removal takes everything nested inside and needs `force`. |
| `edt_adopt_object` | Adopt an object of the base configuration into an extension project via EDT's own `IModelObjectAdopter` – the step that must happen before an extension can intercept anything on that object, and the one that completes `edt_create_extension`. |
| `edt_create_extension` · `edt_create_external_object` | Start a project. A configuration **extension** against a base project via `IExtensionProjectManager`, its root Configuration being the base configuration *adopted* – as the wizard does it, which is what makes the project loadable into an infobase – plus name prefix, purpose (Customization·AddOn·Patch) and synonym; or an **external data processor** project, the start of the "processor → .epf" cycle. |
| `edt_clean_project` · `edt_delete_project` | Finish with a project. Discard its build results so validation runs again (EDT's "Clean" dialog, programmatically – reports the problem count before and after, waiting until it stops changing, because a stale marker outliving its cause is worse than no marker), or remove it from the workspace through the Eclipse workspace so no ghost project is left behind (`force` required – deleting a project is irreversible). |
| `edt_build_extension` · `edt_dump_external_object` | Build the binaries: a **`.cfe`** from an extension project, or an **`.epf`/`.erf`** from an external data processor/report. Both can bypass EDT's platform resolver when it serves no thick client – exporting designer XML in-process, then assembling with a full on-disk 1C install in a throwaway temp infobase that is deleted afterwards. `logPath` keeps the platform's build log next to the artefact. |

### Infobases, the cluster and the platform

Everything that talks to a RUNNING infobase rather than to the model in EDT. Four places to reach, and
they are not interchangeable - the **Through** column says which one a tool uses:

- **EDT's own synchronization** – what the IDE uses. It opens its own infobase connection and has no way
  to take credentials from outside the UI, so it stops at an infobase that authenticates its users.
- **`ibcmd`** – straight at the database, by file path or DBMS coordinates, so a clustered infobase needs
  no cluster access. Its `extension` mode has no 1C credentials at all.
- **the configurator agent** – a designer started with `/AgentMode`, taking commands over SSH and
  authenticating AS THE INFOBASE USER. It reaches what the other two cannot, and the bridge keeps one
  running per infobase because starting one is slow and holding one is cheap.
- **`rac`** – the cluster itself, which is where sessions live. Neither the agent nor `ibcmd` sees them.

Tools that change an infobase are token-gated and dry-run by default, exactly like the write tools above.

| Tool | Through | What it does |
|------|---------|--------------|
| `edt_infobases` · `edt_platform_installations` | EDT | What the platform side has to work with: EDT's registered infobases (name, uuid, connection string) with the open projects' associations, and the 1C:Enterprise installations EDT resolves from when dumping an `.epf`/`.erf` or creating an infobase – each resolved to a concrete install carrying a thick client, plus the full installs found on disk. |
| `edt_designer_agent` | agent | Lifecycle of the **configurator agents** the bridge drives: list, start, stop. An agent is a configurator in `/AgentMode` holding an open infobase session, authenticating **as the infobase user** – which is how the bridge reaches an infobase the other transports cannot. Started on demand and kept between calls; stopping one frees the session it holds on the server. |
| `edt_infobase_config_state` | agent | Is the infobase's **database** configuration – the code sessions actually execute – up to date, or is an update still pending? The platform itself answers: the update is started and its confirmation **refused**, so nothing is applied and a pending update comes back as the full list of structure changes that are waiting. Driven through a configurator agent, so a server infobase that authenticates its users is reachable. |
| `edt_update_database_config` | agent | **Applies** the database configuration – the step that makes running sessions execute the configuration the infobase holds. Loading a project into an infobase does not do this, and until it happens every session keeps running the previous code (a freshly added HTTP route answering 404 is what that looks like). Dry-run by default; `sessionTermination=force` ends the sessions holding the base when an exclusive lock is needed. |
| `edt_update_infobase` | EDT · agent | Update an infobase's configuration **from an EDT project**. Through EDT's synchronization engine by default (db-structure changes auto-confirmed, a conflict aborts), which cannot authenticate to an infobase that has users; with `transport=agent` the project is exported to designer XML and loaded through the agent instead – the only route into a server infobase with users – and the database configuration is applied afterwards. |
| `edt_create_infobase` · `edt_register_platform` | EDT · disk install | Create an **empty file infobase** and register it in EDT's list, falling back to a full install found on disk when EDT resolves none for the version; or register a full install into EDT so its own engine can use it. |
| `edt_extension_properties` | agent · ibcmd | Read and set how an extension is REGISTERED in an infobase – safe mode, protection from dangerous actions, active, scope. Neither building a `.cfe` nor updating from EDT decides these, and a freshly registered extension gets safe mode and dangerous-action protection **on**; an extension that changes methods of the base configuration cannot run under them. Pass the extension project and the result says whether that is the case. Addressed by an EDT-registered name it goes through the agent, which reaches a server infobase with users; by explicit DBMS coordinates it goes through `ibcmd`, which cannot. |
| `edt_delete_extension` | agent | Removes an extension from an infobase – the step that closes the lifecycle (create · load · configure · **delete**). The dry-run reads its current properties first, so a wrong name is answered plainly. Needs **force** on top of apply: an extension's configuration lives in the infobase, and nothing here puts it back. |
| `edt_infobase_sessions` | rac | The 1C **cluster's sessions** through `rac`: list them (for one infobase or one application) and end them. Neither the agent nor `ibcmd` can – sessions live in the cluster manager. Reach for it when an infobase refuses to be configured: a designer session that was killed rather than closed still holds the **configuration lock**, and shows up here as a `Designer` session. Terminating is dry-run by default and needs force. |
| `edt_infobase_maintenance` | rac | A **maintenance window** around a database update: `begin` raises `scheduled-jobs-deny` (optionally `sessions-deny` with a permission code), watches the sessions **drain by themselves** and reports "clear to update"; `end` lowers the flags; `status` just reports. The point: on a lively base BackgroundJob sessions respawn every minute, so terminating them is useless – deny first, and nothing has to be killed. Needs the infobase administrator; dry-run by default. |
| `edt_infobase_dump` | ibcmd | Dump an infobase to a `.dt` through `ibcmd` – the backup to take **before** applying a configuration to the database, which the bridge previously had no way to make. Addresses the infobase by file path or DBMS coordinates, refuses to overwrite an existing file, and is dry-run by default. Nothing in the infobase changes, but the dump reads all of its data, so it is token-gated. |

### Debug

Attach to a **running** infobase's debug server (dbgs) and drive execution. Use a test stand, not
production. All are token-gated; `edt_evaluate` is gated hardest.

| Debug tool | What it does |
|------------|--------------|
| `edt_debug_attach` · `edt_debug_detach` | Attach a debug session to a running infobase's debug server (returns a `sessionId` for the other debug tools), and detach – terminating the session and freeing the infobase. |
| `edt_debug_inspect` · `edt_debug_control` | List a session's threads and, for **suspended** ones, their BSL stack frames + the top frame's variables (read-only); then control execution – `suspend`/`resume`, or `stepOver`/`stepInto`/`stepReturn` a suspended thread. |
| `edt_evaluate` | Evaluate an **arbitrary BSL expression** in a suspended frame – code execution against the live infobase. Needs the token **and** per-call `allowCodeExecution=true` **and** the server switch `EDT_BRIDGE_ALLOW_EVALUATE=1` (off by default). |
