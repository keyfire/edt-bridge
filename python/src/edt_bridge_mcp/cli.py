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
    edt-bridge-mcp wait-ready --project Demo --timeout 3600
    edt-bridge-mcp workspace pack --workspace <ws> --archive ready.tar.gz

Exit codes: 0 fine, 1 the call could not be made (no bridge, bad usage, transport error), 2 the
bridge ran the tool and the tool reported an error. `wait-ready` answers 1 when EDT is not running
or has exited and 2 when the time ran out with EDT still loading.
"""

from __future__ import annotations

import argparse
import json
import sys
import tarfile
import time
import urllib.error
from pathlib import Path

from . import __version__, i18n

BRIDGE_COMMANDS = ("call", "tools", "status", "wait-ready", "shutdown", "gui", "plugins")
COMMANDS = (*BRIDGE_COMMANDS, "workspace", "test-form")

_TOKEN_HINT = (
    "the bridge refused the request (401). Write tools - and, on a token-protected server, every "
    "tool - need EDT_BRIDGE_TOKEN set to the token shown on the EDT-Bridge preference page."
)


def add_connection_flags(parser: argparse.ArgumentParser) -> None:
    """Connection flags shared by the server mode and the commands that use it."""
    parser.add_argument("--workspace", help=i18n.t("conn.workspace"))
    parser.add_argument("--edt-dir", help=i18n.t("conn.edt-dir"))
    parser.add_argument("--port", type=int, help=i18n.t("conn.port"))
    parser.add_argument("--start-timeout", type=int, help=i18n.t("conn.start-timeout"))
    parser.add_argument("--no-autostart", action="store_true", help=i18n.t("conn.no-autostart"))
    parser.add_argument("--heap", help=i18n.t("conn.heap"))
    parser.add_argument("--vmargs", metavar="OPTIONS", help=i18n.t("conn.vmargs"))


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
    if command == "status":
        parser.add_argument("--all", dest="all_bridges", action="store_true", help=i18n.t("status.all"))
        parser.add_argument("--ports", type=_ports, help=i18n.t("status.ports"))
    if command == "shutdown":
        parser.add_argument("--force", action="store_true", help=i18n.t("shutdown.force"))
        parser.add_argument("--no-wait", action="store_true", help=i18n.t("shutdown.no-wait"))
    if command == "gui":
        parser.add_argument("--force", action="store_true", help=i18n.t("gui.force"))
        parser.add_argument("--timeout", type=int, default=90, help=i18n.t("gui.timeout"))
    if command == "wait-ready":
        parser.add_argument("--project", action="append", metavar="NAME", help=i18n.t("wait-ready.project"))
        parser.add_argument("--timeout", type=_positive, default=3600, help=i18n.t("wait-ready.timeout"))
        parser.add_argument("--interval", type=_positive, default=5, help=i18n.t("wait-ready.interval"))
    if command == "workspace":
        parser.add_argument("action", choices=("pack", "restore", "checks"), help=i18n.t("workspace.action"))
        parser.add_argument("--archive", metavar="PATH", help=i18n.t("workspace.archive"))
        parser.add_argument("--output", metavar="PATH", help=i18n.t("workspace.output"))
        parser.add_argument("--compare", metavar="PATH", help=i18n.t("workspace.compare"))
        parser.add_argument("--project", action="append", metavar="NAME", help=i18n.t("workspace.project"))
        parser.add_argument("--object", action="append", metavar="FQN", help=i18n.t("workspace.object"))
        parser.add_argument("--wait", type=_positive, default=600, help=i18n.t("workspace.wait"))
    if command == "test-form":
        from . import native_form
        native_form.add_arguments(parser)
    else:
        add_connection_flags(parser)
    parser.add_argument("--version", action="version", help=i18n.t("version"),
                        version=f"%(prog)s {__version__}")
    return parser


def _positive(text: str) -> int:
    try:
        value = int(text)
    except ValueError:
        value = 0
    if value < 1:
        raise argparse.ArgumentTypeError("use a whole number of seconds, 1 or more")
    return value


def _ports(text: str) -> list[int]:
    """A bounded list of explicit loopback targets, accepting comma-separated ports and ranges."""
    ports = set()
    try:
        for part in text.split(","):
            bounds = part.strip().split("-")
            if len(bounds) == 1:
                start = end = int(bounds[0])
            elif len(bounds) == 2:
                start, end = map(int, bounds)
            else:
                raise ValueError()
            if not 1 <= start <= end <= 65535 or end - start > 127:
                raise ValueError()
            ports.update(range(start, end + 1))
            if len(ports) > 128:
                raise ValueError()
    except ValueError:
        raise argparse.ArgumentTypeError("use ports 1..65535, up to 128 targets, e.g. 8770,8772-8774") from None
    return sorted(ports)


def _parse(command: str, argv: list[str]) -> argparse.Namespace:
    return build_parser(command).parse_args(argv)


def _tool_arguments(args: argparse.Namespace) -> dict:
    """The JSON object to pass as the tool's arguments; raises ValueError with a usable message."""
    if args.json_file:
        # utf-8-sig, not utf-8: the Windows shells write a byte order mark by default
        # (Out-File -Encoding utf8), and a strict reader answers "unexpected UTF-8 BOM" to a file
        # the person just wrote with the tool at hand.
        with open(args.json_file, encoding="utf-8-sig") as handle:
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


def _plugins_report() -> int:
    """The `plugins` command: what is plugged in, through which entry points, or why not.

    Needs no bridge and starts none - the plugins live in the wrapper's own environment.
    A discovery failure exits with 1 and the loader's message: this command is where a
    person looks when the MCP log says plugins are unavailable.
    """
    from . import plugins
    from .server import LOCAL_TOOL_NAMES

    if plugins.disabled():
        print(f"plugins are disabled ({plugins.ENV_DISABLE} is set)")
        return 0
    try:
        tools = plugins.plugin_tools(reserved=frozenset(LOCAL_TOOL_NAMES))
    except plugins.PluginError as bad:
        print(str(bad), file=sys.stderr)
        return 1
    packages = plugins.installed()
    if not tools and not packages:
        print(f"no plugins are installed (entry-point group {plugins.TOOLS_GROUP})")
        return 0
    for package in packages:
        print(f"{package['name']} {package['version']}".rstrip())
    by_source: dict[str, list[str]] = {}
    for tool in tools:
        by_source.setdefault(tool.source, []).append(tool.name)
    for source in sorted(by_source):
        print(f"  {source}: " + ", ".join(sorted(by_source[source])))
    return 0


def _call_plugin_tool(tool, args: argparse.Namespace, backend) -> int:
    """Run one plugin tool in-process - the same dispatch the MCP server applies.

    Exit codes follow the house rule: 1 the call could not be made (bad arguments JSON),
    2 the tool was judged or ran and refused - the same distinction bridge calls get.
    """
    from . import plugins
    from .server import unknown_arguments

    try:
        arguments = _tool_arguments(args)
    except ValueError as bad:
        print(str(bad), file=sys.stderr)
        return 1
    except OSError as bad:
        print(f"could not read the arguments: {bad}", file=sys.stderr)
        return 1
    misnamed = unknown_arguments(tool.name, arguments, tools=[tool.descriptor()])
    if misnamed:
        print(misnamed, file=sys.stderr)
        return 2
    try:
        if plugins.wants_bridge(tool.handler):
            answer = tool.handler(arguments, bridge=backend.call_tool)
        else:
            answer = tool.handler(arguments)
    except Exception as exc:  # the tool's own refusal or failure - report, not crash
        print(f"{tool.name} failed: {exc}", file=sys.stderr)
        return 2
    text = answer if isinstance(answer, str) else json.dumps(answer, ensure_ascii=False, indent=2)
    _print_result({"content": [{"type": "text", "text": text}]}, args.raw)
    return 0


def _shutdown(backend, args: argparse.Namespace) -> int:
    """Ask the bridge to stop the EDT behind it - the graceful end of a headless session.

    Deliberately never starts a backend: shutting down what is not running is a no-op, not a
    reason to launch one. Exit codes follow the house rule - 0 done (or nothing to do), 2 the
    bridge refused (a GUI EDT without --force), 1 the request could not be made.
    """
    if backend.status() is None:
        survivors = backend.headless_pids()
        if not survivors:
            print("no bridge is running in the scanned port range - nothing to shut down")
            return 0
        print(f"the bridge is silent, but its headless session is still there: {survivors}")
        return _end_headless_session(backend, args)
    try:
        answer = backend.shutdown(force=args.force)
    except urllib.error.HTTPError as http:
        if http.code == 401:
            print(_TOKEN_HINT, file=sys.stderr)
            return 1
        detail = ""
        try:
            detail = json.loads(http.read().decode("utf-8")).get("message", "")
        except (OSError, ValueError):
            pass
        if http.code == 409:
            print(detail or "the bridge refused: a GUI EDT is running - close it from its "
                            "window, or pass --force", file=sys.stderr)
            return 2
        print(detail or f"the bridge answered HTTP {http.code}", file=sys.stderr)
        return 1
    except urllib.error.URLError as url:
        print(f"could not reach the bridge: {url.reason}", file=sys.stderr)
        return 1
    print(answer.get("message", "shutting down"))
    if args.no_wait:
        return 0
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if backend.status() is None:
            print("the bridge is down")
            if not backend.headless_pids():
                return 0
            return _end_headless_session(backend, args)
        time.sleep(1)
    print("the bridge is still answering after 60 s - it may be finishing a long operation",
          file=sys.stderr)
    return 1


def _end_headless_session(backend, args: argparse.Namespace) -> int:
    """Wait the headless processes out after the port fell silent.

    A silent port is not an ended session: the CLI outlives the framework it hosted, and it keeps
    the workspace lock - the next start then fails with the lock held by a process nobody is
    looking at. So the session is reported down only when none of its processes is left.
    """
    stopped, left = backend.stop_headless(force=args.force, report=print)
    if stopped and not left:
        print("the headless session is down")
        return 0
    print("still running: " + ", ".join(str(pid) for pid in left)
          + ("" if args.force else " - pass --force to end them"), file=sys.stderr)
    return 2


#: How often wait-ready says how far the projects are while nothing changes, in seconds.
_PROGRESS_EVERY = 300


def _project_rows(status: dict) -> list[dict] | None:
    """The per-project rows of /status, or None for a bridge too old to report readiness."""
    rows = status.get("projects")
    if not isinstance(rows, list):
        return None
    return [row for row in rows if isinstance(row, dict) and isinstance(row.get("name"), str)]


def _readiness(rows: list[dict], names: list[str]) -> tuple[bool, list[dict]]:
    """Whether the projects are ready, and the state of each one waited for.

    Named projects must all be there and ready; one that is not in the workspace yet may still be
    imported, so it counts as not ready rather than as an error. Without names every open EDT
    project counts, and a workspace without any is not ready.
    """
    by_name = {row["name"]: row for row in rows}
    if names:
        state = [{"name": name, "modelReady": bool(by_name.get(name, {}).get("modelReady")),
                  **({} if name in by_name else {"present": False})} for name in names]
        return all(item["modelReady"] for item in state), state
    state = [{"name": row["name"], "modelReady": bool(row.get("modelReady"))}
             for row in rows if row.get("dtProject", True) and row.get("open", True)]
    return bool(state) and all(item["modelReady"] for item in state), state


def _edt_alive(backend) -> bool:
    """Whether an EDT is there for the bridge to come back from: a process, not the port."""
    return backend.launched_alive() or bool(backend.headless_pids()) or bool(backend.gui_pids())


def _wait_ready(backend, args: argparse.Namespace) -> int:
    """Wait until the EDT models of the projects are loaded - the step between a start and a job.

    The bridge answers long before a big project is indexed, and a call made in between is refused
    with "no BM model". This command polls /status, which the bridge serves apart from the tools,
    so a busy model does not slow the poll down. Exit codes: 0 ready, 1 no EDT to wait for (it did
    not start, exited, or autostart is off and nothing runs), 2 the time ran out while EDT runs.
    """
    names = list(dict.fromkeys(args.project or []))
    ok, why = backend.ensure(wait=False)
    if not ok and why != "starting":
        print(f"no bridge to wait for: {why}", file=sys.stderr)
        return 1
    started = time.monotonic()
    deadline = started + args.timeout
    last, last_said = None, started
    state: list[dict] = []

    def finish(code: int, reason: str | None) -> int:
        summary = {"ready": code == 0, "projects": state,
                   "waitedSeconds": round(time.monotonic() - started, 1)}
        if reason:
            summary["reason"] = reason
            print(reason, file=sys.stderr)
        _emit(json.dumps(summary, ensure_ascii=False, indent=2))
        return code

    while True:
        status = backend.status()
        if status is None:
            failure = backend.startup_failure()
            if failure:
                return finish(1, failure)
            if not _edt_alive(backend):
                return finish(1, "EDT is not running: it exited, or nothing serves the bridge port "
                                 f"{backend.port}" + (f" for workspace {backend.workspace}" if backend.workspace else ""))
        else:
            rows = _project_rows(status)
            if rows is None:
                return finish(1, f"the bridge {status.get('version')} does not report per-project "
                                 "readiness; update it (edt-bridge-mcp self-update)")
            ready, state = _readiness(rows, names)
            if ready:
                return finish(0, None)
            now = time.monotonic()
            if state != last or now - last_said >= _PROGRESS_EVERY:
                waiting = ", ".join(item["name"] + ("" if item.get("present", True) else " (not in the workspace)")
                                    for item in state if not item["modelReady"]) or "an open project"
                print(f"waiting for {waiting} ({round(now - started)} s)", file=sys.stderr)
                last, last_said = state, now
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            pending = [item["name"] for item in state if not item["modelReady"]]
            return finish(2, f"not ready after {args.timeout} s: "
                             + (", ".join(pending) if pending else "the bridge did not answer"))
        time.sleep(min(args.interval, remaining))


def _edt_on_workspace(backend):
    """The probe workspace pack and restore use: what still holds the workspace, or None."""
    def probe(path) -> str | None:
        backend.workspace = str(path)
        if backend.status() is not None:
            return "a bridge for it answers"
        owner = backend._workspace_owner()
        if owner is not None:
            return f"EDT process {owner.get('ProcessId', 'unknown')} runs on it"
        if backend._workspace_locked():
            return "its .metadata/.lock is held"
        return None
    return probe


def _workspace_checks(backend, args: argparse.Namespace) -> int:
    from . import workspace as packing

    if bool(args.output) == bool(args.compare):
        print("pass --output to save the checks or --compare to compare with saved ones", file=sys.stderr)
        return 1
    status = backend.status()
    if status is None:
        print("no bridge serves this workspace; start EDT and run wait-ready first", file=sys.stderr)
        return 1
    rows = _project_rows(status)
    if rows is None:
        print(f"the bridge {status.get('version')} does not report per-project readiness", file=sys.stderr)
        return 1
    ready, state = _readiness(rows, list(dict.fromkeys(args.project or [])))
    if not ready:
        pending = ", ".join(item["name"] for item in state if not item["modelReady"]) or "no open EDT project"
        print(f"not ready: {pending}; run wait-ready first", file=sys.stderr)
        return 1
    snapshot = {"workspace": status.get("workspace"), "bridgeVersion": status.get("version"), "projects": {}}
    # A whole large configuration can outgrow the problem limit; --object narrows the check to the
    # objects whose checks depend on the indexed model, the way a ready workspace is usually proven.
    targets = [(item["name"], fqn) for item in state for fqn in (args.object or [None])]
    for name, fqn in targets:
        key = packing.check_key(name, fqn)
        try:
            answer = json.loads(backend.call_tool("edt_project_errors",
                                                  packing.check_arguments(name, args.wait, fqn)))
        except (RuntimeError, ValueError) as failure:
            print(f"{key}: edt_project_errors failed: {failure}", file=sys.stderr)
            return 1
        project = packing.snapshot_project(answer)
        if project["truncated"]:
            print(f"{key}: the list of problems was cut at its limit; a partial list proves nothing either "
                  "way - narrow the check with --object", file=sys.stderr)
            return 1
        if not packing.usable(project):
            print(f"{key}: the validation did not finish (complete={project['complete']}, "
                  f"settled={project['settled']}); a partial list proves nothing either way - raise --wait",
                  file=sys.stderr)
            return 1
        snapshot["projects"][key] = project
    totals = {name: project["total"] for name, project in snapshot["projects"].items()}
    if args.output:
        try:
            with open(args.output, "x", encoding="utf-8", newline="") as handle:
                json.dump(snapshot, handle, ensure_ascii=False, indent=2)
        except OSError as failure:
            print(f"could not write {args.output}: {failure}", file=sys.stderr)
            return 1
        _emit(json.dumps({"saved": args.output, "totals": totals}, ensure_ascii=False, indent=2))
        return 0
    try:
        with open(args.compare, encoding="utf-8-sig") as handle:
            before = json.load(handle)["projects"]
    except (OSError, ValueError, KeyError, TypeError) as failure:
        print(f"could not read the saved checks {args.compare}: {failure}", file=sys.stderr)
        return 1
    if not args.project:
        # Without names the saved snapshot says which projects count.
        missing = sorted(set(before) - set(snapshot["projects"]))
        if missing:
            print("not in this workspace now: " + ", ".join(missing), file=sys.stderr)
    result = packing.compare(before, snapshot["projects"])
    _emit(json.dumps(result, ensure_ascii=False, indent=2))
    if not result["equal"]:
        print("the checks of EDT differ from the saved ones", file=sys.stderr)
    return 0 if result["equal"] else 2


def _workspace(backend, args: argparse.Namespace) -> int:
    """pack, restore or compare the checks of a workspace - see the workspace module."""
    from . import workspace as packing

    if args.action == "checks":
        return _workspace_checks(backend, args)
    if not args.archive:
        print(f"workspace {args.action} needs --archive", file=sys.stderr)
        return 1
    try:
        if args.action == "pack":
            if not backend.workspace:
                print("workspace pack needs --workspace", file=sys.stderr)
                return 1
            result = packing.pack(Path(backend.workspace), Path(args.archive), edt_running=_edt_on_workspace(backend))
        else:
            result = packing.restore(Path(args.archive), Path(args.workspace) if args.workspace else None,
                                     edt_running=_edt_on_workspace(backend))
    except (packing.WorkspaceError, OSError, ValueError) as failure:
        print(f"workspace {args.action} refused: {failure}", file=sys.stderr)
        return 1
    except tarfile.TarError as failure:
        print(f"workspace {args.action} failed: the archive cannot be read: {failure}", file=sys.stderr)
        return 1
    _emit(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def _with_jar_on_disk(status: dict) -> dict:
    """The bridge's own status plus the jar lying in dropins - the code it would load next.

    A headless session that started before the jar was replaced keeps answering with the old
    version, and nothing said so: `/status` reported 0.21.0 while 0.22.0 lay in dropins, and the
    session went on believing the new tools were there. The jar is read here, in the wrapper -
    the running bridge cannot see what replaced its own file. `current` is false when the two
    differ, which is the sign to restart EDT (`edt-bridge-mcp shutdown`).
    """
    from .update import installed_jar

    found = installed_jar()
    if found is None:
        return status
    version, path = found
    live = status.get("version")
    return {**status, "jarOnDisk": {
        "version": version,
        "path": str(path),
        "current": bool(live) and version.startswith(str(live)),
    }}


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

    if command == "plugins":
        return _plugins_report()

    if command == "test-form":
        from . import native_form
        result = native_form.run(args)
        _emit(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["ok"] else (1 if result["stage"] == "validation" else 2)

    from . import toolsets
    from .server import LOCAL_TOOLS, Backend, apply_connection_options, load_plugin_tools
    apply_connection_options(args)
    backend = Backend()

    plugin_tools, plugin_error = ({}, None)
    selection = toolsets.Selection()
    if command in ("call", "tools"):
        plugin_tools, plugin_error = load_plugin_tools()
        if plugin_error:
            print(f"plugins are unavailable: {plugin_error}", file=sys.stderr)
        # The shell commands show and run what an MCP client is shown and may run.
        selection = toolsets.active()
        if selection.problem:
            print(selection.problem, file=sys.stderr)
            return 1

    arguments = None
    if command == "call" and args.tool == toolsets.HELP_TOOL:
        # The bridge describes its own tools. A tool served by the wrapper or a plugin is
        # described here, without a bridge - the MCP server answers the same call the same way.
        try:
            arguments = _tool_arguments(args)
        except ValueError as bad:
            print(str(bad), file=sys.stderr)
            return 1
        except OSError as bad:
            print(f"could not read the arguments: {bad}", file=sys.stderr)
            return 1
        served = list(LOCAL_TOOLS) + [plugin_tools[name].descriptor() for name in sorted(plugin_tools)]
        described = next((tool for tool in served if tool["name"] == arguments.get("tool")), None)
        if described is not None:
            _print_result({"content": [{"type": "text",
                                        "text": toolsets.describe(described, selection)}]}, args.raw)
            return 0

    if command == "call" and args.tool in plugin_tools:
        descriptor = plugin_tools[args.tool].descriptor()
        if not selection.shows(descriptor):
            print(selection.refusal(descriptor), file=sys.stderr)
            return 2
        # A plugin tool runs in the wrapper and needs no EDT - calling one must not
        # spend minutes starting a headless session. The backend still travels along:
        # a handler declaring `bridge` may enrich its answer from a bridge that is
        # ALREADY up (and degrades itself when none is).
        return _call_plugin_tool(plugin_tools[args.tool], args, backend)

    if command == "status":
        if args.ports is not None and not args.all_bridges:
            print("--ports requires --all", file=sys.stderr)
            return 1
        if args.all_bridges:
            try:
                rows = backend.status_all(args.ports)
            except ValueError as invalid:
                print(str(invalid), file=sys.stderr)
                return 1
            from .update import installed_jar, plugin_version_gap
            bridges = []
            for row in rows:
                if row["state"] != "bridge":
                    continue
                row = {**row, "wrapperVersion": __version__}
                gap = plugin_version_gap(row.get("version"))
                if gap:
                    print(f"port {row['port']}: {gap}", file=sys.stderr)
                install = row.get("edtInstall")
                found = installed_jar(Path(install) / "dropins") if install else None
                if found:
                    version, path = found
                    row = {**row, "jarOnDisk": {"version": version, "path": str(path),
                            "current": bool(row.get("version")) and version.startswith(str(row["version"]))}}
                bridges.append(row)
            _emit(json.dumps({"bridges": bridges,
                  "unavailable": [r for r in rows if r["state"] == "unavailable"],
                  "otherServices": [r for r in rows if r["state"] not in ("bridge", "unavailable")]},
                  ensure_ascii=False, indent=2))
            return 0 if bridges else 1
        status = backend.status()
        if status is None:
            print("no bridge is running in the scanned port range", file=sys.stderr)
            return 1
        status = {**_with_jar_on_disk(status), "wrapperVersion": __version__}
        _emit(json.dumps(status, ensure_ascii=False, indent=2))
        jar = status.get("jarOnDisk")
        if jar and not jar["current"]:
            # A false flag inside a long json is easy to read past; the sentence is not.
            print(f"the jar in dropins is {jar['version']}, the bridge answering is "
                  f"{status.get('version')} - restart EDT (edt-bridge-mcp shutdown) to load it",
                  file=sys.stderr)
        return 0

    if command == "shutdown":
        return _shutdown(backend, args)

    if command == "wait-ready":
        return _wait_ready(backend, args)

    if command == "workspace":
        return _workspace(backend, args)

    if command == "gui":
        # Never starts a backend on the way: the point of the command is to END the headless
        # session, and autostart would race the GUI for the workspace lock.
        ok, lines = backend.open_gui(force=args.force, timeout=args.timeout)
        for line in lines:
            print(line, file=sys.stdout if ok else sys.stderr)
        return 0 if ok else 1

    ready, why = backend.ensure(wait=True)
    if not ready:
        print(f"no bridge to call: {why}", file=sys.stderr)
        return 1

    try:
        if command == "tools":
            answer = _forward(backend, {"jsonrpc": "2.0", "id": 1, "method": "tools/list",
                                        "params": {}})
        else:
            if arguments is None:  # already read for edt_tool_help; stdin is read once
                arguments = _tool_arguments(args)
            answer = _forward(backend, {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                        "params": {"name": args.tool, "arguments": arguments}})
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
        served = result.get("tools", [])
        names = {tool.get("name") for tool in served}
        served = served + [plugin_tools[name].descriptor()
                           for name in sorted(plugin_tools) if name not in names]
        served = [tool for tool in served if selection.shows(tool)]
        if args.raw:
            _emit(json.dumps(dict(result, tools=served), ensure_ascii=False, indent=2))
        else:
            for tool in served:
                _emit(tool.get("name", ""))
        return 0

    if args.tool == toolsets.HELP_TOOL:
        # The bridge judged the name against its own tools only.
        toolsets.add_wrapper_names(
            result, arguments.get("tool"),
            [tool["name"] for tool in LOCAL_TOOLS] + sorted(plugin_tools))
        toolsets.agree_with_selection(result, selection)
    _print_result(result, args.raw)
    # A tool that reports isError has run and failed - worth a distinct code so scripts can tell it
    # apart from "the call never happened".
    return 2 if result.get("isError") else 0
