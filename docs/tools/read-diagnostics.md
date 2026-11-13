---
title: "Read: diagnostics and reference"
description: "Read tools for project state, validation problems, check descriptions, tool and property reference and the platform Syntax Helper."
sidebar:
  label: "Read: diagnostics"
  order: 1
---

These tools report the state of the workspace projects and their validation problems, and
return reference material: check descriptions, tool schemas, the properties a write tool
accepts and the platform Syntax Helper.

| Tool | What it returns |
|------|-----------------|
| `edt_projects` · `edt_project_errors` | Workspace projects and validation problems from Eclipse and EDT checks. `modelReady` reports whether the BM model and derived data are ready; `complete=false` marks a partial snapshot and must not be treated as a clean result. Filter by `fqn`, `modulePath` or `severity`; `countOnly` returns counts, with the readiness diagnostics only for a partial result or with `details=true`; `brief` returns one line per problem. `modulePath` includes EDT markers traced to source files; unresolved markers are counted and reported with a hint. Form filters exclude sibling forms. `stale` identifies markers older than their source and `unsynchronized` identifies disk changes not yet read by the workspace. `refresh=true` rereads the scope and builds the project; `waitSeconds` sets the validation wait (1-600, default 120). A timeout returns current markers with `refreshed.settled=false`. Include WARNING when checking generated code: EDT reports some undefined methods as warnings. |
| `edt_support_status` | EDT direct-edit preflight for an existing object (`projectName`, `objectFqn`). Returns `EDITABLE`, `READ_ONLY` or `UNKNOWN`; unavailable policy never grants edit permission. This check does not lock the object. |
| `edt_check_info` | Returns a validation check's description, non-compliant and compliant examples, and links to 1C development standards in Russian or English. It complements `edt_project_errors`, which identifies the triggered check. Search by check ID or by title. A short code such as `SU68` is resolved to its check when `projectName` names the project that reported the problem. |
| `edt_tool_help` | The full description and parameter schema of one tool, with the tool sets it belongs to and whether the active set offers it. An unknown name is refused with the nearest names. The wrapper answers for its own and plugin tools. |
| `edt_property_reference` | Lists the properties a write tool can set on one target - a common module, a field or a form item - with each property's type, the accepted enum values and the current value, and names the tool that takes them. A form FQN without `item` returns the form's item names. The list comes from the validation code of the write, so a property the object lacks is not listed. |
| `edt_platform_help` | Search the 1C:Enterprise platform Syntax Helper supplied with EDT by name or read a page as text. The Russian and English API reference covers objects, methods, properties, and events. |

## Project and runtime diagnostics

`edt_projects` reports model state in each available project `diagnostics` entry. `pendingReasons`
lists conditions such as a model lock or pending derived data. `derivedData` reports the pipeline
stage and the work EDT exposes for active segments. `backgroundJobs` is scoped to the workspace.

`edt_project_errors` reports `diagnostics.pendingReasons`, workspace jobs, validation completion
and the observed problem count. An object check can finish while workspace jobs are still active.
Read again with `refresh` and `baseline` omitted to sample the current state. Use `complete` to assess the
result. For a refresh, also check `refreshed.settled`.

Observations use epoch milliseconds. `firstObservedAt` marks the first sample in the bridge's
retained history. `unchangedSince` marks the first sample of the current value. The first sample
has `lastChangeKnown=false`. After a different value is sampled, `lastChangeObservedAt` records
when the bridge saw the difference. History is limited to the current process. Metrics that EDT
cannot provide include `available=false` and `unavailableReason`.

`GET /status` reports `resources` for the running bridge. The `jvm` section includes heap and
non-heap usage, committed memory, the maximum where defined, and the JVM's collection count and
time for each garbage collector. The `container` section reports the current process's cgroup
v2 memory usage, limits, events, pressure, CPU quota and CPU counters on Linux when readable.
The snapshot uses read-only JVM and cgroup queries. Unavailable measurements include a reason.
`processStartedAt` records when the current process started. `previousExitCause` is `unknown`.

## Validation of one object

With exact `fqn` or `modulePath`, `edt_project_errors` and `refresh=true` explicitly schedule
applicable checks for that BM object. Unresolved or conflicting addresses never expand this request
to the whole project. `validationRequested` and `validationCompleted` report scheduling and
completion. Uninspected count-only or truncated snapshots cannot establish marker freshness.

## Validation snapshots

To compare validation results, call `edt_project_errors` with `baseline="save"`, then pass the
returned `baselineId` with `baseline="compare"` after the change. Both operations refresh the scope
and wait for validation to settle even when `refresh` is omitted. Use the same project and scope
filters in both calls. Comparison returns only new problems plus resolved-problem counts and
`resolvedProblems`; `limit`, `brief` and `countOnly` control the returned presentation.
Line shifts do not turn an unchanged problem into a new one, and duplicate problems remain
separate occurrences. Incomplete or truncated input is refused. Snapshots live only in this bridge
session; restarting EDT invalidates them. The cache holds at most 16 snapshots and 200000 markers
in total, with 100000 markers per snapshot. Saving returns the IDs it evicted.
