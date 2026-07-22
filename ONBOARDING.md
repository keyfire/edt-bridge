# EDT-Bridge quick start

**English** · [Русский](docs/ru/ONBOARDING.ru.md)

EDT-Bridge gives an AI agent (Claude Code and other MCP clients) access to the **live 1C:EDT
model**: reading metadata and BSL, query validation, the platform Syntax Helper, and write
operations – creating objects and extensions, refactoring, building `.epf`/`.erf` and updating
infobases, all through EDT's own engine.

What it consists of:

- **the 1C:EDT plugin** (a jar in `dropins/`) – starts an MCP server inside EDT at
  `http://127.0.0.1:8770/mcp`;
- **the `edt-bridge-mcp` wrapper** (Python, installed via pipx) – a stdio MCP server your client
  talks to; it forwards requests to EDT, auto-starts a headless EDT when none is open, and delivers
  the plugin jar when it is missing.

## Requirements

- **1C:EDT** installed (the wrapper can start it headless; or keep a GUI EDT open with your
  project).
- **Python 3.10+** and **pipx** (see below if you don't have pipx).
- Only for building `.epf`/`.erf` and updating infobases: an installed **1C:Enterprise platform**
  matching the project's version.

## Install (recommended – via pipx)

```bash
pipx install edt-bridge-mcp
```

If you don't have pipx:

```bash
python -m pip install --user pipx
python -m pipx ensurepath      # then reopen the terminal
```

macOS: `brew install pipx && pipx ensurepath`.

## Connect to Claude Code

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

`--workspace` is the EDT workspace folder (with `.metadata`) that the wrapper starts headless when
EDT is not running. With a GUI EDT open (with the plugin), the wrapper just connects to it.

For write tools (create / refactor / build) set a token – then writes are enabled, not only
dry-run:

```bash
claude mcp add edt-bridge --env EDT_BRIDGE_TOKEN=<any-string> -- \
  edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

## Verify

In a Claude Code session, ask for the project list or an API reference – e.g. "show the open EDT
projects" (the `edt_projects` tool) or "find ТаблицаЗначений.Добавить in the Syntax Helper"
(`edt_platform_help`). The first call may take up to a few minutes if the wrapper is starting a
headless EDT and loading the model; after that it is instant.

Check the server manually (once EDT / headless is up):

```bash
curl -s http://127.0.0.1:8770/status
```

A dashboard with status and a runner for every tool is at `http://127.0.0.1:8770/` in a browser.

## Update

```bash
edt-bridge-mcp self-update             # update both the plugin jar (GitHub Releases) and the wrapper (PyPI)
edt-bridge-mcp self-update --jar-only  # jar only
```

Old jars in `dropins/` are removed automatically (two jars would make EDT load an arbitrary one).
A running EDT keeps the old code until it is restarted.

## Manual install (without the wrapper)

To run the plugin yourself: take a jar from the
[Releases page](https://github.com/keyfire/edt-bridge/releases), put **one** into EDT's `dropins/`,
and restart EDT – the server comes up on 8770 (or the next free port). A client can connect
directly over HTTP: `{ "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" } }`.
Details and building from source – in the [README](README.md).

## Troubleshooting

- **The client doesn't see the tools right after start** – the wrapper starts a headless EDT in the
  background; the tool list appears once the model has loaded (a `tools/list_changed` arrives).
- **"a GUI EDT is running"** – a GUI EDT is open without the plugin, and the wrapper won't touch it
  (it holds the workspace lock). It will still deliver the jar into `dropins/` – restart that EDT to
  activate the bridge, or close it.
- **Port 8770 is busy** – the plugin takes the next free port and the wrapper finds it by scanning;
  you can set it explicitly via `EDT_BRIDGE_PORT`.
- **A write tool refuses with "requires a configured token"** – set `EDT_BRIDGE_TOKEN` (see above).
- **`edt_dump_external_object` won't build** – needs an installed 1C:Enterprise platform matching
  the project's version.
