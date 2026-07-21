# Changelog

**English** · [Русский](docs/ru/CHANGELOG.ru.md)

All notable changes to edt-bridge are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The plugin jar and the
`edt-bridge-mcp` wrapper share one version number.

## [Unreleased]

### Added
- `edt_infobase_config_state` – whether an infobase actually RUNS the configuration it holds. In 1C
  the code a session executes is the *database* configuration, a separate thing from the one being
  edited, so changes that are loaded but not applied leave every session on the previous code – the
  exact failure `edt_update_infobase` hid behind `equality: EQUAL`. Established through `ibcmd`, which
  dumps both configurations for comparison and addresses the infobase by file path or DBMS
  coordinates: a clustered infobase needs neither cluster access nor an EDT session. Verified against
  a live file infobase in both directions – matching, then diverging once a change was loaded without
  being applied, then matching again after `config apply`.
- A test suite for the wrapper plus a `ci` workflow (Linux and Windows, 3.10 and 3.12) that both
  release workflows now call first, so a red suite stops a release instead of shipping past it. Its
  core is the three regressions that actually shipped: a cp1251 stdout aborting the `tools/list` frame
  (the client then registered no tools at all), a wrapper version that drifted for two releases, and a
  `BrokenPipeError` when a result was piped into `head`.
- JUnit coverage for the EDT-independent part of the plugin. Resolving an object's sources from its
  FQN and deciding whether a validation problem is in scope moved to `...edtbridge.core`, which
  carries no EDT or Eclipse types, so a plain JDK compiles and tests it – `scripts/test-java.sh` puts
  nothing but JUnit on the classpath, so a dependency on the SDK creeping in fails the build. The move
  also removed a duplicated copy of the folder map that the module resolver and the problem filter had
  been keeping separately.

## [0.6.0] – 2026-07-21

### Added
- `edt_add_route` – add a route (a URL template plus one HTTP method) to an `HTTPService`. The bridge
  covered attributes, forms and module code, but a new route meant editing the service `.mdo` by hand
  and generating the `uuid` of both the `urlTemplates` block and its nested `methods` block – exactly
  the hand-editing the bridge exists to avoid. Dry-run by default: resolves the service, checks the
  template name is free and the HTTP method resolves to a `httpMethod` enum literal, and returns the
  plan. `apply=true` creates both through the model and serialises the `.mdo`; `createHandler` splices
  a `Функция <handler>(Запрос)` stub into the service module.

### Changed
- `edt_project_errors` can now answer "what is wrong with the module I just edited". It took a project
  name and returned everything: on a large configuration that is 13 850 problems, ~5 MB, 152 000 lines
  – past any tool-result limit, so every call spilled to a file that an external script then filtered.
  New optional `fqn` / `modulePath` (narrow to one object or module), `severity` (ERROR/WARNING/INFO)
  and `countOnly` (the counts, no list – what a before/after baseline needs). The result also carries
  `total`, `totalBeforeFilter`, `bySeverity` and `bySource`, and the returned list is capped at `limit`
  (default 1000, `truncated` flag) so an unfiltered call stays bounded.
- `edt_module_text` takes `includeMethods` (default true). Every call returned the module's entire
  procedure/function catalogue with signatures even when a single `method` was requested – 147 methods
  on one production module, re-sent on every read. `false` returns just the requested text.
- `edt_module_text`, `edt_add_method` and `edt_delete_method` resolve `HTTPService.X` and `WebService.X`
  FQNs. `edt_delete_method` refused with "unsupported object type: HTTPService (pass modulePath
  directly)", leaving the caller to reconstruct `src/HTTPServices/<name>/Module.bsl` by hand; both
  service kinds were simply absent from the module resolver's folder map, so all three tools were
  affected by one gap.

## [0.5.0] – 2026-07-19

### Fixed
- `self-update` could not update the wrapper on a current pipx. It ran `pip install --upgrade`
  inside its own environment – but pipx 1.15 builds its venvs through uv, and a uv-built venv has
  no pip at all, so the step died on "No module named pip" and the wrapper never updated itself.

  It no longer uses an installer. The wheel is downloaded from PyPI and unpacked over the package
  in `site-packages` with the standard library alone, the exes in `Scripts` are left alone – they
  are what a running client holds open, and the stub picks up the new code by itself – and
  `pipx_metadata.json` is corrected so `pipx list` stops reporting the old version. An editable
  install is refused rather than overwritten. This is the shape the sibling tools already used;
  the wrapper was the one doing it the hard way.
- The wrapper reported a stale version – `--version` and the MCP `serverInfo` said 0.3.1 through
  two releases, because `__init__.py` carried a literal that `pyproject.toml` did not use. The
  package version is now derived from that one attribute, so the two cannot drift again.
- `edt_create_extension` produced a project no infobase would load – it failed with *"the load must
  not change the ownership of the main configuration object"*, on an empty extension as much as on a
  filled one. The root `Configuration` was built by the md factory, which yields a plain **full**
  configuration: no `objectBelonging=Adopted`, no `ConfigurationExtension` control block, no
  `keepMappingToExtendedConfigurationObjectsByIDs`, no adopted language, no inherited script variant
  or compatibility modes – and, instead, full-configuration properties an extension has no business
  with. The extension's root is now the **base configuration adopted**, the same step EDT's own New
  Configuration Extension wizard performs (`ExtensionWizard.adopt`), so the engine writes all of it.

  Two further gaps closed themselves with it, because both were consequences rather than causes:
  - objects adopted by `edt_adopt_object` now carry `extendedConfigurationObject` – the uuid link to
    the base object – instead of binding by name. EDT writes that link only when the extension's
    `Configuration` has `keepMappingToExtendedConfigurationObjectsByIDs`, which nothing used to set.
  - the base configuration's default language now comes along, adopted properly, with its
    `languageCode` and the uuid link. Languages are not top-level objects, so nothing could adopt one
    on its own; adopting the configuration is what brings it in.
- `edt_create_extension` now reads the created `Configuration` back and reports what actually landed
  (`objectBelonging`, `extensionBlock`, `keepMappingByIds`, `adoptedLanguage`, `languageLinked`).
  The previous failure was invisible until an infobase rejected the build, which is late.

### Added
- `self-update --from <checkout>` installs the wrapper from a local checkout instead of PyPI – for
  trying a build that is not released yet, without a `pipx install --force` that rebuilds the venv
  and replaces the exe the running client holds.
- `edt-bridge-mcp` can be driven from a shell: `call <tool>` runs one tool and prints what it
  returned, `tools` lists what the bridge serves, `status` reports whether one is up. Until now the
  wrapper only spoke JSON-RPC over stdin/stdout, so anything outside an MCP client meant
  hand-writing a client against port 8770 – including checking a tool you had just built, since a
  new tool stays invisible to an MCP client until the client restarts. Arguments come from
  `--json`, `--json-file` or `--stdin`; `--raw` prints the JSON result. Exit codes separate "the
  call could not be made" (1) from "the tool ran and failed" (2).
- `edt_extension_properties` – read and set the properties an extension carries **inside an
  infobase** (safe mode, protection from dangerous actions, active, scope) through
  `ibcmd extension info|list|update`. These belong to the infobase registration, and nothing that
  puts an extension there decides them: a newly registered extension gets safe mode and
  dangerous-action protection **on** – verified as ibcmd's own defaults, and an update from EDT
  leaves them untouched. An extension that changes methods of the base configuration cannot run
  under either, so both have to be cleared, and that was previously silent until the extension
  misbehaved in the infobase.
- `edt_build_extension` and `edt_update_infobase` now report `changesMethods` and, when it is true,
  name the two flags that must be off. The check reads the project's modules for interception
  annotations (`&Вместо` / `&Перед` / `&После` / `&ИзменениеИКонтроль` and their English spellings).
- `edt_create_extension` takes an optional `synonym` – the extension's human-readable name, written
  for the language adopted from the base configuration, as the wizard's own field does.

## [0.4.1] – 2026-07-19

### Added
- `edt_search_modules` – full-text search across a project's BSL modules (substring or regular
  expression, optional path filter). Reading goes through Eclipse's `ITextFileBufferManager`, so a
  module open in an editor is searched as it currently stands, unsaved edits included, and every hit
  says which it came from. This was the one exploratory step that always fell back to disk tools:
  `edt_find_references` answers "who calls this method", this answers "where does this text appear".
- `edt_adopt_object` – adopt an object of the base configuration into an extension project through
  EDT's own `IModelObjectAdopter`. Without it `edt_create_extension` stopped halfway: the project
  existed but nothing could be extended, since intercepting a method requires the owning object to be
  adopted first. The engine writes `objectBelonging`, the per-property control block and the entry in
  the extension's `Configuration.mdo` – all of which is easy to get subtly wrong by hand.
- `edt_metadata_details` now reports a `CommonModule`'s compilation flags (`server`, `serverCall`,
  `clientManagedApplication`, `clientOrdinaryApplication`, `externalConnection`, `privileged`,
  `global`, `returnValuesReuse`) and an `ExchangePlan`'s content – the objects replicated between
  nodes, with their auto-record mode. Both previously sent callers to grep the `.mdo` on disk.

## [0.4.0] – 2026-07-19

### Added
- `edt_clean_project` – discard a project's build results so validation runs again, the programmatic
  equivalent of EDT's "Clean" dialog. EDT hangs its checks off the build, so this is what makes them
  re-run: a marker can otherwise outlive the code that caused it, and a stale marker is worse than
  none. Reports the problem count before and after, waiting until it stops changing.
- `edt_add_form` – create a managed form on an existing metadata object. It drives EDT's own
  `IFormGenerator`, the engine behind the "New form" wizard, following the same sequence as
  `FormNewWizardRelatedModelsFactory`: create the `BasicForm` entry, link it into the owner's
  `forms` feature, generate the form content, bind the two via `setMdForm`, attach the form as a BM
  top object under the FQN from `ITopObjectFqnGenerator`, then write the module from
  `generateModuleContent`. Form kinds accept both `FormType` names and Russian aliases
  (`ФормаЭлемента`, `ФормаСписка`, `ФормаЗаписи`, ...); omitting the kind picks the owner's usual
  one. Dry-run by default, token-gated, additive – no force.
- Form items: `edt_add_form_item`, `edt_modify_form_item`, `edt_remove_form_item`. Items are created
  through EDT's own `IFormItemManagementService` - the service the form editor calls - so naming, ids,
  a field's actual type and a table's auto-filled columns are decided by EDT rather than assembled by
  hand. Kinds: field, table, button, group (usual group, page, pages, command bar, button group,
  column group, popup) and decoration. The dry-run resolves the data path against the form's
  attributes and the command against its commands, so a wrong binding fails the plan. Removal takes
  everything nested inside and requires `force`.
- Form members, the full set for both kinds: `edt_add_form_attribute`, `edt_modify_form_attribute`,
  `edt_remove_form_attribute`, `edt_add_form_command`, `edt_modify_form_command`,
  `edt_remove_form_command`. Ids come from EDT's own `FormIdentifierService`. Attribute types reuse
  the `edt_add_attribute` grammar and additionally accept the platform types a form may hold
  (`ТаблицаЗначений`, `СписокЗначений`, ...), resolved through the platform type provider; the type
  is now built during the dry-run, so an unusable spec fails the plan rather than the apply.
  `edt_add_form_command` can write the handler procedure's stub into the form module, creating that
  module when the form has none. Removals list what still binds to the member - items for an
  attribute, buttons for a command - and require `force`. The three attribute tools also take
  `columnOf`, which addresses a COLUMN of a value-table attribute instead of a form attribute: a
  column is the same `AbstractFormAttribute`, so it needs no separate tools. With columns defined, a
  table's column fields follow from `edt_add_form_item` (`parent` = the table, `dataPath` =
  `attribute.column`).
- `edt_delete_project` – remove a project from the workspace, closing the cycle the project-creating
  tools open. The delete runs through the Eclipse workspace so its resource tree is updated;
  removing a project folder by hand leaves a ghost project that returns from the tree snapshot on
  the next start and keeps the name taken. `deleteContent` chooses between unregistering and erasing
  the files. Dry-run by default, `force` required to apply.
- `edt_create_external_object` accepts `scriptVariant` (Russian / English) and applies it through
  `IExternalObjectProjectManager.setScriptVariant` / `setLanguages`. EDT defaults a standalone
  project to English, which made generated members come out as `Object` rather than `Объект`; the
  dry-run now warns when the variant is left unset on a standalone project.

### Fixed
- `edt_dump_external_object` builds an `.epf`/`.erf` again where EDT cannot. EDT resolves the NEWEST
  build of a version line, so a line topped by thin-client builds made the native dumper fail with
  `MatchingRuntimeNotFound` even with full installs of that line present. There is now an on-disk
  route - export to designer XML in-process, then a full platform found on disk assembles the file -
  the same shape as the infobase disk fallback. Its dry-run also stopped lying: EDT's
  `validateDumpGeneration` reports OK regardless of the thick client, so the platform is now checked
  directly and the plan names the route it will take. A new `logPath` writes the platform build log
  next to the artefact (default `<targetPath>.log`) - when a build fails, that log is where the
  platform says why.
- Modules of external objects resolve by FQN. The metadata-type-to-folder map had no entry for
  external objects, so `ExternalDataProcessor.X.Form.Y` was rejected with "unsupported owner type for
  a form" by `edt_add_method`, `edt_delete_method` and `edt_module_text` (passing `modulePath`
  worked around it). `ExternalDataProcessor` and `ExternalReport` are mapped now. Configuration
  extensions were never affected - their source layout matches a configuration's.

### Changed
- The MCP server reports the plugin's own version, taken from the bundle manifest, instead of a
  hard-coded string - so a release bumps it in one place and the dashboard and the handshake cannot
  drift apart.

## [0.3.1] – 2026-07-19

### Fixed
- The stdio wrapper now pins its standard streams to UTF-8. On Windows they defaulted to the ANSI
  code page, so the "→" in the `edt_modify_attribute` description aborted `tools/list` with a
  `charmap` encoding error and the client ended up registering no tools at all. Cyrillic arguments
  on the way in were exposed to the same defect.
- The README tool tables were missing the four tools added in 0.3.0 (`edt_platform_installations`,
  `edt_register_platform`, `edt_create_infobase`, `edt_build_extension`); they are listed now.

## [0.3.0] – 2026-07-19

### Added
- `edt_platform_installations` – report the 1C:Enterprise platform installations EDT resolves
  (for building binaries and creating infobases), including full installs found on disk.
- `edt_create_infobase` – create an empty file infobase through EDT, with a disk fallback that
  locates a suitable full platform install when EDT has none registered.
- `edt_register_platform` – register a platform installation with EDT.
- `edt_build_extension` – build a configuration extension (`.cfe`) via `ibcmd`, exporting the
  project through EDT and packaging in a temporary infobase that is cleaned up afterwards.
- EDT-Bridge preferences page (token, MCP-server port, and the evaluate switch) so a GUI EDT
  launched from a plain shortcut can authenticate the write tools.
- "Reporting issues" section in the README; a PyPI trusted-publishing workflow.

### Fixed
- `edt_infobases` now expands infobase groups, so grouped/server infobases are no longer omitted.

### Changed
- Internal: the single model gateway was split into focused per-area gateways (project, metadata
  read, metadata write, form, platform, debug, BSL, docs) plus shared helpers. Pure refactor – no
  behavior change.

## [0.2.1] – 2026-07-18

### Changed
- House typography across the repository (en-dash instead of em-dash, three dots instead of the
  ellipsis character), including tool descriptions and the Python wrapper.

## [0.2.0] – 2026-07-18

### Added
- `edt_platform_help` – the 1C:Enterprise platform Syntax Helper bundled with EDT (real API
  reference, searchable, Ru + En).
- Multi-threaded MCP server (bounded worker pool).
- Free-port fallback: if 8770 is busy the next free port is used, and the wrapper finds it.

### Fixed
- Project-creation `apply` now works reliably (a fresh detached Configuration / external data
  processor is built and attached, instead of tripping the headless EDT lifecycle).

## [0.1.1] – 2026-07-18

### Added
- The create / develop / build / deliver write tools (dry-run by default).

### Fixed
- Project-creation `apply` gated for the headless EDT lifecycle.

## [0.1.0] – 2026-07-18

### Added
- Full create / develop / build / deliver cycle exposed over MCP.
- The `edt-bridge-mcp` stdio wrapper (pipx): forwards to a running EDT, auto-starts a headless EDT
  when none is open, and delivers the plugin jar into EDT's `dropins/`.

## [0.0.1] – 2026-07-11

### Added
- Initial release: read tools over MCP (projects, validation errors, metadata details and
  listing, references, query validation, forms), the built-in dashboard, and the localhost MCP
  server.

[0.5.0]: https://github.com/keyfire/edt-bridge/compare/v0.4.1...v0.5.0
[0.3.0]: https://github.com/keyfire/edt-bridge/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/keyfire/edt-bridge/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/keyfire/edt-bridge/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/keyfire/edt-bridge/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/keyfire/edt-bridge/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/keyfire/edt-bridge/releases/tag/v0.0.1
