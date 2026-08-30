# Changelog

**English** · [Русский](docs/ru/CHANGELOG.ru.md)

Notable changes to EDT-Bridge, newest first. Entries are grouped by day; the versions released
that day are named in the heading. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The plugin jar and the
`edt-bridge-mcp` wrapper share one version number.

## 2026-08-30 – 0.18.1

### Changed
- **The package description names what the bridge gives.** The PyPI summary described the
  wrapper alone – a front-end that proxies to a running EDT – and said nothing about the
  diagnostics, metadata, query validation, write tools, infobases and debugger behind it.

## 2026-08-28 – 0.18.0

### Added
- **Plugins can reach the live bridge.** A plugin handler that declares a `bridge`
  parameter – `handler(arguments, bridge)` – receives a callable forwarding one tool call
  to the running bridge and returning the text of its result. The wrapper never starts an
  EDT for it: with no bridge up the callable raises with a readable message and the plugin
  degrades to a note, instead of hanging its caller through a minutes-long headless start.
  Both dispatch paths pass it – the MCP server and `call` on the command line. This is what
  lets a documentation plugin answer across corpora: the books it bundles plus the Syntax
  Helper behind the bridge, in one search.
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
- **The wrapper takes plugins.** External packages installed into the wrapper's environment
  (`pipx inject edt-bridge-mcp <package>`) declare MCP tools through the `edt_bridge.tools`
  entry-point group; the wrapper lists them next to the bridge's tools and dispatches them
  itself, so they answer even while no EDT is running. This is the home for what a public
  repository cannot carry – reference material under somebody's license, tools wired to an
  internal service. The `plugins` command reports what is plugged in and why a broken plugin
  refused to load; `EDT_BRIDGE_NO_PLUGINS=1` turns the discovery off. A failing entry point,
  a duplicated tool name or a name that shadows the wrapper's own tools refuses loudly at
  discovery – except in the MCP server itself, which keeps serving the bridge and reports the
  failure on stderr instead of dying with the whole tool surface.

## 2026-08-26

### Added
- **`edt_validate_query` reads the query out of the module itself.** Address a module
  (`modulePath`, optionally one `method`) instead of pasting the text: the bridge pulls the
  `|`-framed query literals out of the live model, drops the framing and validates each one,
  so the checked text cannot drift from what the module holds - and one call covers every
  query of a module. What qualifies is deliberately narrow: a multiline framed literal whose
  first word is ВЫБРАТЬ or SELECT; a single-line caption never reaches the validator, and the
  pieces of a query built by concatenation never qualify.

## 2026-08-23 – 0.15.0, 0.16.0

### Added
- **`edt_add_form_handler`** registers an event handler on a form or on one of its items – the
  `handlers` entry in `Form.form` without which the platform never calls the procedure. The
  allowed events come from EDT, and the stub it can write carries the event's own signature and
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
- **`shutdown` sees a session whose port has gone silent.** The CLI outlives the framework it
  hosted and keeps the workspace lock, and the answer used to be "nothing to shut down" - the
  next start then failed on a lock nobody was looking at.
- **`--json-file` accepts a file with a byte order mark**, which is what the Windows shells
  write by default.
- **`edt_dump_external_object` falls back to the on-disk route** when EDT's own dumper refuses –
  an object bound to a base configuration used to end there – and puts back the auto-dump
  generation if the refusal switched it off. `route` pins the builder.
- **`scripts/sync-docs.mjs` survives a CRLF checkout** – it used to leave the page frontmatter
  inside the README block, which then read as stale.
- **An argument the schema does not know refuses the call.** It used to be dropped in silence:
  an erase asked with `deleteContents` kept every file and answered like a done deed. The
  refusal names the near miss and lists what the tool takes.
- **The launchers name the installation they start from.** There are several EDT installations
  on a machine, the script picks one itself, and the swapped jar lies in the dropins of the one
  somebody wrote down: a fix that went into another installation looked like a jar that had not
  been built. `run-headless.ps1` and `run-gui.ps1` now print the installation, its dropins, the
  name and time of the bridge jar, warn when there are two of them (Equinox loads an arbitrary
  one) and when the jar in `build/` is newer than the one installed.

## 2026-08-17 – 0.14.0

### Added
- **OBJECT types in the form-attribute grammar** – `ВнешняяОбработкаОбъект.X`,
  `СправочникОбъект.X` and the rest of the family now parse, so a main form attribute can be
  added by the tool. Until now only reference types did, and `edt_add_form_attribute` answered
  "type does not parse": the attribute that reaches the object module had to be written into
  `Form.form` by hand.

### Fixed
- **A table's title no longer lands on every generated column.** EDT hands the new-item
  descriptor to each column it generates from the bound attribute, so a table titled "Rates"
  came out with that same caption on all of its columns. The table is now created untitled and
  titled afterwards; the columns keep the default EDT gives them.

## 2026-08-16 – 0.13.0

### Added
- **`edt_import_project`** – register an EXISTING project directory in the workspace, the
  programmatic form of "Import existing project". The create/work/delete cycle lacked that
  step, so a second project – the BASE one an extension in modification-and-control mode is
  validated against, taken from a worktree of the target release – could only be added by
  hand in the GUI. A name of its own lets two checkouts of one repository sit side by side;
  nothing on disk is rewritten.
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
- **The tool catalogue has a single source.** It used to be kept by hand in both the README and
  the site page; `docs/tools*.md` is the source now, and `scripts/sync-docs.mjs` writes it into
  the README between marker comments, in both languages.
- **The diagrams follow the reader's theme.** Their palette moved into CSS variables with a
  `prefers-color-scheme` branch, so the site shows a picture that matches the page around it.
  A README still gets a PNG, and which palette that PNG carries is no longer decided by the
  machine rendering it: `scripts/render-diagrams.py` (replacing `render-diagrams.sh`) forces the
  palette before the screenshot.
- **The architecture diagram opens the front page** – it used to be reachable only from the
  READMEs on GitHub, which is a strange place for the picture that explains the whole layout.
- **The security page no longer says everything twice.** It carried the README's bullet list and
  then a threat model repeating it, and pointed back at the README for "the full picture"; there
  is one list now, and the port settings link to the environment variables.

### Fixed
- **`edt_clean_project` no longer calls a count final before validation has run.** It waited
  for the problem count to stop changing, but EDT's checks are not part of the build job
  families it joins: right after a clean the count sits at zero simply because nothing has
  been reported yet, and "0 problems, settled" is the worst possible answer there – that
  number is what clean was called for. The wait now also requires the workspace to be idle,
  and the result says whether validation was seen running at all.
- **`edt_build_extension` creates the directory of the file it writes.** `ibcmd` does not,
  and its refusal reads like a build problem rather than a missing folder. The dry-run plan
  now names the directory it is going to create.
- **The delivery diagram ignored the reader's theme on the tools page.** The page embedded the
  PNG – the file whose palette is baked in for the README – so a reader in the light theme was
  served a dark picture. The page shows the SVG now, and the injected README copy gets the PNG
  by a swap in `scripts/sync-docs.mjs`: one source, the right file on each surface. The guard
  judges it, because the same slip is invisible until someone looks at the page in the other
  theme.
- **The README's copy of the tool catalogue had drifted from the site page** – `edt_designer_agent`
  had grown its `sweep` action and idle timeout, and the wrapper's own `edt_open_gui` had never
  been listed there at all. Both were only on the site; the two are one text now.
## 2026-08-07 – 0.12.0

### Added
- **`edt_project_errors` names the disk folder behind each validated project** (the `locations`
  object of the report, also spelled out in the tool description). The model validates the
  REGISTERED folder, so a caller editing a parallel checkout of the same sources used to get a
  plausible-looking report about the wrong tree - silently, since the file names match. Now the
  divergence is visible in the report itself.
- **`edt_symbol_info` answers with a local variable's computed types.** A reference to a
  variable (even one assigned on the previous line) yielded `types: []` while the editor hover
  showed the types: the variable half of the type system is INFERRED, and a bare resource load
  carries no type states. The tool now installs the tree type system on the module on demand
  (one inference pass per ask) and computes against the module's actual environments. On a
  method-access name the answer also carries `invocationTypes` - the computed type(s) of the
  containing call, next to the DECLARED return type of the access itself; callers used to probe
  commas and closing parens to reach exactly that.

### Changed
- **Building from source picks a JDK matching the EDT bundles by itself.** Newer EDT ships
  Java 25 class files, and compiling against that pool needs a JDK able to read them: the
  build script now reads the class-file level from the pool and finds a suitable JDK on its
  own, the one installed alongside EDT included, instead of dying on a too-old JAVA_HOME.
  The jar keeps targeting Java 17, so a single build still loads in EDT versions that run
  on Java 17.

## 2026-07-31 – 0.11.2

### Fixed
- **`self-update` right after a release no longer misses it.** The wrapper's file list came from
  the JSON metadata of PyPI, a cache that catches up minutes after an upload: within that window
  the command answered "already current", and with an explicit version - "no wheel", because the
  files were read from that same lagging document. The list now comes from the SIMPLE index
  (PEP 691), the newest release is ranked numerically (`0.9.0` before `0.11.1`, no pre-releases,
  no yanked files), and the JSON metadata stays as the fallback for an index that does not speak
  PEP 691. Only the wrapper half is affected - the jar keeps coming from the assets of a GitHub
  release, a different source with no such cache.

## 2026-07-31 – 0.11.1

### Fixed
- **`self-update --from` accepts the repository root.** The wrapper lives in the repository's
  `python/` subdirectory, and the command insisted on being handed that subdirectory - passing the
  root, which is what one naturally does, answered "no edt_bridge_mcp package" and turned an
  obvious command into a second attempt. All three shapes are now looked for, and a directory with
  none of them says where it looked.

## 2026-07-30 – 0.11.0

### Added
- **`edt_open_gui` – hand the workspace from the headless EDT to the GUI one.** Reaching the EDT
  window meant finding 1cedtcli in the task manager, killing it and starting EDT by hand; the
  bridge could stop its EDT but not open the next one. The tool stops the headless session, waits
  until its processes are actually gone and launches the GUI EDT on the same workspace. `/shutdown`
  alone does not end such a session, which a live run proved: it stops the OSGi framework, the port
  falls silent, and 1cedtcli keeps running because it was started with a keepalive pipe on stdin. So
  the keepalive shell is closed next - not a kill of EDT, which has already stopped itself, but the
  closing of the pipe that holds the process, after which the CLI reaches EOF and exits. Waiting on
  the port is not enough – it falls silent while the runtime still holds the workspace lock, and
  that leftover is exactly what gets hunted by hand; `force` kills what does not stop in time,
  the keepalive shell first, because it is the CLI's parent and a tree kill from below never
  reaches it. Served by the WRAPPER, not by the bridge: a tool inside EDT cannot report on the EDT
  it just ended. Also `edt-bridge-mcp gui` in the shell.
- The window is brought to the FRONT, and a second run of the command raises it again. A
  process launched detached has no foreground rights: the workbench came up behind every
  other window, which is indistinguishable from "it never started". The launch is no longer
  detached, and the window is found by walking the launcher's process tree - it belongs to
  the javaw the launcher starts, and that javaw runs from whatever JDK the installation
  resolved, which need not be inside the EDT folder at all. Loading a large workspace takes
  minutes, so the wait for the window is bounded (`EDT_BRIDGE_WINDOW_WAIT`, 90 s) and a miss
  is reported instead of endured.

### Fixed
- **A localized Windows hid every EDT process from the wrapper.** `tasklist` answers in the console
  OEM codepage, and the wrapper decoded it as UTF-8: the decode failed inside the reader thread and
  left the listing EMPTY, which every caller read as "no such process". So the guard that refuses
  to start a headless EDT while a GUI one is running never fired on such a machine, and a second
  EDT was launched onto a locked workspace. The listing is now decoded with the console codepage.

## 2026-07-27 – 0.10.0

### Added
- Configurator agents now clean up after themselves. Every agent writes a record into its own
  `/AgentBaseDir` – a directory a clean stop removes and a crash leaves behind – naming the
  infobase, the process and the id of the cluster session it opened. When a later run finds such a
  record with a dead process, it ends exactly that session and removes the directory: the session of
  a crashed agent holds the infobase's CONFIGURATION LOCK, and until now the next call failed with
  "the infobase could not be locked for configuration" until somebody found and terminated it by
  hand. Ownership is proven by the record, never guessed from the session's host and user – EDT
  starts configurator agents of its own, and from the cluster's side they are indistinguishable, so
  the obvious heuristic would end the developer's own configurator. Swept before every agent start
  and on demand with `edt_designer_agent action=sweep`; `action=list` reports what is left over, and
  `stopRunning=true` also stops agents of an earlier bridge process (off by default).
- An agent that has been idle stops by itself. A standing agent of a server infobase costs a client
  license and a Designer session for as long as it lives, which on a stand with a small pool is a
  seat taken from a person. Configured by `edt.bridge.agent-idle-minutes` /
  `EDT_BRIDGE_AGENT_IDLE_MINUTES` / the `agentIdleMinutes` preference: 30 minutes by default, `off`
  or `0` disables it – and so does an unparsable value, deliberately, because a typo must not
  silently shorten an agent's life. A call in flight always wins: the reaper takes the agent's lock
  and holds it across the stop, so an operation can never be cut off mid-way. `edt_designer_agent
  action=list` reports each agent's idle time and the configured timeout.

### Fixed
- Stopping an agent no longer leaves its session in the cluster. The polite shutdown was asked for
  and the process killed immediately afterwards, giving it no time to close the infobase connection
  – so every stop manufactured exactly the orphan described above, and the kill also held the
  agent's own log file open, leaving the base directory behind. The process is now waited out (20 s)
  before it is killed, and a session is ended explicitly when a kill was still necessary.

## 2026-07-24 – 0.9.0

### Added
- `edt_infobase_maintenance` – a maintenance window around a database-configuration update,
  through `rac`: `begin` raises `scheduled-jobs-deny` (optionally `sessions-deny` with a
  permission code), watches the session list until only the allowed applications remain and
  reports "clear to update"; `end` lowers the flags; `status` just reports. The point: on a
  lively base BackgroundJob sessions respawn every minute, so terminating them is useless –
  with the flag up they drain by themselves within a minute and nothing has to be killed.
  Verified live on a clustered infobase: raising the flag stopped the respawn and the jobs
  drained on their own; lowering it brought the queue back within seconds. The configurator
  agent cannot do this at all – its SSH client has no operation for the denial flags – so rac
  is the route, and the tool needs the infobase administrator.
- The command reference and its generator are under tests (`python/tests/test_cli_docs.py`):
  every argument documented, the Russian run actually Russian, the two language versions
  differing, every command covered by a page section and answering `--help` within a timeout,
  and the committed pages equal to what the generator produces. The generator itself gained
  the missing timeout – a command that does not parse `--help` starts the server and hangs.
- `edt_add_route` warns about the construct that will not load: an OWN url template added to
  an ADOPTED service of an extension is accepted by EDT and serialised quietly, but the
  platform refuses to load it in extension compatibility mode 8.5.1 and below. The result now
  carries `serviceAdopted`, the extension's `compatibilityMode` and a warning saying exactly
  that – before this the refusal only surfaced at infobase load, far from the tool that wrote
  the route.
- `edt-bridge-mcp shutdown` – the graceful end of the EDT behind the bridge, instead of
  killing its processes. The wrapper POSTs the bridge's new `/shutdown` (token-gated), which
  stops the OSGi framework in order: the workspace `.lock` is released for a GUI to start,
  and the configurator agents EDT itself runs close properly – their cluster sessions leave
  with them, where a killed process leaves its session holding the configuration lock. A GUI
  EDT is refused unless `--force` is passed, so a script cannot close somebody's window by
  accident; shutting down when no bridge runs is a quiet no-op.

### Changed
- A fully applied `edt_infobase_sessions terminate` answers with the terminated ids (and
  `terminatedCount`) instead of the whole session list: the list was read before the
  terminations, so the ended sessions still looked alive in it – and on a lively cluster it
  drowned the answer in noise. `list`, dry-runs and partial failures keep the list.
- `edt_delete_object` now says in its description what a live run taught: building the delete
  cascade analyses references across every open project, which takes minutes in a workspace
  with a large configuration – and the operation runs to completion even when the client
  stops waiting. The five-minute "hang" of the first extension-project delete was exactly
  that: the cascade finished in the background and left the model clean.
- `edt_update_infobase` (transport=edt) says what rides along: EDT's synchronization has no
  per-project scope – it brings the infobase in line with EVERY workspace project associated
  with it, and a configuration project drags its extension projects by dependency. The result
  lists them all in `syncProjects`, the plan names them, and a multi-project update carries a
  warning pointing at transport=agent for loading one project only. Before this, an update
  from the base configuration quietly took two unrelated extension projects with it.
- A FILE infobase's configurator agent is stopped as soon as its operation is over: the
  standing agent held the base open, so the next batch designer – or a human opening the
  configurator – was refused with "the infobase is already opened in Designer". A server
  infobase keeps its agent between calls as before; reconnecting to a local file is cheap,
  the lock is not.
- The configuration-lock refusal ("Ошибка блокировки информационной базы для
  конфигурирования") now carries a hint: the usual culprit is the Designer session of an
  agent that died – the cluster keeps the session, the session keeps the lock, and nothing in
  the platform's text points there. The hint names `edt_infobase_sessions` as the way out.

### Fixed
- The agent reconnects by itself when a database restructure drops the process's infobase
  connection: "Соединение с информационной базой не установлено" is a gate BEFORE a command
  runs, so every agent-backed operation now reconnects once and re-runs instead of failing –
  the reply used to cost a manual retry after every heavy step (measured: five times in one
  evening). Other errors pass through unchanged.
- `edt_infobase_sessions` died with "Ошибка разбора параметра: --infobase-user" whenever the
  infobase credentials were passed: `rac session list` takes only `--infobase` and
  `--licenses`, no infobase authentication at all. The credentials are no longer sent there
  (and no longer advertised by the tool) – they belong to the operations that do need them.
- Agent error messages no longer drown the diagnosis in the platform's licensing dump: one
  refusal used to arrive as tens of kilobytes – the full hardware inventory repeated per
  license file and per lookup stage, wrapped again by the SSH client. The gateways now drop
  the inventory lines and the repeats (with an "N lines omitted" note); ordinary messages
  pass through untouched. Measured live: ~25 KB down to 2.4 KB, the first line already says
  "no license".
- The platform speaks two languages and the bridge now hears both: every reply it recognises
  – the configuration-lock refusal, the agent's "not connected to the infobase" gate that
  drives the automatic reconnect, "extension not found", ibcmd asking for credentials – is
  matched against the English wording as well as the Russian one, both read out of the
  platform's own resource bundles. The ibcmd recogniser's earlier English guess
  ("authentication is required") is corrected to the platform's actual string ("Authentication
  in the infobase is required..."), which it used to miss.

## 2026-07-22 – 0.8.0

### Added
- The wrapper's CLI help is bilingual. An i18n catalogue (`ru`/`en`, picked by `EDT_BRIDGE_LANG`,
  otherwise the locale) covers flag and command descriptions, usage, the epilogue, the built-in
  argparse strings and the hand-written `self-update` help. What the tools answer to the agent
  stays English – that is the plugin's protocol surface, not text for a human.
- `edt_project_errors` reports a marker's `sourceType` and `extraInfo` – what tells the two
  validation families apart: documented checks come from the standards framework, a short code
  like `SU200` comes from EDT's own metadata validation.
- `edt_check_info` recognises a short code and says what it is instead of "nothing found". There is
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
- **The configurator agent as a third transport to an infobase.** Started with `/AgentMode`, it
  holds an open session and takes commands over SSH – and authenticates **as the infobase user**,
  which neither EDT's synchronization nor `ibcmd` can do. A server infobase that authenticates its
  users is therefore reachable, extensions included. One agent per infobase, reused;
  `edt_designer_agent` lists and stops them.
- `edt_infobase_config_state` – is a database-configuration update still pending? The platform
  answers it itself: the update is started and its confirmation refused, so nothing is applied and
  the waiting changes come back as a list.
- `edt_update_database_config` – applies the database configuration. Loading a project does not do
  this, and until it happens every session keeps running the previous code – which is what a freshly
  added HTTP route answering 404 looks like. `sessionTermination=force` does deny / terminate /
  apply in one call.
- `edt_infobase_sessions` – the cluster's sessions through `rac`: list and terminate. Neither the
  agent nor `ibcmd` reaches them. It exists because a designer that was killed rather than closed
  keeps the configuration lock, and every later operation then fails as though somebody else were
  configuring the base.
- `edt_delete_extension` – remove an extension from an infobase, the step that closed a one-way
  lifecycle. Needs `force` on top of `apply`.
- `edt_infobase_dump` – dump an infobase to a `.dt` through `ibcmd`: the backup that belongs before
  applying a configuration.
- `edt_update_infobase` takes `transport=agent`: designer XML goes straight into the base, with no
  throwaway infobase and no `.cf` in between – which is what makes it usable for a configuration
  with a million objects.
- `edt_extension_properties` takes `infobase` and routes through the agent – that is what makes a
  server infobase's extensions reachable at all.
- `edt_add_route` – add a route to an `HTTPService`. It used to mean editing the `.mdo` by hand and
  generating the uuids of the `urlTemplates` block and its nested `methods`.
- The ibcmd-backed tools take the 1C infobase credentials next to the DBMS ones. Without them ibcmd
  prompts for a user name on stdin – for a non-interactive caller a hang, not an error, and it
  ignores EOF (134 MB of prompts in 60 seconds, measured). Output is now read incrementally and the
  process killed the moment a prompt appears.
- Agent-backed tools address an infobase EDT does not know: `srv\base`, or a file base's directory.
- Each ibcmd invocation gets its own working directory; without it they lock the same one and the
  next invocation anywhere fails.

### Changed
- `edt_project_errors` can answer "what is wrong with the module I just edited". It used to take a
  project name and return everything – 13 850 problems and ~5 MB on a large configuration. New
  `fqn` / `modulePath`, `severity` and `countOnly`, plus `total`, `bySeverity`, `bySource` and a
  capped list.
- `edt_module_text` takes `includeMethods` (default true): asking for one method no longer drags the
  module's entire catalogue along – 147 methods on one production module, re-sent on every read.
- `edt_module_text`, `edt_add_method` and `edt_delete_method` resolve `HTTPService.X` and
  `WebService.X`: all three were affected by one gap in the module resolver's folder map.
- The README tool tables gained a section for everything that talks to a running infobase, with the
  transports spelled out – which one reaches what is the first thing a reader needs. Both diagrams
  redrawn.
- A test suite for the wrapper plus a `ci` workflow (Linux and Windows, 3.10 and 3.12) that both
  release workflows call first, so a red suite stops a release. Its core is the three regressions
  that actually shipped.
- JUnit coverage for the EDT-independent part of the plugin, moved to `...edtbridge.core` so a plain
  JDK compiles and tests it.

### Fixed
- `self-update` left a stale jar in `dropins` when the release jar was already there – and two copies
  of one bundle are what makes Equinox resolve an arbitrary one.
- The diagrams lost their transparent corners; `scripts/render-diagrams.sh` now pins the flags that
  decide it, a recipe that had lived nowhere.

## 2026-07-19 – 0.3.0, 0.3.1, 0.4.0, 0.4.1, 0.5.0

### Added
- Forms, the full set: `edt_add_form` creates a managed form through EDT's own generator – the engine
  behind the "New form" wizard. Items (`edt_add_form_item` and its modify/remove pair) go through the
  service the form editor calls, so naming, ids, a field's actual type and a table's auto-filled
  columns are decided by EDT. Members likewise: `edt_add_form_attribute` /
  `edt_add_form_command` with their pairs, ids from EDT's own service, handler stubs written into the
  form module. Removals list what still binds to the member and require `force`.
- `edt_adopt_object` – adopt an object of the base configuration into an extension through EDT's own
  adopter. Without it a created extension project stopped halfway: intercepting a method requires the
  owning object adopted first.
- `edt_search_modules` – full-text search across a project's BSL modules. Reading goes through
  Eclipse's buffer manager, so a module open in an editor is searched as it currently stands, unsaved
  edits included.
- `edt_clean_project` – discard build results so validation runs again, the programmatic equivalent
  of EDT's "Clean". A stale marker can otherwise outlive the code that caused it.
- `edt_delete_project` – remove a project from the workspace through Eclipse, so its resource tree is
  updated; deleting the folder by hand leaves a ghost project that keeps the name taken.
- `edt_extension_properties` – read and set what an extension carries **inside an infobase** (safe
  mode, protection from dangerous actions, active, scope). A newly registered extension gets safe
  mode and dangerous-action protection **on**, and an extension that changes methods of the base
  configuration cannot run under either.
- `edt_build_extension` and `edt_update_infobase` report `changesMethods` and, when true, name the
  two flags that must be off.
- `edt-bridge-mcp` can be driven from a shell: `call <tool>`, `tools`, `status`. Until now anything
  outside an MCP client meant hand-writing a client against port 8770. Exit codes separate "the call
  could not be made" (1) from "the tool ran and failed" (2).
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
- `edt_dump_external_object` builds an `.epf`/`.erf` again where EDT cannot: EDT resolves the newest
  build of a version line, so a line topped by thin-client builds failed with
  `MatchingRuntimeNotFound`. There is now an on-disk route, and the dry-run stopped lying about it.
- Modules of external objects resolve by FQN – `ExternalDataProcessor` and `ExternalReport` were
  simply absent from the folder map.
- `self-update` could not update the wrapper on a current pipx: a uv-built venv has no pip at all. It
  no longer uses an installer – the wheel is unpacked over the package with the standard library
  alone.
- The wrapper reported a stale version through two releases: `__init__.py` carried a literal that
  `pyproject.toml` did not use. The package version now derives from that one attribute.
- The MCP server reports the plugin's own version from the bundle manifest instead of a hard-coded
  string, so the dashboard and the handshake cannot drift apart.
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
- The `edt-bridge-mcp` stdio wrapper (pipx): forwards to a running EDT, auto-starts a headless one
  when none is open, and delivers the plugin jar into EDT's `dropins/`.
- `edt_platform_help` – the 1C:Enterprise Syntax Helper bundled with EDT: a real API reference,
  searchable, Ru and En.
- A multi-threaded MCP server, and a free-port fallback: if 8770 is busy the next free port is used
  and the wrapper finds it.

### Fixed
- Project-creation `apply` works reliably: a fresh detached Configuration or external data processor
  is built and attached instead of tripping the headless EDT lifecycle.

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
