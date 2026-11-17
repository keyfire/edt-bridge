import base64
import time

from edt_bridge_mcp import form_run, native_web, pii


def test_test_port_wait_services_browser_callbacks(monkeypatch):
    checked = []
    monkeypatch.setattr(native_web, "_accepting", lambda port: bool(checked))

    class Apache:
        def poll(self):
            return None

    native_web._wait_port(Apache(), 1538, time.monotonic() + 1, pause=checked.append)
    assert len(checked) == 1


def test_web_address_preserves_internal_link_as_fragment():
    link = "e1cib/data/Catalog.Люди?ref=" + "a" * 32
    url = native_web.client_url(8080, "EdtClient23", link, "en", "Demo Admin")
    from urllib.parse import parse_qs, unquote, urlsplit

    parsed = urlsplit(url)
    assert parsed.netloc == "127.0.0.1:8080"
    assert parse_qs(parsed.query, keep_blank_values=True) == {
        "TestClient": [""], "TestClientID": ["EdtClient23"], "N": ["Demo Admin"]}
    assert unquote(parsed.fragment) == link


def test_masks_sensitive_names_and_values_and_unknown_input():
    rows = [
        ["form0_ФИО_i0", "Тестовый Пример", True],
        ["form0_Amount_i0", "sample@example.invalid", True],
        ["form0_INN_i0", "1234567894", True],
        ["form0_PublicCode_i0", "UNMASKED-TRACE", True],
        ["unknown", "unknown field's value", True],
        ["unknown", "contact: sample@example.invalid", False],
        ["unknown", "Label: quantity", False],
    ]
    assert native_web.mask_indexes(rows, pii.Filter("redact")) == [0, 1, 2, 4, 5]


def test_only_verified_screenshots_survive_text_redaction(tmp_path):
    verified = tmp_path / "safe.png"
    raw = tmp_path / "raw.png"
    for path in (verified, raw):
        path.touch()
    report = {"phases": [{"screenshot": str(verified)}, {"screenshot": str(raw)}]}
    pii.withhold_screenshots(report, preserved={str(verified)})
    assert report["phases"] == [{"screenshot": str(verified)}, {"screenshotWithheld": True}]
    assert verified.is_file() and not raw.exists()


def test_mcp_web_form_returns_the_masked_png_before_cleanup(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    (source / "1Cv8.1CD").write_bytes(b"fixture")
    png = b"\x89PNG\r\n\x1a\nproof"
    report_paths = []

    def run(args):
        assert args.test_infobase == source
        assert args.epf is args.erf is None
        assert args.web_client is True
        assert not args.output_dir.is_relative_to(source)
        path = args.output_dir / "steps" / "screenshot.png"
        path.parent.mkdir(parents=True)
        path.write_bytes(png)
        report_paths.append(path)
        return {"ok": True, "status": "scenario_completed",
                "screenshot": str(path), "screenshotMasked": True}

    answer = form_run.run_tool(
        {"form": "Catalog.People.Form.ItemForm", "navigationLink": "e1cib/data/Catalog.People?ref=" + "a" * 32,
         "webClient": True, "steps": [{"action": "read_form"}]},
        runner=run, environ={form_run.ENV_INFOBASE: str(source), form_run.ENV_PLATFORM: str(tmp_path)})
    assert base64.b64decode(answer["screenshotData"]) == png
    assert answer["screenshotMasked"] is True
    assert not report_paths[0].exists()


def test_web_external_upload_has_no_navigation_fragment():
    url = native_web.client_url(8080, "EdtClient23", None, "en", None)
    assert url == "http://127.0.0.1:8080/test/en_US/?TestClient&TestClientID=EdtClient23"


def test_mcp_web_external_form_uses_private_runner_copy(tmp_path):
    (tmp_path / "1Cv8.1CD").write_bytes(b"fixture")
    artifact = tmp_path / "Report.erf"
    artifact.touch()

    def run(args):
        assert args.scope == "fixture"
        assert args.test_infobase == tmp_path
        assert args.web_client is True and args.erf == artifact
        assert args.navigation_link is None
        return {"ok": True, "status": "scenario_completed"}

    answer = form_run.run_tool(
        {"path": str(artifact), "form": "ExternalReport.Report.Form",
         "webClient": True, "steps": [{"action": "read_form"}]},
        runner=run, environ={form_run.ENV_INFOBASE: str(tmp_path), form_run.ENV_PLATFORM: str(tmp_path)})
    assert answer["ok"] is True
