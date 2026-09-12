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
build; edit `edt-bridge.target` first. More detail: [README](README.md#build-from-source).

## Running your build

Copy the built jar into EDT's `dropins/` and restart EDT, or let the wrapper deliver it. Keep exactly
one EDT-Bridge jar there, because two make Equinox load an arbitrary one. The MCP server comes up on
`http://127.0.0.1:8770/mcp`, and the page at `http://127.0.0.1:8770/` has a runner for every tool.
See [Manual install](README.md#manual-install-without-the-wrapper).

## Verifying a change

There is no offline unit suite for the Java plugin. It is verified against a **live EDT**: build,
deploy to `dropins/`, restart EDT, and exercise the tools you touched, either from the page in the
browser or from an MCP client. For write tools, check the plan with `apply=false` before running
`apply=true`. A hosted runner can do none of this, because compiling the bundle needs the proprietary
1C:EDT SDK bundles. That is also why the jar is built locally and committed under `dist/`.

The Python wrapper is a different matter. It has no EDT dependency, so it carries a pytest suite:

```sh
cd python
python -m pip install .[test]
python -m pytest -q
```

The `ci` workflow runs that suite on every push and pull request, on Linux and Windows, on 3.10 and
3.12. Both release workflows call `ci` first, so a red suite stops a release instead of shipping past
it. Windows is in the matrix for a reason: the one release that shipped broken did so because of an
encoding fault that only appears there.

New wrapper behaviour is expected to arrive with a test, and a fixed bug with the test that
reproduces it. The current suite is largely made of exactly those.

Part of the plugin is testable as well. Everything under `...edtbridge.core` is pure string work –
where an object's sources live, whether a validation problem belongs to what was asked about – and it
is kept free of EDT and Eclipse types on purpose, so a plain JDK compiles it:

```sh
scripts/test-java.sh
```

That script puts nothing but JUnit on the classpath. A dependency on the SDK creeping into `core`
therefore fails the build instead of quietly making the suite unrunnable. Logic that needs the live
model belongs in the gateways under `...edtbridge.edt` and is verified against a real EDT, as above.

### Documentation

`python scripts/check_docs.py` reads the pages against the sources. Every tool and every
`EDT_BRIDGE_` variable has to have a row, the blocks mirrored into the READMEs have to match the
page they came from, and every image has to be in the repository. The wrapper suite runs the same
script, so a page that has drifted fails `ci` along with the tests.

The Russian pages are read for their wording too. The dictionary of transliterated words lives in
the shared [docsguard](https://github.com/keyfire/docsguard), and each finding comes with the
Russian word to write instead. A word quoted as a word goes in backticks, the way a changelog entry
says which transliteration was replaced; the check reads backticks as a name and walks past them.

The same dictionary reads the Russian strings of `python/src/edt_bridge_mcp/i18n.py`. That is the
message catalog every line of `--help` comes from. A file named there and missing is a finding of
its own: otherwise a renamed catalog would leave the check reading nothing and passing.

`docs/changelog*.md` and `docs/onboarding*.md` are mirrors of the root documents. Rebuild them with
`node scripts/sync-docs.mjs` and edit the source instead.

### Starting a process

A process started from the wrapper or from a script is read as text, and the text is decoded
explicitly: `capture_output=True, text=True, encoding="utf-8"`, plus `errors="replace"` wherever the
output goes to a human. Without `encoding` Python decodes with the code page of the machine, and the
failure is silent in the worst way. The output comes back as replacement characters, or the decode
raises inside the reader thread and leaves the output empty, while the exit code goes on saying the
run went well. That is how the guard which refuses a headless start while a GUI EDT holds the
workspace came to never fire. A call that asks for no text at all decodes nothing and needs neither
setting: bytes in, bytes out, decoded by hand afterwards, the way the `tasklist` lookup does it.

`python/tests/test_conventions.py` fails on a process read as text without an encoding, the
`(run or subprocess.run)(...)` shape of a runner seam included, which is the shape a search for the
text of a call looks straight past. The reading itself comes from the shared
[docsguard](https://github.com/keyfire/docsguard) package, pinned to a tag by the `ci` workflow. What
stays here is the list of folders.

### Writing a file

A text file written from the wrapper or from a script names its line ending: `write_text(text,
encoding="utf-8", newline="")`. Text mode otherwise translates every line feed into the platform's
ending, and a generator run on Windows hands back a page with every line changed. A checkout with
`core.autocrlf=input` normalizes that away on commit, which is what makes it easy to miss.

The batch file of the Windows auto-start was caught by the same rule from the other side. Its lines
are joined with CRLF because that is what a batch file wants, text mode translated each of those a
second time, and every line went to disk as `\r\r\n`.

`python/tests/test_conventions.py` fails on a write that leaves the line ending to the platform. It
reads `python/src` and `scripts` and not the tests: a test writes into a temporary directory that is
gone when the run ends, and a fixture carrying the other line ending on purpose is a test in its own
right.

## Code style

- **Java 17** and the standard Eclipse and OSGi conventions. Keep all IDE-model access inside the
  gateway classes under `...edtbridge.edt`, so that an EDT API change is contained there.
- **English** for all code, comments and identifiers.
- One tool per class under `...edtbridge.tools`, registered in `McpServer`.
- Tool names are `edt_*` in snake_case, parameters are camelCase. Keep the names self-describing: an
  MCP host shows every server's tools as one flat list, as the README explains.

## Commits and pull requests

- Small commits, each about one thing, with a clear subject line.
- Open a pull request against `main` and fill in the template: what changed, how you verified it.
- Releases are cut by the maintainer from a locally built jar, since CI cannot compile the plugin, so
  a merged change ships in the next tagged release.

## Reporting bugs and requesting features

Open a [GitHub issue](https://github.com/keyfire/edt-bridge/issues) using the templates. For
security-sensitive reports, follow [SECURITY.md](SECURITY.md) instead of a public issue.
