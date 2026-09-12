"""Conventions of the sources that no single test of a feature would ever notice.

A convention nobody wrote down is a convention every new file gets to rediscover, and two of
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

The reading of the sources is not the bridge's business and does not live here: elemctl and the
engine start processes and write their files exactly the same way and have the same failures
waiting, so both checks and the readers under them come from the shared `docsguard` package.
They read with `ast` rather than with a regular expression, because a check looking for the text
`subprocess.run(` at the head of a call looks straight past a callable chosen at the call site -
`(run or subprocess.run)(...)`, the shape a runner seam for the tests has.

What stays here is the list of FOLDERS. Which of them hold code that starts processes, and which
of them write files that outlive the run, are facts about this repository and nothing the shared
package could know.
"""

import ast
from pathlib import Path

from docsguard import (
    Layout,
    process_encoding_problems,
    process_starts,
    python_sources,
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


def test_every_process_read_as_text_names_its_encoding():
    """The convention itself: no call decodes with whatever code page the machine has."""
    assert process_encoding_problems(LAYOUT, FOLDERS) == []


def test_the_reader_finds_the_calls_it_is_meant_to_judge():
    """A detector that finds nothing passes every repository, this one included."""
    found = [
        path.relative_to(ROOT).as_posix()
        for path in python_sources(LAYOUT, FOLDERS)
        if process_starts(ast.parse(path.read_text(encoding="utf-8")))
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
        if text_writes(ast.parse(path.read_text(encoding="utf-8")))
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
