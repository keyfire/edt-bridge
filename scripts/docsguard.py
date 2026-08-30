#!/usr/bin/env python
"""Fail when the documentation stops covering what the bridge actually offers.

The command reference is generated (scripts/gen-cli-docs.py) and cannot go stale. The rest of
the documentation is written by hand, and hand-written pages drift in a way nobody notices:
a tool ships without a row on the tools page, a new `EDT_BRIDGE_*` variable is readable but
documented nowhere, one language gets the update and the other does not. Every one of those
had already happened here - `EDT_BRIDGE_PORT_SCAN` was reachable and unmentioned, and the
README's copy of the tool catalogue had drifted from the site page it was copied from.

    python scripts/docsguard.py

Checks, all of them cheap enough to run on every commit:

* every tool the bridge registers has a row on docs/tools.md AND docs/tools.ru.md;
* no page mentions a tool that no longer exists;
* every `EDT_BRIDGE_*` variable the code reads is documented on the install pages;
* the README's injected catalogue matches its source page (scripts/sync-docs.mjs was run);
* every image a page embeds exists in the repository;
* every group of the tools page is named in the short annotations - the site description, the
  front-page description of both languages, the PyPI summary - or is recorded in PITCH_GROUPS
  as one deliberately left out of them.

The exit code is what CI reads: 0 clean, 1 with findings printed one per line.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
JAVA_TOOLS = ROOT / "io.github.keyfire.edtbridge" / "src" / "io" / "github" / "keyfire" / "edtbridge" / "tools"
WRAPPER = ROOT / "python" / "src" / "edt_bridge_mcp"

#: A tool class answers its own name; that method is the registry.
_TOOL_NAME = re.compile(r'String name\(\)\s*\{\s*return\s+"(edt_[a-z_]+)"', re.S)
#: The wrapper serves a couple of tools itself, declared as plain dictionaries.
_LOCAL_TOOL = re.compile(r'"name":\s*"(edt_[a-z_]+)"')
#: A variable is "read by the code" where it is asked for, not where it is merely named.
_ENV_READ = re.compile(r'(?:System\.getenv\(|os\.environ\.get\()"(EDT_BRIDGE_[A-Z_]+)"')
_INLINE = re.compile(r"`(edt_[a-z_]+)`")
_ENV_INLINE = re.compile(r"`(EDT_BRIDGE_[A-Z_]+)`")
_IMAGE = re.compile(r"!\[[^\]]*\]\(([^)\s]+)\)")
_RAW_PREFIX = "https://raw.githubusercontent.com/keyfire/edt-bridge/main/"


def registered_tools() -> set[str]:
    names = set()
    for path in JAVA_TOOLS.glob("*.java"):
        names |= set(_TOOL_NAME.findall(path.read_text(encoding="utf-8")))
    for path in WRAPPER.glob("*.py"):
        names |= set(_LOCAL_TOOL.findall(path.read_text(encoding="utf-8")))
    return names


def env_variables() -> set[str]:
    names = set()
    for path in list(ROOT.rglob("*.java")) + list(WRAPPER.glob("*.py")):
        if "target" in path.parts:
            continue
        names |= set(_ENV_READ.findall(path.read_text(encoding="utf-8", errors="replace")))
    return names


def page(name: str) -> str:
    return (DOCS / name).read_text(encoding="utf-8")


def injected(document: str, marker: str) -> str | None:
    """The block scripts/sync-docs.mjs writes into a repository document."""
    text = (ROOT / document).read_text(encoding="utf-8")
    open_tag, close_tag = f"<!-- {marker}:start -->", f"<!-- {marker}:end -->"
    if open_tag not in text or close_tag not in text:
        return None
    return text.split(open_tag, 1)[1].split(close_tag, 1)[0].strip()


def page_body(name: str) -> str:
    text = page(name)
    text = re.sub(r"^---\n[\s\S]*?\n---\n", "", text)
    text = re.sub(r"<!--[\s\S]*?-->\n?", "", text).strip()
    # The injected copy carries the PNG of every diagram: a page shows the SVG and follows the
    # reader's theme, a README on GitHub cannot (scripts/sync-docs.mjs, readmeImages).
    return re.sub(r"(docs/[\w.-]+)\.svg\)", r"\1.png)", text)


#: A group of the tools page - the English heading, the Russian one - and the word that has to
#: stand for that group in the short annotations of its language. The page groups every tool the
#: bridge has; the one-liners around it are what a search engine, PyPI and an AI answer quote
#: instead, and they drift on their own (at xbsl a whole capability was absent from all of them
#: while the page had named it for two weeks). One word stands for a whole group on purpose - an
#: annotation names the kind of work, not the tools. A row with no words is a group deliberately
#: kept out of the annotations, with the reason beside it.
PITCH_GROUPS: tuple[tuple[str, str, str | None, str | None], ...] = (
    ("Read", "Чтение", "metadata", "метаданн"),
    ("Write", "Запись", "write", "запис"),
    ("Infobases, the cluster and the platform", "Информационные базы, кластер и платформа",
     "infobase", "информационн"),
    ("Debug", "Отладка", "debug", "отлад"),
    # The wrapper's own tools are plumbing - a version, a self-update, the state of the bridge.
    # The wrapper itself is named in the annotations; its tools are not a reason to install.
    ("Served by the wrapper", "Инструменты самой обвязки", None, None),
)


def tool_groups(name: str) -> list[str]:
    """The `### Group` headings of one tools page, in page order."""
    return [line[4:].strip() for line in page(name).splitlines() if line.startswith("### ")]


def front_description(name: str) -> str:
    found = re.search(r'^description:\s*"(.*)"\s*$', page(name), re.M)
    return found.group(1) if found else ""


def site_description() -> str:
    """The `description` of the site config - the meta description of every page."""
    text = (ROOT / "site" / "blume.config.ts").read_text(encoding="utf-8")
    found = re.search(r"\n  description:\s*((?:\s*\"[^\"]*\"\s*\+?)+),", text)
    return "".join(re.findall(r'"([^"]*)"', found.group(1))) if found else ""


def pyproject_description() -> str:
    """The `description` of the wrapper package - the summary line of the PyPI card."""
    text = (ROOT / "python" / "pyproject.toml").read_text(encoding="utf-8")
    found = re.search(r'^description = "(.*)"\s*$', text, re.M)
    return found.group(1) if found else ""


def pitch_surfaces() -> dict[str, dict[str, str]]:
    """The one-line annotations, by locale - what is quoted instead of the page being read.

    The README lede is not here: it enumerates the same capabilities in a paragraph of its own,
    right above the tool catalogue injected from the page.
    """
    return {
        "en": {
            "site/blume.config.ts": site_description(),
            "docs/index.md": front_description("index.md"),
            "python/pyproject.toml": pyproject_description(),
        },
        "ru": {"docs/index.ru.md": front_description("index.ru.md")},
    }


def pitch_problems() -> list[str]:
    """The gaps between the tools page, the table above and the annotations."""
    problems: list[str] = []
    groups = {"en": tool_groups("tools.md"), "ru": tool_groups("tools.ru.md")}
    for locale, column, name in (("en", 0, "tools.md"), ("ru", 1, "tools.ru.md")):
        listed, known = groups[locale], [group[column] for group in PITCH_GROUPS]
        for heading in listed:
            if heading not in known:
                problems.append(
                    f'{name}: the group "{heading}" is in no PITCH_GROUPS row - add the word '
                    f"that stands for it in the annotations, or the reason it stays out"
                )
        for heading in known:
            if heading not in listed:
                problems.append(f'{name}: PITCH_GROUPS names "{heading}", the page does not')

    surfaces = pitch_surfaces()
    for english, _russian, *words in PITCH_GROUPS:
        for locale, word in zip(("en", "ru"), words):
            if not word:
                continue
            for where, text in surfaces[locale].items():
                if word.lower() not in text.lower():
                    problems.append(
                        f'{where}: the short annotation says nothing about the "{english}" '
                        f'tools (expected "{word}")'
                    )
    return problems


def check() -> list[str]:
    problems: list[str] = []

    tools = registered_tools()
    if not tools:
        return ["no tool found in the sources - has the layout changed?"]
    for locale, name in (("en", "tools.md"), ("ru", "tools.ru.md")):
        listed = set(_INLINE.findall(page(name)))
        for missing in sorted(tools - listed):
            problems.append(f"{name}: {missing} has no row ({locale})")
        for phantom in sorted(listed - tools):
            problems.append(f"{name}: {phantom} is documented but not registered")

    variables = env_variables()
    for name in ("install.md", "install.ru.md"):
        documented = set(_ENV_INLINE.findall(page(name)))
        for missing in sorted(variables - documented):
            problems.append(f"{name}: {missing} is read by the code and documented nowhere")

    for document, source, marker in (
        ("README.md", "tools.md", "tools"),
        ("docs/ru/README.ru.md", "tools.ru.md", "tools"),
    ):
        block = injected(document, marker)
        if block is None:
            problems.append(f"{document}: the {marker} markers are gone")
        elif block != page_body(source):
            problems.append(f"{document}: the {marker} block is stale - run node scripts/sync-docs.mjs")

    for path in sorted(DOCS.glob("*.md")):
        for href in _IMAGE.findall(page(path.name)):
            if href.startswith(_RAW_PREFIX):
                target = ROOT / href[len(_RAW_PREFIX):]
            elif href.startswith(("http://", "https://")):
                continue
            else:
                target = (DOCS / href).resolve()
            if not target.exists():
                problems.append(f"{path.name}: the image {href} is not in the repository")
                continue
            # A page must show the SVG, which carries both palettes: the PNG next to it has one
            # baked in, and a reader in the light theme was served a dark picture for a while.
            if target.suffix == ".png" and target.with_suffix(".svg").exists():
                problems.append(
                    f"{path.name}: {target.name} follows no theme - the page needs "
                    f"{target.with_suffix('.svg').name}, the PNG belongs to the README"
                )

    problems += pitch_problems()

    return problems


def main() -> int:
    problems = check()
    if problems:
        print("\n".join(problems))
        print(f"\ndocsguard: {len(problems)} finding(s)")
        return 1
    print("docsguard: the documentation covers every tool, variable and image")
    return 0


if __name__ == "__main__":
    sys.exit(main())
