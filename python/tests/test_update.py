"""Self-update: jar hygiene in dropins, wheel selection, and the editable-install guard.

Two dropins copies of the same bundle make Equinox resolve an arbitrary one - that is how a stale
jar silently keeps serving old code next to a freshly delivered one, which cost a debugging session
before `purge_stale_jars` existed. The editable guard matters just as much: unpacking a wheel over a
checkout would wreck the repository.
"""
import pytest

from edt_bridge_mcp import update

_JARS = (
    "io.github.keyfire.edtbridge_0.5.0.202607192303.jar",
    "io.github.keyfire.edtbridge_0.6.0.202607210146.jar",
    "io.github.keyfire.edtbridge_0.6.0.202607211008.jar",
)


def _dropins(tmp_path, names):
    for name in names:
        (tmp_path / name).write_bytes(b"jar")
    return tmp_path


def test_purge_keeps_only_the_newest_jar(tmp_path):
    dropins = _dropins(tmp_path, _JARS)

    removed = update.purge_stale_jars(dropins, emit=lambda _msg: None)

    assert removed == 2
    assert [p.name for p in dropins.glob("*.jar")] == [_JARS[-1]]


def test_purge_leaves_a_single_jar_alone(tmp_path):
    dropins = _dropins(tmp_path, _JARS[-1:])
    assert update.purge_stale_jars(dropins, emit=lambda _msg: None) == 0
    assert [p.name for p in dropins.glob("*.jar")] == [_JARS[-1]]


def test_install_purges_even_when_the_release_jar_is_already_there(tmp_path, monkeypatch):
    """The early return used to skip the purge, so a hand-built jar stayed next to the release one
    for good - and two copies of the same bundle are what makes Equinox load an arbitrary one."""
    release = _JARS[-1]
    dropins = _dropins(tmp_path, (_JARS[0], release))
    monkeypatch.setattr(update, "_fetch_json", lambda _url: {
        "tag_name": "v9.9.9",
        "assets": [{"name": release, "browser_download_url": "https://example.invalid/jar", "size": 3}],
    })

    assert update.install_latest_jar(dropins, emit=lambda _msg: None) is True
    assert [p.name for p in dropins.glob("*.jar")] == [release]


def test_purge_ignores_foreign_jars(tmp_path):
    """Other plugins live in dropins too - only our own bundle may be touched."""
    dropins = _dropins(tmp_path, (_JARS[0], _JARS[-1], "com.example.other_1.0.0.jar"))

    update.purge_stale_jars(dropins, emit=lambda _msg: None)

    assert (dropins / "com.example.other_1.0.0.jar").exists()


def test_purge_is_a_noop_without_dropins():
    assert update.purge_stale_jars(None, emit=lambda _msg: None) == 0


def test_has_jar_reports_an_empty_dropins(tmp_path):
    assert update.has_jar(tmp_path) is False
    assert update.has_jar(_dropins(tmp_path, _JARS[-1:])) is True


def test_editable_install_is_refused(tmp_path):
    """An editable install points at a checkout - updating it is git's job, not the unpacker's."""
    with pytest.raises(update._UpdateError, match="editable install"):
        update._ensure_regular_install(tmp_path / "src")


@pytest.mark.parametrize("name", ["site-packages", "dist-packages"])
def test_a_regular_install_passes(tmp_path, name):
    site = tmp_path / name
    site.mkdir()
    update._ensure_regular_install(site)  # must not raise


def test_wheel_url_picks_the_universal_wheel(monkeypatch):
    payload = {
        "info": {"version": "0.6.0"},
        "urls": [
            {"filename": "edt_bridge_mcp-0.6.0.tar.gz", "url": "https://pypi/sdist"},
            {"filename": "edt_bridge_mcp-0.6.0-py3-none-any.whl", "url": "https://pypi/wheel"},
        ],
    }
    monkeypatch.setattr(update, "_fetch_json", lambda _url: payload)

    assert update._wheel_url(None) == ("https://pypi/wheel", "0.6.0")


def test_missing_wheel_is_reported(monkeypatch):
    monkeypatch.setattr(update, "_fetch_json", lambda _url: {
        "info": {"version": "0.6.0"},
        "urls": [{"filename": "edt_bridge_mcp-0.6.0.tar.gz", "url": "https://pypi/sdist"}],
    })

    with pytest.raises(update._UpdateError, match="no wheel"):
        update._wheel_url(None)
