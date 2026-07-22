# Changelog

**English** · [Русский](docs/ru/CHANGELOG.ru.md)

Notable changes to EDT-Bridge, newest first. Entries are grouped by day; the versions released
that day are named in the heading. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The plugin jar and the
`edt-bridge-mcp` wrapper share one version number.

## Unreleased

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

### Fixed
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
