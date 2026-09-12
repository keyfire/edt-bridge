# EDT-Bridge quick start

**English** · [Русский](docs/ru/ONBOARDING.ru.md)

EDT-Bridge gives an AI agent – Claude Code and other MCP clients – access to the **live 1C:EDT
model**. That covers reading metadata and BSL, query validation and the platform Syntax Helper, and
it covers writing too: creating objects and extensions, refactoring, building `.epf` and `.erf`,
updating infobases. All of it runs on EDT's own engine.

It comes in two parts:

- **the 1C:EDT plugin**, a jar in `dropins/`, which starts an MCP server inside EDT at
  `http://127.0.0.1:8770/mcp`;
- **the `edt-bridge-mcp` wrapper**, Python, installed through pipx. This is the stdio MCP server
  your client talks to. It forwards requests to EDT, starts a headless EDT when none is open, and
  delivers the plugin jar when it is missing.

## Requirements

- **1C:EDT** installed. The wrapper can start it headless, or you can keep a GUI EDT open with your
  project.
- **Python 3.10+** and **pipx**. See below if you do not have pipx.
- An installed **1C:Enterprise platform** matching the project's version, needed only for building
  `.epf` and `.erf` and for updating infobases.

## Install (recommended – via pipx)

```bash
pipx install edt-bridge-mcp
```

If you do not have pipx:

```bash
python -m pip install --user pipx
python -m pipx ensurepath      # then reopen the terminal
```

macOS: `brew install pipx && pipx ensurepath`.

## Connect to Claude Code

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

`--workspace` is the EDT workspace folder, the one with `.metadata`, that the wrapper starts headless
when EDT is not running. With a GUI EDT open and the plugin in it, the wrapper simply connects.

Set a token to enable the write tools – create, refactor, build – so that they do more than return a
plan:

```bash
claude mcp add edt-bridge --env EDT_BRIDGE_TOKEN=<any-string> -- \
  edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

## Verify

In a Claude Code session, ask for the project list or for an API reference: "show the open EDT
projects" goes to `edt_projects`, "find ТаблицаЗначений.Добавить in the Syntax Helper" goes to
`edt_platform_help`. The first call can take a few minutes while the wrapper starts a headless EDT
and loads the model. After that it is instant.

Check the server by hand once EDT is up, GUI or headless:

```bash
curl -s http://127.0.0.1:8770/status
```

`http://127.0.0.1:8770/` in a browser shows the status page, with a runner for every tool.

## Update

```bash
edt-bridge-mcp self-update             # update both the plugin jar (GitHub Releases) and the wrapper (PyPI)
edt-bridge-mcp self-update --jar-only  # jar only
```

Old jars in `dropins/` are removed along the way, because two jars would make EDT load an arbitrary
one. A running EDT keeps the old code until it is restarted.

## Manual install (without the wrapper)

To run the plugin yourself, take a jar from the
[Releases page](https://github.com/keyfire/edt-bridge/releases), put **one** into EDT's `dropins/`
and restart EDT. The server comes up on 8770, or on the next free port. A client can connect over
HTTP directly: `{ "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" } }`. The
details, and how to build from source, are in the [README](README.md).

## Troubleshooting

- **The client does not see the tools right after start.** The wrapper is starting a headless EDT in
  the background. The tool list appears once the model has loaded, when a `tools/list_changed`
  arrives.
- **"a GUI EDT is running".** A GUI EDT is open without the plugin, and the wrapper will not touch
  it, because it holds the workspace lock. The jar still gets delivered into `dropins/`, so restart
  that EDT to activate the bridge, or close it.
- **Port 8770 is busy.** The plugin takes the next free port and the wrapper finds it by scanning.
  You can also set the port explicitly with `EDT_BRIDGE_PORT`.
- **A write tool refuses with "requires a configured token".** Set `EDT_BRIDGE_TOKEN`, as shown
  above.
- **`edt_dump_external_object` will not build.** It needs an installed 1C:Enterprise platform
  matching the project's version.
