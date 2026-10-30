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


def supervise(command: list[str], state: Path, output: Path) -> int:
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
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a CLI command is required after --")
    return supervise(command, args.state, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
