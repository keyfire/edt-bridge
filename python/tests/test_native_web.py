import argparse
import base64
import re
import time

import pytest

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


_RAW_FORM = {
    "title": "Order 7 dated 01.10.2026", "size": "1006x687",
    "items": [
        ["Number", "input", "Number:", "A-7", "576,39 192x25", [], []],
        ["ФИОКонтакта", "input", "Contact:", "Sample Person", "93,73 399x25", ["clipped"], []],
        ["Note", "text", "", "INN 1234567894", "12,120 300x20", [], []],
        ["Phone", "input", "", "+7 912 345-67-89", "12,150 200x25", ["cut"], []],
        ["Code", "input", "Телефон:", "Q-15", "12,180 200x25", [], []],
        ["Pages", "pages", "", "", "12,220 900x300", [], []],
        ["GoodsPage", "page", "Goods", "", "12,220 117x28", ["current"], []],
        ["Post", "button", "", "Post", "300,4 85x27", ["disabled"], []],
    ],
    "tables": [{"name": "Goods", "columns": ["N", "Product", "E-mail"], "current": 0, "more": True,
                "rows": [["1", "Tea", "sample@example.invalid"], ["2", "Milk", ""]]}],
    "messages": ["Filled for sample@example.invalid"], "others": ["Welcome!"], "hidden": ["Comment", "Extra"],
    "truncated": False,
}
_TABLE_COLUMNS = {
    "Goods": [{"name": "N", "title": "N"}, {"name": "Product", "title": "Product"},
              {"name": "Email", "title": "E-mail"}],
}


def test_form_text_writes_one_line_per_item_without_filter():
    text = native_web.form_text(_RAW_FORM, pii.Filter())
    assert text["title"] == "Order 7 dated 01.10.2026"
    assert text["legend"] == "name | kind | title | value | x,y wxh | flags"
    assert text["items"][0] == "Number | input | Number: | A-7 | 576,39 192x25"
    assert text["items"][1] == "ФИОКонтакта | input | Contact: | Sample Person | 93,73 399x25 | clipped"
    assert text["items"][6] == "GoodsPage | page | Goods |  | 12,220 117x28 | current"
    assert text["tables"] == [{"name": "Goods", "columns": "N | Product | E-mail",
                               "rows": ["1 | Tea | sample@example.invalid", "2 | Milk | "],
                               "currentRow": 1, "more": True}]
    assert text["messages"] == ["Filled for sample@example.invalid"]
    assert text["otherWindows"] == ["Welcome!"]
    assert text["hidden"] == "Comment, Extra"
    assert "truncated" not in text


def test_form_text_filters_values_by_name_title_and_shape():
    flt = pii.Filter("redact")
    items = native_web.form_text(_RAW_FORM, flt)["items"]
    # The name says personal data: the value goes whole.
    assert items[1] == "ФИОКонтакта | input | Contact: | [person] | 93,73 399x25 | clipped"
    # A number with the check digits of an INN, in any field.
    assert items[2] == "Note | text |  | INN [inn] | 12,120 300x20"
    assert items[3] == "Phone | input |  | [phone] | 12,150 200x25 | cut"
    # A field named neutrally under a personal title.
    assert items[4] == "Code | input | Телефон: | [phone] | 12,180 200x25"
    text = native_web.form_text(_RAW_FORM, flt, table_columns=_TABLE_COLUMNS)
    assert text["tables"][0]["rows"] == ["1 | Tea | [email]", "2 | Milk | "]
    assert text["messages"] == ["Filled for [email]"]
    # Names, kinds and places are not data.
    assert items[0] == "Number | input | Number: | A-7 | 576,39 192x25"


def test_form_text_survives_the_answer_filter_unchanged():
    flt = pii.Filter("pseudonym", salt="fixed")
    text = native_web.form_text(_RAW_FORM, flt)
    count = flt.count
    assert pii.mask({"webForm": text}, flt) == {"webForm": text}
    assert flt.count == count


def test_form_text_marks_a_cut_list():
    text = native_web.form_text(dict(_RAW_FORM, truncated=True, tables=[], messages=[], others=[], hidden=[]),
                                pii.Filter())
    assert text["truncated"] is True
    assert not {"tables", "messages", "otherWindows", "hidden"} & set(text)


def test_form_text_filters_option_labels_and_filters_values_before_shortening():
    email = "sample@example.invalid"
    comment = "X" * 193 + " " + email
    raw = {"items": [
        ["Channel", "choice", "", email, "0,0 12x12", [], [email, "Other"]],
        ["Comment", "input", "", comment, "0,0 12x12", [], []],
    ]}
    text = native_web.form_text(raw, pii.Filter("redact"))
    assert text["items"][0] == "Channel | choice |  | [email] | 0,0 12x12 | options=[email]/Other"
    assert "sample" not in text["items"][1]


def test_form_text_includes_filtered_spreadsheet_rows():
    sheets = [{"name": "Report", "title": "Report", "rows": [["Total", "100"], ["sample@example.invalid", ""]],
               "truncated": True}]
    text = native_web.form_text(_RAW_FORM, pii.Filter("redact"), sheets=sheets)
    assert text["spreadsheets"] == [
        {"name": "Report", "title": "Report", "rows": ["Total | 100", "[email] | "], "more": True}
    ]


def test_form_text_uses_the_table_column_name_to_filter_cells():
    raw = {"tables": [{"name": "Orders", "columns": ["Recipient"], "rows": [["Sample Person"]]}]}
    columns = {"Orders": [{"name": "ФИО", "title": "Recipient"}]}
    text = native_web.form_text(raw, pii.Filter("redact"), table_columns=columns)
    assert text["tables"][0]["rows"] == ["[person]"]
    unknown = native_web.form_text(raw, pii.Filter("redact"))
    assert unknown["tables"][0]["rows"] == ["[withheld]"]
    assert unknown["tables"][0]["withheld"]


def test_form_text_filters_multiline_values_before_normalizing_whitespace():
    raw = {"items": [["Notes", "input", "", "Header\nCustomerRef=Q5Z", "0,0 12x12", [], []]]}
    sheets = [{"name": "Report", "title": "", "rows": [["Header\nCustomerRef=Q5Z"]]}]
    flt = pii.Filter("redact", values=[("ref", re.compile(r"(?m)^CustomerRef=[A-Z0-9]+$"))])
    text = native_web.form_text(raw, flt, sheets=sheets)
    assert "Header [ref]" in text["items"][0]
    assert text["spreadsheets"][0]["rows"] == ["Header [ref]"]


def test_form_text_limits_large_tables_across_the_whole_form():
    columns = [f"Column {index}" for index in range(30)]
    rows = [["X" * 200] * 30] * 30
    text = native_web.form_text({"tables": [
        {"name": "Goods", "columns": columns, "rows": rows, "current": 29},
        {"name": "Archive", "columns": columns, "rows": rows},
    ]}, pii.Filter())
    assert 0 < len(text["tables"][0]["rows"]) < len(rows)
    assert text["tables"][0]["more"]
    assert "currentRow" not in text["tables"][0]
    assert text["truncated"]


class _Page:
    """The calls capture and read_form make on a Playwright page, recorded."""

    def __init__(self, rows=(), form=None):
        self.rows, self.form, self.calls = list(rows), form, []
        self.context = self

    def new_cdp_session(self, page):
        return self

    def send(self, method, params):
        self.calls.append((method, params))

    def evaluate(self, script, argument):
        self.calls.append(("evaluate", argument))
        return self.rows if argument == native_web._TAG else self.form

    def locator(self, selector):
        return selector

    def screenshot(self, **options):
        self.calls.append(("screenshot", options))
        return b"\x89PNG\r\n\x1a\nproof"


def test_capture_without_filter_takes_the_plain_screenshot(tmp_path):
    pytest.importorskip("playwright.sync_api")
    page = _Page()
    result = native_web.capture(page, tmp_path, pii.Filter(), time.monotonic() + 30)
    assert result == {"screenshot": str(tmp_path / "screenshot.png"), "screenshotMasked": False}
    options = page.calls[-1][1]
    assert not {"mask", "style"} & set(options)
    assert ("Emulation.setScriptExecutionDisabled", {"value": True}) in page.calls
    assert not any(call == ("evaluate", native_web._TAG) for call in page.calls)


def test_capture_with_filter_masks_as_before(tmp_path):
    pytest.importorskip("playwright.sync_api")
    page = _Page(rows=[["form0_ФИО_i0", "Sample", True], ["form0_Count_i0", "3", True]])
    result = native_web.capture(page, tmp_path, pii.Filter("redact"), time.monotonic() + 30)
    assert result["screenshotMasked"] is True
    options = page.calls[-1][1]
    assert options["mask"] == ['[data-edt-bridge-mask~="0"]'] and options["style"] == native_web._MASK_STYLE


def test_read_form_passes_the_title_and_limits_and_formats():
    pytest.importorskip("playwright.sync_api")
    page = _Page(form=_RAW_FORM)
    text = native_web.read_form(page, pii.Filter(), "Order 7 dated 01.10.2026")
    assert text["items"][0].startswith("Number | input")
    argument = next(call[1] for call in page.calls if call[0] == "evaluate")
    assert argument == dict(native_web.LAYOUT_LIMITS, title="Order 7 dated 01.10.2026", sheetNames=[])


def test_read_form_without_a_form_is_an_error():
    pytest.importorskip("playwright.sync_api")

    with pytest.raises(native_web.WebError, match="no form"):
        native_web.read_form(_Page(form=None), pii.Filter(), "")


def test_read_form_does_not_attach_spreadsheets_to_an_unmatched_window():
    pytest.importorskip("playwright.sync_api")
    raw = dict(_RAW_FORM, title="Warning", matchedTitle=False)
    sheets = [{"name": "Report", "title": "", "rows": [["Total"]]}]
    text = native_web.read_form(_Page(form=raw), pii.Filter(), "Report", sheets=sheets)
    assert text["title"] == "Warning"
    assert "spreadsheets" not in text


def test_read_form_uses_filtered_manager_messages_when_the_panel_is_not_in_the_dom():
    pytest.importorskip("playwright.sync_api")
    raw = dict(_RAW_FORM, matchedTitle=True, messages=[])
    text = native_web.read_form(_Page(form=raw), pii.Filter("redact"), "Order",
                                panel_messages=["Saved for sample@example.invalid"])
    assert text["messages"] == ["Saved for [email]"]


@pytest.mark.parametrize("read_failed", [False, True])
def test_mcp_web_form_text_without_image(tmp_path, read_failed):
    source = tmp_path / "source"
    source.mkdir()
    (source / "1Cv8.1CD").write_bytes(b"fixture")

    def run(args):
        if read_failed:
            return {"ok": True, "status": "scenario_completed", "webFormError": "The web client shows no form."}
        path = args.output_dir / "screenshot.png"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"\x89PNG\r\n\x1a\nproof")
        return {"ok": True, "status": "scenario_completed", "screenshot": str(path), "screenshotMasked": True,
                "webForm": {"title": "Item", "items": ["Code | input | Code: | 7 | 0,0 10x10"]},
                "phases": [{"name": "steps", "screenshot": str(path)}]}

    answer = form_run.run_tool(
        {"form": "Catalog.People.Form.ItemForm", "navigationLink": "e1cib/data/Catalog.People?ref=" + "a" * 32,
         "webClient": True, "screenshot": False, "steps": [{"action": "read_form"}]},
        runner=run, environ={form_run.ENV_INFOBASE: str(source), form_run.ENV_PLATFORM: str(tmp_path)})
    assert "screenshotData" not in answer
    if read_failed:
        assert answer["ok"] is False
        assert answer["status"] == "form_text_failed"
        assert answer["error"] == "The web client shows no form."
    else:
        assert answer["webForm"]["items"] == ["Code | input | Code: | 7 | 0,0 10x10"]


def test_web_client_runs_without_the_personal_data_filter(tmp_path, monkeypatch):
    from edt_bridge_mcp import native_form

    monkeypatch.delenv(pii.ENV_MODE, raising=False)
    seen = {}

    def check(args, base, external, output, report):
        seen["filter"] = args._web_filter
        report.update(ok=True, status="scenario_completed")

    monkeypatch.setattr(native_form, "_validated_paths", lambda args: (tmp_path, None, tmp_path / "out"))
    monkeypatch.setattr(native_form, "_native_check", check)
    args = argparse.Namespace(scope="application", web_client=True, scenario=None, form="Catalog.People.Form.Item",
                              form_title=None, navigation_link="e1cib/data/Catalog.People?ref=" + "a" * 32,
                              epf=None, erf=None, button="Save")
    report = native_form.run(args)
    assert report["status"] == "scenario_completed"
    assert seen["filter"].active is False
    assert "piiFilter" not in report


def test_observed_title_comes_from_the_result_or_the_progress():
    from edt_bridge_mcp import native_form

    assert native_form._observed_title({"interaction": {"observedTitle": "Order 7"}}) == "Order 7"
    assert native_form._observed_title({"lastInteraction": {"observedTitle": "Item"}}) == "Item"
    assert native_form._observed_title({"interaction": {"status": "form_not_found"}}) == ""


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


class _Refused(Exception):
    """The Playwright error a fake page raises."""


class _StartPage:
    """A web client page with windows over the main window, as File - Open meets them.

    windows lists the windows from the top: each is a dict with the title and the answer caption it has. covered
    makes the first click on an answer fail, as when a list a navigation link opened lies over the window.
    """

    def __init__(self, windows=(), covered=False, refuse_menu=0):
        self.windows, self.covered, self.refuse_menu = [dict(window) for window in windows], covered, refuse_menu
        self.actions, self.uploaded = [], None
        self.keyboard = self

    def press(self, key):
        self.actions.append(("press", key))
        if key == "Escape" and self.covered:
            self.covered = False

    def wait_for_timeout(self, milliseconds):
        self.actions.append(("wait", milliseconds))

    def evaluate(self, script):
        assert script == native_web._WINDOWS
        return [window["title"] for window in self.windows]

    def get_by_title(self, caption, exact):
        assert exact is True
        return _Answer(self, caption)

    def locator(self, selector):
        return _Element(self, selector)


class _Answer:
    def __init__(self, page, caption):
        self.page, self.caption = page, caption
        self.first = self

    def count(self):
        return int(any(window.get("answer") == self.caption for window in self.page.windows[:1]))

    def click(self, timeout):
        self.page.actions.append(("answer", self.caption))
        if self.page.covered:
            raise _Refused("<div> from <div id=ps1win> subtree intercepts pointer events")
        self.page.windows.pop(0)


class _Element:
    def __init__(self, page, selector):
        self.page, self.selector = page, selector

    def is_visible(self):
        if self.selector == "#modalSurface":
            return bool(self.page.windows)
        return self.selector == "#fileSelectDialogOk"

    def click(self, timeout):
        self.page.actions.append(("click", self.selector))
        if self.page.windows or (self.selector == "#captionbarMore" and self.page.refuse_menu):
            if self.selector == "#captionbarMore" and self.page.refuse_menu:
                self.page.refuse_menu -= 1
            raise _Refused("<div id=modalSurface> intercepts pointer events")

    def hover(self, timeout):
        self.page.actions.append(("hover", self.selector))

    def set_input_files(self, path, timeout):
        self.page.uploaded = path


def _clicks(page):
    return [action for action in page.actions if action[0] in ("answer", "click", "press")]


_MOVED = {"title": "Информационная база была перемещена или восстановлена из резервной копии",
          "answer": "Это копия информационной базы"}


def test_the_window_of_a_moved_infobase_is_answered_before_file_open(tmp_path):
    page = _StartPage([_MOVED])
    answered = native_web._open_external(page, tmp_path / "Sample.epf", time.monotonic() + 60, _Refused)
    assert answered == [_MOVED["title"]]
    assert _clicks(page) == [("answer", "Это копия информационной базы"), ("click", "#captionbarMore"),
                             ("click", "#fileOpen"), ("click", "#fileSelectDialogOk")]
    assert page.uploaded == str(tmp_path / "Sample.epf")


def test_a_window_over_the_answer_is_closed_with_escape_first(tmp_path):
    page = _StartPage([_MOVED], covered=True)
    answered = native_web._open_external(page, tmp_path / "Sample.epf", time.monotonic() + 60, _Refused)
    assert answered == [_MOVED["title"]]
    assert _clicks(page)[:3] == [("answer", "Это копия информационной базы"), ("press", "Escape"),
                                 ("answer", "Это копия информационной базы")]


def test_the_english_caption_answers_the_window_too(tmp_path):
    window = {"title": "The infobase was moved or restored from a backup", "answer": "This is a copy of the infobase"}
    page = _StartPage([window])
    assert native_web._open_external(page, tmp_path / "Sample.epf", time.monotonic() + 60, _Refused) == [
        window["title"]]


def test_a_window_without_an_answer_stops_the_run_with_its_title(tmp_path):
    page = _StartPage([{"title": "Unexpected question"}])
    with pytest.raises(native_web.WebError) as raised:
        native_web._open_external(page, tmp_path / "Sample.epf", time.monotonic() + 600, _Refused)
    assert raised.value.status == "web_start_window"
    assert "Unexpected question" in str(raised.value)
    assert raised.value.details == {"blockingWindows": ["Unexpected question"], "answeredWindows": []}
    # The run waits a few seconds for a short window, not until its time limit.
    waits = [action for action in page.actions if action[0] == "wait"]
    assert len(waits) == native_web.UNKNOWN_WINDOW_SECONDS
    assert not [action for action in page.actions if action == ("click", "#captionbarMore")]


def test_a_menu_refused_once_is_tried_again_after_escape(tmp_path):
    page = _StartPage(refuse_menu=1)
    assert native_web._open_external(page, tmp_path / "Sample.epf", time.monotonic() + 60, _Refused) == []
    assert _clicks(page)[:3] == [("click", "#captionbarMore"), ("press", "Escape"), ("click", "#captionbarMore")]
    assert page.uploaded == str(tmp_path / "Sample.epf")


def test_the_details_of_a_web_error_reach_the_report():
    from edt_bridge_mcp import native_form

    report = {}
    native_form._record_error(report, native_web.WebError("web_start_window", "Blocked.",
                                                          {"blockingWindows": ["Question"]}), "driver_failed")
    assert report == {"ok": False, "status": "web_start_window", "error": "Blocked.", "blockingWindows": ["Question"]}
