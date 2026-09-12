# Security Policy

**English** · [Русский](docs/ru/SECURITY.ru.md)

## Supported versions

EDT-Bridge has not reached 1.0 yet, and fixes ship on the latest release line only. Reproduce the
problem on the most recent release before you report it.

| Version      | Supported |
|--------------|-----------|
| latest `0.x` | yes       |
| older        | no        |

## Threat model

This is what the bridge guards by design.

- The MCP server binds **`127.0.0.1`** and nothing else. It never listens on a public interface.
- **Write tools are guarded.** Each one requires a configured token, returns a plan until you pass
  `apply=true`, and touches only your local EDT model. `edt_rename`, `edt_delete_object` and
  `edt_delete_method` need an explicit `force` on top of that. `edt_evaluate` runs arbitrary BSL
  against a live infobase, so it needs a per-call opt-in **and** a server-side switch that is off by
  default.
- Any local process can reach the port, so set a token on a shared machine: `EDT_BRIDGE_TOKEN` or
  `-Dedt.bridge.token=`.

The README's [Security](README.md#security) section has the full picture.

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Report it privately through GitHub: on the
repository's **Security** tab choose **Report a vulnerability**, or open
<https://github.com/keyfire/edt-bridge/security/advisories/new>.

Include the EDT-Bridge version, your 1C:EDT version and OS, and the steps to reproduce. You will get
an acknowledgement, and the fix ships on the latest release line. Thank you for telling us privately
first.
