"""Tool sets on the wrapper side: EDT_BRIDGE_TOOLSET applied to what the wrapper lists and runs.

The bridge narrows its own list; these tests are about the part it cannot see. The wrapper's own
tools and the plugin tools have to follow the same variable, the list the wrapper assembles while
EDT is starting has to be narrowed too, and edt_tool_help has to answer for a tool the bridge
has never heard of.

The quiet failures are the ones guarded here: a misspelled set name that would list everything
or nothing, a tool outside the set that would still run, and a plugin whose tools would vanish
because of a variable its author never read about.
"""

import json
import re
from pathlib import Path

import pytest

from edt_bridge_mcp import cli, form_run, plugins, server, toolsets

ROOT = Path(__file__).resolve().parents[2]
JAVA_TABLE = (ROOT / "io.github.keyfire.edtbridge" / "src" / "io" / "github" / "keyfire"
              / "edtbridge" / "core" / "ToolSets.java")


# -- reading the variable -----------------------------------------------------


@pytest.mark.parametrize("raw", [None, "", "   ", " , ,"])
def test_an_empty_value_limits_nothing(raw):
    selection = toolsets.parse(raw)
    assert not selection.limits and selection.problem is None
    assert selection.shows({"name": "edt_open_gui", "toolsets": []})


def test_names_are_read_the_way_the_bridge_reads_them():
    selection = toolsets.parse(" Report, ,extension,report ")
    assert selection.names == ("report", "extension")
    assert selection.limits and selection.problem is None


def test_an_unknown_set_name_is_a_problem_and_shows_nothing():
    selection = toolsets.parse("processor,procesor,whatever")
    assert selection.unknown == ("procesor", "whatever")
    assert "'procesor' (did you mean 'processor'?)" in selection.problem
    assert "'whatever'" in selection.problem and "'whatever' (did you mean" not in selection.problem
    assert "Known tool sets: processor, report, extension" in selection.problem
    # Neither the set that was recognized nor the whole list.
    assert not selection.shows({"name": "edt_test_form", "toolsets": ["processor"]})
    assert not selection.shows({"name": "edt_probe_echo"})


def test_the_selection_comes_from_the_environment(monkeypatch):
    monkeypatch.delenv("EDT_BRIDGE_TOOLSET", raising=False)
    assert not toolsets.active().limits
    monkeypatch.setenv("EDT_BRIDGE_TOOLSET", "report")
    assert toolsets.active().names == ("report",)


def test_the_set_names_are_those_of_the_java_table():
    """The table lives on the Java side; only the names are repeated here, and they must agree."""
    declared = re.findall(r'sets\.put\("([a-z_]+)"', JAVA_TABLE.read_text(encoding="utf-8"))
    assert declared, "the reader found no set in ToolSets.java"
    assert tuple(declared) == toolsets.KNOWN


# -- the rule: a descriptor says which sets contain its tool -----------------


def test_a_descriptor_is_shown_by_the_sets_it_declares():
    report = toolsets.parse("report")
    assert report.shows({"name": "x", "toolsets": ["processor", "report"]})
    assert not report.shows({"name": "x", "toolsets": ["extension"]})
    assert not report.shows({"name": "x", "toolsets": []})
    assert toolsets.parse("extension,report").shows({"name": "x", "toolsets": ["extension"]})


def test_a_descriptor_without_the_field_is_shown_whatever_is_selected():
    """A plugin written before tool sets, or a bridge older than the wrapper."""
    assert toolsets.parse("extension").shows({"name": "edt_probe_echo"})


def test_a_refusal_names_the_set_the_tool_and_where_the_tool_is():
    refusal = toolsets.parse("extension").refusal(form_run.DESCRIPTOR)
    assert refusal.startswith("edt_test_form is outside the active tool set 'extension'")
    assert "EDT_BRIDGE_TOOLSET" in refusal and "nothing was done" in refusal
    assert "Tool sets that contain it: processor, report." in refusal
    several = toolsets.parse("processor,report").refusal({"name": "edt_open_gui", "toolsets": []})
    assert "the active tool sets 'processor', 'report'" in several
    assert "No tool set contains it." in several


# -- the wrapper's own tools --------------------------------------------------


def test_every_wrapper_tool_says_which_sets_carry_it():
    """A new wrapper tool has to be placed deliberately, like a bridge tool in the Java table."""
    for tool in server.LOCAL_TOOLS:
        assert isinstance(tool.get("toolsets"), list), tool["name"]
        assert set(tool["toolsets"]) <= set(toolsets.KNOWN), tool["name"]


def test_the_form_run_belongs_to_the_jobs_that_build_a_file_for_it():
    by_name = {tool["name"]: tool["toolsets"] for tool in server.LOCAL_TOOLS}
    assert by_name["edt_test_form"] == ["processor", "report"]
    assert by_name["edt_open_gui"] == []


# -- the MCP surface ----------------------------------------------------------


def probe(name="edt_probe_echo", **kw):
    kw.setdefault("description", "PROBE: echoes its arguments back.")
    kw.setdefault("handler", lambda arguments: {"echo": arguments})
    kw.setdefault("input_schema", {
        "type": "object",
        "properties": {"text": {"type": "string", "description": "what to echo"}},
    })
    return plugins.Tool(name=name, **kw)


def untouched(*_args, **_kwargs):
    raise AssertionError("the backend must not be touched")


@pytest.fixture()
def serve(monkeypatch):
    """Build a StdioServer for a value of EDT_BRIDGE_TOOLSET; frames are collected, not written."""

    def build(toolset, plugin_tools=()):
        if toolset is None:
            monkeypatch.delenv("EDT_BRIDGE_TOOLSET", raising=False)
        else:
            monkeypatch.setenv("EDT_BRIDGE_TOOLSET", toolset)
        monkeypatch.setattr(server, "load_plugin_tools",
                            lambda: ({tool.name: tool for tool in plugin_tools}, None))
        srv = server.StdioServer(server.Backend())
        sent = []
        monkeypatch.setattr(srv, "_send", sent.append)
        monkeypatch.setattr(srv, "_kick_background_start", lambda: None)
        return srv, sent

    return build


def offline(srv, monkeypatch, catalog):
    monkeypatch.setattr(srv.backend, "is_up", lambda: False)
    monkeypatch.setattr("edt_bridge_mcp.catalog.bridge_tools", lambda: catalog)


def listed(srv, sent):
    srv.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert len(sent) == 1, sent
    return [tool["name"] for tool in sent[0]["result"]["tools"]]


def call(srv, sent, name, arguments):
    srv.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {"name": name, "arguments": arguments}})
    assert len(sent) == 1, sent
    return sent[0]["result"]


CATALOG = [
    {"name": "edt_projects", "toolsets": ["processor", "report", "extension"]},
    {"name": "edt_add_form", "toolsets": ["processor", "report"]},
    {"name": "edt_adopt_object", "toolsets": ["extension"]},
    {"name": "edt_delete_project", "toolsets": []},
]


@pytest.mark.parametrize("toolset, expected", [
    (None, ["edt_projects", "edt_add_form", "edt_adopt_object", "edt_delete_project",
            "edt_open_gui", "edt_test_form"]),
    ("processor", ["edt_projects", "edt_add_form", "edt_test_form"]),
    ("report", ["edt_projects", "edt_add_form", "edt_test_form"]),
    ("extension", ["edt_projects", "edt_adopt_object"]),
    ("extension,processor", ["edt_projects", "edt_add_form", "edt_adopt_object", "edt_test_form"]),
])
def test_the_list_served_while_edt_is_starting_is_narrowed(serve, monkeypatch, toolset, expected):
    srv, sent = serve(toolset)
    offline(srv, monkeypatch, CATALOG)
    assert listed(srv, sent) == expected


def test_a_catalog_without_the_field_is_listed_whole(serve, monkeypatch):
    """The packaged catalog of a wrapper older than tool sets: nothing says where a tool belongs,
    so nothing is hidden - the wrapper's own tools still follow the selection."""
    srv, sent = serve("extension")
    offline(srv, monkeypatch, [{"name": "edt_projects"}, {"name": "edt_add_form"}])
    assert listed(srv, sent) == ["edt_projects", "edt_add_form"]


def test_a_live_list_of_a_bridge_started_without_the_variable_is_narrowed(serve, monkeypatch):
    srv, sent = serve("processor")
    monkeypatch.setattr(srv.backend, "is_up", lambda: True)
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [dict(tool) for tool in CATALOG]}})
    assert listed(srv, sent) == ["edt_projects", "edt_add_form", "edt_test_form"]


def test_a_refusal_of_the_bridge_to_list_reaches_the_client(serve, monkeypatch):
    """The bridge was started with a misspelled set and the wrapper with none."""
    srv, sent = serve(None)
    monkeypatch.setattr(srv.backend, "is_up", lambda: True)
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 1, "error": {"code": -32000, "message": "unknown tool set"}})
    srv.handle({"jsonrpc": "2.0", "id": 9, "method": "tools/list"})
    assert sent == [{"jsonrpc": "2.0", "id": 9,
                     "error": {"code": -32000, "message": "unknown tool set"}}]


def test_an_unknown_set_fails_the_listing_instead_of_listing_all_or_nothing(serve, monkeypatch):
    srv, sent = serve("procesor")
    monkeypatch.setattr(srv.backend, "is_up", untouched)
    monkeypatch.setattr(srv.backend, "forward", untouched)
    srv.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert len(sent) == 1 and "result" not in sent[0]
    assert sent[0]["error"]["code"] == -32000
    assert "unknown tool set 'procesor'" in sent[0]["error"]["message"]


def test_an_unknown_set_refuses_every_call(serve, monkeypatch):
    srv, sent = serve("procesor")
    monkeypatch.setattr(srv.backend, "ensure", untouched)
    monkeypatch.setattr(srv.backend, "forward", untouched)
    monkeypatch.setattr(srv.backend, "open_gui", untouched)
    answer = call(srv, sent, "edt_open_gui", {})
    assert answer["isError"] and "unknown tool set 'procesor'" in answer["content"][0]["text"]
    sent.clear()
    answer = call(srv, sent, "edt_projects", {})
    assert answer["isError"] and "unknown tool set 'procesor'" in answer["content"][0]["text"]


def test_a_wrapper_tool_outside_the_set_is_refused_before_it_runs(serve, monkeypatch):
    srv, sent = serve("processor")
    monkeypatch.setattr(srv.backend, "open_gui", untouched)
    answer = call(srv, sent, "edt_open_gui", {"force": True})
    text = answer["content"][0]["text"]
    assert answer["isError"]
    assert "edt_open_gui is outside the active tool set 'processor'" in text
    assert "No tool set contains it." in text


def test_the_form_run_is_refused_under_the_extension_set_and_runs_under_report(serve, monkeypatch):
    monkeypatch.setattr(form_run, "run_tool", lambda arguments: {"ok": True, "ran": arguments})
    srv, sent = serve("extension")
    answer = call(srv, sent, "edt_test_form", {"path": "x.epf"})
    assert answer["isError"]
    assert "Tool sets that contain it: processor, report." in answer["content"][0]["text"]

    srv, sent = serve("report")
    answer = call(srv, sent, "edt_test_form", {"path": "x.erf"})
    assert not answer.get("isError")
    assert json.loads(answer["content"][0]["text"]) == {"ok": True, "ran": {"path": "x.erf"}}


def test_a_bridge_tool_never_seen_in_a_list_is_forwarded_for_the_bridge_to_judge(serve, monkeypatch):
    """The wrapper keeps no table of the bridge's tools: it knows what the descriptors it has
    listed declare, and anything else is the bridge's to refuse."""
    srv, sent = serve("processor")
    forwarded = []
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda payload: forwarded.append(payload) or {
        "jsonrpc": "2.0", "id": 2, "result": {"isError": True, "content": [
            {"type": "text", "text": "edt_adopt_object is outside the active tool set 'processor'"}]}})
    answer = call(srv, sent, "edt_adopt_object", {"fqn": "Document.Order"})
    assert forwarded[0]["params"] == {"name": "edt_adopt_object", "arguments": {"fqn": "Document.Order"}}
    assert answer["isError"] and "outside the active tool set" in answer["content"][0]["text"]


def test_a_bridge_tool_left_out_of_the_served_list_is_refused_without_a_bridge(serve, monkeypatch):
    """A bridge started without the variable would run it; and no EDT is started for a refusal."""
    srv, sent = serve("processor")
    monkeypatch.setattr(srv.backend, "is_up", lambda: True)
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [dict(tool) for tool in CATALOG]}})
    assert "edt_adopt_object" not in listed(srv, sent)
    sent.clear()
    monkeypatch.setattr(srv.backend, "ensure", untouched)
    monkeypatch.setattr(srv.backend, "forward", untouched)
    answer = call(srv, sent, "edt_adopt_object", {"fqn": "Document.Order"})
    text = answer["content"][0]["text"]
    assert answer["isError"]
    assert "edt_adopt_object is outside the active tool set 'processor'" in text
    assert "Tool sets that contain it: extension." in text


def test_a_bridge_tool_of_the_set_is_forwarded_after_a_listing(serve, monkeypatch):
    srv, sent = serve("processor")
    monkeypatch.setattr(srv.backend, "is_up", lambda: True)
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [dict(tool) for tool in CATALOG]}})
    listed(srv, sent)
    sent.clear()
    forwarded = []
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda payload: forwarded.append(payload) or {
        "jsonrpc": "2.0", "id": 2, "result": {"content": [{"type": "text", "text": "done"}]}})
    assert call(srv, sent, "edt_add_form", {"name": "Form"})["content"][0]["text"] == "done"
    assert forwarded[0]["params"]["name"] == "edt_add_form"


def test_without_a_set_nothing_is_remembered_and_nothing_is_refused(serve, monkeypatch):
    srv, sent = serve(None)
    monkeypatch.setattr(srv.backend, "is_up", lambda: True)
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [dict(tool) for tool in CATALOG]}})
    assert len(listed(srv, sent)) == len(CATALOG) + len(server.LOCAL_TOOLS)
    sent.clear()
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 2, "result": {"content": [{"type": "text", "text": "done"}]}})
    assert call(srv, sent, "edt_delete_project", {})["content"][0]["text"] == "done"


def test_a_live_list_without_the_field_is_listed_whole(serve, monkeypatch):
    """A bridge older than tool sets: nothing says where its tools belong."""
    srv, sent = serve("extension")
    monkeypatch.setattr(srv.backend, "is_up", lambda: True)
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [{"name": "edt_projects"},
                                                        {"name": "edt_add_form"}]}})
    assert listed(srv, sent) == ["edt_projects", "edt_add_form"]


# -- plugin tools -------------------------------------------------------------


def test_a_plugin_tool_that_declares_nothing_is_served_under_any_set(serve, monkeypatch):
    srv, sent = serve("extension", [probe()])
    offline(srv, monkeypatch, CATALOG)
    assert listed(srv, sent) == ["edt_projects", "edt_adopt_object", "edt_probe_echo"]
    sent.clear()
    answer = call(srv, sent, "edt_probe_echo", {"text": "привет"})
    assert json.loads(answer["content"][0]["text"]) == {"echo": {"text": "привет"}}


def test_a_plugin_tool_that_declares_its_sets_follows_them(serve, monkeypatch):
    ran = []
    reporting = probe(toolsets=["report"], handler=lambda arguments: ran.append(arguments) or "ok")
    srv, sent = serve("processor", [reporting])
    offline(srv, monkeypatch, CATALOG)
    assert "edt_probe_echo" not in listed(srv, sent)
    sent.clear()
    answer = call(srv, sent, "edt_probe_echo", {})
    assert answer["isError"] and "Tool sets that contain it: report." in answer["content"][0]["text"]
    assert not ran, "nothing must run on a refused call"

    srv, sent = serve("report", [reporting])
    offline(srv, monkeypatch, CATALOG)
    assert "edt_probe_echo" in listed(srv, sent)
    sent.clear()
    assert call(srv, sent, "edt_probe_echo", {})["content"][0]["text"] == "ok"


def test_a_plugin_tool_with_an_empty_list_is_served_only_without_a_set(serve, monkeypatch):
    srv, sent = serve("report", [probe(toolsets=[])])
    offline(srv, monkeypatch, CATALOG)
    assert "edt_probe_echo" not in listed(srv, sent)
    srv, sent = serve(None, [probe(toolsets=[])])
    offline(srv, monkeypatch, CATALOG)
    assert "edt_probe_echo" in listed(srv, sent)


def test_a_plugin_descriptor_carries_the_field_only_when_it_is_declared():
    assert "toolsets" not in probe().descriptor()
    assert probe(toolsets=("report",)).descriptor()["toolsets"] == ["report"]
    assert probe(toolsets=[]).descriptor()["toolsets"] == []


@pytest.mark.parametrize("declared, message", [
    (["procesor"], "unknown tool set 'procesor'"),
    (["report", "all"], "unknown tool set 'all'"),
    ("report", "must be a list"),
])
def test_a_plugin_naming_an_unknown_set_refuses_at_validation(declared, message):
    """A misspelled set would hide the tool under every selection, without a word."""
    with pytest.raises(plugins.PluginError, match=message):
        probe(toolsets=declared).validate(where="stub")


# -- edt_tool_help ------------------------------------------------------------


def test_help_for_a_wrapper_tool_is_answered_without_a_bridge(serve, monkeypatch):
    srv, sent = serve(None)
    monkeypatch.setattr(srv.backend, "ensure", untouched)
    monkeypatch.setattr(srv.backend, "forward", untouched)
    answer = call(srv, sent, "edt_tool_help", {"tool": "edt_test_form"})
    assert not answer.get("isError")
    payload = json.loads(answer["content"][0]["text"])
    assert payload["name"] == "edt_test_form"
    assert payload["description"] == form_run.DESCRIPTOR["description"]
    assert payload["inputSchema"] == form_run.DESCRIPTOR["inputSchema"]
    assert payload["toolsets"] == ["processor", "report"]
    assert payload["available"] is True and "note" not in payload


def test_help_for_a_wrapper_tool_outside_the_set_says_so(serve, monkeypatch):
    srv, sent = serve("extension")
    monkeypatch.setattr(srv.backend, "ensure", untouched)
    payload = json.loads(call(srv, sent, "edt_tool_help", {"tool": "edt_test_form"})["content"][0]["text"])
    assert payload["available"] is False
    assert "outside the active tool set 'extension'" in payload["note"]
    assert "a call of it is refused" in payload["note"]
    assert "Tool sets that contain it: processor, report." in payload["note"]
    # formTitle is optional: without it the form is found by its name.
    assert payload["inputSchema"]["required"] == ["path", "form"]


def test_help_for_a_plugin_tool_is_answered_by_the_wrapper(serve, monkeypatch):
    srv, sent = serve("report", [probe()])
    monkeypatch.setattr(srv.backend, "ensure", untouched)
    payload = json.loads(call(srv, sent, "edt_tool_help", {"tool": "edt_probe_echo"})["content"][0]["text"])
    assert payload["name"] == "edt_probe_echo" and payload["description"].startswith("PROBE")
    assert "text" in payload["inputSchema"]["properties"]
    # It declares no sets, so it is served whatever is selected.
    assert payload["available"] is True and "toolsets" not in payload


def test_help_for_a_wrapper_tool_refuses_an_unknown_argument(serve, monkeypatch):
    srv, sent = serve(None)
    answer = call(srv, sent, "edt_tool_help", {"tool": "edt_open_gui", "verbose": True})
    assert answer["isError"]
    assert "edt_tool_help: unknown argument 'verbose'" in answer["content"][0]["text"]


def test_help_for_any_other_tool_is_forwarded_to_the_bridge(serve, monkeypatch):
    srv, sent = serve("processor", [probe()])
    forwarded = []
    described = {"content": [{"type": "text", "text": '{"name": "edt_add_form"}'}]}
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda payload: forwarded.append(payload) or {
        "jsonrpc": "2.0", "id": 2, "result": dict(described)})
    answer = call(srv, sent, "edt_tool_help", {"tool": "edt_add_form"})
    assert forwarded == [{"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                          "params": {"name": "edt_tool_help", "arguments": {"tool": "edt_add_form"}}}]
    assert answer == described


def test_a_misspelled_wrapper_tool_is_named_next_to_the_refusal_of_the_bridge(serve, monkeypatch):
    """The bridge judges a name against its own tools and would send the caller away."""
    srv, sent = serve(None, [probe()])
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 2, "result": {"isError": True, "content": [
            {"type": "text", "text": "edt_tool_help: no tool is named 'edt_test_from'."}]}})
    answer = call(srv, sent, "edt_tool_help", {"tool": "edt_test_from"})
    texts = [part["text"] for part in answer["content"]]
    assert answer["isError"]
    assert texts[0] == "edt_tool_help: no tool is named 'edt_test_from'."
    assert texts[1] == "The wrapper serves tools with a close name: edt_test_form."


def test_a_refusal_of_the_bridge_is_left_alone_when_no_wrapper_tool_is_close(serve, monkeypatch):
    srv, sent = serve(None, [probe()])
    refusal = {"isError": True, "content": [
        {"type": "text", "text": "edt_tool_help: no tool is named 'edt_add_from'."}]}
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 2, "result": json.loads(json.dumps(refusal))})
    assert call(srv, sent, "edt_tool_help", {"tool": "edt_add_from"}) == refusal


def bridge_help(name, sets, available=True):
    payload = {"name": name, "description": "bridge", "inputSchema": {"type": "object"},
               "toolsets": sets, "available": available}
    return {"content": [{"type": "text", "text": json.dumps(payload)}]}


def test_help_of_a_bridge_started_without_the_variable_is_corrected(serve, monkeypatch):
    """That bridge calls every tool available, while this wrapper refuses to call the tool."""
    srv, sent = serve("processor")
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 2, "result": bridge_help("edt_adopt_object", ["extension"])})
    payload = json.loads(call(srv, sent, "edt_tool_help", {"tool": "edt_adopt_object"})["content"][0]["text"])
    assert payload["available"] is False
    assert "edt_adopt_object is outside the active tool set 'processor'" in payload["note"]
    assert "Tool sets that contain it: extension." in payload["note"]
    assert payload["description"] == "bridge"


@pytest.mark.parametrize("toolset, sets", [
    ("processor", ["processor", "report"]),  # the tool is in the selected set
    (None, []),                              # nothing is selected
])
def test_help_of_the_bridge_is_left_alone_when_the_tool_is_served(serve, monkeypatch, toolset, sets):
    srv, sent = serve(toolset)
    answer = bridge_help("edt_add_form", sets)
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv.backend, "forward", lambda _payload: {
        "jsonrpc": "2.0", "id": 2, "result": json.loads(json.dumps(answer))})
    assert call(srv, sent, "edt_tool_help", {"tool": "edt_add_form"}) == answer


def test_near_names_find_a_typo_and_a_fragment():
    names = ["edt_open_gui", "edt_test_form", "edt_platform_doc_search", "edt_platform_doc_page"]
    assert toolsets.near_names("edt_test_from", names) == ["edt_test_form"]
    assert toolsets.near_names("platform_doc", names) == ["edt_platform_doc_page",
                                                          "edt_platform_doc_search"]
    assert toolsets.near_names("edt_rename", names) == []


# -- the shell commands -------------------------------------------------------


class FakeEntryPoint:
    def __init__(self, name, target):
        self.name = name
        self.value = f"stub:{name}"
        self.group = plugins.TOOLS_GROUP
        self._target = target
        self.dist = None

    def load(self):
        return self._target


def plug(monkeypatch, *tools):
    points = [FakeEntryPoint(f"probe{index}", tool) for index, tool in enumerate(tools)]
    monkeypatch.setattr(plugins, "entry_points",
                        lambda group: [point for point in points if point.group == group])


def test_cli_tools_lists_what_the_selected_sets_leave(monkeypatch, capsys):
    monkeypatch.setenv("EDT_BRIDGE_TOOLSET", "extension")
    plug(monkeypatch, probe(), probe("edt_probe_report", toolsets=["report"]))
    monkeypatch.setattr(server.Backend, "ensure", lambda self, wait: (True, "up"))
    monkeypatch.setattr(server.Backend, "forward", lambda self, payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [dict(tool) for tool in CATALOG]}})
    assert cli.run("tools", []) == 0
    assert capsys.readouterr().out.split() == ["edt_projects", "edt_adopt_object", "edt_probe_echo"]


def test_cli_call_refuses_a_plugin_tool_outside_the_set_with_exit_2(monkeypatch, capsys):
    monkeypatch.setenv("EDT_BRIDGE_TOOLSET", "processor")
    ran = []
    plug(monkeypatch, probe(toolsets=["report"], handler=lambda arguments: ran.append(arguments)))
    monkeypatch.setattr(server.Backend, "ensure", untouched)
    assert cli.run("call", ["edt_probe_echo"]) == 2
    assert "outside the active tool set 'processor'" in capsys.readouterr().err
    assert not ran


def test_cli_refuses_an_unknown_set_before_reaching_a_bridge(monkeypatch, capsys):
    monkeypatch.setenv("EDT_BRIDGE_TOOLSET", "procesor")
    plug(monkeypatch)
    monkeypatch.setattr(server.Backend, "ensure", untouched)
    monkeypatch.setattr(server.Backend, "forward", untouched)
    assert cli.run("tools", []) == 1
    assert "unknown tool set 'procesor'" in capsys.readouterr().err
    assert cli.run("call", ["edt_projects"]) == 1
    assert "unknown tool set 'procesor'" in capsys.readouterr().err


def test_cli_describes_a_wrapper_tool_without_a_bridge(monkeypatch, capsys):
    monkeypatch.setenv("EDT_BRIDGE_TOOLSET", "extension")
    plug(monkeypatch, probe())
    monkeypatch.setattr(server.Backend, "ensure", untouched)
    monkeypatch.setattr(server.Backend, "forward", untouched)
    assert cli.run("call", ["edt_tool_help", "--json", '{"tool": "edt_test_form"}']) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["name"] == "edt_test_form" and payload["available"] is False
    assert cli.run("call", ["edt_tool_help", "--json", '{"tool": "edt_probe_echo"}']) == 0
    assert json.loads(capsys.readouterr().out)["available"] is True


def test_cli_corrects_the_help_of_a_bridge_started_without_the_variable(monkeypatch, capsys):
    monkeypatch.setenv("EDT_BRIDGE_TOOLSET", "processor")
    plug(monkeypatch)
    monkeypatch.setattr(server.Backend, "ensure", lambda self, wait: (True, "up"))
    monkeypatch.setattr(server.Backend, "forward", lambda self, payload: {
        "jsonrpc": "2.0", "id": 1, "result": bridge_help("edt_adopt_object", ["extension"])})
    assert cli.run("call", ["edt_tool_help", "--json", '{"tool": "edt_adopt_object"}']) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["available"] is False and "Tool sets that contain it: extension." in payload["note"]


def test_cli_forwards_help_for_a_bridge_tool_with_its_arguments(monkeypatch, capsys):
    monkeypatch.delenv("EDT_BRIDGE_TOOLSET", raising=False)
    plug(monkeypatch)
    forwarded = []
    monkeypatch.setattr(server.Backend, "ensure", lambda self, wait: (True, "up"))
    monkeypatch.setattr(server.Backend, "forward", lambda self, payload: forwarded.append(payload) or {
        "jsonrpc": "2.0", "id": 1, "result": {"isError": True, "content": [
            {"type": "text", "text": "edt_tool_help: no tool is named 'edt_open_gu'."}]}})
    assert cli.run("call", ["edt_tool_help", "--json", '{"tool": "edt_open_gu"}']) == 2
    assert forwarded[0]["params"] == {"name": "edt_tool_help", "arguments": {"tool": "edt_open_gu"}}
    out = capsys.readouterr().out
    assert "no tool is named 'edt_open_gu'" in out
    assert "The wrapper serves tools with a close name: edt_open_gui." in out
