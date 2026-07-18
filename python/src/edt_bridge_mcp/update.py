"""self-update: refresh the plugin jar in EDT's dropins from GitHub Releases, and the
python wrapper itself from PyPI (when published).

    edt-bridge-mcp self-update [--jar-only | --pip-only]

Jar update:
- downloads the latest release asset ``io.github.keyfire.edtbridge_*.jar`` and verifies it
  against the release's ``SHA256SUMS.txt``;
- puts it into ``<EDT>/dropins`` and removes older copies (two singletons of the same bundle
  make Equinox resolve an arbitrary one);
- never touches a GUI EDT: with one running, the new jar simply applies on its next restart.
  A running headless ``1cedtcli`` keeps the OLD jar loaded until it is restarted — the wrapper
  restarts it automatically on the next auto-start.

Wrapper update:
- runs ``pip install --upgrade edt-bridge-mcp`` inside this very environment (the pipx venv).
  Python files are replaceable while running; the pipx-managed exe is not touched.
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

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


def has_jar(dropins: Path | None) -> bool:
    """Whether any edt-bridge plugin jar is already present in the dropins folder."""
    return bool(dropins) and any(dropins.glob(JAR_PREFIX + "*.jar"))


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
        emit(f"the latest release ({release.get('tag_name')}) carries no plugin jar — nothing to do")
        return False
    dropins.mkdir(parents=True, exist_ok=True)

    name = jar_asset["name"]
    existing = dropins / name
    if existing.exists():
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
                if len(parts) >= 2 and parts[-1].lstrip("*") == name:
                    expected = parts[0].lower()
            actual = _sha256(tmp)
            if expected is None:
                emit("checksum file has no entry for the jar — proceeding unverified")
            elif expected != actual:
                emit(f"CHECKSUM MISMATCH: expected {expected}, got {actual} — aborting")
                tmp.unlink(missing_ok=True)
                return False
            else:
                emit("checksum verified")
        except OSError as exc:
            emit(f"checksum download failed ({exc}) — proceeding unverified")

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
                emit(f"old jar is locked (a running EDT?): {old.name} — remove it after a restart")
    emit(f"installed {name} into {dropins} (removed {removed} old cop{'ies' if removed != 1 else 'y'})")
    return True


def update_jar() -> bool:
    dropins = _find_dropins()
    if dropins is None:
        log("EDT dropins not found — pass EDT_BRIDGE_EDT_DIR pointing at the …/1cedt folder")
        return False
    ok = install_latest_jar(dropins)
    if ok:
        log("a running EDT (GUI or headless) keeps the old code until restarted")
    return ok


def update_wrapper() -> bool:
    log("upgrading the python wrapper from PyPI (if published)...")
    proc = subprocess.run(
        [sys.executable, "-m", "pip", "install", "--upgrade", "edt-bridge-mcp"],
        capture_output=True, text=True, check=False,
    )
    tail = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        if "No matching distribution" in tail or "Could not find a version" in tail:
            log("the package is not on PyPI yet — reinstall from a checkout instead "
                "(pipx install --force <repo>/python)")
        else:
            log(f"pip upgrade failed: {tail.strip().splitlines()[-1] if tail.strip() else proc.returncode}")
        return False
    last = tail.strip().splitlines()[-1] if tail.strip() else "done"
    log(last)
    return True


def run(argv: list[str]) -> int:
    jar_only = "--jar-only" in argv
    pip_only = "--pip-only" in argv
    ok = True
    if not pip_only:
        ok = update_jar() and ok
    if not jar_only:
        ok = update_wrapper() and ok
    return 0 if ok else 1
