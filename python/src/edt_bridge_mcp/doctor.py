"""doctor: what stands between EDT and a working bridge, checked without a bridge.

Every check only reads files and asks the system about processes: nothing is started, changed or
repaired. That is the point of the command - it answers when the bridge does not, which is
exactly when `status` has nothing to say. Each problem comes with the step that fixes it.

The checks follow a breakage that cost a reinstalled EDT: a jar EDT could not read after an
elevated self-update, a per-user configuration still registering a replaced jar, and an EDT
started from an elevated console that kept its configuration in the install directory instead.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

from . import __version__, server, update

#: How much of the end of the workspace log is read: the last sessions, not the whole history.
LOG_TAIL = 4 * 1024 * 1024
_SESSION = re.compile(r"^!SESSION (\S+ \S+)", re.M)
_ENTRY = re.compile(r"^!ENTRY \S+ \S+ \S+ (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", re.M)


def process_elevated(pid: int) -> bool | None:
    """Whether a process runs with an elevated token on Windows; None elsewhere or when Windows
    does not answer. A process of the same user answers without elevation of the caller."""
    if os.name != "nt":
        return None
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32")
    advapi32 = ctypes.WinDLL("advapi32")
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    advapi32.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                          ctypes.POINTER(wintypes.HANDLE)]
    advapi32.GetTokenInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
                                             wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    process_query_limited_information, token_query, token_elevation = 0x1000, 0x0008, 20
    process = kernel32.OpenProcess(process_query_limited_information, False, pid)
    if not process:
        return None
    try:
        token = wintypes.HANDLE()
        if not advapi32.OpenProcessToken(process, token_query, ctypes.byref(token)):
            return None
        try:
            value, size = wintypes.DWORD(), wintypes.DWORD()
            if not advapi32.GetTokenInformation(token, token_elevation, ctypes.byref(value),
                                                ctypes.sizeof(value), ctypes.byref(size)):
                return None
            return bool(value.value)
        finally:
            kernel32.CloseHandle(token)
    finally:
        kernel32.CloseHandle(process)


def _jars(dropins: Path, problems: list[str]) -> list[dict]:
    jars = sorted(dropins.glob(update.JAR_PREFIX + "*.jar"), key=lambda path: path.name)
    if not jars:
        problems.append(f"no edt-bridge jar in {dropins} - run edt-bridge-mcp self-update "
                        "--jar-only as administrator")
    elif len(jars) > 1:
        problems.append(f"{dropins} holds {len(jars)} edt-bridge jars, and Equinox may load any "
                        f"of them - close EDT and leave only {jars[-1].name}")
    found = []
    for jar in jars:
        unreadable = server._unreadable_jar(jar)
        protected = update._dacl_protected(jar)
        if unreadable:
            problems.append(unreadable)
        elif protected:
            problems.append(update._unreadable_jar(jar))
        found.append({"version": jar.name[len(update.JAR_PREFIX):-len(".jar")], "path": str(jar),
                      "readable": unreadable is None,
                      "inheritsFolderPermissions": None if protected is None else not protected})
    return found


def _registered(configuration: Path) -> dict:
    """The bridge versions an Equinox configuration registers, and the jars its start list names."""
    info = configuration / "org.eclipse.equinox.simpleconfigurator" / "bundles.info"
    registers = [fields[1] for row in update._configuration_lines(info, lambda _message: None)
                 if len(fields := row.split(",")) >= 3 and fields[0] == "io.github.keyfire.edtbridge"]
    start_list = "\n".join(update._configuration_lines(configuration / "config.ini",
                                                       lambda _message: None, "latin-1"))
    return {"registers": registers,
            "startList": update._BRIDGE_JAR.findall(start_list.replace("\\:", ":"))}


def _configurations(edt: Path, workspace: str | None) -> list[dict]:
    areas = [("install", edt / "configuration")]
    if workspace:
        areas.append(("workspace", Path(workspace) / "eclipsecfg"))
    areas += [("user", area) for area in update._user_configurations(edt)]
    return [{"kind": kind, "path": str(path), **_registered(path)}
            for kind, path in areas if path.is_dir()]


def _processes(backend, edt: Path | None, problems: list[str]) -> list[dict]:
    """The EDT processes of this install. Windows shows a console without elevation neither the
    path nor the command line of an elevated process, so such a process is kept as one whose
    install is unknown: it is the very process this check is after."""
    found = []
    install = backend._path_key(str(edt)) if edt else None
    for process in backend._edt_processes():
        args = backend._args(process)
        hidden = not args and not process.get("ExecutablePath")
        executable = Path(process.get("ExecutablePath") or (args[0] if args else ""))
        if not hidden and install and backend._path_key(str(executable.parent)) != install:
            continue
        pid = process.get("ProcessId")
        image = executable.name or process.get("Name") or "EDT"
        workspace = args[args.index("-data") + 1] if "-data" in args[:-1] else None
        elevated = process_elevated(pid) if isinstance(pid, int) else None
        found.append({"pid": pid, "image": image, "workspace": workspace, "elevated": elevated,
                      "commandLineHidden": hidden})
        if elevated:
            where = ("its install directory" if hidden
                     else str(executable.parent / "configuration"))
            problems.append(
                f"EDT process {pid} ({image}) runs as administrator: it keeps its configuration "
                f"in {where}, and the next start without elevation may reset the one in "
                "~/.eclipse. Close it and start EDT once with -clean from a console without "
                "administrator rights")
    return found


def _workspace_log(workspace: str | None, problems: list[str]) -> dict | None:
    """The last failure of the plugin in the workspace log, and whether the last session had it."""
    if not workspace:
        return None
    path = Path(workspace) / ".metadata" / ".log"
    try:
        with open(path, "rb") as handle:
            size = handle.seek(0, os.SEEK_END)
            handle.seek(max(0, size - LOG_TAIL))
            text = handle.read().decode("utf-8", errors="replace")
    except OSError:
        return None
    sessions = list(_SESSION.finditer(text))
    session_start = sessions[-1].start() if sessions else 0
    failures = [(match.start(), match[0].strip())
                for pattern in (server._UNREADABLE_JAR, server._UNRESOLVED_PLUGIN)
                for match in pattern.finditer(text)]
    report = {"path": str(path), "lastSession": sessions[-1][1] if sessions else None,
              "lastPluginFailure": None}
    if failures:
        position, line = max(failures)
        entries = [entry for entry in _ENTRY.finditer(text) if entry.start() < position]
        current = position >= session_start
        report["lastPluginFailure"] = {"line": line, "logged": entries[-1][1] if entries else None,
                                       "inLastSession": current}
        if current:
            problems.append(f"the last EDT session on {workspace} did not load the edt-bridge "
                            f"plugin: {line} (see {path})")
    return report


def diagnose(backend) -> dict:
    """Everything doctor checks, as one JSON-ready report; ``problems`` lists what to fix and
    ``notes`` what EDT puts right by itself on its next start."""
    problems: list[str] = []
    notes: list[str] = []
    cli = backend._find_cli()
    dropins = cli.parent / "dropins" if cli else update._find_dropins()
    edt = dropins.parent if dropins else None
    if edt is None:
        problems.append("EDT is not found - pass --edt-dir or set EDT_BRIDGE_EDT_DIR to the EDT "
                        "install directory (the folder with 1cedtcli and dropins)")
    jars = _jars(dropins, problems) if dropins else []
    configurations = _configurations(edt, backend.workspace) if edt else []
    if dropins:
        update.warn_stale_configurations(dropins, emit=problems.append, note=notes.append)
    processes = _processes(backend, edt, problems)
    status = backend.status()
    bridge = None if status is None else {"port": backend._active_port,
                                          "version": status.get("version")}
    serving = [process for process in processes if not backend.workspace or (
        process["workspace"]
        and backend._path_key(process["workspace"]) == backend._path_key(backend.workspace))]
    if bridge is None and serving:
        problems.append(f"EDT runs (process {', '.join(str(p['pid']) for p in serving)}), but no "
                        f"bridge answers on ports {backend.port}-{backend.port + backend.scan_range}: "
                        "the edt-bridge plugin is not running in it")
    return {
        "wrapperVersion": __version__,
        "elevatedConsole": update._elevated(),
        "edtInstall": str(edt) if edt else None,
        "workspace": backend.workspace,
        "jars": jars,
        "configurations": configurations,
        "edtProcesses": processes,
        "bridge": bridge,
        "workspaceLog": _workspace_log(backend.workspace, problems),
        "problems": problems,
        "notes": notes,
    }
