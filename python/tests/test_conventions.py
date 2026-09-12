"""Conventions of the sources that no single test of a feature would ever notice.

A convention nobody wrote down is a convention every new file gets to rediscover, and three of
them live here.

The first is about a process read as TEXT: without `encoding="utf-8"` Python decodes the output
with the code page of the machine, and the failure is the silent kind - the text comes back as
replacement characters, or the decode raises inside the reader thread and leaves the output
EMPTY, while the exit code goes on saying the run went well.

That is not a hypothetical here. The Windows branch of the process lookup already reads BYTES and
decodes by hand, with a comment saying why: `tasklist` prints in the console OEM code page, and a
`text=True` beside it was reading its output as nothing at all, so the guard that is supposed to
refuse a headless start while a GUI EDT holds the workspace never fired. The two calls this
convention found were the same shape one step further out - the POSIX half of that very lookup,
and the pip run whose output is shown to the user when a plugin update fails.

The second is the line ending of a file this repository WRITES. `scripts/gen-cli-docs.py` and
`scripts/release-notes.py` pass `newline=""`; the Windows auto-start did not, and it joins the
lines of its batch file with CRLF - so text mode translated each of those again and every line
of the file on disk ended `\\r\\r\\n`. Two more writes had the same shape: the pipx metadata the
self-update corrects, and the staged copy of an SVG the diagram renderer hands to a browser.

The third is the NAME of a test. A test that arrives under the name of an existing one takes its
place: Python keeps the last definition, pytest collects what the module ended up with, and the
number of tests goes UP, because the newcomer was added. Nothing in the run says the older test
has stopped running. It happened in the shared package while the newline convention above was
being written there.

The reading of the sources is not the bridge's business and does not live here: elemctl and the
engine start processes, write their files and name their tests exactly the same way and have the
same failures waiting, so all three checks and the readers under them come from the shared
`docsguard` package. They read with `ast` rather than with a regular expression, because a check
looking for the text `subprocess.run(` at the head of a call looks straight past a callable
chosen at the call site - `(run or subprocess.run)(...)`, the shape a runner seam for the tests
has.

What stays here is the list of FOLDERS. Which of them hold code that starts processes, which of
them write files that outlive the run, and which of them pytest collects tests from are facts
about this repository and nothing the shared package could know.
"""

import ast
import codecs
from pathlib import Path

from docsguard import (
    Layout,
    process_encoding_problems,
    process_starts,
    python_sources,
    read_text,
    shadowed_test_problems,
    text_write_newline_problems,
    text_writes,
)

ROOT = Path(__file__).resolve().parents[2]
LAYOUT = Layout(root=ROOT)

#: Everything written in Python here: the wrapper, its tests, and the scripts that generate the
#: pages and render the diagrams. A convention that stops at the test folder is half a
#: convention - a test helper that starts the generator is as able to eat its output as the
#: generator is.
FOLDERS = ("python", "scripts")

#: The folders of the newline convention, and deliberately a shorter list: a test writes into a
#: temporary directory that is gone when the run ends - nothing it writes is committed, shipped
#: or compared between machines, and a fixture carrying the other line ending on purpose is a
#: test in its own right. What belongs here is the code whose writes OUTLIVE the run.
WRITING_FOLDERS = ("python/src", "scripts")

#: The folders of the test-name convention: the one pytest collects tests from. The Java suite
#: under `tests/` has its own compiler to answer to and no Python in it at all.
TEST_FOLDERS = ("python/tests",)


def test_every_process_read_as_text_names_its_encoding():
    """The convention itself: no call decodes with whatever code page the machine has."""
    assert process_encoding_problems(LAYOUT, FOLDERS) == []


def test_the_reader_finds_the_calls_it_is_meant_to_judge():
    """A detector that finds nothing passes every repository, this one included."""
    found = [
        path.relative_to(ROOT).as_posix()
        for path in python_sources(LAYOUT, FOLDERS)
        if process_starts(ast.parse(read_text(path)))
    ]

    assert len(found) > 4
    # The process lookup that decides whether a GUI EDT is holding the workspace - the file the
    # convention was found broken in.
    assert "python/src/edt_bridge_mcp/server.py" in found


def test_the_shared_check_still_bites(tmp_path):
    """The guard comes from a pinned package, and a pin is raised by hand.

    A version that had stopped judging would look from here exactly like a repository in order,
    which is the whole failure this file exists to prevent - so the provocation is made against
    the installed package, on sources of its own.
    """
    (tmp_path / "python").mkdir()
    (tmp_path / "python" / "offender.py").write_text(
        "import subprocess\nsubprocess.run(command, capture_output=True, text=True)\n",
        encoding="utf-8")

    problems = process_encoding_problems(Layout(root=tmp_path), ("python",))

    assert len(problems) == 1
    assert "python/offender.py:2" in problems[0]


def test_every_text_file_written_here_names_its_newline():
    """The convention itself: no write hands back a file with every line changed."""
    assert text_write_newline_problems(LAYOUT, WRITING_FOLDERS) == []


def test_the_writes_reader_finds_the_calls_it_is_meant_to_judge():
    """A detector that finds nothing passes every repository, this one included."""
    found = [
        path.relative_to(ROOT).as_posix()
        for path in python_sources(LAYOUT, WRITING_FOLDERS)
        if text_writes(ast.parse(read_text(path)))
    ]

    assert len(found) > 3
    # The file the convention was found broken in: the batch file of the Windows auto-start.
    assert "python/src/edt_bridge_mcp/server.py" in found


def test_the_shared_newline_check_still_bites(tmp_path):
    """A pinned version that had stopped judging looks from here like a repository in order."""
    (tmp_path / "scripts").mkdir()
    (tmp_path / "scripts" / "offender.py").write_text(
        'from pathlib import Path\nPath("page.md").write_text(text, encoding="utf-8")\n',
        encoding="utf-8", newline="")

    problems = text_write_newline_problems(Layout(root=tmp_path), ("scripts",))

    assert len(problems) == 1
    assert "scripts/offender.py:2" in problems[0]


def test_no_test_here_is_shadowed_by_a_namesake():
    """The convention itself: every test this repository names is a test that still runs."""
    assert shadowed_test_problems(LAYOUT, TEST_FOLDERS) == []


def test_the_shared_test_name_check_still_bites(tmp_path):
    """A pinned version that had stopped judging looks from here like a repository in order."""
    (tmp_path / "tests").mkdir()
    (tmp_path / "tests" / "test_twice.py").write_text(
        "def test_one():\n    pass\n\n\ndef test_one():\n    pass\n",
        encoding="utf-8", newline="")

    problems = shadowed_test_problems(Layout(root=tmp_path), ("tests",))

    assert len(problems) == 1
    assert "tests/test_twice.py:5" in problems[0]


def test_a_source_with_a_byte_order_mark_is_judged_rather_than_crashed_on(tmp_path):
    """The readers above parse what they read, and a mark at the head of a file used to raise.

    Editors on Windows write the mark without being asked and no diff shows it. Read as plain
    `utf-8` it stays in the text as a character `ast.parse` refuses, so a single such file left
    the whole check with no findings from any file at all.
    """
    (tmp_path / "python").mkdir()
    (tmp_path / "python" / "marked.py").write_bytes(
        codecs.BOM_UTF8 + b"import subprocess\nsubprocess.run(command, text=True)\n")

    problems = process_encoding_problems(Layout(root=tmp_path), ("python",))

    assert len(problems) == 1
    assert "python/marked.py:2" in problems[0]
