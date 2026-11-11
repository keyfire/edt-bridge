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
import os
from dataclasses import dataclass

#: The names of the sets, as core/ToolSets.java declares them and in the same order.
KNOWN = ("processor", "report", "extension")


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
