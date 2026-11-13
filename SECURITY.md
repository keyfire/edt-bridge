# Security Policy

**English** · [Русский](docs/ru/SECURITY.ru.md)

## Threat model

The server and write tools use the following restrictions:

- The MCP server binds **`127.0.0.1`** and nothing else. It never listens on a public interface.
- **EDT model-changing tools** require a configured token and return a plan until `apply=true`
  is passed. `edt_rename`, `edt_delete_object` and `edt_delete_method` also require `force`.
  Debug tools require a token as well. `edt_evaluate` runs arbitrary BSL
  against a live infobase, so it needs a per-call opt-in **and** a server-side switch that is off by
  default.
- The **shared-secret token** restricts access by other processes on the machine. Set
  `EDT_BRIDGE_TOKEN` or `-Dedt.bridge.token=`, then send `Authorization: Bearer <token>` or
  `X-Edt-Bridge-Token: <token>`. The token is optional, but any local process can reach the port;
  configure a token on a shared machine.
- The port comes from `EDT_BRIDGE_PORT` or `-Dedt.bridge.port=`, 8770 by default, and a busy one
  makes the server take the next free port. The rest is in
  [Environment variables](https://docs.keyfire.ru/edt-bridge/settings/#environment-variables).

## Supported versions

EDT-Bridge has not reached 1.0 yet, and fixes ship on the latest release line only. Reproduce the
problem on the most recent release before you report it.

| Version      | Supported |
|--------------|-----------|
| latest `0.x` | yes       |
| older        | no        |

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Report it privately through GitHub: on the
repository's **Security** tab choose **Report a vulnerability**, or open
<https://github.com/keyfire/edt-bridge/security/advisories/new>.

Include the EDT-Bridge version, your 1C:EDT version and OS, and the steps to reproduce. You will get
an acknowledgement, and the fix ships on the latest release line. Thank you for telling us privately
first.
