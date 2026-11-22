"""Taxi field layout and controls derived from the EDT view model."""

from edt_bridge_mcp.form_view import Assets, build

from test_form_render import field, find, view


def test_taxi_switches_draw_runtime_and_model_boolean_values():
    model = view()
    content = find(model["lay"], "Главное#content")
    off = field("Debugging", "Debugging enabled", "Object.Debugging")
    on = field("Publishing", "Publish web client", "Object.Publishing", value=True)
    for switch in (off, on):
        switch["titlePlacement"] = "Right"
        switch["children"][1]["_c"] = "HippoLaySwitcher"
    content["children"] = [off, on]

    drawn = build(model, interface="taxi", data={"values": {"Debugging": False}}, assets=Assets())

    assert 'role="switch" aria-checked="false"' in drawn.html
    assert 'role="switch" aria-checked="true"' in drawn.html
    assert ">Debugging enabled:<" not in drawn.html
    assert not drawn.warnings


def test_taxi_disabled_edits_keep_spin_buttons_and_hide_the_choice_arrow():
    model = view()
    content = find(model["lay"], "Главное#content")
    count = field("Count", "Count", "Object.Count", width=9, stretchableMode="None", spinButton=True)
    mode = field("Mode", "Mode", "Object.Mode", dropListButton=True, enabled=False)
    content["children"] = [count, mode]
    icons = {"editDropDown": "data:image/png;base64,AA", "editRegUp": "data:image/png;base64,AA"}
    data = {"values": {"Count": 12, "Mode": "Default"}, "disabled": ["Count"]}

    html = build(model, interface="taxi", data=data, assets=Assets(taxi_pictures=icons)).html

    assert html.count('class="inp dis') == 2
    assert 'class="tedit compact"' in html
    assert 'class="p-editRegUp"' in html
    assert 'class="p-editDropDown"' not in html


def test_taxi_renders_choice_tags_and_preserves_empty_rows_in_a_titled_section():
    model = view()
    page = find(model["lay"], "Главное")
    content = page["children"][0]
    content["children"] = [{
        "_c": "HippoLayLogicGroup", "name": "Section", "showTitle": True, "representation": "NoneBigTitle",
        "children": [
            {"_c": "HippoLayStaticText", "name": "Section#title"},
            field("Issuers", "Issuers", "Object.Issuers", choiceButton=True),
            {"_c": "HippoLayStaticText", "name": "Spacer", "minWidth": 1, "maxWidth": 40},
        ],
    }]
    page["children"] = [{
        "_c": "HippoLayFormRichElement", "name": "Главное#richGroup", "titlePlacement": "None",
        "children": [
            {"_c": "HippoLayImage", "name": "Главное#extTooltipBtn", "extTooltip": 'Details "link"'},
            content,
        ],
    }]
    data = {"values": {"Issuers": {"items": ["<Directory>"], "more": 2}}}
    icons = {"editDropDown": "data:image/png;base64,AA"}

    html = build(model, interface="taxi", data=data, assets=Assets(taxi_pictures=icons)).html

    assert 'class="page-help" title="Details &quot;link&quot;"' in html
    assert '<div class="gtitle"></div>' in html
    assert 'class="tblank" aria-hidden="true"' in html
    assert 'class="tag-item">&lt;Directory&gt;<' in html
    assert 'class="tag-more">+2<' in html
    assert 'class="p-editDropDown"' in html


def test_v85_switches_and_checkboxes_support_three_state_and_top_aligned_labels():
    model = view()
    content = find(model["lay"], "Главное#content")
    sw_off = field("SwOff", "Switch off", "Object.SwOff")
    sw_on = field("SwOn", "Switch on", "Object.SwOn", value=True)
    sw_part = field("SwPart", "Switch partial", "Object.SwPart")
    chk_part = field("ChkPart", "Checkbox partial with long wrapping label", "Object.ChkPart")
    for sw in (sw_off, sw_on, sw_part):
        sw["titlePlacement"] = "Right"
        sw["children"][1]["_c"] = "HippoLaySwitcher"
    chk_part["titlePlacement"] = "Right"
    chk_part["children"][1]["_c"] = "HippoLayCheckBox"
    content["children"] = [sw_off, sw_on, sw_part, chk_part]

    data = {
        "values": {"SwOff": False, "SwPart": 2, "ChkPart": "partial"},
        "disabled": ["SwOff"],
    }
    drawn = build(model, interface="v85", theme="light", data=data, assets=Assets())

    assert 'class="switch" role="switch" aria-checked="false" aria-disabled="true"' in drawn.html
    assert 'class="switch on" role="switch" aria-checked="true"' in drawn.html
    assert 'class="switch part" role="switch" aria-checked="mixed"' in drawn.html
    assert 'class="chk part"' in drawn.html
    assert 'class="chkrow fixed switchrow dis" aria-disabled="true"' in drawn.html
    assert "--chk:24px;" in drawn.html
    assert "--chk-tbl:20px;" in drawn.html
    assert "--chk-dis:#c5c5c5;" in drawn.html
    assert "--chk-dis-on:#8dc2fb;" in drawn.html
    assert ".v85 .chkrow { align-items: flex-start;" in drawn.html
    assert ".v85 .row > .fixed:has(.fld, .chkrow), .v85 .row > .chkrow.fixed { flex-shrink: 1; min-width: 0; }" in drawn.html
    assert not drawn.warnings

    # Compact density scales v85 checkboxes and switchers down to 20px (16px in tables).
    model["form"]["scaleVariant"] = "Compact"
    compact_html = build(model, interface="v85", theme="dark", data=data, assets=Assets()).html
    assert "--chk:20px;" in compact_html
    assert "--chk-tbl:16px;" in compact_html
    assert "--chk-dis:#6b6b6b;" in compact_html
    assert "--chk-dis-on:#37597e;" in compact_html

    # Taxi switcher has no third state.
    taxi_html = build(model, interface="taxi", data=data, assets=Assets()).html
    assert 'class="switch part"' not in taxi_html
    assert 'aria-checked="mixed"' not in taxi_html


def test_v85_hides_scrollbars_while_taxi_keeps_styled_table_scrollbar():
    model = view()
    v85_html = build(model, interface="v85", theme="light", assets=Assets()).html
    taxi_html = build(model, interface="taxi", assets=Assets()).html

    assert ".v85 .tbox { scrollbar-width: none; }" in v85_html
    assert ".v85 .tbox::-webkit-scrollbar { display: none; }" in v85_html
    assert ".tbox::-webkit-scrollbar { height: 15px; }" in taxi_html

