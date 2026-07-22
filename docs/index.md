---
title: "EDT-Bridge"
description: "A 1C:EDT plugin that exposes EDT's live semantic model to AI agents over MCP."
sidebar:
  label: Home
  order: 1
---

A small **1C:EDT plugin** that exposes EDT's **live semantic model** to AI agents and other
tools over the **Model Context Protocol (MCP)**.

Static parsers read source files; EDT-Bridge instead asks the running IDE. It answers things
that need the *live* model: EDT's own validation problems, real metadata structure and types,
semantic cross-references, **query validation against the project's actual metadata**, and the
**platform Syntax Helper** bundled with EDT – plus write tools that create, refactor, build and
deliver, all through EDT's own engine.

> Read **and** write. Localhost only; writes are token-gated and dry-run by default. The plugin
> runs inside EDT, so an EDT (GUI or headless) must be up with your project.


Development notes and updates (in Russian): the [1C × AI: engineering workshop](https://t.me/ceh_1c_ai) Telegram channel.

## Install (recommended: pipx)

One command sets up everything – the client wrapper AND the plugin. The
[**edt-bridge-mcp**](https://github.com/keyfire/edt-bridge/blob/main/python/README.md) wrapper is a stdio MCP server that your client talks to; it
forwards to a running EDT, **auto-starts a headless EDT** when none is open, and **delivers the
plugin jar** into EDT's `dropins/` when it is missing. You do not copy any jar by hand.

```bash
pipx install edt-bridge-mcp
```

Then register it with your MCP client (Claude Code shown; `--workspace` is the EDT workspace to
serve when auto-starting headless):

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

That is the whole setup. Wrapper flags, the write-tools token and `self-update` are documented in
[python/README.md](https://github.com/keyfire/edt-bridge/blob/main/python/README.md). Prefer to run
the plugin yourself, without the wrapper? See
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

### Settings inside EDT

The plugin has its own preference page – **Window ▸ Preferences ▸ EDT-Bridge** – and that is where the
token comes from when the bridge runs inside a GUI EDT:

| Setting | What it is |
|---------|------------|
| **Token for write tools** | The shared secret every write tool requires. Empty means writes are refused, not unguarded. The same value goes to the client as `EDT_BRIDGE_TOKEN`. |
| **MCP server port** | Default 8770. **Takes effect after EDT restarts**, so the running server keeps the old port until then. |
| **Allow arbitrary BSL evaluation while debugging** | Off by default. It gates `edt_evaluate`, which executes code against a live infobase – test stands only. |

**A launch parameter wins over this page.** `-Dedt.bridge.*` system properties and `EDT_BRIDGE_*`
environment variables given at startup take precedence over the stored values – which is how the
wrapper drives a headless EDT, and why a token set here can look ignored when one was also passed on
the command line.

## Nearby

EDT-Bridge works with 1C:Enterprise. The neighbouring platform, 1C:Element, is served by a
pair of tools built on the same principle – a tool hands the agent its hands, not advice:

- **[XBSL](https://docs.keyfire.ru/xbsl/)** – a linter for Element sources with autofixes, an LSP server,
  metadata scaffolding, an MCP server and a VS Code extension.
- **[Elemctl](https://docs.keyfire.ru/elemctl/)** – delivery to an Element stand: build, upload, apply, and a
  check that it really applied.

## Dashboard

Open `http://127.0.0.1:8770/` in a browser for a built-in dashboard: server status, the open EDT
projects, and an interactive runner for every tool. Light/dark theme and an EN/RU language toggle.
