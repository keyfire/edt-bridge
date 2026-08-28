"""self-update: refresh the plugin jar in EDT's dropins from GitHub Releases, the
python wrapper itself from PyPI (when published), and the wrapper plugins.

    edt-bridge-mcp self-update [--jar-only | --pip-only | --plugins-only] [--from <checkout>]

Jar update:
- downloads the latest release asset ``io.github.keyfire.edtbridge_*.jar`` and verifies it
  against the release's ``SHA256SUMS.txt``;
- puts it into ``<EDT>/dropins`` and removes older copies (two singletons of the same bundle
  make Equinox resolve an arbitrary one);
- never touches a GUI EDT: with one running, the new jar simply applies on its next restart.
  A running headless ``1cedtcli`` keeps the OLD jar loaded until it is restarted – the wrapper
  restarts it automatically on the next auto-start.

Wrapper update:
- downloads the wheel from PyPI (or copies the package out of a checkout given with ``--from``)
  and replaces the package inside ``site-packages`` with the standard library alone - no pip, no
  pipx, no build backend.
- the exes in ``Scripts`` are never touched. They are what a running client holds open, and
  Windows will not let them be replaced; they do not need to be, since the stub launches whatever
  code is in site-packages next time. Installers are no use here anyway: pipx 1.15 builds its
  venvs through uv and a uv-built venv has no pip in it at all.
- ``pipx_metadata.json`` is corrected, so ``pipx list`` does not go on reporting the old version.

Plugin update:
- wrapper plugins (the distributions publishing ``edt_bridge.tools`` entry points) are updated
  through pip, unlike the wrapper itself: a plugin has no exe for a running client to hold, so
  pip's route is safe here - and it is the only route that knows the plugin's source. That source
  is read from each plugin's ``direct_url.json`` (PEP 610): a git install updates from its
  repository URL, a registry install by project name (honouring ``EDT_BRIDGE_PLUGIN_INDEX`` as
  the index URL). pip itself is reached the way the environment allows: ``pipx runpip`` for a
  pipx venv (works even though a uv-built venv has no pip module), the venv's own pip otherwise.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile
from io import BytesIO
from pathlib import Path

from . import __version__, i18n, plugins

RELEASES_LATEST = "https://api.github.com/repos/keyfire/edt-bridge/releases/latest"
JAR_PREFIX = "io.github.keyfire.edtbridge_"


def log(message: str) -> None:
    print(f"[self-update] {message}", flush=True)


def _fetch_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _download(url: str, target: Path) -> None:
    with urllib.request.urlopen(url, timeout=600) as resp, open(target, "wb") as f:
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            f.write(chunk)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def _find_dropins() -> Path | None:
    edt_dir = (os.environ.get("EDT_BRIDGE_EDT_DIR") or "").strip()
    if edt_dir:
        d = Path(edt_dir) / "dropins"
        return d if d.parent.exists() else None
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", "")) / "1C" / "1cedtstart" / "installations"
        if base.is_dir():
            for inst in sorted(base.iterdir(), reverse=True):
                edt = inst / "1cedt"
                if (edt / ("1cedtcli.exe")).exists() or (edt / "1cedt.exe").exists():
                    return edt / "dropins"
    return None


def purge_stale_jars(dropins: Path | None, emit=log) -> int:
    """Keep only the newest edt-bridge jar in dropins, deleting the rest. Two singletons of the
    same bundle make Equinox resolve an arbitrary (often older) one, so a stale jar left next to a
    freshly delivered one silently loads the wrong code. Returns how many were removed."""
    if not dropins or not dropins.is_dir():
        return 0
    jars = sorted(dropins.glob(JAR_PREFIX + "*.jar"), key=lambda p: p.name)
    removed = 0
    for old in jars[:-1]:  # all but the lexically-newest (version+timestamp sort)
        try:
            old.unlink()
            removed += 1
        except OSError:
            emit(f"stale jar is locked (a running EDT?): {old.name} – remove it after a restart")
    if removed:
        emit(f"purged {removed} stale jar(s) from dropins, kept {jars[-1].name}")
    return removed


def has_jar(dropins: Path | None) -> bool:
    """Whether an edt-bridge plugin jar is present. Also collapses dropins to a single newest jar,
    so a stale copy next to the current one never gets loaded by mistake."""
    if not dropins:
        return False
    purge_stale_jars(dropins)
    return any(dropins.glob(JAR_PREFIX + "*.jar"))


def install_latest_jar(dropins: Path, emit=log) -> bool:
    """Download the latest release jar (checksum-verified) into ``dropins``, dropping older
    copies. Shared by the ``self-update`` command and the wrapper's first-run delivery."""
    emit("querying the latest GitHub release...")
    try:
        release = _fetch_json(RELEASES_LATEST)
    except (OSError, ValueError) as exc:
        emit(f"cannot query releases: {exc}")
        return False
    assets = release.get("assets") or []
    jar_asset = next((a for a in assets if a.get("name", "").startswith(JAR_PREFIX)
                      and a.get("name", "").endswith(".jar")), None)
    sums_asset = next((a for a in assets if a.get("name") == "SHA256SUMS.txt"), None)
    if jar_asset is None:
        emit(f"the latest release ({release.get('tag_name')}) carries no plugin jar – nothing to do")
        return False
    dropins.mkdir(parents=True, exist_ok=True)

    name = jar_asset["name"]
    existing = dropins / name
    if existing.exists():
        # Still collapse dropins to one jar: a hand-built sibling next to the release jar is the
        # exact situation Equinox resolves arbitrarily, and returning early used to leave it there
        # for good - the purge only ran on the path that downloads something.
        purge_stale_jars(dropins, emit)
        emit(f"already at the latest release jar: {name}")
        return True

    tmp = Path(tempfile.gettempdir()) / name
    emit(f"downloading {name} ({jar_asset.get('size', 0)} bytes)...")
    try:
        _download(jar_asset["browser_download_url"], tmp)
    except OSError as exc:
        emit(f"download failed: {exc}")
        return False

    if sums_asset is not None:
        try:
            sums_tmp = Path(tempfile.gettempdir()) / "edtbridge-SHA256SUMS.txt"
            _download(sums_asset["browser_download_url"], sums_tmp)
            expected = None
            for line in sums_tmp.read_text(encoding="utf-8").splitlines():
                parts = line.split()
                # `sha256sum dist/<jar>` writes the path with its dist/ prefix; match on the
                # basename so a path-prefixed checksum line still verifies the downloaded asset.
                if len(parts) >= 2 and parts[-1].lstrip("*").rsplit("/", 1)[-1] == name:
                    expected = parts[0].lower()
            actual = _sha256(tmp)
            if expected is None:
                emit("checksum file has no entry for the jar – proceeding unverified")
            elif expected != actual:
                emit(f"CHECKSUM MISMATCH: expected {expected}, got {actual} – aborting")
                tmp.unlink(missing_ok=True)
                return False
            else:
                emit("checksum verified")
        except OSError as exc:
            emit(f"checksum download failed ({exc}) – proceeding unverified")

    target = dropins / name
    try:
        if target.exists():
            target.unlink()
        tmp.replace(target)
    except OSError as exc:
        emit(f"cannot place the jar into dropins: {exc}")
        return False
    removed = 0
    for old in dropins.glob(JAR_PREFIX + "*.jar"):
        if old.name != name:
            try:
                old.unlink()
                removed += 1
            except OSError:
                emit(f"old jar is locked (a running EDT?): {old.name} – remove it after a restart")
    emit(f"installed {name} into {dropins} (removed {removed} old cop{'ies' if removed != 1 else 'y'})")
    return True


def update_jar() -> bool:
    dropins = _find_dropins()
    if dropins is None:
        log("EDT dropins not found – pass EDT_BRIDGE_EDT_DIR pointing at the .../1cedt folder")
        return False
    ok = install_latest_jar(dropins)
    if ok:
        log("a running EDT (GUI or headless) keeps the old code until restarted")
    return ok


# Where the wrapper's files come from. The simple index (PEP 691) is served straight from the
# upload, while the JSON metadata below is a cache that lags behind a release by minutes - see
# `_wheel_url`. The JSON is kept as the fallback for an index that does not speak PEP 691.
PYPI_SIMPLE = "https://pypi.org/simple/edt-bridge-mcp/"
PYPI_VERSION = "https://pypi.org/pypi/edt-bridge-mcp/{version}/json"
PYPI_LATEST = "https://pypi.org/pypi/edt-bridge-mcp/json"
# The same URL answers an HTML page unless JSON is asked for by name.
SIMPLE_ACCEPT = "application/vnd.pypi.simple.v1+json"

# What belongs to this wheel inside site-packages. The exes in Scripts are deliberately absent:
# they are what a running client holds, and they do not need replacing - the stub launches whatever
# code is in site-packages next time.
_OWNED_PATTERNS = ("edt_bridge_mcp", "edt_bridge_mcp-*.dist-info")


def _site_packages() -> Path:
    """Directory this package is installed into (site-packages in a real install)."""
    return Path(__file__).resolve().parent.parent


def _ensure_regular_install(site: Path) -> None:
    """Refuse an editable install - unpacking a wheel over a checkout would wreck the repository."""
    if site.name.lower() not in ("site-packages", "dist-packages"):
        raise _UpdateError(
            f"the package is imported from {site} - that is an editable install from a checkout; "
            "update it with git instead"
        )


class _UpdateError(RuntimeError):
    """Wrapper-update failure; the text is shown to the user as it is."""


def _simple_files() -> list[dict]:
    """Files of the project from the simple index: `{"filename", "url", "version"}` each.

    Empty list when the index cannot be read as JSON (a mirror that answers HTML, a network
    failure) - the caller then falls back to the JSON metadata, which reports the outage in its
    own words. Yanked files are dropped here: a yanked release must not win the "latest" race
    nor be installed by name.
    """
    request = urllib.request.Request(PYPI_SIMPLE, headers={"Accept": SIMPLE_ACCEPT})
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except (OSError, ValueError):
        return []
    files = []
    for item in data.get("files") or []:
        name, url = str(item.get("filename") or ""), str(item.get("url") or "")
        if not name or not url or item.get("yanked"):
            continue
        version = _version_of(name)
        if version:
            files.append({"filename": name, "url": url, "version": version})
    return files


def _version_of(filename: str) -> str:
    """Version segment of a distribution file name; "" when the name is not one of ours."""
    for suffix in (".whl", ".tar.gz", ".zip"):
        if filename.lower().endswith(suffix):
            parts = filename[: -len(suffix)].split("-")
            return parts[1] if len(parts) > 1 else ""
    return ""


def _release_key(version: str) -> tuple[tuple[int, ...], int] | None:
    """Sort key of a plain release (`0.11.1` -> `((0, 11, 1), 0)`); None for anything else.

    Deliberately narrow: only digits and an optional `.postN` are ranked, so a pre-release or a
    dev build can never be picked as the latest version by accident.
    """
    head, _, post = version.partition(".post")
    if post and not post.isdigit():
        return None
    parts = head.split(".")
    if not all(part.isdigit() for part in parts):
        return None
    return tuple(int(part) for part in parts), int(post or 0)


def _latest_release(files: list[dict]) -> str:
    """The newest plain release among the files; "" when none of them ranks."""
    ranked = []
    for version in {item["version"] for item in files if item["filename"].lower().endswith(".whl")}:
        key = _release_key(version)
        if key is not None:
            ranked.append((key, version))
    return max(ranked)[1] if ranked else ""


def _wheel_url(version: str | None) -> tuple[str, str]:
    """URL and exact version of the py3-none-any wheel on PyPI (latest, or the one asked for).

    The file list is taken from the SIMPLE index, not from the JSON metadata. Caught on the
    toolkit engine on 31.07.2026 and true here for the same reason: the JSON is a cache that
    catches up minutes after an upload, so right after a release the command answers "already
    current" - or, with an explicit version, "no wheel", because the files are read from that
    same lagging document. The JSON stays as the fallback for an index that does not answer
    PEP 691 (and it is the one that reports an outage in words). Only the WRAPPER is affected:
    the jar half of the update reads the assets of a GitHub release, a different source.
    """
    files = _simple_files()
    if files:
        target = version or _latest_release(files)
        entries = [
            item for item in files
            if item["version"] == target and item["filename"].endswith("-py3-none-any.whl")
        ]
        if target and entries:
            return entries[0]["url"], target
        if version:  # the index is readable and simply does not carry this version
            raise _UpdateError("no such version on PyPI")
    url = PYPI_VERSION.format(version=version) if version else PYPI_LATEST
    try:
        data = _fetch_json(url)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise _UpdateError("no such version on PyPI" if version
                               else "edt-bridge-mcp is not on PyPI yet") from error
        raise _UpdateError(f"PyPI answered {error.code}") from error
    except OSError as error:
        raise _UpdateError(f"could not reach PyPI: {error}") from error
    resolved = data["info"]["version"]
    for entry in data["urls"]:
        if entry["filename"].endswith("-py3-none-any.whl"):
            return entry["url"], resolved
    raise _UpdateError(f"PyPI has no wheel for edt-bridge-mcp {resolved}")


def _clear_owned(site: Path) -> None:
    for pattern in _OWNED_PATTERNS:
        for path in site.glob(pattern):
            if path.is_dir():
                shutil.rmtree(path, ignore_errors=True)


def _checkout_version(package_dir: Path) -> str:
    """The __version__ literal of a checkout, without importing it."""
    match = re.search(r'__version__\s*=\s*"([^"]+)"',
                      (package_dir / "__init__.py").read_text(encoding="utf-8"))
    return match.group(1) if match else "unknown"


def _install_from_checkout(site: Path, checkout: str) -> str:
    """Copy the package straight out of a checkout - no build backend needed to try a local build."""
    source = Path(checkout).expanduser().resolve()
    # The REPOSITORY root is what one naturally passes - the wrapper lives in its python/
    # subdirectory, and demanding that suffix turned an obvious command into a second attempt.
    candidates = (source / "src" / "edt_bridge_mcp",
                  source / "edt_bridge_mcp",
                  source / "python" / "src" / "edt_bridge_mcp")
    package = next((path for path in candidates if path.is_dir()), None)
    if package is None:
        raise _UpdateError(
            f"no edt_bridge_mcp package under {source} - looked in "
            + ", ".join(str(path.relative_to(source)) for path in candidates)
        )
    version = _checkout_version(package)
    # Only the package tree is replaced; the dist-info stays as installed, so pip metadata will
    # report the released version until a real install happens. __version__ lives in the code, so
    # what the wrapper reports about itself is right either way.
    shutil.rmtree(site / "edt_bridge_mcp", ignore_errors=True)
    shutil.copytree(package, site / "edt_bridge_mcp",
                    ignore=shutil.ignore_patterns("__pycache__"))
    return version


def update_wrapper(source: str | None = None, version: str | None = None) -> bool:
    """Replace the wrapper inside its own environment by unpacking, never through pip.

    pip is not usable for this: pipx 1.15 builds its venvs through uv and a uv-built venv has no pip
    in it at all, and any installer that does have pip wants to rewrite the console script - which a
    running client holds open on Windows. Only files under site-packages are touched; the stub in
    Scripts picks up the new code next time it starts. This is the same shape the sibling tools use.
    """
    try:
        site = _site_packages()
        _ensure_regular_install(site)
        if source:
            installed = _install_from_checkout(site, source)
            log(f"installed edt-bridge-mcp {installed} from {source}")
        else:
            url, target = _wheel_url(version)
            if version is None and target == __version__:
                log(f"already current: edt-bridge-mcp {__version__}")
                return True
            log(f"downloading edt-bridge-mcp {target} from PyPI...")
            try:
                with urllib.request.urlopen(url, timeout=600) as resp:
                    blob = resp.read()
            except OSError as error:
                raise _UpdateError(f"could not download the wheel: {error}") from error
            _clear_owned(site)
            with zipfile.ZipFile(BytesIO(blob)) as archive:
                archive.extractall(site)
            installed = target
            log(f"unpacked into {site}: edt-bridge-mcp {__version__} -> {target}")
        _update_pipx_metadata(site, installed)
        log("restart the MCP client (or any running edt-bridge-mcp) to pick up the new code")
        return True
    except _UpdateError as failure:
        log(str(failure))
        return False
    except OSError as failure:
        log(f"could not replace the installed package: {failure}")
        return False


def _update_pipx_metadata(site: Path, version: str) -> None:
    """Keep `pipx list` honest - it reads a version this update would otherwise leave behind."""
    meta = site.parent.parent / "pipx_metadata.json"   # <venv>/Lib/site-packages -> <venv>
    if not meta.is_file():
        return
    try:
        data = json.loads(meta.read_text(encoding="utf-8"))
        main = data.get("main_package") or {}
        if main.get("package") == "edt-bridge-mcp":
            main["package_version"] = version
            meta.write_text(json.dumps(data, indent=4), encoding="utf-8")
            log("updated pipx_metadata.json")
    except (OSError, ValueError):
        pass


# -- wrapper plugins ---------------------------------------------------------------------------

#: Index URL for plugins installed by project name; git installs carry their own source.
PLUGIN_INDEX_ENV = "EDT_BRIDGE_PLUGIN_INDEX"


def _plugin_distributions() -> list:
    """Installed plugin distributions - the ones publishing ``edt_bridge.tools`` entry points."""
    from importlib.metadata import entry_points
    seen, dists = set(), []
    for point in entry_points(group=plugins.TOOLS_GROUP):
        dist = getattr(point, "dist", None)
        if dist is None or dist.name in seen:
            continue
        seen.add(dist.name)
        dists.append(dist)
    return dists


def _plugin_source(dist) -> str | None:
    """pip target that updates this plugin.

    A git install (PEP 610 ``direct_url.json`` with ``vcs_info``) updates from its repository
    URL; an archive URL from that URL; a registry install by project name. None for a local
    directory install - pip would just reinstall the same files, so the answer is "update the
    checkout itself".
    """
    raw = None
    try:
        raw = dist.read_text("direct_url.json")
    except OSError:
        pass
    if raw:
        try:
            direct = json.loads(raw)
        except ValueError:
            direct = {}
        url = str(direct.get("url") or "")
        vcs = (direct.get("vcs_info") or {}).get("vcs")
        if url and vcs:
            return f"{vcs}+{url}"
        if direct.get("dir_info") is not None:
            return None
        if url:
            return url
    return dist.name


def _pip_command(site: Path) -> list[str] | None:
    """How pip reaches this environment.

    ``pipx runpip`` for a pipx venv - it routes through uv on uv-built venvs, which have no pip
    module of their own. Elsewhere the venv's python with ``-m pip``; None when neither route
    exists (the caller reports it).
    """
    venv = site.parent.parent
    if (venv / "pipx_metadata.json").is_file() and shutil.which("pipx"):
        return ["pipx", "runpip", "edt-bridge-mcp", "--"]
    python = venv / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if python.exists():
        return [str(python), "-m", "pip"]
    return None


def _installed_version(site: Path, project: str) -> str:
    """Version of a project by its dist-info directory name; "" when it is not installed.

    Read from the file system, not importlib.metadata: this runs right after a child pip
    changed site-packages, and the metadata finder of the running process may have cached
    the old listing.
    """
    prefix = re.sub(r"[-_.]+", "_", project).lower() + "-"
    for info in site.glob("*.dist-info"):
        if info.name.lower().startswith(prefix):
            return info.name[len(prefix):-len(".dist-info")]
    return ""


def update_plugins(emit=log) -> bool:
    """Update every installed wrapper plugin through pip, reporting each one.

    pip is the right tool HERE, unlike for the wrapper itself: a plugin has no console script
    for a running client to hold open, and pip is what knows how to follow the plugin's own
    source - a git repository for most of them.
    """
    site = _site_packages()
    try:
        _ensure_regular_install(site)
    except _UpdateError as failure:
        emit(str(failure))
        return False
    dists = _plugin_distributions()
    if not dists:
        emit("no wrapper plugins installed - nothing to update")
        return True
    command = _pip_command(site)
    if command is None:
        emit("no pip route into this environment - update the plugins by hand")
        return False
    ok = True
    for dist in dists:
        name, before = dist.name, dist.version
        target = _plugin_source(dist)
        if target is None:
            emit(f"{name} {before}: installed from a local directory - update that checkout instead")
            continue
        argv = command + ["install", "--upgrade", "--no-deps"]
        index = (os.environ.get(PLUGIN_INDEX_ENV) or "").strip()
        if index and target == name:
            argv += ["--index-url", index]
        argv.append(target)
        emit(f"updating {name} {before} from "
             + ("its repository..." if target != name else "the package index..."))
        try:
            result = subprocess.run(argv, capture_output=True, text=True, timeout=600)
        except (OSError, subprocess.TimeoutExpired) as failure:
            emit(f"{name}: update failed - {failure}")
            ok = False
            continue
        if result.returncode != 0:
            tail = (result.stderr or result.stdout or "").strip().splitlines()
            emit(f"{name}: update failed - {tail[-1] if tail else 'pip gave no output'}")
            ok = False
            continue
        after = _installed_version(site, name) or before
        emit(f"{name}: {before} -> {after}"
             + (" (already current)" if after == before else ""))
    if ok:
        emit("restart the MCP client to pick up updated plugin code")
    return ok


def run(argv: list[str]) -> int:
    if "-h" in argv or "--help" in argv:
        # Without this, asking for help would perform the update - the flags are parsed by
        # presence, so an unrecognised one means "do everything".
        print(i18n.t("update.usage"))
        return 0
    jar_only = "--jar-only" in argv
    pip_only = "--pip-only" in argv
    plugins_only = "--plugins-only" in argv
    source = None
    if "--from" in argv:
        index = argv.index("--from")
        if index + 1 >= len(argv):
            log("--from needs a path to a checkout, e.g. --from <repo>/python")
            return 1
        source = argv[index + 1]
    ok = True
    if not pip_only and not plugins_only:
        ok = update_jar() and ok
    if not jar_only and not plugins_only:
        ok = update_wrapper(source) and ok
    if not jar_only and not pip_only:
        ok = update_plugins() and ok
    return 0 if ok else 1
