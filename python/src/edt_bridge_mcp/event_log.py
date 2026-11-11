"""Read the event log of a file infobase in the platform's text format (1Cv8Log/*.lgf, *.lgp).

A form run reports what the client showed, and an error caught by the code under test, or one the
platform only shows as an "unexpected error" window, never reaches that report with its module and
line. The event log of the infobase has them: the platform writes the error of a server call, a
background job or a session there with its description. The check copies a fresh infobase for each
run, so the log of that copy holds exactly the run.

The text format is a dictionary file (1Cv8.lgf) and record files (YYYYMMDDHHMMSS.lgp). Both are a
sequence of brace lists; the dictionary maps the numeric codes of a record to names. The SQLite
format (1Cv8.lgd) is not read here: the platform writes the text format unless told otherwise.
"""
from __future__ import annotations

import datetime as _dt
import re
from pathlib import Path

SEVERITIES = {"I": "Information", "E": "Error", "W": "Warning", "N": "Note"}
# Dictionary entry kinds of 1Cv8.lgf: the record field each kind names.
_USER, _COMPUTER, _APP, _EVENT, _METADATA = 1, 2, 3, 4, 5
_MAX_FILE = 64 * 1024 * 1024
# A stack line of an error description: {Module(line)}:source text of that line.
_FRAME = re.compile(r"^\{(?P<module>[^{}()]+)\((?P<line>\d+)(?:,\d+)?\)\}:\s?(?P<code>.*)$")


class EventLogError(Exception):
    pass


def parse_lists(text: str) -> list:
    """Parse a sequence of brace lists: strings in double quotes ("" escapes a quote), bare tokens."""
    stack: list[list] = [[]]
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "{":
            stack.append([])
            i += 1
        elif c == "}":
            if len(stack) == 1:
                raise EventLogError(f"unbalanced closing brace at {i}")
            done = stack.pop()
            stack[-1].append(done)
            i += 1
        elif c == '"':
            j = i + 1
            parts = []
            while True:
                k = text.find('"', j)
                if k < 0:
                    raise EventLogError(f"unterminated string at {i}")
                parts.append(text[j:k])
                if k + 1 < n and text[k + 1] == '"':
                    parts.append('"')
                    j = k + 2
                    continue
                i = k + 1
                break
            stack[-1].append("".join(parts))
        elif c in ", \r\n\t﻿":
            i += 1
        else:
            j = i
            while j < n and text[j] not in ',{}"\r\n':
                j += 1
            stack[-1].append(text[i:j].strip())
            i = j
    if len(stack) != 1:
        raise EventLogError("unbalanced opening brace")
    return stack[0]


def _body(path: Path) -> str:
    if path.stat().st_size > _MAX_FILE:
        raise EventLogError(f"{path.name} exceeds {_MAX_FILE // (1024 * 1024)} MiB")
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    # The header is "1CV8LOG(ver 2.0)", the infobase id and an empty line; the lists follow.
    start = text.find("{")
    return text[start:] if start >= 0 else ""


def read_dictionary(folder: Path) -> dict[int, dict[int, str]]:
    names: dict[int, dict[int, str]] = {}
    path = folder / "1Cv8.lgf"
    if not path.is_file():
        raise EventLogError("the event log has no dictionary 1Cv8.lgf")
    for entry in parse_lists(_body(path)):
        if not isinstance(entry, list) or len(entry) < 3 or not str(entry[0]).isdigit():
            continue
        kind = int(entry[0])
        code = entry[-1]
        if not isinstance(code, str) or not code.isdigit():
            continue
        # Users and metadata carry a uuid before the name; the others only the name.
        name = entry[2] if kind in (_USER, _METADATA) and len(entry) >= 4 else entry[1]
        if isinstance(name, str):
            names.setdefault(kind, {})[int(code)] = name
    return names


def _time(stamp: str) -> _dt.datetime | None:
    try:
        return _dt.datetime.strptime(stamp, "%Y%m%d%H%M%S")
    except (TypeError, ValueError):
        return None


def _code(value) -> int | None:
    return int(value) if isinstance(value, str) and value.isdigit() else None


def _data(value) -> str:
    """The data field is a typed value: {"S","text"}, {"N",1}, {"U"} or a reference; keep its text."""
    if isinstance(value, list) and len(value) >= 2 and value[0] in ("S", "N", "B", "D") and isinstance(value[1], str):
        return value[1]
    return ""


def read(folder: Path, since: _dt.datetime | None = None, until: _dt.datetime | None = None,
         severities: tuple[str, ...] = ("E",), limit: int = 200) -> list[dict]:
    """Records of the given severities between since and until (local time, whole seconds)."""
    folder = Path(folder)
    if not folder.is_dir():
        return []
    names = read_dictionary(folder)
    found: list[dict] = []
    for path in sorted(folder.glob("*.lgp")):
        for record in parse_lists(_body(path)):
            if not isinstance(record, list) or len(record) < 13:
                continue
            when = _time(record[0])
            severity = record[8] if isinstance(record[8], str) else ""
            if when is None or severity not in severities:
                continue
            if since is not None and when < since.replace(microsecond=0):
                continue
            if until is not None and when > until:
                continue
            event = names.get(_EVENT, {}).get(_code(record[7]), record[7])
            entry = {
                "time": when.isoformat(sep=" "),
                "severity": SEVERITIES.get(severity, severity),
                "event": event,
                "application": names.get(_APP, {}).get(_code(record[5]), record[5]),
                "user": names.get(_USER, {}).get(_code(record[3]), ""),
                "comment": record[9] if isinstance(record[9], str) else "",
            }
            metadata = names.get(_METADATA, {}).get(_code(record[10]))
            if metadata:
                entry["metadata"] = metadata
            data = _data(record[11])
            if data:
                entry["data"] = data
            entry.update(_stack(entry["comment"]))
            found.append(entry)
            if len(found) >= limit:
                return found
    return found


def _stack(comment: str) -> dict:
    """Split an error description into its message and the stack lines {Module(line)}:code.

    The first stack line is where the error was raised, the following ones are its callers, so the
    module and line of the first one are the place to look at.
    """
    message, frames, categories = [], [], []
    for line in comment.splitlines():
        text = line.strip()
        match = _FRAME.match(text)
        if match:
            frames.append({"module": match["module"], "line": int(match["line"]), "code": match["code"].strip()})
        elif frames and text.startswith("[") and text.endswith("]"):
            # 8.5 closes the description with the error categories, e.g. [ОшибкаВоВремяВыполненияВстроенногоЯзыка].
            categories = [part.strip() for part in text[1:-1].split(",") if part.strip()]
        elif not frames and text:
            message.append(text)
    if not frames:
        return {}
    found = {"message": "\n".join(message), "module": frames[0]["module"], "line": frames[0]["line"], "stack": frames}
    if categories:
        found["categories"] = categories
    return found
