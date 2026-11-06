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
COMMANDS = ["call", "tools", "status", "shutdown", "gui", "plugins", "test-form", "self-update"]

TEXT = {
    "ru": {
        "without_yo": True,
        "title": "Команды",
        "desc": "Справочник команд обвязки edt-bridge-mcp: вызов инструмента из командной строки, список инструментов, состояние моста и самообновление.",
        "label": "Команды",
        "order": 5,
        "intro": (
            "Обвязка `edt-bridge-mcp` работает как stdio MCP-сервер для клиента. Из командной "
            "строки можно проверить состояние моста, получить список инструментов и вызвать "
            "инструмент без запуска агента.\n\n"
            "Без команды обвязка работает как MCP-сервер. Команды работы с мостом используют тот же "
            "экземпляр EDT. `test-form` запускает отдельные сеансы платформы.\n\n"
            "Для вызовов инструментов код возврата **1** означает, что мост не запустился или "
            "аргументы неверны. Код **2** означает, что инструмент выполнился и вернул ошибку."
        ),
        "common": "Без команды: запуск MCP-сервера",
        "col_opt": "Параметр",
        "col_desc": "Описание",
        "col_cmd": "Команда",
        "sections": {"options": "Параметры", "positional arguments": "Аргументы",
                     "commands": "Команды"},
        "note_args": (
            "Аргументы инструмента принимаются из `--json`, файла (`--json-file`) или потока "
            "ввода (`--stdin`). На Windows передавать кириллицу в файле: консольная кодировка "
            "может исказить аргументы командной строки."
        ),
        "note_native": (
            "Запускайте проверку в изолированном Linux-окружении с `1cv8`, `ibcmd`, "
            "`Xvfb` и `libX11`. Выберите файловую тестовую базу и основную управляемую форму, "
            "указанную в EPF. Укажите полный заголовок из клиента, включая имя обработки, "
            "если оно отображается.\n\n"
            "Перед первым запуском подтвердите открытие именно этого EPF в выбранной тестовой "
            "базе под тем же пользователем. Платформа сохраняет разрешение для конкретного "
            "файла. Если предупреждение появилось во время проверки, оно останется на снимке.\n\n"
            "Команда сохраняет `result.json` и, если доступен экран клиента, `screenshot.png`. "
            "Для успешной проверки укажите `--expect-message`. Заданный текст должен появиться "
            "после нажатия кнопки. Без этого параметра команда сохраняет наблюдение "
            "`command_observed` и возвращает код **2**. Предел времени включает подготовку и "
            "взаимодействие с клиентом. Для завершения процессов предусмотрен отдельный короткий срок."
        ),
    },
    "en": {
        "title": "Commands",
        "desc": "Reference of the edt-bridge-mcp wrapper commands: call a tool from a shell, list tools, report the bridge, self-update.",
        "label": "Commands",
        "order": 5,
        "intro": (
            "The `edt-bridge-mcp` wrapper runs as a stdio MCP server for a client. From the "
            "command line, you can check bridge status, list tools, and call a tool without "
            "starting an agent.\n\n"
            "With no command, the wrapper runs as an MCP server. Bridge commands use the "
            "same EDT instance. `test-form` starts separate platform sessions.\n\n"
            "For tool calls, exit code **1** means the bridge did not start or "
            "the arguments were invalid. Exit code **2** means the tool ran and returned an error."
        ),
        "common": "No command: running as an MCP server",
        "col_opt": "Option",
        "col_desc": "Description",
        "col_cmd": "Command",
        "sections": {"options": "Options", "positional arguments": "Arguments",
                     "commands": "Commands"},
        "note_args": (
            "Tool arguments come from `--json`, a file (`--json-file`) or standard input "
            "(`--stdin`). On Windows, pass non-ASCII arguments in a file because the console code "
            "page can corrupt them on the command line."
        ),
        "note_native": (
            "Run the check in an isolated Linux environment with `1cv8`, `ibcmd`, "
            "`Xvfb`, and `libX11`. Select a file test infobase and the main managed form "
            "declared in the EPF. Use the full title shown in the client, including the processing "
            "name if it appears.\n\n"
            "Before the first run, authorize opening the exact EPF in the selected test infobase "
            "under the same test user. The platform retains authorization for that file. "
            "If the security prompt appears during the check, the screenshot captures it.\n\n"
            "The command writes `result.json` and, when the client display is available, "
            "`screenshot.png`. To pass the check, set `--expect-message`. The specified text must "
            "appear after the click. A run without that option records `command_observed` and "
            "returns exit code **2**. The time limit covers setup and client interaction. Process "
            "cleanup has a separate short deadline."
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


def esc_text(s: str, *, without_yo: bool = False) -> str:
    """Ordinary text: Markdown reads angle brackets as a tag and swallows them along with
    what is inside (`edt-bridge-mcp <command>` becomes `edt-bridge-mcp`), and the theme's
    typography glues a double hyphen into a dash - a flag mentioned in a description,
    `--json-file`, turns into an unusable `-json-file`. Inside backticks neither happens."""
    s = s.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")
    s = FLAG_RE.sub(r"`\1`", s)
    if not without_yo:
        return s
    # The Russian reference uses е in prose; keep code, URLs and quoted values intact.
    pieces = re.split(r"(`[^`]*`|https?://[^\s]+|\"[^\"]*\"|'[^']*')", s)
    return "".join(part if i % 2 else part.replace("ё", "е").replace("Ё", "Е")
                   for i, part in enumerate(pieces))


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
        out.write(esc_text(p["description"], without_yo=t.get("without_yo", False)) + "\n\n")
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
            out.write(f"| `{esc(opt)}` | {esc_text(desc, without_yo=t.get('without_yo', False))} |\n")
        out.write("\n")
    for paragraph in p["epilog"]:
        if paragraph:
            out.write(esc_text(paragraph, without_yo=t.get("without_yo", False)) + "\n\n")
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
        if cmd == "test-form":
            out.write(t["note_native"] + "\n\n")
    return out.getvalue().rstrip() + "\n"


def generate() -> dict[str, str]:
    """File name -> page text; assembly without writing to disk (tests need this)."""
    return {fname: page(lang) for lang, fname in (("en", "cli.md"), ("ru", "cli.ru.md"))}


def main() -> None:
    for fname, text in generate().items():
        (ROOT / "docs" / fname).write_text(text, encoding="utf-8", newline="")
        print(f"{fname}: {len(text.splitlines())} строк")


if __name__ == "__main__":
    main()
