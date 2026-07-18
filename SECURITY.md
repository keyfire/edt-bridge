# Security Policy

**English** · [Русский](SECURITY.ru.md)

## Supported versions

edt-bridge is pre-1.0 and ships fixes on the latest release line only. Please reproduce on the most
recent release before reporting.

| Version      | Supported |
|--------------|-----------|
| latest `0.x` | yes       |
| older        | no        |

## Threat model (by design)

- The MCP server binds **`127.0.0.1` only** – never a public interface.
- **Write tools are gated:** each requires a configured token, defaults to a dry-run (`apply=false`),
  and acts only on your local EDT model. `edt_rename`, `edt_delete_object` and `edt_delete_method`
  additionally require an explicit `force`; `edt_evaluate` (arbitrary BSL against a live infobase)
  additionally requires a per-call opt-in **and** a server-side switch that is off by default.
- Any local process can reach the port, so on shared machines set a token
  (`EDT_BRIDGE_TOKEN` / `-Dedt.bridge.token=`).

The README [Security](README.md#security) section has the full picture.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** Report it privately through GitHub:
on the repository's **Security** tab choose **Report a vulnerability** (GitHub private vulnerability
reporting), or open <https://github.com/keyfire/edt-bridge/security/advisories/new>.

Include the edt-bridge version, your 1C:EDT version and OS, and steps to reproduce. You can expect
an acknowledgement; fixes ship on the latest release line. Thank you for reporting responsibly.
