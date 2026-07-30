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
    monkeypatch.setattr(update, "_simple_files", list)  # the index is out of the picture here
    monkeypatch.setattr(update, "_fetch_json", lambda _url: payload)

    assert update._wheel_url(None) == ("https://pypi/wheel", "0.6.0")


def test_missing_wheel_is_reported(monkeypatch):
    monkeypatch.setattr(update, "_simple_files", list)
    monkeypatch.setattr(update, "_fetch_json", lambda _url: {
        "info": {"version": "0.6.0"},
        "urls": [{"filename": "edt_bridge_mcp-0.6.0.tar.gz", "url": "https://pypi/sdist"}],
    })

    with pytest.raises(update._UpdateError, match="no wheel"):
        update._wheel_url(None)


# -- the file list comes from the simple index -----------------------------------
#
# Ported from the toolkit engine and elemctl, where the failure was caught live on 31.07.2026:
# right after a release the JSON metadata of PyPI still answered with the previous version, so
# `self-update` said "already current" - and with an explicit version, "no wheel", because the
# file list was read from that same lagging document. Only the WRAPPER half is affected; the jar
# comes from the assets of a GitHub release.

import json as _json  # noqa: E402 - the payload of the index is built here only


def _simple_payload(*names: str, yanked: tuple[str, ...] = ()) -> bytes:
    return _json.dumps({
        "meta": {"api-version": "1.1"},
        "files": [
            {"filename": name, "url": f"https://pypi/{name}", "yanked": name in yanked}
            for name in names
        ],
    }).encode("utf-8")


class _FakeResp:
    def __init__(self, data):
        self._data = data

    def read(self):
        return self._data

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def _serve(monkeypatch, index: bytes | None) -> list[str]:
    """Answer the simple index; the JSON metadata must not be asked. Returns the asked urls."""
    asked: list[str] = []

    def urlopen(target, timeout=0):
        url = getattr(target, "full_url", target)
        asked.append(url)
        assert url == update.PYPI_SIMPLE, "the JSON metadata must not be asked at all"
        accept = getattr(target, "headers", {}).get("Accept")
        assert accept == update.SIMPLE_ACCEPT, "without the header the index answers HTML"
        if index is None:
            raise OSError("index unreachable")
        return _FakeResp(index)

    monkeypatch.setattr(update.urllib.request, "urlopen", urlopen)
    return asked


def test_wheel_url_reads_the_simple_index(monkeypatch):
    asked = _serve(monkeypatch, _simple_payload(
        "edt_bridge_mcp-0.11.0-py3-none-any.whl",
        "edt_bridge_mcp-0.11.1-py3-none-any.whl",
        "edt_bridge_mcp-0.11.1.tar.gz",
    ))

    url, version = update._wheel_url(None)

    assert version == "0.11.1" and url.endswith("edt_bridge_mcp-0.11.1-py3-none-any.whl")
    assert asked == [update.PYPI_SIMPLE]


def test_a_fresh_release_is_installable_while_the_json_still_lags(monkeypatch):
    """The very failure this port exists for - and the JSON is never even asked."""
    asked = _serve(monkeypatch, _simple_payload("edt_bridge_mcp-0.11.1-py3-none-any.whl"))

    url, version = update._wheel_url("0.11.1")

    assert version == "0.11.1" and url.endswith("edt_bridge_mcp-0.11.1-py3-none-any.whl")
    assert asked == [update.PYPI_SIMPLE]


def test_yanked_and_pre_release_files_never_win_the_latest_race(monkeypatch):
    _serve(monkeypatch, _simple_payload(
        "edt_bridge_mcp-0.11.0-py3-none-any.whl",
        "edt_bridge_mcp-0.11.1-py3-none-any.whl",
        "edt_bridge_mcp-0.12.0rc1-py3-none-any.whl",
        yanked=("edt_bridge_mcp-0.11.1-py3-none-any.whl",),
    ))
    assert update._wheel_url(None)[1] == "0.11.0"


def test_release_ranking_is_numeric_not_lexicographic():
    files = [{"filename": f"edt_bridge_mcp-{v}-py3-none-any.whl", "version": v}
             for v in ("0.9.0", "0.11.1", "0.11.1.post1")]
    assert update._latest_release(files) == "0.11.1.post1"
    assert update._release_key("0.12.0rc1") is None
    assert update._version_of("edt_bridge_mcp-0.11.1.tar.gz") == "0.11.1"


def test_a_version_the_index_does_not_carry_is_named_as_such(monkeypatch):
    """A readable index is the answer: no second guess at the lagging JSON."""
    _serve(monkeypatch, _simple_payload("edt_bridge_mcp-0.11.1-py3-none-any.whl"))
    with pytest.raises(update._UpdateError, match="no such version"):
        update._wheel_url("9.9.9")


# -- установка из рабочей копии -------------------------------------------------


def _checkout(root, relative):
    """Рабочая копия, где пакет лежит по указанному пути."""
    package = root / relative
    package.mkdir(parents=True)
    (package / "__init__.py").write_text('__version__ = "9.9.9"\n', encoding="utf-8")
    return package


@pytest.mark.parametrize("relative", ["src/edt_bridge_mcp",
                                      "edt_bridge_mcp",
                                      "python/src/edt_bridge_mcp"])
def test_a_checkout_is_found_by_any_of_its_shapes(tmp_path, relative):
    """Корень РЕПОЗИТОРИЯ - то, что передают естественно, а обвязка живёт в его python/.
    Требование этого суффикса превращало очевидную команду во вторую попытку."""
    _checkout(tmp_path / "repo", relative)
    site = tmp_path / "site"
    site.mkdir()
    assert update._install_from_checkout(site, str(tmp_path / "repo")) == "9.9.9"
    assert (site / "edt_bridge_mcp" / "__init__.py").exists()


def test_a_directory_without_the_package_names_where_it_looked(tmp_path):
    site = tmp_path / "site"
    site.mkdir()
    (tmp_path / "repo").mkdir()
    with pytest.raises(update._UpdateError) as error:
        update._install_from_checkout(site, str(tmp_path / "repo"))
    assert "python" in str(error.value) and "src" in str(error.value)
