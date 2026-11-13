"""Self-update: jar hygiene in dropins, wheel selection, and the editable-install guard.

Two dropins copies of the same bundle make Equinox resolve an arbitrary one - that is how a stale
jar silently keeps serving old code next to a freshly delivered one, which cost a debugging session
before `purge_stale_jars` existed. The editable guard matters just as much: unpacking a wheel over a
checkout would wreck the repository.
"""
from hashlib import sha256
from io import BytesIO
from zipfile import ZipFile


import os

import pytest

from edt_bridge_mcp import server, update

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


def test_a_yanked_wheel_in_simple_takes_precedence_over_stale_version_json(monkeypatch):
    filename = "edt_bridge_mcp-0.11.2-py3-none-any.whl"
    _serve(monkeypatch, _simple_payload(filename, yanked=(filename,)))

    def fetch_json(url):
        return {
            "info": {"version": "0.11.2"},
            "urls": [{"filename": filename, "url": "https://example.invalid/wheel", "yanked": False}],
        }

    monkeypatch.setattr(update, "_fetch_json", fetch_json)
    with pytest.raises(update._UpdateError, match="yanked"):
        update._wheel_url("0.11.2")


@pytest.mark.parametrize("yanked", [False, True])
def test_requested_version_falls_back_to_version_json_if_missing_from_simple(monkeypatch, yanked):
    _serve(monkeypatch, _simple_payload("edt_bridge_mcp-0.11.1-py3-none-any.whl"))
    wheel_url = "https://example.invalid/edt_bridge_mcp-0.11.2-py3-none-any.whl"

    def fetch_json(url):
        assert url == "https://pypi.org/pypi/edt-bridge-mcp/0.11.2/json"
        return {
            "info": {"version": "0.11.2"},
            "urls": [{"filename": "edt_bridge_mcp-0.11.2-py3-none-any.whl",
                      "url": wheel_url, "yanked": yanked}],
        }

    monkeypatch.setattr(update, "_fetch_json", fetch_json)
    if yanked:
        with pytest.raises(update._UpdateError, match="no wheel"):
            update._wheel_url("0.11.2")
    else:
        assert update._wheel_url("0.11.2") == (wheel_url, "0.11.2")


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


# -- the verdict: a partial failure has to be told apart from a total one --------
#
# Reported after the 0.22.0 release: the jar and the wrapper both reached 0.22.0 and only the
# plugin step failed, on a source host that was unreachable - and the call exited 1, exactly
# like a run that updated nothing at all.


def test_a_failed_addon_does_not_read_like_a_failed_update():
    lines = []
    code = update.verdict(
        [("plugin jar", True), ("wrapper", True), ("wrapper plugins", False)], emit=lines.append)

    assert code == update.EXIT_ADDON_FAILED
    assert any("wrapper plugins" in line and "failed" in line for line in lines)
    assert any("up to date" in line for line in lines)


def test_a_failed_core_step_is_still_a_failure():
    for steps in (
        [("plugin jar", False), ("wrapper", True), ("wrapper plugins", True)],
        [("plugin jar", True), ("wrapper", False), ("wrapper plugins", True)],
        [("plugin jar", False), ("wrapper", False), ("wrapper plugins", False)],
    ):
        assert update.verdict(steps, emit=lambda _msg: None) == 1


def test_a_lone_step_that_failed_is_not_a_partial_success():
    # --plugins-only: the plugins were the whole job, so there is no "core is fine" to report
    assert update.verdict([("wrapper plugins", False)], emit=lambda _msg: None) == 1


def test_everything_succeeding_is_zero_and_says_so():
    lines = []
    assert update.verdict([("plugin jar", True), ("wrapper", True)], emit=lines.append) == 0
    assert any("all done" in line for line in lines)
    assert update.verdict([], emit=lambda _msg: None) == 0


@pytest.mark.parametrize("args", [
    ["--unknown"],
    ["--ver", "0.6.0"],
    ["--version"],
    ["--version", "invalid"],
    ["--from"],
    ["--jar-only", "--pip-only"],
    ["--from", "checkout", "--jar-only"],
    ["--from", "checkout", "--version", "0.6.0"],
    ["--plugins-only", "--version", "0.6.0"],
])
def test_self_update_rejects_invalid_args_before_any_update(args, monkeypatch, capsys):
    monkeypatch.setattr(server, "force_utf8_streams", lambda: None)
    monkeypatch.setattr(server.sys, "argv", ["edt-bridge-mcp", "self-update", *args])
    steps = []

    def record_step(*args, **kwargs):
        steps.append((args, kwargs))
        return True

    for name in ("update_jar", "update_wrapper", "update_plugins"):
        monkeypatch.setattr(update, name, record_step)

    with pytest.raises(SystemExit) as exc:
        server.main()

    assert exc.value.code == 2
    assert "self-update" in capsys.readouterr().err
    assert steps == []


@pytest.mark.parametrize("mode", [[], ["--jar-only"], ["--pip-only"]])
def test_self_update_selects_the_requested_release(mode, monkeypatch, tmp_path):
    dropins = tmp_path / "edt" / "dropins"
    dropins.mkdir(parents=True)
    jar = "io.github.keyfire.edtbridge_0.6.0.202607211008.jar"
    newer = "io.github.keyfire.edtbridge_0.7.0.202608010800.jar"
    _dropins(dropins, [newer])
    if mode == ["--jar-only"]:
        _dropins(dropins, [jar])
    site = _plugins_site(tmp_path)
    wheel_url = "https://pypi/edt_bridge_mcp-0.6.0-py3-none-any.whl"
    jar_bytes = b"release jar"
    wheel = BytesIO()
    with ZipFile(wheel, "w") as archive:
        archive.writestr("edt_bridge_mcp/__init__.py", '__version__ = "0.6.0"\n')
    urls = []

    def urlopen(target, timeout):
        url = getattr(target, "full_url", target)
        urls.append(url)
        if url == "https://api.github.com/repos/keyfire/edt-bridge/releases/tags/v0.6.0":
            return _FakeResp(_json.dumps({
                "tag_name": "v0.6.0",
                "assets": [
                    {"name": jar, "browser_download_url": "https://example.invalid/jar"},
                    {"name": "SHA256SUMS.txt",
                     "browser_download_url": "https://example.invalid/sums"},
                ],
            }).encode("utf-8"))
        if url == update.PYPI_SIMPLE:
            return _FakeResp(_simple_payload(
                "edt_bridge_mcp-0.6.0-py3-none-any.whl",
                "edt_bridge_mcp-0.7.0-py3-none-any.whl",
            ))
        if url == "https://example.invalid/jar":
            return BytesIO(jar_bytes)
        if url == "https://example.invalid/sums":
            checksum = sha256(jar_bytes).hexdigest()
            return BytesIO(f"{checksum}  dist/{jar}\n".encode("utf-8"))
        assert url == wheel_url
        return _FakeResp(wheel.getvalue())

    monkeypatch.setattr(update.urllib.request, "urlopen", urlopen)
    monkeypatch.setattr(update, "_site_packages", lambda: site)
    monkeypatch.setattr(update, "_plugin_distributions", list)
    monkeypatch.setattr(update, "running_bridge_version", lambda: None)
    monkeypatch.setattr(server, "force_utf8_streams", lambda: None)
    monkeypatch.delenv("EDT_BRIDGE_EDT_DIR", raising=False)
    monkeypatch.delenv("EDT_BRIDGE_AUTOSTART", raising=False)
    monkeypatch.setattr(server.sys, "argv", [
        "edt-bridge-mcp", "--no-autostart", "self-update", *mode,
        "--version", "0.6.0", "--edt-dir", str(dropins.parent),
    ])

    assert server.main() == 0

    if mode == ["--pip-only"]:
        assert (dropins / newer).exists()
    else:
        assert [p.name for p in dropins.glob("*.jar")] == [jar]
        assert urls[0].endswith("/tags/v0.6.0")
        assert (dropins / jar).read_bytes() == jar_bytes
    installed = site / "edt_bridge_mcp" / "__init__.py"
    if mode == ["--jar-only"]:
        assert not installed.exists()
    else:
        assert installed.read_text(encoding="utf-8") == '__version__ = "0.6.0"\n'
        assert urls[-1] == wheel_url


@pytest.mark.parametrize("replacement", [None, b"invalid download"])
def test_a_failed_exact_jar_replacement_keeps_the_working_sibling(tmp_path, monkeypatch, replacement):
    target, working = _JARS[0], _JARS[-1]
    dropins = tmp_path / "dropins"
    dropins.mkdir()
    (dropins / target).write_bytes(b"")
    (dropins / working).write_bytes(b"working jar")
    monkeypatch.setattr(update, "_fetch_json", lambda _url: {
        "tag_name": "v0.5.0",
        "assets": [
            {"name": target, "browser_download_url": "https://example.invalid/jar"},
            {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.invalid/sums"},
        ],
    })

    def download(url, destination):
        if url.endswith("/sums"):
            destination.write_text(f"{'0' * 64}  {target}\n", encoding="utf-8", newline="")
        elif replacement is None:
            raise OSError("download unavailable")
        else:
            destination.write_bytes(replacement)

    monkeypatch.setattr(update, "_download", download)

    assert update.install_latest_jar(dropins, version="0.5.0") is False
    assert (dropins / working).read_bytes() == b"working jar"
    assert (dropins / target).read_bytes() == b""


# -- where EDT is installed on Windows -------------------------------------------------


def _windows_roots(monkeypatch, tmp_path):
    local, programs = tmp_path / "local", tmp_path / "programs"
    monkeypatch.setenv("LOCALAPPDATA", str(local))
    monkeypatch.setenv("ProgramFiles", str(programs))
    return (local / "1C" / "1cedtstart" / "installations",
            programs / "1C" / "1CE" / "components")


def _edt(directory):
    directory.mkdir(parents=True)
    (directory / "1cedt.exe").write_bytes(b"")
    (directory / "1cedtcli.exe").write_bytes(b"")
    return directory


def test_windows_installs_compare_versions_as_numbers(monkeypatch, tmp_path):
    _, components = _windows_roots(monkeypatch, tmp_path)
    older = _edt(components / "1c-edt-2026.1.9+1-x86_64")
    newer = _edt(components / "1c-edt-2026.1.10+1-x86_64")

    assert update.windows_installs() == [newer, older]


def test_windows_installs_skip_the_launcher_component(monkeypatch, tmp_path):
    _, components = _windows_roots(monkeypatch, tmp_path)
    (components / "1c-edt-start-0.9.0+277-x86_64").mkdir(parents=True)
    edt = _edt(components / "1c-edt-2026.1.2+2-x86_64")

    assert update.windows_installs() == [edt]


def test_windows_installs_prefer_the_launcher_installations(monkeypatch, tmp_path):
    """What the auto-detection found before 1C:Installer was known keeps being found."""
    installations, components = _windows_roots(monkeypatch, tmp_path)
    component = _edt(components / "1c-edt-2026.1.2+2-x86_64")
    launcher = _edt(installations / "2025.2.0" / "1cedt")

    assert update.windows_installs() == [launcher, component]


def test_windows_installs_without_the_variables_look_nowhere(monkeypatch):
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    monkeypatch.delenv("ProgramFiles", raising=False)

    assert update.windows_installs() == []


@pytest.mark.skipif(os.name != "nt", reason="the Windows branch runs only on Windows")
def test_dropins_and_executables_are_found_in_a_component(monkeypatch, tmp_path):
    """The reported install: no `1cedt` subfolder, and `dropins` right in the component."""
    monkeypatch.delenv("EDT_BRIDGE_EDT_DIR", raising=False)
    _, components = _windows_roots(monkeypatch, tmp_path)
    (components / "1c-edt-start-0.9.0+277-x86_64").mkdir(parents=True)
    edt = _edt(components / "1c-edt-2026.1.2+2-x86_64")

    assert update._find_dropins() == edt / "dropins"
    assert server.Backend()._find_cli() == edt / "1cedtcli.exe"


# -- no write access to dropins ----------------------------------------------------------


def _latest_release(monkeypatch):
    jar = update.JAR_PREFIX + "0.34.1.202611030809.jar"
    monkeypatch.setattr(update, "_fetch_json", lambda _url: {
        "tag_name": "v0.34.1",
        "assets": [{"name": jar, "size": 1, "browser_download_url": "https://example.invalid/jar"}],
    })
    monkeypatch.setattr(update, "_download", lambda _url, target: target.write_bytes(b"jar"))


def test_a_denied_write_into_dropins_names_the_cure(monkeypatch, tmp_path):
    _latest_release(monkeypatch)

    def denied(_self, _target):
        raise PermissionError(13, "Access is denied")

    monkeypatch.setattr(update.Path, "replace", denied)
    lines = []

    assert update.install_latest_jar(tmp_path, emit=lines.append) is False
    assert "cannot place the jar into dropins" in lines[-1]
    assert ("as administrator" in lines[-1]) == (os.name == "nt")


def test_a_denied_mkdir_of_dropins_is_reported_not_raised(monkeypatch, tmp_path):
    _latest_release(monkeypatch)

    def denied(*_args, **_kwargs):
        raise PermissionError(13, "Access is denied")

    monkeypatch.setattr(update.Path, "mkdir", denied)
    lines = []

    assert update.install_latest_jar(tmp_path / "dropins", emit=lines.append) is False
    assert ("as administrator" in lines[-1]) == (os.name == "nt")


def test_other_placement_failures_do_not_blame_the_rights():
    assert "administrator" not in update._placement_failure(OSError("the disk is full"))


def test_the_auto_start_reports_why_the_jar_was_not_delivered(monkeypatch, tmp_path):
    """It used to blame a download from GitHub for whatever went wrong."""
    def refused(_dropins, emit):
        emit("cannot place the jar into dropins: denied")
        return False

    monkeypatch.setattr(update, "install_latest_jar", refused)

    ok, message = server.Backend()._ensure_plugin_jar(tmp_path)

    assert ok is False
    assert "cannot place the jar into dropins" in message
    assert "GitHub" not in message


# -- the plugin and the wrapper on one version --------------------------------------------


@pytest.mark.parametrize("live", [update.__version__, None, ""])
def test_no_gap_for_the_same_version_or_an_unknown_one(live):
    assert update.plugin_version_gap(live) is None


def test_a_wrapper_post_release_belongs_to_its_plugin(monkeypatch):
    monkeypatch.setattr(update, "__version__", "0.34.1.post1")

    assert update.plugin_version_gap("0.34.1") is None


def test_a_gap_names_both_versions(monkeypatch):
    monkeypatch.setattr(update, "__version__", "0.34.1")

    gap = update.plugin_version_gap("0.11.2")

    assert "0.11.2" in gap and "0.34.1" in gap and "self-update" in gap


def test_the_gap_is_said_once_and_only_on_stderr(monkeypatch, capsys):
    monkeypatch.setattr(update, "__version__", "0.34.1")
    backend = server.Backend()
    monkeypatch.setattr(backend, "_target_status", lambda _port: {"version": "0.11.2"})

    backend.status()
    backend.status()

    captured = capsys.readouterr()
    assert captured.err.count("0.11.2") == 1
    assert captured.out == ""


def test_windows_installs_orders_build_numbers_without_architecture(monkeypatch, tmp_path):
    _, components = _windows_roots(monkeypatch, tmp_path)
    bare = _edt(components / "1c-edt-2026.1.2-x86_64")
    old_build = _edt(components / "1c-edt-2026.1.2+9-x86_64")
    new_build = _edt(components / "1c-edt-2026.1.2+10-x86_64")

    assert update.windows_installs() == [new_build, old_build, bare]


def test_windows_installs_order_versions_in_launcher_directory_names(monkeypatch, tmp_path):
    installations, _ = _windows_roots(monkeypatch, tmp_path)
    older = _edt(installations / "1C_EDT (Lite) 2026.1.9" / "1cedt")
    newer = _edt(installations / "1C_EDT (Lite) 2026.1.10" / "1cedt")

    assert update.windows_installs() == [newer, older]


def _build_id(edt_dir, build_id):
    (edt_dir / "configuration").mkdir(parents=True, exist_ok=True)
    (edt_dir / "configuration" / "config.ini").write_text(
        f"#written by the launcher\neclipse.buildId={build_id}\nproduct.version=ignored\n",
        encoding="utf-8")
    return edt_dir


def test_windows_installs_trust_the_build_over_a_short_directory_name(monkeypatch, tmp_path):
    """The launcher keeps a release line in one directory and updates it in place: "1C_EDT 2026.2"
    holds 2026.2.2 and must not lose to "1C_EDT (Lite) 2026.2.0" because its name is shorter."""
    installations, _ = _windows_roots(monkeypatch, tmp_path)
    lite = _build_id(_edt(installations / "1C_EDT (Lite) 2026.2.0" / "1cedt"), "2026.2.0.289")
    full = _build_id(_edt(installations / "1C_EDT 2026.2" / "1cedt"), "2026.2.2.21")

    assert update.windows_installs() == [full, lite]


def test_windows_installs_read_the_build_of_a_component(monkeypatch, tmp_path):
    _, components = _windows_roots(monkeypatch, tmp_path)
    named_newer = _build_id(_edt(components / "1c-edt-2026.1.9+1-x86_64"), "2026.1.2.5")
    named_older = _build_id(_edt(components / "1c-edt-2026.1.2+2-x86_64"), "2026.1.10.3")

    assert update.windows_installs() == [named_older, named_newer]


def test_windows_installs_fall_back_to_the_name_on_an_unreadable_build(monkeypatch, tmp_path):
    installations, _ = _windows_roots(monkeypatch, tmp_path)
    older = _build_id(_edt(installations / "1C_EDT (Lite) 2026.1.9" / "1cedt"), "${build}")
    newer = _edt(installations / "1C_EDT (Lite) 2026.1.10" / "1cedt")

    assert update.windows_installs() == [newer, older]


def _jdk(home, version):
    (home / "bin").mkdir(parents=True)
    for name in ("java", "javaw", "java.exe", "javaw.exe"):
        (home / "bin" / name).write_bytes(b"")
        # shutil.which on POSIX finds only an executable file.
        (home / "bin" / name).chmod(0o755)
    (home / "release").write_text(f'IMPLEMENTOR="Vendor"\nJAVA_VERSION="{version}"\n', encoding="utf-8")
    return home


def _ini(edt_dir, *lines):
    edt_dir.mkdir(parents=True, exist_ok=True)
    (edt_dir / "1cedt.ini").write_text("\n".join(["-startup", "plugins/launcher.jar", *lines]) + "\n",
                                       encoding="utf-8")
    return edt_dir


def _java_roots(monkeypatch, tmp_path, path_jdk):
    _, components = _windows_roots(monkeypatch, tmp_path)
    monkeypatch.delenv("JAVA_HOME", raising=False)
    monkeypatch.setenv("PATH", str(path_jdk / "bin"))
    return components


def test_edt_java_supplies_the_required_jdk_when_the_ini_names_none(monkeypatch, tmp_path):
    """1cedtcli without -vm takes java from PATH; an older one stops EDT with a modal dialog."""
    old = _jdk(tmp_path / "old-jdk", "17.0.16")
    components = _java_roots(monkeypatch, tmp_path, old)
    _jdk(components / "axiom-jdk-full-17.0.16+12-x86_64", "17.0.16")
    required = _jdk(components / "axiom-jdk-full-25.0.2+12-x86_64", "25.0.2")
    edt = _ini(tmp_path / "edt", "-vmargs", "-Dosgi.requiredJavaVersion=25")

    assert update.edt_java(edt) == required
    environment = update.with_edt_java(dict(os.environ), edt)
    assert environment["PATH"].split(os.pathsep)[0] == str(required / "bin")
    assert environment["PATH"].split(os.pathsep)[1] == str(old / "bin")


def test_edt_java_leaves_an_ini_that_names_its_vm(monkeypatch, tmp_path):
    old = _jdk(tmp_path / "old-jdk", "17.0.16")
    components = _java_roots(monkeypatch, tmp_path, old)
    _jdk(components / "axiom-jdk-full-25.0.2+12-x86_64", "25.0.2")
    edt = _ini(tmp_path / "edt", "-vm", "C:/jdk/bin/javaw.exe", "-vmargs", "-Dosgi.requiredJavaVersion=25")

    assert update.edt_java(edt) is None
    assert update.with_edt_java({"PATH": "unchanged"}, edt) == {"PATH": "unchanged"}


def test_edt_java_keeps_a_suitable_java_on_the_path(monkeypatch, tmp_path):
    current = _jdk(tmp_path / "current-jdk", "25.0.1")
    components = _java_roots(monkeypatch, tmp_path, current)
    _jdk(components / "axiom-jdk-full-25.0.2+12-x86_64", "25.0.2")
    edt = _ini(tmp_path / "edt", "-vmargs", "-Dosgi.requiredJavaVersion=25")

    assert update.edt_java(edt) is None


def test_edt_java_prefers_a_suitable_java_home(monkeypatch, tmp_path):
    old = _jdk(tmp_path / "old-jdk", "17.0.16")
    components = _java_roots(monkeypatch, tmp_path, old)
    _jdk(components / "axiom-jdk-full-25.0.2+12-x86_64", "25.0.2")
    home = _jdk(tmp_path / "java-home", "25.0.3")
    monkeypatch.setenv("JAVA_HOME", str(home))
    edt = _ini(tmp_path / "edt", "-vmargs", "-Dosgi.requiredJavaVersion=25")

    assert update.edt_java(edt) == home


def test_edt_java_without_a_suitable_jdk_changes_nothing(monkeypatch, tmp_path):
    old = _jdk(tmp_path / "old-jdk", "17.0.16")
    components = _java_roots(monkeypatch, tmp_path, old)
    _jdk(components / "axiom-jdk-full-17.0.16+12-x86_64", "17.0.16")
    edt = _ini(tmp_path / "edt", "-vmargs", "-Dosgi.requiredJavaVersion=25")

    assert update.edt_java(edt) is None
    assert update.with_edt_java({"PATH": "unchanged"}, edt) == {"PATH": "unchanged"}


@pytest.mark.skipif(os.name != "nt", reason="Windows launcher script")
def test_powershell_supplies_the_required_jdk(monkeypatch, tmp_path):
    from pathlib import Path
    import subprocess

    import shutil

    system_path = os.environ["PATH"]
    powershell = shutil.which("powershell.exe", path=system_path)
    old = _jdk(tmp_path / "old-jdk", "17.0.16")
    components = _java_roots(monkeypatch, tmp_path, old)
    required = _jdk(components / "axiom-jdk-full-25.0.2+12-x86_64", "25.0.2")
    edt = _ini(tmp_path / "edt", "-vmargs", "-Dosgi.requiredJavaVersion=25")
    script = tmp_path / "java.ps1"
    script.write_text(
        "param($Common, $Programs, $Edt, $PathJdk)\n"
        "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
        "$env:ProgramFiles = $Programs\n$env:JAVA_HOME = ''\n$env:PATH = $PathJdk\n"
        ". $Common\nResolve-EdtJava -EdtDir $Edt\n",
        encoding="ascii",
    )
    common = Path(__file__).resolve().parents[2] / "scripts" / "edt-common.ps1"

    result = subprocess.run(
        [powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
         str(common), str(tmp_path / "programs"), str(edt), str(old / "bin")],
        capture_output=True, text=True, encoding="utf-8", stdin=subprocess.DEVNULL, timeout=30,
        env={**os.environ, "PATH": system_path},
    )

    assert result.returncode == 0, result.stderr
    assert Path(result.stdout.strip()) == required / "bin"


@pytest.mark.skipif(os.name != "nt", reason="Windows launcher script")
def test_powershell_trusts_the_build_over_a_short_directory_name(monkeypatch, tmp_path):
    from pathlib import Path
    import subprocess

    installations, _ = _windows_roots(monkeypatch, tmp_path)
    _build_id(_edt(installations / "1C_EDT (Lite) 2026.2.0" / "1cedt"), "2026.2.0.289")
    full = _build_id(_edt(installations / "1C_EDT 2026.2" / "1cedt"), "2026.2.2.21")
    script = tmp_path / "resolve.ps1"
    script.write_text(
        "param($Common, $Programs, $Local)\n"
        "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
        "$env:ProgramFiles = $Programs\n$env:LOCALAPPDATA = $Local\n"
        ". $Common\nResolve-EdtDir -RequireExe '1cedtcli.exe'\n",
        encoding="ascii",
    )
    common = Path(__file__).resolve().parents[2] / "scripts" / "edt-common.ps1"

    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
         str(common), str(tmp_path / "programs"), str(tmp_path / "local")],
        capture_output=True, text=True, encoding="utf-8", stdin=subprocess.DEVNULL, timeout=30,
    )

    assert result.returncode == 0, result.stderr
    assert Path(result.stdout.strip()) == full


@pytest.mark.skipif(os.name != "nt", reason="Windows launcher script")
def test_powershell_finds_the_newest_component_build(monkeypatch, tmp_path):
    from pathlib import Path
    import subprocess

    _, components = _windows_roots(monkeypatch, tmp_path)
    _edt(components / "1c-edt-2026.1.2+9-x86_64")
    newest = _edt(components / "1c-edt-2026.1.2+10-x86_64")
    script = tmp_path / "resolve.ps1"
    script.write_text(
        "param($Common, $Programs, $Local)\n"
        "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
        "$env:ProgramFiles = $Programs\n$env:LOCALAPPDATA = $Local\n"
        ". $Common\nResolve-EdtDir -RequireExe '1cedtcli.exe'\n",
        encoding="ascii",
    )
    common = Path(__file__).resolve().parents[2] / "scripts" / "edt-common.ps1"

    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
         str(common), str(tmp_path / "programs"), str(tmp_path / "local")],
        capture_output=True, text=True, encoding="utf-8", stdin=subprocess.DEVNULL, timeout=30,
    )

    assert result.returncode == 0, result.stderr
    assert Path(result.stdout.strip()) == newest



def test_pinned_jar_keeps_the_installed_copy_if_staging_is_denied(monkeypatch, tmp_path):
    _latest_release(monkeypatch)
    installed = tmp_path / _JARS[0]
    installed.write_bytes(b"existing jar")

    def denied(**_kwargs):
        raise PermissionError(13, "Access is denied")

    monkeypatch.setattr(update.tempfile, "TemporaryDirectory", denied)
    lines = []

    assert update.install_latest_jar(tmp_path, emit=lines.append, version="0.34.1") is False
    assert installed.read_bytes() == b"existing jar"
    assert "cannot place the jar into dropins" in lines[-1]
    assert ("as administrator" in lines[-1]) == (os.name == "nt")
