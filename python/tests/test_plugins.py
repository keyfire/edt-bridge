"""The plugin socket: discovery, validation, and the wrapper serving what plugins bring.

The dangers this file guards against are the quiet ones. A plugin that fails to load and
simply disappears leaves an agent without its tools and with nothing saying why - so a
failure is loud (PluginError) everywhere except the MCP server loop, which must keep
serving the bridge and reports on stderr instead. A tool name that collides with another
handler would make the answer depend on installation order - so collisions refuse at
discovery. And an argument the schema does not declare must refuse the call, not vanish:
a dropped name reads as a done deed for a call that did something else.
"""

import json

import pytest

from edt_bridge_mcp import cli, plugins, server


class FakeEntryPoint:
    """An entry point whose load() is scripted - no distribution needs installing."""

    def __init__(self, name, target, group=plugins.TOOLS_GROUP, dist=None):
        self.name = name
        self.value = f"stub:{name}"
        self.group = group
        self._target = target
        self.dist = dist

    def load(self):
        if isinstance(self._target, Exception):
            raise self._target
        return self._target


def scan_of(*eps):
    """A stand-in for importlib.metadata.entry_points serving exactly these points.

    A fresh function per call on purpose: the scan cache keys on the callable, so
    every test gets its own walk with no cache reset to remember.
    """

    def scan(group):
        return [ep for ep in eps if ep.group == group]

    return scan


def tool(name="edt_probe_echo", **kw):
    kw.setdefault("description", "PROBE: echoes its arguments back.")
    kw.setdefault("handler", lambda arguments: {"echo": arguments})
    kw.setdefault("input_schema", {
        "type": "object",
        "properties": {"text": {"type": "string", "description": "what to echo"}},
    })
    return plugins.Tool(name=name, **kw)


# -- the Tool declaration ---------------------------------------------------


def test_descriptor_fills_the_mcp_shape():
    d = tool(description_ru="ПРОБА: возвращает аргументы.").descriptor()
    assert d["name"] == "edt_probe_echo"
    assert d["inputSchema"]["type"] == "object"
    assert "text" in d["inputSchema"]["properties"]
    assert d["descriptionRu"].startswith("ПРОБА")


def test_descriptor_of_a_toolless_schema_is_still_a_schema():
    d = plugins.Tool(name="edt_probe_bare", description="x", handler=lambda a: "ok").descriptor()
    assert d["inputSchema"] == {"type": "object", "properties": {}}
    assert "descriptionRu" not in d


@pytest.mark.parametrize("bad, message", [
    (dict(name=""), "no name"),
    (dict(name="Edt-Probe"), "lowercase"),
    (dict(name="1probe"), "lowercase"),
    (dict(description=" "), "no description"),
    (dict(handler="not callable"), "callable handler"),
    (dict(input_schema={"properties": "oops"}), "properties"),
])
def test_a_wrong_declaration_refuses_at_validation(bad, message):
    fields = dict(name="edt_probe_echo", description="d", handler=lambda a: "ok")
    fields.update(bad)
    with pytest.raises(plugins.PluginError, match=message):
        plugins.Tool(**fields).validate(where="stub-plugin")


# -- discovery --------------------------------------------------------------


def test_discovery_accepts_a_tool_a_list_and_a_callable(monkeypatch):
    listed = [tool("edt_probe_one"), tool("edt_probe_two")]
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("b-list", listed),
        FakeEntryPoint("a-single", tool("edt_probe_single")),
        FakeEntryPoint("c-callable", lambda: [tool("edt_probe_called")]),
    ))
    found = plugins.plugin_tools()
    # ordered by entry-point name, sources filled in
    assert [(t.name, t.source) for t in found] == [
        ("edt_probe_single", "a-single"),
        ("edt_probe_one", "b-list"),
        ("edt_probe_two", "b-list"),
        ("edt_probe_called", "c-callable"),
    ]


def test_a_failing_entry_point_is_an_error_not_a_skip(monkeypatch):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("broken", RuntimeError("boom")),
    ))
    with pytest.raises(plugins.PluginError, match="broken.*boom"):
        plugins.plugin_tools()


def test_a_value_that_is_not_a_tool_refuses(monkeypatch):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("junk", ["not a tool"]),
    ))
    with pytest.raises(plugins.PluginError, match="not a Tool"):
        plugins.plugin_tools()


def test_two_plugins_behind_one_name_refuse(monkeypatch):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("first", tool("edt_probe_echo")),
        FakeEntryPoint("second", tool("edt_probe_echo")),
    ))
    with pytest.raises(plugins.PluginError, match="already declared.*first"):
        plugins.plugin_tools()


def test_a_plugin_cannot_shadow_the_wrapper(monkeypatch):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("greedy", tool("edt_open_gui")),
    ))
    with pytest.raises(plugins.PluginError, match="shadows"):
        plugins.plugin_tools(reserved=frozenset(server.LOCAL_TOOL_NAMES))


def test_the_kill_switch_turns_discovery_off(monkeypatch):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("present", tool()),
    ))
    monkeypatch.setenv(plugins.ENV_DISABLE, "1")
    assert plugins.plugin_tools() == []
    assert plugins.installed() == []


def test_installed_skips_an_entry_point_without_a_distribution(monkeypatch):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("bare", tool()),
    ))
    assert plugins.installed() == []


# -- the MCP server serving plugin tools ------------------------------------


@pytest.fixture()
def stdio(monkeypatch):
    """A StdioServer with one plugin tool, its frames collected instead of written."""
    monkeypatch.setattr(server, "load_plugin_tools",
                        lambda: ({"edt_probe_echo": tool()}, None))
    srv = server.StdioServer(server.Backend())
    sent = []
    monkeypatch.setattr(srv, "_send", sent.append)
    return srv, sent


def result_of(sent):
    assert len(sent) == 1, f"expected one frame, got {sent}"
    return sent[0]["result"]


def test_plugin_tools_are_listed_while_the_backend_is_down(stdio, monkeypatch):
    srv, sent = stdio
    monkeypatch.setattr(srv.backend, "is_up", lambda: False)
    monkeypatch.setattr(srv, "_kick_background_start", lambda: None)
    srv.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    names = [t["name"] for t in result_of(sent)["tools"]]
    assert "edt_open_gui" in names and "edt_probe_echo" in names


def test_merged_listing_prefers_the_plugin_descriptor(stdio, monkeypatch):
    # the dispatch never forwards a plugin-served name, so the listing must match it:
    # a bridge tool of the same name is dropped, and only one descriptor survives
    srv, sent = stdio
    monkeypatch.setattr(srv.backend, "is_up", lambda: True)
    monkeypatch.setattr(srv.backend, "forward", lambda payload: {
        "jsonrpc": "2.0", "id": 1, "result": {"tools": [
            {"name": "edt_projects", "description": "bridge"},
            {"name": "edt_probe_echo", "description": "the bridge's own, to be shadowed"},
        ]},
    })
    srv.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    tools = result_of(sent)["tools"]
    echoes = [t for t in tools if t["name"] == "edt_probe_echo"]
    assert len(echoes) == 1
    assert echoes[0]["description"].startswith("PROBE")
    assert {"edt_projects", "edt_open_gui"} <= {t["name"] for t in tools}


def test_a_plugin_call_answers_without_a_backend(stdio, monkeypatch):
    srv, sent = stdio

    def no_backend(*a, **kw):
        raise AssertionError("a plugin call must not touch the backend")

    monkeypatch.setattr(srv.backend, "ensure", no_backend)
    monkeypatch.setattr(srv.backend, "forward", no_backend)
    srv.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {"name": "edt_probe_echo", "arguments": {"text": "привет"}}})
    payload = json.loads(result_of(sent)["content"][0]["text"])
    assert payload == {"echo": {"text": "привет"}}


def test_a_string_answer_travels_as_plain_text(stdio, monkeypatch):
    srv, sent = stdio
    srv._plugin_tools["edt_probe_echo"].handler = lambda arguments: "plain answer"
    srv.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                "params": {"name": "edt_probe_echo", "arguments": {}}})
    assert result_of(sent)["content"][0]["text"] == "plain answer"


def test_an_unknown_argument_refuses_before_the_handler_runs(stdio):
    srv, sent = stdio
    ran = []
    srv._plugin_tools["edt_probe_echo"].handler = lambda arguments: ran.append(arguments)
    srv.handle({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                "params": {"name": "edt_probe_echo", "arguments": {"texd": "typo"}}})
    answer = result_of(sent)
    assert answer["isError"] and "texd" in answer["content"][0]["text"]
    assert "did you mean 'text'" in answer["content"][0]["text"]
    assert not ran, "nothing must run on a refused call"


def test_a_crashing_handler_becomes_a_tool_error(stdio):
    srv, sent = stdio

    def die(arguments):
        raise ValueError("query is required")

    srv._plugin_tools["edt_probe_echo"].handler = die
    srv.handle({"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                "params": {"name": "edt_probe_echo", "arguments": {}}})
    answer = result_of(sent)
    assert answer["isError"]
    assert "edt_probe_echo failed: query is required" in answer["content"][0]["text"]


def test_a_discovery_failure_keeps_the_server_serving(monkeypatch):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("broken", RuntimeError("boom")),
    ))
    srv = server.StdioServer(server.Backend())
    assert srv._plugin_tools == {}
    assert "boom" in srv._plugin_error


# -- the CLI ----------------------------------------------------------------


def test_plugins_command_reports_the_tools(monkeypatch, capsys):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("probe", [tool("edt_probe_echo"), tool("edt_probe_more")]),
    ))
    assert cli.run("plugins", []) == 0
    out = capsys.readouterr().out
    assert "probe: edt_probe_echo, edt_probe_more" in out


def test_plugins_command_reports_a_failure_and_exits_1(monkeypatch, capsys):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("broken", RuntimeError("boom")),
    ))
    assert cli.run("plugins", []) == 1
    assert "boom" in capsys.readouterr().err


def test_plugins_command_says_when_nothing_is_installed(monkeypatch, capsys):
    monkeypatch.setattr(plugins, "entry_points", scan_of())
    assert cli.run("plugins", []) == 0
    assert plugins.TOOLS_GROUP in capsys.readouterr().out


def test_plugins_command_names_the_kill_switch(monkeypatch, capsys):
    monkeypatch.setenv(plugins.ENV_DISABLE, "1")
    assert cli.run("plugins", []) == 0
    assert plugins.ENV_DISABLE in capsys.readouterr().out


def test_cli_call_dispatches_a_plugin_tool_without_a_backend(monkeypatch, capsys):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("probe", tool()),
    ))

    def no_backend(self, *a, **kw):
        raise AssertionError("a plugin call must not touch the backend")

    monkeypatch.setattr(server.Backend, "ensure", no_backend)
    monkeypatch.setattr(server.Backend, "forward", no_backend)
    assert cli.run("call", ["edt_probe_echo", "--json", '{"text": "привет"}']) == 0
    assert json.loads(capsys.readouterr().out) == {"echo": {"text": "привет"}}


def test_cli_call_refuses_an_unknown_argument_with_exit_2(monkeypatch, capsys):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("probe", tool()),
    ))
    assert cli.run("call", ["edt_probe_echo", "--json", '{"texd": "x"}']) == 2
    assert "texd" in capsys.readouterr().err


def test_cli_tools_appends_plugin_tools_to_the_bridge_list(monkeypatch, capsys):
    monkeypatch.setattr(plugins, "entry_points", scan_of(
        FakeEntryPoint("probe", tool()),
    ))
    monkeypatch.setattr(server.Backend, "ensure", lambda self, wait: (True, "up"))
    monkeypatch.setattr(server.Backend, "forward", lambda self, payload: {
        "jsonrpc": "2.0", "id": 1,
        "result": {"tools": [{"name": "edt_projects", "description": "bridge"}]},
    })
    assert cli.run("tools", []) == 0
    lines = capsys.readouterr().out.split()
    assert "edt_projects" in lines and "edt_probe_echo" in lines
