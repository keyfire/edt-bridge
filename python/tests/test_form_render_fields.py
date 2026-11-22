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
