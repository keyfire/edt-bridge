import json
import xml.etree.ElementTree as ET
from pathlib import Path

from edt_bridge_mcp import native_report


def test_report_launcher_keeps_the_selected_path_as_data(tmp_path):
    target = Path('quoted"report;Message("unexpected").erf')

    root_xml = native_report.write_launcher(tmp_path, target, "Main", "2.21")

    request = json.loads((tmp_path / "report-request.json").read_text(encoding="utf-8"))
    assert request == {"file": str(target), "formName": "Main"}
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
