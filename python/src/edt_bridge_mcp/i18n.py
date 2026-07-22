"""Language of the wrapper's --help text: the catalog and the lookup.

The wrapper is small, so every help string lives right here in MESSAGES rather than in
per-module catalogs. A key is `<command>.<flag>`; the values carry every language of LANGS.

The language is chosen by: env EDT_BRIDGE_LANG > system locale > ru. There is no --lang
flag: argparse would learn it only when it parses, which is too late to pick the help
language, and the wrapper's own flag set is deliberately minimal.

What the tools themselves say to an agent stays English - that is the plugin's protocol
surface, not text a person reads. Localized here is only what a person sees in a shell.
"""

from __future__ import annotations

import argparse as _argparse
import locale as _locale
import os

LANGS = ("ru", "en")
DEFAULT_LANG = "ru"
ENV_LANG = "EDT_BRIDGE_LANG"

MESSAGES = {
    "group.positional": {
        "ru": "аргументы",
        "en": "positional arguments",
    },
    "group.options": {
        "ru": "параметры",
        "en": "options",
    },
    "help": {
        "ru": "показать эту справку и выйти",
        "en": "show this help message and exit",
    },
    "version": {
        "ru": "показать версию и выйти",
        "en": "show the version and exit",
    },
    # -- the no-command server (server.py) --
    "server.usage": {
        "ru": "%(prog)s [опции]                   (без команды: запуск MCP-сервера)\n"
              "       %(prog)s <команда> [опции]",
        "en": "%(prog)s [options]           (no command: run as an MCP server)\n"
              "       %(prog)s <command> [options]",
    },
    "server.description": {
        "ru": "stdio-обвязка MCP для плагина edt-bridge к 1С:EDT: проксирует запросы в\n"
              "запущенный EDT, а если ни одного нет – поднимает headless.\n\n"
              "Без команды обвязка говорит JSON-RPC через stdin/stdout – так её запускает\n"
              "MCP-клиент. Команды ниже адресуют тот же мост из шелла.",
        "en": "stdio MCP front-end for the edt-bridge 1C:EDT plugin: proxies to a running\n"
              "EDT, or auto-starts a headless one.\n\n"
              "With no command it speaks JSON-RPC over stdin/stdout - that is how an MCP\n"
              "client launches it. The commands below drive the same bridge from a shell.",
    },
    "server.epilog": {
        "ru": "команды:\n"
              "  call <инструмент>   вызвать один инструмент моста и напечатать ответ\n"
              "  tools               перечислить инструменты, которые отдаёт мост\n"
              "  status              состояние запущенного моста (сам его не поднимает)\n"
              "  self-update         обновить jar плагина и саму обвязку\n"
              "\n"
              "Опции команды: edt-bridge-mcp <команда> --help.\n"
              "Опции выше относятся и к режиму MCP-сервера, и к каждой команде.",
        "en": "commands:\n"
              "  call <tool>   call one bridge tool and print what it returned\n"
              "  tools         list the tools the running bridge serves\n"
              "  status        report the running bridge (never starts one)\n"
              "  self-update   refresh the plugin jar and this wrapper\n"
              "\n"
              "Run 'edt-bridge-mcp <command> --help' for a command's own options.\n"
              "The options above apply to the MCP-server mode and to every command.",
    },
    # -- connection flags, shared by the server and every command (cli.py) --
    "conn.workspace": {
        "ru": "воркспейс EDT для автозапуска headless",
        "en": "EDT workspace path for the headless auto-start",
    },
    "conn.edt-dir": {
        "ru": "каталог установки EDT (.../1cedt); без флага определяется сам",
        "en": "EDT install dir (.../1cedt); auto-detected when omitted",
    },
    "conn.port": {
        "ru": "порт моста (по умолчанию 8770)",
        "en": "bridge port (default 8770)",
    },
    "conn.start-timeout": {
        "ru": "сколько секунд ждать поднимающийся мост",
        "en": "seconds to wait for a starting backend",
    },
    "conn.no-autostart": {
        "ru": "не поднимать headless EDT ни при каких условиях",
        "en": "never launch a headless EDT",
    },
    # -- the call / tools / status commands (cli.py) --
    "call.description": {
        "ru": "Вызвать один инструмент моста и напечатать результат.",
        "en": "Call one bridge tool and print its result.",
    },
    "tools.description": {
        "ru": "Перечислить инструменты, которые отдаёт запущенный мост.",
        "en": "List the tools the running bridge serves.",
    },
    "status.description": {
        "ru": "Состояние запущенного моста (сам его не поднимает).",
        "en": "Report the running bridge (does not start one).",
    },
    "call.tool": {
        "ru": "имя инструмента, например edt_projects (перечень: edt-bridge-mcp tools)",
        "en": "tool name, e.g. edt_projects (see: edt-bridge-mcp tools)",
    },
    "call.json": {
        "ru": "аргументы инструмента объектом JSON",
        "en": "tool arguments as a JSON object",
    },
    "call.json-file": {
        "ru": "читать объект JSON из файла в UTF-8 – надёжный путь для аргументов "
              "с неанглийским текстом на Windows",
        "en": "read the JSON object from a UTF-8 file - the reliable route for "
              "arguments with non-ASCII text on Windows",
    },
    "call.stdin": {
        "ru": "читать объект JSON из потока ввода",
        "en": "read the JSON object from standard input",
    },
    "call.raw": {
        "ru": "напечатать сырой JSON-результат вместо текста, который вернул инструмент",
        "en": "print the raw JSON result instead of the text the tool returned",
    },
    # -- self-update (update.py) --
    "update.usage": {
        "ru": """usage: edt-bridge-mcp self-update [опции]

Обновить jar плагина в dropins у EDT и саму обвязку в её окружении.

параметры:
  --jar-only        только jar плагина (GitHub Releases)
  --pip-only        только обвязку (PyPI или --from)
  --from <путь>     ставить обвязку из рабочей копии, а не с PyPI
  -h, --help        показать эту справку

Jar применится при следующем запуске EDT. Обвязка заменяется прямо в site-packages;
exe в Scripts не трогаются, поэтому запущенные edt-bridge-mcp надо перезапустить.""",
        "en": """usage: edt-bridge-mcp self-update [options]

Refresh the plugin jar in EDT's dropins and this wrapper in its own environment.

options:
  --jar-only        only the plugin jar (GitHub Releases)
  --pip-only        only the wrapper (PyPI, or --from)
  --from <path>     install the wrapper from a checkout instead of PyPI
  -h, --help        show this message

The jar applies on EDT's next restart. The wrapper is replaced inside site-packages;
the exes in Scripts are left alone, so restart any running edt-bridge-mcp afterwards.""",
    },
}


def _system_lang() -> str | None:
    code = ""
    try:
        code = _locale.getlocale()[0] or ""
    except (ValueError, TypeError):
        pass
    code = (code or os.environ.get("LC_ALL") or os.environ.get("LANG") or "").lower()
    # "ru_RU.UTF-8" and Windows' "Russian_Russia" both start with the language code.
    for lang in LANGS:
        if code.startswith(lang):
            return lang
    return None


def current_lang() -> str:
    env = os.environ.get(ENV_LANG, "").strip().lower()
    if env in LANGS:
        return env
    return _system_lang() or DEFAULT_LANG


def t(key: str) -> str:
    """The message for the key in the current language. An unknown key is returned as is."""
    entry = MESSAGES.get(key)
    if entry is None:
        return key
    return entry.get(current_lang()) or entry[DEFAULT_LANG]


class ArgumentParser(_argparse.ArgumentParser):
    """An ArgumentParser whose own `-h/--help` and group titles are translated.

    argparse takes those strings from its gettext catalog, i.e. always in English: in a
    Russian help screen the `-h, --help` line and the group headings stayed in the wrong
    language.
    """

    def __init__(self, *args, add_help: bool = True, **kwargs) -> None:
        super().__init__(*args, add_help=False, **kwargs)
        self._positionals.title = t("group.positional")
        self._optionals.title = t("group.options")
        if add_help:
            self.add_argument("-h", "--help", action="help", help=t("help"))
