import json
import xml.etree.ElementTree as ET
from pathlib import Path

from edt_bridge_mcp import native_report


def test_report_launcher_keeps_the_selected_path_as_data(tmp_path):
    target = Path('quoted"report;Message("unexpected").erf')

    root_xml = native_report.write_launcher(tmp_path, target, "Main", "2.21")

    request = json.loads((tmp_path / "report-request.json").read_text(encoding="utf-8"))
    assert request == {"file": str(target), "formName": "Main", "kind": "ExternalReport"}
    module, = tmp_path.rglob("Module.bsl")
    assert str(target) not in module.read_text(encoding="utf-8-sig")
    root = ET.parse(root_xml).getroot()
    assert root.attrib["version"] == "2.21"
    assert root.findtext("./{*}ExternalDataProcessor/{*}Properties/{*}DefaultForm") == (
        "ExternalDataProcessor.EdtBridgeReportLoader.Form.Main")
    for xml in tmp_path.rglob("*.xml"):
        assert ET.parse(xml).getroot().attrib["version"] == "2.21"


def test_report_loader_request_path_cannot_inject_bsl():
    assert native_report._bsl_string('x";Quit();"\nnext\r') == (
        '"x"";Quit();""" + Chars.LF + "next" + Chars.CR + ""')


def test_a_data_processor_launcher_reopens_the_form_by_its_button(tmp_path):
    """EDT-TEST-20: named scenarios open an .epf through a launcher whose button opens the form again."""
    root_xml = native_report.write_launcher(tmp_path, Path("check.epf"), "Form", "2.21",
                                            kind="ExternalDataProcessor")

    request = json.loads((tmp_path / "report-request.json").read_text(encoding="utf-8"))
    assert request == {"file": "check.epf", "formName": "Form", "kind": "ExternalDataProcessor"}
    root = ET.parse(root_xml).getroot()
    assert root.findtext("./{*}ExternalDataProcessor/{*}Properties/{*}Name") == native_report.FORM_LAUNCHER
    form, = tmp_path.rglob("Form.xml")
    form = ET.parse(form).getroot()
    button = form.find("./{*}ChildItems/{*}Button")
    assert button.get("name") == native_report.REOPEN_BUTTON
    assert button.findtext("{*}CommandName") == "Form.Command." + native_report.REOPEN_BUTTON
    command = form.find("./{*}Commands/{*}Command")
    assert command.findtext("{*}Action") == native_report.REOPEN_BUTTON
    module = next(tmp_path.rglob("Module.bsl")).read_text(encoding="utf-8-sig")
    # The client runs an .epf it opens itself in unsafe mode, so the launcher connects it the same way.
    assert "ExternalDataProcessors.Connect(Address, , False, Protection)" in module
    assert "ExternalReports.Connect(Address, , True)" in module
    assert f"Procedure {native_report.REOPEN_BUTTON}(Command)" in module
    assert native_report.launcher_form(native_report.FORM_LAUNCHER) == (
        "ExternalDataProcessor.EdtBridgeFormLauncher.Form.Main")


def test_an_unknown_kind_gets_no_launcher(tmp_path):
    import pytest
    with pytest.raises(ValueError):
        native_report.write_launcher(tmp_path, Path("x.epf"), "Form", "2.21", kind="Catalog")
