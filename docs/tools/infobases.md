---
title: "Infobases, the cluster and the platform"
description: "Tools for a running infobase: configuration update and dump, configurator agents, extensions, cluster sessions, maintenance and platform installations."
sidebar:
  label: "Infobases"
  order: 9
---

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

Tools that change an infobase need the token and return a plan by default.

With `EDT_BRIDGE_PII` set, `edt_infobase_sessions`, `edt_infobase_maintenance` and
`edt_designer_agent` replace the users and computers of sessions and mask personal data in the rest
of the answer; see [Personal data](/personal-data).

| Tool | Through | What it does |
|------|---------|--------------|
| `edt_infobases` · `edt_platform_installations` | EDT | Lists infobases registered in EDT with their names, UUIDs, connection strings and associated open projects. Also lists the 1C:Enterprise installations EDT resolves for `.epf` or `.erf` export and infobase creation, each with a thick client, plus full installations found on disk. |
| `edt_platform_build_readiness` | disk install | Runs Designer against a temporary file infobase to check batch execution and license access. Requires a write token. |
| `edt_designer_agent` | agent | The lifecycle of the configurator agents the bridge drives: list, start, stop, sweep. An agent is a configurator in `/AgentMode` holding an open infobase session and authenticating as the infobase user, which is how the bridge reaches an infobase the other transports cannot. It starts on demand and stays up between calls, and stopping one frees the session it holds on the server. An agent idle past `EDT_BRIDGE_AGENT_IDLE_MINUTES` stops by itself, because a standing agent costs a client license and a Designer session; the default is 30 minutes, and `off` keeps agents forever. A crashed agent leaves behind a session that holds the infobase's configuration lock. That remnant is swept before every start, on a `stop` for the infobase whose agent has died, and on demand with `action=sweep`. Ownership is proven by the record each agent writes about itself, never guessed from the session's host and user. `list` also returns `edtSessions`, the configurator sessions EDT keeps for its own infobases, with the infobase, bound projects, pid and state (`live`, `dead` or `external` for an agent EDT did not start), and `otherConfigurators`, thick-client processes that belong to neither. `action=stop-edt` closes EDT's session for one registered infobase through EDT: EDT shuts down the agent it started and only disconnects from one it did not start. |
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

## Check platform build readiness

Use `edt_platform_installations` to list the full installations available to EDT and on disk.
To exercise the local platform, call `edt_platform_build_readiness` with a configured write
token. Pass `platformVersion` to select the exact four-part build or the newest full
installation in the specified version line. Omit it to check the newest full installation.
`timeoutSeconds` sets the time limit for each process (1–120 seconds, default 30).

The probe creates a file infobase in a temporary directory and runs `1cv8 DESIGNER /DumpCfg`
against it using the host's platform and license access. It then attempts to remove the
temporary files and reports the outcome in `cleanup`. `installation` distinguishes
`platformNotFound` from `incompatibleVersion`. The `createInfobase` and `designer` stages
report their status, including `licenseRefused`, `launchFailed`, `timedOut` or `notChecked`,
and provide the executable name and exit code when available. A pass confirms that the
selected Designer could dump an empty configuration in this environment.

Platform command failures report the executable name, operation and exit code when the process
exited. Diagnostic labels omit the command arguments.

## Incremental configuration loading

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
