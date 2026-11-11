"""Tool sets on the wrapper side: the EDT_BRIDGE_TOOLSET the bridge reads, applied to what the wrapper lists.

The bridge narrows its own tool list to the sets the variable names. The wrapper adds tools the
bridge does not know - its own and the plugins' - and assembles a list by itself while EDT is
starting, so it reads the same variable and applies the same rule to everything it hands out.

The rule needs no table here. A descriptor says which sets contain its tool in the `toolsets`
field: the bridge writes it into every descriptor it serves, the wrapper's own tools declare it
next to their schema, and a plugin may declare it on its Tool. A descriptor WITHOUT the field is
listed whatever is selected. That covers a plugin written before tool sets existed, whose tools
must not vanish because of a variable its author never heard of, and a bridge older than this
wrapper, whose descriptors carry no field at all.

The table of sets itself lives on the Java side (core/ToolSets.java). Only the set names are
repeated here, to tell a misspelled name from a known one; a test compares the two lists.
"""

from __future__ import annotations

import difflib
import json
import os
from dataclasses import dataclass

#: The names of the sets, as core/ToolSets.java declares them and in the same order.
KNOWN = ("processor", "report", "extension")

#: The tool that describes one tool. The bridge serves it; the wrapper answers in its place
#: when the name asked about belongs to a tool the wrapper or a plugin serves.
HELP_TOOL = "edt_tool_help"

#: How many near names a refusal offers.
_SUGGESTIONS = 5


def _quoted(names) -> str:
    return ", ".join(f"'{name}'" for name in names)


@dataclass(frozen=True)
class Selection:
    """What EDT_BRIDGE_TOOLSET selected: nothing, some sets, or names that are not sets."""

    names: tuple[str, ...] = ()
    unknown: tuple[str, ...] = ()

    @property
    def limits(self) -> bool:
        """Whether the tool list is narrowed at all."""
        return bool(self.names or self.unknown)

    @property
    def problem(self) -> str | None:
        """Why the value cannot be served, or None when it can.

        A name that is not a set is refused as a whole, in the bridge's own words. Serving the
        sets that were recognized would run a job on a list nobody asked for, and serving the
        whole list would undo the limit - both without a word to whoever misspelled the name.
        """
        if not self.unknown:
            return None
        named = []
        for name in self.unknown:
            near = difflib.get_close_matches(name, KNOWN, n=1, cutoff=0.7)
            named.append(f"'{name}'" + (f" (did you mean '{near[0]}'?)" if near else ""))
        return ("EDT_BRIDGE_TOOLSET names "
                + ("an unknown tool set " if len(self.unknown) == 1 else "unknown tool sets ")
                + ", ".join(named) + ". Known tool sets: " + ", ".join(KNOWN)
                + ". No tool is listed or run until the variable names known sets or is empty.")

    def shows(self, descriptor: dict) -> bool:
        """Whether the selection serves the tool this descriptor declares."""
        if self.unknown:
            return False
        if not self.names:
            return True
        declared = descriptor.get("toolsets")
        if not isinstance(declared, (list, tuple)):
            return True
        return any(name in declared for name in self.names)

    def label(self) -> str:
        """The selected sets as a phrase: tool set 'report', tool sets 'a', 'b'."""
        return ("tool set " if len(self.names) == 1 else "tool sets ") + _quoted(self.names)

    def refusal(self, descriptor: dict) -> str:
        """The refusal for a call of a tool the selection does not serve."""
        return (f"{descriptor['name']} is outside the active {self.label()} (EDT_BRIDGE_TOOLSET), "
                "so the call was refused and nothing was done. " + _membership(descriptor))

    def note(self, descriptor: dict) -> str:
        """The note a description of a tool outside the selection carries."""
        return (f"{descriptor['name']} is outside the active {self.label()} (EDT_BRIDGE_TOOLSET): "
                "a call of it is refused. " + _membership(descriptor))


def _membership(descriptor: dict) -> str:
    declared = [name for name in KNOWN if name in (descriptor.get("toolsets") or ())]
    if not declared:
        return "No tool set contains it."
    return "Tool sets that contain it: " + ", ".join(declared) + "."


def parse(raw: str | None) -> Selection:
    """The selection a value of EDT_BRIDGE_TOOLSET asks for - the bridge's own reading of it.

    Names are separated by commas; spaces, case, repeats and empty items do not matter.
    """
    known: list[str] = []
    unknown: list[str] = []
    for part in (raw or "").split(","):
        name = part.strip().lower()
        if not name:
            continue
        target = known if name in KNOWN else unknown
        if name not in target:
            target.append(name)
    return Selection(tuple(known), tuple(unknown))


def active() -> Selection:
    """The selection of this process.

    The variable is read as a literal on purpose: the docs guard recognizes a read by its
    literal name, and a variable read through a constant could go undocumented.
    """
    return parse(os.environ.get("EDT_BRIDGE_TOOLSET"))


def describe(descriptor: dict, selection: Selection) -> str:
    """The answer of edt_tool_help for a tool the wrapper serves, shaped like the bridge's."""
    answer = {
        "name": descriptor["name"],
        "description": descriptor.get("description", ""),
        "inputSchema": descriptor.get("inputSchema", {"type": "object", "properties": {}}),
    }
    declared = descriptor.get("toolsets")
    if isinstance(declared, (list, tuple)):
        answer["toolsets"] = list(declared)
    answer["available"] = selection.shows(descriptor)
    if not answer["available"]:
        answer["note"] = selection.note(descriptor)
    return json.dumps(answer, ensure_ascii=False, indent=2)


def agree_with_selection(result: dict, selection: Selection) -> dict:
    """Make the bridge's description of a tool agree with the selection of this wrapper.

    The bridge marks a tool as outside the set by ITS variable. A bridge started without the
    variable describes every tool as available, while this wrapper refuses to call the ones
    outside its selection - so the description is corrected to say what a call will get.
    """
    if not (selection.names and isinstance(result, dict)) or result.get("isError"):
        return result
    content = result.get("content") or []
    if len(content) != 1 or not isinstance(content[0], dict) or content[0].get("type") != "text":
        return result
    try:
        payload = json.loads(content[0].get("text") or "")
    except ValueError:
        return result
    if (not isinstance(payload, dict) or not isinstance(payload.get("name"), str)
            or payload.get("available") is not True or selection.shows(payload)):
        return result
    payload["available"] = False
    payload["note"] = selection.note(payload)
    content[0]["text"] = json.dumps(payload, ensure_ascii=False, indent=2)
    return result


def near_names(name: str, candidates) -> list[str]:
    """The candidates a misspelled tool name was most likely meant to be, nearest first."""
    pool = sorted(set(candidates))
    found = difflib.get_close_matches(name, pool, n=_SUGGESTIONS, cutoff=0.7)
    fragment = name.strip().lower()
    if len(fragment) >= 4:
        found += [candidate for candidate in pool
                  if candidate not in found and (fragment in candidate or candidate in fragment)]
    return found[:_SUGGESTIONS]


def add_wrapper_names(result: dict, name, candidates) -> dict:
    """Complete the bridge's refusal for an unknown tool name with the wrapper's near names.

    The bridge judges a name against its own tools. A misspelled name of a tool the wrapper or
    a plugin serves is unknown to it, and its refusal would send the caller away from a tool
    that exists.
    """
    if not (isinstance(result, dict) and result.get("isError") and isinstance(name, str)):
        return result
    near = near_names(name, candidates)
    if near:
        result.setdefault("content", []).append({
            "type": "text",
            "text": "The wrapper serves tools with a close name: " + ", ".join(near) + ".",
        })
    return result
