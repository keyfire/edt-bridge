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


def test_taxi_field_without_a_width_uses_the_taxi_default():
    model = view()
    content = find(model["lay"], "Главное#content")
    control = field("Code", "Code", "Object.Code", stretchableMode="None")
    del control["children"][1]["width"]
    content["children"] = [control]

    html = build(model, interface="taxi").html

    assert 'class="tedit compact" style="--field-w:225px"' in html


def test_v85_stretchable_edit_distinguishes_initial_and_maximum_width():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["children"] = [field("Lifetime", "Lifetime", "Object.Lifetime", width=10, maxWidth=40,
                                 spinButton=True, enabled=False)]

    html = build(model, data={"values": {"Lifetime": "432 000 s"}}).html

    assert 'style="--field-max-w:451px"><div class="lbl"' in html
    assert 'class="tedit" style="--field-w:134px"' in html
    assert '>432 000 s<' in html
    assert 'class="frow"' in html


def test_v85_only_fits_vertical_cards_with_width_limited_fields():
    model = view()
    content = find(model["lay"], "Главное#content")
    bounded = field("Protocol", "Protocol", "Object.Protocol", maxWidth=40)
    fixed = field("Debugger", "Debugger", "Object.Debugger", width=30, stretchableMode="None")
    unbounded = field("Name", "Name", "Object.Name")
    content["children"] = [
        {"_c": "HippoLayLogicGroup", "name": "Compact", "showAsCard": True, "children": [bounded, fixed]},
        {"_c": "HippoLayLogicGroup", "name": "Unbounded", "showAsCard": True, "children": [unbounded]},
        {"_c": "HippoLayLogicGroup", "name": "Stretch", "showAsCard": True, "horStretchable": True,
         "children": [field("Mode", "Mode", "Object.Mode", maxWidth=40)]},
        {"_c": "HippoLayLogicGroup", "name": "Horizontal", "showAsCard": True,
         "childrenGrouping": "AlwaysHorizontal", "children": [
             field("Limit", "Limit", "Object.Limit", maxWidth=40),
             field("Wait", "Wait", "Object.Wait", maxWidth=40),
         ]},
    ]

    html = build(model).html
    assert html.count('class="card fixed fit-fields"') == 1
    assert '<div class="card fixed fit-fields"><div class="col">' in html
    assert 'class="card stretch"' in html

    taxi = build(model, interface="taxi").html
    assert 'class="card fixed fit-fields"' not in taxi


def test_v85_mutes_zero_numeric_values_and_keeps_the_color_of_nonzero_values():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["children"] = [
        field("Wait", "Wait", "Object.Wait", spinButton=True),
        field("Count", "Count", "Object.Count"),
        field("Lifetime", "Lifetime", "Object.Lifetime", spinButton=True),
        field("Code", "Code", "Object.Code"),
    ]
    data = {"values": {"Wait": "0 ms", "Count": 0, "Lifetime": "400 ms", "Code": "0"},
            "disabled": ["Wait", "Lifetime"]}

    html = build(model, data=data).html
    assert 'class="inp dis empty" style=""><span class="v">0 ms<' in html
    assert 'class="inp empty" style=""><span class="v">0<' in html
    assert 'class="inp dis" style=""><span class="v">400 ms<' in html
    assert 'class="inp" style=""><span class="v">0<' in html
    assert 'class="inp dis empty"' not in build(model, interface="taxi", data=data).html


def test_v85_aligns_matching_reference_fields_with_the_stretchable_member():
    model = view()
    content = find(model["lay"], "Главное#content")
    user, client, tenant = [
        field(name, name, f"Object.{name}", width=15, minWidth=6, maxWidth=40,
              stretchableMode="None", choiceButton=True, openButton=True)
        for name in ("User", "Client", "Tenant")
    ]
    client["stretchableMode"] = "Hor"
    content["children"] = [{"_c": "HippoLayLogicGroup", "children": [user, client, tenant]}]

    aligned = 'class="col reference-fields fixed" style="--reference-field-max:451px"'
    assert aligned in build(model).html
    assert aligned not in build(model, interface="taxi").html
    tenant["children"][1]["width"] = 10
    assert aligned not in build(model).html


def test_v85_balances_input_columns_with_open_buttons_and_unequal_row_counts():
    model = view()
    content = find(model["lay"], "Главное#content")
    refs = [
        field(name, name, f"Object.{name}", width=15, minWidth=6, maxWidth=40,
              stretchableMode="None", choiceButton=True, openButton=True)
        for name in ("User", "Client", "Tenant")
    ]
    refs[1]["stretchableMode"] = "Hor"
    plain = [field(name, name, f"Object.{name}", maxWidth=40) for name in ("Date", "Version")]
    content["children"] = [{
        "_c": "HippoLayLogicGroup", "childrenGrouping": "Horizontal", "children": [
            {"_c": "HippoLayLogicGroup", "children": refs},
            {"_c": "HippoLayLogicGroup", "children": plain},
        ],
    }]

    marker = 'class="row balanced-field-columns fixed" style="--balanced-slot-max:451px"'
    assert marker in build(model).html
    assert marker not in build(model, interface="taxi").html
    plain[0]["children"][1]["maxWidth"] = 20
    assert marker not in build(model).html


def test_v85_marks_a_horizontal_card_of_checkboxes_for_input_alignment():
    model = view()
    content = find(model["lay"], "Главное#content")
    checks = [field(name, name, f"Object.{name}") for name in ("Invalid", "Service")]
    for item in checks:
        item["children"][1]["_c"] = "HippoLayCheckBox"
    card = {"_c": "HippoLayLogicGroup", "childrenGrouping": "AlwaysHorizontal",
            "showAsCard": True, "children": checks}
    content["children"] = [{
        "_c": "HippoLayLogicGroup", "childrenGrouping": "Horizontal", "children": [
            field("Name", "Name", "Object.Name", markRequiredComplete=True), card,
        ],
    }]

    marker = 'class="card fixed checkbox-card"'
    assert marker in build(model).html
    assert marker not in build(model, interface="taxi").html
    checks[1]["children"][1]["_c"] = "HippoLayEdit"
    assert marker not in build(model).html


def test_v85_fixed_edit_reserves_space_for_buttons_at_small_widths():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["children"] = [field("Count", "Count", "Object.Count", width=1,
                                  stretchableMode="None", spinButton=True)]

    html = build(model).html

    assert 'class="tedit compact"' in html
    assert 'max-width:-' not in html


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


def test_v85_renders_regular_and_large_group_titles_as_section_headings():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["children"] = [
        {"_c": "HippoLayLogicGroup", "name": name, "showTitle": True, **layout, "children": [
            {"_c": "HippoLayStaticText", "name": f"{name}#title", "title": name},
            field(f"{name}Field", "Value", f"Object.{name}"),
        ]}
        for name, layout in (("Connection", {}), ("Advanced", {"representation": "NoneBigTitle"}))
    ]

    html = build(model).html
    assert '<div class="gtitle section">Connection</div>' in html
    assert '<div class="gtitle section">Advanced</div>' in html

    taxi = build(model, interface="taxi").html
    assert '<div class="gtitle">Connection</div>' in taxi


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


def test_v85_uses_a_thin_horizontal_table_scrollbar():
    model = view()
    v85_html = build(model, interface="v85", theme="light", assets=Assets()).html
    taxi_html = build(model, interface="taxi", assets=Assets()).html

    assert ".v85 .tbox { overflow-x: auto; }" in v85_html
    assert ".v85 .tbox::-webkit-scrollbar { width: 0; height: 4px; }" in v85_html
    assert ".v85 .tbox::-webkit-scrollbar { display: none; }" not in v85_html
    assert ".tbox::-webkit-scrollbar { height: 15px; }" in taxi_html

