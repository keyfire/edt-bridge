"""scripts/run-headless.ps1 installs the jar it is given, and only that one, before EDT starts.

The functions run in Windows PowerShell 5.1, the shell the script is written for. The launcher
itself is only driven into its refusals here: every one of them comes before it stops a process,
and a test that got past them would stop the headless EDT of the machine it runs on.
"""

import os
import subprocess
from pathlib import Path

import pytest

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell launcher scripts")

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"


def _powershell(tmp_path, body, *args):
    script = tmp_path / "probe.ps1"
    script.write_text("param($Common, $A, $B)\n"
                      "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
                      ". $Common\n" + body + "\n", encoding="ascii")
    return subprocess.run(
        ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
         str(SCRIPTS / "edt-common.ps1"), *map(str, args)],
        capture_output=True, text=True, encoding="utf-8", stdin=subprocess.DEVNULL, timeout=60,
    )


def _jar(folder, version):
    path = folder / f"io.github.keyfire.edtbridge_{version}.jar"
    path.write_bytes(version.encode("ascii"))
    return path


def test_the_version_comes_from_the_jar_name(tmp_path):
    result = _powershell(tmp_path, "Get-BridgeJarVersion -Path $A\nGet-BridgeJarVersion -Path $B\n"
                                   "if ($null -eq (Get-BridgeJarVersion -Path 'bridge.jar')) { 'none' }",
                         r"C:\build\io.github.keyfire.edtbridge_0.42.2.202611121549.jar",
                         "io.github.keyfire.edtbridge_1.0.0.jar")

    assert result.returncode == 0, result.stderr
    assert result.stdout.split() == ["0.42.2.202611121549", "1.0.0", "none"]


def test_the_given_jar_replaces_every_bridge_jar_in_dropins(tmp_path):
    edt = tmp_path / "edt"
    dropins = edt / "dropins"
    dropins.mkdir(parents=True)
    _jar(dropins, "0.42.2.202611121143")
    _jar(dropins, "0.41.0.202611100900")
    other = dropins / "another.plugin_1.0.0.jar"
    other.write_bytes(b"not ours")
    build = tmp_path / "build"
    build.mkdir()
    fresh = _jar(build, "0.42.2.202611121549")

    result = _powershell(tmp_path, "if (Install-DropinJar -EdtDir $A -Jar $B -WaitSec 1) { 'installed' }",
                         edt, fresh)

    assert result.returncode == 0, result.stderr
    assert "installed" in result.stdout
    assert sorted(path.name for path in dropins.iterdir()) == [
        "another.plugin_1.0.0.jar", "io.github.keyfire.edtbridge_0.42.2.202611121549.jar"]
    assert fresh.exists()


def test_a_jar_already_in_dropins_stays_and_its_neighbours_go(tmp_path):
    edt = tmp_path / "edt"
    dropins = edt / "dropins"
    dropins.mkdir(parents=True)
    kept = _jar(dropins, "0.42.2.202611121143")
    _jar(dropins, "0.42.2.202611121549")

    result = _powershell(tmp_path, "if (Install-DropinJar -EdtDir $A -Jar $B -WaitSec 1) { 'installed' }",
                         edt, kept)

    assert result.returncode == 0, result.stderr
    assert "installed" in result.stdout
    assert [path.name for path in dropins.iterdir()] == [kept.name]
    assert kept.read_bytes() == b"0.42.2.202611121143"


def test_a_locked_jar_is_reported_and_nothing_is_copied(tmp_path):
    edt = tmp_path / "edt"
    dropins = edt / "dropins"
    dropins.mkdir(parents=True)
    locked = _jar(dropins, "0.42.2.202611121143")
    fresh = _jar(tmp_path, "0.42.2.202611121549")

    # Python opens files without FILE_SHARE_DELETE: while the handle is open, the jar cannot go,
    # the way it cannot while an EDT process has it open.
    with locked.open("rb"):
        result = _powershell(tmp_path, "if (-not (Install-DropinJar -EdtDir $A -Jar $B -WaitSec 1)) { 'refused' }",
                             edt, fresh)

    assert result.returncode == 0, result.stderr
    assert "refused" in result.stdout
    assert "nothing was copied" in result.stderr + result.stdout
    assert [path.name for path in dropins.iterdir()] == [locked.name]


def _launcher(tmp_path, *args):
    return subprocess.run(
        ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
         str(SCRIPTS / "run-headless.ps1"), "-Workspace", str(tmp_path / "ws"),
         "-EdtDir", str(tmp_path / "edt"), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        stdin=subprocess.DEVNULL, timeout=60,
    )


@pytest.mark.parametrize("case", ["both", "missing", "foreign"])
def test_the_launcher_refuses_a_bad_jar_before_it_stops_anything(tmp_path, case):
    foreign = tmp_path / "bridge-latest.jar"
    foreign.write_bytes(b"jar")
    args = {
        "both": ["-Jar", str(foreign), "-FromBuild"],
        "missing": ["-Jar", str(tmp_path / "io.github.keyfire.edtbridge_0.42.2.1.jar")],
        "foreign": ["-Jar", str(foreign)],
    }[case]

    result = _launcher(tmp_path, *args)

    assert result.returncode == 2, result.stdout + result.stderr
    # The installation is printed after the jar checks; a refusal never gets that far.
    assert "EDT installation:" not in result.stdout
