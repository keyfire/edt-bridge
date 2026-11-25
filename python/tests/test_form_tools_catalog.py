"""The offline catalog describes what the form tools take.

An agent reads the catalog before EDT is up. A setting it does not find there is a setting the
agent works around by editing Form.form on disk, behind EDT's back.
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _tools():
    catalog = json.loads((ROOT / "python/src/edt_bridge_mcp/tool_catalog.json").read_text(encoding="utf-8"))
    return {tool["name"]: tool for tool in catalog["tools"]}


def test_a_column_group_layout_is_described_on_both_item_tools():
    tools = _tools()
    for name in ("edt_add_form_item", "edt_modify_form_item"):
        properties = tools[name]["inputSchema"]["properties"]
        assert "InCell" in properties["groupDirection"]["description"], name
        assert "column group" in properties["showTitle"]["description"], name


def test_both_command_tools_take_a_picture_by_the_names_form_form_stores():
    tools = _tools()
    for name in ("edt_add_form_command", "edt_modify_form_command"):
        picture = tools[name]["inputSchema"]["properties"]["picture"]
        assert picture["type"] == "string", name
        assert "StdPicture." in picture["description"] and "CommonPicture." in picture["description"], name
    assert "removes it" in tools["edt_modify_form_command"]["inputSchema"]["properties"]["picture"]["description"]


def test_both_item_tools_make_an_input_field_multi_line():
    tools = _tools()
    for name in ("edt_add_form_item", "edt_modify_form_item"):
        properties = tools[name]["inputSchema"]["properties"]
        assert properties["multiLine"]["type"] == "boolean", name
        assert properties["wrap"]["type"] == "boolean", name


def test_both_item_tools_take_a_picture_for_a_picture_decoration():
    tools = _tools()
    for name in ("edt_add_form_item", "edt_modify_form_item"):
        picture = tools[name]["inputSchema"]["properties"]["picture"]
        assert picture["type"] == "string", name
        assert "StdPicture." in picture["description"] and "decoration" in picture["description"], name
