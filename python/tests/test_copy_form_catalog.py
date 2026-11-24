"""The offline catalog describes edt_copy_form.

Without the tool an agent moves a form by creating an empty one and copying Form.form and the module
on disk, behind EDT's back. The catalog is what it reads before EDT is up.
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _tool(name):
    catalog = json.loads((ROOT / "python/src/edt_bridge_mcp/tool_catalog.json").read_text(encoding="utf-8"))
    return next(tool for tool in catalog["tools"] if tool["name"] == name)


def test_the_copy_names_its_source_form_and_its_target_object():
    schema = _tool("edt_copy_form")["inputSchema"]
    assert schema["required"] == ["projectName", "sourceFormFqn", "targetOwnerFqn"]
    source = schema["properties"]["sourceFormFqn"]["description"]
    assert "<Type>.<Owner>.Form.<Name>" in source and "CommonForm.<Name>" in source


def test_the_copy_is_a_planned_write():
    tool = _tool("edt_copy_form")
    assert tool["description"].startswith("WRITE")
    assert tool["inputSchema"]["properties"]["apply"]["type"] == "boolean"
    assert "dry-run" in tool["inputSchema"]["properties"]["apply"]["description"]


def test_the_copy_points_to_adoption_for_a_base_form():
    tool = _tool("edt_copy_form")
    assert "edt_adopt_object" in tool["description"]
    assert "edt_adopt_object" in tool["descriptionRu"]
