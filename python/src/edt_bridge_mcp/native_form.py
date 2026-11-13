"""Check managed forms through the platform's native TestClient mode."""

from __future__ import annotations

import contextlib
import datetime
import json
import os
import re
import secrets
import select
import shutil
import signal
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from . import event_log, i18n, native_report, native_scenario


_SCOPES = ("empty", "fixture", "application")
_START_FLAGS = ("/DisableStartupDialogs", "/DisableStartupMessages", "/DisableSplash")
_ACCESS_DENIED = (
    "access denied", "insufficient rights", "insufficient access rights",
    "доступ запрещен", "доступ запрещён", "недостаточно прав", "нарушение прав доступа",
)
#: A license that is missing or taken. The free-license texts are those of the platform resources
#: (backbas_ru.res, backbas_root.res): "Не обнаружено свободной лицензии!" / "There are no free licenses!",
#: and the COM connection and server variants "... did not detect any free license!".
_LICENSE_MISSING = ("license not found", "no license found", "не найдена лицензия",
                    "свободной лицензии", "no free licenses", "any free license")
#: The conf.cfg settings the client of the check depends on.
_CONF = "conf.cfg"
_UNSAFE_SETTING = "DisableUnsafeActionProtection"
_LANGUAGE_SETTING = "SystemLanguage"
#: The directory prefix edt_test_form copies its test infobase under.
_RUN_COPY_PREFIX = "edt-bridge-form-run-"


class _CheckError(Exception):
    def __init__(self, status: str, message: str):
        super().__init__(message)
        self.status = status


def _platform_failure_status(interaction_error, logs, *, connecting=False):
    logs = logs.casefold()
    detail = f"{str(interaction_error).casefold()} {logs}"
    if any(message in detail for message in _ACCESS_DENIED):
        return "access_denied"
    license_detail = detail if connecting else logs
    if any(message in license_detail for message in _LICENSE_MISSING):
        return "license_unavailable"
    return None


def _record_error(report, error, fallback):
    status = "access_denied" if isinstance(error, PermissionError) else getattr(error, "status", fallback)
    report.update(ok=False, status=status, error=str(error))


def _is_regular_file(path):
    try:
        return stat.S_ISREG(path.stat().st_mode)
    except (FileNotFoundError, NotADirectoryError):
        return False


def add_arguments(parser):
    parser.add_argument("--platform-bin", required=True, type=Path,
                        help=i18n.t("test-form.platform-bin"))
    parser.add_argument("--test-infobase", required=True, type=Path,
                        help=i18n.t("test-form.test-infobase"))
    parser.add_argument("--scope", required=True, choices=_SCOPES, help=i18n.t("test-form.scope"))
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--epf", type=Path, help=i18n.t("test-form.epf"))
    target.add_argument("--erf", type=Path, help=i18n.t("test-form.erf"))
    target.add_argument("--navigation-link", help=i18n.t("test-form.navigation-link"))
    parser.add_argument("--form", required=True, help=i18n.t("test-form.form"))
    parser.add_argument("--form-title", help=i18n.t("test-form.form-title"))
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--button", help=i18n.t("test-form.button"))
    operation.add_argument("--scenario", type=Path, help=i18n.t("test-form.scenario"))
    parser.add_argument("--expect-message", help=i18n.t("test-form.expect-message"))
    parser.add_argument("--user", help=i18n.t("test-form.user"))
    parser.add_argument("--output-dir", required=True, type=Path, help=i18n.t("test-form.output-dir"))
    parser.add_argument("--timeout", type=int, default=120, help=i18n.t("test-form.timeout"))


def _form_key(name):
    parts = name.split(".")
    kinds = {"ExternalDataProcessor": "ExternalDataProcessor", "ВнешняяОбработка": "ExternalDataProcessor",
             "ExternalReport": "ExternalReport", "ВнешнийОтчет": "ExternalReport",
             "Document": "Document", "Документ": "Document", "Catalog": "Catalog", "Справочник": "Catalog"}
    if (len(parts) == 3 and kinds.get(parts[0]) == "ExternalReport"
            and parts[1].isidentifier() and parts[2] in ("Form", "Форма")):
        return "ExternalReport", parts[1].casefold(), None
    if (len(parts) == 4 and parts[0] in kinds
            and parts[2] in ("Form", "Форма") and all(part.isidentifier() for part in parts)):
        return kinds[parts[0]], parts[1].casefold(), parts[3].casefold()
    return None


def _validate_navigation_link(link, form):
    match = re.fullmatch(r"e1cib/data/(Document|Документ|Catalog|Справочник)\.([^.?/#]+)\?ref=([a-fA-F0-9]{32})", link)
    key = _form_key(form)
    target_key = _form_key(match[1] + "." + match[2] + ".Form.Main") if match else None
    if (target_key is None or key is None or key[0] not in ("Document", "Catalog")
            or target_key[:2] != key[:2]):
        raise _CheckError("invalid_input", "Use an internal e1cib/data object link matching the selected form.")
    return link


def _validated_paths(args):
    if sys.platform != "linux":
        raise _CheckError("unsupported", "The native form check requires isolated Linux with Xvfb.")
    if not 1 <= args.timeout <= 900:
        raise _CheckError("invalid_input", "Timeout must be between 1 and 900 seconds.")
    key = _form_key(args.form)
    if key is None:
        raise _CheckError("invalid_input", "Use the fully qualified managed form name of the selected target.")
    # The platform reads * and ? in a title as wildcards. A form title may carry them: the form
    # is still selected by its exact name. A button has no second selector, so it stays exact.
    if args.form_title is not None and (not args.form_title.strip("*? \t") or "\x00" in args.form_title):
        raise _CheckError("invalid_input", "Use a form title with text in it; * and ? are wildcards.")
    if args.button is not None and (not args.button.strip() or any(c in args.button for c in "*?\x00")):
        raise _CheckError("invalid_input", "Use an exact, nonempty button selector.")
    if args.scenario and args.expect_message:
        raise _CheckError("invalid_input", "Use assert_messages inside a scenario instead of --expect-message.")
    base = args.test_infobase.resolve()
    if not _is_regular_file(base / "1Cv8.1CD"):
        raise _CheckError("invalid_input", "The test infobase must contain 1Cv8.1CD.")
    external = args.epf or args.erf
    if external:
        external = external.resolve()
        extension, kind = (".epf", "ExternalDataProcessor") if args.epf else (".erf", "ExternalReport")
        if not _is_regular_file(external) or external.suffix.lower() != extension or key[0] != kind:
            raise _CheckError("invalid_input", "Select an existing EPF/ERF file and its matching main form.")
    else:
        _validate_navigation_link(args.navigation_link, args.form)
        if not args.scenario:
            raise _CheckError("invalid_input", "A configuration form requires a scenario with result assertions.")
    output = args.output_dir.resolve()
    if output.is_relative_to(base) or output.exists():
        raise _CheckError("invalid_input", "Select a new output directory outside the test infobase.")
    return base, external, output


def _seconds_left(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise _CheckError("timeout", "The native form check reached its time limit.")
    return remaining


def _stop_group(process):
    with contextlib.suppress(ProcessLookupError):
        os.killpg(process.pid, signal.SIGTERM)
    with contextlib.suppress(subprocess.TimeoutExpired):
        process.wait(timeout=2)
    # The group can still contain a platform subprocess after its leader has exited.
    with contextlib.suppress(ProcessLookupError):
        os.killpg(process.pid, signal.SIGKILL)
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired as error:
        raise _CheckError("cleanup_failed", "An owned native process did not exit.") from error


def _spawn(stack, argv, env, log, *, pass_fds=()):
    with log.open("wb") as output:
        process = subprocess.Popen(argv, cwd=log.parent, env=env, stdin=subprocess.DEVNULL, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True,
                                   pass_fds=pass_fds)
    stack.callback(_stop_group, process)
    return process


def _display(stack, xvfb, root, deadline):
    root.mkdir()
    auth = root / "Xauthority"
    protocol = b"MIT-MAGIC-COOKIE-1"
    cookie = secrets.token_bytes(16)
    # Xauthority FamilyWild with empty address and display fields. Only these processes see the cookie.
    auth.write_bytes(struct.pack(">HHHH", 0xFFFF, 0, 0, len(protocol)) + protocol
                     + struct.pack(">H", len(cookie)) + cookie)
    home = root / "home"
    home.mkdir()
    runtime = root / "runtime"
    runtime.mkdir(mode=0o700)
    env = dict(os.environ, HOME=str(home), XAUTHORITY=str(auth),
               XDG_CONFIG_HOME=str(home / ".config"), XDG_DATA_HOME=str(home / ".local/share"),
               XDG_CACHE_HOME=str(home / ".cache"), XDG_STATE_HOME=str(home / ".local/state"),
               XDG_RUNTIME_DIR=str(runtime))
    read_fd, write_fd = os.pipe()
    try:
        try:
            _spawn(stack, [xvfb, "-displayfd", str(write_fd), "-screen", "0", "1280x900x24",
                           "-nolisten", "tcp", "-auth", str(auth)], env, root / "xvfb.log",
                   pass_fds=(write_fd,))
        finally:
            os.close(write_fd)
        ready, _, _ = select.select([read_fd], [], [], min(10, _seconds_left(deadline)))
        number = os.read(read_fd, 32).strip() if ready else b""
        if not number.isdigit():
            raise _CheckError("display_failed", "Xvfb did not allocate a private display.")
    finally:
        os.close(read_fd)
    return dict(env, DISPLAY=":" + number.decode("ascii"))


def _log(path):
    if not path.exists():
        return ""
    with path.open("rb") as stream:
        data = stream.read(16_384)
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16", errors="replace").strip()
    return data.decode("utf-8-sig", errors="replace").strip()


def _require_exit(process, deadline, message):
    try:
        code = process.wait(timeout=_seconds_left(deadline))
    except subprocess.TimeoutExpired as error:
        raise _CheckError("timeout", "The native form check reached its time limit.") from error
    if code != 0:
        raise _CheckError("setup_failed", f"{message} (exit code {code}).")


def _free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def _owns_listener(process, port):
    # The port reservation has to be released before 1C binds. Check socket ownership before connecting.
    port_suffix = f":{port:04X}"
    listening = set()
    for table in (Path("/proc/net/tcp"), Path("/proc/net/tcp6")):
        if table.is_file():
            for row in table.read_text().splitlines()[1:]:
                fields = row.split()
                if fields[1].endswith(port_suffix) and fields[3] == "0A":
                    listening.add(f"socket:[{fields[9]}]")
    if not listening:
        return False
    owned = set()
    for proc in Path("/proc").iterdir():
        if not proc.name.isdecimal():
            continue
        with contextlib.suppress(ProcessLookupError, PermissionError, FileNotFoundError):
            if os.getpgid(int(proc.name)) == process.pid:
                for fd in (proc / "fd").iterdir():
                    with contextlib.suppress(FileNotFoundError, PermissionError):
                        owned.add(os.readlink(fd))
    return listening <= owned


def _wait_for_client(client, port, deadline):
    while not _owns_listener(client, port):
        if client.poll() is not None:
            raise _CheckError("startup_failed", "The test client exited before opening its listener.")
        time.sleep(min(0.2, _seconds_left(deadline)))


def _capture(stack, env, root, output):
    image = root / "screenshot.png"
    script = Path(__file__).with_name("_x11_capture.py")
    log = root / "screenshot.log"
    process = _spawn(stack, [sys.executable, "-B", str(script), str(image)], env, log)
    try:
        _require_exit(process, time.monotonic() + 3, "Could not capture the Xvfb display")
        with image.open("rb") as stream:
            if stream.read(8) != b"\x89PNG\r\n\x1a\n" or image.stat().st_size <= 8:
                raise OSError("The screenshot is not a PNG image.")
    except (OSError, _CheckError) as error:
        return {"screenshotError": _log(log) or str(error)}
    destination = output / "screenshot.png"
    shutil.copyfile(image, destination)
    return {"screenshot": str(destination)}


def _run_session(args, base, external, output, root, manager_base, manager_env, enterprise,
                 xvfb, deadline, steps, launch_external=None):
    report = {"ok": False, "stage": "client_startup"}
    root.mkdir()
    progress = root / "manager-progress.json"
    client_log = root / "client-platform.log"
    manager_log = root / "manager-platform.log"
    client_process_log = root / "client-process.log"
    manager_process_log = root / "manager-process.log"
    client = None
    manager = None
    with contextlib.ExitStack() as stack:
        try:
            client_env = _display(stack, xvfb, root / "client-display", deadline)
            port = _free_port()
            auth = ["/N" + args.user] if args.user else []
            target = (["/Execute", str(launch_external or external)] if external
                      else ["/URL", args.navigation_link])
            client = _spawn(stack, [*enterprise, "/F" + str(base), *auth, "/TestClient", f"-TPort{port}",
                                   *target, "/Out", str(client_log)],
                            client_env, client_process_log)
            _wait_for_client(client, port, deadline)

            report["stage"] = "form_interaction"
            request = root / "request.json"
            result = root / "manager-result.json"
            # No title: the manager waits for the form by its name.
            payload = {"port": port, "form": args.form, "formTitle": args.form_title or "",
                       "button": args.button or "", "expectedMessage": args.expect_message or "",
                       "waitSeconds": min(10, max(1, int(_seconds_left(deadline) / 2))),
                       "result": str(result), "progress": str(progress)}
            if steps is not None:
                payload.update(steps=steps, maxRows=native_scenario.MAX_ROWS,
                               maxColumns=native_scenario.MAX_COLUMNS, maxItems=native_scenario.MAX_ITEMS,
                               maxListed=native_scenario.MAX_LISTED)
            request.write_text(json.dumps(payload), encoding="utf-8", newline="")
            manager_args = [*enterprise, "/F" + str(manager_base), "/TestManager",
                            "/C" + str(request), "/Out", str(manager_log)]
            manager_stack = stack.enter_context(contextlib.ExitStack())
            report["connectAttempts"] = 0
            while True:
                _seconds_left(deadline)
                manager = _spawn(manager_stack, manager_args, manager_env, manager_process_log)
                report["connectAttempts"] += 1
                while not result.is_file():
                    if client.poll() is not None or manager.poll() is not None:
                        raise _CheckError("session_exited", "A native session exited before reporting a result.")
                    time.sleep(min(0.15, _seconds_left(deadline)))
                if result.stat().st_size > 16_777_216:
                    raise _CheckError("driver_failed", "The test manager result exceeds 16 MiB.")
                data = json.loads(result.read_text(encoding="utf-8-sig"))
                if not isinstance(data, dict) or not isinstance(data.get("status"), str):
                    raise _CheckError("driver_failed", "The test manager returned an invalid result.")
                report.update(interaction=data, status=data["status"])
                if isinstance(data.get("observedFields"), dict):
                    # A selector that missed: the fields of the open form, where the caller looks first.
                    report["observedFields"] = data["observedFields"]
                if isinstance(data.get("dialog"), dict):
                    # A window over the form that no step expected: what the solution showed, not a failure
                    # of the check, so the caller can judge it.
                    report["dialog"] = data["dialog"]
                # The owned listener can open before the platform accepts a test connection.
                if data["status"] != "driver_failed" or data.get("stage") != "connect":
                    break
                logs = " ".join(_log(path) for path in
                                (client_log, manager_log, client_process_log, manager_process_log))
                failure = _platform_failure_status(data.get("error", ""), logs, connecting=True)
                if failure:
                    report["status"] = failure
                    break
                manager_stack.close()
                manager = None
                time.sleep(min(1, _seconds_left(deadline)))
                _seconds_left(deadline)
                if client.poll() is not None or not _owns_listener(client, port):
                    break
                result.unlink()
                progress.unlink(missing_ok=True)
            if steps is not None:
                if data["status"] == "scenario_observed":
                    report.update(native_scenario.evaluate(steps, data.get("observations")))
            elif data["status"] == "command_completed":
                messages = data.get("messages")
                if (data.get("clickReturned") is not True or not args.expect_message
                        or not isinstance(messages, list) or args.expect_message not in messages):
                    raise _CheckError("driver_failed", "The test manager did not report the expected result.")
                report["ok"] = True
        except (_CheckError, OSError, ValueError) as error:
            _record_error(report, error, "driver_failed")
            if progress.is_file():
                with contextlib.suppress(OSError, ValueError):
                    report["lastInteraction"] = json.loads(progress.read_text(encoding="utf-8-sig"))
        finally:
            if client is not None:
                try:
                    report.update(_capture(stack, client_env, root, output))
                except OSError as error:
                    report["screenshotError"] = str(error)
            for key, process in (("clientExitCode", client), ("managerExitCode", manager)):
                if process is not None and process.poll() is not None:
                    report[key] = process.poll()
            for key, log in (("clientLog", client_log), ("managerLog", manager_log),
                             ("clientProcessLog", client_process_log),
                             ("managerProcessLog", manager_process_log),
                             ("displayLog", root / "client-display/xvfb.log")):
                detail = _log(log)
                if detail:
                    report[key] = detail
            if report["ok"] and "screenshot" not in report:
                report.update(ok=False, status="screenshot_failed")
    report["cleanupComplete"] = True
    return report


def _conf_files(platform):
    """The conf.cfg files the client of the check reads: the version's, the directory its ConfLocation
    names, and the installation root's.

    The platform also reads ~/.1cv8/1C/1cv8/conf/conf.cfg, but the check gives the client a HOME of
    its own, so the file of the person running it is not among them.
    """
    version = platform / "conf" / _CONF
    candidates = [version]
    if _is_regular_file(version):
        location = _read_conf(version).get("conflocation", (None, ""))[1]
        if location:
            candidates.append(Path(location) / _CONF)
    if len(platform.parents) >= 2:
        candidates.append(platform.parents[1] / "conf" / _CONF)
    return [path for path in dict.fromkeys(candidates) if _is_regular_file(path)]


def _read_conf(path):
    """key (case-folded) -> (key, value) of one conf.cfg; the first line of a key wins."""
    settings = {}
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        key, separator, value = line.partition("=")
        key = key.strip()
        if separator and key and not key.startswith("#"):
            settings.setdefault(key.casefold(), (key, value.strip()))
    return settings


def _posix_bre(pattern):
    """A POSIX basic regular expression, the syntax conf.cfg patterns use, as a Python pattern.

    In a basic expression ( ) { } | + ? are plain characters and the escaped forms are the operators.
    """
    converted, index = [], 0
    while index < len(pattern):
        char = pattern[index]
        if char == "\\" and index + 1 < len(pattern):
            following = pattern[index + 1]
            converted.append(following if following in "(){}|" else
                             "\\" + following if following.isdigit() else re.escape(following))
            index += 2
            continue
        converted.append("\\" + char if char in "(){}|+?" else char)
        index += 1
    return re.compile("".join(converted))


def _bre_literal(text):
    return re.sub(r"([\\.\[\]*^$])", r"\\\1", text)


def _client_environment(platform, base):
    """What the conf.cfg files say about the client of this check, for the report and the preflight.

    DisableUnsafeActionProtection lists patterns that are matched against the whole connection string
    of an infobase, File="<path>"; for a file infobase: the platform opened the external object for
    .*/<path>.* and showed the warning for the bare path (measured with 8.5.1.1529 on 12.11.2026).
    Where one matches, the client opens an external data processor or report without the
    unsafe action warning; where none does, it stops at that warning, which nothing in the check can
    answer. SystemLanguage sets the platform interface language, which the captions of the standard
    report form are in. Each setting is taken from the first file that has it.
    """
    files = _conf_files(platform)
    found = {}
    for path in files:
        for folded, (_key, value) in _read_conf(path).items():
            found.setdefault(folded, (value, str(path)))
    connection = f'File="{base}";'
    patterns, source = found.get(_UNSAFE_SETTING.casefold(), ("", None))
    patterns = [pattern for pattern in patterns.split(";") if pattern.strip()]
    matched, invalid = None, []
    for pattern in patterns:
        try:
            if _posix_bre(pattern).fullmatch(connection):
                matched = pattern
                break
        except re.error:
            invalid.append(pattern)
    language, language_source = found.get(_LANGUAGE_SETTING.casefold(), (None, None))
    protection = {"setting": _UNSAFE_SETTING, "patterns": patterns, "source": source,
                  "disabledForTestInfobase": matched is not None}
    if matched is not None:
        protection["matchedPattern"] = matched
    if invalid:
        protection["invalidPatterns"] = invalid
    return {"confFiles": [str(path) for path in files], "connectionString": connection,
            "unsafeActionProtection": protection,
            "systemLanguage": {"setting": _LANGUAGE_SETTING, "value": language, "source": language_source}}


def _suggested_pattern(base):
    if base.parent.name.startswith(_RUN_COPY_PREFIX):
        # edt_test_form works on a fresh copy in a directory with a random suffix.
        return f".*/{_RUN_COPY_PREFIX}.*"
    return ".*" + _bre_literal(str(base)) + ".*"


def _require_client_environment(args, platform, base, external, report):
    environment = _client_environment(platform, base)
    report["clientEnvironment"] = environment
    protection = environment["unsafeActionProtection"]
    if protection["disabledForTestInfobase"] or not external:
        return
    if args.user:
        # The flag "Unsafe action protection" of the infobase user disables it as well; the file
        # cannot tell whether the user has it cleared.
        protection["note"] = (f"{_UNSAFE_SETTING} does not cover the test infobase; the check relies on "
                              f"the unsafe action protection flag of user {args.user} being cleared")
        return
    # The file that already holds the setting is the one the patterns are added to; patterns are
    # separated by ";".
    target = protection["source"] or platform / "conf" / _CONF
    advice = (f"Add {_suggested_pattern(base)} to {_UNSAFE_SETTING} in {target}, or sign in with --user whose "
              "unsafe action protection is cleared.")
    if protection["patterns"]:
        # The patterns are matched here by a model of the platform's rules: POSIX basic expressions over
        # the whole connection string. A pattern it misreads must not stop a run the platform would allow,
        # so the run goes on and the report says what to look at if the warning appears.
        protection["warning"] = (f"no {_UNSAFE_SETTING} pattern matches {environment['connectionString']} as the "
                                 "check reads them; if the client stops at the unsafe action warning, " + advice)
        return
    looked = [platform / "conf"] + ([platform.parents[1] / "conf"] if len(platform.parents) >= 2 else [])
    read = ", ".join(environment["confFiles"]) or f"no {_CONF} in " + " or ".join(map(str, looked))
    raise _CheckError(
        "client_environment",
        f"no {_CONF} the client reads sets {_UNSAFE_SETTING}, so the client would stop at the unsafe action "
        f"warning when it opens the external object in {environment['connectionString']}. {advice} "
        f"Read: {read}.")


def _native_check(args, base, external, output, report):
    platform = args.platform_bin.resolve()
    client_exe, ibcmd = (platform / name for name in ("1cv8", "ibcmd"))
    xvfb = shutil.which("Xvfb")
    if not client_exe.is_file() or not ibcmd.is_file() or not xvfb:
        raise _CheckError("dependency_missing", "The check requires 1cv8, ibcmd, Xvfb and libX11.")
    report["stage"] = "client_environment"
    _require_client_environment(args, platform, base, external, report)

    deadline = time.monotonic() + args.timeout
    with tempfile.TemporaryDirectory(prefix="edt-bridge-form-") as temporary, contextlib.ExitStack() as stack:
        root = Path(temporary)
        manager_base = root / "manager-infobase"
        designer_log = root / "designer.log"
        try:
            report["stage"] = "manager_setup"
            manager_env = _display(stack, xvfb, root / "manager-display", deadline)
            create = _spawn(stack, [str(ibcmd), "infobase", "--data=" + str(root / "ibcmd-data"),
                                    "--db-path=" + str(manager_base), "create"],
                            manager_env, root / "ibcmd.log")
            _require_exit(create, deadline, "Could not create the manager infobase")
            if not (manager_base / "1Cv8.1CD").is_file():
                raise _CheckError("setup_failed", "The platform did not create the manager infobase.")

            designer = [str(client_exe), "DESIGNER", "/F" + str(manager_base), *_START_FLAGS]
            standard_report_form = False
            if external:
                report["stage"] = "target_metadata"
                metadata = root / "target.xml"
                export = _spawn(stack, [*designer, "/DumpExternalDataProcessorOrReportToFiles",
                                        str(metadata), str(external), "/Out", str(designer_log)],
                                manager_env, root / "designer-process.log")
                _require_exit(export, deadline, "Could not read the selected external object metadata")
                form_key = _form_key(args.form)
                kind, object_name, form_name = form_key
                try:
                    properties = ET.parse(metadata).find("./{*}" + kind + "/{*}Properties")
                    default = properties.findtext("{*}DefaultForm", "").strip() if properties is not None else ""
                except PermissionError:
                    raise
                except (OSError, ET.ParseError) as error:
                    raise _CheckError("metadata_unavailable", "Designer did not export readable external object metadata.") from error
                if form_name is None and properties is not None and not default:
                    standard_report_form = (
                        properties.findtext("{*}Name", "").casefold() == object_name
                        and bool(properties.findtext("{*}MainDataCompositionSchema", "").strip())
                    )
                explicit_main_form = form_name is not None and _form_key(default) == form_key
                if not (standard_report_form or explicit_main_form):
                    report["defaultForm"] = default
                    raise _CheckError("form_not_default", "Select the main managed form declared by this external object.")

            report["stage"] = "manager_setup"
            driver = root / "driver"
            driver.mkdir()
            dump = _spawn(stack, [*designer, "/DumpConfigToFiles", str(driver), "-Format", "Hierarchical",
                                  "/Out", str(designer_log)], manager_env, root / "designer-process.log")
            _require_exit(dump, deadline, "Could not export the empty manager configuration")
            if not (driver / "Configuration.xml").is_file():
                raise _CheckError("setup_failed", "Designer did not export the manager configuration.")
            module = driver / "Ext/ManagedApplicationModule.bsl"
            module.parent.mkdir(exist_ok=True)
            shutil.copyfile(Path(__file__).with_name("native_form_manager.bsl"), module)
            for command in (["/LoadConfigFromFiles", str(driver)], ["/UpdateDBCfg"]):
                load = _spawn(stack, [*designer, *command, "/Out", str(designer_log)],
                              manager_env, root / "designer-process.log")
                _require_exit(load, deadline, "Could not prepare the temporary manager configuration")

            launch_external = external
            if args.erf:
                report["stage"] = "report_launcher_setup"
                launcher = root / "report-loader"
                launcher.mkdir()
                format_version = ET.parse(driver / "Configuration.xml").getroot().attrib["version"]
                launcher_xml = native_report.write_launcher(
                    launcher, external, "" if standard_report_form else args.form.rsplit(".", 1)[1], format_version)
                launch_external = root / "report-loader.epf"
                build = _spawn(stack, [*designer, "/LoadExternalDataProcessorOrReportFromFiles",
                                      str(launcher_xml), str(launch_external), "/Out", str(designer_log)],
                               manager_env, root / "designer-process.log")
                _require_exit(build, deadline, "Could not build the report launcher")
                if not _is_regular_file(launch_external):
                    raise _CheckError("setup_failed", "Designer did not build the report launcher.")

            enterprise = [str(client_exe), "ENTERPRISE", "/RunModeManagedApplication", *_START_FLAGS]
            scenario = getattr(args, "_scenario", None)
            phases = [("steps", scenario["steps"])] if scenario else [("legacy", None)]
            if scenario and "reopen" in scenario:
                phases.append(("reopen", scenario["reopen"]))
            if scenario:
                report["phases"] = []
            previous_keys = set()
            for name, steps in phases:
                for key in previous_keys:
                    report.pop(key, None)
                report.update(ok=False, phase=name)
                phase_output = output / name if scenario else output
                if scenario:
                    phase_output.mkdir()
                result = _run_session(args, base, external, phase_output, root / name, manager_base,
                                      manager_env, enterprise, xvfb, deadline, steps, launch_external)
                latest = {key: value for key, value in result.items() if key != "cleanupComplete"}
                report.update(latest)
                previous_keys = set(latest)
                if scenario:
                    report["phases"].append(dict(name=name, **result))
                if not result["ok"]:
                    break
        except (_CheckError, OSError, ValueError) as error:
            _record_error(report, error, "driver_failed")
        finally:
            for key, path in (("designerLog", designer_log), ("setupLog", root / "ibcmd.log")):
                detail = _log(path)
                if detail:
                    report[key] = detail
            if not report["ok"]:
                platform_logs = " ".join(report.get(key, "") for key in
                                         ("clientLog", "managerLog", "clientProcessLog", "managerProcessLog",
                                          "designerLog", "setupLog"))
                failure = _platform_failure_status(report.get("interaction", {}).get("error", ""), platform_logs)
                if failure:
                    report["status"] = failure
    report["cleanupComplete"] = True


def _read_event_log(base, started):
    """Errors the platform logged in the test infobase while the check ran, with module and line."""
    folder = base / "1Cv8Log"
    if not folder.is_dir():
        return {"read": True, "errors": []}
    if not (folder / "1Cv8.lgf").is_file() and (folder / "1Cv8.lgd").is_file():
        return {"read": False, "errors": [], "error": "the event log is in the SQLite format (1Cv8.lgd), which the check does not read"}
    try:
        # The log stamps whole seconds, so the second the check started in belongs to it.
        errors = event_log.read(folder, since=started - datetime.timedelta(seconds=1))
    except (event_log.EventLogError, OSError, ValueError) as error:
        return {"read": False, "errors": [], "error": str(error)}
    return {"read": True, "errors": errors}


def _cancel_check(_signal, _frame):
    raise _CheckError("cancelled", "The native form check was cancelled.")


def run(args):
    report = {"ok": False, "scope": args.scope, "scopeSource": "caller", "stage": "validation"}
    output = None
    try:
        base, external, destination = _validated_paths(args)
        args._scenario = native_scenario.load(args.scenario) if args.scenario else None
        try:
            # A run often names a directory per check inside a folder nobody has made yet.
            destination.parent.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise _CheckError("io_error", "Could not create the parent directory of --output-dir "
                                          f"{destination.parent}: {error}") from error
        destination.mkdir(mode=0o700)
        output = destination
        report.update(testInfobase=str(base), form=args.form, formTitle=args.form_title)
        if external:
            report["epf" if args.epf else "erf"] = str(external)
        else:
            report["navigationLink"] = args.navigation_link
        if args._scenario:
            report["scenario"] = args._scenario
        else:
            report["button"] = args.button
        # Python installs a signal handler in the main thread only. The MCP wrapper serves a call in
        # a worker thread: there the check runs without the handler, and SIGTERM ends the wrapper.
        in_main_thread = threading.current_thread() is threading.main_thread()
        previous = signal.signal(signal.SIGTERM, _cancel_check) if in_main_thread else None
        started = datetime.datetime.now()
        try:
            _native_check(args, base, external, output, report)
        finally:
            if in_main_thread:
                signal.signal(signal.SIGTERM, previous)
            # Read after every session is stopped: the platform writes the log as the session runs and
            # finishes it on exit, and the error of a server call is only complete there.
            report["eventLog"] = _read_event_log(base, started)
    except (_CheckError, OSError, native_scenario.ScenarioError) as error:
        _record_error(report, error, "io_error")
    except KeyboardInterrupt:
        report.update(ok=False, status="cancelled", error="The native form check was cancelled.")
    if output is not None:
        try:
            (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8", newline="")
        except OSError as error:
            _record_error(report, error, "io_error")
    return report
