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
import re
from dataclasses import dataclass

#: The names of the sets, as core/ToolSets.java declares them and in the same order.
KNOWN = ("processor", "report", "extension")

#: The tool that describes one tool. The bridge serves it; the wrapper answers in its place
#: when the name asked about belongs to a tool the wrapper or a plugin serves.
HELP_TOOL = "edt_tool_help"

#: How many near names a refusal offers.
_SUGGESTIONS = 5

_TOOL_NAME = re.compile(r"(?<![A-Za-z0-9_])edt_[a-z0-9_]+(?![A-Za-z0-9_])")
_ADVICE_MARK = re.compile(r" \(outside the active tool sets? [^\n]+?: a call of it is refused\)")
_PROJECT_ERRORS = "edt_project_errors"
_ADVISORY_FIELDS = {_PROJECT_ERRORS: "hint", "edt_clean_project": "nextStep"}
_JOB_RESULTS = frozenset(("edt_job_status", "edt_cancel_job"))

# ProjectErrorsTool writes the service hint before the diagnostics. Match that slot only.
# Other refresh warnings can include exception text and continuation lines.
_BASELINE_REFUSAL = ("A baseline requires a complete validation snapshot. "
                     "Refresh and wait for modelReady=true, synchronized sources and settled validation; "
                     "narrow fqn or modulePath if the marker limit is exceeded. ")
_REFRESH_TIMING = re.compile(r"\(build [0-9]+ ms, validation (?:NOT )?settled after [0-9]+ ms"
                             r"(?:, never seen running)?\)")
_BRIEF_PROJECT_HINT = re.compile(
    r"\A[^\r\n]+: [0-9]+ problem\(s\) \(ERROR [0-9]+, WARNING [0-9]+, INFO [0-9]+; "
    r"eclipse [0-9]+, edt-check [0-9]+\)[^\r\n]*\n"
    r"modelReady: (?:true|false)(?:; PARTIAL validation snapshot)?\n"
    r"(?P<refresh>refreshed: [^()\r\n]* " + _REFRESH_TIMING.pattern
    + r"(?: - Validation completion or a stable problem count was not established; re-read without refresh\.)?\n)?"
    r"hint: (?P<hint>[^\r\n]+)(?=\n)"
)


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


def help_options(arguments: dict) -> tuple[str | None, bool]:
    """Read the help arguments used by both MCP and the CLI."""
    if not isinstance(arguments, dict):
        raise ValueError("arguments must be an object")
    extra = sorted(arguments.keys() - {"tool", "brief"})
    if extra:
        names = _quoted(extra)
        raise ValueError(f"unknown argument {names}. Arguments this tool takes: brief, tool")
    name = arguments.get("tool")
    if name is not None and (not isinstance(name, str) or not name.strip()):
        raise ValueError("tool must be a non-empty name")
    brief = arguments.get("brief", False)
    if not isinstance(brief, bool):
        raise ValueError("brief must be a boolean")
    return name.strip() if name is not None else None, brief


def list_help(descriptors: list[dict], selection: Selection) -> str:
    tools = []
    for descriptor in descriptors:
        if selection.shows(descriptor):
            line = " ".join(descriptor.get("description", "").split())
            before, period, _ = line.partition(". ")
            line = before + ("." if period else "")
            if len(line) > 160:
                line = line[:157].rstrip() + "..."
            tools.append({"name": descriptor["name"], "description": line})
    return json.dumps({"tools": tools}, ensure_ascii=False, indent=2)


def describe(descriptor: dict, selection: Selection, *, brief: bool = False) -> str:
    """The answer of edt_tool_help for a tool the wrapper serves, shaped like the bridge's."""
    name = descriptor["name"]
    schema = descriptor.get("inputSchema", {"type": "object", "properties": {}})
    answer = {"name": name}
    if brief:
        args = []
        required = set(schema.get("required", ()))
        for group in schema.get("allOf", ()):
            alternatives = group.get("anyOf") or ()
            if alternatives:
                required.update(alternatives[0].get("required", ()))
        for key, prop in schema.get("properties", {}).items():
            kind = prop.get("type", "any") if isinstance(prop, dict) else "any"
            if isinstance(kind, list):
                kind = "|".join(kind)
            args.append(f"{key}{'*' if key in required else ''}: {kind}")
        answer["signature"] = f"{name}({', '.join(args)})"
    else:
        answer.update(description=descriptor.get("description", ""), inputSchema=schema)
    declared = descriptor.get("toolsets")
    if isinstance(declared, (list, tuple)):
        answer["toolsets"] = list(declared)
    answer["available"] = selection.shows(descriptor)
    if not answer["available"]:
        answer["note"] = selection.note(descriptor)
    return json.dumps(answer, ensure_ascii=False, indent=2)


def _annotate(text: str, selection: Selection, unavailable: set[str]) -> str:
    seen = set()
    mark = f" (outside the active {selection.label()}: a call of it is refused)"

    def insert(match):
        name = match[0]
        if name not in unavailable or name in seen:
            return name
        seen.add(name)
        if _ADVICE_MARK.match(text, match.end()):
            return name
        return name + mark

    return _TOOL_NAME.sub(insert, text) if unavailable and "edt_" in text else text


def _described_fields(node: dict, selection: Selection, unavailable: set[str]) -> dict:
    for key in ("description", "descriptionRu"):
        value = node.get(key)
        if isinstance(value, str):
            marked = _annotate(value, selection, unavailable)
            if marked != value:
                node = dict(node, **{key: marked})
    return node


def _described_schema(schema, selection: Selection, unavailable: set[str]):
    if isinstance(schema, list):
        return [_described_schema(item, selection, unavailable) for item in schema]
    if not isinstance(schema, dict):
        return schema
    described = _described_fields(schema, selection, unavailable)
    for key in ("properties", "patternProperties", "$defs", "definitions"):
        value = schema.get(key)
        if isinstance(value, dict):
            marked = {name: _described_schema(item, selection, unavailable) for name, item in value.items()}
            if marked != value:
                described = dict(described, **{key: marked})
    for key in ("items", "prefixItems", "additionalProperties", "allOf", "anyOf", "oneOf",
                "not", "if", "then", "else"):
        value = schema.get(key)
        if isinstance(value, (dict, list)):
            marked = _described_schema(value, selection, unavailable)
            if marked != value:
                described = dict(described, **{key: marked})
    return described


def annotated_descriptor(descriptor: dict, selection: Selection, unavailable: set[str]) -> dict:
    if not unavailable:
        return descriptor
    described = _described_fields(descriptor, selection, unavailable)
    for key in ("inputSchema", "outputSchema"):
        schema = descriptor.get(key)
        marked = _described_schema(schema, selection, unavailable)
        if marked != schema:
            described = dict(described, **{key: marked})
    return described


def _text_payload(content):
    if isinstance(content, dict) and content.get("type") == "text":
        text = content.get("text")
        if isinstance(text, str) and text.lstrip().startswith("{"):
            try:
                return json.loads(text)
            except ValueError:
                pass
    return None


def _annotate_project_text(text: str, selection: Selection, unavailable: set[str]) -> str:
    if text.startswith(_BASELINE_REFUSAL):
        start, end = len(_BASELINE_REFUSAL), len(text)
        if any(char in text[start:] for char in "\r\n"):
            return text
    else:
        report = _BRIEF_PROJECT_HINT.match(text)
        # Filter strings and resource names precede this slot. A duplicate service header
        # or timing can be part of such a value rather than the formatter's own line.
        if (report is None or text.count("\nmodelReady: ") != 1
                or (report["refresh"] and len(_REFRESH_TIMING.findall(text)) != 1)):
            return text
        start, end = report.span("hint")
    advice = text[start:end]
    marked = _annotate(advice, selection, unavailable)
    return text if marked == advice else text[:start] + marked + text[end:]


def annotate_advisory(tool, result, selection: Selection, unavailable: set[str]) -> None:
    """Annotate only the known advisory field of a forwarded tool response."""
    if not (unavailable and isinstance(result, dict) and isinstance(result.get("content"), list)):
        return
    for content in result["content"]:
        payload = _text_payload(content)
        if not isinstance(payload, dict):
            if tool == _PROJECT_ERRORS and isinstance(content, dict) and content.get("type") == "text":
                text = content.get("text")
                if isinstance(text, str):
                    marked = _annotate_project_text(text, selection, unavailable)
                    if marked != text:
                        content["text"] = marked
            continue
        body, source = payload, tool
        if tool in _JOB_RESULTS:
            body, source = payload.get("result"), payload.get("tool")
            if source == _PROJECT_ERRORS and isinstance(body, str):
                marked = _annotate_project_text(body, selection, unavailable)
                if marked != body:
                    payload["result"] = marked
                    content["text"] = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                continue
        field = _ADVISORY_FIELDS.get(source) if isinstance(source, str) else None
        if not (field and isinstance(body, dict) and isinstance(body.get(field), str)):
            continue
        advice = body[field]
        marked = _annotate(advice, selection, unavailable)
        if marked != advice:
            body[field] = marked
            content["text"] = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def has_advisory(tool) -> bool:
    return isinstance(tool, str) and (tool in _ADVISORY_FIELDS or tool in _JOB_RESULTS)


def agree_with_selection(result: dict, selection: Selection, unavailable: set[str] | None = None) -> dict:
    """Make the bridge's description of a tool agree with the selection of this wrapper.

    The bridge marks a tool as outside the set by ITS variable. A bridge started without the
    variable describes every tool as available, while this wrapper refuses to call the ones
    outside its selection - so the description is corrected to say what a call will get.
    """
    if not (selection.names and isinstance(result, dict)) or result.get("isError"):
        return result
    content = result.get("content") or []
    if not isinstance(content, list) or len(content) != 1:
        return result
    payload = _text_payload(content[0])
    if not isinstance(payload, dict) or not isinstance(payload.get("name"), str):
        return result
    described = annotated_descriptor(payload, selection, unavailable or set())
    if payload.get("available") is True and not selection.shows(payload):
        described = dict(described, available=False, note=selection.note(payload))
    if described != payload:
        content[0]["text"] = json.dumps(described, ensure_ascii=False, indent=2)
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
