"""The command reference (docs/cli*.md) and its generator.

Defects that lived on the finished page at the same time and were only caught by
proof-reading: all Russian descriptions in English (no i18n and no language passed
into the generation), and a page that quietly goes stale after a flag is edited.
A command that does not parse --help starts the server instead and hangs, so every
help request here runs under a timeout.
"""

import argparse
import importlib.util
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

from edt_bridge_mcp import cli, i18n, server

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
SRC = ROOT / "python" / "src"

CYRILLIC_RE = re.compile(r"[А-Яа-яЁё]")

# self-update parses its flags by presence, not by argparse; its help is one catalog text
ARGPARSE_COMMANDS = tuple(cli.COMMANDS)
ALL_COMMANDS = ARGPARSE_COMMANDS + ("self-update",)


def parsers():
    """(command path, parser) for the server mode and every argparse command."""
    yield "edt-bridge-mcp", server.build_parser()
    for name in ARGPARSE_COMMANDS:
        yield f"edt-bridge-mcp {name}", cli.build_parser(name)


def actions_with_help(parser):
    for action in parser._actions:
        if action.help == argparse.SUPPRESS:
            continue
        yield action.dest, action.help


def test_every_argument_has_help():
    for path, parser in parsers():
        for dest, help_text in actions_with_help(parser):
            assert help_text and help_text.strip(), f"{path}: argument {dest} has no help"


def test_russian_help_is_russian(monkeypatch):
    # the wrapper had no i18n at all once: every description on the Russian page was
    # English, and nothing in the generator's own output showed it
    monkeypatch.setenv(i18n.ENV_LANG, "ru")
    for path, parser in parsers():
        for dest, help_text in actions_with_help(parser):
            assert CYRILLIC_RE.search(help_text or ""), (
                f"{path}: {dest} is not Russian in the ru run: {help_text!r}"
            )
    assert CYRILLIC_RE.search(i18n.t("update.usage")), "self-update usage is not Russian"


def test_language_versions_differ(monkeypatch):
    # the generator once never passed a language in, so both pages carried the same text
    monkeypatch.setenv(i18n.ENV_LANG, "en")
    english = {p: [h for _, h in actions_with_help(parser)] for p, parser in parsers()}
    english_update = i18n.t("update.usage")
    monkeypatch.setenv(i18n.ENV_LANG, "ru")
    russian = {p: [h for _, h in actions_with_help(parser)] for p, parser in parsers()}
    assert english.keys() == russian.keys()
    assert english != russian
    assert english_update != i18n.t("update.usage")


def test_parser_shapes_match_between_languages(monkeypatch):
    # a language switches texts, never the set of commands and arguments
    def shape():
        return {p: [d for d, _ in actions_with_help(parser)] for p, parser in parsers()}

    monkeypatch.setenv(i18n.ENV_LANG, "en")
    english = shape()
    monkeypatch.setenv(i18n.ENV_LANG, "ru")
    assert shape() == english


@pytest.fixture(scope="module")
def generator():
    spec = importlib.util.spec_from_file_location(
        "gen_cli_docs", ROOT / "scripts" / "gen-cli-docs.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_generator_covers_every_command(generator):
    # the generator keeps its own command list; a command added to the wrapper but not
    # here would silently miss its section on both pages
    assert tuple(generator.COMMANDS) == ALL_COMMANDS


def page_sections(fname: str) -> set[str]:
    text = (DOCS / fname).read_text(encoding="utf-8")
    return {m.group(1) for m in re.finditer(r"^## `(edt-bridge-mcp[^`]*)`", text, re.M)}


def test_pages_cover_every_command():
    expected = {f"edt-bridge-mcp {name}" for name in ALL_COMMANDS}
    for fname in ("cli.md", "cli.ru.md"):
        missing = expected - page_sections(fname)
        assert not missing, f"{fname}: sections missing for {sorted(missing)}"


def test_page_sections_match_between_languages():
    assert page_sections("cli.md") == page_sections("cli.ru.md")


@pytest.mark.parametrize("command", [(), *((name,) for name in ALL_COMMANDS)])
def test_help_answers_within_timeout(command):
    # a command that does not parse --help starts the MCP server and waits on stdin
    out = subprocess.run(
        [sys.executable, "-m", "edt_bridge_mcp.server", *command, "--help"],
        capture_output=True, text=True, encoding="utf-8", timeout=30,
        cwd=ROOT, env=dict(os.environ, PYTHONPATH=str(SRC)),
    )
    assert out.returncode == 0 and (out.stdout or "").strip()


def test_committed_pages_are_current(generator):
    # the generator is the source of truth; a mismatch means the committed page went
    # stale after a flag was edited
    for fname, text in generator.generate().items():
        committed = (DOCS / fname).read_text(encoding="utf-8")
        assert committed == text, f"{fname} is stale: rerun python scripts/gen-cli-docs.py"
