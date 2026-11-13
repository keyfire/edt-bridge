---
title: "Build from source"
description: "What the build needs, how to build the plugin jar without Maven, and how a release is cut."
sidebar:
  label: "Build from source"
  order: 5
---

**To build the plugin from source.** This is for contributors; end users install through pipx.

- A **JDK that matches the EDT bundles**. EDT 2026.2 ships Java 25 class files, so compiling against
  them needs a JDK 25. The build script reads the level from the pool and finds a suitable JDK
  itself, including the one installed alongside EDT. The jar still targets Java 17, so one build
  also loads in EDT versions that run on Java 17.
- The local **EDT bundle pool**. On **Windows** that is the p2 pool
  `%USERPROFILE%\.p2\pool\plugins`. On **macOS** it sits inside the installed component:
  `.../1C/1CE/components/1c-edt-<ver>-x86_64/1cedt (<ver>).app/Contents/Eclipse/plugins`, and the
  shell build finds it by itself.

## Build scripts

Without Maven this is the quickest route: a local JDK plus the EDT pool, no network.

```powershell
# Windows – defaults: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME%
powershell -ExecutionPolicy Bypass -File scripts/build-nomaven.ps1
```

```bash
# macOS / Linux – --pool auto-detected from the installed 1C:EDT component pool
./scripts/build-nomaven.sh
```

It produces `build/io.github.keyfire.edtbridge_<version>.<timestamp>.jar`. Maven and Tycho
(`mvn -f pom.xml clean verify`, after editing `edt-bridge.target`) are there for CI.

## Installing the built jar

EDT does not pick the new jar up by itself. On Windows `scripts/run-headless.ps1 -Workspace <ws>
-FromBuild` stops the headless EDT of that workspace, leaves the newest jar from `build/` as the
only bridge jar in `dropins/` and starts EDT again; `-Jar <path>` installs a given jar. The script
refuses while a GUI EDT holds the jar, and a jar that stays locked is reported without a copy. Once
the bridge answers, its `bridgeBundleVersion` must match the version in the jar name, otherwise
the script exits with code 3.

## Releases

Releases are cut from a locally built jar, because CI cannot compile it: the 1C:EDT SDK bundles are
proprietary and cannot be fetched anonymously. The maintainer runs
`scripts/build-nomaven.ps1 -Dist`, commits the jar under `dist/`, tags `vX.Y.Z` and pushes the tag.
`.github/workflows/release.yml` attaches the jar and its checksum. To verify an asset, rebuild from
the tagged source and compare.
