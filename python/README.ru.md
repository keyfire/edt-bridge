# edt-bridge-mcp

[English](https://github.com/keyfire/edt-bridge/blob/main/python/README.md) · **Русский**

stdio MCP-обвязка для плагина [edt-bridge](https://github.com/keyfire/edt-bridge) к 1C:EDT.

Java-плагин отдаёт MCP как простой JSON-RPC по HTTP на `127.0.0.1:8770` – а значит,
MCP-клиент, настроенный на этот URL, теряет сервер всякий раз, когда EDT не запущена. Вместо
URL клиент разговаривает с этой обвязкой:

- **EDT открыта** (GUI или headless) → каждый запрос пробрасывается живому мосту;
- **EDT закрыта** → обвязка **сама поднимает headless EDT** (`1cedtcli` с поддерживающим
  конвейером – тот же рецепт, что в `run-headless.ps1`) и пробрасывает запросы, как только
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

## Самообновление

```bash
edt-bridge-mcp self-update             # обновить jar плагина (GitHub Releases) + обвязку (PyPI)
edt-bridge-mcp self-update --jar-only  # только jar
edt-bridge-mcp self-update --pip-only  # только обвязку
```

Запущенная EDT (GUI или headless) держит старый jar до перезапуска; свой headless-экземпляр
обвязка перезапустит при следующем автостарте.

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
