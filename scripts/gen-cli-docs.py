#!/usr/bin/env python
"""Generate the wrapper's command reference (docs/cli.md and docs/cli.ru.md) from the CLI.

The source of truth is the output of `edt-bridge-mcp ... --help`, so the reference cannot
drift from the implementation: add a flag, regenerate the page. Run it after changing the
set of commands or their options:

    python scripts/gen-cli-docs.py

The --help output is not pasted onto the page as is: the usage line goes into a code block
(highlighting belongs there) while the flag and command lists are parsed into tables.

The result is committed to the repository: the site build needs no Python. The wrapper picks
its help language from EDT_BRIDGE_LANG, so that variable is what each language version of
the page is generated with.
"""
from __future__ import annotations

import io
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "python" / "src"
COMMANDS = ["call", "tools", "status", "shutdown", "self-update"]

TEXT = {
    "ru": {
        "title": "Команды",
        "desc": "Справочник команд обвязки edt-bridge-mcp: вызов инструмента из шелла, список инструментов, состояние моста и самообновление.",
        "label": "Команды",
        "order": 5,
        "intro": (
            "Обвязка `edt-bridge-mcp` – это не только stdio-сервер для MCP-клиента. Тем же "
            "исполняемым файлом можно дёрнуть мост прямо из шелла: посмотреть, поднят ли он, "
            "какие инструменты отдаёт и что отвечает конкретный из них. Это удобнее, чем "
            "поднимать агента, когда нужно всего лишь проверить, живо ли всё.\n\n"
            "Без команды обвязка работает как MCP-сервер – так её запускает клиент. Команды "
            "ниже адресуют тот же мост.\n\n"
            "Код возврата разводит две разные беды: **1** – вызов не состоялся (мост не "
            "поднялся, аргументы неверны), **2** – инструмент отработал и вернул ошибку. "
            "В скриптах это разница между \"мост лежит\" и \"мост жив, но задача не выполнена\"."
        ),
        "common": "Без команды: запуск MCP-сервера",
        "col_opt": "Параметр",
        "col_desc": "Описание",
        "col_cmd": "Команда",
        "sections": {"options": "Параметры", "positional arguments": "Аргументы",
                     "commands": "Команды"},
        "note_args": (
            "Аргументы инструмента принимаются из `--json`, файла (`--json-file`) или потока "
            "ввода (`--stdin`). На Windows кириллица в аргументах надёжнее всего доезжает "
            "файлом: консольная кодировка в командной строке её портит."
        ),
    },
    "en": {
        "title": "Commands",
        "desc": "Reference of the edt-bridge-mcp wrapper commands: call a tool from a shell, list tools, report the bridge, self-update.",
        "label": "Commands",
        "order": 5,
        "intro": (
            "The `edt-bridge-mcp` wrapper is not only a stdio server for an MCP client. The "
            "same executable drives the bridge from a shell: whether it is up, which tools it "
            "serves, and what one of them answers. That beats starting an agent when all you "
            "want is to check that things are alive.\n\n"
            "With no command the wrapper runs as an MCP server – that is how a client launches "
            "it. The commands below address that same bridge.\n\n"
            "The exit code separates two different failures: **1** – the call never happened "
            "(no bridge, bad arguments), **2** – the tool ran and reported an error. In a "
            "script that is the difference between \"the bridge is down\" and \"the bridge is "
            "up, but the job failed\"."
        ),
        "common": "No command: running as an MCP server",
        "col_opt": "Option",
        "col_desc": "Description",
        "col_cmd": "Command",
        "sections": {"options": "Options", "positional arguments": "Arguments",
                     "commands": "Commands"},
        "note_args": (
            "Tool arguments come from `--json`, a file (`--json-file`) or standard input "
            "(`--stdin`). On Windows, non-ASCII arguments travel most reliably in a file: the "
            "console code page mangles them on the command line."
        ),
    },
}


SECTION_RE = re.compile(
    r"^(options|positional arguments|commands|параметры|аргументы|команды)\s*:\s*$", re.I)
ENTRY_RE = re.compile(r"^\s{2,4}(\S.*?)(?:\s{2,}(.*))?$")
CHOICES_RE = re.compile(r"^\{([\w,-]+)\}$")
SUBPARSERS_RE = re.compile(r"\{([\w,-]+)\}\s*\.\.\.")
FLAG_RE = re.compile(r"(?<![\w`-])(--?[a-zA-Z][\w-]*)")


def parse(help_text: str) -> dict:
    """Parse argparse output: the usage line, the description and the entry sections."""
    lines = help_text.split("\n")
    usage, i = [], 0
    while i < len(lines) and (not usage or lines[i].startswith(" ")) and lines[i].strip():
        usage.append(lines[i]); i += 1
    while i < len(lines) and not lines[i].strip():
        i += 1
    description = []
    while i < len(lines) and lines[i].strip() and not SECTION_RE.match(lines[i].strip()):
        description.append(lines[i].strip()); i += 1

    sections, current, entries, epilog = [], None, [], []
    while i < len(lines):
        line = lines[i]
        if SECTION_RE.match(line.strip()):
            if current:
                sections.append((current, entries))
            current, entries = line.strip().rstrip(":"), []
        elif not line.strip():
            # A blank line inside the epilog is a paragraph break; without it a line wrap
            # inside one paragraph would turn a single sentence into two.
            if epilog and epilog[-1]:
                epilog.append("")
        elif current:
            m = ENTRY_RE.match(line)
            if m and not line.startswith(" " * 6):
                # The indent tells a group metavar (2) from the nested commands themselves (4).
                indent = len(line) - len(line.lstrip(" "))
                entries.append([m.group(1).strip(), (m.group(2) or "").strip(), indent])
            elif not line.startswith(" "):
                # Text at zero indent is already the parser's epilog, not a wrapped description:
                # otherwise it gets glued onto the last row of the table.
                if epilog and epilog[-1]:
                    epilog[-1] += " " + line.strip()
                else:
                    epilog.append(line.strip())
            elif entries:                                  # wrapped description of the previous entry
                prev, tail = entries[-1][1], line.strip()
                # argparse wraps a long word on its hyphen (`--write-\nbaseline`); such a wrap
                # is joined without a space, otherwise a flag in the description is torn apart.
                glue = "" if prev.endswith("-") and tail[:1].isalnum() else " "
                entries[-1][1] = (prev + glue + tail).strip()
        i += 1
    if current:
        sections.append((current, entries))
    return {"usage": "\n".join(usage), "description": " ".join(description),
            "sections": sections, "epilog": epilog}


def esc(s: str) -> str:
    """An option name - it goes inside backticks, so only the pipe needs escaping."""
    return s.replace("|", "\\|")


def esc_text(s: str) -> str:
    """Ordinary text: Markdown reads angle brackets as a tag and swallows them along with
    what is inside (`edt-bridge-mcp <command>` becomes `edt-bridge-mcp`), and the theme's
    typography glues a double hyphen into a dash - a flag mentioned in a description,
    `--json-file`, turns into an unusable `–json-file`. Inside backticks neither happens."""
    s = s.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")
    return FLAG_RE.sub(r"`\1`", s)


def children(entries: list, i: int) -> list[int]:
    """Indices of the entries nested under entry i: a larger indent, until the group ends.

    argparse prints a group of nested commands on two levels: the metavar itself
    (`{a,b}`, or whatever `metavar` sets) with no description and a smaller indent, and
    the subcommands under it.
    """
    out = []
    for j in range(i + 1, len(entries)):
        if entries[j][2] <= entries[i][2]:
            break
        out.append(j)
    return out


def stubs(entries: list) -> set[int]:
    """Indices of the housekeeping rows: a group metavar and the description-less rows under it.

    The first is an argparse stub, the second is continued prose (under a group heading the
    command names may run on as a comma-separated list broken across lines). A table needs
    neither.
    """
    skip = set()
    for i, e in enumerate(entries):
        kids = children(entries, i) if not e[1] else []
        if not kids:
            continue
        skip.add(i)
        skip.update(j for j in kids if not entries[j][1])
    return skip


def render(help_text: str, t: dict) -> str:
    p = parse(help_text)
    out = io.StringIO()
    if p["description"]:
        out.write(esc_text(p["description"]) + "\n\n")
    out.write("```bash\n" + p["usage"] + "\n```\n\n")
    for title, entries in p["sections"]:
        if not entries:
            continue
        is_cmds = title.lower() in ("команды", "commands")
        head = t["col_cmd"] if is_cmds else t["col_opt"]
        name = t["sections"].get(title.lower(), title.capitalize())
        out.write(f"**{name}**\n\n")
        out.write(f"| {head} | {t['col_desc']} |\n|---|---|\n")
        skip = stubs(entries)
        for k, (opt, desc, _) in enumerate(entries):
            if k in skip:
                continue
            out.write(f"| `{esc(opt)}` | {esc_text(desc)} |\n")
        out.write("\n")
    for paragraph in p["epilog"]:
        if paragraph:
            out.write(esc_text(paragraph) + "\n\n")
    return out.getvalue()


def subcommands(help_text: str) -> list[str]:
    """Names of the nested commands: from a named group, or from nesting under a metavar."""
    p = parse(help_text)
    named = [e for title, entries in p["sections"] if title.lower() in ("команды", "commands")
             for e in entries]
    if named:
        return [e[0].split()[0] for e in named if re.match(r"^[a-z][\w-]*$", e[0].split()[0])]
    # A group without its own heading: the subcommands sit under the metavar at a larger
    # indent. The ellipsis in usage tells nested parsers from a positional with a choice list.
    if "..." not in p["usage"]:
        return []
    for _, entries in p["sections"]:
        for i, e in enumerate(entries):
            kids = [entries[j][0].split()[0] for j in children(entries, i)] if not e[1] else []
            named = [n for n in kids if re.match(r"^[a-z][\w-]*$", n)]
            if named:
                return named
    return []


def run(args: list[str], lang: str) -> str:
    env = dict(os.environ, PYTHONPATH=str(SRC), EDT_BRIDGE_LANG=lang, COLUMNS="88")
    # The timeout is mandatory: a command that does not parse --help starts the MCP server
    # and waits on stdin - without the limit the generation hangs instead of failing.
    try:
        out = subprocess.run(
            [sys.executable, "-m", "edt_bridge_mcp.server", *args, "--help"],
            capture_output=True, text=True, encoding="utf-8", env=env, cwd=ROOT,
            timeout=30,
        )
    except subprocess.TimeoutExpired:
        return ""          # no help - the command's section simply does not appear
    return (out.stdout or out.stderr).rstrip()


def page(lang: str) -> str:
    t = TEXT[lang]
    out = io.StringIO()
    out.write(
        f'---\ntitle: "{t["title"]}"\ndescription: "{t["desc"]}"\n'
        f'sidebar:\n  label: {t["label"]}\n  order: {t["order"]}\n---\n\n'
    )
    out.write("<!-- Собрано из вывода `edt-bridge-mcp --help` скриптом scripts/gen-cli-docs.py. "
              "Не редактировать вручную. -->\n\n")
    out.write(t["intro"] + "\n\n")
    out.write(f"## {t['common']}\n\n" + render(run([], lang), t))
    for cmd in COMMANDS:
        out.write(f"## `edt-bridge-mcp {cmd}`\n\n" + render(run([cmd], lang), t))
        if cmd == "call":
            out.write(t["note_args"] + "\n\n")
    return out.getvalue()


def generate() -> dict[str, str]:
    """File name -> page text; assembly without writing to disk (tests need this)."""
    return {fname: page(lang) for lang, fname in (("en", "cli.md"), ("ru", "cli.ru.md"))}


def main() -> None:
    for fname, text in generate().items():
        (ROOT / "docs" / fname).write_text(text, encoding="utf-8", newline="")
        print(f"{fname}: {len(text.splitlines())} строк")


if __name__ == "__main__":
    main()
