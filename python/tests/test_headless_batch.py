"""The batch file the Windows auto-start runs, as it lands on disk.

The bytes are asserted rather than the string, because the defect was in the writing and not in
the text. The lines are joined with CRLF, `write_text` opens in text mode, and on Windows it
translated every "\\n" of that again - so each line of the file ended "\\r\\r\\n" for as long as
the auto-start existed. A test that compared strings would have passed through all of it.

`newline=""` reads as a small thing to leave out, which is why `python/tests/test_conventions.py`
holds the general rule for every write in the repository. This file is about the one file that
rule was found broken in.
"""

from pathlib import Path

from edt_bridge_mcp.server import write_headless_batch

CLI = Path("C:/Program Files/1cedt/1cedtcli.exe")
WORKSPACE = Path("D:/edt-workspace")


def batch(tmp_path, **extra) -> bytes:
    return write_headless_batch(
        tmp_path / "edtbridge-headless.bat", CLI, WORKSPACE, **extra).read_bytes()


def test_every_line_ends_with_one_carriage_return(tmp_path):
    """The defect itself: text mode turned each CRLF into "\\r\\r\\n"."""
    written = batch(tmp_path, token="secret")

    assert b"\r\r" not in written
    assert written.count(b"\r\n") == written.count(b"\n") == 3


def test_the_file_is_the_keepalive_recipe(tmp_path):
    """One command down the pipe and a ping that never ends - what holds the session open."""
    written = batch(tmp_path).decode("ascii")

    assert written.startswith("@echo off\r\n")
    assert written.endswith(
        f'(echo version& ping -n 999999 127.0.0.1 >nul) | "{CLI}" -data "{WORKSPACE}" '
        f'-nl en_US\r\n'
    )


def test_the_secrets_travel_as_set_lines_and_only_when_there_are_any(tmp_path):
    """An empty value writes no line: `set "X="` would hand EDT an empty string, not nothing."""
    assert b"set" not in batch(tmp_path, token="", allow_evaluate="   ")

    written = batch(tmp_path, token="secret", allow_evaluate=" 1 ").decode("ascii")

    assert 'set "EDT_BRIDGE_TOKEN=secret"\r\n' in written
    assert 'set "EDT_BRIDGE_ALLOW_EVALUATE=1"\r\n' in written
