---
title: "Tools"
description: "Every edt_* tool the bridge exposes: reading the live model, writing through EDT's own engine, and debugging a running infobase."
sidebar:
  label: Tools
  order: 3
---

Tool names are `edt_*` in snake_case, parameters are camelCase: `projectName`, `fqn`, `queryText`.
Object names are Cyrillic, as in the configuration; the type prefix is English:
`Catalog.Контрагенты`, `Document.ЗаказКлиента`. An MCP host shows the agent one flat list of every
server's tools, so each name has to carry its own context. Hence the `edt_` prefix: `edt_rename`
says what it renames, where a bare `rename` does not.

A parameter the tool does not declare refuses the whole call. The refusal names the near miss
(`deleteContents` → `deleteContent`) and lists what the tool does take. Skipping an unknown
parameter in silence would report success for a call that did something else.

Together the tools close the whole cycle without leaving MCP: create, develop, build, deliver, debug.

![Full delivery cycle over MCP](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/delivery.svg)

### Read

| Tool | What it returns |
|------|-----------------|
| `edt_projects` · `edt_project_errors` | The open workspace projects: name, disk location, natures, and whether each one is a 1C:EDT project. Then a project's EDT validation problems with message, severity, resource and line. Narrow them by `fqn`, `modulePath` or `severity`, count them with `countOnly`, or get one text line per problem with `brief`. Start here to find out what you can address at all. What comes back is EDT's own grading, and EDT grades a call to a method another module does not have as a `WARNING` (`SU239`, "Property (method) of object is not defined"). Only an undefined bare call is an error, so a check of generated code has to read the warnings too. `modulePath` reaches the Eclipse markers alone. An EDT check marker is addressed by object, so narrow by `fqn` to see both kinds. An `fqn` that names a form (`...Form.<Name>`) is matched by the form's name as well, otherwise the object's other forms would answer too. A marker is a snapshot, so every problem is judged against its file: `stale` means the marker predates the file's last change on disk, `unsynchronized` means the workspace has not read that change yet. The summary counts both (`staleCount`, `unsynchronized`) and a `hint` says what to do about them. `refresh=true` re-reads the narrowed scope from disk and runs an incremental build before the markers are read. That takes seconds for one module, where `edt_clean_project` rebuilds everything. |
| `edt_check_info` | What a validation check actually means: its description, the non-compliant and compliant examples, and the links to the 1C development standards behind it, in English or Russian. It goes with `edt_project_errors`, which names the check that fired. That one answers *what*, this one answers *why*. Ask by check id, or paste the problem message. A check whose id is a short code is found by its title. |
| `edt_metadata_objects` · `edt_metadata_details` | Top-level metadata objects, optionally filtered by type (`Catalog`, `Document`, ...) and by a name substring. Then one object's core properties and its structure: attributes, tabular sections, forms, commands, templates, dimensions, resources, enum values, each attribute with its value type. |
| `edt_find_references` · `edt_outgoing_calls` · `edt_outgoing_structures` | Which way the calls go. Inbound references to a metadata object come from EDT's cross-reference index; with `method` you get the BSL call sites of `CommonModule.X.method`. The reverse direction lists the methods a module, a method or a form calls one level out, with call-site counts and a flag for the ExtAPI layer. The third tool reads, as far as it can, the top-level keys of the `Структура` passed to each qualified outgoing call. |
| `edt_module_text` · `edt_go_to_definition` · `edt_symbol_info` | Reading BSL. A module's source, or one method of it, with the list of procedures and functions and their signatures, addressed by FQN or by modulePath. A symbol's definition at a position: kind, name, owning object, location. And the type at a position: the element under the cursor and the computed value types of the expression. |
| `edt_search_modules` | Full-text search across a project's BSL modules, by substring or regular expression, with an optional path filter. It reads through Eclipse's file buffers, so a module open in an editor is searched as it stands right now, unsaved edits included. `edt_find_references` answers "who calls this method"; this one answers "where does this text appear". |
| `edt_validate_query` | Checks a 1C query against the project's live metadata: syntax and semantics, so unknown tables and fields and type errors come back with positions. Takes the query text, or a module address (`modulePath`, optionally one `method`). Given a module, the bridge pulls the `\|`-framed query literals out of the live module itself and checks each one. The batch is followed as a whole too, which EDT does not do. A query that reads a temporary table no earlier query puts there (`ПОМЕСТИТЬ`), or one an earlier query has dropped, makes the text invalid. For a module literal that is an `INFO` note instead, because the table may come from a shared temporary table manager or from another literal. |
| `edt_form_structure` · `edt_form_render` · `edt_picture_export` | Forms and images. The items tree of a managed form – fields, groups, tables, buttons, decorations – with data bindings, static visible/enabled/readOnly, per-item event handlers, input-field properties, the button-to-command wiring and the form's conditional appearance, plus its attributes, commands, parameters and handlers. The same form rendered to a PNG by EDT's own offscreen renderer, with a choice of interface variant and theme. And the content of a CommonPicture out of its Picture.zip. |
| `edt_platform_help` | The 1C:Enterprise platform Syntax Helper that ships with EDT: the real API reference of objects, methods, properties and events, in Russian and English. Search by name, or read a page as text. Look the API up instead of guessing a signature. |

### Write

Write tools change the model through EDT's own engine, not by editing text. Every one of them needs
the token, and every one does nothing until asked: `apply=false` is the default and returns a plan.
`apply=true` makes the change and serializes the `.mdo`.

| Write tool | What it does |
|------------|--------------|
| `edt_create_object` · `edt_delete_object` | Creates a new top-level object – Catalog, Document, Enum, InformationRegister and the rest – through EDT's factory and the per-type initializer, and registers it in the Configuration. Deletion cascades: every reference in metadata and in BSL goes with the object, which is why it needs `force`. |
| `edt_add_attribute` · `edt_modify_attribute` · `edt_remove_attribute` | Adds an attribute to a metadata object with a validated type, klass, synonym and comment; changes an existing attribute's type, synonym or comment; removes one. Removal checks the references first and refuses while any remain, unless forced. |
| `edt_rename` | Renames an object or a member and carries every reference in metadata and in BSL along with it, through EDT's native refactoring engine. Needs `force`, because a rename breaks whatever was built on the old name. |
| `edt_add_method` · `edt_delete_method` | Adds and deletes a procedure or function in a module's BSL, both guided by the model. The insert refuses any result that would not parse again. The cut takes the adjacent doc comment with it, and its plan returns the exact text that will go, which is why it needs `force`. Both address a module by FQN, `HTTPService.X` and `WebService.X` included. |
| `edt_add_route` | Adds a route to an `HTTPService`: a URL template plus one HTTP method. It generates the `uuid` of the template and of its method, and writing those by hand is exactly what the bridge exists to spare you. It resolves the `httpMethod` enum, and with `createHandler` it splices a `Функция <handler>(Запрос)` stub into the service module. |
| `edt_add_form` | Adds a managed form to a metadata object through EDT's own form generator, the engine behind the "New form" wizard. The form, its items and its module are generated rather than written out as XML by hand. |
| `edt_add_form_attribute` · `edt_modify_form_attribute` · `edt_remove_form_attribute` | Adds, changes and removes a form attribute, or a column of a value-table attribute when you pass `columnOf`. Ids come from EDT's form identifier service. Besides the metadata type grammar these take the platform types a form may hold (`ТаблицаЗначений`, `СписокЗначений` and so on) and the object types `ВнешняяОбработкаОбъект.X` and `СправочникОбъект.X`, the kind a main attribute carries. Removal lists the items bound to the attribute and needs `force`. |
| `edt_add_form_command` · `edt_modify_form_command` · `edt_remove_form_command` | Adds, changes and removes a form command. Adding can write the handler procedure's stub into the form module as well, creating that module when the form has none. Removal lists the buttons wired to the command and needs `force`. |
| `edt_add_form_handler` | Registers an event handler on a form or on one of its items. This is the `handlers` entry in `Form.form`, and without it the platform never calls the procedure while validation says nothing. The allowed events come from EDT itself, so a misspelled one is refused with the list attached. It can also write the stub, with the signature the event declares and the directive its environments imply. |
| `edt_add_form_item` · `edt_modify_form_item` · `edt_remove_form_item` | Adds, changes and removes a form's visual items: field, table, button, group, decoration. It goes through EDT's own `IFormItemManagementService`, the service the form editor calls. A table bound to a value-table attribute gets its columns filled in automatically. A `titleRu` given to the table stays on the table, and the generated columns keep EDT's own default. Changing an item also renames it with `newName`: an item's name lives in the form model, and the handler procedures keep theirs. Removal takes everything nested inside and needs `force`. |
| `edt_adopt_object` | Adopts an object of the base configuration into an extension project through EDT's own `IModelObjectAdopter`. Nothing in an extension can intercept that object until this happens, and it is the step that completes `edt_create_extension`. |
| `edt_import_project` | Registers a project directory that already exists in the workspace: "Import existing project" without the dialog. The create-work-delete cycle lacked this step. An extension in modification-and-control mode is validated against a base project on the target release, and that project comes from another worktree. Nothing on disk is rewritten, and a name of its own lets two working copies of one repository live side by side. |
| `edt_create_extension` · `edt_create_external_object` | Starts a project. Either a configuration extension against a base project through `IExtensionProjectManager`, whose root Configuration is the base configuration adopted the way the wizard does it, which is what makes the project loadable into an infobase, plus a name prefix, a purpose (Customization, AddOn or Patch) and a synonym. Or an external data processor project, where the "processor → .epf" cycle begins. |
| `edt_clean_project` · `edt_delete_project` | Finishes with a project. Either discard its build results so that validation runs again: EDT's "Clean" dialog driven programmatically, reporting the problem count before and after and waiting until that count settles, because a stale marker outliving its cause is worse than no marker at all. For one module changed on disk, `edt_project_errors` with `refresh=true` is the light alternative. Or remove the project from the workspace through the Eclipse workspace, so that no ghost project is left behind. Removal needs `force`, since it cannot be undone. |
| `edt_build_extension` · `edt_dump_external_object` | Builds the binaries: a `.cfe` from an extension project, an `.epf` or `.erf` from an external data processor or report. Both can go around EDT's platform resolver when it serves no thick client. They export designer XML in-process, then assemble the file with a full on-disk 1C install in a temporary infobase that is deleted afterwards. `logPath` keeps the platform's build log next to the artefact. The dump falls back to the on-disk route when EDT's own dumper refuses, `route` pins it to `edt` or `disk`, and the auto-dump generation is put back if the refusal switched it off. |

### Infobases, the cluster and the platform

These tools talk to a running infobase rather than to the model in EDT. There are four ways in, and
they are not interchangeable. The **Through** column says which one a tool uses.

- **EDT's own synchronization** is what the IDE itself uses. It opens its own infobase connection and
  has no way to take credentials from outside the UI, so it stops at an infobase that authenticates
  its users.
- **`ibcmd`** goes straight at the database, by file path or by DBMS coordinates, so a clustered
  infobase needs no cluster access. Its `extension` mode has no 1C credentials at all.
- **the configurator agent** is a designer started with `/AgentMode`. It takes commands over SSH and
  authenticates as the infobase user, which is how it reaches what the other two cannot. The bridge
  keeps one agent per infobase: starting one is slow, holding one is cheap.
- **`rac`** is the cluster itself, and that is where sessions live. Neither the agent nor `ibcmd`
  sees them.

Tools that change an infobase need the token and return a plan by default, exactly like the write
tools above.

| Tool | Through | What it does |
|------|---------|--------------|
| `edt_infobases` · `edt_platform_installations` | EDT | What the platform side has to work with. EDT's registered infobases with name, uuid and connection string, and the associations the open projects have with them. Then the 1C:Enterprise installations EDT resolves from when it dumps an `.epf` or `.erf` or creates an infobase, each one resolved to a concrete install that carries a thick client, plus the full installs found on disk. |
| `edt_designer_agent` | agent | The lifecycle of the configurator agents the bridge drives: list, start, stop, sweep. An agent is a configurator in `/AgentMode` holding an open infobase session and authenticating as the infobase user, which is how the bridge reaches an infobase the other transports cannot. It starts on demand and stays up between calls, and stopping one frees the session it holds on the server. An agent idle past `EDT_BRIDGE_AGENT_IDLE_MINUTES` stops by itself, because a standing agent costs a client license and a Designer session; the default is 30 minutes, and `off` keeps agents forever. A crashed agent leaves behind a session that holds the infobase's configuration lock. That remnant is swept before every start, on a `stop` for the infobase whose agent has died, and on demand with `action=sweep`. Ownership is proven by the record each agent writes about itself, never guessed from the session's host and user. |
| `edt_infobase_config_state` | agent | Is the infobase's database configuration – the code sessions actually execute – up to date, or is an update still waiting? The platform answers this itself. The update is started and its confirmation is then refused, so nothing is applied, and a pending update comes back as the full list of structure changes that are waiting. It runs through a configurator agent, so a server infobase that authenticates its users is reachable. |
| `edt_update_database_config` | agent | Applies the database configuration. This is the step that makes running sessions execute the configuration the infobase holds. Loading a project into an infobase does not do it, and until it happens every session keeps running the previous code; a freshly added HTTP route answering 404 is what that looks like. Returns a plan by default. `sessionTermination=force` ends the sessions holding the base when an exclusive lock is needed. |
| `edt_update_infobase` | EDT · agent | Updates an infobase's configuration from an EDT project. By default it goes through EDT's synchronization engine, which confirms db-structure changes automatically and aborts on a conflict, and which cannot authenticate to an infobase that has users. With `transport=agent` the project is exported to designer XML and loaded through the agent instead. That is the only route into a server infobase with users, and the database configuration is applied afterwards. |
| `edt_create_infobase` · `edt_register_platform` | EDT · disk install | Creates an empty file infobase and registers it in EDT's list, falling back to a full install found on disk when EDT resolves none for the version. Or registers a full install into EDT, so that EDT's own engine can use it. |
| `edt_extension_properties` | agent · ibcmd | Reads and sets how an extension is registered in an infobase: safe mode, protection from dangerous actions, active, scope. Neither building a `.cfe` nor updating from EDT decides any of this, and a freshly registered extension gets safe mode and dangerous-action protection turned on. An extension that changes methods of the base configuration cannot run under them. Pass the extension project and the result says whether that is the case. Addressed by an EDT-registered name the call goes through the agent, which reaches a server infobase with users; addressed by explicit DBMS coordinates it goes through `ibcmd`, which does not. |
| `edt_delete_extension` | agent | Removes an extension from an infobase, the step that closes the lifecycle of create, load, configure, delete. The plan reads the extension's current properties first, so a wrong name gets a plain answer. Needs `force` on top of apply: an extension's configuration lives in the infobase, and nothing here puts it back. |
| `edt_infobase_sessions` | rac | The 1C cluster's sessions through `rac`: list them for one infobase or one application, and end them. Neither the agent nor `ibcmd` can, because sessions live in the cluster manager. Reach for this when an infobase refuses to be configured. A designer session that was killed rather than closed still holds the configuration lock, and it shows up here as a `Designer` session. Terminating returns a plan by default and needs force. |
| `edt_infobase_maintenance` | rac | A maintenance window around a database update. `begin` raises `scheduled-jobs-deny`, optionally `sessions-deny` with a permission code, watches the sessions drain by themselves and reports "clear to update". `end` lowers both flags, including a `sessions-deny` that a `begin` in another call raised, and says so when one stays up. `status` only reports. The point is that on a lively base BackgroundJob sessions respawn every minute, so terminating them is useless. Deny first, and then nothing has to be killed. Needs the infobase administrator, and returns a plan by default. |
| `edt_infobase_dump` | ibcmd | Dumps an infobase to a `.dt` through `ibcmd`. This is the backup to take before applying a configuration to the database, and the bridge had no way to make it before. Addresses the infobase by file path or by DBMS coordinates, refuses to overwrite an existing file, and returns a plan by default. Nothing in the infobase changes, but the dump reads all of its data, so it needs the token. |

### Debug

These tools attach to the debug server (dbgs) of a running infobase and drive execution. Use a test
stand, not production. All of them need the token, and `edt_evaluate` is guarded hardest.

| Debug tool | What it does |
|------------|--------------|
| `edt_debug_attach` · `edt_debug_detach` | Attaches a debug session to the debug server of a running infobase and returns a `sessionId` for the other debug tools. Detaching terminates the session and frees the infobase. |
| `edt_debug_inspect` · `edt_debug_control` | Lists a session's threads and, for the suspended ones, their BSL stack frames and the top frame's variables, read-only. Then drives execution: `suspend` and `resume`, or `stepOver`, `stepInto` and `stepReturn` on a suspended thread. |
| `edt_evaluate` | Evaluates an arbitrary BSL expression in a suspended frame, which is code execution against the live infobase. It needs the token, `allowCodeExecution=true` on the call, and the server switch `EDT_BRIDGE_ALLOW_EVALUATE=1`, which is off by default. |

### Served by the wrapper

One tool does not come from the bridge inside EDT, because it acts on that EDT, and a tool cannot
report on the process it has just ended. `edt-bridge-mcp` serves this one itself: it lists it
alongside the bridge tools and answers without forwarding anything.

| Wrapper tool | What it does |
|--------------|--------------|
| `edt_open_gui` | Hands the workspace over to the GUI EDT. It stops the headless session behind the bridge, waits until the processes are really gone, and opens the EDT window on the same workspace. The waiting matters, because the port falls silent well before the runtime does, and it is that leftover process people end up hunting in the task manager. `force` kills whatever does not stop in time, the keepalive shell included, which outlives a tree kill from below. The bridge comes back by itself once the GUI EDT has loaded the plugin. |
