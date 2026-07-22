---
title: "Установка и сборка"
description: "Требования, установка вручную без обвязки и сборка плагина из исходников."
sidebar:
  label: Установка
  order: 4
---

**Чтобы пользоваться мостом**

- **1C:EDT** с открытым проектом – либо позвольте `edt-bridge-mcp` поднять headless EDT.
- Для `edt_dump_external_object` (сборка `.epf`/`.erf`) и `edt_update_infobase`: установленная
  локально **платформа 1С:Предприятие**, совместимая с версией проекта – EDT управляет ею для
  компиляции бинарника и обновления информационной базы.

**Чтобы собрать плагин из исходников** (для контрибьюторов – конечные пользователи ставят через pipx)

- **JDK 17+** (байткод бандла нацелен на Java 17, рантайм EDT).
- Локальный **пул бандлов EDT**. На **Windows** p2-пул `%USERPROFILE%\.p2\pool\plugins`; на
  **macOS** пул внутри установленного компонента
  `.../1C/1CE/components/1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/plugins`
  (shell-сборка определяет его сама).

## Установка вручную (без обвязки)

Обвязка pipx доставляет jar и запускает EDT за вас. Чтобы запускать плагин самому:

1. Возьмите jar – со [страницы Releases](https://github.com/keyfire/edt-bridge/releases) (с
   `SHA256SUMS.txt`) или соберите (ниже).
2. Скопируйте в `dropins/` EDT – Windows `.../installations/<EDT>/1cedt/dropins/`, **macOS**
   `.../1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/dropins/` (создайте, если нет).
   Держите там только один jar EDT-Bridge – два заставят Equinox взять произвольный.
3. **Перезапустите EDT.** Плагин поднимает MCP-сервер на `http://127.0.0.1:8770/mcp` (или на
   следующем свободном порту, если 8770 занят).

Запустить EDT **headless** (без GUI): `scripts/run-headless.ps1 -Workspace <ws>` (Windows) или
`scripts/run-headless.sh --workspace <ws>` (macOS / Linux); `scripts/toggle-headless.ps1` включает/выключает
одним действием. Запущенную GUI EDT это никогда не трогает. Запустить **GUI** на рабочей области:
`scripts/run-gui.ps1 -Workspace <ws>`.

Оба лаунчера отказываются поднимать вторую EDT на уже занятой рабочей области и не снимают
блокировку, которую держит живой экземпляр, – общая проверка лежит в `scripts/edt-common.ps1`.
Запуск `1cedt.exe` руками эту проверку минует: второй экземпляр падает с "рабочая область уже
используется", а если сделать так дважды – остаётся ворох недозапущенных окон.

MCP-клиент может говорить с плагином и напрямую по HTTP (без обвязки) – добавьте
`{ "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" } }` в его `.mcp.json`.
Сервер говорит простым JSON-RPC по HTTP (`initialize` / `tools/list` / `tools/call`).

### Сборка из исходников

Без Maven (быстрее всего – локальный JDK + пул EDT, без сети):

```powershell
# Windows – по умолчанию: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File scripts/build-nomaven.ps1
```

```bash
# macOS / Linux – --pool автоопределяется из пула установленного 1C:EDT
./scripts/build-nomaven.sh
```

Даёт `build/io.github.keyfire.edtbridge_<версия>.<таймстамп>.jar`. Maven + Tycho
(`mvn -f pom.xml clean verify`, сначала правьте `edt-bridge.target`) доступен для CI.

Релизы собираются из локально построенного jar – CI не может его скомпилировать (SDK-бандлы
1C:EDT проприетарны и не скачиваются анонимно). Мейнтейнер запускает `scripts/build-nomaven.ps1 -Dist`,
кладёт jar в `dist/`, ставит тег `vX.Y.Z` и пушит тег; `.github/workflows/release.yml`
прикладывает jar + контрольную сумму. Проверить ассет можно пересборкой из тегированного
исходника и сравнением.
