---
title: "Security"
description: "How writes are gated, what the token protects, and how to report a security problem privately."
sidebar:
  label: Security
  order: 6
---

- Binds **`127.0.0.1` only** – never a public interface.
- **Writes are gated**: every write tool requires a configured token, defaults to a dry-run, and
  operates only on your local EDT model; `edt_rename`, `edt_delete_object` and `edt_delete_method`
  additionally need an explicit `force`.
- Optional **shared-secret token** – set `EDT_BRIDGE_TOKEN` (or `-Dedt.bridge.token=`) and send
  `Authorization: Bearer <token>` (or `X-Edt-Bridge-Token: <token>`). Any local process can reach
  the port, so set a token on shared machines.
- Port: `EDT_BRIDGE_PORT` / `-Dedt.bridge.port=` (default 8770; the next free port is used if busy).
- Found a security problem? Report it **privately** – see [SECURITY.md](SECURITY.md), not a public issue.

**English** · [Русский](docs/ru/SECURITY.ru.md)

## Supported versions

EDT-Bridge is pre-1.0 and ships fixes on the latest release line only. Please reproduce on the most
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

Include the EDT-Bridge version, your 1C:EDT version and OS, and steps to reproduce. You can expect
an acknowledgement; fixes ship on the latest release line. Thank you for reporting responsibly.
