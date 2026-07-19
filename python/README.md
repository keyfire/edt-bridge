# edt-bridge-mcp

**English** · [Русский](https://github.com/keyfire/edt-bridge/blob/main/python/README.ru.md)

stdio MCP front-end for the [edt-bridge](https://github.com/keyfire/edt-bridge) 1C:EDT plugin.

The Java plugin serves MCP as plain JSON-RPC over HTTP on `127.0.0.1:8770` – which means an
MCP client configured with that URL loses the server whenever EDT is not running. This wrapper
is what the client talks to instead:

- **EDT open** (GUI or headless) → every request is forwarded to the live bridge;
- **EDT closed** → it **auto-starts a headless EDT** (`1cedtcli` with a keepalive pipe, the
  same recipe as `scripts/run-headless.ps1`) and forwards once the model is ready;
- **plugin jar missing** → it **delivers the jar itself** from the latest GitHub release
  (checksum-verified) into EDT's `dropins/` before starting – a bare
  `pipx install edt-bridge-mcp` is enough to get a working bridge;
- a client session never hangs on startup: while the backend is starting, `tools/list`
  returns an empty list and a `notifications/tools/list_changed` follows when ready.

![How the bridge is wired](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/architecture.png)

## Install

```bash
pipx install edt-bridge-mcp        # or: pipx install ./python  from a checkout
```

## Register in an MCP client

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\path\\to\\edt-workspace"
```

## From a shell

The default mode speaks JSON-RPC over stdin/stdout, for an MCP client to drive. To reach the same
bridge by hand or from a script, use the sub-commands – same port scan, same token, same headless
auto-start:

```bash
edt-bridge-mcp tools                    # what the running bridge serves
edt-bridge-mcp call edt_projects        # call a tool, print what it returned
edt-bridge-mcp call edt_metadata_details --json '{"projectName": "SM", "fqn": "CommonModule.Foo"}'
edt-bridge-mcp call edt_create_extension --json-file args.json   # arguments from a UTF-8 file
edt-bridge-mcp status                   # is a bridge up? (never starts one)
```

`--raw` prints the JSON result instead of the text a tool returned. Arguments come from `--json`,
`--json-file` or `--stdin`; a file is the dependable route for non-ASCII arguments on Windows.

Exit codes: `0` fine, `1` the call could not be made (no bridge, bad usage, transport error), `2`
the bridge ran the tool and the tool reported an error – so a script can tell "it failed" from "it
never ran".

## Self-update

```bash
edt-bridge-mcp self-update             # refresh the plugin jar (GitHub Releases) + the wrapper (PyPI)
edt-bridge-mcp self-update --jar-only  # only the jar
edt-bridge-mcp self-update --pip-only  # only the wrapper
edt-bridge-mcp self-update --pip-only --from <repo>/python   # from a checkout
```

A running EDT (GUI or headless) keeps the old jar loaded until it restarts; the wrapper
restarts its own headless instance on the next auto-start.

`--from` installs the wrapper from a local checkout instead of PyPI – for trying a build that is not
released yet, without a full `pipx install --force` (which rebuilds the venv and replaces the exe the
running client holds).

The wrapper updates itself by unpacking, not through an installer: it downloads the wheel from PyPI
(or copies the package out of the checkout given to `--from`) and replaces the package inside
`site-packages` using the standard library alone. No pip, no pipx, no build backend – which matters,
because pipx 1.15 builds its venvs through uv and a uv-built venv contains no pip at all.

The exes in `Scripts` are never touched: they are what a running client holds open, Windows will not
let them be replaced, and they do not need to be – the stub launches whatever code is in
site-packages the next time it starts. `pipx_metadata.json` is corrected so `pipx list` does not go
on reporting the old version. An editable install is refused rather than overwritten.

## Configuration

CLI flags override the environment.

| Env | Flag | Meaning |
|-----|------|---------|
| `EDT_BRIDGE_PORT` | `--port` | bridge port (default 8770) |
| `EDT_BRIDGE_TOKEN` | – | write-tools token, forwarded as `Authorization: Bearer` and injected into the headless JVM |
| `EDT_BRIDGE_WORKSPACE` | `--workspace` | EDT workspace path – required for the headless auto-start |
| `EDT_BRIDGE_EDT_DIR` | `--edt-dir` | EDT install dir (`.../1cedt`); newest install auto-detected when omitted |
| `EDT_BRIDGE_START_TIMEOUT` | `--start-timeout` | seconds to wait for a starting backend (default 360) |
| `EDT_BRIDGE_AUTOSTART` | `--no-autostart` | set `0`/pass the flag for proxy-only mode |

## Safety

- If a **GUI EDT is running but the bridge port is dead** (plugin missing there), the wrapper
  refuses to start a headless instance – the GUI holds the workspace lock. It still delivers
  the jar into `dropins/` when missing, so restarting that EDT activates the bridge.
- If a headless `1cedtcli` is already starting, the wrapper waits for it instead of spawning
  a second one.
