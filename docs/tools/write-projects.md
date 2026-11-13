---
title: "Write: projects and builds"
description: "Write tools for projects: extension and external object projects, import, cleaning and deletion, builds of .cfe, .epf and .erf files, and EDT applications."
sidebar:
  label: "Write: projects and builds"
  order: 8
---

These tools create, import, clean and delete projects, build `.cfe`, `.epf` and `.erf`
files, and manage the EDT applications of a project.

Changes need a token and are returned as a plan until `apply=true` is passed; see
[Plan and apply](/tools#plan-and-apply).

| Write tool | What it does |
|------------|--------------|
| `edt_import_project` | Registers a project directory that already exists in the workspace: "Import existing project" without the dialog. The create-work-delete cycle lacked this step. An extension in modification-and-control mode is validated against a base project on the target release, and that project comes from another worktree. Files on disk are not changed. A separate name allows two working copies of one repository in the workspace. An already registered closed project is reopened without reimport, preserving its settings and infobase associations. Repeating the call for an open project changes nothing. |
| `edt_applications` | List native EDT applications for an open project (`action=list`). `associate` links an existing registered infobase by exact name or UUID and reuses an existing application. It does not update the database. `start` launches a thin client for an infobase application; `stop` uses its id from the list; mutations are dry-run by default and require a token. Stop is limited to the process returned to this bridge. If it is still exiting, re-read `bridgeSession` with `list` to confirm completion. |
| `edt_create_extension` · `edt_create_external_object` | Create a configuration extension or an external object project. The extension uses `IExtensionProjectManager`; its root Configuration is adopted from the base configuration as in EDT's wizard, and a name prefix, purpose, and synonym are set. `edt_create_external_object` accepts `kind=processor` (default) or `kind=report`, optionally with `baseProjectName`. Dry-run returns the object type and FQN; apply creates the project through `IExternalObjectProjectManager`. Add its managed form and build an `.epf` or `.erf` with `edt_dump_external_object`. |
| `edt_clean_project` · `edt_delete_project` | Clear build results or remove a project. The clean operation invokes EDT's "Clean" function, reports problem counts before and after, and waits for the count to stabilize so validation can run again. For one module changed on disk, use `edt_project_errors` with `refresh=true`. Project removal uses the Eclipse workspace to update its resource tree and requires `force` because it cannot be undone. |
| `edt_build_extension` · `edt_dump_external_object` | Builds the binaries: a `.cfe` from an extension project, an `.epf` or `.erf` from an external data processor or report. Both can go around EDT's platform resolver when it serves no thick client. They export designer XML in-process, then assemble the file with a full on-disk 1C install in a temporary infobase that is deleted afterwards, or in a copy of the infobase `EDT_BRIDGE_DUMP_INFOBASE` names; an extension with a borrowed form that excludes Post or Write needs that copy, since only the base configuration resolves those commands. `logPath` keeps the platform's build log next to the artefact. An empty spreadsheet template is exported as the empty document DESIGNER writes for it; `method` names such templates. The dump falls back to the on-disk route when EDT's own dumper refuses, `route` pins it to `edt` or `disk`, and the auto-dump generation is put back if the refusal switched it off. |
| `edt_import_external_object` | Imports an existing `.epf` or `.erf` into a new EDT project, optionally with `baseProjectName`. Uses EDT or a full platform installation to unpack XML, then the native EDT importer. The source is preserved and the tool removes its own partial project after a failed import. |

## Application association

Application association uses the active EDT context, including the Git branch.
`associationVerified` confirms the saved relation; `verified` confirms its native application.
Repeated calls reuse the application and do not update the database.

## Full clean

For full clean, inspect `stage`, `completedStages`, elapsed time and cancellation/status details.
`ok=false` with `applied=true` reports a later failure after clean changed build state. Job state
alone does not establish the cause of cancellation.
