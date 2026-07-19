# edt-bridge-mcp

[English](https://github.com/keyfire/edt-bridge/blob/main/python/README.md) · **Русский**

stdio MCP-обвязка для плагина [edt-bridge](https://github.com/keyfire/edt-bridge) к 1C:EDT.

Java-плагин отдаёт MCP как простой JSON-RPC по HTTP на `127.0.0.1:8770` – а значит,
MCP-клиент, настроенный на этот URL, теряет сервер всякий раз, когда EDT не запущена. Вместо
URL клиент разговаривает с этой обвязкой:

- **EDT открыта** (GUI или headless) → каждый запрос пробрасывается живому мосту;
- **EDT закрыта** → обвязка **сама поднимает headless EDT** (`1cedtcli` с поддерживающим
  конвейером – тот же рецепт, что в `scripts/run-headless.ps1`) и пробрасывает запросы, как только
  модель готова;
- **нет jar плагина** → обвязка **сама доставляет jar** из последнего релиза GitHub
  (с проверкой контрольной суммы) в `dropins/` EDT перед запуском – достаточно одного
  `pipx install edt-bridge-mcp`, чтобы мост заработал;
- сессия клиента не виснет на старте: пока бэкенд поднимается, `tools/list` возвращает
  пустой список, а после готовности приходит `notifications/tools/list_changed`.

![Как устроен мост](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/architecture.ru.png)

## Установка

```bash
pipx install edt-bridge-mcp        # или из чекаута: pipx install ./python
```

## Регистрация в MCP-клиенте

```bash
claude mcp add edt-bridge -- edt-bridge-mcp --workspace "D:\\путь\\к\\workspace-EDT"
```

## Из командной строки

Режим по умолчанию говорит по JSON-RPC через stdin/stdout – его ведёт MCP-клиент. Чтобы достучаться
до того же моста руками или из скрипта, есть подкоманды: тот же перебор портов, тот же токен, тот же
автозапуск headless.

```bash
edt-bridge-mcp tools                    # что отдаёт запущенный мост
edt-bridge-mcp call edt_projects        # вызвать инструмент и напечатать результат
edt-bridge-mcp call edt_metadata_details --json '{"projectName": "SM", "fqn": "CommonModule.Foo"}'
edt-bridge-mcp call edt_create_extension --json-file args.json   # аргументы из файла UTF-8
edt-bridge-mcp status                   # поднят ли мост (сам ничего не запускает)
```

`--raw` печатает JSON-результат вместо текста, который вернул инструмент. Аргументы берутся из
`--json`, `--json-file` или `--stdin`; для аргументов с не-ASCII текстом на Windows надёжнее файл.

Коды возврата: `0` – порядок, `1` – вызов не состоялся (моста нет, ошибка использования или
транспорта), `2` – мост выполнил инструмент, и тот сообщил об ошибке. Так скрипт отличает "упало"
от "не запускалось".

## Самообновление

```bash
edt-bridge-mcp self-update             # обновить jar плагина (GitHub Releases) + обвязку (PyPI)
edt-bridge-mcp self-update --jar-only  # только jar
edt-bridge-mcp self-update --pip-only  # только обвязку
edt-bridge-mcp self-update --pip-only --from <repo>/python   # из рабочей копии
```

Запущенная EDT (GUI или headless) держит старый jar до перезапуска; свой headless-экземпляр
обвязка перезапустит при следующем автостарте.

`--from` ставит обвязку из локальной рабочей копии, а не с PyPI – чтобы попробовать невыпущенную
сборку, не прибегая к `pipx install --force` (тот пересоздаёт venv и заменяет exe, который держит
запущенный клиент).

Обвязка ставится в своё окружение и никогда не через `pipx upgrade`, поэтому exe остаётся на месте.
Маршрута три, по порядку, потому что pip есть не всегда: **pip** (обычные venv), затем **uv** (venv,
созданный pipx 1.15, идёт через uv и pip не содержит вовсе – `python -m pip` там отвечает "No module
named pip"), затем **ensurepip** и следом pip. Запуск `self-update` из установленного
`edt-bridge-mcp.exe` исключает uv: он удаляет консольный скрипт перед перезаписью, а Windows не даёт
удалить запущенный exe – поэтому такой случай доходит до ensurepip, который один раз добавляет pip в
venv; дальше обновления идут первым маршрутом. Про каждый пропущенный маршрут пишется, почему.

## Настройка

Флаги CLI имеют приоритет над переменными окружения.

| Переменная | Флаг | Смысл |
|-----|------|---------|
| `EDT_BRIDGE_PORT` | `--port` | порт моста (по умолчанию 8770) |
| `EDT_BRIDGE_TOKEN` | – | токен write-инструментов; уходит как `Authorization: Bearer` и передаётся в JVM headless-экземпляра |
| `EDT_BRIDGE_WORKSPACE` | `--workspace` | путь к workspace EDT – обязателен для автозапуска headless |
| `EDT_BRIDGE_EDT_DIR` | `--edt-dir` | каталог установки EDT (`.../1cedt`); без него берётся самая свежая установка |
| `EDT_BRIDGE_START_TIMEOUT` | `--start-timeout` | сколько секунд ждать поднимающийся бэкенд (по умолчанию 360) |
| `EDT_BRIDGE_AUTOSTART` | `--no-autostart` | `0`/флаг – режим только прокси, ничего не запускать |

## Безопасность

- Если **GUI EDT запущена, а порт моста мёртв** (там нет плагина), обвязка отказывается
  поднимать headless – GUI держит блокировку workspace. Недостающий jar она при этом в
  `dropins/` доставит: перезапуск этой EDT активирует мост.
- Если headless `1cedtcli` уже стартует, обвязка ждёт его, а не порождает второй.
