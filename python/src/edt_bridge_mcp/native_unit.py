"""Run the YAXUnit tests of built configuration extensions in a copy of a test infobase.

YAXUnit (https://github.com/bia-technologies/yaxunit) runs the tests inside 1C:Enterprise: a client
started with /C RunUnitTests=<file> finds the test modules of the extensions the file names, runs
them and writes a jUnit report. This module copies the test infobase, loads the extensions of the
call into the copy with ibcmd, starts that client on a private Xvfb display and turns the report into
one entry per test.

The test infobase itself is never changed. YAXUnit has to be in it already, as the extension
YAXUNIT: loading its 5 MB into every copy would slow every run down. Measured with the platform
8.5.1.1529 and YAXUnit 25.12 on 13.11.2026.
"""

from __future__ import annotations

import contextlib
import datetime
import json
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from . import i18n, pii
from .native_form import (_START_FLAGS, _CheckError, _capture, _display, _is_regular_file, _log,
                          _platform_failure_status, _read_event_log, _record_error, _spawn)

#: The name YAXUnit has as an extension; its own frames are left out of a test's trace.
YAXUNIT = "YAXUNIT"
DATA_FILE = "1Cv8.1CD"
#: The tests listed in a report at most; the failed ones go first.
MAX_LISTED = 500
_MAX_TRACE = 20
_MAX_LOG = 1_048_576
_ENGINE_VERSION = "ВерсияТестовогоДвижка"
#: One record of the YAXUnit log: "15:27:24.411 [Клиент][ERR]: text", continued on the next lines.
_LOG_RECORD = re.compile(r"^\d\d:\d\d:\d\d\.\d{3} \[[^\]]*\]\[(?P<level>[A-Z]+)\]: ?(?P<text>.*)$")
_LISTED_NAME = re.compile(r'^name\s*:\s*"(.*)"\s*$', re.M)
_RELEASES = "https://github.com/bia-technologies/yaxunit/releases"


def add_arguments(parser):
    parser.add_argument("--platform-bin", required=True, type=Path, help=i18n.t("unit-tests.platform-bin"))
    parser.add_argument("--test-infobase", required=True, type=Path, help=i18n.t("unit-tests.test-infobase"))
    parser.add_argument("--extension", required=True, action="append", metavar="NAME=PATH",
                        help=i18n.t("unit-tests.extension"))
    parser.add_argument("--module", action="append", default=[], metavar="MODULE",
                        help=i18n.t("unit-tests.module"))
    parser.add_argument("--test", action="append", default=[], metavar="MODULE.TEST[.CONTEXT]",
                        help=i18n.t("unit-tests.test"))
    parser.add_argument("--user", help=i18n.t("unit-tests.user"))
    parser.add_argument("--output-dir", required=True, type=Path, help=i18n.t("unit-tests.output-dir"))
    parser.add_argument("--timeout", type=int, default=300, help=i18n.t("unit-tests.timeout"))


def _left(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise _CheckError("timeout", "The unit test run reached its time limit.")
    return remaining


def _extensions(specs):
    """(name, file) of each --extension NAME=PATH, in the order they are loaded."""
    extensions, seen = [], set()
    for spec in specs:
        name, separator, path = spec.partition("=")
        if not separator or not name.isidentifier() or not path:
            raise _CheckError("invalid_input", f"Use NAME=PATH for an extension, with the name the extension has in "
                                               f"its project: {spec!r}.")
        if name.casefold() == YAXUNIT.casefold():
            raise _CheckError("invalid_input", "YAXUnit is taken from the test infobase; pass only the extensions "
                                               "with the tests and the code under test.")
        if name.casefold() in seen:
            raise _CheckError("invalid_input", f"The extension {name} is named twice.")
        seen.add(name.casefold())
        file = Path(path).resolve()
        if file.suffix.lower() != ".cfe" or not _is_regular_file(file):
            raise _CheckError("invalid_input", f"Select an existing .cfe file for the extension {name}: {path}.")
        extensions.append((name, file))
    return extensions


def _validated(args):
    if sys.platform != "linux":
        raise _CheckError("unsupported", "The unit test run requires isolated Linux with Xvfb.")
    if not 1 <= args.timeout <= 900:
        raise _CheckError("invalid_input", "Timeout must be between 1 and 900 seconds.")
    extensions = _extensions(args.extension)
    for module in args.module:
        if not module.isidentifier():
            raise _CheckError("invalid_input", f"Use the name of a test module: {module!r}.")
    for test in args.test:
        parts = test.split(".")
        if not 2 <= len(parts) <= 3 or not all(part.isidentifier() for part in parts):
            raise _CheckError("invalid_input", f"Name a test as Module.Test or Module.Test.Context: {test!r}.")
    base = args.test_infobase.resolve()
    if not _is_regular_file(base / DATA_FILE):
        raise _CheckError("invalid_input", f"The test infobase must contain {DATA_FILE}.")
    output = args.output_dir.resolve()
    if output.is_relative_to(base) or output.exists():
        raise _CheckError("invalid_input", "Select a new output directory outside the test infobase.")
    return base, extensions, output


class _Infobase:
    """ibcmd on the copy of the test infobase: every call has its own log, a refusal carries it."""

    def __init__(self, stack, ibcmd, env, root, copy, user, deadline):
        self.stack, self.ibcmd, self.env, self.root = stack, ibcmd, env, root
        self.copy, self.user, self.deadline = copy, user, deadline
        self.step = 0

    def run(self, mode, command, *options, message, status="extension_load_failed"):
        self.step += 1
        log = self.root / f"ibcmd-{self.step}-{mode}-{command}.log"
        argv = [str(self.ibcmd), mode, "--data=" + str(self.root / "ibcmd-data"), "--db-path=" + str(self.copy),
                *(["--user=" + self.user] if self.user else []), command, *options]
        process = _spawn(self.stack, argv, self.env, log)
        try:
            code = process.wait(timeout=_left(self.deadline))
        except subprocess.TimeoutExpired as error:
            raise _CheckError("timeout", "The unit test run reached its time limit.") from error
        text = _log(log)
        if code != 0:
            detail = " ".join(text.split())[:1500] or "ibcmd wrote nothing about the cause."
            raise _CheckError(_platform_failure_status("", text) or status,
                              f"{message} (exit code {code}): {detail}")
        return text

    def extensions(self):
        text = self.run("extension", "list", status="setup_failed",
                        message="Could not list the extensions of the test infobase")
        return _LISTED_NAME.findall(text)


def _load(infobase, extensions):
    """Load each extension into the copy; returns the names the extensions have there, in order."""
    present = infobase.extensions()
    engine = next((name for name in present if name.casefold() == YAXUNIT.casefold()), None)
    if engine is None:
        raise _CheckError(
            "yaxunit_missing",
            f"The test infobase has no YAXUnit extension ({YAXUNIT}); its extensions: "
            f"{', '.join(present) or 'none'}. Load YAXUnit into it once: ibcmd config load "
            f"--db-path=<infobase> --extension={YAXUNIT} YAxUnit-<version>.cfe, then ibcmd config apply "
            f"--db-path=<infobase> --extension={YAXUNIT} --force. Release files: {_RELEASES}.")
    loaded = []
    for name, file in extensions:
        before = present
        infobase.run("config", "load", "--extension=" + name, str(file),
                     message=f"Could not load {file.name} as the extension {name}")
        infobase.run("config", "apply", "--extension=" + name, "--force",
                     message=f"Could not apply the extension {name} to the test infobase")
        present = infobase.extensions()
        # ibcmd loads a file under any name and gives the extension the name inside the file when it
        # is applied. Names of the platform do not depend on case.
        found = next((other for other in present if other.casefold() == name.casefold()), None)
        if found is None:
            held = [other for other in present if other not in before]
            raise _CheckError(
                "extension_name_mismatch",
                f"{file.name} holds the extension {', '.join(held) or 'of another name'}, not {name}. "
                "Pass the name the extension has in its project.")
        loaded.append(found)
    for name in (engine, *loaded):
        # YAXUnit reads and writes files; the extensions of a run are its fixtures.
        infobase.run("extension", "update", "--name=" + name, "--safe-mode=no", "--unsafe-action-protection=no",
                     message=f"Could not turn off the safe mode of the extension {name}", status="setup_failed")
    return loaded


def _value(text):
    """An expected or actual value of a failed assertion: YAXUnit writes it as JSON {"#value": ...}."""
    text = (text or "").strip()
    try:
        value = json.loads(text)
    except ValueError:
        return text
    return value["#value"] if isinstance(value, dict) and "#value" in value else value


def _own_frames(text):
    """The stack lines of the code under test: YAXUnit's own and its expression wrapper {(1)} are left out."""
    frames = [line.strip() for line in (text or "").splitlines()]
    return [line for line in frames
            if line.startswith("{") and not line.startswith(("{" + YAXUNIT + " ", "{("))][:_MAX_TRACE]


def _seconds(text):
    try:
        return float(text)
    except (TypeError, ValueError):
        return 0.0


def _parse_report(path):
    """(tests, YAXUnit version) of a jUnit report as YAXUnit writes it."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        raise _CheckError("yaxunit_failed", f"YAXUnit wrote a report that is not readable XML: {error}.") from error
    tests = []
    for suite in root.iter("testsuite"):
        for case in suite.findall("testcase"):
            entry = {"module": suite.get("classname") or "", "name": case.get("name") or "",
                     "context": case.get("context") or suite.get("context") or "",
                     "extension": suite.get("package") or "", "status": "passed",
                     "time": _seconds(case.get("time"))}
            for tag, status in (("failure", "failed"), ("error", "error"), ("skipped", "skipped")):
                node = case.find(tag)
                if node is None:
                    continue
                entry["status"] = status
                if node.get("message"):
                    entry["message"] = node.get("message")
                trace = _own_frames(node.text)
                if trace:
                    entry["trace"] = trace
                for side in ("expected", "actual"):
                    value = node.find(side)
                    if value is not None:
                        entry[side] = _value(value.text)
                break
            tests.append(entry)
    version = next((item.get("value") for item in root.iter("property") if item.get("name") == _ENGINE_VERSION),
                   None)
    return tests, version


def _read_text(path):
    if not path.is_file():
        return ""
    with path.open("rb") as stream:
        return stream.read(_MAX_LOG).decode("utf-8-sig", errors="replace")


def _log_errors(text):
    """The [ERR] records of the YAXUnit log, each with the lines that continue it."""
    records = []
    for line in text.splitlines():
        match = _LOG_RECORD.match(line)
        if match:
            records.append([match["level"], [match["text"].rstrip()]])
        elif records:
            records[-1][1].append(line.rstrip())
    return ["\n".join(lines).strip()[:4000] for level, lines in records if level == "ERR"][:20]


def _exit_code(path):
    text = _read_text(path).strip()
    return int(text) if text.isdigit() else None


def _summary(tests):
    count = {status: sum(1 for test in tests if test["status"] == status)
             for status in ("passed", "failed", "error", "skipped")}
    return {"tests": len(tests), "passed": count["passed"], "failed": count["failed"],
            "errors": count["error"], "skipped": count["skipped"]}


def _listed(tests):
    if len(tests) <= MAX_LISTED:
        return tests
    problems = [test for test in tests if test["status"] != "passed"]
    return (problems + [test for test in tests if test["status"] == "passed"])[:MAX_LISTED]


def _judge(report, files, filter_):
    """Read what YAXUnit left and decide the status of the run."""
    exit_code = _exit_code(files["exit"])
    report["yaxunitExitCode"] = exit_code
    errors = _log_errors(_read_text(files["log"]))
    if errors:
        report["yaxunitErrors"] = errors
    if not files["report"].is_file():
        # The first record names the cause on its continuation lines ("ЗагрузкаТестов:" alone says nothing).
        cause = " ".join(errors[0].split())[:600] if errors else "see yaxunitLog and clientLog"
        raise _CheckError("yaxunit_failed", f"YAXUnit stopped before it wrote a report: {cause}.")
    tests, version = _parse_report(files["report"])
    if version:
        report["yaxunitVersion"] = version
    report["summary"] = summary = _summary(tests)
    listed = _listed(tests)
    report["tests"] = listed
    if len(listed) < len(tests):
        report["testsListed"] = len(listed)
    if not tests:
        report.update(status="no_tests", error=f"The filter selected no tests in {', '.join(filter_['extensions'])}.")
    elif summary["failed"] or summary["errors"] or exit_code not in (0, None):
        report["status"] = "failed"
    else:
        report.update(ok=True, status="passed")


def _unit_run(args, base, extensions, output, report):
    platform = args.platform_bin.resolve()
    client_exe, ibcmd = platform / "1cv8", platform / "ibcmd"
    xvfb = shutil.which("Xvfb")
    if not client_exe.is_file() or not ibcmd.is_file() or not xvfb:
        raise _CheckError("dependency_missing", "The run requires 1cv8, ibcmd, Xvfb and libX11.")
    deadline = time.monotonic() + args.timeout
    with tempfile.TemporaryDirectory(prefix="edt-bridge-unit-") as temporary:
        root = Path(temporary)
        copy = root / "infobase"
        copy.mkdir()
        files = {"report": root / "junit.xml", "allure": root / "allure-results",
                 "exit": root / "exit-code.txt", "log": root / "yaxunit.log",
                 "client": root / "client-platform.log", "process": root / "client-process.log"}
        started = None
        try:
            report["stage"] = "copy"
            shutil.copyfile(base / DATA_FILE, copy / DATA_FILE)
            with contextlib.ExitStack() as stack:
                report["stage"] = "display"
                env = _display(stack, xvfb, root / "display", deadline)
                report["stage"] = "extensions"
                report["filter"]["extensions"] = _load(
                    _Infobase(stack, ibcmd, env, root, copy, args.user, deadline), extensions)

                report["stage"] = "tests"
                config = root / "yaxunit.json"
                config.write_text(json.dumps({
                    "filter": report["filter"], "reportFormat": "jUnit", "reportPath": str(files["report"]),
                    # Both reports for CI from YAXUnit itself; a version without `reports` still writes
                    # the jUnit one through reportPath.
                    "reports": [{"format": "jUnit", "path": str(files["report"])},
                                {"format": "allure", "path": str(files["allure"])}],
                    "closeAfterTests": True, "showReport": False, "exitCode": str(files["exit"]),
                    "logging": {"file": str(files["log"]), "enable": True, "level": "info"},
                }, ensure_ascii=False), encoding="utf-8", newline="")
                auth = ["/N" + args.user] if args.user else []
                started = datetime.datetime.now()
                client = _spawn(stack, [str(client_exe), "ENTERPRISE", "/RunModeManagedApplication", *_START_FLAGS,
                                        "/F" + str(copy), *auth, "/C", "RunUnitTests=" + str(config),
                                        "/Out", str(files["client"])], env, files["process"])
                try:
                    report["clientExitCode"] = client.wait(timeout=_left(deadline))
                except (subprocess.TimeoutExpired, _CheckError) as error:
                    # What the client shows when it waits: a dialog nothing answers, a test that hangs.
                    report.update(_capture(stack, env, root, output))
                    raise _CheckError("timeout", "The unit test run reached its time limit.") from error
            _judge(report, files, report["filter"])
        except (_CheckError, OSError, ValueError) as error:
            _record_error(report, error, "io_error")
        finally:
            if started is not None:
                # Read after the client is stopped: the platform finishes the log of a session on exit.
                report["eventLog"] = _read_event_log(copy, started)
            for key, name in (("report", "junit.xml"), ("log", "yaxunit.log")):
                if files[key].is_file():
                    shutil.copyfile(files[key], output / name)
            if files["allure"].is_dir():
                shutil.copytree(files["allure"], output / "allure-results")
            reports = {key: str(output / name) for key, name in (("junit", "junit.xml"), ("allure", "allure-results"))
                       if (output / name).exists()}
            if reports:
                report["reports"] = reports
            if not report["ok"]:
                for key, name in (("clientLog", "client"), ("clientProcessLog", "process")):
                    detail = _log(files[name])
                    if detail:
                        report[key] = detail
                if "tests" not in report:
                    log = _read_text(files["log"]).strip()
                    if log:
                        report["yaxunitLog"] = log[-4000:]
                    failure = _platform_failure_status(
                        "", " ".join(report.get(key, "") for key in ("clientLog", "clientProcessLog")))
                    if failure:
                        report["status"] = failure
    report["cleanupComplete"] = True


def _cancel(_signal, _frame):
    raise _CheckError("cancelled", "The unit test run was cancelled.")


def run(args):
    report = {"ok": False, "stage": "validation"}
    output = None
    flt = pii.Filter()
    try:
        try:
            flt = pii.Filter.from_env()
        except pii.RulesError as error:
            raise _CheckError("invalid_input", str(error)) from error
        base, extensions, destination = _validated(args)
        try:
            destination.parent.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise _CheckError("io_error", "Could not create the parent directory of --output-dir "
                                          f"{destination.parent}: {error}") from error
        destination.mkdir(mode=0o700)
        output = destination
        # An empty list in the YAXUnit filter selects nothing, so only the parts given are passed.
        filter_ = {"extensions": [name for name, _ in extensions]}
        if args.module:
            filter_["modules"] = list(args.module)
        if args.test:
            filter_["tests"] = list(args.test)
        report.update(testInfobase=str(base),
                      extensions=[{"name": name, "file": str(file)} for name, file in extensions], filter=filter_)
        # Python installs a signal handler in the main thread only; the MCP wrapper serves a call in
        # a worker thread.
        in_main_thread = threading.current_thread() is threading.main_thread()
        previous = signal.signal(signal.SIGTERM, _cancel) if in_main_thread else None
        try:
            _unit_run(args, base, extensions, output, report)
        finally:
            if in_main_thread:
                signal.signal(signal.SIGTERM, previous)
    except (_CheckError, OSError) as error:
        _record_error(report, error, "io_error")
    except KeyboardInterrupt:
        report.update(ok=False, status="cancelled", error="The unit test run was cancelled.")
    if flt.active:
        # The verdict is YAXUnit's on the real values; the answer and the files it leaves are masked.
        pii.withhold_screenshots(report)
        report = pii.mask(report, flt)
        if output is not None:
            pii.mask_files([path for path in output.rglob("*") if path.suffix in (".xml", ".json", ".log", ".txt",
                                                                                 ".properties")], flt)
        report["piiFilter"] = flt.marker()
    if output is not None:
        try:
            (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8", newline="")
        except OSError as error:
            _record_error(report, error, "io_error")
    return report
