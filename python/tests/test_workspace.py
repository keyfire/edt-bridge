"""Pack and restore a ready EDT workspace, and compare the EDT checks before and after."""
import io
import json
import os
import shutil
import tarfile
from pathlib import Path

import pytest

from edt_bridge_mcp import cli, workspace as packing

#: A modification time with a fraction of a second: EDT compares times with the state it saved.
STAMP = 1794480000.123456


def _workspace(root: Path) -> Path:
    ws = root / "ws"
    (ws / ".metadata" / ".plugins" / "org.eclipse.core.resources" / ".projects" / "Demo").mkdir(parents=True)
    (ws / ".metadata" / ".plugins" / "com.example" ).mkdir(parents=True)
    (ws / ".metadata" / ".plugins" / "com.example" / "index.log").write_text("kept: not a top-level log")
    (ws / ".metadata" / ".log").write_text("-Dedt.bridge.token=secret")
    (ws / ".metadata" / "edt-2026.log").write_text("command line with the token")
    (ws / ".metadata" / ".bak_0.log").write_text("old log")
    (ws / ".metadata" / ".lock").write_text("")
    (ws / ".metadata" / "1cedtcli.pid").write_text("47792")
    (ws / ".metadata" / "telemetry.log.0.lck").write_text("")
    (ws / ".metadata" / "version.ini").write_text("org.eclipse.core.runtime=2")
    module = ws / "Demo" / "src" / "CommonModules" / "Тест" / "Module.bsl"
    module.parent.mkdir(parents=True)
    (ws / "Demo" / ".project").write_text("<projectDescription/>")
    module.write_text("Процедура Тест() КонецПроцедуры", encoding="utf-8")
    for path in [module, ws / "Demo" / ".project", ws / ".metadata" / "version.ini"]:
        os.utime(path, (STAMP, STAMP))
    # A directory keeps its time too: files written into it during the restore must not change it.
    os.utime(module.parent, (STAMP, STAMP))
    return ws


def _idle(path):
    return None


@pytest.mark.parametrize("suffix", [".tar", ".tar.gz", pytest.param(".tar.zst", marks=pytest.mark.skipif(
    "zst" not in tarfile.TarFile.OPEN_METH, reason="zstd needs Python 3.14"))])
def test_a_workspace_comes_back_under_its_path_with_its_times_and_without_its_logs(tmp_path, suffix):
    ws = _workspace(tmp_path)
    archive = tmp_path / "out" / ("ready" + suffix)
    archive.parent.mkdir()

    packed = packing.pack(ws, archive, edt_running=_idle)
    assert packed["projects"] == ["Demo"]
    assert not (archive.parent / ("ready" + suffix + ".part")).exists()
    with tarfile.open(archive) as tar:
        names = tar.getnames()
    assert names[0] == packing.MANIFEST
    assert ".metadata/.log" not in names and ".metadata/edt-2026.log" not in names
    assert ".metadata/.bak_0.log" not in names and ".metadata/.lock" not in names
    assert ".metadata/1cedtcli.pid" not in names and ".metadata/telemetry.log.0.lck" not in names
    assert ".metadata/.plugins/com.example/index.log" in names

    shutil.move(str(ws), str(tmp_path / "ws-original"))
    restored = packing.restore(archive, None, edt_running=_idle)

    assert restored["workspace"] == str(ws)
    module = ws / "Demo" / "src" / "CommonModules" / "Тест" / "Module.bsl"
    assert module.read_text(encoding="utf-8") == "Процедура Тест() КонецПроцедуры"
    assert abs(module.stat().st_mtime - STAMP) < 1e-5
    assert abs((ws / ".metadata" / "version.ini").stat().st_mtime - STAMP) < 1e-5
    assert abs(module.parent.stat().st_mtime - STAMP) < 1e-5
    assert not (ws / ".metadata" / ".log").exists()
    assert not (ws / packing.MANIFEST).exists()


def test_pack_refuses_while_edt_runs_and_names_what_holds_the_workspace(tmp_path):
    ws = _workspace(tmp_path)
    archive = tmp_path / "ready.tar"

    with pytest.raises(packing.WorkspaceError, match="EDT process 4242 runs on it.*shutdown"):
        packing.pack(ws, archive, edt_running=lambda path: "EDT process 4242 runs on it")
    assert not archive.exists()


@pytest.mark.parametrize("case", ["exists", "inside", "external", "not-a-workspace", "suffix"])
def test_pack_refuses_what_it_cannot_carry(tmp_path, case):
    ws = _workspace(tmp_path)
    archive = tmp_path / "ready.tar"
    if case == "exists":
        archive.write_bytes(b"")
    elif case == "inside":
        archive = ws / "ready.tar"
    elif case == "external":
        (ws / ".metadata/.plugins/org.eclipse.core.resources/.projects/Demo/.location").write_bytes(b"\x00")
    elif case == "not-a-workspace":
        ws = tmp_path / "plain"
        ws.mkdir()
    else:
        archive = tmp_path / "ready.zip"

    with pytest.raises(packing.WorkspaceError):
        packing.pack(ws, archive, edt_running=_idle)


def _packed(tmp_path):
    ws = _workspace(tmp_path)
    archive = tmp_path / "ready.tar"
    packing.pack(ws, archive, edt_running=_idle)
    return ws, archive


def test_restore_refuses_another_path_a_busy_target_and_a_running_edt(tmp_path):
    ws, archive = _packed(tmp_path)

    with pytest.raises(packing.WorkspaceError, match="restored there and not at"):
        packing.restore(archive, tmp_path / "elsewhere", edt_running=_idle)
    with pytest.raises(packing.WorkspaceError, match="not empty"):
        packing.restore(archive, ws, edt_running=_idle)
    shutil.rmtree(ws)
    with pytest.raises(packing.WorkspaceError, match="stop it before the restore"):
        packing.restore(archive, ws, edt_running=lambda path: "its .metadata/.lock is held")
    assert not ws.exists()
    assert packing.restore(archive, ws, edt_running=_idle)["workspace"] == str(ws)


def test_restore_refuses_an_archive_of_another_system(tmp_path):
    ws, archive = _packed(tmp_path)
    manifest = packing.read_manifest(archive)
    foreign = tmp_path / "foreign.tar"
    with tarfile.open(foreign, "w", format=tarfile.PAX_FORMAT) as tar:
        data = json.dumps({**manifest, "os": "posix" if os.name == "nt" else "nt"}).encode()
        info = tarfile.TarInfo(packing.MANIFEST)
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
    shutil.rmtree(ws)

    with pytest.raises(packing.WorkspaceError, match="same kind of system"):
        packing.restore(foreign, None, edt_running=_idle)


@pytest.mark.parametrize("name", ["../escape.txt", "/etc/escape.txt", "C:/escape.txt"])
def test_restore_refuses_an_entry_outside_the_workspace(tmp_path, name):
    target = tmp_path / "ws"
    archive = tmp_path / "evil.tar"
    with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as tar:
        for member, data in ((packing.MANIFEST, json.dumps({"schema": 1, "workspace": str(target),
                                                              "os": os.name}).encode()), (name, b"x")):
            info = tarfile.TarInfo(member)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))

    with pytest.raises(packing.WorkspaceError, match="cannot be restored safely"):
        packing.restore(archive, None, edt_running=_idle)
    assert not (tmp_path / "escape.txt").exists()


def test_an_archive_without_the_manifest_is_not_taken(tmp_path):
    archive = tmp_path / "plain.tar"
    with tarfile.open(archive, "w") as tar:
        info = tarfile.TarInfo("file.txt")
        tar.addfile(info, io.BytesIO(b""))

    with pytest.raises(packing.WorkspaceError, match="not a workspace archive"):
        packing.restore(archive, None, edt_running=_idle)


# -- checks ----------------------------------------------------------------------------------------


def _answer(*problems, complete=True, settled=True, truncated=False):
    return {"complete": complete, "refreshed": {"settled": settled}, "truncated": truncated,
            "total": len(problems), "bySeverity": {"ERROR": len(problems)}, "elapsedMs": 812,
            "problems": [{"severity": "ERROR", "resource": "src/Module.bsl", "line": line,
                          "message": text, "checkId": "SU1", "markerId": line * 7} for line, text in problems]}


class ChecksBackend:
    def __init__(self, answers, ready=True):
        self.answers = answers
        self.ready = ready
        self.workspace = None
        self.calls = []

    def status(self):
        return {"version": "0.43.0", "workspace": "/ws", "projects": [
            {"name": name, "open": True, "dtProject": True, "modelReady": self.ready} for name in self.answers]}

    def call_tool(self, name, arguments):
        self.calls.append((name, arguments))
        return json.dumps(self.answers[arguments["projectName"]])


def _checks(backend, *argv, capsys):
    code = cli._workspace(backend, cli._parse("workspace", ["checks", *argv]))
    out, err = capsys.readouterr()
    return code, out, err


def test_saved_checks_compare_equal_after_a_restore_and_differ_when_a_problem_moves(tmp_path, capsys):
    saved = tmp_path / "before.json"
    code, out, _ = _checks(ChecksBackend({"Demo": _answer((3, "Variable is not used"))}),
                           "--output", str(saved), capsys=capsys)
    assert code == 0
    assert json.loads(out)["totals"] == {"Demo": 1}
    assert "elapsedMs" not in saved.read_text(encoding="utf-8")

    # Run-to-run fields such as the marker id and the time do not count.
    after = _answer((3, "Variable is not used"))
    after["problems"][0]["markerId"], after["elapsedMs"] = 999, 5
    code, out, _ = _checks(ChecksBackend({"Demo": after}), "--compare", str(saved), capsys=capsys)
    assert (code, json.loads(out)["equal"]) == (0, True)

    code, out, err = _checks(ChecksBackend({"Demo": _answer((4, "Variable is not used"))}),
                             "--compare", str(saved), capsys=capsys)
    assert code == 2
    project = json.loads(out)["projects"]["Demo"]
    assert project["onlyBefore"][0][2] == 3 and project["onlyAfter"][0][2] == 4
    assert "differ" in err


def test_checks_refuse_an_incomplete_validation_and_projects_that_are_not_ready(tmp_path, capsys):
    code, _, err = _checks(ChecksBackend({"Demo": _answer(settled=False)}), "--output",
                           str(tmp_path / "a.json"), capsys=capsys)
    assert code == 1 and "did not finish" in err and "raise --wait" in err
    code, _, err = _checks(ChecksBackend({"Demo": _answer(truncated=True)}), "--output",
                           str(tmp_path / "t.json"), capsys=capsys)
    assert code == 1 and "narrow the check with --object" in err
    backend = ChecksBackend({"Demo": _answer()}, ready=False)
    code, _, err = _checks(backend, "--output", str(tmp_path / "b.json"), capsys=capsys)
    assert code == 1 and "run wait-ready first" in err and backend.calls == []
    assert not (tmp_path / "a.json").exists() and not (tmp_path / "b.json").exists()


def test_checks_can_be_narrowed_to_objects(tmp_path, capsys):
    backend = ChecksBackend({"Demo": _answer((3, "Variable is not used"))})
    saved = tmp_path / "objects.json"

    code, out, _ = _checks(backend, "--object", "Document.Order", "--object", "Catalog.Items",
                           "--output", str(saved), capsys=capsys)

    assert code == 0
    assert [arguments.get("fqn") for _, arguments in backend.calls] == ["Document.Order", "Catalog.Items"]
    assert sorted(json.loads(out)["totals"]) == ["Demo Catalog.Items", "Demo Document.Order"]


def test_checks_ask_edt_for_a_complete_refreshed_list(tmp_path, capsys):
    backend = ChecksBackend({"Demo": _answer(), "Base": _answer()})
    code, _, _ = _checks(backend, "--project", "Demo", "--wait", "900", "--output", str(tmp_path / "c.json"),
                         capsys=capsys)
    assert code == 0
    assert backend.calls == [("edt_project_errors", {"projectName": "Demo", "refresh": True, "waitSeconds": 900,
                                                     "severity": "ERROR,WARNING,INFO", "limit": 10000})]


class PackBackend:
    def __init__(self, owner=None, locked=False, answering=False):
        self.workspace = None
        self.owner, self.locked, self.answering = owner, locked, answering

    def status(self):
        return {"workspace": self.workspace} if self.answering else None

    def _workspace_owner(self):
        return self.owner

    def _workspace_locked(self):
        return self.locked


def test_the_command_packs_and_refuses_with_the_reason(tmp_path, capsys):
    ws = _workspace(tmp_path)
    backend = PackBackend(owner={"ProcessId": 4242})
    backend.workspace = str(ws)
    args = cli._parse("workspace", ["pack", "--archive", str(tmp_path / "a.tar")])

    assert cli._workspace(backend, args) == 1
    assert "EDT process 4242 runs on it" in capsys.readouterr().err

    backend = PackBackend()
    backend.workspace = str(ws)
    assert cli._workspace(backend, args) == 0
    assert json.loads(capsys.readouterr().out)["projects"] == ["Demo"]


def _location_file(folder: Path, location: str) -> Path:
    data = location.encode("utf-8")
    path = folder / ".location"
    path.write_bytes(packing._LOCATION_MAGIC + len(data).to_bytes(2, "big") + data + b"\x00\x00\x00\x00")
    return path


def test_a_project_located_inside_the_workspace_is_carried_and_one_outside_is_not(tmp_path):
    ws = _workspace(tmp_path)
    projects = ws / ".metadata/.plugins/org.eclipse.core.resources/.projects"
    _location_file(projects / "Demo", "")
    hidden = projects / ".org.eclipse.egit.core.cmp"
    hidden.mkdir()
    _location_file(hidden, "URI//" + (ws / ".metadata/.plugins/org.eclipse.egit.core/.cmp").as_uri())
    assert packing.external_projects(ws) == []

    elsewhere = tmp_path / "sources" / "Проект Б"
    (projects / "Base").mkdir()
    _location_file(projects / "Base", "URI//" + elsewhere.as_uri())
    assert packing.project_location(projects / "Base" / ".location") == elsewhere
    assert packing.external_projects(ws) == [f"Base at {elsewhere}"]
    with pytest.raises(packing.WorkspaceError, match="Base at"):
        packing.pack(ws, tmp_path / "ready.tar", edt_running=_idle)


@pytest.mark.skipif(os.name == "nt", reason="Windows has no file names with a colon or a backslash")
@pytest.mark.parametrize("name", ["report:v2.txt", r"back\slash.txt"])
def test_pack_refuses_a_name_restore_would_refuse(tmp_path, name):
    ws = _workspace(tmp_path)
    (ws / "Demo" / name).write_text("x")

    with pytest.raises(packing.WorkspaceError, match="cannot be restored"):
        packing.pack(ws, tmp_path / "ready.tar", edt_running=_idle)
    assert not (tmp_path / "ready.tar").exists()


def test_a_refused_restore_leaves_no_half_restored_files(tmp_path):
    target = tmp_path / "ws"
    archive = tmp_path / "half.tar"
    with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as tar:
        for member, data in ((packing.MANIFEST, json.dumps({"schema": 1, "workspace": str(target),
                                                              "os": os.name}).encode()),
                             ("Demo/ok.txt", b"first"), ("../escape.txt", b"x")):
            info = tarfile.TarInfo(member)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))

    with pytest.raises(packing.WorkspaceError):
        packing.restore(archive, None, edt_running=_idle)
    assert not target.exists()
