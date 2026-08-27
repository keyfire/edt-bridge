"""Extension point: external packages add MCP tools the wrapper serves itself.

Not everything a team runs next to the bridge belongs in a public repository –
reference material under somebody's license, tools wired to an internal service.
Those live in separate private packages installed into the wrapper's environment
(pipx inject), and this module is how the wrapper finds them: the entry-point
group "edt_bridge.tools", the same two-layer model the xbsl linter and elemctl
follow – a public core that only declares the socket, and plugins that fill it.

The declaration in a plugin's pyproject.toml:

    [project.entry-points."edt_bridge.tools"]
    package-name = "my_package.tools:tools"

The value is a Tool, a list of them, or a zero-argument callable returning
either. A Tool carries the MCP descriptor (name, description, the JSON schema
of the arguments) and the handler the wrapper calls. Plugin tools are listed
next to the bridge's tools and dispatched by the wrapper itself, without
touching EDT – so they answer even while no EDT is running, exactly like the
wrapper's own edt_open_gui.

EDT_BRIDGE_NO_PLUGINS=1 disables the discovery – a run with the wrapper's own
capabilities only.

A failing entry point is an error (PluginError), not a silent skip: a wrapper
that quietly lost a plugin would leave an agent without its tools and without
an explanation. The MCP server itself must keep serving the bridge though, so
it reports the failure on stderr and through the `plugins` command instead of
dying with the whole tool surface.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from functools import lru_cache
from importlib.metadata import EntryPoint, entry_points
from typing import Callable

TOOLS_GROUP = "edt_bridge.tools"
ENV_DISABLE = "EDT_BRIDGE_NO_PLUGINS"

_FALSY = {"", "0", "false", "no"}

#: Tool names stay in the bridge's own style: lowercase, digits, underscores.
_NAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789_"


class PluginError(RuntimeError):
    pass


def disabled() -> bool:
    """Whether plugin discovery is turned off (EDT_BRIDGE_NO_PLUGINS).

    The variable is read as a literal on purpose: the docs guard recognizes a read
    by its literal name, and a variable read through a constant would be reachable
    yet demanded on no documentation page.
    """
    return os.environ.get("EDT_BRIDGE_NO_PLUGINS", "").strip().lower() not in _FALSY


@dataclass
class Tool:
    """One MCP tool a plugin serves through the wrapper.

    name and description go into the tool listing as they are; input_schema is
    the complete JSON schema of the arguments object ({"type": "object",
    "properties": {...}, "required": [...]}) – the same shape the bridge's own
    descriptors carry. description_ru is optional and travels as descriptionRu,
    like the bridge tools do.

    handler is called as handler(arguments) with the arguments object of the
    MCP call. A str return becomes the text result; any other JSON-serializable
    value is pretty-printed as JSON. An exception becomes an isError result
    with the exception's message – raise ValueError with a readable message for
    a deliberate refusal (a missing required argument, an unknown path).
    """

    name: str
    description: str
    handler: Callable
    input_schema: dict = field(default_factory=dict)
    description_ru: str = ""
    source: str = ""  # the entry point the tool arrived through; filled by discovery

    def descriptor(self) -> dict:
        """The MCP tool descriptor, shaped like the wrapper's own LOCAL_TOOLS."""
        schema = dict(self.input_schema) if self.input_schema else {}
        schema.setdefault("type", "object")
        schema.setdefault("properties", {})
        out = {"name": self.name, "description": self.description, "inputSchema": schema}
        if self.description_ru:
            out["descriptionRu"] = self.description_ru
        return out

    def validate(self, where: str = "") -> "Tool":
        """Check the declaration; a violation is a PluginError naming the entry point.

        Checked at discovery time, not at the moment of a call: a plugin that
        declares a tool wrongly must be visible right away, not when somebody
        happens to call it.
        """
        label = where or self.source or "<unknown entry point>"
        if not self.name or not isinstance(self.name, str):
            raise PluginError(f"plugin '{label}': a tool has no name")
        if set(self.name) - set(_NAME_CHARS) or not self.name[0].isalpha():
            raise PluginError(
                f"plugin '{label}': tool name '{self.name}' – use lowercase latin "
                "letters, digits and underscores, starting with a letter"
            )
        if not self.description or not str(self.description).strip():
            raise PluginError(f"plugin '{label}': tool '{self.name}' has no description")
        if not callable(self.handler):
            raise PluginError(f"plugin '{label}': tool '{self.name}' has no callable handler")
        if self.input_schema and not isinstance(self.input_schema, dict):
            raise PluginError(
                f"plugin '{label}': tool '{self.name}' – input_schema must be the JSON "
                f"schema object of the arguments, got {type(self.input_schema).__name__}"
            )
        properties = (self.input_schema or {}).get("properties", {})
        if not isinstance(properties, dict):
            raise PluginError(
                f"plugin '{label}': tool '{self.name}' – input_schema['properties'] "
                f"must be an object, got {type(properties).__name__}"
            )
        return self


def _points(group: str) -> list[EntryPoint]:
    if disabled():
        return []
    return list(_scan(group, entry_points))


@lru_cache(maxsize=None)
def _scan(group: str, scan: Callable) -> tuple[EntryPoint, ...]:
    """One entry-point walk per group and process.

    A walk reads every installed distribution's metadata; the callers come back
    on every tools/list. The scanning callable is part of the cache key on
    purpose: a test that monkeypatches `entry_points` gets a fresh walk through
    its stub, with no cache reset to remember.
    """
    return tuple(sorted(scan(group=group), key=lambda ep: ep.name))


def _load(ep: EntryPoint):
    try:
        return ep.load()
    except Exception as exc:
        raise PluginError(
            f"entry point '{ep.name}' of group {ep.group} failed to load "
            f"({ep.value}): {exc}"
        ) from exc


def plugin_tools(reserved: frozenset[str] = frozenset()) -> list[Tool]:
    """The tools declared by external packages, ordered by entry-point name.

    reserved is the set of names the wrapper serves itself: a plugin must not
    shadow those. Duplicates between plugins are an error too – two handlers
    behind one name would make the answer depend on installation order.
    """
    tools: list[Tool] = []
    taken: dict[str, str] = {}
    for ep in _points(TOOLS_GROUP):
        target = _load(ep)
        if not isinstance(target, Tool) and callable(target):
            target = target()
        items = [target] if isinstance(target, Tool) else target
        if isinstance(items, (str, bytes)) or not hasattr(items, "__iter__"):
            raise PluginError(
                f"entry point '{ep.name}' of group {TOOLS_GROUP} must give a Tool, "
                f"a list of them, or a callable returning either – got {items!r}"
            )
        for item in items:
            if not isinstance(item, Tool):
                raise PluginError(
                    f"entry point '{ep.name}' of group {TOOLS_GROUP} gave {item!r}, "
                    "which is not a Tool"
                )
            item.source = ep.name
            item.validate(where=ep.name)
            if item.name in reserved:
                raise PluginError(
                    f"plugin '{ep.name}': tool '{item.name}' shadows a tool the "
                    "wrapper serves itself"
                )
            if item.name in taken:
                raise PluginError(
                    f"plugin '{ep.name}': tool '{item.name}' is already declared "
                    f"by entry point '{taken[item.name]}'"
                )
            taken[item.name] = ep.name
            tools.append(item)
    return tools


def installed() -> list[dict]:
    """The plugged-in distributions, each once: [{"name", "version"}], ordered by name.

    Nothing is loaded here: the names come from the entry-point metadata alone,
    so the answer is safe even when a plugin is broken. An entry point without
    a distribution (a test stub) is skipped.
    """
    found: dict[str, str] = {}
    for ep in _points(TOOLS_GROUP):
        dist = getattr(ep, "dist", None)
        if dist is None:
            continue
        try:
            name, version = dist.metadata["Name"], dist.version
        except Exception:
            continue
        if name:
            found.setdefault(str(name), str(version or ""))
    return [{"name": name, "version": found[name]} for name in sorted(found)]
