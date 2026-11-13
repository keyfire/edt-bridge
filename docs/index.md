---
title: "EDT-Bridge"
description: "A 1C:EDT plugin that exposes EDT's live semantic model to AI agents over MCP: diagnostics, metadata and types, query validation, the platform Syntax Helper, write tools, infobases and a debugger."
sidebar:
  label: Home
  order: 1
---

EDT-Bridge is a 1C:EDT plugin that provides AI agents and other tools with access to EDT's semantic
model over the Model Context Protocol (MCP). The plugin requires a running EDT instance with the
project open, either in the GUI or in headless mode.

The tools provide EDT diagnostics, metadata structure and types, semantic references, and the
platform Syntax Helper supplied with EDT. They validate queries against project metadata,
create and rename objects, and build and deliver them. They also support infobase operations and
debugging. These operations use EDT's own engine.

> The server is available only on localhost. Model-changing tools require a token and return a
> plan by default. Debug tools also require a token.

![An MCP client connects to edt-bridge-mcp over stdio. The wrapper checks port 8770, installs the plugin jar from GitHub Releases, and starts headless EDT when needed. The plugin in EDT provides the project model and accesses the infobase through a configurator agent, ibcmd, or rac](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/architecture.svg)

Development notes and updates (in Russian): the [1C × AI: engineering workshop](https://t.me/ceh_1c_ai) Telegram channel.

## Install (recommended: pipx)

The [**edt-bridge-mcp**](https://github.com/keyfire/edt-bridge/blob/main/python/README.md) wrapper
runs as a stdio MCP server for the client and forwards requests to EDT. If EDT is not running, the
wrapper starts it in headless mode. If the plugin jar is missing from EDT's `dropins/`, the wrapper
installs it.

```bash
pipx install edt-bridge-mcp
```

Then register it with your MCP client. Claude Code is shown here; `--workspace` is the EDT workspace
to serve when the wrapper starts a headless EDT:

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

Wrapper flags, the write-tools token and `self-update` are documented in
[python/README.md](https://github.com/keyfire/edt-bridge/blob/main/python/README.md). To run the
plugin yourself, without the wrapper, see
[Manual install](/install#manual-install-without-the-wrapper).

<details>
<summary>Don't have <code>pipx</code>?</summary>

`pipx` installs Python CLI apps into isolated environments. Install it once:

```bash
python -m pip install --user pipx
python -m pipx ensurepath      # then reopen the terminal
```

macOS: `brew install pipx && pipx ensurepath`. More: <https://pipx.pypa.io>.
</details>

<a id="settings-inside-edt"></a>The token, the port and the other settings of the plugin and the
wrapper are described in [Settings](/settings).

## Nearby

EDT-Bridge works with 1C:Enterprise. The following tools are available for the 1C:Element technology:

- **[XBSL](https://docs.keyfire.ru/xbsl/)** – a linter for Element sources with autofixes, an LSP server,
  metadata scaffolding, an MCP server and a VS Code extension.
- **[Elemctl](https://docs.keyfire.ru/elemctl/)** – delivery to an Element stand: build, upload, apply, and a
  check that it really applied.

## The page in a browser

Open `http://127.0.0.1:8770/` for the built-in status page: the server, the open EDT projects, and a
runner for every tool. It has a light and a dark theme and an EN/RU switch.
