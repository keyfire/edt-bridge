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


def test_guard_notices_a_missing_image(guard, monkeypatch):
    original = guard.page
    monkeypatch.setattr(
        guard, "page",
        lambda name: original(name) + "\n![gone](https://raw.githubusercontent.com/keyfire/"
                                      "edt-bridge/main/docs/no-such-diagram.svg)\n",
    )
    assert any("no-such-diagram.svg" in p for p in guard.check())
