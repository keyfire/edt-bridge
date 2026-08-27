"""The release-notes extractor: the changelog section that becomes the release body.

The defect classes guarded here are the quiet ones. A version token matching inside a
longer version (`0.17.0` inside `10.17.0`) would ship a wrong day's notes; a section
that does not stop at the next day heading would ship the whole history; and a version
absent from the changelog must refuse loudly - the workflow downgrades that to a
warning, but only because it knows, not because the script kept silent.
"""

import importlib.util
import subprocess
import sys
from pathlib import Path

from edt_bridge_mcp import __version__

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release-notes.py"

spec = importlib.util.spec_from_file_location("relnotes", SCRIPT)
relnotes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relnotes)

SAMPLE = """# Changelog

Intro prose.

## 2026-08-27 - 0.17.0

### Added
- The wrapper takes plugins.

## 2026-08-23 - 0.15.0, 0.16.0

### Added
- Form handlers.

## 2026-08-17 - 10.17.0

### Fixed
- A decoy version that must never match 0.17.0.
"""


def test_extracts_one_section_without_its_heading():
    section = relnotes.extract(SAMPLE, "0.17.0")
    assert section.startswith("### Added")
    assert "plugins" in section
    assert "Form handlers" not in section, "the section must stop at the next day heading"
    assert "## 2026-08-27" not in section, "the heading duplicates the release title"


def test_a_multi_version_day_serves_either_version():
    for version in ("0.15.0", "0.16.0"):
        assert "Form handlers" in relnotes.extract(SAMPLE, version)


def test_the_version_token_does_not_match_inside_a_longer_one():
    assert "decoy" in relnotes.extract(SAMPLE, "10.17.0")
    assert "decoy" not in relnotes.extract(SAMPLE, "0.17.0")
    assert relnotes.extract("## 1.2.3.4\n\ntext\n", "1.2.3") is None


def test_a_version_only_heading_works_too():
    # the VS Code extension's changelog names the version alone
    assert "solo" in relnotes.extract("## 0.67.1\n\n- solo entry\n", "0.67.1")


def test_a_missing_version_answers_none_and_exit_1(tmp_path):
    assert relnotes.extract(SAMPLE, "9.9.9") is None
    changelog = tmp_path / "CHANGELOG.md"
    changelog.write_text(SAMPLE, encoding="utf-8")
    run = subprocess.run(
        [sys.executable, str(SCRIPT), "9.9.9", str(changelog)],
        capture_output=True, text=True, encoding="utf-8", timeout=30,
    )
    assert run.returncode == 1
    assert "9.9.9" in run.stderr


def test_out_writes_the_section_as_utf8(tmp_path):
    changelog = tmp_path / "CHANGELOG.md"
    changelog.write_text(SAMPLE, encoding="utf-8")
    out = tmp_path / "notes.md"
    run = subprocess.run(
        [sys.executable, str(SCRIPT), "0.17.0", str(changelog), "--out", str(out)],
        capture_output=True, text=True, encoding="utf-8", timeout=30,
    )
    assert run.returncode == 0
    assert "plugins" in out.read_text(encoding="utf-8")


def test_the_real_changelog_carries_the_current_version():
    # the release workflow will ask for exactly this pair on the next tag
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    section = relnotes.extract(text, __version__)
    assert section, f"CHANGELOG.md names no section for {__version__}"
    assert "###" in section
