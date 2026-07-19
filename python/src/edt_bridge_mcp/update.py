"""self-update: refresh the plugin jar in EDT's dropins from GitHub Releases, and the
python wrapper itself from PyPI (when published).

    edt-bridge-mcp self-update [--jar-only | --pip-only] [--from <checkout>]

Jar update:
- downloads the latest release asset ``io.github.keyfire.edtbridge_*.jar`` and verifies it
  against the release's ``SHA256SUMS.txt``;
- puts it into ``<EDT>/dropins`` and removes older copies (two singletons of the same bundle
  make Equinox resolve an arbitrary one);
- never touches a GUI EDT: with one running, the new jar simply applies on its next restart.
  A running headless ``1cedtcli`` keeps the OLD jar loaded until it is restarted – the wrapper
  restarts it automatically on the next auto-start.

Wrapper update:
- installs ``edt-bridge-mcp`` into this very environment (the pipx venv), or a local checkout
  given with ``--from``. Python files are replaceable while running; the pipx-managed exe is not
  touched, which is why this never shells out to ``pipx upgrade``.
- pip, then uv, then ensurepip: a venv built by pipx 1.15 goes through uv and has no pip in it at
  all, so ``python -m pip`` answers "No module named pip" - uv installs into a target interpreter
  without needing pip inside it.
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


def _installers(source: str) -> list[tuple[str, list[str]]]:
    """Ways to install into THIS environment, best first.

    pip is not a given. pipx 1.15 builds its venvs with uv, and a uv-built venv carries no pip at
    all - ``python -m pip`` there answers "No module named pip", which is where this used to stop.
    uv installs into a target interpreter without needing pip inside it, so it covers exactly the
    case pip cannot; ensurepip is the last resort, and the only one that adds anything to the venv.
    """
    return [
        ("pip", [sys.executable, "-m", "pip", "install", "--upgrade", source]),
        ("uv", ["uv", "pip", "install", "--python", sys.executable, "--upgrade", source]),
        ("ensurepip+pip", None),  # handled in update_wrapper - it needs two steps
    ]


def _run(cmd: list[str]) -> tuple[int, str]:
    """Run an installer, decoding its output as UTF-8 rather than the console code page.

    These tools report OS errors in the system language and emit them as UTF-8; letting Python
    decode by locale turns a Windows "access denied" into mojibake, which is exactly the message
    worth reading.
    """
    try:
        proc = subprocess.run(cmd, capture_output=True, check=False)
    except FileNotFoundError:
        return 127, f"{cmd[0]} is not installed"
    raw = (proc.stdout or b"") + (proc.stderr or b"")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode(sys.getfilesystemencoding() or "utf-8", errors="replace")
    return proc.returncode, text.strip()


def _missing_pip(output: str) -> bool:
    return "No module named pip" in output


def update_wrapper(source: str = "edt-bridge-mcp") -> bool:
    """Upgrade the wrapper in place. ``source`` may be a local checkout path instead of the name."""
    from_pypi = source == "edt-bridge-mcp"
    log(f"upgrading the python wrapper from {'PyPI (if published)' if from_pypi else source}...")
    notes = []
    for name, cmd in _installers(source):
        if cmd is None:
            code, out = _run([sys.executable, "-m", "ensurepip", "--upgrade"])
            if code != 0:
                notes.append(f"{name}: {_last_line(out)}")
                continue
            code, out = _run([sys.executable, "-m", "pip", "install", "--upgrade", source])
        else:
            code, out = _run(cmd)
        if code == 0:
            log(f"{name}: {_last_line(out) or 'done'}")
            return True
        if from_pypi and ("No matching distribution" in out or "Could not find a version" in out):
            log("the package is not on PyPI yet - install from a checkout instead: "
                "edt-bridge-mcp self-update --pip-only --from <repo>/python")
            return False
        # Say why each route was passed over: the last one adds pip to the environment, which is
        # worth knowing was not the first choice.
        why = "no pip in this environment" if _missing_pip(out) else _last_line(out)
        log(f"{name} did not work ({why}) - trying the next route")
        notes.append(f"{name}: {why}")
    log("could not upgrade the wrapper - " + "; ".join(notes))
    return False


def _last_line(output: str) -> str:
    """The line worth showing: what got installed, not the installer's own housekeeping.

    pip signs off with "[notice] To update, run: ... --upgrade pip", which as the last line of a
    successful install reads like the result and is not.
    """
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    lines = [line for line in lines if not line.startswith(("[notice]", "WARNING:"))]
    for line in reversed(lines):
        if "Successfully installed" in line or "edt-bridge-mcp" in line:
            return line
    return lines[-1] if lines else ""


def run(argv: list[str]) -> int:
    jar_only = "--jar-only" in argv
    pip_only = "--pip-only" in argv
    source = "edt-bridge-mcp"
    if "--from" in argv:
        index = argv.index("--from")
        if index + 1 >= len(argv):
            log("--from needs a path to a checkout, e.g. --from <repo>/python")
            return 1
        source = argv[index + 1]
    ok = True
    if not pip_only:
        ok = update_jar() and ok
    if not jar_only:
        ok = update_wrapper(source) and ok
    return 0 if ok else 1
