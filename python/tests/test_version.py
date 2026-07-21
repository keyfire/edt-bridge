"""The wrapper version must be the one the package metadata reports.

0.5.0 fixed a two-release drift: `pyproject.toml` did not derive the version from the literal in
`__init__.py`, so `--version` and the MCP serverInfo both went on claiming 0.3.1 while the code had
long moved on. The plugin jar and the wrapper share one number, which makes a silent drift worse
than cosmetic - it is how you end up debugging the wrong build.
"""
import re
from pathlib import Path

import pytest

from edt_bridge_mcp import __version__

PYPROJECT = Path(__file__).resolve().parent.parent / "pyproject.toml"


def test_version_literal_is_a_release_number():
    assert re.fullmatch(r"\d+\.\d+\.\d+", __version__), __version__


def test_pyproject_derives_the_version_from_the_package():
    """The single source of truth: setuptools must read the literal, not carry its own copy."""
    text = PYPROJECT.read_text(encoding="utf-8")
    assert 'dynamic = ["version"]' in text
    assert 'attr = "edt_bridge_mcp.__version__"' in text
    assert not re.search(r'^\s*version\s*=\s*"', text, re.MULTILINE), \
        "a literal version in pyproject.toml is exactly the drift this guards against"


def test_installed_metadata_matches_the_literal():
    metadata = pytest.importorskip("importlib.metadata")
    try:
        installed = metadata.version("edt-bridge-mcp")
    except metadata.PackageNotFoundError:
        pytest.skip("edt-bridge-mcp is not installed in this environment")
    assert installed == __version__
