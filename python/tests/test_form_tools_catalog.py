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
