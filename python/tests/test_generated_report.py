import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from edt_bridge_mcp import cli, native_form


@pytest.mark.parametrize(
    ("selection", "default", "schema", "expected"),
    [
        ("ExternalReport.Neutral.Form", "", "ExternalReport.Neutral.Template.Schema", "scenario_completed"),
        ("ВнешнийОтчет.Neutral.Форма", "", "ExternalReport.Neutral.Template.Schema", "scenario_completed"),
        ("ExternalReport.Other.Form", "", "ExternalReport.Neutral.Template.Schema", "form_not_default"),
        ("ExternalReport.Neutral.Form", "", "", "form_not_default"),
        ("ExternalReport.Neutral.Form.Named", "", "ExternalReport.Neutral.Template.Schema", "form_not_default"),
        ("ExternalReport.Neutral.Form", "ExternalReport.Neutral.Form.Custom",
         "ExternalReport.Neutral.Template.Schema", "form_not_default"),
        ("ExternalReport.Neutral.Form.Custom", "ExternalReport.Neutral.Form.Custom",
         "ExternalReport.Neutral.Template.Schema", "scenario_completed"),
    ],
)
def test_report_preflight_requires_declared_or_generated_main_form(
    monkeypatch, tmp_path, selection, default, schema, expected,
):
    platform = tmp_path / "platform"
    platform.mkdir()
    for binary in ("1cv8", "ibcmd"):
        (platform / binary).touch()
    base = tmp_path / "base"
    base.mkdir()
    (base / "1Cv8.1CD").touch()
    target = tmp_path / "neutral.erf"
    target.touch()
    scenario = tmp_path / "scenario.json"
    scenario.write_text(
        '{"version":1,"steps":[{"action":"assert_field","field":"Filter","equals":"A"}]}',
        encoding="utf-8",
    )
    args = cli.build_parser("test-form").parse_args([
        "--platform-bin", str(platform), "--test-infobase", str(base), "--scope", "fixture",
        "--erf", str(target), "--form", selection, "--form-title", "Neutral report",
        "--scenario", str(scenario), "--output-dir", str(tmp_path / "result"),
    ])
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    monkeypatch.setattr(native_form.shutil, "which", lambda name: name)
    monkeypatch.setattr(native_form, "_display", lambda *args: {})
    report_launches = []

    def spawn(_stack, argv, _env, _log):
        if Path(argv[0]).name == "ibcmd":
            destination = next(x for x in argv if x.startswith("--db-path="))
            manager_base = Path(destination.split("=", 1)[1])
            manager_base.mkdir()
            (manager_base / "1Cv8.1CD").touch()
        elif "/DumpExternalDataProcessorOrReportToFiles" in argv:
            xml = Path(argv[argv.index("/DumpExternalDataProcessorOrReportToFiles") + 1])
            xml.write_text(
                '<MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses" version="2.21">'
                "<ExternalReport><Properties><Name>Neutral</Name>"
                f"<DefaultForm>{default}</DefaultForm>"
                f"<MainDataCompositionSchema>{schema}</MainDataCompositionSchema>"
                "</Properties></ExternalReport></MetaDataObject>",
                encoding="utf-8",
            )
        elif "/DumpConfigToFiles" in argv:
            directory = Path(argv[argv.index("/DumpConfigToFiles") + 1])
            (directory / "Configuration.xml").write_text(
                '<MetaDataObject version="2.21"/>', encoding="utf-8",
            )
        elif "/LoadExternalDataProcessorOrReportFromFiles" in argv:
            index = argv.index("/LoadExternalDataProcessorOrReportFromFiles")
            directory = Path(argv[index + 1]).parent
            report_launches.append((directory / "report-request.json").read_text(encoding="utf-8"))
            Path(argv[index + 2]).touch()
        else:
            assert "/LoadConfigFromFiles" in argv or "/UpdateDBCfg" in argv
        return SimpleNamespace(wait=lambda timeout: 0)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    monkeypatch.setattr(native_form, "_run_session", lambda *args: {
        "status": "scenario_completed", "ok": True, "cleanupComplete": True,
    })
    report = native_form.run(args)
    assert report["status"] == expected
    assert bool(report_launches) == (expected == "scenario_completed")


    if report_launches:
        request = json.loads(report_launches[0])
        assert Path(request["file"]) == target.resolve()
        assert request["formName"] == ("Custom" if selection.endswith(".Custom") else "")
