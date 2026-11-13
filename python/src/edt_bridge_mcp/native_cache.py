"""The cache of the test manager infobase that every form check needs.

The manager infobase is an empty infobase with the test driver loaded into it: two designer runs and
an ibcmd call, the same for every run of one platform build. The first check of a build prepares it
and keeps its data file here; later checks copy that file into their own directory, because the
manager writes into its infobase while it runs.

An entry is a directory named by the key: the data file and a manifest with its size and sha256. The
key covers the client executable (its path, size and modification time) and the text of the test
driver, so another platform build or another driver never takes an entry it was not built with. An
entry that does not match its manifest is removed and built again.
"""
from __future__ import annotations

import datetime
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

ENV_CACHE = "EDT_BRIDGE_TEST_CACHE"
DATA_FILE = "1Cv8.1CD"
MANIFEST = "manifest.json"
#: Raised when the layout of an entry changes, so older entries are not read as current ones.
LAYOUT = 1


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def root(environ=os.environ):
    """(directory of the cache, why there is none): EDT_BRIDGE_TEST_CACHE, `off`, or the user cache."""
    value = (environ.get(ENV_CACHE) or "").strip()
    if value.casefold() == "off":
        return None, f"{ENV_CACHE}=off"
    if value:
        return Path(value), None
    base = environ.get("XDG_CACHE_HOME")
    try:
        base = Path(base) if base else Path.home() / ".cache"
    except RuntimeError as error:  # no home directory to put it in
        return None, f"no cache directory: {error}"
    return base / "edt-bridge" / "test-manager", None


def key(client_exe: Path, driver: Path) -> str:
    """The key of the manager infobase a platform build and a test driver give."""
    stat = client_exe.stat()
    material = json.dumps([LAYOUT, str(client_exe.resolve()), stat.st_size, stat.st_mtime_ns, _sha256(driver)])
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:20]


class Entry:
    """One entry of the cache: the manager infobase of one key."""

    def __init__(self, directory: Path, name: str):
        self.directory = directory
        self.path = directory / name

    def take(self, destination: Path):
        """Copy the cached data file into `destination`: (manifest, None) on a hit, (None, why not) otherwise.

        An entry that is incomplete or differs from its manifest is removed, so the next check builds it again."""
        if not self.path.exists():
            return None, "missing"
        try:
            manifest = json.loads((self.path / MANIFEST).read_text(encoding="utf-8"))
            data = self.path / DATA_FILE
            if not isinstance(manifest, dict) or manifest.get("layout") != LAYOUT:
                raise ValueError("the manifest is of another layout")
            if not isinstance(manifest.get("formatVersion"), str):
                raise ValueError("the manifest has no configuration format version")
            if data.stat().st_size != manifest.get("size") or _sha256(data) != manifest.get("sha256"):
                raise ValueError(f"{DATA_FILE} differs from the manifest")
        except (OSError, ValueError) as error:
            self.discard()
            return None, f"damaged: {error}"
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(data, destination / DATA_FILE)
        return manifest, None

    def put(self, infobase: Path, format_version: str) -> None:
        """Keep the data file of a prepared manager infobase. The entry appears whole or not at all: it is
        written aside and renamed into place; a check that stored the same key first wins."""
        self.directory.mkdir(parents=True, exist_ok=True)
        pending = Path(tempfile.mkdtemp(prefix=self.path.name + ".", suffix=".pending", dir=self.directory))
        try:
            data = pending / DATA_FILE
            shutil.copyfile(infobase / DATA_FILE, data)
            manifest = {"layout": LAYOUT, "sha256": _sha256(data), "size": data.stat().st_size,
                        "formatVersion": format_version,
                        "created": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
            (pending / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="")
            try:
                os.replace(pending, self.path)
            except OSError:
                if not self.path.exists():
                    raise
        finally:
            shutil.rmtree(pending, ignore_errors=True)

    def discard(self) -> None:
        shutil.rmtree(self.path, ignore_errors=True)


def entry(client_exe: Path, driver: Path, environ=os.environ):
    """(the entry of this platform build and driver, why there is none)."""
    directory, reason = root(environ)
    if directory is None:
        return None, reason
    return Entry(directory, key(client_exe, driver)), None
