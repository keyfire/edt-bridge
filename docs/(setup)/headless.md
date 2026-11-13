---
title: "Headless EDT"
description: "Auto-start of a headless EDT, waiting for the project models in a CI job, and moving a ready workspace to another machine."
sidebar:
  label: "Headless EDT"
  order: 4
---

The wrapper starts a headless EDT when EDT is not running. This page describes that start,
the wait for the project models in a CI job, and moving a workspace that EDT has already
indexed.

## Auto-start

During auto-start, a detached supervisor keeps the CLI input open and tracks the launched
`1cedtcli` process. If the CLI exits before the bridge is ready, the wrapper reports its exit
code and up to 16 KiB from the end of its combined stdout/stderr, decoded as UTF-8 with replacement
for invalid bytes. The supervisor exits with the CLI, and a later call can retry the launch.
`--start-timeout` limits the readiness wait while the CLI is still running. A timeout leaves
the live process running; EDT also continues running after a short-lived wrapper command exits.

## Waiting for the models

The bridge answers before a large project is indexed, and a tool called in between is refused
with "no BM model". In a CI job, wait for the models with `edt-bridge-mcp wait-ready --project
<name> --timeout <seconds>`. The command starts a headless EDT when none is running and polls
`/status` until every named project reports `modelReady`; without `--project` it waits for every
open EDT project. It exits with code 0 when the projects are ready, 1 when EDT is not running or
has exited, and 2 when the time runs out while EDT is still loading. A project imported after the
start is awaited as well.

## A ready workspace

Importing and indexing a large configuration takes a headless EDT tens of minutes, while a
workspace that EDT saved on a graceful stop opens in a few. `edt-bridge-mcp workspace` moves
such a workspace to the next machine or container:

1. Start EDT on the workspace, import the projects and wait for the models:
   `edt-bridge-mcp wait-ready --workspace <ws> --project <name>`.
2. Save the EDT checks of the projects: `edt-bridge-mcp workspace checks --workspace <ws>
   --object <fqn> --output before.json`. The command runs `edt_project_errors` with a refresh for
   every project, or for every object given with `--object`, and refuses a list that is incomplete,
   not settled or cut at its limit. On a large configuration check a few objects whose checks depend
   on the indexed model: the whole project can exceed the problem limit.
3. Stop EDT with `edt-bridge-mcp shutdown --workspace <ws>`, so that it saves its state.
4. Pack the workspace: `edt-bridge-mcp workspace pack --workspace <ws> --archive ready.tar.gz`.
5. On the next machine restore it: `edt-bridge-mcp workspace restore --archive ready.tar.gz`.
6. Start EDT there, wait for the models and compare: `edt-bridge-mcp workspace checks
   --workspace <ws> --compare before.json`.

The archive is a PAX tar, `.tar`, `.tar.gz` or, with Python 3.14 and newer, `.tar.zst`. It keeps
the modification times with their fraction of a second, because EDT compares them with the state
it saved. It leaves out the workspace lock, the logs of `.metadata` (EDT writes its command line
with the bridge token there) and the pid and log lock files. EDT keeps absolute paths in its
metadata, so `restore` unpacks the workspace only under the path it was packed from, into a
missing or empty directory, on the same kind of system. `pack` refuses while an EDT uses the
workspace, and both commands name the process or the lock that holds it. A project whose files
live outside the workspace directory is refused as well, because the archive would not carry it.
`checks --compare` exits with code 0 when the checks match and 2 when they differ, and lists
the problems found on one side only. Where the archives are kept and which inputs name one is
left to the job that builds them.
