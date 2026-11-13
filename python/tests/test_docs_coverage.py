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


#: The documents the guard reads from the repository root rather than from `docs/`. The copy
#: carries them so that the fake root is a whole repository: a page of the copy names its own
#: root when it reports a finding, and a check that reads a root document reads the copy's.
#: The message catalogue travels with them, for the same reason and by the name the guard
#: itself gives it - a copy missing it is a copy the jargon check reports as renamed, and that
#: finding would then ride along with every provocation below and mean nothing in any of them.
ROOT_DOCUMENTS = ("README.md", "python/README.ru.md")

#: The folders of sources the copy carries, with the endings that travel from each. Only the
#: files a check opens are copied: the attribution check reads comments and docstrings, so the
#: Python and the Java go and the build output, the jars and the diagrams stay. A folder the
#: guard names and does not find is a finding of its own, and that one would ride along with
#: every provocation below the way a renamed catalogue would.
SOURCE_FOLDERS = {".py": ("python", "scripts"),
                  ".java": ("io.github.keyfire.edtbridge/src", "tests/java")}


def _only(suffix):
    """Copy filter: keep the folders and the files of one ending, leave everything else."""
    def ignore(folder, names):
        return [name for name in names
                if not name.endswith(suffix) and not (Path(folder) / name).is_dir()]
    return ignore


@pytest.fixture()
def sabotage(guard, tmp_path, monkeypatch):
    """Run the guard over a COPY of the repository, its pages edited by the given function."""
    def run(edit, *, documents: dict[str, str] | None = None,
            without: tuple[str, ...] = ()):
        docs = tmp_path / "docs"
        shutil.copytree(ROOT / "docs", docs)
        for suffix, folders in SOURCE_FOLDERS.items():
            for folder in folders:
                shutil.copytree(ROOT / folder, tmp_path / folder, ignore=_only(suffix))
        for name in (*ROOT_DOCUMENTS, *guard.ATTRIBUTION_DOCUMENTS, *guard.RUSSIAN_SOURCES):
            target = tmp_path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                shutil.copyfile(ROOT / name, target)
        for name, text in (documents or {}).items():
            target = tmp_path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        for name in without:
            (tmp_path / name).unlink()
        # every page of both trees, by its path under docs/: the pages are in folders, and an
        # edit that names a page names it the way the guard does
        for path in sorted(docs.rglob("*.md")):
            name = path.relative_to(docs).as_posix()
            edited = edit(name, path.read_text(encoding="utf-8"))
            path.write_text(edited, encoding="utf-8")
        monkeypatch.setattr(
            guard, "LAYOUT",
            guard.Layout(root=tmp_path, docs=docs, site_config=guard.LAYOUT.site_config,
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
    # Read through a constant bound above rather than asked for by name. The first reader saw
    # neither, so the page could have dropped either one and the check would have said nothing.
    assert "EDT_BRIDGE_LANG" in variables
    assert "EDT_BRIDGE_PLUGIN_INDEX" in variables


def test_the_pages_and_the_code_agree_on_the_whole_set(guard):
    """Both directions now, so the two sets have to be the same set."""
    for folder in guard.LOCALES.values():
        name = folder + guard.SETTINGS_PAGE
        listed = set(guard._ENV_INLINE.findall(guard.LAYOUT.page(name)))
        assert listed == guard.env_variables(), name


def test_the_catalogue_is_read_from_every_page_of_both_trees(guard):
    # the catalogue is a folder of pages now; a reader that found one page, or none, would
    # still pass the row-count rule below for every tool it never saw
    for locale in guard.LOCALES:
        pages = guard.tool_pages(locale)
        assert len(pages) > 5, locale
        rows = [tool for name in pages for tool in guard.tool_rows(guard.LAYOUT.page(name))]
        assert sorted(rows) == sorted(guard.registered_tools()), locale


def test_guard_notices_a_tool_without_a_row(sabotage):
    found = sabotage(lambda name, text: text.replace("`edt_projects`", ""))
    assert any("edt_projects" in problem for problem in found)


def test_guard_notices_a_tool_described_on_two_pages(sabotage):
    # two descriptions of one tool are two texts to keep in step, and the pages were divided
    # by hand: nothing else stops a row from being copied instead of moved
    found = sabotage(
        lambda name, text: text + "\n| `edt_projects` | Described a second time. |\n"
        if name == "tools/debug.md" else text)
    assert any("edt_projects has 2 table rows" in problem for problem in found)


def test_guard_notices_a_tool_named_only_in_prose(sabotage):
    # a mention keeps the coverage check quiet, so a row lost in a move would pass it
    def edit(name, text):
        if name == "tools/read-diagnostics.md":
            return text.replace("| `edt_check_info` |", "| the check reference |")
        if name == "tools/debug.md":
            return text + "\nSee `edt_check_info`.\n"
        return text
    found = sabotage(edit)
    assert any("edt_check_info is mentioned and has no table row" in problem for problem in found)


def test_guard_notices_an_undocumented_variable(guard, monkeypatch):
    monkeypatch.setattr(guard, "env_variables", lambda: {"EDT_BRIDGE_INVENTED"})
    assert any("EDT_BRIDGE_INVENTED" in problem for problem in guard.problems())


def test_guard_notices_a_variable_the_code_no_longer_reads(sabotage, guard):
    """The direction the check was missing: a knob described on the page and gone from the code.

    A reader follows the page, sets the variable and waits for something to happen.
    """
    found = sabotage(
        lambda name, text: text + "\nSet `EDT_BRIDGE_RETIRED` to change nothing.\n"
        if name.endswith(guard.SETTINGS_PAGE) else text)
    assert any("EDT_BRIDGE_RETIRED" in problem for problem in found)


def test_guard_notices_a_reader_that_has_gone_quiet(guard, monkeypatch):
    """An empty sources side finds nothing, and finding nothing reads as a clean repository."""
    monkeypatch.setattr(guard, "env_variables", set)
    assert any("has the shape the reader knows changed" in problem
               for problem in guard.check_environment())

    monkeypatch.setattr(guard, "registered_tools", set)
    assert any("has the shape the reader knows changed" in problem
               for problem in guard.check_tools())


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


def test_guard_notices_a_page_without_its_translation(sabotage):
    # the two editions are in separate folders: a page missing from one of them is not seen
    # beside its twin, and the site quietly serves the other language at that address
    found = sabotage(lambda name, text: text, without=("docs/ru/tools/debug.md",))
    assert any("tools/debug.md: has no ru/tools/debug.md" in problem for problem in found)


def test_guard_notices_a_russian_page_without_its_original(sabotage):
    found = sabotage(lambda name, text: text, without=("docs/native-forms/results.md",))
    assert any("ru/native-forms/results.md: has no native-forms/results.md" in problem
               for problem in found)


def test_guard_notices_an_anchor_of_a_moved_section_gone(sabotage):
    # the card of an older release on PyPI links /install#environment-variables; the section
    # is on the settings page now, and the anchor left behind is what that link lands on
    found = sabotage(
        lambda name, text: text.replace('<a id="environment-variables"></a>', ""))
    assert any('(setup)/install.md: the anchor "environment-variables"' in problem
               for problem in found)


def test_guard_notices_a_reworded_heading_older_links_point_at(sabotage):
    found = sabotage(
        lambda name, text: text.replace("## Manual install (without the wrapper)",
                                        "## Manual installation"))
    assert any('the heading "Manual install (without the wrapper)" is gone' in problem
               for problem in found)


def test_guard_notices_a_readme_anchor_gone(sabotage):
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    found = sabotage(
        lambda name, text: text,
        documents={"README.md": readme.replace('<a name="build-from-source"></a>', "")})
    assert any('README.md: the anchor "build-from-source"' in problem for problem in found)


def test_guard_notices_jargon_on_a_site_page(sabotage):
    found = sabotage(
        lambda name, text: text + "\nПрогон покраснел.\n" if name == "ru/index.md" else text)
    assert any("docs/ru/index.md" in problem and "is jargon" in problem for problem in found)


def test_guard_reads_the_russian_pages_the_default_glob_misses(sabotage):
    # the wiring this repository adds: the Russian edition of the root documents lives in
    # docs/ru/, and the wrapper's README a directory away, so both are named to the check
    found = sabotage(
        lambda name, text: text,
        documents={"docs/ru/ONBOARDING.ru.md": "Ворктри на каждую задачу.\n",
                   "python/README.ru.md": "По дефолту стоит ноль.\n"},
    )
    assert any("docs/ru/ONBOARDING.ru.md" in problem for problem in found)
    assert any("python/README.ru.md" in problem for problem in found)


def test_guard_notices_jargon_in_the_help_catalogue(sabotage, guard):
    # help text is Russian the same reader reads, one surface before the site: it is printed
    # by the wrapper in a terminal, and until now nothing read it at all
    catalogue = guard.RUSSIAN_SOURCES[0]
    found = sabotage(
        lambda name, text: text,
        documents={catalogue: 'MESSAGES = {"x": {"ru": "Фикс уехал в релиз."}}'},
    )
    assert any(catalogue in problem and "is jargon" in problem for problem in found)


def test_guard_notices_a_renamed_help_catalogue(sabotage, guard):
    # a catalogue that moved leaves the check reading nothing, and reading nothing is what a
    # repository in order looks like
    found = sabotage(lambda name, text: text, without=guard.RUSSIAN_SOURCES)
    assert any("has it been renamed" in problem for problem in found)


def test_a_jargon_word_quoted_as_a_word_is_left_alone(sabotage):
    # how a changelog entry tells which transliteration was replaced without becoming a finding
    found = sabotage(
        lambda name, text: text + "\nСправка говорит `рабочая область`, а не `воркспейс`.\n"
        if name == "ru/index.md" else text)
    assert not any("is jargon" in problem for problem in found)


def test_guard_notices_a_group_missing_from_one_annotation(guard, monkeypatch):
    # the failure this check exists for: the tools page has the group and the one-liners
    # around it - what a search engine and an AI answer quote - do not name it
    surfaces = guard.surfaces()
    surfaces["ru"]["docs/ru/index.md"] = surfaces["ru"]["docs/ru/index.md"].replace("отлад", "")
    monkeypatch.setattr(guard, "surfaces", lambda: surfaces)
    problems = guard.check_pitches()
    assert len(problems) == 1
    assert "docs/ru/index.md" in problems[0]


def test_guard_notices_a_catalogue_page_no_row_names(sabotage):
    # a page added to the catalogue with no row in the table: no annotation is asked to name it
    found = sabotage(
        lambda name, text: text,
        documents={"docs/tools/invented.md": '---\ntitle: "Invented group"\n---\n\nNothing.\n'})
    assert any('"Invented group" is in no table row' in problem for problem in found)


def test_guard_notices_a_row_the_catalogue_dropped(sabotage):
    found = sabotage(
        lambda name, text: text.replace('title: "Debug"', 'title: "Debugging"')
        if name == "tools/debug.md" else text)
    assert any('the table names "Debug", the page does not' in problem for problem in found)


def test_the_annotations_are_read_as_annotations(guard):
    # an extractor quietly returning a whole file would make every word check above vacuous
    for locale, group in guard.surfaces().items():
        for where, text in group.items():
            assert 80 < len(text) < 600, f"{locale} {where}: {len(text)} characters"
