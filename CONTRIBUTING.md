# Contributing to EDT-Bridge

**English** · [Русский](docs/ru/CONTRIBUTING.ru.md)

Thanks for your interest. EDT-Bridge is a 1C:EDT plugin plus a small Python wrapper. This guide
covers building it, the conventions, and how to propose a change.

## Ground rules

- **Clean-room.** EDT-Bridge is an independent implementation written from scratch, as
  [ORIGIN.md](ORIGIN.md) explains. Contribute only code you wrote yourself, built on the open MCP
  spec and the public Eclipse and 1C:EDT plugin APIs (`org.eclipse.*`, `com._1c.g5.v8.dt.*`). Do not
  paste decompiled or otherwise proprietary source from EDT or any other tool.
- **Scope.** The plugin exposes EDT's *live* model over MCP. A new tool should need something a
  static parser cannot answer: the running IDE's validation, real types, semantic references, native
  refactoring or a build. If it does not, a standalone tool is a better home for it.
- **License.** By contributing you agree your work is licensed under [Apache-2.0](LICENSE), inbound
  equals outbound.

## Building

You need a **JDK matching your EDT** and the local **1C:EDT bundle pool**. The plugin compiles
against EDT's own bundles, which are proprietary and come from your local EDT install, so there is no
network build. Those bundles are Java 25 class files as of EDT 2026.2, and reading them needs a JDK
25. The script derives the level from the pool and falls back to any newer JDK it finds, including
the one installed with EDT. The jar itself keeps targeting Java 17.

```powershell
# Windows – defaults: -Pool %USERPROFILE%\.p2\pool\plugins, -JdkHome %JAVA_HOME% (auto if too old)
powershell -ExecutionPolicy Bypass -File scripts/build-nomaven.ps1
```

```bash
# macOS / Linux – --pool auto-detected from the installed 1C:EDT component pool
./scripts/build-nomaven.sh
```

The jar lands in `build/`. Maven and Tycho (`mvn -f pom.xml clean verify`) are there for a full
build; edit `edt-bridge.target` first. More detail: [Build from source](https://docs.keyfire.ru/edt-bridge/build/).

## Running your build

Copy the built jar into EDT's `dropins/` and restart EDT, or let the wrapper deliver it. Keep exactly
one EDT-Bridge jar there, because two make Equinox load an arbitrary one. On Windows
`scripts/run-headless.ps1 -Workspace <ws> -FromBuild` does both for a headless EDT: it stops the
headless session of that workspace, leaves the newest jar from `build/` as the only one in
`dropins/` and starts EDT. `-Jar <path>` installs a given jar instead. The script then compares the
`bridgeBundleVersion` the bridge reports with the version in the jar name and exits with code 3 when
they differ. The MCP server comes up on
`http://127.0.0.1:8770/mcp`, and the page at `http://127.0.0.1:8770/` has a runner for every tool.
See [Manual install](https://docs.keyfire.ru/edt-bridge/install/#manual-install-without-the-wrapper).

## Verifying a change

There is no complete offline test suite for the Java plugin. Verify it against a running EDT: build,
deploy to `dropins/`, restart EDT, and exercise the tools you touched, either from the page in the
browser or from an MCP client. For write tools, check the plan with `apply=false` before running
`apply=true`. A hosted runner can do none of this, because compiling the bundle needs the proprietary
1C:EDT SDK bundles. That is also why the jar is built locally and committed under `dist/`.

The Python wrapper is a different matter. It has no EDT dependency, so it has a pytest suite:

```sh
cd python
python -m pip install .[test]
python -m pytest -q
```

The `ci` workflow runs that suite on every push and pull request, on Linux and Windows, on 3.10 and
3.12. Both release workflows call `ci` first, so a red suite stops a release instead of shipping past
it. The matrix includes Windows because an encoding fault specific to that system caused a broken
release.

New wrapper behaviour is expected to arrive with a test, and a fixed bug with the test that
reproduces it. The current suite is largely made of exactly those.

Part of the plugin is testable as well. Everything under `...edtbridge.core` is pure string work –
where an object's sources are located, whether a validation problem belongs to the requested scope – and it
is kept free of EDT and Eclipse types on purpose, so a plain JDK compiles it:

```sh
scripts/test-java.sh
```

The script adds only JUnit to the classpath, so an SDK dependency in `core` fails the build. Logic
that requires the live model remains in the gateways under `...edtbridge.edt` and is verified
against a running EDT, as described above.

### Documentation

`python scripts/check_docs.py` compares documentation with the sources. Every tool must have one
table row on one page of `docs/tools/`, and every `EDT_BRIDGE_` variable must be described on the
settings page. The table of tool groups in the READMEs must match its source page, and image files
must be in the repository. The wrapper test suite runs the same check in `ci`.

The English pages are in `docs/`, the Russian ones in `docs/ru/` under the same paths, and the
check reports a page that exists in one language only. A folder is a sidebar group; its `meta.ts`
sets the group's title and order. When a section moves to another page, leave its anchor on the old
page as `<a id="..."></a>` and add it to `MOVED_ANCHORS` in `scripts/check_docs.py`: links from
outside the site still point at the old address.

The Russian texts are checked against a transliteration dictionary from
[docsguard](https://github.com/keyfire/docsguard). Each finding includes a suggested Russian
replacement. Put a word in backticks when quoting it as a word, for example when a changelog entry
names a replaced transliteration.

The same dictionary checks the Russian strings in `python/src/edt_bridge_mcp/i18n.py`, the message
catalog used to generate `--help`. A missing named file is reported as an error so that a renamed
catalog cannot make the check pass without reading any text.

The changelog, onboarding and security pages are generated from `CHANGELOG.md`, `ONBOARDING.md`
and `SECURITY.md` by `node scripts/sync-docs.mjs`, and the command reference `docs/cli.md` by
`python scripts/gen-cli-docs.py`. Edit the source documents and the generator.

### Starting a process

When reading text output from a process started by the wrapper or a script, specify the encoding:
`capture_output=True, text=True, encoding="utf-8"`. Also use `errors="replace"` for output intended
for a person. Without `encoding`, Python uses the machine's code page. A decoding error may produce
replacement characters or leave output empty after an exception in the reader thread, while the
process exit code still indicates success. This prevented the check that blocks a headless start
when a GUI EDT holds the workspace from detecting the running process.

When text mode is not required, pass and receive bytes and decode them separately. The `tasklist`
lookup uses this approach.

Set `stdin=subprocess.DEVNULL` for a child process started by the wrapper. The wrapper runs as an
MCP server and uses stdin to communicate with the client. On Windows, a child that inherits this
channel can remain running until the timeout, even when its work takes milliseconds. `tasklist`,
`powershell` and `cmd` close their end of the channel and exit. Git, an interpreter and pip may
remain running; pip is used to update plugins. The wrapper does not send input to these child
processes. The check allows calls that pass data through `input=` or provide their own `stdin=`.

`python/tests/test_conventions.py` checks that an encoding is specified when process output is read
as text. It also checks calls shaped like `(run or subprocess.run)(...)`, which a text search can
miss. The analysis is provided by [docsguard](https://github.com/keyfire/docsguard), installed by
the `ci` workflow from a tag. The project supplies the list of directories. The stdin rule covers
only `python/src`: generators and tests run from a console and can pass console stdin to a child.

### Writing a file

Specify encoding and line endings when writing a text file from the wrapper or a script:
`write_text(text,
encoding="utf-8", newline="")`. Without `newline=""`, text mode translates line feeds to the
platform's line ending. A generator on Windows can therefore change every line in a page. A checkout
with `core.autocrlf=input` normalizes the change on commit, so it can be missed.

The Windows auto-start batch file already joins lines with CRLF. If text mode translates those line
feeds again, the file contains `\r\r\n`.

`python/tests/test_conventions.py` checks that line endings are specified. It covers `python/src`
and `scripts`, but not tests. Tests write to temporary directories, and a fixture with a different
line ending is tested separately.

`newline=""` controls writing. If a file is read with carriage returns still present,
`scripts/sync-docs.mjs` can copy them into both READMEs when inserting the table of tool groups. The
`.gitattributes` line `* text=auto
eol=lf` specifies line feeds for text files regardless of the machine's settings. The
`core.autocrlf=input` setting applies only to the checkout where it is configured.

### Naming a test

When two tests in the same namespace have the same name, Python keeps the last definition. Pytest
collects the last version, and the test count can still increase if other tests are added. The test
report does not indicate that the earlier definition was replaced.

`python/tests/test_conventions.py` runs `shadowed_test_problems` over `python/tests` and reports
the line of the duplicate definition to rename. Namespaces are checked separately, so methods in
different classes can have the same name.

## Code style

- **Java 17** and the standard Eclipse and OSGi conventions. Keep all IDE-model access inside the
  gateway classes under `...edtbridge.edt`, so that an EDT API change is contained there.
- **English** for all code, comments and identifiers.
- One tool per class under `...edtbridge.tools`, registered in `McpServer`.
- Tool names are `edt_*` in snake_case, parameters are camelCase. Keep the names self-describing: an
  MCP host shows every server's tools as one flat list, as the [tools overview](https://docs.keyfire.ru/edt-bridge/tools/)
  explains.

## Commits and pull requests

- Small commits, each about one thing, with a clear subject line.
- Open a pull request against `main` and fill in the template: what changed, how you verified it.
- Every changelog entry ends with a link to its pull request, in both editions. The number only
  exists once the pull request is open, so the link arrives in a commit of its own:

  ```sh
  python scripts/changelog-link.py 16
  ```

  It writes the link into every link-less entry of the topmost section of `CHANGELOG.md` and
  `docs/ru/CHANGELOG.ru.md`, puts it on a continuation line where the entry is already the full
  width, and rebuilds the mirrored pages. Older sections are left alone: an entry from before the
  rule is history rather than an unfinished change.
- Releases are cut by the maintainer from a locally built jar, since CI cannot compile the plugin, so
  a merged change ships in the next tagged release.

## Reporting bugs and requesting features

Open a [GitHub issue](https://github.com/keyfire/edt-bridge/issues) using the templates. For
security-sensitive reports, follow [SECURITY.md](SECURITY.md) instead of a public issue.
