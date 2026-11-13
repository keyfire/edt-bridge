"""A ready EDT workspace: pack it once, restore it where the next EDT starts, compare the checks.

Importing and indexing a large configuration takes EDT tens of minutes; a workspace that EDT saved
on a graceful stop opens in a few. What makes the saved one usable elsewhere:

- EDT has stopped. A running EDT holds the workspace lock and keeps writing its state.
- The modification times survive. EDT compares them with the state it saved, and a file that looks
  changed is read again. The archive is a PAX tar, which keeps times with their fraction of a second.
- The path is the same. EDT keeps absolute paths in its metadata, so the archive records where the
  workspace was and restores it only there.
- Nothing secret travels. EDT writes its command line, with the bridge token, into the logs of
  .metadata; they stay behind.

    edt-bridge-mcp workspace checks --workspace W --output before.json   (EDT runs, models ready)
    edt-bridge-mcp shutdown --workspace W
    edt-bridge-mcp workspace pack --workspace W --archive ready.tar.gz
    edt-bridge-mcp workspace restore --archive ready.tar.gz              (on the next machine)
    edt-bridge-mcp wait-ready --workspace W
    edt-bridge-mcp workspace checks --workspace W --compare before.json

Where the archives are kept and which inputs name one is the business of whoever builds them.
"""
from __future__ import annotations

import datetime
import fnmatch
import io
import json
import os
import shutil
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath

from . import __version__

MANIFEST = ".edt-bridge-workspace.json"
SCHEMA = 1
#: Paths relative to the workspace, matched within one directory level: the lock, the logs with the
#: EDT command line, and the pid and log lock files of a process that is gone.
EXCLUDES = (".metadata/.lock", ".metadata/.log", ".metadata/*.log", ".metadata/.bak_*",
            ".metadata/*.pid", ".metadata/*.lck")
#: Where Eclipse keeps a project that lives outside the workspace directory.
_PROJECTS_META = Path(".metadata/.plugins/org.eclipse.core.resources/.projects")
#: The checks a snapshot holds per project.
_SEVERITIES = "ERROR,WARNING,INFO"
_PROBLEM_LIMIT = 10000


class WorkspaceError(Exception):
    """A refusal or a failure; the message says what to do."""


def _key(path: Path) -> str:
    return os.path.normcase(os.path.realpath(path))


def _excluded(relative: str) -> bool:
    return any(relative.count("/") == pattern.count("/") and fnmatch.fnmatchcase(relative, pattern)
               for pattern in EXCLUDES)


def _compression(archive: Path) -> str:
    """The compression the archive name asks for: "", "gz" or "zst"."""
    name = archive.name.lower()
    if name.endswith((".tar.gz", ".tgz")):
        return "gz"
    if name.endswith(".tar.zst"):
        if "zst" not in tarfile.TarFile.OPEN_METH:
            raise WorkspaceError("a .tar.zst archive needs Python 3.14 or newer; use .tar.gz or .tar")
        return "zst"
    if name.endswith(".tar"):
        return ""
    raise WorkspaceError(f"name the archive .tar, .tar.gz or .tar.zst: {archive.name}")


def _compressor(compression: str, raw):
    """A writer that compresses into the open binary file, or None for a plain tar.

    gzip runs at level 1: it compresses in one thread, and at its default level 9 a workspace of
    several gigabytes takes many times longer to pack while the archive shrinks by a few percent.
    """
    if compression == "gz":
        import gzip
        return gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=1)
    if compression == "zst":
        from compression import zstd
        return zstd.ZstdFile(raw, mode="w")
    return None


def _read(archive: Path) -> tarfile.TarFile:
    _compression(archive)
    return tarfile.open(archive, "r:*")


def _stream(archive: Path) -> tarfile.TarFile:
    """The archive read once from start to end, the way restore needs it."""
    _compression(archive)
    return tarfile.open(archive, "r|*")


def _unsafe_name(name: str) -> bool:
    """A name restore refuses: absolute, leaving the workspace, or carrying a drive or a backslash."""
    path = PurePosixPath(name)
    return path.is_absolute() or ".." in path.parts or ":" in name or "\\" in name


#: The chunk marker Eclipse writes before the location of a project in its .location file.
_LOCATION_MAGIC = bytes([0x40, 0xB1, 0x8B, 0x81, 0x23, 0xBC, 0x00, 0x14,
                         0x1A, 0x25, 0x96, 0xE7, 0xA3, 0x93, 0xBE, 0x1E])


def project_location(location_file: Path) -> Path | None:
    """The directory a .location file names, or None for the default place inside the workspace.

    Eclipse writes the location with DataOutput.writeUTF after a chunk marker: an empty string for a
    project in its default place, "URI//file:/..." for a project elsewhere. A file that cannot be read
    that way raises ValueError, and the caller treats the project as one it cannot carry.
    """
    data = location_file.read_bytes()
    offset = len(_LOCATION_MAGIC) if data.startswith(_LOCATION_MAGIC) else 0
    if len(data) < offset + 2:
        raise ValueError("too short")
    length = int.from_bytes(data[offset:offset + 2], "big")
    text = data[offset + 2:offset + 2 + length].decode("utf-8")
    if not text:
        return None
    if not text.startswith("URI//"):
        raise ValueError(f"unexpected location {text!r}")
    uri = urllib.parse.urlparse(text[len("URI//"):])
    if uri.scheme != "file":
        raise ValueError(f"not a file location: {text[len('URI//'):]}")
    return Path(urllib.request.url2pathname(uri.path))


def external_projects(workspace: Path) -> list[str]:
    """Projects of the workspace whose files live elsewhere: an archive of the directory lacks them.

    EDT records a location for projects inside the workspace as well, and Eclipse keeps a hidden
    project of its own under .metadata; only a location outside the workspace directory counts.
    """
    meta = workspace / _PROJECTS_META
    if not meta.is_dir():
        return []
    root = _key(workspace)
    outside = []
    for entry in sorted(meta.iterdir()):
        location_file = entry / ".location"
        if not location_file.is_file():
            continue
        try:
            location = project_location(location_file)
        except (OSError, ValueError, UnicodeDecodeError):
            outside.append(f"{entry.name} (its location cannot be read)")
            continue
        if location is not None and not (_key(location) + os.sep).startswith(root + os.sep):
            outside.append(f"{entry.name} at {location}")
    return outside


def pack(workspace: Path, archive: Path, *, edt_running) -> dict:
    """Archive a stopped workspace; edt_running(path) names what still uses it, or returns None."""
    workspace = Path(os.path.abspath(workspace))
    archive = Path(os.path.abspath(archive))
    compression = _compression(archive)
    if not (workspace / ".metadata").is_dir():
        raise WorkspaceError(f"{workspace} is not an EDT workspace: it has no .metadata")
    reason = edt_running(workspace)
    if reason:
        raise WorkspaceError(f"EDT is using {workspace} ({reason}); stop it first (edt-bridge-mcp shutdown), "
                             "so that it saves its state and releases the lock")
    if archive.exists():
        raise WorkspaceError(f"{archive} already exists; choose a new archive name")
    if _key(archive).startswith(_key(workspace) + os.sep):
        raise WorkspaceError("put the archive outside the workspace it packs")
    outside = external_projects(workspace)
    if outside:
        raise WorkspaceError("these projects live outside the workspace directory, and the archive would "
                             "not carry them: " + ", ".join(outside))
    projects = sorted(entry.name for entry in workspace.iterdir()
                      if entry.is_dir() and (entry / ".project").is_file())
    files = total = 0
    entries = []
    for folder, directories, names in os.walk(workspace):
        directories.sort()
        here = Path(folder)
        for name in sorted(directories) + sorted(names):
            path = here / name
            relative = path.relative_to(workspace).as_posix()
            if _excluded(relative):
                if name in directories:
                    directories.remove(name)
                continue
            if path.is_symlink() or not (path.is_dir() or path.is_file()):
                raise WorkspaceError(f"{relative} is a link or a special file; the archive takes files and "
                                     "directories only")
            if _unsafe_name(relative):
                # Checked here as well, so that an archive restore would refuse is never written.
                raise WorkspaceError(f"{relative}: a name with a colon or a backslash cannot be restored on "
                                     "every system; rename the file before packing")
            entries.append((path, relative))
            if path.is_file():
                files += 1
                total += path.stat().st_size
    manifest = {"schema": SCHEMA, "workspace": str(workspace), "os": os.name,
                "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
                "wrapperVersion": __version__, "projects": projects, "files": files, "bytes": total,
                "excluded": list(EXCLUDES)}
    staged = archive.with_name(archive.name + ".part")
    try:
        # A tar is bytes: the file is opened in binary, and the compressor sits between it and tar.
        with open(staged, "xb") as raw:
            compressor = _compressor(compression, raw)
            try:
                with tarfile.TarFile(fileobj=compressor or raw, mode="w", format=tarfile.PAX_FORMAT) as tar:
                    data = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
                    info = tarfile.TarInfo(MANIFEST)
                    info.size, info.mtime, info.mode = len(data), datetime.datetime.now().timestamp(), 0o644
                    tar.addfile(info, io.BytesIO(data))
                    for path, relative in entries:
                        tar.add(path, arcname=relative, recursive=False)
            finally:
                if compressor is not None:
                    compressor.close()
        staged.replace(archive)
    except BaseException:
        staged.unlink(missing_ok=True)
        raise
    return {**manifest, "archive": str(archive), "archiveBytes": archive.stat().st_size}


def read_manifest(archive: Path) -> dict:
    with _read(archive) as tar:
        first = tar.next()
        if first is None or first.name != MANIFEST or not first.isfile():
            raise WorkspaceError(f"{archive} is not a workspace archive of edt-bridge-mcp: it has no {MANIFEST}")
        manifest = json.loads(tar.extractfile(first).read().decode("utf-8"))
    if manifest.get("schema") != SCHEMA or not isinstance(manifest.get("workspace"), str):
        raise WorkspaceError(f"{archive} has a manifest this wrapper does not read (schema {manifest.get('schema')})")
    return manifest


def restore(archive: Path, workspace: Path | None, *, edt_running) -> dict:
    """Unpack the archive under the path it was packed from; workspace, if given, must be that path."""
    archive = Path(os.path.abspath(archive))
    if not archive.is_file():
        raise WorkspaceError(f"no archive at {archive}")
    manifest = read_manifest(archive)
    target = Path(manifest["workspace"])
    if manifest.get("os") != os.name:
        raise WorkspaceError(f"the workspace was packed on {manifest.get('os')} at {target}; EDT keeps its "
                             "absolute paths, so restore it on the same kind of system")
    if workspace is not None and _key(Path(os.path.abspath(workspace))) != _key(target):
        raise WorkspaceError(f"the workspace was packed at {target}; EDT keeps that absolute path in its "
                             f"metadata, so it is restored there and not at {os.path.abspath(workspace)}")
    reason = edt_running(target)
    if reason:
        raise WorkspaceError(f"EDT is using {target} ({reason}); stop it before the restore")
    if target.exists() and (not target.is_dir() or any(target.iterdir())):
        raise WorkspaceError(f"{target} exists and is not empty; restore into an empty or missing directory")
    created = not target.exists()
    target.mkdir(parents=True, exist_ok=True)
    # One pass from start to end: every entry is checked as it comes, then written. The data filter keeps
    # the times and refuses what is unsafe even past that check.
    options = {"filter": "data"} if hasattr(tarfile, "data_filter") else {}
    directories = []
    try:
        with _stream(archive) as tar:
            for member in tar:
                if member.name == MANIFEST:
                    continue
                if _unsafe_name(member.name) or not (member.isfile() or member.isdir()):
                    raise WorkspaceError(f"the archive holds an entry that cannot be restored safely: {member.name}")
                if member.isdir():
                    directories.append((member.name, member.mtime))
                tar.extract(member, target, **options)
    except BaseException:
        # Nothing half restored is left behind: the directory was empty or missing before.
        shutil.rmtree(target, ignore_errors=True)
        if not created:
            target.mkdir(parents=True, exist_ok=True)
        raise
    # A file written into a directory changes its time, so the directory times are set last, deepest first.
    for name, mtime in sorted(directories, key=lambda item: item[0].count("/"), reverse=True):
        os.utime(target / name, (mtime, mtime))
    return {"workspace": str(target), "projects": manifest.get("projects", []), "files": manifest.get("files"),
            "bytes": manifest.get("bytes"), "packedAt": manifest.get("createdAt")}


# -- the checks of EDT before and after ------------------------------------------------------------


def _problem_rows(value: dict) -> list[list]:
    rows = [[row.get("severity"), row.get("resource"), row.get("line"), row.get("message"), row.get("checkId")]
            for row in value.get("problems") or [] if isinstance(row, dict)]
    return sorted(rows, key=lambda row: json.dumps(row, ensure_ascii=False))


def snapshot_project(answer: dict) -> dict:
    """What one edt_project_errors answer says, without the fields that differ from run to run."""
    settled = answer.get("settled", (answer.get("refreshed") or {}).get("settled"))
    return {"complete": answer.get("complete"), "settled": settled, "truncated": answer.get("truncated"),
            "total": answer.get("total"), "bySeverity": answer.get("bySeverity"),
            "problems": _problem_rows(answer)}


def usable(project: dict) -> bool:
    return bool(project.get("complete")) and project.get("settled") is not False and not project.get("truncated")


def check_arguments(project: str, wait: int, fqn: str | None = None) -> dict:
    """edt_project_errors for a whole project, or for one object of it when fqn is given."""
    arguments = {"projectName": project, "refresh": True, "waitSeconds": wait, "severity": _SEVERITIES,
                 "limit": _PROBLEM_LIMIT}
    if fqn:
        arguments["fqn"] = fqn
    return arguments


def check_key(project: str, fqn: str | None) -> str:
    """The name a check is saved under: the project, or the project and the object."""
    return f"{project} {fqn}" if fqn else project


def compare(before: dict, after: dict) -> dict:
    """Project by project: equal, or what only one side has."""
    result, equal = {}, True
    for name in sorted(set(before) | set(after)):
        first, second = before.get(name), after.get(name)
        if first is None or second is None:
            result[name] = {"equal": False, "missing": "before" if first is None else "after"}
            equal = False
            continue
        rows_before = {json.dumps(row, ensure_ascii=False) for row in first["problems"]}
        rows_after = {json.dumps(row, ensure_ascii=False) for row in second["problems"]}
        same = rows_before == rows_after and first.get("total") == second.get("total")
        row = {"equal": same, "total": [first.get("total"), second.get("total")]}
        if not same:
            row["onlyBefore"] = [json.loads(item) for item in sorted(rows_before - rows_after)][:20]
            row["onlyAfter"] = [json.loads(item) for item in sorted(rows_after - rows_before)][:20]
        result[name] = row
        equal &= same
    return {"equal": equal, "projects": result}
