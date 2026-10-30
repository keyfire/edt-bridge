---
title: "Tools"
description: "Every edt_* tool the bridge exposes: reading the live model, writing through EDT's own engine, and debugging a running infobase."
sidebar:
  label: Tools
  order: 3
---

Tool names are `edt_*` in snake_case, parameters are camelCase: `projectName`, `fqn`, `queryText`.
Object names are Cyrillic, as in the configuration; the type prefix is English:
`Catalog.Контрагенты`, `Document.ЗаказКлиента`. An MCP host presents tools from all servers in one list. The `edt_` prefix identifies EDT tools;
for example, `edt_rename` can be distinguished from a server's `rename` tool.

A call with an undeclared parameter is rejected. The response suggests a similar name
(`deleteContents` → `deleteContent`) and lists the accepted parameters.

Together the tools close the whole cycle without leaving MCP: create, develop, build, deliver, debug.

![Full delivery cycle over MCP](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/delivery.svg)

### Read

| Tool | What it returns |
|------|-----------------|
| `edt_projects` · `edt_project_errors` | Workspace projects and validation problems from Eclipse and EDT checks. `modelReady` reports whether the BM model and derived data are ready; `complete=false` marks a partial snapshot and must not be treated as a clean result. Filter by `fqn`, `modulePath` or `severity`; `countOnly` returns counts and `brief` returns one line per problem. `modulePath` includes EDT markers traced to source files; unresolved markers are counted and reported with a hint. Form filters exclude sibling forms. `stale` identifies markers older than their source and `unsynchronized` identifies disk changes not yet read by the workspace. `refresh=true` rereads the scope and builds the project; `waitSeconds` sets the validation wait (1-600, default 120). A timeout returns current markers with `refreshed.settled=false`. Include WARNING when checking generated code: EDT reports some undefined methods as warnings. |
| `edt_check_info` | Returns a validation check's description, non-compliant and compliant examples, and links to 1C development standards in Russian or English. It complements `edt_project_errors`, which identifies the triggered check. Search by check ID or problem message. A check with a short-code ID is found by title. |
| `edt_metadata_objects` · `edt_metadata_details` | Top-level metadata objects, optionally filtered by type (`Catalog`, `Document`, ...) and by a name substring. Then one object's core properties and its structure: attributes, tabular sections, forms, commands, templates, dimensions, resources, enum values, each attribute with its value type. Common attributes include usage composition, auto-use and separator settings; event subscriptions include source types, event and handler. Scheduled jobs include `use`, `methodName`, `predefined`, `key`, `restartCountOnFailure` and `restartIntervalOnFailure` in `properties`, including model defaults. Values are strings; these describe metadata, not the current scheduler state. |
| `edt_find_references` · `edt_outgoing_calls` · `edt_outgoing_structures` | Return incoming references and outgoing calls. Metadata references come from EDT's cross-reference index; `method` selects BSL call sites of `CommonModule.X.method`. Outgoing calls list the methods called by a module, method, or form one level out, with call-site counts and an ExtAPI layer flag. The third tool extracts the recognized top-level keys of the `Структура` passed to each qualified outgoing call. Method search also accepts literal object-manager calls such as `Catalogs.Items.Method(...)`; `moduleType` does not narrow this search. Metadata mode also reports `queryReferences` from dynamic-list and DCS dataset queries; `queryCoverageComplete` and errors report the limits of that separate scan. Method mode searches BSL call sites. |
| `edt_module_text` · `edt_go_to_definition` · `edt_symbol_info` | Return BSL module text or one method, including procedure and function names and signatures, addressed by FQN or modulePath. Also return a symbol's definition at a position, including kind, name, owning object, and location, or the element and computed expression value types at a position. |
| `edt_search_modules` | Full-text search across a project's BSL modules, by substring or regular expression, with an optional path filter. It reads through Eclipse's file buffers, so a module open in an editor is searched as it stands right now, unsaved edits included. `edt_find_references` answers "who calls this method"; this one answers "where does this text appear". Regular expressions use Unicode word classes and case folding. Texts in `Form.form` and `Template.dcs` are outside the BSL search. |
| `edt_search_queries` | Searches the query fields of dynamic lists in `Form.form` and datasets in `Template.dcs`, by text or Unicode regular expression. Returns the path, form/attribute or template/dataset, query line and XML field line. Parse errors and truncation set `complete=false`. |
| `edt_validate_query` | Checks a 1C query against the project's live metadata: syntax and semantics, so unknown tables and fields and type errors come back with positions. Takes the query text, or a module address (`modulePath`, optionally one `method`). Given a module, the bridge pulls the `\|`-framed query literals out of the live module itself and checks each one. The batch is followed as a whole too, which EDT does not do. A query that reads a temporary table no earlier query puts there (`ПОМЕСТИТЬ`), or one an earlier query has dropped, makes the text invalid. For a module literal that is an `INFO` note instead, because the table may come from a shared temporary table manager or from another literal. |
| `edt_form_structure` · `edt_form_render` · `edt_picture_export` | Return a managed form's structure, its image, and the content of a configuration picture. The structure includes a tree of fields, groups, tables, buttons, and decorations. Items include data bindings, static visible/enabled/readOnly, event handlers, input-field properties, command-to-button links, and conditional appearance. Form attributes, commands, parameters, and handlers are also returned. EDT's offscreen renderer renders the form to PNG with a choice of interface variant and theme. For CommonPicture, the content of Picture.zip is returned. |
| `edt_platform_help` | Search the 1C:Enterprise platform Syntax Helper supplied with EDT by name or read a page as text. The Russian and English API reference covers objects, methods, properties, and events. |

Dynamic-list queries are first parsed with the EDT Ql language. A syntax error triggers a second
parse with QlDcs, which supports data-composition extensions such as `{WHERE ...}` and
`{LEFT JOIN ...}`. If parsing still fails, `queryFailures` reports the failure and
`queryCoverageComplete` remains false. Use `edt_search_queries` to search those query fields by text.

### Write

Write tools change the model through EDT's own engine, not by editing text. Every one of them needs
the token, and every one does nothing until asked: `apply=false` is the default and returns a plan.
`apply=true` makes the change and serializes the `.mdo`.

`edt_delete_object` separates EDT's indexed references from lexical candidates in `referenceAudit`.
`scanComplete` describes the stated file scan; `complete=false` keeps semantic coverage explicit.
Check `countIsLowerBound`, errors and samples before deciding to force deletion. After apply,
`deletionConfirmed` confirms target absence in the live BM model. `remainingPossibleReferences`
and `retainedFormControls` report the observed remaining uses and controls with cleared data paths;
the response states the limits of the export wait and after-audit.

| Write tool | What it does |
|------------|--------------|
| `edt_create_object` · `edt_delete_object` | Creates a new top-level object – Catalog, Document, Enum, InformationRegister and the rest – through EDT's factory and the per-type initializer, and registers it in the Configuration. Deletion uses EDT refactoring and requires `force`. The preview reports indexed references and possible uses in BSL, query text and form bindings. These uses can remain after deletion; the response does not promise a complete cascade. |
| `edt_add_attribute` · `edt_modify_attribute` · `edt_remove_attribute` | Adds or changes an attribute, register resource or dimension (`kind`, default `attribute`). Addition checks the name across all three field lists. Changes support the type, Russian synonym and comment. `edt_remove_attribute` removes attributes; use `edt_delete_object` with the member FQN for resources and dimensions. |
| `edt_rename` | Renames a method, metadata object or member and updates references through EDT's refactoring engine. The preview reports known affected files and the completeness of file and edit counts. Requires `force` because external uses of the old name may break. |
| `edt_add_method` · `edt_delete_method` | Adds and deletes a procedure or function in a module's BSL, both guided by the model. The insert refuses any result that would not parse again. The cut takes the adjacent doc comment with it, and its plan returns the exact text that will go, which is why it needs `force`. Both address a module by FQN, `HTTPService.X` and `WebService.X` included. |
| `edt_add_route` | Adds a route to an `HTTPService`: a URL template plus one HTTP method. It generates the `uuid` of the template and its method. It resolves the `httpMethod` enum, and with `createHandler` it splices a `Функция <handler>(Запрос)` stub into the service module. |
| `edt_add_form` | Adds a managed form to a metadata object through EDT's own form generator, the engine behind the "New form" wizard. The generator creates the form, its items and its module. |
| `edt_add_form_attribute` · `edt_modify_form_attribute` · `edt_remove_form_attribute` | Adds, changes and removes a form attribute, or a column of a value-table attribute when you pass `columnOf`. Ids come from EDT's form identifier service. Besides the metadata type grammar these take the platform types a form may hold (`ТаблицаЗначений`, `СписокЗначений` and so on) and the object types `ВнешняяОбработкаОбъект.X` and `СправочникОбъект.X`, which can be used for the main attribute. Removal lists the items bound to the attribute and needs `force`. |
| `edt_add_form_command` · `edt_modify_form_command` · `edt_remove_form_command` | Adds, changes and removes a form command. Adding can write the handler procedure's stub into the form module as well, creating that module when the form has none. Removal lists the buttons wired to the command and needs `force`. |
| `edt_add_form_handler` | Registers an event handler on a form or on one of its items. This creates the `handlers` entry in `Form.form`. Without it, the platform does not call the procedure and validation does not report an error. The allowed events come from EDT itself, so a misspelled one is refused with the list attached. It can also write the stub, with the signature the event declares and the directive its environments imply. |
| `edt_add_form_item` · `edt_modify_form_item` · `edt_remove_form_item` | Adds, changes and removes a form's visual items: field, table, button, group, decoration. It goes through EDT's own `IFormItemManagementService`, the service the form editor calls. A table bound to a value-table attribute gets its columns filled in automatically. A `titleRu` given to the table stays on the table, and the generated columns keep EDT's own default. The `newName` parameter renames the item in the form model while preserving handler procedure names. Removal takes everything nested inside and needs `force`. |
| `edt_adopt_object` | Adopts an object of the base configuration into an extension project through EDT's own `IModelObjectAdopter`. Nothing in an extension can intercept that object until this happens, and it is the step that completes `edt_create_extension`. |
| `edt_import_project` | Registers a project directory that already exists in the workspace: "Import existing project" without the dialog. The create-work-delete cycle lacked this step. An extension in modification-and-control mode is validated against a base project on the target release, and that project comes from another worktree. Files on disk are not changed. A separate name allows two working copies of one repository in the workspace. |
| `edt_modify_object` · `edt_update_subsystem` | Sets common-module execution flags and result reuse, or adds/removes existing objects from a subsystem. Both validate the whole request before writing and show a plan. Removing members additionally requires `force=true`. `extendModule=true` enables an adopted common module for extension. |
| `edt_create_extension` · `edt_create_external_object` | Create a configuration extension project or an external data processor project. The extension uses `IExtensionProjectManager`. Its root Configuration is adopted from the base configuration as in EDT's wizard, making the project loadable into an infobase. A name prefix, purpose (Customization, AddOn or Patch), and synonym are also set. An external processor project is used for the "processor → .epf" build process. |
| `edt_clean_project` · `edt_delete_project` | Clear build results or remove a project. The clean operation invokes EDT's "Clean" function, reports problem counts before and after, and waits for the count to stabilize so validation can run again. For one module changed on disk, use `edt_project_errors` with `refresh=true`. Project removal uses the Eclipse workspace to update its resource tree and requires `force` because it cannot be undone. |
| `edt_build_extension` · `edt_dump_external_object` | Builds the binaries: a `.cfe` from an extension project, an `.epf` or `.erf` from an external data processor or report. Both can go around EDT's platform resolver when it serves no thick client. They export designer XML in-process, then assemble the file with a full on-disk 1C install in a temporary infobase that is deleted afterwards. `logPath` keeps the platform's build log next to the artefact. The dump falls back to the on-disk route when EDT's own dumper refuses, `route` pins it to `edt` or `disk`, and the auto-dump generation is put back if the refusal switched it off. |
| `edt_move_method` | Moves a method within its module, preserving its exact text, documentation and directives. Supports nested region paths and neighboring methods, checks the intermediate and final module with the EDT parser, and requires `apply=true, force=true`. |
| `edt_move_form_item` | Moves an existing form item by sibling index or anchor while preserving identity, id, children, properties and events. Refuses cycles and moves that require converting the item kind or data binding. |
| `edt_import_external_object` | Imports an existing `.epf` or `.erf` into a new EDT project, optionally with `baseProjectName`. Uses EDT or a full platform installation to unpack XML, then the native EDT importer. The source is preserved and the tool removes its own partial project after a failed import. |

#### Register fields

`edt_add_attribute` and `edt_modify_attribute` accept `kind`: `attribute` (default),
`resource` or `dimension`. Resources and dimensions require an owner that supports
that field list. The operations support information, accumulation, accounting and
calculation registers. Existing calls without `kind` continue to address attributes.

Addition checks names case-insensitively across the owner's `attributes`, `resources`
and `dimensions`, including a second check in the write transaction. A collision returns
`nameAvailable=false` and the exact `conflictingFqns`. The dry-run writes nothing.

For accumulation, accounting and calculation register resources, use one explicit
`Число` type, for example `Число(15,2)`. Non-numeric, composite, reference and defined
types are refused for these resources before writing. Information-register resources
retain the general type syntax. An explicit `kind` selects that list even if an old
configuration already contains the same name in another list; duplicate names inside
the selected list are refused.

For example, preview a resource addition:

```json
{
  "projectName": "Demo",
  "ownerFqn": "InformationRegister.Versions",
  "kind": "resource",
  "name": "Version",
  "type": "Строка(20)"
}
```

To change that field, call `edt_modify_attribute` with the same owner, name and kind,
and at least one of `newType`, `newSynonymRu` or `newComment`. An empty synonym or
comment clears it; omitting a property keeps it. Changing the type returns a warning
about data and code compatibility. Both operations require `apply=true` to write.
The response distinguishes an in-memory commit from an accepted export request;
acceptance does not confirm that serialization has finished.

`edt_remove_attribute` continues to remove attributes only. If a modify/remove call
addresses a resource or dimension as an attribute, the response names the actual
member FQN and suggests `kind`, `edt_delete_object` and `edt_rename`. Delete or rename
those members through the latter tools, using `targetFqn`, for example
`InformationRegister.Versions.Resource.Version`.

### Infobases, the cluster and the platform

These tools access a running infobase. They use four distinct connection methods, identified in the
**Through** column.

- **EDT's own synchronization** is what the IDE itself uses. It opens its own infobase connection and
  has no way to take credentials from outside the UI, so it stops at an infobase that authenticates
  its users.
- **`ibcmd`** goes straight at the database, by file path or by DBMS coordinates, so a clustered
  infobase needs no cluster access. Its `extension` mode has no 1C credentials at all.
- **the configurator agent** is a designer started with `/AgentMode`. It takes commands over SSH and
  authenticates as the infobase user, which is how it reaches what the other two cannot. The bridge
  keeps one agent per infobase: it starts the agent on demand and keeps it between calls.
- **`rac`** connects to the cluster, which manages sessions. Neither the agent nor `ibcmd`
  sees them.

Tools that change an infobase need the token and return a plan by default, exactly like the write
tools above.

| Tool | Through | What it does |
|------|---------|--------------|
| `edt_infobases` · `edt_platform_installations` | EDT | Lists infobases registered in EDT with their names, UUIDs, connection strings and associated open projects. Also lists the 1C:Enterprise installations EDT resolves for `.epf` or `.erf` export and infobase creation, each with a thick client, plus full installations found on disk. |
| `edt_designer_agent` | agent | The lifecycle of the configurator agents the bridge drives: list, start, stop, sweep. An agent is a configurator in `/AgentMode` holding an open infobase session and authenticating as the infobase user, which is how the bridge reaches an infobase the other transports cannot. It starts on demand and stays up between calls, and stopping one frees the session it holds on the server. An agent idle past `EDT_BRIDGE_AGENT_IDLE_MINUTES` stops by itself, because a standing agent costs a client license and a Designer session; the default is 30 minutes, and `off` keeps agents forever. A crashed agent leaves behind a session that holds the infobase's configuration lock. That remnant is swept before every start, on a `stop` for the infobase whose agent has died, and on demand with `action=sweep`. Ownership is proven by the record each agent writes about itself, never guessed from the session's host and user. |
| `edt_infobase_config_state` | agent | Checks whether the database configuration executed by sessions is applied or an update is pending. The update is started but its confirmation is refused, so no changes are applied. A pending update returns the full list of structure changes. The call uses a configurator agent, so a server infobase with user authentication is supported. |
| `edt_update_database_config` | agent | Applies the database configuration. This is the step that makes running sessions execute the configuration the infobase holds. Loading a project into an infobase does not do it, and until it happens every session keeps running the previous code; a freshly added HTTP route answering 404 is what that looks like. Returns a plan by default. `sessionTermination=force` ends the sessions holding the base when an exclusive lock is needed. |
| `edt_update_infobase` | EDT · agent | Updates an infobase configuration from an EDT project. EDT synchronization is used by default: database structure changes are confirmed automatically, a conflict aborts the update, and infobases with users are not supported. With `transport=agent`, the project is exported to designer XML and loaded through a configurator agent. This supports server infobases with users and applies the database configuration unless `updateDatabaseConfig=false`. When the saved load record matches the infobase state, only changed files are loaded. See [Incremental configuration loading](#incremental-configuration-loading) for the conditions and `fullLoad` parameter. |
| `edt_create_infobase` · `edt_register_platform` | EDT · disk install | Creates an empty file infobase and registers it in EDT's list, falling back to a full install found on disk when EDT resolves none for the version. Or registers a full install into EDT, so that EDT's own engine can use it. |
| `edt_register_infobase` | EDT | Registers an existing server infobase by `name`, `connect="Srvr=server;Ref=base;"`, `version` and optional `user`. Writes through EDT, reloads its list and reads back the UUID without restarting EDT. Refuses duplicate names/addresses and embedded credentials; it does not create or change the server database. |
| `edt_extension_properties` | agent · ibcmd | Reads and sets how an extension is registered in an infobase: safe mode, protection from dangerous actions, active, scope. Neither building a `.cfe` nor updating from EDT decides any of this, and a freshly registered extension gets safe mode and dangerous-action protection turned on. An extension that changes methods of the base configuration cannot run under them. Pass the extension project to check whether these settings prevent it from running. Addressed by an EDT-registered name the call goes through the agent, which reaches a server infobase with users; addressed by explicit DBMS coordinates it goes through `ibcmd`, which does not. |
| `edt_delete_extension` | agent | Removes an extension from an infobase, the step that closes the lifecycle of create, load, configure, delete. The plan reads the extension's current properties first, so a wrong name gets a plain answer. Requires `force` in addition to apply because the extension configuration is stored in the infobase and cannot be restored by this tool. |
| `edt_infobase_sessions` | rac | The 1C cluster's sessions through `rac`: list them for one infobase or one application, and end them. Neither the agent nor `ibcmd` can, because the cluster manager controls sessions. Reach for this when an infobase refuses to be configured. A designer session that was killed rather than closed still holds the configuration lock, and it shows up here as a `Designer` session. Terminating returns a plan by default and needs force. |
| `edt_infobase_maintenance` | rac | Manages a maintenance window for a database update. `begin` sets `scheduled-jobs-deny` and optionally `sessions-deny` with a permission code, waits for sessions to finish, and returns "clear to update". `end` clears both flags, including `sessions-deny` set by another `begin` call, and reports any flag that remains set. `status` reports the state. BackgroundJob sessions are recreated every minute on a running infobase; setting the denial prevents them from being recreated. Needs the infobase administrator, and returns a plan by default. |
| `edt_infobase_dump` | ibcmd | Dumps an infobase to a `.dt` through `ibcmd`. This is the backup to take before applying a configuration to the database, and the bridge had no way to make it before. Addresses the infobase by file path or by DBMS coordinates, refuses to overwrite an existing file, and returns a plan by default. Nothing in the infobase changes, but the dump reads all of its data, so it needs the token. |

#### Incremental configuration loading

With `edt_update_infobase` and `transport=agent`, the project is exported in full on every call.
The exported files are compared with the record of the last successful load into the same infobase.
The record contains file hashes, the platform version and the infobase configuration generation ID.
It is stored in the workspace metadata and remains available after the agent or EDT restarts.

Only changed files are loaded when all of these conditions are met:

- The record is readable and belongs to the same infobase and platform version.
- The configuration generation ID is available and matches the record.
- No file has been removed from the export.
- `Configuration.xml` and the vendor support file `Ext/ParentConfigurations.bin` are unchanged.
- No more than half of the exported files have changed.

If any condition is not met, the full configuration is loaded. If the conditions are met
and no files have changed, file loading is skipped. If a partial load fails, the bridge attempts a full load; a lost connection
is handled by the agent connection retry. Extensions are always loaded in full.
To request a full configuration load explicitly, pass `fullLoad=true`.

The response includes `loadMode` (`full`, `partial` or `skipped`) and `loadReason`, file counts,
and export and load durations. `loadRecord` identifies the saved record and `loadRecordState`
reports whether it was read or saved. `loadRepeated` indicates a load repeated after a lost
connection. The database configuration is applied after file processing unless
`updateDatabaseConfig=false`, including when file loading is skipped.

### Debug

These tools attach to the debug server (dbgs) of a running infobase and drive execution. Use a test
stand. All tools require a token; `edt_evaluate` also requires additional permissions.

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
| `edt_open_gui` | Transfers the workspace to the GUI EDT. It stops the headless EDT, waits for its processes to exit, and opens EDT on the same workspace. The port closes before the runtime exits, so the processes must be allowed to finish. `force` terminates processes that do not stop in time, including the parent keepalive shell. The bridge becomes available once GUI EDT loads the plugin. |

### Placement, properties and validation snapshots

`edt_add_method` accepts one of `beforeMethod` and `afterMethod`. `region` accepts a full nested
path such as `Public/Helpers`; a short name must be unique. A neighbor must belong directly to the
selected scope. A missing or ambiguous target is refused. The plan returns `insertLine`,
`previousMethod` and `nextMethod`. `edt_move_method` uses the same destination options.
For the first method of an existing empty model module, pass its `fqn`. The bridge creates the
missing `.bsl` only on apply after checking the owner and module type; dry-run creates no file.
An arbitrary missing `modulePath` is refused.

`edt_rename` accepts `method="CommonModule.Api.Run"`, or `methodName="Run"` with an owner FQN
or `modulePath`. It uses EDT's method refactoring. The preview lists `affectedFiles` with change
and edit counts before `apply=true, force=true`. Save affected editors before applying a rename.
`nativeTextEditCount` counts the text edits exposed by EDT. Model changes can have
`editCount=null` and `editCountComplete=false`; `changeCount` still reports the operations.
Metadata mode also returns `targetPath`. Some metadata refactoring items expose no file mapping:
`coverageComplete=false`, `unmappedItemCount` and `unmappedItems` identify that partial preview.
The known file list is then a subset; inspect the native `items` before applying.

`edt_add_form_item` and `edt_move_form_item` accept exactly one placement selector: `index`
(zero-based), `beforeItem` or `afterItem`. A move calculates the index after excluding the moved
item. An omitted `parent` keeps its parent; `parent=""` selects the form root. Add and modify
support `groupDirection` (alias `group`), `representation`, `showTitle`, `united`, `titleLocation`,
`maxHeight` and `verticalStretch` where the item type supports them. Unknown enums and settings
for the wrong item type are refused. `fieldType` selects `InputField`, `RadioButtonField` or
`CheckBoxField`. `choiceList` replaces the choices of an input or radio field:

```json
{
  "choiceList": [
    {
      "value": "all",
      "titleRu": "Все"
    },
    {
      "value": "selected",
      "titleRu": "Выбранные"
    }
  ]
}
```

Choice values can be strings, numbers or booleans; references and enum values are not supported.
An empty list clears the choices. `edt_form_structure` returns item IDs and properties for checking
the result. A dynamic-list attribute also returns `customQuery`, `mainTable` and `queryText` under
`dynamicList`. `queryLimit` bounds each returned query (default 20000, maximum 100000 characters);
`queryTextTruncated` reports omitted text.

For a new common module, `edt_create_object` accepts a `properties` object. The same object goes
to `edt_modify_object` for an existing module: `global`, `server`, `externalConnection`,
`clientManagedApplication`, `clientOrdinaryApplication`, `serverCall` and `privileged` are booleans;
`returnValuesReuse` is `DontUse`, `DuringRequest` or `DuringSession`. Adopted modules retain the
base configuration's execution flags. Pass `extendModule=true` to `edt_adopt_object` or
`edt_modify_object` to enable extending an adopted common module without replacing its body.
`edt_update_subsystem` takes `fqn` and arrays of object FQNs in `add` and `remove`.
Creating and adopting objects preserve an already alphabetic Configuration list, ignoring case.
A list in a custom order keeps that order and receives a new object at the end.

To compare validation results, call `edt_project_errors` with `baseline="save"`, then pass the
returned `baselineId` with `baseline="compare"` after the change. Both operations refresh the scope
and wait for validation to settle even when `refresh` is omitted. Use the same project and scope
filters in both calls. Comparison returns only new problems plus resolved-problem counts and
`resolvedProblems`; `limit`, `brief` and `countOnly` control the returned presentation.
Line shifts do not turn an unchanged problem into a new one, and duplicate problems remain
separate occurrences. Incomplete or truncated input is refused. Snapshots live only in this bridge
session; restarting EDT invalidates them. The cache holds at most 16 snapshots and 200000 markers
in total, with 100000 markers per snapshot. Saving returns the IDs it evicted.

`edt-bridge-mcp status --all` checks the bridge discovery port range without starting EDT.
Use `--ports 8770,8771` to select ports explicitly. Each detected bridge reports its workspace,
port, bridge bundle version, EDT product version and project readiness. Unreachable ports and
other HTTP services are distinguished from a running bridge.
