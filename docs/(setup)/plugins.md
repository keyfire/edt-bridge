---
title: "Wrapper plugins"
description: "Installing, updating and writing plugins that add tools to the edt-bridge-mcp wrapper."
sidebar:
  label: "Wrapper plugins"
  order: 4
---

Wrapper plugins are distributed as separate packages and installed in the wrapper's environment.
They can contain reference material under another license or tools for internal services. The
wrapper discovers them through the `edt_bridge.tools` entry-point group, lists their tools alongside
its own, and executes them. These tools are available without a running EDT.

Installing one:

```bash
pipx inject edt-bridge-mcp <package>
```

## When pipx runs on uv (pipx >= 1.15)

When pipx uses uv, it creates the environment without pip. Note the following:

- When `--pip-args="--no-deps"` is written as one token, uv receives the argument split and displays
  its usage message. Pass the flag as a separate argument:
  `pipx inject edt-bridge-mcp <package> --pip-args "--no-deps"`.
- `<venv>/Scripts/python -m pip` returns "No module named pip". Use `pipx runpip edt-bridge-mcp`
  to access the environment: pipx routes the command to `uv pip`.
- Reinstalling a plugin without `--no-deps` also reinstalls the core package. If an MCP session
  holds `edt-bridge-mcp.exe` open, the file cannot be replaced ("failed to persist ... Access denied").
  The plugin is usually installed before the failure. Check `edt-bridge-mcp plugins` for the result
  instead of relying on the exit code.

To update a plugin without reinstalling the core package while it is in use, run:

```bash
python -m pipx runpip edt-bridge-mcp -- install --upgrade --no-deps <package>
```

Add `--index-url <your index>` when the plugin is hosted in a private registry.

## Updating and listing plugins

`edt-bridge-mcp self-update` updates installed plugins. The `--plugins-only` flag limits the
update to plugins. Each one is updated from the source it was installed
from: its git repository, or a package index by project name, with `EDT_BRIDGE_PLUGIN_INDEX` naming
the index for registry installs.

`edt-bridge-mcp plugins` lists installed packages, entry points and their tools. If a plugin fails
to load, the command prints the loader's message. `EDT_BRIDGE_NO_PLUGINS=1` turns the
discovery off.

## Declaring tools

A plugin declares its tools in `pyproject.toml`:

```toml
[project.entry-points."edt_bridge.tools"]
package-name = "my_package.tools:tools"
```

The value is a `Tool` from `edt_bridge_mcp.plugins`, a list of them, or a zero-argument callable
returning either. A `Tool` contains the MCP descriptor – `name`, `description`, the JSON schema of
the arguments – and the handler the wrapper calls. A str return becomes the text result, any other
JSON-serializable value is pretty-printed as JSON, and an exception becomes the tool's error
message. A failing entry point, duplicate tool name, or name that shadows a wrapper tool produces an error
at discovery.

A handler that also declares a `bridge` parameter – `handler(arguments, bridge)` – receives a
callable `bridge(tool_name, arguments) -> str` that forwards one call to the live bridge and returns
the text of its result. This call does not start EDT. If the bridge is unavailable, the callable raises `RuntimeError` and
the plugin returns its message. Older wrappers call such handlers with one argument, so give
`bridge` a default of `None`.
