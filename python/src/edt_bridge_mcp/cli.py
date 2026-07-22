"""Command-line front-end for the bridge: call one tool and print what it returned.

The default mode of ``edt-bridge-mcp`` speaks JSON-RPC over stdin/stdout, which no human and no
shell script can drive. These sub-commands reach the same running bridge through the same
:class:`~edt_bridge_mcp.server.Backend` - the port scan, the token and the headless auto-start all
behave as they do for an MCP client:

    edt-bridge-mcp tools
    edt-bridge-mcp call edt_projects
    edt-bridge-mcp call edt_metadata_details --json '{"fqn": "CommonModule.Foo"}'
    edt-bridge-mcp call edt_create_extension --json-file args.json
    edt-bridge-mcp status

Exit codes: 0 fine, 1 the call could not be made (no bridge, bad usage, transport error), 2 the
bridge ran the tool and the tool reported an error.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error

from . import __version__, i18n

COMMANDS = ("call", "tools", "status")

_TOKEN_HINT = (
    "the bridge refused the request (401). Write tools - and, on a token-protected server, every "
    "tool - need EDT_BRIDGE_TOKEN set to the token shown on the EDT-Bridge preference page."
)


def add_connection_flags(parser: argparse.ArgumentParser) -> None:
    """Bridge connection flags - the same set for the server mode and for every command."""
    parser.add_argument("--workspace", help=i18n.t("conn.workspace"))
    parser.add_argument("--edt-dir", help=i18n.t("conn.edt-dir"))
    parser.add_argument("--port", type=int, help=i18n.t("conn.port"))
    parser.add_argument("--start-timeout", type=int, help=i18n.t("conn.start-timeout"))
    parser.add_argument("--no-autostart", action="store_true", help=i18n.t("conn.no-autostart"))


def build_parser(command: str) -> argparse.ArgumentParser:
    """The parser of one shell command; separate from parsing so tests can walk it."""
    parser = i18n.ArgumentParser(
        prog=f"edt-bridge-mcp {command}",
        description=i18n.t(f"{command}.description"),
    )
    if command == "call":
        parser.add_argument("tool", help=i18n.t("call.tool"))
        source = parser.add_mutually_exclusive_group()
        source.add_argument("--json", dest="json_text", metavar="JSON",
                            help=i18n.t("call.json"))
        source.add_argument("--json-file", metavar="PATH", help=i18n.t("call.json-file"))
        source.add_argument("--stdin", action="store_true", help=i18n.t("call.stdin"))
    if command in ("call", "tools"):
        parser.add_argument("--raw", action="store_true", help=i18n.t("call.raw"))
    add_connection_flags(parser)
    parser.add_argument("--version", action="version", help=i18n.t("version"),
                        version=f"%(prog)s {__version__}")
    return parser


def _parse(command: str, argv: list[str]) -> argparse.Namespace:
    return build_parser(command).parse_args(argv)


def _tool_arguments(args: argparse.Namespace) -> dict:
    """The JSON object to pass as the tool's arguments; raises ValueError with a usable message."""
    if args.json_file:
        with open(args.json_file, encoding="utf-8") as handle:
            text = handle.read()
    elif args.json_text:
        text = args.json_text
    elif args.stdin:
        text = sys.stdin.read()
    else:
        return {}
    text = text.strip()
    if not text:
        return {}
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as bad:
        raise ValueError(f"arguments are not valid JSON: {bad}") from bad
    if not isinstance(parsed, dict):
        raise ValueError("arguments must be a JSON object, e.g. {\"projectName\": \"SM\"}")
    return parsed


def _forward(backend, payload: dict) -> dict:
    """One JSON-RPC round trip, with transport failures turned into readable messages."""
    try:
        return backend.forward(payload)
    except urllib.error.HTTPError as http:
        if http.code == 401:
            raise ValueError(_TOKEN_HINT) from http
        raise ValueError(f"the bridge answered HTTP {http.code}") from http
    except urllib.error.URLError as url:
        raise ValueError(f"could not reach the bridge: {url.reason}") from url


def _emit(text: str) -> None:
    """Print one chunk, treating a closed pipe as the end of the job rather than a crash.

    Piping into ``head`` is the normal way to look at a big result; without this it ends in a
    BrokenPipeError traceback.
    """
    try:
        print(text)
    except BrokenPipeError:
        raise _PipeClosed from None


class _PipeClosed(Exception):
    """The consumer stopped reading - stop writing, quietly."""


def _print_result(result: dict, raw: bool) -> None:
    if raw:
        _emit(json.dumps(result, ensure_ascii=False, indent=2))
        return
    for item in result.get("content", []):
        if item.get("type", "text") == "text":
            _emit(item.get("text", ""))


def run(command: str, argv: list[str]) -> int:
    try:
        return _run(command, argv)
    except _PipeClosed:
        # Downstream (head, less, a script) stopped reading. Detach stdout so the interpreter does
        # not retry the flush at shutdown and print the traceback we just avoided.
        try:
            sys.stdout.close()
        except BrokenPipeError:
            pass
        return 0


def _run(command: str, argv: list[str]) -> int:
    args = _parse(command, argv)

    from .server import Backend, apply_connection_options
    apply_connection_options(args)
    backend = Backend()

    if command == "status":
        status = backend.status()
        if status is None:
            print("no bridge is running in the scanned port range", file=sys.stderr)
            return 1
        _emit(json.dumps(status, ensure_ascii=False, indent=2))
        return 0

    ready, why = backend.ensure(wait=True)
    if not ready:
        print(f"no bridge to call: {why}", file=sys.stderr)
        return 1

    try:
        if command == "tools":
            answer = _forward(backend, {"jsonrpc": "2.0", "id": 1, "method": "tools/list",
                                        "params": {}})
        else:
            answer = _forward(backend, {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                        "params": {"name": args.tool,
                                                   "arguments": _tool_arguments(args)}})
    except ValueError as bad:
        print(str(bad), file=sys.stderr)
        return 1
    except OSError as bad:
        print(f"could not read the arguments: {bad}", file=sys.stderr)
        return 1

    if "error" in answer:
        error = answer["error"]
        print(f"bridge error {error.get('code')}: {error.get('message')}", file=sys.stderr)
        return 1
    result = answer.get("result", {})

    if command == "tools":
        if args.raw:
            _emit(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            for tool in result.get("tools", []):
                _emit(tool.get("name", ""))
        return 0

    _print_result(result, args.raw)
    # A tool that reports isError has run and failed - worth a distinct code so scripts can tell it
    # apart from "the call never happened".
    return 2 if result.get("isError") else 0
