# Changelog

**English** · [Русский](docs/ru/CHANGELOG.ru.md)

Notable changes to EDT-Bridge, newest first. Entries are grouped by day; the versions released
that day are named in the heading. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The plugin jar and the
`edt-bridge-mcp` wrapper share one version number.

Every entry ends with a link to the pull request it came from -
`([#12](https://github.com/keyfire/edt-bridge/pull/12))`. That is why changes come in through pull
requests: an entry without such a link is unfinished, because the reader has no way from the line
to the code and the reasoning behind it.

## [Unreleased]

### Added
- `edt_update_infobase` with `transport=agent` can load only changed configuration files
  (`--partial --list-file`). The full export is compared with the last successful load into the
  same infobase. Partial loading requires a matching platform version and configuration generation,
  no removed files, unchanged `Configuration.xml` and `Ext/ParentConfigurations.bin`, and changes
  to no more than half of the export. Otherwise the configuration is loaded in full; unchanged files require no
  load. Extensions are always loaded in full, and `fullLoad=true` requests a full configuration load.
  If a partial load fails, the bridge attempts a full load.
  The load record is stored in workspace metadata and remains available after agent and EDT
  restarts. The response includes `loadMode`, `loadReason`, file counts, operation durations,
  `loadRecord`, `loadRecordState` and `loadRepeated` for a load repeated after a lost connection.
  In the author's test with about 6,600 files, loading a changed module took seconds, compared
  with about 90 seconds for a full load; export time is additional.
  ([#46](https://github.com/keyfire/edt-bridge/pull/46))

## 2026-10-05 – 0.27.3

### Fixed
- **MCP startup no longer times out on Windows when EDT is not running.** The wrapper probes
  ports in parallel and returns the tool list while EDT starts in the background. A slow or
  non-HTTP service on a higher port no longer delays or breaks discovery of a working bridge.
  ([#22](https://github.com/keyfire/edt-bridge/pull/22))

## 2026-09-23 – 0.27.2

### Changed
- Python comments and docstrings consistently use hyphens. Ordinary string literals remain unchanged.
  ([#21](https://github.com/keyfire/edt-bridge/pull/21))

### Added
- **The documentation guard catches a sentence that explains a change by naming who asked for
  it.** The repository has one author, so that sentence gives the reader nothing to act on and
  suggests the code was written for somebody else; what belongs there is what the previous
  behaviour or text got wrong. Both editions of the pages and documents are read, and with them
  the comments of `python`, `scripts` and the Java of the plugin. The table comes from
  `docsguard@v0.10.0` and catches a turn of phrase rather than a word, so a form's owner and the
  owner's explicit override stay quiet. ([#20](https://github.com/keyfire/edt-bridge/pull/20))
- **The conventions guard requires a process started from `python/src` to name its stdin.** The
  check reads the shipped wrapper alone. A generator and a test run from a console, and a console
  stdin is safe to hand on. ([#19](https://github.com/keyfire/edt-bridge/pull/19))

### Fixed
- **A child process of the wrapper no longer inherits the stdin the MCP client speaks over.** On
  Windows a child holding that handle never reaches its own exit. Whether that hurts depends on
  the command. `tasklist`, `powershell` and `taskkill` close their end and leave, so the process
  lookup was unaffected, but pip did not exit during plugin self-update. Inside a live
  server `pip --version` answered in 0.3 seconds with an empty stdin and hung to the timeout
  without one. Six process starts in `python/src` pass `stdin=subprocess.DEVNULL` now.
  ([#19](https://github.com/keyfire/edt-bridge/pull/19))

## 2026-09-12 – 0.27.1

### Added
- **`python/tests/test_conventions.py` detects a test shadowed by another test with the same name.**
  Python keeps the last definition, so a new test can replace an existing one. The total test count
  can still rise when other tests are added. The [docsguard](https://github.com/keyfire/docsguard)
  check scans `python/tests` and reports the line of the new definition to rename.
  ([#18](https://github.com/keyfire/edt-bridge/pull/18))
- **`scripts/changelog-link.py` writes a pull request link into both changelog editions.** It takes
  the number, links every entry of the topmost section that lacks a link, moves the link to a
  continuation line where the entry already fills the width, and rebuilds the mirrored pages.
  Writing that by hand into two files is how one of them ends up wrong.
  ([#17](https://github.com/keyfire/edt-bridge/pull/17))
- **`python/tests/test_conventions.py` fails on a text file written without `newline=""`.** Text
  mode otherwise translates every line feed into the platform's ending. The check comes from the
  shared [docsguard](https://github.com/keyfire/docsguard) and reads `python/src` and `scripts`; a
  test writes into a temporary directory, so the test folder stays out of it.
  ([#16](https://github.com/keyfire/edt-bridge/pull/16))
- **`python/tests/test_headless_batch.py` reads the bytes of the batch file back.** Comparing
  strings walks straight past a doubled line ending, and that is what the file carried.
  ([#16](https://github.com/keyfire/edt-bridge/pull/16))
- **The jargon check now reads the wrapper's Russian help.** `scripts/check_docs.py` names the
  message catalog `python/src/edt_bridge_mcp/i18n.py` to the guard. Help text reaches a terminal the
  way a page reaches the site, and until now no check read it.
  ([#15](https://github.com/keyfire/edt-bridge/pull/15))
- **`python/tests/test_conventions.py` catches a process read as text without an encoding.** It
  parses the sources with `ast`: a text search walks past the `(run or subprocess.run)(...)` shape.
  The check comes from the shared [docsguard](https://github.com/keyfire/docsguard).
  ([#11](https://github.com/keyfire/edt-bridge/pull/11))
- **The Russian pages are checked for transliterated jargon.** `scripts/check_docs.py` takes the
  dictionary from the shared guard and fails on words like `прогон` and `воркспейс`. The guard finds
  `docs/*.ru.md` by itself, so the script names `docs/ru/*.ru.md` and `python/README.ru.md` as well.
  A word the text quotes as a word goes in backticks, and the check walks past those.
  ([#14](https://github.com/keyfire/edt-bridge/pull/14))

### Changed
- **`ci` installs `docsguard@v0.9.0` instead of `v0.8.0`.** In that release the source checks read a
  file as `utf-8-sig`. Editors on Windows write a byte-order mark without being asked, `ast`
  answered it with a `SyntaxError`, and one such file took down the findings from every other file
  with it. ([#18](https://github.com/keyfire/edt-bridge/pull/18))
- **The two coverage checks in `scripts/check_docs.py` use the shared guard.** Previously each
  check computed its own set difference. The environment-variable check had already lost the empty
  extraction check present in the tool check. `coverage_problems` checks differences in both
  directions and an empty extraction. ([#17](https://github.com/keyfire/edt-bridge/pull/17))
- **The environment-variable check includes names bound to constants.** This covers
  `EDT_BRIDGE_LANG` and `EDT_BRIDGE_PLUGIN_INDEX`, which the check previously missed. It also checks
  the reverse: a variable described on the installation page must exist in the code.
  ([#17](https://github.com/keyfire/edt-bridge/pull/17))
- **`ci` installs `docsguard@v0.8.0` instead of `v0.7.1`.** That version checks string literals in the sources. The dictionary also gained six words:
  `дашборд`, `бэкенд`, `лаунчер`, `мейнтейнер`, `топ-объект`, `легаси`.
  ([#15](https://github.com/keyfire/edt-bridge/pull/15))
- **`ci` installs `docsguard@v0.7.1` instead of `v0.4.0`.** That version added the jargon dictionary.
  ([#14](https://github.com/keyfire/edt-bridge/pull/14))
- **The Russian help uses `рабочая область` instead of the transliterated `воркспейс`.** It
  covers `--workspace` and the description of the `gui` command. A person reads that text in a
  terminal, which is no place for a transliteration.
  ([#13](https://github.com/keyfire/edt-bridge/pull/13))
- **The documentation is rewritten in plain language.** Long sentences are cut up, mid-sentence
  parentheticals and shouted words are gone, and the Russian edition drops its transliterations. The
  English edition reads as English rather than as a translation of the Russian.
  ([#13](https://github.com/keyfire/edt-bridge/pull/13))
- **The shared guard is installed by tag, not from `@main`.** On a branch pin, a `docsguard` commit
  landed in a run here in the middle of unrelated work, and a red run caused by no commit of ours is
  one nobody reads. `ci` now installs `docsguard@v0.4.0`, and raising the pin is a pull request of
  its own. ([#11](https://github.com/keyfire/edt-bridge/pull/11))

### Fixed
- **`.gitattributes` specifies line endings for the repository.** The `* text=auto eol=lf` line
  stores and checks out text files with LF regardless of the machine's settings. The `newline=""`
  option in Python generators controls writing, but does not remove carriage returns that
  `scripts/sync-docs.mjs` can copy from the tool catalogue page into both READMEs.
  ([#18](https://github.com/keyfire/edt-bridge/pull/18))
- **The batch file of the Windows auto-start gets one carriage return per line.** Its lines are
  joined with CRLF, and `write_text` in text mode translated each of those again, so every line went
  to disk as `\r\r\n`. Two writes beside it had the same shape: the pipx metadata the self-update
  corrects, and the staged copy of an SVG the diagram renderer hands to a browser.
  ([#16](https://github.com/keyfire/edt-bridge/pull/16))
- **Two processes read as text now name their encoding.** They are the POSIX half of the process
  lookup and the pip run behind a plugin update. Without an encoding the decode raises inside the
  reader thread, the output comes back empty, and the guard reads that emptiness as "no such
  process". ([#11](https://github.com/keyfire/edt-bridge/pull/11))
- **The entry about the Russian command help now quotes both words in backticks.** It named
  `воркспейс` while telling how that word had been replaced, and it became the first finding of the
  new check. ([#14](https://github.com/keyfire/edt-bridge/pull/14))

## 2026-09-11 – 0.27.0

### Fixed
- **Stopping an unavailable agent no longer waits for SSH reconnection.** A forcibly terminated
  agent can leave a Designer session holding the infobase lock. A graceful stop needs an active
  SSH session. Previously, a lost session caused a full reconnection cycle. The measured stop time
  fell from 35.6 to 0.1 seconds. ([#8](https://github.com/keyfire/edt-bridge/pull/8))
- **Stopping a terminated agent closes its session and removes its directory.** Previously, an
  agent whose process had exited was removed from the registry. `stop` returned "no agent is running for X".
  The directory recording its Designer session remained on disk, and the session
  held the infobase lock. `stop` now ends the session, removes the directory, and reports the
  result. ([#9](https://github.com/keyfire/edt-bridge/pull/9))

## 2026-09-10 – 0.26.0

### Fixed
- **A running agent does not execute a call for a different infobase user.** Credentials are
  bound to the agent at startup and used by its SSH session. Previously, `infobaseUser` on later
  calls was ignored. The call ran as the agent's user, or failed with `Auth fail` without identifying
  the user or cause if the agent had started without one. A request for another user is now refused
  with the active identity and instructions for changing it. A call without a specified user still
  uses the running agent. Authentication failures also include a hint unless the agent log identifies
  the configuration lock as the cause. ([#4](https://github.com/keyfire/edt-bridge/pull/4))
- **`transport=agent` substitutes the project's associated infobase.** `infobase` is documented as
  optional and the EDT synchronization route has always filled it in from the project, but the agent
  route answered a well-formed call with "an infobase name, uuid or address is required". ([#4](https://github.com/keyfire/edt-bridge/pull/4))
- **Stopping an agent removes its directory after the process exits.** When the platform disconnected
  SSH because it could not acquire the configuration lock, `stop` terminated the agent and removed
  its directory immediately. The process still held `agent.log`, leaving an `edtbridge-agent-*`
  directory in `%TEMP%` with an empty log and no ownership record. In one measurement, the process
  exited 150 ms after the record was removed. `stop` now waits for the process and retries removal
  for up to five seconds. On repetition, the directory was gone when stop returned.
  ([`1795fc6`](https://github.com/keyfire/edt-bridge/commit/1795fc6))
- **The agent listing distinguishes directories left by the current and previous bridge processes.**
  Previously, every directory was reported as "left over from an earlier bridge process". A directory
  created by the current process and not removed at stop can indicate that its agent still holds a
  Designer session and the infobase configuration lock. The bridge tracks directories it creates.
  The response identifies their origin in `origin` and counts the two categories separately.
  ([`1795fc6`](https://github.com/keyfire/edt-bridge/commit/1795fc6))
- **The caller supplies answers to platform questions.** Previously, no question handler was
  registered. For example, a locked infobase could prompt for cancellation or retry; for a change
  without table structure changes, it could also offer a dynamic update while sessions continued.
  The operation failed with "question delegate has not been specified" under every
  `sessionTermination` value, which controls a different branch. Dynamic updates were unavailable,
  and a code-only change required a maintenance window. A handler is now registered for every
  session. Without `answer`, the question and options are returned without an answer;
  `answer=<value>` selects an option. The bridge does not select one automatically because an option
  can terminate other users' sessions. ([#6](https://github.com/keyfire/edt-bridge/pull/6))
- **Errors after answering a platform question are distinguished from invalid answer choices.**
  Previously, an operation could fail after accepting an answer and report that the choice was not
  offered, hiding the platform error. The messages now distinguish these cases and identify the
  cause. The `sessionTermination` description also explains that `force` cannot resolve the lock
  when background jobs reconnect within a minute; use a maintenance window
  (`edt_infobase_maintenance`) in that case. ([#6](https://github.com/keyfire/edt-bridge/pull/6))
- **`edt_infobase_config_state` does not treat an empty structure-change list as proof that the
  database configuration is applied.** A change without table changes produces an empty list even
  when sessions still run old code. The response reports only the confirmed state.
  ([#6](https://github.com/keyfire/edt-bridge/pull/6))
- **The status page runs tools when a token is configured.** Previously, the tool list was requested
  at page load before a token could be entered. A 401 response became an empty list without a
  message, leaving only the "Expand all" and "Collapse all" buttons. Entering a token saved its
  value without requesting the list again, so tools could not be run. The list is now requested
  again after token entry, and HTTP failures are reported separately.
  ([#5](https://github.com/keyfire/edt-bridge/pull/5))

## 2026-09-09 – 0.23.0, 0.24.0, 0.25.0

### Added
- **`status` reports the jar version in dropins alongside the running bridge version.** A headless
  EDT started before the jar was replaced continues to run the previous code. For example,
  `/status` returned 0.21.0 when dropins already contained 0.22.0. The wrapper reads the jar from
  disk and returns `jarOnDisk` with its version, path, and `current` flag. When versions differ,
  the command reports that EDT must be restarted. `self-update` also reports both versions.

### Fixed
- **A slow tool no longer holds the requests behind it.** The wrapper read one frame from stdin,
  ran it to the end and only then read the next: while `edt_project_errors` refreshed a project,
  built it and waited for validation to settle, everything the client sent meanwhile sat unread
  and timed out - even though the bridge itself was answering other calls in under a second
  (measured: light calls returned in 0.4-1.4 s throughout a 15-second run over a large applied
  configuration). Requests are now served on a small pool (8 at a time). Measured on the
  same configuration: `edt_project_errors` took 11.5 s while `edt_projects` answered in 0.34 s
  and `ping` in 0.30 s, both of them behind it in the stream.
- **The shared options are accepted before the command, as the help promises.**
  `edt-bridge-mcp --port 8770 status` answered "unrecognized arguments: status" - the command was
  looked for in the first position only, while the help states that the connection options belong to the
  server mode and to every command, which reads as "in any order". The command is now found
  wherever it stands; an option that takes a value is skipped together with its value, so a port
  number is never mistaken for a command name.

### Fixed
- **A filter for a form named `Форма` excludes findings from other forms of the object.** An EDT
  marker uses an object presentation (`ВнешняяОбработка.Проба.Форма.Контроль.Форма.Модуль`).
  Previously, the filter matched the object and form names as segments in any position, so the
  form word also matched a sibling form. In a test with forms `Форма` and `Контроль` and one
  error in each, `...Form.Форма` returned 7 of 8 findings, including four from `Контроль`. The
  object, form type and form name segments must now appear consecutively and in order. The form
  type is recognized in both languages, including the plural used in paths.

- **A running agent from the requested version line can be reused.** Previously, reuse compared
  the agent against the newest installed build of that line. A server on 8.5.1.1302 with
  8.5.1.1464 installed rejected its own agent for `8.5.1` and suggested a restart on the
  incompatible build. A line now accepts any build in that line; a specific build accepts only
  itself. Remaining errors identify the build a restart would select. Reuse of a suitable agent
  no longer scans installation directories.

- **Installation directories are scanned once per call.** Previously, if no suitable installation
  was found, `rac`, `ibcmd` and the configurator agent scanned twice: to select a build and to list
  installed versions in the error. The agent also scanned when reusing a running agent. Both
  results now use one scan.

- **`edt_infobase_maintenance action=end` clears `sessions-deny`.** Previously the flag was
  cleared only if `sessionsDeny` was passed again to `end`. Arguments from the separate `begin`
  call are not retained. `end` reported "denial flags lowered" while access remained denied; the
  actual state was available in `flags`. `end` now clears the flags that `begin` can set and
  reports any flag that remains set.
- **An SSH login error reports an infobase lock.** When another configurator held the configuration
  lock, `edt_update_infobase transport=agent` returned `AuthenticationException` and "Auth fail"
  even though the cause was unrelated to credentials. The platform's explanation remained in the
  agent's `/Out` log. A refused login now checks the log, reports the lock and lists running
  thick-client processes.

- **An `fqn` form filter excludes other forms of the object.** Previously, the filter used only
  the object name, so a request for form Контроль also returned findings for form Форма. The
  filter now matches both the object and form names.

- **`self-update` tells a failed add-on from a failed update.** The jar and the wrapper reaching
  0.22.0 while the plugin step failed on an unreachable source exited 1, exactly like a run that
  updated nothing: the exit code could not answer the question a caller has. It now separates them –
  `0` everything asked for, `1` the bridge itself did not update (or nothing did), `2` the bridge is
  current and only a wrapper plugin is not – and closes with a line naming each step's outcome.

## 2026-09-08 – 0.22.0

### Fixed
- **`platformVersion` pins a build when it is written out in full.** Four digits were truncated to
  the line before anything was looked up, so `8.5.1.1302` resolved to the newest 8.5.1 build
  installed and the configurator died at the stand with "Несоответствие версий клиента и сервера" –
  the build a server infobase requires was the one thing a caller could not ask for. Four digits
  now admit that build alone; three (`8.5.1`) still mean the line and its newest build. The rule is
  one for the configurator agent, `rac` and `ibcmd`: a running agent serves only when it is the
  build a fresh start would have chosen, a request that is neither a build nor a line is refused
  instead of read as a line, and a refusal names the builds that ARE installed.

## 2026-09-06 – 0.21.0

### Added
- **`edt_project_errors` reports marker freshness.** Previously, the report did not distinguish
  old markers from current ones. Refreshing required `edt_clean_project` and a full rebuild, which
  could take minutes for one module in a large configuration. Each problem now has `stale` when its
  marker predates the file's last disk change, and `unsynchronized` when the workspace has not read
  that change. The summary includes counts, and `hint` suggests actions. `refresh=true` rereads the
  selected scope and runs an incremental build before reading markers; for one module this takes
  seconds.

## 2026-09-05 – 0.20.0

### Fixed
- **`edt_project_errors` accepts multiple severities: `severity: "ERROR,WARNING"`.** Previously,
  a comma-separated value matched no severity and returned zero results in a workspace with
  13 698 problems. Filtering only `ERROR` also omitted EDT's warning for a call to an undefined
  method.

## 2026-09-02 – 0.19.0

### Added
- **`edt_validate_query` checks temporary tables across a query batch.** Previously, EDT validated
  individual queries against metadata without considering the entire batch. A query could read a
  `ПОМЕСТИТЬ` table that earlier queries had not created or had already dropped, and the error
  appeared only on the platform. The bridge now tracks table creation and removal and reports the
  read position. It returns an ERROR for `queryText`, which is treated as a self-contained batch,
  and INFO for a module literal, where a shared temporary table manager or another literal may
  create the table. In a tested configuration, all 195 such reads in 3125 literals were valid.
- **`edt_project_errors` has a `brief` form.** It returns one line per problem with severity,
  resource and line, message, and check ID, below a one-line summary. Full objects with
  `extraInfo` and paths are omitted in this mode.

### Changed
- **The tools page describes `edt_project_errors` results.** EDT reports a call to a missing
  method in another module as warning `SU239`. An undefined call without a module name is an error.
  The `modulePath` filter returns only Eclipse markers, so checking generated code requires
  warnings and the `fqn` filter. This was verified against a running model where the earlier report
  had not indicated the problem.

## 2026-08-30 – 0.18.1

### Changed
- **The package description includes the bridge's capabilities.** The PyPI summary previously
  described only the wrapper forwarding requests to a running EDT. It now names diagnostics,
  metadata, query validation, write tools, infobases, and debugging.

## 2026-08-28 – 0.18.0

### Added
- **Plugins can call a running bridge.** A handler with a `bridge` parameter
  (`handler(arguments, bridge)`) receives a callable that invokes a tool and returns its result
  text. The call does not start EDT. If the bridge is unavailable, the callable raises an exception
  with a message, and the plugin returns the message without waiting for a headless EDT to start.
  The MCP server and the `call` command both pass the callable. For example, a documentation plugin
  can search its bundled books and the bridge's Syntax Helper together.
- **`self-update` updates the wrapper plugins too.** Every distribution publishing
  `edt_bridge.tools` entry points is updated through pip from the source it was installed
  from – its git repository (read from PEP 610 `direct_url.json`), or a package index by
  project name, with `EDT_BRIDGE_PLUGIN_INDEX` naming the index. pip is reached the way the
  environment allows: `pipx runpip` for a pipx venv (works on uv-built venvs that have no
  pip module), the venv's own pip otherwise; unlike the wrapper itself, a plugin has no exe
  for a running client to hold, so pip's route is safe here. `--plugins-only` limits the run
  to the plugins; each one is reported as `name: old -> new`.

## 2026-08-27 – 0.17.0

### Added
- **The wrapper accepts plugins.** External packages installed in the wrapper environment
  (`pipx inject edt-bridge-mcp <package>`) declare MCP tools through the `edt_bridge.tools`
  entry-point group. The wrapper lists and runs those tools even when EDT is not running.
  Plugins can provide reference material under a third-party license or tools for an internal
  service. The `plugins` command lists plugins and explains loading errors;
  `EDT_BRIDGE_NO_PLUGINS=1` disables discovery. A failing entry point, duplicate tool name or
  name that shadows a wrapper tool causes a discovery error. The MCP server continues serving
  the bridge and reports the error to stderr.

## 2026-08-26

### Added
- **`edt_validate_query` reads queries from a module.** Specify a module with `modulePath` and,
  optionally, one `method` instead of pasting the query text. The tool extracts query literals
  framed with `|` from the model, removes the framing and validates each query. One call checks
  the module's queries against their current text. A query must be a framed multiline literal
  whose first word is ВЫБРАТЬ or SELECT. Single-line captions and parts of concatenated queries
  are not recognized.

## 2026-08-23 – 0.15.0, 0.16.0

### Added
- **`edt_add_form_handler`** registers an event handler on a form or on one of its items – the
  `handlers` entry in `Form.form` without which the platform never calls the procedure. The
  allowed events come from EDT, and the optional stub contains the event's signature and
  directive.
- **`edt_modify_form_item` renames an item** (`newName`) – nothing could: `edt_rename` works on
  metadata, not on the pieces of a form.
- **Infobase credentials are in the schema of `edt_infobase_sessions`** – `infobaseUser` and
  `infobasePassword` were read but not declared, so no caller could find them.

### Changed
- **The form tools agree on one parameter name: `formFqn`.** `edt_form_structure` and
  `edt_form_render` called it `fqn` while everything that writes a form called it `formFqn` –
  cosmetic until an argument outside the schema started refusing the call. The old name is still
  read, and both answers now carry `formFqn` next to the old `fqn` key.

### Fixed
- **`shutdown` detects a session after its port closes.** The CLI can outlive the framework it
  hosted and retain the workspace lock. Previously, it answered "nothing to shut down" and the
  next start failed because of that lock.
- **`--json-file` accepts a file with a byte order mark**, which is what the Windows shells
  write by default.
- **`edt_dump_external_object` falls back to the on-disk route** when the EDT dumper refuses,
  for example for an object bound to a base configuration. It also restores auto-dump generation
  if the refusal disabled it. Use `route` to select the route explicitly.
- **`scripts/sync-docs.mjs` survives a CRLF checkout** – it used to leave the page frontmatter
  inside the README block, which then read as stale.
- **An argument outside the schema rejects the call.** Previously, the argument was ignored:
  an erase requested with `deleteContents` kept the files but returned a success response. The
  error now suggests a similar name and lists the accepted arguments.
- **The launchers report the EDT installation they use.** `run-headless.ps1` and `run-gui.ps1`
  print the selected installation, its dropins, and the bridge jar name and time. They warn if
  dropins contains two jars (Equinox loads an arbitrary one) or the jar in `build/` is newer than
  the installed jar. This helps identify a jar installed in another EDT copy.

## 2026-08-17 – 0.14.0

### Added
- **OBJECT types in the form-attribute grammar.** The tool parses
  `ВнешняяОбработкаОбъект.X`, `СправочникОбъект.X` and other object types, allowing it to add
  the main form attribute. Previously, only reference types parsed, `edt_add_form_attribute`
  returned "type does not parse", and the attribute used to reach the object module had to be
  added to `Form.form` by hand.

### Fixed
- **A table's title no longer lands on every generated column.** EDT hands the new-item
  descriptor to each column it generates from the bound attribute, so a table titled "Rates"
  came out with that same caption on all of its columns. The table is now created untitled and
  titled afterwards; the columns keep the default EDT gives them.

## 2026-08-16 – 0.13.0

### Added
- **`edt_import_project` registers an existing project directory in the workspace.** It is the
  programmatic equivalent of "Import existing project". Previously, the base project from a
  worktree of the target release, used to validate an extension in modification-and-control mode,
  had to be added through the GUI. A separate project name allows two checkouts of one repository
  to coexist. Files on disk are not rewritten.
- **Every `EDT_BRIDGE_*` variable is documented**, in two tables on the install page – the ones
  the wrapper reads at its start and the ones the plugin reads inside EDT, each with its flag or
  launch property and its default. `EDT_BRIDGE_PORT_SCAN` and `EDT_BRIDGE_WINDOW_WAIT` were
  readable and named nowhere at all; the rest lived only in the wrapper's README, off the site.
- **A guard for the documentation** – `scripts/docsguard.py`, run by the wrapper's suite and so
  by CI. It fails on a tool that has no row on the tools page, a page mentioning a tool that no
  longer exists, a variable the code reads and no page documents, a README block left stale, and
  an image no file backs. Each finding is provoked in the tests, because a check that quietly
  stops finding anything looks exactly like a clean repository.

### Changed
- **The tool catalogue has a single source.** Previously it was maintained by hand in the README
  and on the site page. `docs/tools*.md` is now the source; `scripts/sync-docs.mjs` inserts it
  between markers in both README editions.
- **The diagrams match the site theme.** Their palette moved into CSS variables with a
  `prefers-color-scheme` branch, so the site shows a picture that matches the page around it.
  README files still use PNG with a fixed palette independent of the
  machine rendering it: `scripts/render-diagrams.py` (replacing `render-diagrams.sh`) forces the
  palette before the screenshot.
- **The architecture diagram is on the front page.** Previously, it was available only from the
  READMEs on GitHub.

- **The security page no longer duplicates its content.** The README list and the threat model
  that repeated it were replaced by one list. Port settings link to the environment variables.

### Fixed
- **`edt_clean_project` no longer calls a count final before validation has run.** It waited
  for the problem count to stop changing, but EDT's checks are not part of the build job
  families it joins: right after a clean the count sits at zero simply because nothing has
  been reported yet, and "0 problems, settled" is the worst possible answer there – that
  number is what clean was called for. The wait now also requires the workspace to be idle,
  and the result reports whether validation ran.
- **`edt_build_extension` creates the directory of the file it writes.** `ibcmd` does not,
  and its refusal reads like a build problem rather than a missing folder. The dry-run plan
  now names the directory it is going to create.
- **The delivery diagram on the tools page follows the site theme.** Previously the page displayed
  the PNG with its fixed README palette, so the light theme showed a dark diagram. The page now
  uses SVG, and `scripts/sync-docs.mjs` substitutes PNG for the README. The documentation guard
  checks the file selection.
- **The README's copy of the tool catalogue had drifted from the site page** – `edt_designer_agent`
  had grown its `sweep` action and idle timeout, and the wrapper's own `edt_open_gui` had never
  been listed there at all. Both were only on the site; the two are one text now.

## 2026-08-07 – 0.12.0

### Added
- **`edt_project_errors` reports the directory of each validated project.** The directory is
  included in the report's `locations` object and described in the tool reference. EDT validates
  the registered directory, so someone editing a parallel checkout could receive a report for the
  other copy with matching file names. The path now makes that difference visible.
- **`edt_symbol_info` returns the inferred types of a local variable.** Previously, a variable
  reference returned `types: []` even when the editor showed its types. The tool now installs the
  tree type system on the module on demand and performs one inference pass per request. On a
  method-access name, the response includes `invocationTypes` with the inferred call types next
  to the declared return type of the access. Previously, callers could obtain them only at commas
  and closing parentheses.

### Changed
- **Building from source picks a JDK matching the EDT bundles by itself.** Newer EDT ships
  Java 25 class files, and compiling against that pool needs a JDK able to read them: the
  build script now reads the class-file level from the pool and finds a suitable JDK on its
  own, the one installed alongside EDT included, instead of dying on a too-old JAVA_HOME.
  The jar keeps targeting Java 17, so a single build still loads in EDT versions that run
  on Java 17.

## 2026-07-31 – 0.11.2

### Fixed
- **`self-update` detects a release immediately after publication.** Previously, the wrapper
  fetched its file list from PyPI JSON metadata, which can lag behind publication by minutes.
  During that interval the command reported "already current", or "no wheel" for an explicit
  version. It now uses the PEP 691 SIMPLE index. Releases are ordered numerically (`0.9.0` before
  `0.11.1`), excluding pre-releases and yanked files. JSON remains the fallback for an index without
  PEP 691. The change affects the wrapper; the jar still comes from GitHub release assets.

## 2026-07-31 – 0.11.1

### Fixed
- **`self-update --from` accepts the repository root.** Previously, the command required the
  `python/` subdirectory containing the wrapper. Passing the root returned "no edt_bridge_mcp package".
  The command now checks all three supported layouts and reports the paths checked when
  the package is absent.

## 2026-07-30 – 0.11.0

### Added
- **`edt_open_gui` transfers the workspace from headless EDT to GUI EDT.** Previously, the bridge
  could stop EDT, but opening a window required manually ending 1cedtcli and starting EDT. The tool
  stops headless EDT, waits for its processes to exit, and starts GUI EDT on the same workspace.
  A live check showed that `/shutdown` alone did not end the session: the OSGi framework stopped
  and the port closed, but 1cedtcli continued running with a keepalive pipe on stdin. After EDT
  stops, the keepalive shell is closed, the CLI receives EOF, and the process exits. Waiting for
  the port is insufficient because the runtime may still hold the workspace lock. `force` ends
  processes that do not stop in time, beginning with the parent keepalive shell: terminating the
  process tree from the CLI cannot reach its parent. The wrapper serves
  the tool because a tool inside EDT cannot return a result after stopping EDT. The command-line
  equivalent is `edt-bridge-mcp gui`.
- The window is brought to the foreground; a repeat call raises it again. Previously, a detached
  process had no foreground rights, so the window could open behind others. The launch is no longer
  detached. The window is found by traversing the launcher's process tree and belongs to javaw using
  the JDK resolved by the installation, which need not be inside the EDT directory. A large
  workspace can take minutes to load. The window wait is bounded (`EDT_BRIDGE_WINDOW_WAIT`,
  90 seconds), and a missing window is reported.

### Fixed
- **The wrapper detects EDT processes on localized Windows.** `tasklist` returns output in the
  console OEM code page, but the wrapper decoded it as UTF-8. The decoding error left an empty
  process list, so the guard against starting headless EDT while GUI EDT was running did not work.
  The output is now decoded with the console code page.

## 2026-07-27 – 0.10.0

### Added
- Each configurator agent records the infobase, process, and cluster session ID in its
  `/AgentBaseDir`. A normal stop removes the directory; a crash leaves it behind. If the agent
  process has exited, the bridge uses the record to end its session and remove the directory.
  An orphaned session holds the configuration lock, causing the next call to fail with
  "the infobase could not be locked for configuration" until the session is terminated. Ownership
  is established from the record; the session host and user are insufficient because EDT also
  starts configurator agents that appear identical to the cluster. Cleanup runs before every agent
  start and on request with `edt_designer_agent action=sweep`. `action=list` reports leftovers.
  `stopRunning=true` also stops running agents from a previous bridge process; it is off by default.
- An agent for a server infobase stops after an idle period. While running, it occupies a client
  license and a Designer session. Configure the timeout with `edt.bridge.agent-idle-minutes`,
  `EDT_BRIDGE_AGENT_IDLE_MINUTES`, or the `agentIdleMinutes` preference. The default is 30 minutes;
  `off`, `0`, or an unrecognized value disables the stop. The agent lock is held during stopping,
  so an operation in progress is not interrupted. `edt_designer_agent action=list` reports each
  agent's idle time and the configured timeout.

### Fixed
- Stopping an agent closes its cluster session. Previously the process was killed immediately
  after a graceful stop was requested, before it could close its infobase connection. The session
  remained in the cluster, and the open log could prevent removal of the agent directory. The process
  is now given 20 seconds to stop before forced termination. If it must be terminated, the session is
  closed explicitly.

## 2026-07-24 – 0.9.0

### Added
- `edt_infobase_maintenance` manages a maintenance window for database configuration updates
  through `rac`. `begin` sets `scheduled-jobs-deny` and optionally `sessions-deny` with a
  permission code, waits until only allowed applications remain, and returns "clear to update".
  `end` clears the flags; `status` reports their state. BackgroundJob sessions are recreated every
  minute on a running infobase; after denial is set, they finish within a minute. In a clustered
  infobase test, the denial stopped their recreation,
  and the queue returned within seconds after it was cleared. The configurator agent's SSH client
  cannot manage these flags; the tool uses `rac` and requires an infobase administrator.
- The command reference and its generator are under tests (`python/tests/test_cli_docs.py`):
  every argument documented, the Russian run actually Russian, the two language versions
  differing, every command covered by a page section and answering `--help` within a timeout,
  and the committed pages equal to what the generator produces. The generator itself gained
  the missing timeout – a command that does not parse `--help` starts the server and hangs.
- `edt_add_route` warns about an own URL template on an adopted extension service. EDT accepts and
  serializes it, but the platform cannot load it in extension compatibility mode 8.5.1 or earlier.
  The response includes `serviceAdopted`, the extension's `compatibilityMode`, and a warning.
  Previously the failure appeared when loading the infobase.
- `edt-bridge-mcp shutdown` gracefully stops the EDT serving the bridge. The wrapper sends a
  token-protected request to `/shutdown`, which stops the OSGi framework in order. The workspace
  `.lock` is released for GUI startup and configurator agents close their cluster sessions. A
  forcibly terminated process can leave a session holding the configuration lock. GUI EDT
  requires `--force` to prevent accidental window closure. If the bridge is not running, the
  command completes without action.

### Changed
- A fully applied `edt_infobase_sessions terminate` returns terminated session IDs and
  `terminatedCount` instead of the full list. Previously, the list was read before termination,
  so it included ended sessions and could be large. `list`, dry-runs and partial failures still
  return the list.

- The `edt_delete_object` description explains delete-cascade construction. References are
  analyzed across all open projects, so the operation can take minutes in a large workspace and
  continue after the client's timeout. During a check, the first deletion in an extension project
  took five minutes; the cascade finished in the background and left the model clean.
- `edt_update_infobase` (transport=edt) reports the synchronization scope. EDT synchronizes every
  workspace project associated with the infobase and pulls in extension projects through the
  configuration project's dependencies. The response lists projects in `syncProjects` and the
  plan names them. A multi-project update includes a warning pointing to transport=agent for loading
  one project. Previously, updating the base configuration also loaded two unrelated extension
  projects without a warning.
- A file infobase's configurator agent stops immediately after its operation. Previously, the
  agent kept the infobase open, causing the next batch Designer or user to receive
  "the infobase is already opened in Designer". A server infobase retains its agent between calls as before.

- The configuration-lock error ("Ошибка блокировки информационной базы для
  конфигурирования") includes a hint to use `edt_infobase_sessions`. A terminated agent's
  Designer session can remain in the cluster and hold the lock; the original platform message
  does not identify this cause.

### Fixed
- The agent reconnects when a database restructure interrupts its infobase connection. The
  response "Соединение с информационной базой не установлено" occurs before the command runs.
  Agent-backed operations now reconnect and retry once; previously they required a manual retry
  (five times in one evening in a measurement). Other errors remain unchanged.

- `edt_infobase_sessions` no longer passes infobase credentials to `rac session list`. That command
  accepts only `--infobase` and `--licenses` and does not authenticate to the infobase. Previously,
  credentials caused "Ошибка разбора параметра: --infobase-user". The tool no longer declares or
  passes those parameters.
- Agent error messages omit repeated licensing details. Previously, an error could reach tens of
  kilobytes: the hardware inventory was repeated for each license file and lookup stage, then
  included again by the SSH client. Gateways omit inventory lines and repetitions with an
  "N lines omitted" note; other messages are retained. In a live measurement, the response shrank
  from ~25 KB to 2.4 KB, with "no license" on the first line.

- Platform response recognition supports Russian and English text from the platform's resource
  bundles: configuration-lock refusal, the agent's "not connected to the infobase" response used
  for automatic reconnection, "extension not found", and the ibcmd credential prompt. The ibcmd
  recognizer's guessed "authentication is required" was replaced with the platform's actual
  "Authentication in the infobase is required...".

## 2026-07-22 – 0.8.0

### Added
- The wrapper's CLI help is bilingual. An i18n catalogue (`ru`/`en`, picked by `EDT_BRIDGE_LANG`,
  otherwise the locale) covers flag and command descriptions, usage, the epilogue, the built-in
  argparse strings and the hand-written `self-update` help. What the tools answer to the agent
  stays English – that is the plugin's protocol surface, not text for a human.
- `edt_project_errors` reports a marker's `sourceType` and `extraInfo` – what tells the two
  validation families apart: documented checks come from the standards framework, a short code
  like `SU200` comes from EDT's own metadata validation.
- `edt_check_info` recognizes a short code and identifies it instead of returning "nothing found". There is
  no code-to-slug mapping to dig out, as 0.7.1 assumed – the families are simply different, and
  matching by title stays the way to a description.

### Fixed
- `run-headless.ps1` refused to start whenever any GUI EDT process existed, including one that was
  still exiting. It now checks the real collisions – the port, the workspace lock, the shared
  `dropins`. Two neighbours: `-Port` only changed which port was polled, so a non-default port
  started a second server on 8770; and a workspace with no projects was reported as not ready
  although the server was up.

## 2026-07-21 – 0.6.0, 0.7.0, 0.7.1

### Added
- **The configurator agent is a third transport to an infobase.** Started with `/AgentMode`, it
  keeps a session open, accepts SSH commands and authenticates **as the infobase user**. Neither
  EDT synchronization nor `ibcmd` provides this login. This makes server infobases that
  authenticate users and their extensions accessible. One agent is reused per infobase;
  `edt_designer_agent` lists and stops agents.

- `edt_infobase_config_state` checks whether a database configuration update is pending. The
  platform starts the update without confirmation, so nothing is applied and pending changes are
  returned as a list.

- `edt_update_database_config` applies the database configuration. Loading a project does not
  apply it, so sessions continue running the previous code until application. For example, a new
  HTTP route returns 404. `sessionTermination=force` denies sessions, terminates them and applies
  the configuration in one call.

- `edt_infobase_sessions` lists and terminates cluster sessions through `rac`; neither the agent
  nor `ibcmd` provides this operation. A forcibly terminated Designer can leave a session holding
  the configuration lock, causing later operations to fail.

- `edt_delete_extension` removes an extension from an infobase. The operation requires `force`
  and `apply`.

- `edt_infobase_dump` exports an infobase to `.dt` through `ibcmd`, allowing a backup before
  configuration application.

- `edt_update_infobase` accepts `transport=agent` to load Designer XML into the infobase without
  an intermediate infobase or `.cf`. This route supports configurations with a million objects.

- `edt_extension_properties` accepts `infobase` and uses the agent to access extensions in a server
  infobase.

- `edt_add_route` – add a route to an `HTTPService`. It used to mean editing the `.mdo` by hand and
  generating the uuids of the `urlTemplates` block and its nested `methods`.
- The ibcmd-backed tools take the 1C infobase credentials next to the DBMS ones. Without them ibcmd
  prompts for a user name on stdin – for a non-interactive caller a hang, not an error, and it
  ignores EOF (134 MB of prompts in 60 seconds, measured). Output is now read incrementally and the
  process killed the moment a prompt appears.
- Agent-backed tools accept an infobase not registered in EDT: `srv\base` or a file infobase
  directory.
- Each ibcmd invocation gets its own working directory; without it they lock the same one and the
  next invocation anywhere fails.

### Changed
- `edt_project_errors` supports checking a single module. It used to take a
  project name and return everything – 13 850 problems and ~5 MB on a large configuration. New
  `fqn` / `modulePath`, `severity` and `countOnly`, plus `total`, `bySeverity`, `bySource` and a
  capped list.
- `edt_module_text` takes `includeMethods` (default true): asking for one method no longer drags the
  module's entire catalogue along – 147 methods on one production module, re-sent on every read.
- `edt_module_text`, `edt_add_method` and `edt_delete_method` resolve `HTTPService.X` and
  `WebService.X`: all three were affected by one gap in the module resolver's folder map.
- The README tool tables include a section for tools that access a running infobase, with the
  transport identified for each tool. Both diagrams were redrawn.
- A test suite for the wrapper plus a `ci` workflow (Linux and Windows, 3.10 and 3.12) that both
  release workflows call first, so a red suite stops a release. The suite includes three regressions found after release.
- JUnit coverage for the EDT-independent part of the plugin, moved to `...edtbridge.core` so a plain
  JDK compiles and tests it.

### Fixed
- `self-update` left a stale jar in `dropins` when the release jar was already there – and two copies
  of one bundle are what makes Equinox resolve an arbitrary one.
- Transparent corners were removed from the diagrams. `scripts/render-diagrams.sh` now fixes the
  relevant flags, which were not previously documented.

## 2026-07-19 – 0.3.0, 0.3.1, 0.4.0, 0.4.1, 0.5.0

### Added
- Form tools: `edt_add_form` creates a managed form with the EDT generator used by the "New form"
  wizard. `edt_add_form_item` and its modify/remove counterparts use the form editor's service;
  EDT sets names, IDs, field types and automatically generated table columns. Similarly,
  `edt_add_form_attribute` and `edt_add_form_command` and their counterparts use EDT's service
  for IDs and write handler stubs to the form module. Removals list remaining references and
  require `force`.

- `edt_adopt_object` adopts a base configuration object into an extension through EDT's adopter.
  The owning object must be adopted before its method can be intercepted.

- `edt_search_modules` searches a project's BSL modules through Eclipse buffers, including unsaved
  changes in modules open in an editor.

- `edt_clean_project` – discard build results so validation runs again, the programmatic equivalent
  of EDT's "Clean". A stale marker can otherwise outlive the code that caused it.
- `edt_delete_project` – remove a project from the workspace through Eclipse, so its resource tree is
  updated. Manually deleting the folder leaves a project in the workspace that occupies the name.
- `edt_extension_properties` reads and sets extension properties **inside an infobase**: safe
  mode, protection from dangerous actions, active state and scope. A newly registered extension
  has safe mode and dangerous-action protection **on**. An extension that changes base
  configuration methods cannot run with either option enabled.

- `edt_build_extension` and `edt_update_infobase` report `changesMethods` and, when true, name the
  two flags that must be off.
- `edt-bridge-mcp` provides shell commands: `call <tool>`, `tools` and `status`. Previously,
  use outside an MCP client required a custom client for port 8770. Exit code 1 means "the call
  could not be made"; code 2 means "the tool ran and failed".

- `self-update --from <checkout>` installs the wrapper from a local checkout instead of PyPI.
- Platform and infobase tools: `edt_platform_installations`, `edt_register_platform`,
  `edt_create_infobase`, `edt_build_extension` (a `.cfe` through `ibcmd`).
- `edt_metadata_details` reports a `CommonModule`'s compilation flags and an `ExchangePlan`'s
  content – both previously sent callers to grep the `.mdo` on disk.
- `edt_create_external_object` accepts `scriptVariant`: EDT defaults a standalone project to English,
  which made generated members come out as `Object` rather than `Объект`.
- An EDT-Bridge preferences page (token, port, evaluate switch), so a GUI EDT launched from a plain
  shortcut can authenticate the write tools.

### Fixed
- `edt_create_extension` produced a project no infobase would load – it failed with *"the load must
  not change the ownership of the main configuration object"*. The root `Configuration` was built by
  the md factory, which yields a plain full configuration. It is now the **base configuration
  adopted**, the same step EDT's own wizard performs, so the engine writes everything an extension
  needs. Two consequences closed with it: adopted objects now carry the uuid link to the base object
  instead of binding by name, and the base configuration's default language comes along.
- `edt_dump_external_object` builds an `.epf`/`.erf` when EDT cannot. EDT selects the newest build
  in a version line; a line topped by thin-client builds returned `MatchingRuntimeNotFound`.
  An on-disk route is now available and described in the dry-run plan.

- Modules of external objects resolve by FQN – `ExternalDataProcessor` and `ExternalReport` were
  simply absent from the folder map.
- `self-update` updates the wrapper in a pipx environment created with uv and no pip. The wheel
  is unpacked over the package with the standard library, without an installer.

- The wrapper reported an outdated version through two releases: `__init__.py` carried a literal that
  `pyproject.toml` did not use. The package version now derives from that one attribute.
- The MCP server reports the plugin version from the bundle manifest instead of a hard-coded
  string, so the dashboard and handshake report the same version.
- The stdio wrapper pins its standard streams to UTF-8. On Windows they defaulted to the ANSI code
  page, so a "→" in a tool description aborted `tools/list` and the client registered no tools at all.
- `edt_infobases` expands infobase groups, so grouped and server infobases are no longer omitted.

### Changed
- Internal: the single model gateway was split into focused per-area gateways (project, metadata read
  and write, form, platform, debug, BSL, docs). Pure refactor.

## 2026-07-18 – 0.1.0, 0.1.1, 0.2.0, 0.2.1

### Added
- The full create / develop / build / deliver cycle exposed over MCP, with the write tools dry-run by
  default.
- The `edt-bridge-mcp` stdio wrapper (pipx) forwards requests to a running EDT, starts headless
  EDT when none is running, and installs the plugin jar in EDT's `dropins/`.

- `edt_platform_help` provides a searchable 1C:Enterprise Syntax Helper bundled with EDT in
  Russian and English.

- A multi-threaded MCP server, and a free-port fallback: if 8770 is busy the next free port is used
  and the wrapper finds it.

### Fixed
- Project-creation `apply` builds and attaches a detached Configuration or external data processor
  while respecting the headless EDT lifecycle.

### Changed
- House typography across the repository – en-dash instead of em-dash, three dots instead of the
  ellipsis character – including tool descriptions and the wrapper.

## 2026-07-11 – 0.0.1

### Added
- Initial release: read tools over MCP (projects, validation problems, metadata details and listing,
  references, query validation, forms), the built-in dashboard and the localhost MCP server.

[0.5.0]: https://github.com/keyfire/edt-bridge/compare/v0.4.1...v0.5.0
[0.3.0]: https://github.com/keyfire/edt-bridge/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/keyfire/edt-bridge/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/keyfire/edt-bridge/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/keyfire/edt-bridge/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/keyfire/edt-bridge/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/keyfire/edt-bridge/releases/tag/v0.0.1
