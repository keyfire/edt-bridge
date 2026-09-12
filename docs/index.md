---
title: "EDT-Bridge"
description: "A 1C:EDT plugin that exposes EDT's live semantic model to AI agents over MCP: diagnostics, metadata and types, query validation, the platform Syntax Helper, write tools, infobases and a debugger."
sidebar:
  label: Home
  order: 1
---

A small **1C:EDT plugin** that hands EDT's **live semantic model** to AI agents and other tools over
the **Model Context Protocol (MCP)**.

A static parser reads source files. EDT-Bridge asks the running IDE instead, so it can answer what
only the *live* model knows: EDT's own validation problems, the real structure and types of the
metadata, semantic cross-references, **query validation against the project's actual metadata**, and
the **platform Syntax Helper** that ships with EDT. Reading is half of it. The write tools create,
refactor, build and deliver; infobases and a debugger come with them. All of it runs on EDT's own
engine.

> Read **and** write. Localhost only. A write needs the token and returns a plan until you ask for
> more. The plugin lives inside EDT, so an EDT – GUI or headless – has to be up with your project.

![An MCP client talks stdio to the edt-bridge-mcp wrapper; the wrapper checks port 8770, delivers the plugin jar from GitHub Releases and starts a headless EDT when needed; the plugin inside a GUI or headless EDT serves the live model, and reaches a running infobase through a configurator agent, ibcmd or rac](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/architecture.svg)

Development notes and updates (in Russian): the [1C × AI: engineering workshop](https://t.me/ceh_1c_ai) Telegram channel.

## Install (recommended: pipx)

One command sets up both halves, the client wrapper and the plugin. The
[**edt-bridge-mcp**](https://github.com/keyfire/edt-bridge/blob/main/python/README.md) wrapper is a
stdio MCP server that your client talks to. It forwards to a running EDT, **starts a headless EDT**
when none is open, and **delivers the plugin jar** into EDT's `dropins/` when it is missing. You
never copy a jar by hand.

```bash
pipx install edt-bridge-mcp
```

Then register it with your MCP client. Claude Code is shown here; `--workspace` is the EDT workspace
to serve when the wrapper starts a headless EDT:

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

That is the whole setup. Wrapper flags, the write-tools token and `self-update` are documented in
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

### Settings inside EDT

The plugin has a preference page of its own, **Window ▸ Preferences ▸ EDT-Bridge**. That is where the
token comes from when the bridge runs inside a GUI EDT:

| Setting | What it is |
|---------|------------|
| **Token for write tools** | The shared secret every write tool requires. An empty token means writes are refused, not that they are open to anyone. The client gets the same value as `EDT_BRIDGE_TOKEN`. |
| **MCP server port** | 8770 by default. **It takes effect after EDT restarts**, so the running server keeps the old port until then. |
| **Allow arbitrary BSL evaluation while debugging** | Off by default. It guards `edt_evaluate`, which executes code against a live infobase. Test stands only. |

**A launch parameter wins over this page.** `-Dedt.bridge.*` system properties and `EDT_BRIDGE_*`
environment variables given at startup take precedence over the stored values. That is how the
wrapper drives a headless EDT, and it is also why a token set here can look ignored when one was
passed on the command line as well.

## Nearby

EDT-Bridge works with 1C:Enterprise. The neighbouring platform, 1C:Element, has a pair of tools
built on the same idea: give the agent hands, not advice.

- **[XBSL](https://docs.keyfire.ru/xbsl/)** – a linter for Element sources with autofixes, an LSP server,
  metadata scaffolding, an MCP server and a VS Code extension.
- **[Elemctl](https://docs.keyfire.ru/elemctl/)** – delivery to an Element stand: build, upload, apply, and a
  check that it really applied.

## The page in a browser

Open `http://127.0.0.1:8770/` for the built-in status page: the server, the open EDT projects, and a
runner for every tool. It has a light and a dark theme and an EN/RU switch.
