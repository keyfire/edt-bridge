"""Table layout and sample values in the Taxi form renderer."""
from edt_bridge_mcp import form_view


def column(name, title=None, **layout):
    return {"_c": "HippoLayTableColumn", "name": name, "title": title,
            "formModelElement": {"dataPath": f"Object.Lines.{name}"}, **layout}


def group(*children, **layout):
    return {"_c": "HippoLayTableColumnsGroup", "children": list(children), **layout}


def render_table(*columns, data=None, caption=None, interface="taxi", commands=None, **layout):
    title = [{"_c": "HippoLayStaticText", "name": "Lines#title", "title": caption}] if caption else []
    grid = {"_c": "HippoLayTable", "name": "Lines", "width": 40, "rowsSelectionMode": "SingleRow",
            "height": 1, "stretchableMode": "Any", "children": list(columns), **layout}
    bar = [{"_c": "HippoLayCommandBar", "children": commands}] if commands else []
    table_group = {"_c": "HippoLayLogicGroup", "role": "TableGroupRole", "children": [*bar, grid]}
    rich = {"_c": "HippoLayFormRichElement", "name": "Lines#richGroup", "children": title + [table_group]}
    view = {"lay": {"_c": "HippoLayForm", "children": [rich]}}
    return form_view.build(view, interface=interface, data={"tables": {"Lines": data or {}}}).html


def test_v85_table_uses_column_size_hints_and_a_stretchable_empty_body():
    html = render_table(column("Enabled", columnMode="Check"),
                        column("Name", "Name", width=10, defaultHeaderFooterWidth=20,
                               autoWidthInTable="ByDataAndTitle"),
                        column("Visible", "Visibility", columnMode="Check"),
                        data={"rows": []}, interface="v85")

    assert '<colgroup><col style="width:40px"><col style="width:237px"><col style="width:110px"></colgroup>' in html
    assert '</table><div class="grid-empty">Список пуст</div></div>' in html


def test_v85_expands_a_single_stretchable_text_column_to_the_table_width():
    name = column("Name", "Name", width=10, defaultHeaderFooterWidth=8,
                  autoWidthInTable="ByDataAndTitle", stretchableMode="Hor")
    table = render_table(name, data={"rows": [{"Name": "Sample"}]}, interface="v85")
    assert 'class="grid col-widths single-column" style="min-width:369px"><colgroup><col></colgroup>' in table
    assert ">Sample</td>" in table
    assert "table.grid.single-column { width: auto; width: stretch; }" in table

    check = render_table(column("Enabled", columnMode="Check", stretchableMode="Hor"), interface="v85")
    assert '<colgroup><col style="width:40px"></colgroup>' in check
    assert 'class="grid col-widths single-column"' not in check

    fixed = render_table({**name, "stretchableMode": "None"}, interface="v85")
    assert '<colgroup><col style="width:106px"></colgroup>' in fixed
    assert 'class="grid col-widths single-column"' not in fixed


def test_v85_stretches_text_columns_without_widening_checks_and_line_numbers():
    cols = [
        column("Selected", columnMode="Check", stretchableMode="Hor"),
        column("LineNumber", "N", width=4, defaultHeaderFooterWidth=5,
               autoWidthInTable="ByDataAndTitle", stretchableMode="Hor"),
        column("Name", "Name", width=40, defaultHeaderFooterWidth=20,
               autoWidthInTable="ByDataAndTitle", stretchableMode="Hor"),
        column("Code", "Code", width=10, defaultHeaderFooterWidth=10,
               autoWidthInTable="ByDataAndTitle", stretchableMode="Hor"),
    ]
    html = render_table(*cols, interface="v85")

    assert 'contain-intrinsic-inline-size:479px' in html
    assert '<col style="width:40px"><col style="width:74px">' in html
    assert 'calc(237px + max(0px, (100cqw - 479px) / 2))' in html
    assert 'calc(128px + max(0px, (100cqw - 479px) / 2))' in html


def test_table_preserves_disabled_horizontal_lines_in_both_interfaces():
    name = column("Name", "Name", stretchableMode="Hor")
    for interface in ("v85", "taxi"):
        hidden = render_table(name, interface=interface, showHorLines=False)
        assert '<table class="grid no-row-lines' in hidden
        visible = render_table(name, interface=interface, showHorLines=True)
        assert '<table class="grid no-row-lines' not in visible


def test_v85_custom_actions_precede_the_generated_move_buttons():
    def command(name, standard=False):
        binding = {"name": name, "source": "standard" if standard else "FormCommand"}
        return {"_c": "HippoLayCommand", "name": name, "title": name,
                "formModelElement": {"command": binding, "generated": standard}}

    html = render_table(column("Name", "Name"), interface="v85",
                        commands=[command("Add", True), command("MoveUp", True), command("MoveDown", True),
                                  command("Mark all"), command("Unmark all")])
    captions = [html.index(f'>{name}</span>') for name in ("Add", "Mark all", "Unmark all", "MoveUp", "MoveDown")]

    assert captions == sorted(captions)


def test_taxi_table_headers_follow_visible_column_groups():
    html = render_table(
        column("Published", columnMode="Check", headerPicture="PictureRef", invisibleTitle="Published"),
        column("Name", "Name", width=40),
        group(column("Token", columnMode="Check", headerPicture="PictureRef", invisibleTitle="Use token"),
              column("Issuers", "Issuers", width=15), column("Hidden", "Hidden", invisible=True),
              title="Token authentication", grouping="Horizontal", showInHeader=True),
    )

    assert 'rowspan="2"' in html and 'colspan="2"' in html
    assert '<thead>' in html and html.split('</thead>', 1)[0].count('<tr>') == 2
    assert 'aria-label="Published"' in html and 'aria-label="Use token"' in html
    assert html.count('class="check-heading"') == 2
    assert '>Token authentication</th>' in html and '>Hidden</th>' not in html


def test_taxi_in_cell_group_uses_its_visible_member_caption():
    html = render_table(
        group(column("Set", columnMode="Check", showInHeader=False), column("Value", "Value", width=40),
              grouping="InCell"),
        column("Safe", "Safe", columnMode="Check"),
        data={"rows": [{"Set": True, "Value": "Sample", "Safe": True}]},
    )

    assert '>Value</th>' in html
    cells = html.split('<tbody>', 1)[1].split('</tbody>', 1)[0]
    assert cells.count('<td') == 2
    assert cells.count('class="chk on"') == 2 and '>Sample</span>' in cells


def test_taxi_table_widths_allow_scroll_without_stretching_checkbox_columns():
    html = render_table(column("Preview", columnMode="Check", headerPicture="PictureRef"),
                        column("Address", "Address", width=80, stretchableMode="Hor"))

    assert '<div class="tbox">' in html
    assert '<colgroup><col style="width:34px"><col>' in html
    assert 'style="min-width:647px"' in html


def test_taxi_table_caption_prefers_a_supplied_runtime_title():
    html = render_table(column("Address", "Address"), caption="Lines", data={"title": "Redirect addresses"})

    assert '>Redirect addresses:</span>' in html
    assert '>Lines:</span>' not in html


def test_taxi_table_cell_keeps_issuer_chips():
    html = render_table(column("Issuers", "Issuers", width=15),
                        data={"rows": [[{"items": ["Example <issuer>"], "more": 1}]]})

    cells = html.split('<tbody>', 1)[1].split('</tbody>', 1)[0]
    assert '>Example &lt;issuer&gt;</span>' in cells and '+1' in cells
