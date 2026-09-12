"""The step that writes a pull request link into the changelog and rebuilds the mirrors.

The link is written into two editions at once, one of them a directory away, and the changelog
is wrapped to a width - so an entry that is already long takes the link on a line of its own.
That is the work the script exists to do the same way every time, and it is what the tests ask
for: the link where it belongs and nowhere else, and the rebuild called on every run.
"""

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "changelog-link.py"

spec = importlib.util.spec_from_file_location("changelog_link", SCRIPT)
changelog_link = importlib.util.module_from_spec(spec)
spec.loader.exec_module(changelog_link)


CHANGELOG = """# Changelog

Notable changes, newest first.

## 2026-09-12 – 0.27.1

### Added
- **A short entry.** Nothing to see here.

### Fixed
- **An entry that is already linked.** It came from an earlier pull request.
  ([#3](https://github.com/keyfire/edt-bridge/pull/3))

## 2026-09-11 – 0.27.0

### Added
- **An entry of a released day.** Its link was written long ago.
  ([#5](https://github.com/keyfire/edt-bridge/pull/5))
- **A released entry nobody linked.** From before the rule - history, not an unfinished change.
"""


def linked(text, number=16):
    return changelog_link.add_link(text, number, "keyfire/edt-bridge")


def test_the_link_goes_to_the_link_less_entries_of_the_topmost_section():
    text, added = linked(CHANGELOG)

    assert added == 1
    assert "- **A short entry.** Nothing to see here. " \
           "([#16](https://github.com/keyfire/edt-bridge/pull/16))" in text


def test_an_entry_that_already_carries_a_link_is_left_alone():
    """A second link would say the change came from two pull requests."""
    text, _ = linked(CHANGELOG)

    assert text.count("/pull/16") == 1
    assert "/pull/3)" in text


def test_a_released_section_is_history_and_stays_untouched():
    """Entries from before the pull request rule are not unfinished - they are the past."""
    text, _ = linked(CHANGELOG)

    assert "- **A released entry nobody linked.** From before the rule - history, not an " \
           "unfinished change.\n" in text


def test_a_long_entry_takes_the_link_on_a_line_of_its_own():
    """The changelog is wrapped to a width, and an appended link is 54 characters of it."""
    long_entry = CHANGELOG.replace(
        "- **A short entry.** Nothing to see here.",
        "- **A long entry.** It runs over two lines, the way a real one does, and the\n"
        "  second line ends far enough to the right that no link fits after it.",
    )

    text, added = linked(long_entry)

    assert added == 1
    assert "\n  ([#16](https://github.com/keyfire/edt-bridge/pull/16))\n" in text
    assert all(len(line) <= changelog_link.WIDTH for line in text.split("\n"))


def test_both_editions_are_linked_and_the_mirrors_rebuilt(tmp_path, monkeypatch):
    """One run covers the whole step - that is the point of having the script at all."""
    for name in changelog_link.EDITIONS:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(CHANGELOG, encoding="utf-8", newline="")
    calls = []

    def fake_run(command, **kwargs):
        calls.append((command, kwargs))
        return type("Done", (), {"returncode": 0, "stdout": "CHANGELOG.md -> docs/changelog.md",
                                 "stderr": ""})()

    monkeypatch.setattr(changelog_link, "ROOT", tmp_path)

    assert changelog_link.main(["16"], run=fake_run) == 0

    for name in changelog_link.EDITIONS:
        assert "/pull/16" in (tmp_path / name).read_text(encoding="utf-8")
    assert [command for command, _ in calls] == [["node", "scripts/sync-docs.mjs"]]
    assert calls[0][1]["cwd"] == str(tmp_path)
    # The generator names the Russian pages it writes; with the system code page the reader
    # thread dies on the first Cyrillic byte and the output vanishes, exit code 0 and all
    assert calls[0][1]["encoding"] == "utf-8"


def test_the_editions_keep_their_line_endings(tmp_path, monkeypatch):
    """The whole file is rewritten, and only one line of it was edited."""
    for name in changelog_link.EDITIONS:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(CHANGELOG, encoding="utf-8", newline="")

    changelog_link.write_links(tmp_path, 16, "keyfire/edt-bridge")

    for name in changelog_link.EDITIONS:
        assert b"\r\n" not in (tmp_path / name).read_bytes()


def test_a_rebuild_that_did_not_happen_is_an_error(tmp_path, monkeypatch):
    """A missing node must not look like a finished step."""
    for name in changelog_link.EDITIONS:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(CHANGELOG, encoding="utf-8", newline="")

    def fake_run(command, **kwargs):
        raise OSError("node not found")

    monkeypatch.setattr(changelog_link, "ROOT", tmp_path)

    assert changelog_link.main(["16"], run=fake_run) == 1


def test_a_generator_that_failed_is_an_error_too(tmp_path, monkeypatch):
    """An exit code nobody looked at is how a half-finished tree gets committed."""
    for name in changelog_link.EDITIONS:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(CHANGELOG, encoding="utf-8", newline="")

    def fake_run(command, **kwargs):
        return type("Done", (), {"returncode": 1, "stdout": "", "stderr": "markers not found"})()

    monkeypatch.setattr(changelog_link, "ROOT", tmp_path)

    assert changelog_link.main(["16"], run=fake_run) == 1


def test_the_real_editions_are_where_the_script_looks_for_them():
    """A renamed edition would leave the script linking nothing and saying so cheerfully."""
    for name in changelog_link.EDITIONS:
        assert (ROOT / name).is_file(), name
