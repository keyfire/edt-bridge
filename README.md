# edt-bridge

A small **1C:EDT plugin** that exposes EDT's **live semantic model** to AI agents and other
tools over the **Model Context Protocol (MCP)** — on `http://127.0.0.1:8770/mcp`.

Static parsers read source files; edt-bridge instead asks the running IDE. It answers things
that need the *live* model: EDT's own validation problems, real metadata structure and types,
semantic cross-references, and **query validation against the project's actual metadata**.

> Read **and** write. Localhost only; writes are token-gated and dry-run by default. The plugin
> runs inside EDT, so EDT must be open with the project.

**English** · [Русский](#русский)

## Tools

| Tool | What it returns |
|------|-----------------|
| `edt_projects` | Open workspace projects — name, disk location, natures, and whether each is a 1C:EDT project (discover what is addressable). |
| `edt_project_errors` | EDT validation problems (errors/warnings) for a project: message, severity, resource, line. |
| `edt_metadata_details` | A metadata object's core properties **and structure** — attributes, tabular sections, forms, commands, templates, dimensions, resources, enum values — with each attribute's value type. |
| `edt_metadata_objects` | Top-level metadata objects, optionally filtered by type (`Catalog`, `Document`, …) and a name substring. |
| `edt_find_references` | Inbound references to a metadata object (metadata membership), from EDT's cross-reference index. With `method` given: the BSL call sites of `CommonModule.X.method` (module + line + call text), by scanning the project's BSL. |
| `edt_outgoing_calls` | The reverse: methods CALLED BY a module / method / form (one level out), aggregated `qualifier.method` with call-site counts and an ExtAPI-layer flag. |
| `edt_module_text` | BSL source of a module (or one method) + the module's procedure/function list with signatures, by FQN (CommonModule.X, a form FQN, object+moduleType) or modulePath. |
| `edt_validate_query` | Validates a 1C query against the project's live metadata: syntax **and** semantics (unknown tables/fields, type errors), with positions. |
| `edt_go_to_definition` | Resolve a BSL symbol's definition at a position (line+column or offset): the target's kind, name, owning object and location. |
| `edt_symbol_info` | Type/symbol info at a position in a BSL module: the element under the cursor and the computed value type(s) of the expression (dynamic typing). |
| `edt_form_structure` | A managed form's items tree (fields/groups/tables/buttons/decorations) with data bindings + static visible/enabled/readOnly, per-item event handlers + a cell-hyperlink flag, input-field password/choice-button props (passwordMode/choiceButton/choiceButtonPicture), button→command + representation/placement, and the form's declarative conditional appearance; plus its attributes, commands, parameters and event handlers. |
| `edt_form_render` | Renders a managed form to a PNG via EDT's native offscreen renderer (the engine behind the form-editor preview); chooses the interface variant (Taxi / 8.5) and theme. |
| `edt_picture_export` | A CommonPicture's content from its Picture.zip: the variant list (DPI / interface variant 8.2 vs 8.5 / theme / isTemplate) + a recommended pick, and a chosen variant's bytes as base64. |
| `edt_outgoing_structures` | **Best-effort companion to `edt_outgoing_calls`:** for each outgoing (qualified) call, the top-level keys of the `Структура` passed as its argument — collects `<var>.Вставить("key", …)` and expands a same-module template helper one level. Optional `qualifier` prefix scopes to one layer (e.g. `ПрограммныйИнтерфейсСервиса`). |

**Write tools** mutate the model through EDT's own engine (not text edits). All are **token-gated**
and **dry-run by default** (`apply=false` returns a plan and changes nothing); `apply=true` performs
the change and serializes the `.mdo`.

| Write tool | What it does |
|------------|--------------|
| `edt_add_attribute` | Add an attribute to a metadata object (type / klass / synonym / comment), validated. |
| `edt_modify_attribute` | Change an existing attribute's type, synonym or comment. |
| `edt_remove_attribute` | Remove an attribute (reference-checked; refuses if referenced unless forced). |
| `edt_rename` | Rename an object or member and **cascade every reference in metadata AND BSL** via EDT's native refactoring engine (`force` required — a rename is a breaking change). |
| `edt_create_object` | Create a new top object (Catalog/Document/Enum/InformationRegister/…) via EDT's factory + per-type initializer, registered in the Configuration. |
| `edt_delete_object` | Delete an object or member and **cascade the removal of every reference in metadata AND BSL** via EDT's native refactoring engine; removes the object's `.mdo` and updates the Configuration (`force` required — a delete is irreversible and breaking). |
| `edt_debug_attach` | Attach a debug session to a **running** infobase's debug server (dbgs); returns a `sessionId` for the other debug tools (token-gated; use a test stand, not production). |
| `edt_debug_detach` | Detach (terminate) a debug session and free the infobase. |
| `edt_debug_inspect` | List a session's threads and, for **suspended** ones, their BSL stack frames (signature/line/source) + the top frame's variables. Read-only. |
| `edt_debug_control` | Control execution: `suspend`/`resume` the target, or `stepOver`/`stepInto`/`stepReturn` a suspended thread (token-gated). |
| `edt_evaluate` | Evaluate an **arbitrary BSL expression** in a suspended frame — code execution against the live infobase. Heaviest gate: token **and** per-call `allowCodeExecution=true` **and** the server switch `EDT_BRIDGE_ALLOW_EVALUATE=1` (off by default). |

Naming convention: tools are `edt_*` (snake_case); parameters are camelCase (`projectName`,
`fqn`, `queryText`); Cyrillic FQNs are supported (`Справочник.Контрагенты`).

## Requirements

- **1C:EDT** installed and running, with your project open in the workspace.
- A **JDK 17+** to build (the bundle's bytecode targets Java 17, the EDT runtime).
- The local **EDT bundle pool** (used as the offline build target). On **Windows** that is the
  p2 pool `<your-home>\.p2\pool\plugins`; on **macOS** the self-contained pool lives inside the
  installed component: `…/1C/1CE/components/1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/plugins`
  (the shell build auto-detects it).

## Build

**Option A — no Maven** (quickest; pure local JDK + the EDT pool, no network):

```powershell
# Windows — defaults: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File build-nomaven.ps1
# or override:
powershell -File build-nomaven.ps1 -Pool "D:\path\.p2\pool\plugins" -JdkHome "C:\path\to\jdk"
```

```bash
# macOS / Linux — same output. Defaults: --jdk-home from JAVA_HOME (or `/usr/libexec/java_home -v 17`
# on macOS); --pool auto-detected from the installed 1C:EDT component pool.
./build-nomaven.sh
# or override:
./build-nomaven.sh --pool "/path/to/Contents/Eclipse/plugins" --jdk-home "/path/to/jdk-17"
```

Produces `build/io.github.keyfire.edtbridge_0.0.1.<timestamp>.jar`.

**Option B — Maven + Tycho** (standard, for CI). Edit `edt-bridge.target` to point at your pool, then:

```
mvn -f pom.xml clean verify
```

## Install & run

1. Copy the built jar into EDT's `dropins/` folder — Windows: `…/installations/<EDT>/1cedt/dropins/`;
   **macOS**: `…/1C/1CE/components/1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/dropins/`
   (create it if it does not exist).
2. **Restart EDT.** On launch the plugin starts the MCP server on `http://127.0.0.1:8770/mcp`.

To run EDT **headless** (no GUI window) so the server serves the live model, use
`run-headless.ps1` (Windows) or `run-headless.sh --workspace <ws>` (macOS / Linux).

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
- **Writes are gated**: every write tool requires a configured token, defaults to a dry-run, and
  operates only on your own local EDT model; `edt_rename` and `edt_delete_object` additionally need an
  explicit `force`. No code execution: `edt_validate_query` only parses and validates.
- Optional **shared-secret token** — set `EDT_BRIDGE_TOKEN` (or `-Dedt.bridge.token=`) and send
  `Authorization: Bearer <token>` (or `X-Edt-Bridge-Token: <token>`). Any local process can reach
  the port, so set a token for shared machines.
- Port override: `EDT_BRIDGE_PORT` / `-Dedt.bridge.port=`.

## Status & roadmap

- **Phase 1 (read) + Phase 2 (write) + Phase 3 (debug) — done.** 25 tools: 14 read + 6 write +
  5 debug (above).
- **`edt_outgoing_structures` is best-effort and general.** It reports the top-level keys of the
  structure passed to any qualified outgoing call; pass the optional `qualifier` prefix to scope to one
  layer — e.g. `ПрограммныйИнтерфейсСервиса` for a project that routes its service calls through an
  "ExtAPI" wrapper. Heuristic: literal keys only, flow-insensitive; `partial` flags an incomplete result.
- **Known limitation:** the server is currently single-threaded, so a long operation (e.g. a rename,
  whose native refactoring can run for minutes) blocks other requests until it finishes — the server
  appears unresponsive meanwhile. Making it multi-threaded / async is planned.
- **Later:** test runs (YAXUnit / Vanessa) — out of scope here.

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

> Чтение **и** запись. Только localhost; запись под токеном и по умолчанию dry-run. Плагин работает
> внутри EDT — EDT должен быть запущен с открытым проектом.

## Инструменты

| Инструмент | Что возвращает |
|------------|----------------|
| `edt_projects` | Открытые проекты рабочей области — имя, путь на диске, natures, является ли проектом 1C:EDT (понять, что адресовать). |
| `edt_project_errors` | Проблемы валидации EDT (ошибки/предупреждения): сообщение, severity, ресурс, строка. |
| `edt_metadata_details` | Свойства объекта метаданных **и его структуру** — реквизиты, табличные части, формы, команды, макеты, измерения, ресурсы, значения перечислений — с типом каждого реквизита. |
| `edt_metadata_objects` | Объекты метаданных верхнего уровня; опц. фильтр по типу (`Catalog`, `Document`, …) и подстроке имени. |
| `edt_find_references` | Входящие ссылки на объект метаданных (членство в метаданных) из индекса перекрёстных ссылок EDT. С параметром `method`: BSL-места вызова `CommonModule.X.method` (модуль + строка + текст вызова), обходом BSL проекта. |
| `edt_outgoing_calls` | Обратное: какие методы ВЫЗЫВАЕТ модуль / метод / форма (на уровень наружу), агрегировано `квалификатор.метод` с числом мест вызова и флагом слоя ExtAPI. |
| `edt_module_text` | Исходный BSL модуля (или одного метода) + список процедур/функций с сигнатурами; адресация по FQN (CommonModule.X, FQN формы, объект+moduleType) или modulePath. |
| `edt_validate_query` | Валидирует запрос 1С против живых метаданных проекта: синтаксис **и** семантику (несуществующие таблицы/поля, ошибки типов) с позициями. |
| `edt_go_to_definition` | Переход к определению символа BSL в позиции (строка+столбец или offset): вид цели, имя, объект-владелец, расположение. |
| `edt_symbol_info` | Тип/инфо символа в позиции модуля BSL: элемент под курсором и вычисленные типы значения выражения (динамическая типизация). |
| `edt_form_structure` | Дерево элементов управляемой формы (поля/группы/таблицы/кнопки/декорации) с привязками данных + статические visible/enabled/readOnly, обработчики уровня элемента + флаг cellHyperlink, design-свойства пароля/кнопки выбора у полей ввода (passwordMode/choiceButton/choiceButtonPicture), команда→кнопка + представление/размещение, и декларативное условное оформление формы; плюс реквизиты, команды, параметры и обработчики событий формы. |
| `edt_form_render` | Рендерит управляемую форму в PNG штатным offscreen-рендером EDT (движок предпросмотра редактора форм); выбор варианта интерфейса (Такси / 8.5) и темы. |
| `edt_picture_export` | Содержимое CommonPicture из Picture.zip: перечень вариантов (DPI / вариант интерфейса 8.2 или 8.5 / тема / isTemplate) + рекомендуемый, и байты выбранного варианта в base64. |
| `edt_outgoing_structures` | **Best-effort, пара к `edt_outgoing_calls`:** для каждого исходящего (квалифицированного) вызова — ключи верхнего уровня `Структуры`, переданной аргументом; собирает `<Перем>.Вставить("ключ", …)` и раскрывает хелпер-шаблон того же модуля на один уровень. Необязательный префикс `qualifier` ограничивает одним слоем (напр. `ПрограммныйИнтерфейсСервиса`). |

**Инструменты записи** меняют модель через штатный движок EDT (не текстовой заменой). Все –
**под токеном** и по умолчанию **dry-run** (`apply=false` возвращает план и ничего не меняет);
`apply=true` применяет изменение и сериализует `.mdo`.

| Инструмент записи | Что делает |
|-------------------|------------|
| `edt_add_attribute` | Добавляет реквизит объекту метаданных (тип / синоним / comment), с валидацией. |
| `edt_modify_attribute` | Меняет тип, синоним или comment существующего реквизита. |
| `edt_remove_attribute` | Удаляет реквизит (проверка ссылок; отказ при наличии ссылок без force). |
| `edt_rename` | Переименовывает объект или член с **каскадом всех ссылок в метаданных И в BSL** через штатный движок рефакторинга EDT (нужен `force` – переименование ломает совместимость). |
| `edt_create_object` | Создаёт новый топ-объект (Справочник/Документ/Перечисление/РегистрСведений/…) через фабрику EDT + инициализатор типа, с регистрацией в Configuration. |
| `edt_delete_object` | Удаляет объект или член с **каскадным удалением всех ссылок в метаданных И в BSL** через штатный движок рефакторинга EDT; удаляет `.mdo` объекта и правит Configuration (нужен `force` – удаление необратимо и ломает совместимость). |
| `edt_debug_attach` | Подключает сессию отладки к debug-серверу (dbgs) **запущенной** ИБ; возвращает `sessionId` для остальных debug-инструментов (под токеном; использовать тестовый стенд, не продакшен). |
| `edt_debug_detach` | Отключает (завершает) сессию отладки и освобождает ИБ. |
| `edt_debug_inspect` | Список потоков сессии и, для **приостановленных**, кадры стека BSL (сигнатура/строка/источник) + переменные верхнего кадра. Только чтение. |
| `edt_debug_control` | Управление выполнением: `suspend`/`resume` цели или `stepOver`/`stepInto`/`stepReturn` приостановленного потока (под токеном). |
| `edt_evaluate` | Вычисляет **произвольное BSL-выражение** в приостановленном кадре — исполнение кода против живой ИБ. Самый жёсткий гейт: токен **и** per-call `allowCodeExecution=true` **и** серверный переключатель `EDT_BRIDGE_ALLOW_EVALUATE=1` (по умолчанию выключено). |

Соглашение об именах: инструменты `edt_*` (snake_case); параметры camelCase (`projectName`,
`fqn`, `queryText`); поддерживаются кириллические FQN (`Справочник.Контрагенты`).

## Требования

- Установленный и запущенный **1C:EDT** с открытым проектом.
- **JDK 17+** для сборки (байт-код плагина — Java 17, рантайм EDT).
- Локальный **пул бандлов EDT** (офлайн-цель сборки). На **Windows** это p2-пул
  `<домашний-каталог>\.p2\pool\plugins`; на **macOS** самодостаточный пул лежит внутри установленного
  компонента: `…/1C/1CE/components/1c-edt-<вер>-x86_64/1cedt (<вер>).app/Contents/Eclipse/plugins`
  (shell-сборка находит его автоматически).

## Сборка

**Вариант A — без Maven** (быстрее всего; локальный JDK + пул EDT, без сети):

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File build-nomaven.ps1
# или с переопределением путей:
powershell -File build-nomaven.ps1 -Pool "D:\path\.p2\pool\plugins" -JdkHome "C:\path\to\jdk"
```

```bash
# macOS / Linux — тот же результат. По умолчанию: --jdk-home из JAVA_HOME (или
# `/usr/libexec/java_home -v 17` на macOS); --pool автоопределяется из пула установленного 1C:EDT.
./build-nomaven.sh
# или с переопределением путей:
./build-nomaven.sh --pool "/path/to/Contents/Eclipse/plugins" --jdk-home "/path/to/jdk-17"
```

Результат: `build/io.github.keyfire.edtbridge_0.0.1.<timestamp>.jar`.

**Вариант B — Maven + Tycho** (стандарт, для CI). Поправьте путь в `edt-bridge.target`, затем:

```
mvn -f pom.xml clean verify
```

## Установка и запуск

1. Скопируйте собранный jar в папку `dropins/` вашего EDT — Windows: `…/installations/<EDT>/1cedt/dropins/`;
   **macOS**: `…/1C/1CE/components/1c-edt-<вер>-x86_64/1cedt (<вер>).app/Contents/Eclipse/dropins/`
   (создайте, если её нет).
2. **Перезапустите EDT.** При старте плагин поднимет MCP-сервер на `http://127.0.0.1:8770/mcp`.

Чтобы запустить EDT **headless** (без окна GUI), используйте `run-headless.ps1` (Windows) или
`run-headless.sh --workspace <ws>` (macOS / Linux).

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
- **Запись под защитой**: каждый инструмент записи требует токен, по умолчанию dry-run и работает
  только с вашей локальной моделью EDT; `edt_rename` и `edt_delete_object` дополнительно требуют явный
  `force`. Выполнения
  кода нет: `edt_validate_query` только разбирает и валидирует.
- Опциональный **общий секрет-токен** — задайте `EDT_BRIDGE_TOKEN` (или `-Dedt.bridge.token=`) и
  присылайте `Authorization: Bearer <token>` (или `X-Edt-Bridge-Token: <token>`).
- Порт: `EDT_BRIDGE_PORT` / `-Dedt.bridge.port=`.

## Статус и план

- **Фаза 1 (чтение) + Фаза 2 (запись) + Фаза 3 (отладка) — готовы.** 25 инструментов: 14 read +
  6 write + 5 debug (выше).
- **`edt_outgoing_structures` — best-effort и общий.** Возвращает ключи верхнего уровня структуры,
  передаваемой в любой квалифицированный исходящий вызов; необязательный префикс `qualifier`
  ограничивает одним слоем — например `ПрограммныйИнтерфейсСервиса` для проекта, направляющего
  сервисные вызовы через обвязку «ExtAPI». Эвристика: только литеральные ключи; `partial` — неполно.
- **Известное ограничение:** сервер пока однопоточный, поэтому долгая операция (напр. переименование,
  чей нативный рефакторинг может идти минутами) блокирует другие запросы до завершения — всё это время
  сервер не отвечает. Многопоточность / async запланированы.
- **Дальше:** прогон тестов (YAXUnit / Vanessa) — вне этой фазы.

## Лицензия

[Apache License 2.0](LICENSE). См. [NOTICE](NOTICE) и [ORIGIN.md](ORIGIN.md).
