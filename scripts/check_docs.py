#!/usr/bin/env python
"""Does the documentation still cover the bridge: tools, variables, images, annotations, words.

What is the bridge's own business stays here - which tools the Java side registers, which
variables the wrapper reads, which pages hold the tool catalogue, where the Russian pages live,
which anchors older links still point at, which sources hold Russian a person reads, and which
documents and folders are read for a sentence that explains a change by naming who asked for it.
Everything underneath (reading a page, the block between the injection markers, the
annotations a repository states about itself, the gap between what the sources offer and what
a page lists, the jargon dictionary, the runner) comes from the `docsguard` package, which
three repositories were keeping in triplicate until the copies drifted.

One check from that package is deliberately not used here. `claim_problems` guards a statement
that several documents and the code all have to make in the same words, and its table is worth
writing once a correction has reached one place and left the others behind. Nothing here has
drifted that way yet: the port the bridge listens on is spelled 8770 in all thirteen places
that name it.

Run: `python scripts/check_docs.py`; the exit code is what CI reads.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from docsguard import (
    Layout,
    PitchItem,
    attribution_problems,
    attribution_self_check,
    coverage_problems,
    front_description,
    image_problems,
    injection_problems,
    jargon_problems,
    jargon_self_check,
    pitch_problems,
    pyproject_description,
    run,
    site_description,
    source_attribution_problems,
    source_jargon_problems,
)

ROOT = Path(__file__).resolve().parent.parent
JAVA_TOOLS = ROOT / "io.github.keyfire.edtbridge" / "src" / "io" / "github" / "keyfire" / "edtbridge" / "tools"
WRAPPER = ROOT / "python" / "src" / "edt_bridge_mcp"

LAYOUT = Layout(
    root=ROOT,
    docs=ROOT / "docs",
    site_config=ROOT / "site" / "blume.config.ts",
    pyproject=ROOT / "python" / "pyproject.toml",
    raw_prefix="https://raw.githubusercontent.com/keyfire/edt-bridge/main/",
)

#: The Russian pages, by glob, inside the documentation folder. The site keeps each language in
#: a tree of its own: English at the root of `docs/`, Russian under `docs/ru/` by the same paths.
#: The Russian edition of the root documents (`README.ru.md` and the others) lies in `docs/ru/`
#: as well, so one pattern reads both. The changelog, onboarding and security pages are mirrors
#: built by `scripts/sync-docs.mjs` and are absent from a fresh checkout - when they are there,
#: they are read as copies of their source, which costs a second reading of a finding and
#: nothing else.
RUSSIAN_PAGES = ("ru/**/*.md",)

#: The two trees of site pages: the language, and the folder under `docs/` that holds it.
LOCALES = {"en": "", "ru": "ru/"}

#: Files under `docs/` that are not site pages: working notes the site config leaves out.
WORKING_NOTES = ("DESIGN.md",)

#: The folder of the tool catalogue inside a language tree, and the page of that tree that
#: describes every `EDT_BRIDGE_` variable.
TOOLS_FOLDER = "tools/"
SETTINGS_PAGE = "(setup)/settings.md"

#: The Russian documents outside the documentation folder. The wrapper's README ships to PyPI
#: as the package card, so it is read by more people than most pages here.
RUSSIAN_DOCUMENTS = ("python/README.ru.md",)

#: The sources whose Russian strings a person reads. Here that is one file: every line of
#: `--help` the wrapper prints comes out of its message catalog, and it reaches a terminal the
#: way a page reaches the site - so the same dictionary reads both.
#:
#: Nothing else in the repository belongs here, and each for its own reason. The Java side
#: writes English: what a tool says goes to an agent over the protocol, not to a reader. The
#: Russian of `scripts/gen-cli-docs.py` is the text of `docs/ru/cli.md`, and that page is read as
#: a page a few lines above - naming the generator as well would report one word twice, and a page
#: that has drifted from its generator is a failure of the suite rather than of this guard. The
#: Russian in the tests is jargon on purpose: it is what proves the guard still bites.
RUSSIAN_SOURCES = ("python/src/edt_bridge_mcp/i18n.py",)

_TOOL_NAME = re.compile(r'String name\(\)\s*\{\s*return\s+"(edt_[a-z_]+)"', re.S)
#: A tool served by the wrapper itself - it has no Java class, only an entry in the local list.
_LOCAL_TOOL = re.compile(r'"name":\s*"(edt_[a-z_]+)"')
#: A variable is read by the code where it is asked for by name...
_ENV_READ = re.compile(r'(?:System\.getenv\(|os\.environ\.get\()"(EDT_BRIDGE_[A-Z_]+)"')
#: ...or where the name is bound to a constant and read through that later. Both halves are
#: needed: `EDT_BRIDGE_LANG` and `EDT_BRIDGE_PLUGIN_INDEX` are read that way, the first reader
#: never saw them, and a variable the reader cannot see is one the coverage check passes for
#: free. The name has to fill the quotes on its own, so the `set "EDT_BRIDGE_TOKEN=..."` line
#: the auto-start writes into a batch file is not mistaken for a declaration.
_ENV_DECLARED = re.compile(r'"(EDT_BRIDGE_[A-Z_]+)"')
_INLINE = re.compile(r"`(edt_[a-z_]+)`")
_ENV_INLINE = re.compile(r"`(EDT_BRIDGE_[A-Z_]+)`")
_TITLE = re.compile(r'^title:\s*"(.*)"\s*$', re.M)

#: A page of the tool catalogue - its English title, the Russian one - and the word that has to
#: stand for that page in the short annotations of its language. One word stands for a whole
#: page on purpose: an annotation names the kind of work, not the tools, so the five pages of
#: write tools share one word. The overview page is not a row: it holds the table of these
#: pages and the two tools of the wrapper, which are plumbing and not a reason to install.
PITCH_GROUPS = (
    PitchItem("Read: diagnostics and reference", "Чтение: диагностика и справка",
              "diagnostics", "диагностик"),
    PitchItem("Read: metadata, code and queries", "Чтение: метаданные, код и запросы",
              "metadata", "метаданн"),
    # Forms and templates are metadata to an annotation: it has no word of its own for them.
    PitchItem("Read: forms and templates", "Чтение: формы и макеты", "metadata", "метаданн"),
    PitchItem("Write: metadata objects", "Запись: объекты метаданных", "write", "запис"),
    PitchItem("Write: modules", "Запись: модули", "write", "запис"),
    PitchItem("Write: forms", "Запись: формы", "write", "запис"),
    PitchItem("Write: templates", "Запись: макеты", "write", "запис"),
    PitchItem("Write: projects and builds", "Запись: проекты и сборка", "write", "запис"),
    PitchItem("Infobases, the cluster and the platform", "Информационные базы, кластер и платформа",
              "infobase", "информационн"),
    PitchItem("Debug", "Отладка", "debug", "отлад"),
)

#: The anchors of sections that moved to a page of their own when the long pages were divided.
#: A link from outside - a PyPI card of an older release, a bookmark, another site - still ends
#: in the old anchor, and a redirect cannot carry one. So the old page keeps each of them as an
#: explicit `<a id>` on the line that links the section's new place.
MOVED_ANCHORS = {
    "index.md": ("settings-inside-edt",),
    "(setup)/install.md": (
        "environment-variables", "write-scope", "a-ready-workspace", "build-from-source",
        "wrapper-plugins", "when-pipx-runs-on-uv-pipx--115",
    ),
    "tools/index.md": (
        "read", "project-and-runtime-diagnostics", "managed-form-preview", "write",
        "html-source-form-ids-and-validation", "replace-an-existing-bsl-method", "register-fields",
        "infobases-the-cluster-and-the-platform", "check-platform-build-readiness",
        "incremental-configuration-loading", "debug",
        "placement-properties-and-validation-snapshots",
    ),
    "native-forms/index.md": (
        "scenario-format", "check-an-external-report",
        "check-an-installed-extension-and-saved-values", "results",
    ),
    "ru/index.md": ("настройки-в-самой-edt",),
    "ru/(setup)/install.md": (
        "переменные-окружения", "граница-записи", "готовая-рабочая-область",
        "сборка-из-исходников", "плагины-обвязки", "когда-pipx-работает-поверх-uv-pipx--115",
    ),
    "ru/tools/index.md": (
        "чтение", "диагностика-проекта-и-среды", "предпросмотр-управляемой-формы", "запись",
        "html-id-формы-и-проверка-метаданных", "замена-существующего-метода-bsl", "поля-регистров",
        "информационные-базы-кластер-и-платформа", "проверка-готовности-платформы-к-сборке",
        "загрузка-измененных-файлов", "отладка", "размещение-свойства-и-снимки-замечаний",
    ),
    "ru/native-forms/index.md": (
        "формат-сценария", "проверка-внешнего-отчета",
        "проверка-установленного-расширения-и-сохраненных-значений", "результаты",
    ),
}

#: The headings older links point at that are still on their page. The anchor comes from the
#: text of the heading, so rewording one of these breaks the link the same way moving it would.
KEPT_HEADINGS = {
    "index.md": ("Install (recommended: pipx)", "Nearby", "The page in a browser"),
    "(setup)/install.md": ("Manual install (without the wrapper)",),
    "tools/index.md": ("Served by the wrapper",),
    "native-forms/index.md": ("Prepare the environment", "Select a target",
                              "Existing single-button checks"),
    "ru/index.md": ("Установка (рекомендуется: pipx)", "Рядом", "Страница в браузере"),
    "ru/(setup)/install.md": ("Установка вручную (без обвязки)",),
    "ru/tools/index.md": ("Инструменты самой обвязки",),
    "ru/native-forms/index.md": ("Подготовка окружения", "Выбор цели",
                                 "Существующая проверка одной кнопки"),
}

#: The same for the two READMEs, whose sections went to the site. GitHub documents `<a name>`
#: for a custom anchor, so that is the spelling there.
README_ANCHORS = {
    "README.md": ("requirements", "manual-install-without-the-wrapper", "settings-inside-edt",
                  "build-from-source", "the-page-in-a-browser"),
    "docs/ru/README.ru.md": ("требования", "установка-вручную-без-обвязки",
                             "настройки-в-самой-edt", "сборка-из-исходников",
                             "страница-в-браузере"),
}


def registered_tools() -> set[str]:
    """Every tool the bridge serves: the Java classes plus the wrapper's own."""
    found = set()
    for path in JAVA_TOOLS.glob("*.java"):
        found.update(_TOOL_NAME.findall(path.read_text(encoding="utf-8")))
    for path in WRAPPER.glob("*.py"):
        found.update(_LOCAL_TOOL.findall(path.read_text(encoding="utf-8")))
    return found


def env_variables() -> set[str]:
    """Every EDT_BRIDGE_ variable the code reads - on either side, however it is spelled."""
    found = set()
    for folder, pattern in ((JAVA_TOOLS.parent, "**/*.java"), (WRAPPER, "*.py")):
        for path in folder.glob(pattern):
            text = path.read_text(encoding="utf-8")
            found.update(_ENV_READ.findall(text))
            found.update(_ENV_DECLARED.findall(text))
    return found


def site_pages(locale: str) -> list[str]:
    """The pages of one language, as paths under `docs/`.

    The Russian tree shares its folder with the Russian edition of the root documents; those
    end in `.ru.md` and are not pages, which is also how the site config tells them apart.
    """
    folder = LAYOUT.docs / LOCALES[locale] if LOCALES[locale] else LAYOUT.docs
    found = []
    for path in sorted(folder.rglob("*.md")):
        name = path.relative_to(LAYOUT.docs).as_posix()
        if locale == "en" and (name.startswith("ru/") or name in WORKING_NOTES):
            continue
        if locale == "ru" and name.endswith(".ru.md"):
            continue
        found.append(name)
    return found


def tool_pages(locale: str) -> list[str]:
    """The pages of the tool catalogue of one language."""
    prefix = LOCALES[locale] + TOOLS_FOLDER
    return [name for name in site_pages(locale) if name.startswith(prefix)]


def tool_rows(text: str) -> list[str]:
    """The tools a page describes: every name in the first cell of a table row."""
    found: list[str] = []
    for line in text.splitlines():
        if line.startswith("|"):
            found += _INLINE.findall(line.split("|")[1])
    return found


def check_tools() -> list[str]:
    """Every registered tool is described on one page of the catalogue, in one row.

    Both directions and the empty reader come from `coverage_problems`: the set difference was
    written out by hand here, once per check, and the environment copy had already lost the
    empty-reader guard this one had. What stays is what the sources are and where the pages are.

    The row count is this repository's own rule. The catalogue is divided over several pages,
    and a tool described on two of them is two texts to keep in step; one of them falls behind.
    Naming a tool in the prose of another page is not a second description, so only the first
    cell of a table row counts.
    """
    tools = registered_tools()
    problems: list[str] = []
    for locale, folder in LOCALES.items():
        where = f"{folder}{TOOLS_FOLDER}"
        texts = {name: LAYOUT.page(name) for name in tool_pages(locale)}
        listed = {tool for text in texts.values() for tool in _INLINE.findall(text)}
        problems += coverage_problems(tools, listed, what="tool", where=where)
        rows: dict[str, list[str]] = {}
        for name, text in texts.items():
            for tool in tool_rows(text):
                rows.setdefault(tool, []).append(name)
        for tool in sorted(tools & listed):
            owners = rows.get(tool, [])
            if not owners:
                problems.append(f"{where}: the tool {tool} is mentioned and has no table row")
            elif len(owners) > 1:
                problems.append(
                    f"{where}: the tool {tool} has {len(owners)} table rows "
                    f"({', '.join(owners)}) - one tool is described in one row")
    return problems


def check_environment() -> list[str]:
    """A variable the code reads and the settings page does not describe, and the reverse.

    The other direction is judged here too, which it was not before: the page is the only place
    a variable is described, so a name it carries and the code has no longer got sends a reader
    after a knob that does nothing. It was safe to turn on once the reader learned to see a
    variable read through a constant - until then those two looked like phantoms.
    """
    variables = env_variables()
    problems: list[str] = []
    for folder in LOCALES.values():
        name = folder + SETTINGS_PAGE
        problems += coverage_problems(
            variables, set(_ENV_INLINE.findall(LAYOUT.page(name))),
            what="variable", where=name,
        )
    return problems


def check_injections() -> list[str]:
    """The table of tool groups in both READMEs still matches the overview page it comes from."""
    return injection_problems(LAYOUT, [
        ("README.md", "tools", "tools/index.md", "Tool groups"),
        ("docs/ru/README.ru.md", "tools", "ru/tools/index.md", "Группы инструментов"),
    ])


def check_images() -> list[str]:
    """Every image of every page is in the repository, and a page shows the SVG.

    The working notes are read with the pages. The Russian edition of the root documents is
    not: a README shows the PNG on purpose, and this check would call that a defect.
    """
    names = [*WORKING_NOTES, *site_pages("en"), *site_pages("ru")]
    return image_problems(
        LAYOUT, [name for name in names if LAYOUT.page_path(name).is_file()], prefer_svg=True
    )


def check_translations() -> list[str]:
    """Every page exists in both languages, at the same path of its tree.

    The two editions are in separate folders, so a page added to one of them is not missed by
    looking at its neighbours. The site then serves the English text at the Russian address, or
    offers no English page at all, and nothing about the build says so.
    """
    english = set(site_pages("en"))
    russian = {name.removeprefix(LOCALES["ru"]) for name in site_pages("ru")}
    return ([f"{name}: has no {LOCALES['ru']}{name}" for name in sorted(english - russian)]
            + [f"{LOCALES['ru']}{name}: has no {name}" for name in sorted(russian - english)])


def check_anchors() -> list[str]:
    """The addresses older links point at are still on their pages.

    Three shapes of the same promise: a section that moved leaves an explicit anchor behind, a
    section that stayed keeps the heading its anchor is made from, and a README keeps the named
    anchors of the sections it handed to the site.
    """
    problems: list[str] = []
    for name, anchors in MOVED_ANCHORS.items():
        text = LAYOUT.page(name)
        problems += [f'{name}: the anchor "{anchor}" of a section that moved is gone - '
                     f"older links to it land at the top of the page"
                     for anchor in anchors if f'<a id="{anchor}"></a>' not in text]
    for name, kept in KEPT_HEADINGS.items():
        lines = LAYOUT.page(name).splitlines()
        problems += [f'{name}: the heading "{heading}" is gone or reworded - '
                     f"older links end in its anchor"
                     for heading in kept if f"## {heading}" not in lines]
    for name, anchors in README_ANCHORS.items():
        text = LAYOUT.document(name)
        problems += [f'{name}: the anchor "{anchor}" of a section that went to the site is gone'
                     for anchor in anchors if f'<a name="{anchor}"></a>' not in text]
    return problems


def surfaces() -> dict[str, dict[str, str]]:
    """The one-line annotations, by locale - what is quoted instead of the page being read.

    The README lede is not here: it enumerates the same capabilities in a paragraph of its own,
    right above the tool catalogue injected from the page.
    """
    return {
        "en": {
            "site/blume.config.ts": site_description(LAYOUT),
            "docs/index.md": front_description(LAYOUT, "index.md"),
            "python/pyproject.toml": pyproject_description(LAYOUT),
        },
        "ru": {"docs/ru/index.md": front_description(LAYOUT, "ru/index.md")},
    }


def tool_page_titles(locale: str) -> list[str]:
    """The titles of the catalogue pages of one language, the overview page left out."""
    index = LOCALES[locale] + TOOLS_FOLDER + "index.md"
    titles = []
    for name in tool_pages(locale):
        found = _TITLE.search(LAYOUT.page(name))
        if name != index and found:
            titles.append(found.group(1))
    return titles


def check_pitches() -> list[str]:
    """The gaps between the pages of the tool catalogue, the table above and the annotations."""
    return pitch_problems(
        PITCH_GROUPS,
        {locale: tool_page_titles(locale) for locale in LOCALES},
        surfaces(),
        pages={locale: folder + TOOLS_FOLDER for locale, folder in LOCALES.items()},
    )


def check_jargon() -> list[str]:
    """The Russian edition is written in Russian, and the dictionary that says so is awake.

    The first half is about the guard rather than about the pages. A root that has lost a
    letter finds nothing, and finding nothing reads exactly like a repository in order, so the
    dictionary proves itself on its own samples before it is let near a page.

    A word quoted as a word - a changelog entry saying which transliteration was replaced -
    goes in backticks. The guard leaves backticks alone, and the reader sees the quotation.

    The sources are read last. The help of a command is Russian too and lives in a string
    literal rather than on a page, and it reaches a terminal the moment somebody runs the
    wrapper - the same reader, one surface earlier.
    """
    return (
        jargon_self_check()
        + jargon_problems(LAYOUT, pages=RUSSIAN_PAGES, documents=RUSSIAN_DOCUMENTS)
        + source_jargon_problems(LAYOUT, RUSSIAN_SOURCES)
    )


#: The pages read for a sentence that credits a person: both editions, in whatever folder of
#: `docs/` they are. The Russian edition of the root documents lies in `docs/ru/`, the way the
#: jargon list above says, and the same pattern reads it.
ATTRIBUTION_PAGES = ("**/*.md",)

#: The documents outside the documentation folder, English edition - their Russian twins are
#: pages of `docs/ru/` and are read by the glob above. The wrapper's two READMEs ship to PyPI as
#: the package card.
ATTRIBUTION_DOCUMENTS = (
    "README.md",
    "CHANGELOG.md",
    "CONTRIBUTING.md",
    "ONBOARDING.md",
    "ORIGIN.md",
    "SECURITY.md",
    "CODE_OF_CONDUCT.md",
    "python/README.md",
    "python/README.ru.md",
)

#: The folders whose comments and docstrings are read, and here the Java side is read with them.
#: The jargon list above leaves Java out for a good reason - what a tool says there goes to an
#: agent over the protocol, not to a reader - but a comment explaining a decision is written for
#: a person whatever the language around it is, and most of the comments in this repository are
#: Java ones.
ATTRIBUTION_SOURCES = ("python", "scripts")
ATTRIBUTION_JAVA = ("io.github.keyfire.edtbridge/src", "tests/java")


def check_attribution() -> list[str]:
    """No page and no comment explains a change by naming the person who asked for it.

    The repository has one author, so a sentence about who asked gives the reader nothing to act
    on and suggests the code was written for somebody else. What belongs there is what the
    previous behaviour or text got wrong.

    The table lives in `docsguard` and catches a turn of phrase rather than a word, because an
    owner is a word of the subject here as much as anywhere: a form has an owner, an attribute
    is attached to the owner's list, and `force=true` is the owner's explicit override. Those
    sentences stay quiet; what is caught is a possessive beside a noun of deciding or asking, or
    the word beside a verb of speaking.
    """
    return (attribution_self_check()
            + attribution_problems(LAYOUT, pages=ATTRIBUTION_PAGES,
                                   documents=ATTRIBUTION_DOCUMENTS)
            + source_attribution_problems(LAYOUT, ATTRIBUTION_SOURCES)
            + source_attribution_problems(LAYOUT, ATTRIBUTION_JAVA, patterns=("*.java",)))


CHECKS = (check_tools, check_environment, check_injections, check_images, check_translations,
          check_anchors, check_pitches, check_jargon, check_attribution)


def problems() -> list[str]:
    """Every finding of every check - what the test suite asserts on."""
    found: list[str] = []
    for check in CHECKS:
        found.extend(check())
    return found


if __name__ == "__main__":
    sys.exit(run(CHECKS, title="docsguard"))
