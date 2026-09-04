"""The documentation covers what the bridge offers, and the guard that says so still bites.

Two separate failures live here. The first is coverage: a tool, a variable or an image that
the code has and the pages do not - the state the repository was actually in, with
`EDT_BRIDGE_PORT_SCAN` readable and documented nowhere and the README's copy of the tool
catalogue drifted from the page it was copied from. The second is the guard itself going
quiet: a check that stops finding anything looks exactly like a clean repository, so each
class of finding is provoked here on purpose.

The provocation is made on a COPY of the pages rather than by patching the guard's insides:
since the shared parts moved into the `docsguard` package, a patched name in this module would
not be the one the check calls, and the sabotage would prove nothing while still passing.
"""

import importlib.util
import shutil
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def _load():
    spec = importlib.util.spec_from_file_location(
        "bridge_check_docs", ROOT / "scripts" / "check_docs.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="module")
def guard():
    return _load()


@pytest.fixture()
def sabotage(guard, tmp_path, monkeypatch):
    """Run the guard over a COPY of the pages, edited by the given function."""
    def run(edit, *, documents: dict[str, str] | None = None):
        docs = tmp_path / "docs"
        shutil.copytree(ROOT / "docs", docs)
        root = tmp_path if documents is not None else ROOT
        if documents is not None:
            for name, text in documents.items():
                target = tmp_path / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(text, encoding="utf-8")
        for path in sorted(docs.glob("*.md")):
            edited = edit(path.name, path.read_text(encoding="utf-8"))
            path.write_text(edited, encoding="utf-8")
        monkeypatch.setattr(
            guard, "LAYOUT",
            guard.Layout(root=root, docs=docs, site_config=guard.LAYOUT.site_config,
                         pyproject=guard.LAYOUT.pyproject, raw_prefix=guard.LAYOUT.raw_prefix),
        )
        return guard.problems()
    return run


def test_documentation_covers_everything(guard):
    assert guard.problems() == []


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


def test_guard_notices_a_tool_without_a_row(sabotage):
    found = sabotage(lambda name, text: text.replace("`edt_projects`", ""))
    assert any("edt_projects" in problem for problem in found)


def test_guard_notices_an_undocumented_variable(guard, monkeypatch):
    monkeypatch.setattr(guard, "env_variables", lambda: {"EDT_BRIDGE_INVENTED"})
    assert any("EDT_BRIDGE_INVENTED" in problem for problem in guard.problems())


def test_guard_notices_a_stale_readme_block(sabotage):
    stale = "<!-- tools:start -->\nan outdated copy\n<!-- tools:end -->\n"
    found = sabotage(
        lambda name, text: text,
        documents={"README.md": stale, "docs/ru/README.ru.md": stale},
    )
    assert any("no longer matches" in problem for problem in found)


def test_guard_notices_a_page_showing_the_readme_png(sabotage):
    # the page has to embed the SVG - it carries both palettes; the PNG has one baked in and
    # showed a dark diagram to a reader in the light theme
    found = sabotage(
        lambda name, text: text.replace("docs/delivery.ru.svg)", "docs/delivery.ru.png)"))
    assert any("delivery.ru.png follows no theme" in problem for problem in found)


def test_guard_notices_a_missing_image(sabotage):
    gone = ("\n![gone](https://raw.githubusercontent.com/keyfire/edt-bridge/main/"
            "docs/no-such-diagram.svg)\n")
    found = sabotage(lambda name, text: text + gone if name == "index.md" else text)
    assert any("no-such-diagram.svg" in problem for problem in found)


def test_guard_notices_a_group_missing_from_one_annotation(guard, monkeypatch):
    # the failure this check exists for: the tools page has the group and the one-liners
    # around it - what a search engine and an AI answer quote - do not name it
    surfaces = guard.surfaces()
    surfaces["ru"]["docs/index.ru.md"] = surfaces["ru"]["docs/index.ru.md"].replace("отлад", "")
    monkeypatch.setattr(guard, "surfaces", lambda: surfaces)
    problems = guard.check_pitches()
    assert len(problems) == 1
    assert "docs/index.ru.md" in problems[0]


def test_guard_notices_a_group_no_row_names(sabotage):
    found = sabotage(
        lambda name, text: text + "\n### Invented group\n" if name == "tools.md" else text)
    assert any("Invented group" in problem for problem in found)


def test_guard_notices_a_row_the_page_dropped(sabotage):
    found = sabotage(lambda name, text: text.replace("\n### Debug\n", "\n### Debugging\n"))
    assert any("Debug" in problem for problem in found)


def test_the_annotations_are_read_as_annotations(guard):
    # an extractor quietly returning a whole file would make every word check above vacuous
    for locale, group in guard.surfaces().items():
        for where, text in group.items():
            assert 80 < len(text) < 600, f"{locale} {where}: {len(text)} characters"
