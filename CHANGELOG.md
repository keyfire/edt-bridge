# Changelog

**English** · [Русский](docs/ru/CHANGELOG.ru.md)

All notable changes to edt-bridge are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The plugin jar and the
`edt-bridge-mcp` wrapper share one version number.

## [Unreleased]

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

[Unreleased]: https://github.com/keyfire/edt-bridge/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/keyfire/edt-bridge/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/keyfire/edt-bridge/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/keyfire/edt-bridge/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/keyfire/edt-bridge/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/keyfire/edt-bridge/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/keyfire/edt-bridge/releases/tag/v0.0.1
