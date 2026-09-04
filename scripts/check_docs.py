#!/usr/bin/env python
"""Does the documentation still cover the bridge: tools, variables, images, annotations.

What is the bridge's own business stays here - which tools the Java side registers, which
variables the wrapper reads, how the tools page is grouped. Everything underneath (reading a
page, the block between the injection markers, the annotations a repository states about
itself, the runner) comes from the `docsguard` package, which three repositories were keeping
in triplicate until the copies drifted.

Run: `python scripts/docsguard.py`; the exit code is what CI reads.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from docsguard import (
    Layout,
    PitchItem,
    front_description,
    headings,
    image_problems,
    injection_problems,
    pitch_problems,
    pyproject_description,
    run,
    site_description,
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

_TOOL_NAME = re.compile(r'String name\(\)\s*\{\s*return\s+"(edt_[a-z_]+)"', re.S)
#: A tool served by the wrapper itself - it has no Java class, only an entry in the local list.
_LOCAL_TOOL = re.compile(r'"name":\s*"(edt_[a-z_]+)"')
_ENV_READ = re.compile(r'(?:System\.getenv\(|os\.environ\.get\()"(EDT_BRIDGE_[A-Z_]+)"')
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
    """Every EDT_BRIDGE_ variable the code reads - on either side."""
    found = set()
    for folder, pattern in ((JAVA_TOOLS.parent, "**/*.java"), (WRAPPER, "*.py")):
        for path in folder.glob(pattern):
            found.update(_ENV_READ.findall(path.read_text(encoding="utf-8")))
    return found


def check_tools() -> list[str]:
    """Every registered tool has a row on the tools page, and no page names a phantom."""
    tools = registered_tools()
    if not tools:
        return ["no tool found in the sources - has the layout changed?"]
    problems = []
    for locale, name in (("en", "tools.md"), ("ru", "tools.ru.md")):
        listed = set(_INLINE.findall(LAYOUT.page(name)))
        for missing in sorted(tools - listed):
            problems.append(f"{name}: {missing} has no row ({locale})")
        for phantom in sorted(listed - tools):
            problems.append(f"{name}: {phantom} is documented but not registered")
    return problems


def check_environment() -> list[str]:
    """A variable the code reads and the installation page does not describe."""
    variables = env_variables()
    problems = []
    for name in ("install.md", "install.ru.md"):
        documented = set(_ENV_INLINE.findall(LAYOUT.page(name)))
        for missing in sorted(variables - documented):
            problems.append(f"{name}: {missing} is read by the code and documented nowhere")
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


CHECKS = (check_tools, check_environment, check_injections, check_images, check_pitches)


def problems() -> list[str]:
    """Every finding of every check - what the test suite asserts on."""
    found: list[str] = []
    for check in CHECKS:
        found.extend(check())
    return found


if __name__ == "__main__":
    sys.exit(run(CHECKS, title="docsguard"))
