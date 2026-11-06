"""Run a managed EPF form in the platform's native TestClient mode."""

from __future__ import annotations

import contextlib
import json
import os
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
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from . import i18n


_SCOPES = ("empty", "fixture", "application")
_START_FLAGS = ("/DisableStartupDialogs", "/DisableStartupMessages", "/DisableSplash")
_ACCESS_DENIED = (
    "access denied", "insufficient rights", "insufficient access rights",
    "доступ запрещен", "доступ запрещён", "недостаточно прав", "нарушение прав доступа",
)
_LICENSE_MISSING = ("license not found", "no license found", "не найдена лицензия")


class _CheckError(Exception):
    def __init__(self, status: str, message: str):
        super().__init__(message)
        self.status = status


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
    parser.add_argument("--epf", required=True, type=Path, help=i18n.t("test-form.epf"))
    parser.add_argument("--form", required=True, help=i18n.t("test-form.form"))
    parser.add_argument("--form-title", required=True, help=i18n.t("test-form.form-title"))
    parser.add_argument("--button", required=True, help=i18n.t("test-form.button"))
    parser.add_argument("--expect-message", help=i18n.t("test-form.expect-message"))
    parser.add_argument("--user", help=i18n.t("test-form.user"))
    parser.add_argument("--output-dir", required=True, type=Path, help=i18n.t("test-form.output-dir"))
    parser.add_argument("--timeout", type=int, default=120, help=i18n.t("test-form.timeout"))


def _form_key(name):
    parts = name.split(".")
    if (len(parts) == 4 and parts[0] in ("ExternalDataProcessor", "ВнешняяОбработка")
            and parts[2] in ("Form", "Форма") and all(part.isidentifier() for part in parts)):
        return parts[1].casefold(), parts[3].casefold()
    return None


def _validated_paths(args):
    if sys.platform != "linux":
        raise _CheckError("unsupported", "The native form check requires isolated Linux with Xvfb.")
    if not 1 <= args.timeout <= 900:
        raise _CheckError("invalid_input", "Timeout must be between 1 and 900 seconds.")
    if _form_key(args.form) is None:
        raise _CheckError("invalid_input", "Use the fully qualified main form name of the selected EPF.")
    if any(not text.strip() or any(c in text for c in "*?") for text in (args.form_title, args.button)):
        raise _CheckError("invalid_input", "Use exact, nonempty form and button selectors.")
    base = args.test_infobase.resolve()
    epf = args.epf.resolve()
    if not _is_regular_file(base / "1Cv8.1CD"):
        raise _CheckError("invalid_input", "The test infobase must contain 1Cv8.1CD.")
    if not _is_regular_file(epf) or epf.suffix.lower() != ".epf":
        raise _CheckError("invalid_input", "Select an existing EPF file.")
    output = args.output_dir.resolve()
    if output.is_relative_to(base) or output.exists():
        raise _CheckError("invalid_input", "Select a new output directory outside the test infobase.")
    return base, epf, output


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


def _native_check(args, base, epf, output, report):
    platform = args.platform_bin.resolve()
    client_exe, ibcmd = (platform / name for name in ("1cv8", "ibcmd"))
    xvfb = shutil.which("Xvfb")
    if not client_exe.is_file() or not ibcmd.is_file() or not xvfb:
        raise _CheckError("dependency_missing", "The check requires 1cv8, ibcmd, Xvfb and libX11.")

    deadline = time.monotonic() + args.timeout
    with tempfile.TemporaryDirectory(prefix="edt-bridge-form-") as temporary, contextlib.ExitStack() as stack:
        root = Path(temporary)
        manager_base = root / "manager-infobase"
        manager_log = root / "manager-platform.log"
        designer_log = root / "designer.log"
        client_log = root / "client-platform.log"
        progress = root / "manager-progress.json"
        client = None
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
            report["stage"] = "target_metadata"
            metadata = root / "target.xml"
            export = _spawn(stack, [*designer, "/DumpExternalDataProcessorOrReportToFiles",
                                    str(metadata), str(epf), "/Out", str(designer_log)],
                            manager_env, root / "designer-process.log")
            _require_exit(export, deadline, "Could not read the selected EPF metadata")
            try:
                default = ET.parse(metadata).findtext("./{*}ExternalDataProcessor/{*}Properties/{*}DefaultForm", "")
            except PermissionError:
                raise
            except (OSError, ET.ParseError) as error:
                raise _CheckError("metadata_unavailable", "Designer did not export readable EPF metadata.") from error
            if _form_key(default) != _form_key(args.form):
                report["defaultForm"] = default
                raise _CheckError("form_not_default", "Select the main managed form declared by this EPF.")

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

            report["stage"] = "client_startup"
            client_env = _display(stack, xvfb, root / "client-display", deadline)
            port = _free_port()
            auth = ["/N" + args.user] if args.user else []
            enterprise = [str(client_exe), "ENTERPRISE", "/RunModeManagedApplication", *_START_FLAGS]
            client = _spawn(stack, [*enterprise, "/F" + str(base), *auth, "/TestClient", f"-TPort{port}",
                                   "/Execute", str(epf), "/Out", str(client_log)],
                            client_env, root / "client-process.log")
            _wait_for_client(client, port, deadline)

            report["stage"] = "form_interaction"
            request = root / "request.json"
            result = root / "manager-result.json"
            request.write_text(json.dumps({"port": port, "form": args.form, "formTitle": args.form_title,
                                          "button": args.button, "expectedMessage": args.expect_message or "",
                                          "waitSeconds": min(10, max(1, int(_seconds_left(deadline) / 2))),
                                          "result": str(result), "progress": str(progress)}),
                               encoding="utf-8", newline="")
            manager = _spawn(stack, [*enterprise, "/F" + str(manager_base), "/TestManager",
                                    "/C" + str(request), "/Out", str(manager_log)],
                             manager_env, root / "manager-process.log")
            while not result.is_file():
                if client.poll() is not None or manager.poll() is not None:
                    raise _CheckError("session_exited", "A native session exited before reporting a result.")
                time.sleep(min(0.15, _seconds_left(deadline)))
            data = json.loads(result.read_text(encoding="utf-8-sig"))
            if not isinstance(data, dict) or not isinstance(data.get("status"), str):
                raise _CheckError("driver_failed", "The test manager returned an invalid result.")
            report["interaction"] = data
            report["status"] = data["status"]
            messages = data.get("messages")
            if data["status"] == "command_completed":
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
            for key, path in (("clientLog", client_log), ("managerLog", manager_log),
                              ("designerLog", designer_log), ("setupLog", root / "ibcmd.log")):
                detail = _log(path)
                if detail:
                    report[key] = detail
            if not report["ok"]:
                platform_logs = " ".join(report.get(key, "") for key in
                                         ("clientLog", "managerLog", "designerLog", "setupLog")).casefold()
                detail = " ".join([str(report.get("interaction", {}).get("error", "")),
                                   platform_logs]).casefold()
                if any(message in detail for message in _ACCESS_DENIED):
                    report["status"] = "access_denied"
                elif any(message in platform_logs for message in _LICENSE_MISSING):
                    report["status"] = "license_unavailable"
            elif "screenshot" not in report:
                report.update(ok=False, status="screenshot_failed")
    report["cleanupComplete"] = True


def _cancel_check(_signal, _frame):
    raise _CheckError("cancelled", "The native form check was cancelled.")


def run(args):
    report = {"ok": False, "scope": args.scope, "scopeSource": "caller", "stage": "validation"}
    output = None
    try:
        base, epf, destination = _validated_paths(args)
        destination.mkdir(mode=0o700)
        output = destination
        report.update(testInfobase=str(base), epf=str(epf), form=args.form,
                      formTitle=args.form_title, button=args.button)
        previous = signal.signal(signal.SIGTERM, _cancel_check)
        try:
            _native_check(args, base, epf, output, report)
        finally:
            signal.signal(signal.SIGTERM, previous)
    except (_CheckError, OSError) as error:
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
