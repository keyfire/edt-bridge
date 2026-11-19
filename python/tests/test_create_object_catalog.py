"""The offline catalog says what edt_create_object can create and what name it gives.

The catalog is generated from the Java descriptors and shipped with the wrapper, so an agent reads
it before EDT is up. A role and the prefix of an extension are the parts of the tool a caller
cannot guess: without them an agent asks for an object type the tool refuses, expects the name it
passed, or looks for a way to give the new role rights that the tool leaves to another one.
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _tools():
    catalog = json.loads((ROOT / "python/src/edt_bridge_mcp/tool_catalog.json").read_text(encoding="utf-8"))
    return {tool["name"]: tool for tool in catalog["tools"]}


def test_a_role_is_one_of_the_object_types():
    properties = _tools()["edt_create_object"]["inputSchema"]["properties"]
    kinds = properties["objectType"]["description"].split(":", 1)[1]
    assert "Роль" in [kind.strip() for kind in kinds.split(",")]


def test_the_name_warns_about_the_prefix_of_an_extension():
    name = _tools()["edt_create_object"]["inputSchema"]["properties"]["name"]["description"]
    assert "prefix" in name and "extension" in name


def test_both_descriptions_send_the_rights_of_a_new_role_to_the_tool_that_sets_them():
    tools = _tools()
    create = tools["edt_create_object"]
    for field in ("description", "descriptionRu"):
        assert "Rights.rights" in create[field], field
        assert "edt_modify_role_rights" in create[field], field
    assert "edt_modify_role_rights" in tools
