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
    "test-form.description": {
        "ru": "В изолированном Linux проверить форму EPF, ERF или объекта конфигурации в явно выбранной файловой тестовой базе.",
        "en": "Check an EPF, ERF or configuration object form in an explicitly selected file test infobase on isolated Linux.",
    },
    "test-form.platform-bin": {
        "ru": "каталог платформы с исполняемыми файлами 1cv8 и ibcmd",
        "en": "platform directory containing the 1cv8 and ibcmd executables",
    },
    "test-form.test-infobase": {
        "ru": "каталог выделенной файловой тестовой базы с файлом 1Cv8.1CD",
        "en": "dedicated file test infobase directory containing 1Cv8.1CD",
    },
    "test-form.scope": {
        "ru": "область проверки: пустая база (empty), контрольная конфигурация (fixture) или прикладное окружение (application)",
        "en": "declared test scope: empty infobase, fixture configuration, or application environment",
    },
    "test-form.epf": {
        "ru": "файл проверяемой внешней обработки",
        "en": "external data processor file to check",
    },
    "test-form.erf": {
        "ru": "файл проверяемого внешнего отчета",
        "en": "external report file to check",
    },
    "test-form.navigation-link": {
        "ru": "внутренняя ссылка e1cib/data на документ или элемент справочника в тестовой базе",
        "en": "internal e1cib/data link to a document or catalog item in the test infobase",
    },
    "test-form.scenario": {
        "ru": "файл JSON со сценарием ввода, команд и проверок наблюдаемого результата",
        "en": "JSON scenario containing inputs, commands and assertions of observed results",
    },
    "test-form.form": {
        "ru": "полное имя управляемой формы, например ExternalReport.Sales.Form для штатной формы СКД",
        "en": "fully qualified managed form name, for example ExternalReport.Sales.Form for a standard DCS form",
    },
    "test-form.form-title": {
        "ru": "заголовок окна формы в клиенте; * и ? – знаки подстановки. Окно формы объекта дописывает к "
              "заголовку представление объекта, поэтому передавать шаблон вида \"Заголовок*\". Без флага форма "
              "ищется по имени из --form",
        "en": "window title of the form in the client; * and ? are wildcards. The window of an object form "
              "adds the object presentation to the title, so pass a pattern such as \"Title*\". Without it the "
              "form is found by its --form name",
    },
    "test-form.button": {
        "ru": "точный заголовок кнопки, выполняющей команду",
        "en": "exact title of the button that invokes the command",
    },
    "test-form.expect-message": {
        "ru": "точное новое сообщение после команды для подтверждения результата",
        "en": "exact new user message required to confirm the command result",
    },
    "test-form.user": {
        "ru": "имя пользователя тестовой базы, если оно необходимо для входа без пароля",
        "en": "test infobase user name for a login that does not require a password",
    },
    "test-form.output-dir": {
        "ru": "новый каталог для отчета JSON и снимка PNG; недостающие родительские каталоги создаются",
        "en": "new directory for the JSON report and PNG screenshot; missing parent directories are created",
    },
    "test-form.timeout": {
        "ru": "предел времени проверки в секундах, по умолчанию 120",
        "en": "check time limit in seconds, default 120",
    },
    "unit-tests.description": {
        "ru": "В изолированном Linux загрузить собранные расширения в копию файловой тестовой базы и выполнить их тесты YAXUnit.",
        "en": "Load built extensions into a copy of a file test infobase on isolated Linux and run their YAXUnit tests.",
    },
    "unit-tests.platform-bin": {
        "ru": "каталог платформы с исполняемыми файлами 1cv8 и ibcmd",
        "en": "platform directory containing the 1cv8 and ibcmd executables",
    },
    "unit-tests.test-infobase": {
        "ru": "каталог файловой тестовой базы с расширением YAXUNIT; сама база не меняется, тесты идут в ее копии",
        "en": "directory of the file test infobase that has the YAXUNIT extension; the infobase is not changed, the tests run in a copy",
    },
    "unit-tests.extension": {
        "ru": "расширение для загрузки в копию: имя расширения в проекте и путь к .cfe; можно повторять",
        "en": "an extension to load into the copy: its name in the project and the path of its .cfe; repeatable",
    },
    "unit-tests.module": {
        "ru": "выполнить только тесты этого модуля; можно повторять",
        "en": "run only the tests of this module; repeatable",
    },
    "unit-tests.test": {
        "ru": "выполнить только этот тест: Модуль.Тест или Модуль.Тест.Контекст; можно повторять",
        "en": "run only this test: Module.Test or Module.Test.Context; repeatable",
    },
    "unit-tests.user": {
        "ru": "пользователь тестовой базы для ibcmd и клиента",
        "en": "test infobase user for ibcmd and the client",
    },
    "unit-tests.output-dir": {
        "ru": "новый каталог для отчета JSON, отчета jUnit и лога YAXUnit; недостающие родительские каталоги создаются",
        "en": "new directory for the JSON report, the jUnit report and the YAXUnit log; missing parent directories are created",
    },
    "unit-tests.timeout": {
        "ru": "предел времени запуска в секундах, по умолчанию 300",
        "en": "run time limit in seconds, default 300",
    },
    "server.usage": {
        "ru": "%(prog)s [опции]                   (без команды: запуск MCP-сервера)\n"
              "       %(prog)s <команда> [опции]",
        "en": "%(prog)s [options]           (no command: run as an MCP server)\n"
              "       %(prog)s <command> [options]",
    },
    "server.description": {
        "ru": "MCP-сервер stdio для плагина edt-bridge к 1С:EDT: передает запросы в\n"
              "запущенную EDT и запускает headless EDT при отсутствии работающей среды.\n\n"
              "Без команды обвязка использует JSON-RPC через stdin/stdout. Ее запускает\n"
              "MCP-клиент. Команды работы с мостом адресуют его из командной строки.",
        "en": "stdio MCP front-end for the edt-bridge 1C:EDT plugin: proxies to a running\n"
              "EDT, or auto-starts a headless one.\n\n"
              "With no command it speaks JSON-RPC over stdin/stdout - that is how an MCP\n"
              "client launches it. The bridge commands drive the same bridge from a shell.",
    },
    "server.epilog": {
        "ru": "команды:\n"
              "  call <инструмент>   вызвать один инструмент моста и напечатать ответ\n"
              "  tools               перечислить инструменты запущенного моста\n"
              "  status              состояние моста без его запуска\n"
              "  wait-ready          дождаться загрузки моделей проектов\n"
              "  shutdown            штатно завершить EDT, обслуживающую мост\n"
              "  gui                 остановить headless и открыть клиентскую EDT\n"
              "  plugins             перечислить плагины обвязки и их инструменты\n"
              "  workspace           упаковать, восстановить и сверить рабочую область EDT\n"
              "  test-form           проверить форму и результат сценария в тестовой базе\n"
              "  unit-tests          выполнить тесты YAXUnit расширений в копии тестовой базы\n"
              "  self-update         обновить jar плагина и саму обвязку\n"
              "\n"
              "Опции команды: edt-bridge-mcp <команда> --help.\n"
              "Опции подключения относятся к серверу MCP и командам работы с мостом.",
        "en": "commands:\n"
              "  call <tool>   call one bridge tool and print what it returned\n"
              "  tools         list the tools the running bridge serves\n"
              "  status        report the running bridge (never starts one)\n"
              "  wait-ready    wait until the project models are loaded\n"
              "  shutdown      shut down the EDT behind the bridge\n"
              "  gui           stop the headless EDT and open the GUI one\n"
              "  plugins       list the wrapper's plugins and their tools\n"
              "  workspace     pack, restore and compare an EDT workspace\n"
              "  test-form     check a form and scenario results in a test infobase\n"
              "  unit-tests    run the YAXUnit tests of extensions in a copy of a test infobase\n"
              "  self-update   refresh the plugin jar and this wrapper\n"
              "\n"
              "Run 'edt-bridge-mcp <command> --help' for a command's own options.\n"
              "Connection options apply to the MCP server mode and the bridge commands.",
    },
    # -- connection flags, shared by the server and the bridge commands (cli.py) --
    "conn.workspace": {
        "ru": "рабочая область EDT для автозапуска headless",
        "en": "EDT workspace path for the headless auto-start",
    },
    "conn.edt-dir": {
        "ru": "каталог установки EDT (папка с 1cedtcli и dropins); без флага определяется сам",
        "en": "EDT install dir (the folder with 1cedtcli and dropins); auto-detected when omitted",
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
    "conn.heap": {
        "ru": "наибольший размер кучи JVM для автозапуска headless EDT, например 4096m или 6g",
        "en": "maximum JVM heap of an auto-started headless EDT, e.g. 4096m or 6g",
    },
    "conn.vmargs": {
        "ru": "другие параметры JVM для автозапуска headless EDT через пробел; присоединять к флагу "
              "знаком =, потому что они начинаются с дефиса",
        "en": "further JVM options of an auto-started headless EDT, separated by spaces; join them "
              "to the flag with = because they start with a dash",
    },
    "wait-ready.description": {
        "ru": "Дождаться загрузки моделей проектов EDT (modelReady). При необходимости запускает "
              "headless EDT. Код 0 - проекты готовы, 1 - EDT не запущена или завершилась, "
              "2 - срок истек, а EDT еще загружается.",
        "en": "Wait until the EDT models of the projects are loaded (modelReady), starting a "
              "headless EDT if needed. Exit code 0 the projects are ready, 1 EDT is not running or "
              "has exited, 2 the time ran out while EDT is still loading.",
    },
    "workspace.description": {
        "ru": "Готовая рабочая область EDT: pack упаковывает рабочую область остановленной EDT в "
              "архив tar с временами изменения, restore восстанавливает ее по тому же пути, checks "
              "сохраняет замечания EDT по проектам в файл (--output) или сравнивает с ним (--compare). "
              "Журналы .metadata с командной строкой EDT в архив не попадают.",
        "en": "A ready EDT workspace: pack archives the workspace of a stopped EDT as a tar with its "
              "modification times, restore unpacks it under the same path, checks saves the EDT "
              "checks of the projects to a file (--output) or compares them with one (--compare). "
              "The .metadata logs, which carry the EDT command line, stay out of the archive.",
    },
    "workspace.action": {
        "ru": "действие: pack, restore или checks",
        "en": "action: pack, restore or checks",
    },
    "workspace.archive": {
        "ru": "архив рабочей области: .tar, .tar.gz или .tar.zst (Python 3.14 и новее)",
        "en": "workspace archive: .tar, .tar.gz or .tar.zst (Python 3.14 or newer)",
    },
    "workspace.output": {
        "ru": "checks: новый файл для сохранения замечаний",
        "en": "checks: a new file to save the checks to",
    },
    "workspace.compare": {
        "ru": "checks: файл сохраненных замечаний для сравнения; код 2, если замечания различаются",
        "en": "checks: a saved checks file to compare with; exit code 2 when they differ",
    },
    "workspace.project": {
        "ru": "checks: проект для проверки; можно повторять. Без флага проверяются все открытые проекты EDT",
        "en": "checks: a project to check; repeat for several. Without it every open EDT project counts",
    },
    "workspace.object": {
        "ru": "checks: объект метаданных, замечания которого сравниваются, например Document.Order; "
              "можно повторять. Без флага проверяется проект целиком, что на большой конфигурации "
              "упирается в предел числа замечаний",
        "en": "checks: a metadata object whose problems are compared, e.g. Document.Order; repeat for "
              "several. Without it the whole project is checked, which on a large configuration runs into "
              "the problem limit",
    },
    "workspace.wait": {
        "ru": "checks: сколько секунд ждать завершения проверки каждого проекта (по умолчанию 600)",
        "en": "checks: seconds to wait for the validation of each project (default 600)",
    },
    "wait-ready.project": {
        "ru": "проект, которого нужно дождаться; можно повторять. Без флага ожидаются все открытые "
              "проекты EDT",
        "en": "a project to wait for; repeat for several. Without it every open EDT project counts",
    },
    "wait-ready.timeout": {
        "ru": "сколько секунд ждать (по умолчанию 3600)",
        "en": "seconds to wait (default 3600)",
    },
    "wait-ready.interval": {
        "ru": "пауза между опросами в секундах (по умолчанию 5)",
        "en": "seconds between polls (default 5)",
    },
    # -- the call / tools / status commands (cli.py) --
    "call.description": {
        "ru": "Вызвать один инструмент моста и напечатать результат.",
        "en": "Call one bridge tool and print its result.",
    },
    "tools.description": {
        "ru": "Перечислить инструменты запущенного моста.",
        "en": "List the tools the running bridge serves.",
    },
    "status.all": {
        "ru": "проверить все порты диапазона без фильтра рабочей области и запуска EDT",
        "en": "inspect every port in the scan range without filtering workspaces or starting EDT",
    },
    "status.ports": {
        "ru": "порты для --all, через запятую или диапазон: 8770,8772-8774 (до 128 портов)",
        "en": "ports for --all, comma-separated or ranges: 8770,8772-8774 (up to 128 ports)",
    },
    "status.description": {
        "ru": "Состояние запущенного моста и версия JAR в dropins. Команда сообщает в stderr "
              "о расхождении версий обвязки и плагина. С --all она проверяет каждый найденный мост.",
        "en": "Report the running bridge and the version of the JAR in dropins. Version mismatches "
              "between the wrapper and the plugin are reported on stderr. With --all, check each "
              "bridge found.",
    },
    "shutdown.description": {
        "ru": "Штатно завершить EDT, обслуживающую мост, - вместо taskkill по процессам. "
              "Headless обвязка поднимет заново при следующем вызове; GUI без --force не трогается.",
        "en": "Shut down the EDT behind the bridge - instead of killing its processes. "
              "A headless one is relaunched on the next call; a GUI is left alone without --force.",
    },
    "shutdown.force": {
        "ru": "разрешить завершение GUI EDT; без этого флага завершение GUI запрещено",
        "en": "shut a GUI EDT down too: without this flag the bridge refuses rather than close "
              "somebody's window",
    },
    "shutdown.no-wait": {
        "ru": "не ждать остановки (по умолчанию ожидание закрытия порта составляет до 60 секунд)",
        "en": "do not wait for the bridge to go down (the default waits up to 60 seconds)",
    },
    "gui.description": {
        "ru": "Передать рабочую область клиентской EDT: остановить headless, дождаться, пока его "
              "процессы действительно исчезнут, и запустить окно EDT на той же рабочей области. "
              "Обвязка не запускает headless EDT при выполнении команды.",
        "en": "Hand the workspace over to the GUI EDT: stop the headless one, wait until its "
              "processes are really gone, and open the EDT window on the same workspace. "
              "Never starts a headless EDT on the way.",
    },
    "gui.force": {
        "ru": "завершить headless принудительно, если он не остановился за отведенное время",
        "en": "kill the headless session when it does not stop within the timeout",
    },
    "gui.timeout": {
        "ru": "сколько секунд ждать завершения headless (по умолчанию 90)",
        "en": "seconds to wait for the headless EDT to stop (default 90)",
    },
    "plugins.description": {
        "ru": "Перечислить плагины обвязки: пакеты, точки расширения и добавленные ими "
              "инструменты. Плагины установлены в окружении обвязки (pipx inject), мост "
              "для этой команды не нужен.",
        "en": "List the wrapper's plugins: packages, entry points and the tools they add. "
              "Plugins are installed in the wrapper's environment (pipx inject); no bridge is "
              "needed for this command.",
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
        "ru": "читать объект JSON из файла в UTF-8; этот способ подходит для аргументов "
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
    "update.description": {
        "ru": "Обновить jar плагина в dropins EDT, обвязку в ее окружении и плагины обвязки.",
        "en": "Update the plugin jar in EDT's dropins, the wrapper and its plugins.",
    },
    "update.jar-only": {"ru": "обновить только jar плагина", "en": "update only the plugin jar"},
    "update.pip-only": {"ru": "обновить только обвязку", "en": "update only the wrapper"},
    "update.plugins-only": {
        "ru": "обновить только плагины обвязки",
        "en": "update only the wrapper plugins",
    },
    "update.from": {
        "ru": "установить обвязку из рабочей копии",
        "en": "install the wrapper from a checkout",
    },
    "update.version": {
        "ru": "установить указанную версию jar и обвязки или одного из них по выбранному режиму",
        "en": "install this release of the jar and wrapper, or just the selected component",
    },
    "update.empty-source": {"ru": "--from требует путь", "en": "--from requires a path"},
    "update.source-mode": {
        "ru": "--from несовместим с --jar-only и --plugins-only",
        "en": "--from cannot be combined with --jar-only or --plugins-only",
    },
    "update.invalid-version": {
        "ru": "для --version укажите номер релиза в формате X.Y.Z",
        "en": "--version requires a release number in X.Y.Z format",
    },
    "update.version-mode": {
        "ru": "--version несовместим с --plugins-only",
        "en": "--version cannot be combined with --plugins-only",
    },
    "update.epilog": {
        "ru": """С параметром --version команда берет jar по тегу vX.Y.Z на GitHub и обвязку с PyPI.
Параметры --version и --from нельзя сочетать с --plugins-only.
Параметр --from также несовместим с --jar-only.

После обновления перезапустите EDT для загрузки jar и клиент MCP для загрузки обвязки.

Плагины обвязки обновляются через pip из своих источников установки. Для реестра
пакетов можно указать адрес в переменной EDT_BRIDGE_PLUGIN_INDEX.

Код 0 означает, что все выбранные операции выполнены. Код 1 означает, что
обновление jar или обвязки не удалось либо не удалась ни одна операция.
Код 2 означает ошибку аргументов или сбой только при обновлении плагинов обвязки.""",
        "en": """With --version, the command gets the jar from the vX.Y.Z tag on GitHub and the wrapper from PyPI.
Both --version and --from require a mode other than --plugins-only.
The --from option also requires a mode other than --jar-only.

Restart EDT to load the updated jar and restart the MCP client to load the wrapper.

Wrapper plugins are updated through pip from their installation sources. Use
EDT_BRIDGE_PLUGIN_INDEX to set the package index URL.

Exit code 0 means all selected steps succeeded. Code 1 means the jar or wrapper
update failed, or none of the selected steps succeeded. Code 2 means the arguments
are invalid or only the wrapper plugin step failed.""",
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
