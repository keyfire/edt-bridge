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


# -- wrapper plugins ---------------------------------------------------------------------------


class _Dist:
    """A distribution as `_plugin_source` sees one: name, version, readable direct_url.json."""

    def __init__(self, name, version, direct_url=None):
        self.name = name
        self.version = version
        self._direct = direct_url

    def read_text(self, filename):
        if filename == "direct_url.json" and self._direct is not None:
            import json as _json
            return _json.dumps(self._direct)
        return None


def test_plugin_source_prefers_the_git_origin():
    dist = _Dist("demo-plugin", "0.1.0", {
        "url": "ssh://git@example.invalid/team/demo-plugin.git",
        "vcs_info": {"vcs": "git", "commit_id": "abc"},
    })
    assert update._plugin_source(dist) == "git+ssh://git@example.invalid/team/demo-plugin.git"


def test_plugin_source_refuses_a_local_directory_install():
    dist = _Dist("demo-plugin", "0.1.0", {"url": "file:///work/demo", "dir_info": {}})
    assert update._plugin_source(dist) is None


def test_plugin_source_falls_back_to_the_project_name():
    assert update._plugin_source(_Dist("demo-plugin", "0.1.0")) == "demo-plugin"


def test_installed_version_reads_dist_info_from_disk(tmp_path):
    (tmp_path / "demo_plugin-0.2.0.dist-info").mkdir()
    assert update._installed_version(tmp_path, "demo-plugin") == "0.2.0"
    assert update._installed_version(tmp_path, "other") == ""


def _plugins_site(tmp_path):
    """A site-packages inside a venv shape, so `_pip_command` has a parent to look at."""
    site = tmp_path / "venv" / "Lib" / "site-packages"
    site.mkdir(parents=True)
    return site


def test_update_plugins_runs_pip_against_the_git_origin(tmp_path, monkeypatch):
    site = _plugins_site(tmp_path)
    dist = _Dist("demo-plugin", "0.1.0", {
        "url": "https://example.invalid/team/demo-plugin.git",
        "vcs_info": {"vcs": "git", "commit_id": "abc"},
    })
    monkeypatch.setattr(update, "_site_packages", lambda: site)
    monkeypatch.setattr(update, "_ensure_regular_install", lambda _site: None)
    monkeypatch.setattr(update, "_plugin_distributions", lambda: [dist])
    monkeypatch.setattr(update, "_pip_command", lambda _site: ["pip"])
    calls = []

    def fake_run(argv, **_kwargs):
        calls.append(argv)
        (site / "demo_plugin-0.2.0.dist-info").mkdir()

        class Done:
            returncode = 0
            stdout = stderr = ""
        return Done()

    monkeypatch.setattr(update.subprocess, "run", fake_run)
    lines = []

    assert update.update_plugins(emit=lines.append) is True
    assert calls == [["pip", "install", "--upgrade", "--no-deps",
                      "git+https://example.invalid/team/demo-plugin.git"]]
    assert any("0.1.0 -> 0.2.0" in line for line in lines)


def test_update_plugins_names_the_index_for_registry_installs(tmp_path, monkeypatch):
    site = _plugins_site(tmp_path)
    monkeypatch.setattr(update, "_site_packages", lambda: site)
    monkeypatch.setattr(update, "_ensure_regular_install", lambda _site: None)
    monkeypatch.setattr(update, "_plugin_distributions", lambda: [_Dist("demo-plugin", "0.1.0")])
    monkeypatch.setattr(update, "_pip_command", lambda _site: ["pip"])
    monkeypatch.setenv(update.PLUGIN_INDEX_ENV, "https://index.invalid/simple")
    calls = []

    def fake_run(argv, **_kwargs):
        calls.append(argv)

        class Done:
            returncode = 0
            stdout = stderr = ""
        return Done()

    monkeypatch.setattr(update.subprocess, "run", fake_run)

    assert update.update_plugins(emit=lambda _line: None) is True
    assert calls == [["pip", "install", "--upgrade", "--no-deps",
                      "--index-url", "https://index.invalid/simple", "demo-plugin"]]


def test_update_plugins_reports_a_failing_pip(tmp_path, monkeypatch):
    site = _plugins_site(tmp_path)
    monkeypatch.setattr(update, "_site_packages", lambda: site)
    monkeypatch.setattr(update, "_ensure_regular_install", lambda _site: None)
    monkeypatch.setattr(update, "_plugin_distributions", lambda: [_Dist("demo-plugin", "0.1.0")])
    monkeypatch.setattr(update, "_pip_command", lambda _site: ["pip"])

    def fake_run(_argv, **_kwargs):
        class Failed:
            returncode = 1
            stdout = ""
            stderr = "ERROR: No matching distribution found for demo-plugin"
        return Failed()

    monkeypatch.setattr(update.subprocess, "run", fake_run)
    lines = []

    assert update.update_plugins(emit=lines.append) is False
    assert any("No matching distribution" in line for line in lines)


def test_update_plugins_without_plugins_is_a_no_op(tmp_path, monkeypatch):
    site = _plugins_site(tmp_path)
    monkeypatch.setattr(update, "_site_packages", lambda: site)
    monkeypatch.setattr(update, "_ensure_regular_install", lambda _site: None)
    monkeypatch.setattr(update, "_plugin_distributions", lambda: [])
    lines = []

    assert update.update_plugins(emit=lines.append) is True
    assert any("nothing to update" in line for line in lines)


def test_pip_command_prefers_pipx_runpip_for_a_pipx_venv(tmp_path, monkeypatch):
    site = _plugins_site(tmp_path)
    (site.parent.parent / "pipx_metadata.json").write_text("{}", encoding="utf-8")
    monkeypatch.setattr(update.shutil, "which", lambda name: "C:/pipx" if name == "pipx" else None)
    assert update._pip_command(site) == ["pipx", "runpip", "edt-bridge-mcp", "--"]


def test_pip_command_falls_back_to_the_venv_python(tmp_path, monkeypatch):
    site = _plugins_site(tmp_path)
    scripts = site.parent.parent / ("Scripts" if update.os.name == "nt" else "bin")
    scripts.mkdir()
    python = scripts / ("python.exe" if update.os.name == "nt" else "python")
    python.write_bytes(b"")
    monkeypatch.setattr(update.shutil, "which", lambda _name: None)
    command = update._pip_command(site)
    assert command is not None and command[1:] == ["-m", "pip"]
