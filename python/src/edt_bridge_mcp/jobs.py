"""Background runs of the wrapper's own long tools, edt_test_form and edt_unit_tests (EDT-JOB-01).

A form check or a run of unit tests takes minutes, and an MCP client drops a call it has waited on too
long. With async=true the call answers with a job id at once; edt_job_status and edt_cancel_job follow
it. The bridge runs its own long tools as jobs inside EDT; these two run in the wrapper, and the wrapper
lives only as long as its client. So a job here is a process of its own, detached from the wrapper, with
its state and its answer in a directory of the user cache: a wrapper started again by a new client finds
them there.

A job directory holds state.json (the state, written by the job process), pid (written by the wrapper
that started it), result.json (the answer, the same object the call returns without async) and log.txt
(what the process wrote to stderr). The arguments of the call are passed in request.json and removed as
soon as the job has read them: they may carry the password of the infobase user.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

PREFIX = "wjob-"
_ID = re.compile(r"wjob-[0-9a-f]{8}")
#: How long a finished job keeps its directory.
RETENTION_SECONDS = 24 * 3600
#: The longest a status call waits for the end: below the minute many clients allow a call.
MAX_WAIT_SECONDS = 50
DEFAULT_WAIT_SECONDS = 30
#: How long a call with async=true waits before it answers with a job id: a refusal answers as itself.
INLINE_SECONDS = 1.5
#: How long a cancel waits for the job process to stop by itself before it is killed.
CANCEL_WAIT_SECONDS = 10
FINISHED = ("succeeded", "failed", "cancelled")
STATUS_TOOL = "edt_job_status"
CANCEL_TOOL = "edt_cancel_job"
#: The description of the async argument of the wrapper tools, worded as the bridge words its own.
ASYNC_DESCRIPTION = ("true = run the call as a background job: the answer is a jobId at once, and "
                     "edt_job_status gives the state and, at the end, the same result the call returns "
                     "without it. Use it when the call may outlast the time your client waits for a tool; "
                     "edt_cancel_job stops the job. A call refused at once still answers directly.")


def root(environ=os.environ) -> Path:
    """The directory of the job directories: edt-bridge/jobs in the user cache."""
    base = environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
    return Path(base) / "edt-bridge" / "jobs"


def is_local(job_id) -> bool:
    """Whether the id names a job of the wrapper rather than one of the bridge."""
    return isinstance(job_id, str) and job_id.strip().startswith(PREFIX)


def _write(path: Path, data) -> None:
    """Replace a file at once, so a reader never sees half of it. The job directory is the owner's only."""
    temporary = path.with_name(path.name + ".tmp")
    with open(temporary, "w", encoding="utf-8", newline="") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)
    # On Windows a file another process is reading at this moment cannot be replaced; the reader holds it
    # for a moment only, so the replace is tried again.
    for attempt in range(100):
        try:
            os.replace(temporary, path)
            return
        except PermissionError:
            if attempt == 99:
                raise
            time.sleep(0.02)


def _read(path: Path):
    for attempt in range(50):
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except PermissionError:  # Windows: the file is being replaced at this moment
            time.sleep(0.02)
        except (OSError, ValueError):
            return None
    return None


def _alive(pid) -> bool:
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name == "nt":
        import ctypes

        kernel = ctypes.windll.kernel32
        handle = kernel.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            code = ctypes.c_ulong()
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == 259  # STILL_ACTIVE
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _runners() -> dict:
    from . import form_run, unit_run

    return {form_run.DESCRIPTOR["name"]: form_run.run_tool, unit_run.DESCRIPTOR["name"]: unit_run.run_tool}


def _spawn(folder: Path):
    """Start the job process, detached from the wrapper so it outlives it."""
    command = [sys.executable, "-m", "edt_bridge_mcp.jobs", "run", str(folder)]
    options = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW}
               if os.name == "nt" else {"start_new_session": True})
    # The job imports the same copy of the package as the wrapper that starts it, installed or not.
    environment = dict(os.environ)
    package_root = str(Path(__file__).resolve().parents[1])
    environment["PYTHONPATH"] = os.pathsep.join(
        part for part in (package_root, environment.get("PYTHONPATH")) if part)
    with open(folder / "log.txt", "ab") as log:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=log,
                                   cwd=str(folder), env=environment, **options)
    # Reaped by the wrapper while it lives, so a finished job is not left a zombie that looks alive.
    threading.Thread(target=process.wait, daemon=True).start()
    return process


def start(tool: str, arguments: dict, *, environ=os.environ, spawn=_spawn) -> dict:
    """Start a job for one call of a wrapper tool; the answer of edt_job_status for it."""
    purge(environ)
    job_id = PREFIX + secrets.token_hex(4)
    folder = root(environ) / job_id
    folder.mkdir(parents=True, mode=0o700)
    _write(folder / "request.json", {"tool": tool, "arguments": arguments})
    _write(folder / "state.json", {"jobId": job_id, "tool": tool, "status": "queued", "submittedAt": time.time()})
    process = spawn(folder)
    (folder / "pid").write_text(str(process.pid), encoding="ascii", newline="")
    return job_id


class _Cancelled(BaseException):
    """The job was asked to stop (SIGTERM): unwinds the run so its temporary files are removed."""


def run(folder: Path, *, runners=None) -> int:
    """The job process: run the call, write the answer and the end state."""
    folder = Path(folder)
    request = _read(folder / "request.json") or {}
    (folder / "request.json").unlink(missing_ok=True)
    state = _read(folder / "state.json") or {}
    state.update(status="running", startedAt=time.time())
    _write(folder / "state.json", state)

    def stop(signum, frame):
        raise _Cancelled()

    previous = signal.signal(signal.SIGTERM, stop) if os.name != "nt" else None
    tool = request.get("tool")
    runner = (runners or _runners()).get(tool)
    try:
        if runner is None:
            raise ValueError(f"no background run for {tool!r}")
        report = runner(request.get("arguments") or {})
        _write(folder / "result.json", report)
        state.update(status="succeeded")
    except _Cancelled:
        state.update(status="cancelled", cancelRequested=True)
    except Exception as error:  # the answer the call gives without async: "<tool> failed: ..."
        state.update(status="failed", error=f"{tool} failed: {error}")
    finally:
        if previous is not None:
            signal.signal(signal.SIGTERM, previous)
    state["finishedAt"] = time.time()
    _write(folder / "state.json", state)
    return 0


def _folder(job_id: str, environ) -> tuple[Path | None, str | None]:
    job_id = job_id.strip()
    if not _ID.fullmatch(job_id):
        return None, f"'{job_id}' is not a job id: a call with async=true answers one like wjob-0123abcd"
    folder = root(environ) / job_id
    if not (folder / "state.json").is_file():
        return None, (f"no job {job_id} on this machine: it was never started, or its directory is gone - a "
                      f"finished job is kept for {RETENTION_SECONDS // 3600} hours")
    return folder, None


def _state(folder: Path) -> dict:
    """The state of a job, with a job whose process died without an answer marked as failed."""
    state = _read(folder / "state.json") or {}
    if state.get("status") not in FINISHED:
        try:
            pid = int((folder / "pid").read_text(encoding="ascii"))
        except (OSError, ValueError):
            pid = None
        if pid is not None and not _alive(pid):
            # Read again: the process may have finished between the two reads.
            state = _read(folder / "state.json") or state
            if state.get("status") not in FINISHED:
                state.update(status="failed", finishedAt=time.time(),
                             error="the process of the job ended without an answer: the machine restarted, "
                                   "or the process was killed")
                _write(folder / "state.json", state)
    return state


def _render(folder: Path, state: dict, message: str | None = None) -> dict:
    end = state.get("finishedAt") or time.time()
    answer = {"jobId": state.get("jobId"), "tool": state.get("tool"), "status": state.get("status"),
              "elapsedSeconds": int(max(0, end - (state.get("submittedAt") or end)))}
    if state.get("cancelRequested"):
        answer["cancelRequested"] = True
    if message:
        answer["message"] = message
    if state.get("status") in FINISHED:
        result = _read(folder / "result.json")
        if result is not None:
            answer["result"] = result
        if state.get("error"):
            answer["error"] = state["error"]
    else:
        answer["next"] = "call edt_job_status with this jobId for the state and the result; edt_cancel_job stops the job"
    return answer


def status(job_id: str, wait_seconds=DEFAULT_WAIT_SECONDS, *, environ=os.environ) -> tuple[dict | str, bool]:
    """(the answer of edt_job_status, whether it is an error) after waiting up to wait_seconds for the end."""
    folder, refusal = _folder(job_id, environ)
    if refusal:
        return refusal, True
    try:
        wait = max(0.0, min(float(wait_seconds), MAX_WAIT_SECONDS))
    except (TypeError, ValueError):
        wait = DEFAULT_WAIT_SECONDS
    deadline = time.monotonic() + wait
    state = _state(folder)
    while state.get("status") not in FINISHED and time.monotonic() < deadline:
        time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
        state = _state(folder)
    return _render(folder, state), state.get("status") == "failed"


def _kill(pid: int, force: bool) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], stdin=subprocess.DEVNULL, capture_output=True,
                       check=False, timeout=30)
        return
    try:
        os.killpg(pid, signal.SIGKILL if force else signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        pass


def cancel(job_id: str, *, environ=os.environ) -> tuple[dict | str, bool]:
    """Stop a job: its process and the platform processes it started. A finished job is answered as it is."""
    folder, refusal = _folder(job_id, environ)
    if refusal:
        return refusal, True
    state = _state(folder)
    if state.get("status") in FINISHED:
        return _render(folder, state, "the job had already ended: nothing to cancel"), False
    try:
        pid = int((folder / "pid").read_text(encoding="ascii"))
    except (OSError, ValueError):
        pid = None
    if pid is not None:
        # On Linux the job unwinds on SIGTERM and removes its copy of the infobase; Windows has no such
        # signal, and the whole process tree is stopped at once.
        _kill(pid, force=os.name == "nt")
        deadline = time.monotonic() + CANCEL_WAIT_SECONDS
        while _alive(pid) and time.monotonic() < deadline:
            time.sleep(0.2)
        if _alive(pid):
            _kill(pid, force=True)
    state = _read(folder / "state.json") or state
    if state.get("status") not in FINISHED:
        state.update(status="cancelled", cancelRequested=True, finishedAt=time.time())
        _write(folder / "state.json", state)
    message = ("cancelled; a check works on its own copy of the test infobase, so nothing it did stays applied"
               if state.get("status") == "cancelled" else "the job ended before it could be stopped")
    return _render(folder, state, message), False


def listing(*, environ=os.environ) -> list[dict]:
    """The wrapper jobs this machine keeps, the newest first."""
    purge(environ)
    rows = []
    base = root(environ)
    if not base.is_dir():
        return rows
    for folder in base.iterdir():
        if folder.is_dir() and _ID.fullmatch(folder.name) and (folder / "state.json").is_file():
            state = _state(folder)
            end = state.get("finishedAt") or time.time()
            rows.append({"jobId": state.get("jobId"), "tool": state.get("tool"), "status": state.get("status"),
                         "elapsedSeconds": int(max(0, end - (state.get("submittedAt") or end))),
                         "_submitted": state.get("submittedAt") or 0})
    rows.sort(key=lambda row: row["_submitted"], reverse=True)
    for row in rows:
        del row["_submitted"]
    return rows


def purge(environ=os.environ) -> None:
    """Remove the directories of the jobs that ended longer ago than RETENTION_SECONDS."""
    base = root(environ)
    if not base.is_dir():
        return
    now = time.time()
    for folder in base.iterdir():
        if not (folder.is_dir() and _ID.fullmatch(folder.name)):
            continue
        state = _read(folder / "state.json") or {}
        if state.get("status") in FINISHED and now - (state.get("finishedAt") or now) > RETENTION_SECONDS:
            shutil.rmtree(folder, ignore_errors=True)


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "run":
        return run(Path(argv[1]))
    print("usage: python -m edt_bridge_mcp.jobs run <job directory>", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
