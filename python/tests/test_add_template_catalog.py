"""The offline catalog says which templates edt_add_template creates and how to fill them.

An agent reads the catalog before EDT is up. If it does not name the HTML and text templates and
the parameter that carries their starting text, the agent writes those files on disk, behind
EDT's back, which is what the tool is there to avoid.
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _tools():
    catalog = json.loads((ROOT / "python/src/edt_bridge_mcp/tool_catalog.json").read_text(encoding="utf-8"))
    return {tool["name"]: tool for tool in catalog["tools"]}


def test_html_and_text_templates_are_among_the_types():
    kinds = _tools()["edt_add_template"]["inputSchema"]["properties"]["templateType"]["description"]
    for name in ("HTMLDocument", "HTMLДокумент", "TextDocument", "ТекстовыйДокумент"):
        assert name in kinds, name


def test_the_starting_text_has_a_parameter_of_its_own():
    schema = _tools()["edt_add_template"]["inputSchema"]
    assert schema["properties"]["text"]["type"] == "string"
    assert "text" not in schema.get("required", [])


def test_both_descriptions_send_an_html_page_to_the_tool_that_replaces_it():
    tools = _tools()
    create = tools["edt_add_template"]
    for field in ("description", "descriptionRu"):
        assert "HTML_DOCUMENT" in create[field] and "TEXT_DOCUMENT" in create[field], field
        assert "edt_replace_html_template" in create[field], field
    assert "edt_replace_html_template" in tools
