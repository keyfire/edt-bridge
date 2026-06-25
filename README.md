# edt-bridge

A small **1C:EDT plugin** that exposes EDT's **live semantic model** to AI agents and other
tools over the **Model Context Protocol (MCP)** — on `http://127.0.0.1:8770/mcp`.

Static parsers read source files; edt-bridge instead asks the running IDE. It answers things
that need the *live* model: EDT's own validation problems, real metadata structure and types,
semantic cross-references, and **query validation against the project's actual metadata**.

> Read-only. Localhost only. The plugin runs inside EDT, so EDT must be open with the project.

**English** · [Русский](#русский)

## Tools

| Tool | What it returns |
|------|-----------------|
| `edt_project_errors` | EDT validation problems (errors/warnings) for a project: message, severity, resource, line. |
| `edt_metadata_details` | A metadata object's core properties **and structure** — attributes, tabular sections, forms, commands, templates, dimensions, resources, enum values — with each attribute's value type. |
| `edt_metadata_objects` | Top-level metadata objects, optionally filtered by type (`Catalog`, `Document`, …) and a name substring. |
| `edt_find_references` | Inbound references to a metadata object (metadata + BSL), from EDT's cross-reference index. |
| `edt_validate_query` | Validates a 1C query against the project's live metadata: syntax **and** semantics (unknown tables/fields, type errors), with positions. |

Planned: `edt_go_to_definition`, `edt_symbol_info` (semantic BSL navigation / typing).

Naming convention: tools are `edt_*` (snake_case); parameters are camelCase (`projectName`,
`fqn`, `queryText`); Cyrillic FQNs are supported (`Справочник.Контрагенты`).

## Requirements

- **1C:EDT** installed and running, with your project open in the workspace.
- A **JDK 17+** to build (the bundle's bytecode targets Java 17, the EDT runtime).
- The local **EDT p2 bundle pool** (used as the offline build target): `<your-home>/.p2/pool/plugins`.

## Build

**Option A — no Maven** (quickest; pure local JDK + the EDT pool, no network):

```powershell
# defaults: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File build-nomaven.ps1
# or override:
powershell -File build-nomaven.ps1 -Pool "D:\path\.p2\pool\plugins" -JdkHome "C:\path\to\jdk"
```

Produces `build/com.e1c.fresh.edtbridge_0.0.1.<timestamp>.jar`.

**Option B — Maven + Tycho** (standard, for CI). Edit `edt-bridge.target` to point at your pool, then:

```
mvn -f pom.xml clean verify
```

## Install & run

1. Copy the built jar into EDT's `dropins/` folder
   (e.g. `…/installations/<EDT>/1cedt/dropins/`).
2. **Restart EDT.** On launch the plugin starts the MCP server on `http://127.0.0.1:8770/mcp`.

Smoke-test with curl:

```bash
# list tools
curl -s -X POST http://127.0.0.1:8770/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# call a tool
curl -s -X POST http://127.0.0.1:8770/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"edt_project_errors","arguments":{"projectName":"YourProject"}}}'
```

## MCP client wiring

Add an HTTP MCP server entry (e.g. in your client's `.mcp.json`):

```json
{
  "mcpServers": {
    "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" }
  }
}
```

The minimal server speaks plain JSON-RPC over HTTP (`initialize` / `tools/list` / `tools/call`) —
no SSE or extra SDK required.

## Dashboard

Open `http://127.0.0.1:8770/` in a browser for a built-in dashboard: server status, the open
EDT projects, and an interactive runner for every tool. Light/dark theme and an EN/RU language
toggle (defaults to the browser locale).

## Security

- Binds **`127.0.0.1` only** — never a public interface.
- **Read-only**: no write/refactor/exec tools; `edt_validate_query` only parses and validates.
- Optional **shared-secret token** — set `EDT_BRIDGE_TOKEN` (or `-Dedt.bridge.token=`) and send
  `Authorization: Bearer <token>` (or `X-Edt-Bridge-Token: <token>`). Any local process can reach
  the port, so set a token for shared machines.
- Port override: `EDT_BRIDGE_PORT` / `-Dedt.bridge.port=`.

## Status & roadmap

- **Phase 1 (current): read-only.** The five tools above (two more navigation tools planned).
- **Later phases:** write/refactor, debugging, form rendering, test runs — out of scope here.

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) and [ORIGIN.md](ORIGIN.md).

---

# Русский

Небольшой **плагин для 1C:EDT**, который отдаёт **живую семантическую модель** EDT
AI-агентам и другим инструментам по протоколу **MCP** — на `http://127.0.0.1:8770/mcp`.

Статические парсеры читают тексты; edt-bridge вместо этого спрашивает работающую IDE. Он
отвечает на то, что требует *живой* модели: собственные диагностики EDT, реальную структуру и
типы метаданных, семантические перекрёстные ссылки и **валидацию запроса против настоящих
метаданных проекта**.

> Только чтение. Только localhost. Плагин работает внутри EDT — EDT должен быть запущен с открытым
> проектом.

## Инструменты

| Инструмент | Что возвращает |
|------------|----------------|
| `edt_project_errors` | Проблемы валидации EDT (ошибки/предупреждения): сообщение, severity, ресурс, строка. |
| `edt_metadata_details` | Свойства объекта метаданных **и его структуру** — реквизиты, табличные части, формы, команды, макеты, измерения, ресурсы, значения перечислений — с типом каждого реквизита. |
| `edt_metadata_objects` | Объекты метаданных верхнего уровня; опц. фильтр по типу (`Catalog`, `Document`, …) и подстроке имени. |
| `edt_find_references` | Входящие ссылки на объект метаданных (метаданные + BSL) из индекса перекрёстных ссылок EDT. |
| `edt_validate_query` | Валидирует запрос 1С против живых метаданных проекта: синтаксис **и** семантику (несуществующие таблицы/поля, ошибки типов) с позициями. |

В планах: `edt_go_to_definition`, `edt_symbol_info` (семантическая навигация/типизация BSL).

Соглашение об именах: инструменты `edt_*` (snake_case); параметры camelCase (`projectName`,
`fqn`, `queryText`); поддерживаются кириллические FQN (`Справочник.Контрагенты`).

## Требования

- Установленный и запущенный **1C:EDT** с открытым проектом.
- **JDK 17+** для сборки (байт-код плагина — Java 17, рантайм EDT).
- Локальный **p2-пул бандлов EDT** (офлайн-цель сборки): `<домашний-каталог>/.p2/pool/plugins`.

## Сборка

**Вариант A — без Maven** (быстрее всего; локальный JDK + пул EDT, без сети):

```powershell
powershell -ExecutionPolicy Bypass -File build-nomaven.ps1
# или с переопределением путей:
powershell -File build-nomaven.ps1 -Pool "D:\path\.p2\pool\plugins" -JdkHome "C:\path\to\jdk"
```

Результат: `build/com.e1c.fresh.edtbridge_0.0.1.<timestamp>.jar`.

**Вариант B — Maven + Tycho** (стандарт, для CI). Поправьте путь в `edt-bridge.target`, затем:

```
mvn -f pom.xml clean verify
```

## Установка и запуск

1. Скопируйте собранный jar в папку `dropins/` вашего EDT
   (напр. `…/installations/<EDT>/1cedt/dropins/`).
2. **Перезапустите EDT.** При старте плагин поднимет MCP-сервер на `http://127.0.0.1:8770/mcp`.

Проверка через curl:

```bash
curl -s -X POST http://127.0.0.1:8770/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Подключение MCP-клиента

Добавьте HTTP-сервер MCP (напр. в `.mcp.json` вашего клиента):

```json
{
  "mcpServers": {
    "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" }
  }
}
```

Минимальный сервер говорит на обычном JSON-RPC по HTTP (`initialize` / `tools/list` /
`tools/call`) — без SSE и дополнительных SDK.

## Дашборд

Откройте `http://127.0.0.1:8770/` в браузере - встроенная панель: статус сервера, открытые
проекты EDT и интерактивный запуск любого инструмента. Переключение светлой/тёмной темы и
языка EN/RU (по умолчанию - по локали браузера).

## Безопасность

- Слушает **только `127.0.0.1`** — никогда не публичный интерфейс.
- **Только чтение**: нет инструментов записи/рефакторинга/выполнения; `edt_validate_query` только
  разбирает и валидирует.
- Опциональный **общий секрет-токен** — задайте `EDT_BRIDGE_TOKEN` (или `-Dedt.bridge.token=`) и
  присылайте `Authorization: Bearer <token>` (или `X-Edt-Bridge-Token: <token>`).
- Порт: `EDT_BRIDGE_PORT` / `-Dedt.bridge.port=`.

## Статус и план

- **Фаза 1 (сейчас): только чтение.** Пять инструментов выше (ещё два навигационных — в планах).
- **Дальше:** запись/рефакторинг, отладка, рендеринг форм, прогон тестов — вне этой фазы.

## Лицензия

[Apache License 2.0](LICENSE). См. [NOTICE](NOTICE) и [ORIGIN.md](ORIGIN.md).
