"""The documentation covers what the bridge offers, and the guard that says so still bites.

Two separate failures live here. The first is coverage: a tool, a variable or an image that
the code has and the pages do not - the state the repository was actually in, with
`EDT_BRIDGE_PORT_SCAN` readable and documented nowhere and the README's copy of the tool
catalogue drifted from the page it was copied from. The second is the guard itself going
quiet: a check that stops finding anything looks exactly like a clean repository, so each
class of finding is provoked here on purpose.
"""

import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="module")
def guard():
    spec = importlib.util.spec_from_file_location("docsguard", ROOT / "scripts" / "docsguard.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_documentation_covers_everything(guard):
    assert guard.check() == []


def test_every_tool_is_found_in_the_sources(guard):
    tools = guard.registered_tools()
    # the exact number moves with the bridge; what must not happen is the registry reading
    # empty or losing the wrapper's own tool, both of which would make the guard vacuous
    assert len(tools) > 50
    assert "edt_projects" in tools and "edt_open_gui" in tools


def test_variables_are_the_ones_the_code_reads(guard):
    variables = guard.env_variables()
    assert "EDT_BRIDGE_PORT_SCAN" in variables, "the reader misses os.environ.get calls"
    assert "EDT_BRIDGE_ALLOW_EVALUATE" in variables, "the reader misses System.getenv calls"


def test_guard_notices_a_tool_without_a_row(guard, monkeypatch):
    original = guard.page
    monkeypatch.setattr(guard, "page", lambda name: original(name).replace("`edt_projects`", ""))
    assert any("edt_projects" in p for p in guard.check())


def test_guard_notices_an_undocumented_variable(guard, monkeypatch):
    monkeypatch.setattr(guard, "env_variables", lambda: {"EDT_BRIDGE_INVENTED"})
    assert any("EDT_BRIDGE_INVENTED" in p for p in guard.check())


def test_guard_notices_a_stale_readme_block(guard, monkeypatch):
    monkeypatch.setattr(guard, "injected", lambda document, marker: "an outdated copy")
    assert any("stale" in p for p in guard.check())


def test_guard_notices_a_page_showing_the_readme_png(guard, monkeypatch):
    # the page has to embed the SVG - it carries both palettes; the PNG has one baked in and
    # showed a dark diagram to a reader in the light theme
    original = guard.page
    monkeypatch.setattr(
        guard, "page",
        lambda name: original(name).replace("docs/delivery.ru.svg)", "docs/delivery.ru.png)"),
    )
    assert any("delivery.ru.png follows no theme" in p for p in guard.check())


def test_guard_notices_a_missing_image(guard, monkeypatch):
    original = guard.page
    monkeypatch.setattr(
        guard, "page",
        lambda name: original(name) + "\n![gone](https://raw.githubusercontent.com/keyfire/"
                                      "edt-bridge/main/docs/no-such-diagram.svg)\n",
    )
    assert any("no-such-diagram.svg" in p for p in guard.check())


def test_guard_notices_a_group_missing_from_one_annotation(guard, monkeypatch):
    # the failure this check exists for: the tools page has the group and the one-liners
    # around it - what a search engine and an AI answer quote - do not name it
    surfaces = guard.pitch_surfaces()
    surfaces["ru"]["docs/index.ru.md"] = surfaces["ru"]["docs/index.ru.md"].replace("отлад", "")
    monkeypatch.setattr(guard, "pitch_surfaces", lambda: surfaces)
    problems = guard.pitch_problems()
    assert len(problems) == 1
    assert "docs/index.ru.md" in problems[0]


def test_guard_notices_a_group_no_row_names(guard, monkeypatch):
    original = guard.tool_groups
    monkeypatch.setattr(guard, "tool_groups", lambda name: original(name) + ["Invented group"])
    assert any("Invented group" in problem for problem in guard.pitch_problems())


def test_guard_notices_a_row_the_page_dropped(guard, monkeypatch):
    original = guard.tool_groups
    monkeypatch.setattr(
        guard, "tool_groups",
        lambda name: [g for g in original(name) if g not in ("Debug", "Отладка")],
    )
    assert any("Debug" in problem for problem in guard.pitch_problems())


def test_the_annotations_are_read_as_annotations(guard):
    # an extractor quietly returning a whole file would make every word check above vacuous
    for locale, group in guard.pitch_surfaces().items():
        for where, text in group.items():
            assert 80 < len(text) < 600, f"{locale} {where}: {len(text)} characters"
