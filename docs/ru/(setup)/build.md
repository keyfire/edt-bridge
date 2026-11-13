---
title: "Сборка из исходников"
description: "Что нужно для сборки, как собрать jar плагина без Maven и как выпускается релиз."
sidebar:
  label: "Сборка из исходников"
  order: 5
---

Для сборки плагина из исходников требуется:

- **JDK под бандлы EDT**. EDT 2026.2 поставляется с class-файлами Java 25, поэтому для компиляции
  против них нужен JDK 25. Скрипт сборки определяет уровень по пулу и сам находит подходящий JDK, в
  том числе установленный рядом с EDT. Байткод jar по-прежнему нацелен на Java 17, поэтому одна
  сборка грузится и в версиях EDT, работающих на Java 17.
- Локальный **пул бандлов EDT**. На **Windows** это p2-пул `%USERPROFILE%\.p2\pool\plugins`. На
  **macOS** он лежит внутри установленного компонента:
  `.../1C/1CE/components/1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/plugins`, и
  shell-сборка находит его сама.

## Скрипты сборки

Без Maven получается быстрее всего: локальный JDK плюс пул EDT, сеть не нужна.

```powershell
# Windows – по умолчанию: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File scripts/build-nomaven.ps1
```

```bash
# macOS / Linux – --pool автоопределяется из пула установленного 1C:EDT
./scripts/build-nomaven.sh
```

На выходе `build/io.github.keyfire.edtbridge_<версия>.<таймстамп>.jar`. Maven и Tycho
(`mvn -f pom.xml clean verify`, сначала правьте `edt-bridge.target`) остаются для CI.

## Установка собранного jar

Новый jar EDT сама не подхватывает. На Windows `scripts/run-headless.ps1 -Workspace <ws>
-FromBuild` останавливает headless EDT этой рабочей области, оставляет в `dropins/` единственным
jar моста самый новый jar из `build/` и снова запускает EDT; `-Jar <путь>` ставит указанный jar.
Пока jar держит EDT с интерфейсом, скрипт отказывает, а о jar, который так и не освободился,
сообщает без копирования. Когда мост ответит, его `bridgeBundleVersion` должен совпасть с версией в
имени jar, иначе скрипт завершается с кодом 3.

## Релизы

Релизы собираются из локально построенного jar, потому что CI его скомпилировать не может:
SDK-бандлы 1C:EDT проприетарны и анонимно не скачиваются. Сопровождающий запускает
`scripts/build-nomaven.ps1 -Dist`, размещает jar в `dist/`, ставит тег `vX.Y.Z` и отправляет тег в
репозиторий. `.github/workflows/release.yml` прикладывает jar и контрольную сумму. Проверить
приложенный файл можно так: пересобрать из тегированного исходника и сравнить.
