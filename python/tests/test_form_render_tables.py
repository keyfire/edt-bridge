"""Table layout and sample values in the Taxi form renderer."""
from edt_bridge_mcp import form_view


def column(name, title=None, **layout):
    return {"_c": "HippoLayTableColumn", "name": name, "title": title,
            "formModelElement": {"dataPath": f"Object.Lines.{name}"}, **layout}


def group(*children, **layout):
    return {"_c": "HippoLayTableColumnsGroup", "children": list(children), **layout}


def render_table(*columns, data=None, caption=None):
    title = [{"_c": "HippoLayStaticText", "name": "Lines#title", "title": caption}] if caption else []
    grid = {"_c": "HippoLayTable", "name": "Lines", "width": 40, "rowsSelectionMode": "SingleRow",
            "height": 1, "stretchableMode": "Any", "children": list(columns)}
    table_group = {"_c": "HippoLayLogicGroup", "role": "TableGroupRole", "children": [grid]}
    rich = {"_c": "HippoLayFormRichElement", "name": "Lines#richGroup", "children": title + [table_group]}
    view = {"lay": {"_c": "HippoLayForm", "children": [rich]}}
    return form_view.build(view, interface="taxi", data={"tables": {"Lines": data or {}}}).html


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
