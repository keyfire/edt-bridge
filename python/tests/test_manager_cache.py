"""The cache of the test manager infobase (EDT-TEST-13): built once per platform build and test driver."""
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from edt_bridge_mcp import native_cache, native_form

from test_native_form import _fixture_args

CONFIGURATION = '<MetaDataObject version="2.21"/>'


# -- the cache itself --------------------------------------------------------------------------------


def _infobase(tmp_path, content=b"manager infobase"):
    base = tmp_path / "built"
    base.mkdir(exist_ok=True)
    (base / native_cache.DATA_FILE).write_bytes(content)
    return base


def test_the_cache_lives_in_the_user_cache_unless_the_variable_says_otherwise(tmp_path):
    assert native_cache.root({"XDG_CACHE_HOME": str(tmp_path)}) == (tmp_path / "edt-bridge" / "test-manager", None)
    assert native_cache.root({native_cache.ENV_CACHE: str(tmp_path / "baked")}) == (tmp_path / "baked", None)
    directory, reason = native_cache.root({native_cache.ENV_CACHE: "OFF"})
    assert directory is None and reason == "EDT_BRIDGE_TEST_CACHE=off"


def test_another_platform_build_or_driver_has_another_key(tmp_path):
    client = tmp_path / "1cv8"
    client.write_bytes(b"8.5.1.1529")
    driver = tmp_path / "driver.bsl"
    driver.write_text("Procedure OnStart() EndProcedure", encoding="utf-8")
    first = native_cache.key(client, driver)

    driver.write_text("Procedure OnStart() Return; EndProcedure", encoding="utf-8")
    assert native_cache.key(client, driver) != first
    driver.write_text("Procedure OnStart() EndProcedure", encoding="utf-8")
    assert native_cache.key(client, driver) == first
    client.write_bytes(b"8.5.1.1600 build")
    assert native_cache.key(client, driver) != first


def test_an_entry_is_kept_and_copied_out_whole(tmp_path):
    entry = native_cache.Entry(tmp_path / "cache", "k1")
    assert entry.take(tmp_path / "run1") == (None, "missing")

    entry.put(_infobase(tmp_path), "2.21")
    manifest, reason = entry.take(tmp_path / "run2")

    assert reason is None and manifest["formatVersion"] == "2.21"
    assert (tmp_path / "run2" / native_cache.DATA_FILE).read_bytes() == b"manager infobase"
    assert [path.name for path in (tmp_path / "cache").iterdir()] == ["k1"]


@pytest.mark.parametrize("damage", ["data", "manifest", "layout", "missing data"])
def test_an_entry_that_differs_from_its_manifest_is_removed(tmp_path, damage):
    entry = native_cache.Entry(tmp_path / "cache", "k1")
    entry.put(_infobase(tmp_path), "2.21")
    data, manifest = entry.path / native_cache.DATA_FILE, entry.path / native_cache.MANIFEST
    if damage == "data":
        data.write_bytes(b"manager infobasE")
    elif damage == "manifest":
        manifest.write_text("{not json", encoding="utf-8")
    elif damage == "layout":
        manifest.write_text(json.dumps({**json.loads(manifest.read_text(encoding="utf-8")), "layout": 0}),
                            encoding="utf-8")
    else:
        data.unlink()

    found, reason = entry.take(tmp_path / "run")

    assert found is None and reason.startswith("damaged: ")
    assert not entry.path.exists()


def test_a_check_that_stores_second_keeps_the_first_entry(tmp_path):
    entry = native_cache.Entry(tmp_path / "cache", "k1")
    entry.put(_infobase(tmp_path, b"first"), "2.21")

    entry.put(_infobase(tmp_path, b"second"), "2.21")

    manifest, _ = entry.take(tmp_path / "run")
    assert (tmp_path / "run" / native_cache.DATA_FILE).read_bytes() == b"first"
    assert sorted(path.name for path in (tmp_path / "cache").iterdir()) == ["k1"]


# -- the manager setup of a form check ---------------------------------------------------------------


class Platform:
    """ibcmd and the designer of the manager setup; `fail_metadata` makes the next target export fail."""

    def __init__(self):
        self.calls = []
        self.fail_metadata = 0
        self.refuse_export = None
        self.sessions = []

    def spawn(self, _stack, argv, _env, log):
        self.calls.append(list(argv))
        if Path(argv[0]).name == "ibcmd" and "export" in argv:
            if self.fail_metadata or self.refuse_export:
                if self.refuse_export:
                    log.write_text(self.refuse_export, encoding="utf-8")
                    return SimpleNamespace(wait=lambda timeout: 1)
                log.write_text("The infobase is damaged", encoding="utf-8")
                return SimpleNamespace(wait=lambda timeout: 1)
            Path(argv[-1] + ".xml").write_text(
                "<MetaDataObject><ExternalDataProcessor><Properties>"
                "<DefaultForm>ExternalDataProcessor.Sample.Form.Main</DefaultForm>"
                "</Properties></ExternalDataProcessor></MetaDataObject>", encoding="utf-8")
        elif Path(argv[0]).name == "ibcmd":
            directory = Path(next(x.split("=", 1)[1] for x in argv if x.startswith("--db-path=")))
            directory.mkdir()
            (directory / "1Cv8.1CD").write_bytes(b"manager infobase")
        elif "/DumpExternalDataProcessorOrReportToFiles" in argv:
            if self.fail_metadata:
                # Both ways of reading the target fail: the setup of this attempt fails.
                self.fail_metadata -= 1
                log.write_text("The infobase is damaged", encoding="utf-8")
                return SimpleNamespace(wait=lambda timeout: 255)
            xml = Path(argv[argv.index("/DumpExternalDataProcessorOrReportToFiles") + 1])
            xml.write_text("<MetaDataObject><ExternalDataProcessor><Properties>"
                           "<DefaultForm>ExternalDataProcessor.Sample.Form.Main</DefaultForm>"
                           "</Properties></ExternalDataProcessor></MetaDataObject>", encoding="utf-8")
        elif "/DumpConfigToFiles" in argv:
            (Path(argv[argv.index("/DumpConfigToFiles") + 1]) / "Configuration.xml").write_text(
                CONFIGURATION, encoding="utf-8")
        return SimpleNamespace(wait=lambda timeout: 0)

    def session(self, *session_args):
        manager_base = session_args[5]
        self.sessions.append((manager_base / "1Cv8.1CD").read_bytes())
        return {"ok": True, "status": "command_completed", "screenshot": "screenshot.png", "cleanupComplete": True}

    def setup_commands(self):
        """The platform processes of the setup: ibcmd create or export and the designer commands."""
        return [("ibcmd export" if "export" in call else "ibcmd") if Path(call[0]).name == "ibcmd" else
                next(word for word in call if word in ("/DumpConfigToFiles", "/LoadConfigFromFiles", "/UpdateDBCfg",
                                                      "/DumpExternalDataProcessorOrReportToFiles"))
                for call in self.calls]


@pytest.fixture
def check(monkeypatch, tmp_path):
    platform = Platform()
    monkeypatch.setenv(native_cache.ENV_CACHE, str(tmp_path / "cache"))
    monkeypatch.setattr(native_form.shutil, "which", lambda name: name)
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    monkeypatch.setattr(native_form, "_spawn", platform.spawn)
    monkeypatch.setattr(native_form, "_run_session", platform.session)
    args = _fixture_args(tmp_path)

    def run():
        platform.calls.clear()
        report = {"ok": False}
        native_form._native_check(args, args.test_infobase, args.epf, args.output_dir, report)
        return report

    return platform, run


def test_the_first_check_of_a_build_stores_the_manager_infobase_and_the_next_one_copies_it(check, tmp_path):
    platform, run = check

    first = run()
    assert first["ok"] is True and first["managerCache"]["status"] == "stored"
    assert platform.setup_commands() == ["ibcmd", "ibcmd export",
                                         "/DumpConfigToFiles", "/LoadConfigFromFiles", "/UpdateDBCfg"]

    second = run()
    assert second["ok"] is True and second["managerCache"]["status"] == "hit"
    # Only the target is read; the manager infobase costs no platform process.
    assert platform.setup_commands() == ["ibcmd export"]
    assert platform.sessions == [b"manager infobase", b"manager infobase"]
    assert second["managerCache"]["key"] == first["managerCache"]["key"]
    assert Path(second["managerCache"]["path"]).parent == tmp_path / "cache"


def test_a_damaged_entry_is_built_again(check, tmp_path):
    platform, run = check
    stored = Path(run()["managerCache"]["path"])
    (stored / native_cache.DATA_FILE).write_bytes(b"half a file")

    report = run()

    assert report["ok"] is True
    assert report["managerCache"]["status"] == "rebuilt"
    assert report["managerCache"]["reason"].startswith("damaged: ")
    assert platform.setup_commands()[0] == "ibcmd"
    assert run()["managerCache"]["status"] == "hit"


def test_a_setup_that_fails_on_the_cached_infobase_is_retried_without_it(check):
    platform, run = check
    run()
    platform.fail_metadata = 1

    report = run()

    assert report["ok"] is True
    assert report["setupRetries"][0]["stage"] == "target_metadata"
    assert report["managerCache"]["status"] == "rebuilt"
    assert report["managerCache"]["reason"].startswith("the setup failed on the cached infobase: ")
    assert platform.setup_commands() == ["ibcmd export", "/DumpExternalDataProcessorOrReportToFiles", "ibcmd",
                                         "ibcmd export", "/DumpConfigToFiles", "/LoadConfigFromFiles",
                                         "/UpdateDBCfg"]


def test_a_setup_that_fails_before_storing_says_why_it_built_one(check, monkeypatch):
    platform, run = check
    original = platform.spawn

    def refuse_designer(stack, argv, env, log):
        if "/DumpConfigToFiles" in argv:
            log.write_text("Не обнаружено свободной лицензии!", encoding="utf-8")
            return SimpleNamespace(wait=lambda timeout: 1)
        return original(stack, argv, env, log)

    monkeypatch.setattr(native_form, "_spawn", refuse_designer)

    report = run()

    assert report["status"] == "license_unavailable"
    assert report["managerCache"]["status"] == "miss" and report["managerCache"]["reason"] == "missing"


def test_a_cache_turned_off_is_neither_read_nor_written(check, monkeypatch, tmp_path):
    platform, run = check
    monkeypatch.setenv(native_cache.ENV_CACHE, "off")

    report = run()

    assert report["managerCache"] == {"status": "off", "reason": "EDT_BRIDGE_TEST_CACHE=off"}
    assert not (tmp_path / "cache").exists()
    assert platform.setup_commands()[0] == "ibcmd"


def test_a_cache_that_cannot_be_written_does_not_fail_the_check(check, monkeypatch, tmp_path):
    _, run = check
    blocked = tmp_path / "blocked"
    blocked.write_text("a file where the cache directory should be", encoding="utf-8")
    monkeypatch.setenv(native_cache.ENV_CACHE, str(blocked / "cache"))

    report = run()

    assert report["ok"] is True
    assert report["managerCache"]["status"] == "storeFailed" and report["managerCache"]["error"]


# -- the metadata of the target (EDT-TEST-15) --------------------------------------------------------


def test_the_target_is_read_by_ibcmd_without_the_designer(check):
    platform, run = check

    report = run()

    assert report["targetMetadata"] == {"source": "ibcmd"}
    assert "/DumpExternalDataProcessorOrReportToFiles" not in platform.setup_commands()


def test_the_designer_reads_the_target_when_ibcmd_cannot(check):
    platform, run = check
    platform.refuse_export = "Unsupported format of the external data processor"

    report = run()

    assert report["ok"] is True
    assert report["targetMetadata"]["source"] == "designer"
    assert "Unsupported format of the external data processor" in report["targetMetadata"]["ibcmdError"]
    assert platform.setup_commands()[1:3] == ["ibcmd export", "/DumpExternalDataProcessorOrReportToFiles"]
