"""The write scope the wrapper is configured with, compared with the one the running bridge holds.

The bridge enforces the scope itself: every call is checked before the tool runs. The settings reach
it through the environment of the EDT process, which an EDT started by this wrapper inherits. An EDT
started elsewhere without them would run every call unscoped while the agent's configuration says
otherwise, so the wrapper refuses to forward until the two agree.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

WRITE_ENV = "EDT_BRIDGE_WRITE_PROJECTS"
READ_ENV = "EDT_BRIDGE_READ_PROJECTS"
OUTPUT_ENV = "EDT_BRIDGE_OUTPUT_DIR"


def _names(value: str | None) -> tuple[str, ...]:
    names: list[str] = []
    for part in (value or "").replace(";", ",").split(","):
        part = part.strip()
        if part and part not in names:
            names.append(part)
    return tuple(names)


def _directory(value: str | None) -> str | None:
    value = (value or "").strip()
    if not value:
        return None
    return os.path.normcase(os.path.normpath(value)) if os.path.isabs(value) else value


@dataclass(frozen=True)
class Scope:
    write: frozenset[str]
    read: frozenset[str]
    projects_limited: bool
    output_dir: str | None

    @property
    def active(self) -> bool:
        return self.projects_limited or self.output_dir is not None

    def describe(self) -> str:
        if not self.active:
            return "no write scope"
        parts = []
        if self.projects_limited:
            parts.append("write " + (", ".join(sorted(self.write)) or "none"))
            parts.append("read " + (", ".join(sorted(self.read)) or "none"))
        parts.append("output " + (self.output_dir or "none"))
        return "; ".join(parts)


def from_environment(environ=None) -> Scope:
    """The scope the wrapper's own settings describe, parsed as the bridge parses them."""
    environ = os.environ if environ is None else environ
    write = _names(environ.get(WRITE_ENV))
    read = tuple(name for name in _names(environ.get(READ_ENV)) if name not in write)
    limited = bool((environ.get(WRITE_ENV) or "").strip() or (environ.get(READ_ENV) or "").strip())
    return Scope(frozenset(write), frozenset(read), limited, _directory(environ.get(OUTPUT_ENV)))


def from_status(status: dict | None) -> Scope:
    """The scope a bridge reports in /status; a bridge that reports none holds none."""
    reported = (status or {}).get("scope")
    if not isinstance(reported, dict) or not reported.get("active"):
        return Scope(frozenset(), frozenset(), False, None)
    limited = "writeProjects" in reported or "readProjects" in reported
    return Scope(frozenset(reported.get("writeProjects") or ()), frozenset(reported.get("readProjects") or ()),
                 limited, _directory(reported.get("outputDir")))


def mismatch(wrapper: Scope, status: dict | None) -> str | None:
    """Why calls must not be forwarded to this bridge, or None when its scope is the wrapper's."""
    if not wrapper.active:
        return None
    bridge = from_status(status)
    if bridge == wrapper:
        return None
    return (f"the running EDT holds a different write scope than this wrapper: EDT has {bridge.describe()}, "
            f"the wrapper has {wrapper.describe()}. That EDT was started without these settings; stop it "
            f"(edt-bridge-mcp shutdown) so the wrapper starts EDT with them, or start EDT with the same "
            f"{WRITE_ENV}, {READ_ENV} and {OUTPUT_ENV}")
