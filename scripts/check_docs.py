#!/usr/bin/env python
"""Does the documentation still cover the bridge: tools, variables, images, annotations, words.

What is the bridge's own business stays here - which tools the Java side registers, which
variables the wrapper reads, how the tools page is grouped, where the Russian pages live,
which sources hold Russian a person reads, and which documents and folders are read for a
sentence that explains a change by naming who asked for it.
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
    headings,
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

#: The Russian pages, by glob, inside the documentation folder. `*.ru.md` is what the guard
#: reads on its own and covers the site pages; this repository keeps the Russian edition of its
#: root documents one level down, in `docs/ru/`, so that folder is named too. The changelog and
#: the onboarding page of `docs/` are mirrors built by `scripts/sync-docs.mjs` and are absent
#: from a fresh checkout - when they are there, they are read as copies of their source, which
#: costs a second reading of a finding and nothing else.
RUSSIAN_PAGES = ("*.ru.md", "ru/*.ru.md")

#: The Russian documents outside the documentation folder. The wrapper's README ships to PyPI
#: as the package card, so it is read by more people than most pages here.
RUSSIAN_DOCUMENTS = ("python/README.ru.md",)

#: The sources whose Russian strings a person reads. Here that is one file: every line of
#: `--help` the wrapper prints comes out of its message catalog, and it reaches a terminal the
#: way a page reaches the site - so the same dictionary reads both.
#:
#: Nothing else in the repository belongs here, and each for its own reason. The Java side
#: writes English: what a tool says goes to an agent over the protocol, not to a reader. The
#: Russian of `scripts/gen-cli-docs.py` is the text of `cli.ru.md`, and that page is read as a
#: page two lines above - naming the generator as well would report one word twice, and a page
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

#: A group of the tools page - the English heading, the Russian one - and the word that has to
#: stand for that group in the short annotations of its language. One word stands for a whole
#: group on purpose: an annotation names the kind of work, not the tools. A row with no words is
#: a group deliberately kept out of the annotations, with the reason beside it.
PITCH_GROUPS = (
    PitchItem("Read", "Чтение", "metadata", "метаданн"),
    PitchItem("Write", "Запись", "write", "запис"),
    PitchItem("Infobases, the cluster and the platform", "Информационные базы, кластер и платформа",
              "infobase", "информационн"),
    PitchItem("Debug", "Отладка", "debug", "отлад"),
    # The wrapper's own tools are plumbing - a version, a self-update, the state of the bridge.
    # The wrapper itself is named in the annotations; its tools are not a reason to install.
    PitchItem("Served by the wrapper", "Инструменты самой обвязки", None, None),
)


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


def check_tools() -> list[str]:
    """Every registered tool has a row on the tools page, and no row names a tool that is gone.

    Both directions and the empty reader come from `coverage_problems`: the set difference was
    written out by hand here, once per check, and the environment copy had already lost the
    empty-reader guard this one had. What stays is what the sources are and where the page is.
    """
    tools = registered_tools()
    problems: list[str] = []
    for name in ("tools.md", "tools.ru.md"):
        problems += coverage_problems(
            tools, set(_INLINE.findall(LAYOUT.page(name))), what="tool", where=name,
        )
    return problems


def check_environment() -> list[str]:
    """A variable the code reads and the installation page does not describe, and the reverse.

    The other direction is judged here too, which it was not before: the page is the only place
    a variable is described, so a name it carries and the code has no longer got sends a reader
    after a knob that does nothing. It was safe to turn on once the reader learned to see a
    variable read through a constant - until then those two looked like phantoms.
    """
    variables = env_variables()
    problems: list[str] = []
    for name in ("install.md", "install.ru.md"):
        problems += coverage_problems(
            variables, set(_ENV_INLINE.findall(LAYOUT.page(name))),
            what="variable", where=name,
        )
    return problems


def check_injections() -> list[str]:
    """The tool catalogue injected into both READMEs still matches the page it comes from."""
    return injection_problems(LAYOUT, [
        ("README.md", "tools", "tools.md", None),
        ("docs/ru/README.ru.md", "tools", "tools.ru.md", None),
    ], svg_to_png=True)


def check_images() -> list[str]:
    """Every image of every page is in the repository, and a page shows the SVG."""
    return image_problems(
        LAYOUT, [path.name for path in sorted(LAYOUT.docs.glob("*.md"))], prefer_svg=True
    )


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
        "ru": {"docs/index.ru.md": front_description(LAYOUT, "index.ru.md")},
    }


def check_pitches() -> list[str]:
    """The gaps between the tools page, the table above and the annotations."""
    return pitch_problems(
        PITCH_GROUPS,
        {"en": headings(LAYOUT, "tools.md"), "ru": headings(LAYOUT, "tools.ru.md")},
        surfaces(),
        pages={"en": "tools.md", "ru": "tools.ru.md"},
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


#: The pages read for a sentence that credits a person: both editions. The Russian edition of
#: the root documents lives in `docs/ru/`, the way the jargon list above says, so that folder is
#: named here too.
ATTRIBUTION_PAGES = ("*.md", "ru/*.md")

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


CHECKS = (check_tools, check_environment, check_injections, check_images, check_pitches,
          check_jargon, check_attribution)


def problems() -> list[str]:
    """Every finding of every check - what the test suite asserts on."""
    found: list[str] = []
    for check in CHECKS:
        found.extend(check())
    return found


if __name__ == "__main__":
    sys.exit(run(CHECKS, title="docsguard"))
