"""Detached CLI supervisor: keep stdin open and retain bounded startup diagnostics."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time


OUTPUT_LIMIT = 16 * 1024


def lock_first_byte(handle) -> bool:
    """Lock the first byte of an open file without waiting; False when another process holds it.

    The operating system drops the lock with the process that holds it, however that ends."""
    try:
        if os.name == "nt":
            import msvcrt

            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 0)
    except OSError:
        return False
    return True


def unlock_first_byte(handle) -> None:
    try:
        if os.name == "nt":
            import msvcrt

            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.lockf(handle.fileno(), fcntl.LOCK_UN, 1, 0)
    except OSError:
        pass


def lock_held(path: Path) -> bool:
    """Whether another process holds the lock on ``path``. The probe lets go at once."""
    try:
        with open(path, "a+b", buffering=0) as handle:
            if not lock_first_byte(handle):
                return True
            unlock_first_byte(handle)
    except OSError:
        pass
    return False


def supervise(command: list[str], state: Path, output: Path, lock: Path | None = None) -> int:
    def report(values: dict) -> None:
        staged = state.with_suffix(".tmp")
        try:
            staged.write_text(json.dumps(values), encoding="utf-8", newline="")
            for attempt in range(20):
                try:
                    staged.replace(state)
                    return
                except PermissionError:
                    # Windows can temporarily prevent replacing a file opened by the parent.
                    if attempt == 19:
                        return
                    time.sleep(0.05)
        except OSError:
            pass  # Diagnostic-file failures must not replace the CLI's exit code.

    # Held for the whole life of this process: while it is, a headless EDT for the workspace runs
    # or starts, and a wrapper can tell that without a process list.
    holder = None
    if lock is not None:
        try:
            holder = open(lock, "a+b", buffering=0)
        except OSError as exc:
            report({"error": f"cannot open the workspace lock {lock}: {exc}"})
            return 1
        if not lock_first_byte(holder):
            holder.close()
            report({"error": "another headless EDT is already running for this workspace"})
            return 1

    try:
        child = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            bufsize=0, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
    except OSError as exc:
        report({"error": str(exc)})
        return 1

    def close_input(*_args) -> None:
        try:
            child.stdin.close()
        except OSError:
            pass

    def drain() -> None:
        tail = bytearray()
        try:
            while chunk := os.read(child.stdout.fileno(), 4096):
                tail.extend(chunk)
                del tail[:-OUTPUT_LIMIT]
                try:
                    output.write_bytes(tail)
                except OSError:
                    pass  # Continue draining even if diagnostics cannot be written.
        except OSError:
            pass

    report({"pid": child.pid})
    signal.signal(signal.SIGTERM, close_input)
    reader = threading.Thread(target=drain, daemon=True, name="edt-bridge-output")
    reader.start()
    try:
        try:
            child.stdin.write(b"version\n")
            child.stdin.flush()
        except OSError:
            pass  # A launcher can exit before reading its first command.
        code = child.wait()
        reader.join(timeout=1)
        report({"pid": child.pid, "exitCode": code})
        return code
    finally:
        close_input()
        child.stdout.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--lock", type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a CLI command is required after --")
    return supervise(command, args.state, args.output, args.lock)


if __name__ == "__main__":
    raise SystemExit(main())
