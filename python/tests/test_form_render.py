"""edt_form_render in the wrapper: the client view model drawn as HTML, the platform assets, the dispatch."""
from __future__ import annotations

import io
import json
import zipfile

import pytest

from edt_bridge_mcp import client_assets, form_render, form_view, server


def field(name, title, path, **props):
    """A laid-out field as the bridge hands it over: the rich element, its title and the edit box."""
    model = {"_ref": "FormField", "name": name, "dataPath": path}
    edit = {"_c": "HippoLayEdit", "name": name, "logFormElementName": name, "formModelElement": model,
            "width": 40, "height": 1, "stretchableMode": "Hor", **props}
    caption = {"_c": "HippoLayStaticText", "name": name + "#title", "logFormElementName": name, "title": title,
               "formModelElement": model}
    return {"_c": "HippoLayFormRichElement", "name": name + "#richGroup", "titlePlacement": "Auto",
            "children": [caption, edit]}


def table(name, columns):
    cols = [{"_c": "HippoLayTableColumn", "name": name + c, "title": c,
             "formModelElement": {"_ref": "FormField", "name": name + c, "dataPath": f"Объект.{name}.{c}"}}
            for c in columns]
    bar = {"_c": "HippoLayCommandBar", "name": name + "КоманднаяПанель", "children": [
        {"_c": "HippoLayCommand", "name": name + "Добавить", "title": "Добавить", "picture": "PlatformPicture:AddListItem",
         "locationInCommandBar": "InCommandBar",
         "formModelElement": {"_ref": "Button", "command": {"source": "standard", "name": "Add"}}},
        {"_c": "HippoLayCommand", "name": name + "Удалить", "title": "Удалить", "important": False,
         "locationInCommandBar": "InAdditionalSubmenu",
         "formModelElement": {"_ref": "Button", "command": {"source": "standard", "name": "Delete"}}}]}
    grid = {"_c": "HippoLayTable", "name": name, "height": 3, "stretchableMode": "Any",
            "formModelElement": {"_ref": "Table", "name": name, "dataPath": f"Объект.{name}"}, "children": cols}
    group = {"_c": "HippoLayLogicGroup", "name": name + "#group", "role": "TableGroupRole", "children": [bar, grid]}
    return {"_c": "HippoLayFormRichElement", "name": name + "#richGroup", "titlePlacement": "None",
            "children": [group]}


def page(name, title, kids):
    content = {"_c": "HippoLayLogicGroup", "name": name + "#content", "role": "PageContentRole", "children": kids}
    return {"_c": "HippoLayPage", "name": name, "title": title, "children": [content]}


def view(scale="Auto"):
    pages = {"_c": "HippoLayPages", "name": "Страницы", "children": [
        page("Главное", "Главное", [field("Наименование", "Наименование", "Объект.Description",
                                          markRequiredComplete=True),
                                    field("Владелец", "Владелец", "Объект.Owner", dropListButton=True,
                                          choiceButton=True, openButton=True)]),
        page("Адреса", "Адреса", [table("Адреса", ["Адрес"])]),
    ]}
    bottom = {"_c": "HippoLayCommandBar", "name": "ФормаНижняяКоманднаяПанель", "location": "FormBottom", "children": [
        {"_c": "HippoLayCommand", "name": "ФормаЗаписатьИЗакрыть", "title": "Записать и закрыть",
         "representation": "Text", "importance": "Main", "locationInCommandBar": "InCommandBar",
         "formModelElement": {"_ref": "Button", "command": {"source": "standard", "name": "WriteAndClose"}}}]}
    lay = {"_c": "HippoLayForm", "languageCode": "ru", "children": [
        {"_c": "HippoLayPanel", "name": "$scrl", "children": [
            {"_c": "HippoLayFormRichElement", "name": "Страницы#richGroup", "titlePlacement": "None",
             "children": [pages]}]}, bottom]}
    return {"languageCode": "ru", "form": {"scaleVariant": scale, "owner": {"objectPresentation": "Клиент"}},
            "lay": lay, "navigation": [{"name": "РегистрСведений.Коды.ОткрытьПоЗначению.Клиент", "title": "Коды"}]}


def find(node, name):
    """The laid-out node with the given name, depth first."""
    if node.get("name") == name:
        return node
    for kid in node.get("children") or []:
        found = find(kid, name)
        if found is not None:
            return found
    return None


ASSETS = form_view.Assets(sprite="<svg></svg>", symbols=frozenset({"svg_plus", "pic_plus", "svg_moreTop"}))


def test_v85_draws_titles_values_and_the_first_page():
    drawn = form_view.build(view(), data={"title": "Сайт (Клиент)", "values": {"Наименование": "Сайт"}},
                            assets=ASSETS)
    assert drawn.title == "Сайт (Клиент)"
    assert drawn.page == "Главное" and drawn.density == "normal"
    assert ">Сайт<" in drawn.html and '<span class="req">*</span>Наименование' in drawn.html
    assert "Записать и закрыть" in drawn.html and 'class="btn main"' in drawn.html
    # Every page is laid out, the others unseen: the pages group is as large as its largest page.
    assert drawn.html.count('class="page ghost"') == 1
    assert "Основное" in drawn.html  # the navigation panel has a command


def test_the_page_is_chosen_by_title_and_a_lone_table_counts_its_rows():
    data = {"tables": {"Адреса": {"rows": [["https://a.example"], ["https://b.example"]], "current": 0}}}
    drawn = form_view.build(view(), page="адреса", data=data, assets=ASSETS)
    assert drawn.page == "Адреса"
    assert '<span class="badge">2</span>' in drawn.html
    assert "https://b.example" in drawn.html and 'tr class="cur"' in drawn.html
    # A command the platform puts into the "more" menu is not drawn on the bar.
    assert "Добавить" in drawn.html and ">Удалить<" not in drawn.html


def test_density_comes_from_the_form_and_can_be_overridden():
    assert form_view.build(view("Compact"), assets=ASSETS).density == "compact"
    assert form_view.build(view("Compact"), assets=ASSETS, density="normal").density == "normal"


def test_dark_theme_switches_the_palette():
    drawn = form_view.build(view(), theme="dark", assets=ASSETS)
    assert "--win:#1a1a1a" in drawn.html and "--brand:#ffe433" in drawn.html


def test_taxi_puts_titles_left_with_a_colon_and_links_the_navigation():
    drawn = form_view.build(view(), interface="taxi", data={"tables": {"Адреса": {"rows": [["x"]]}}}, assets=ASSETS)
    assert ">Наименование:<" in drawn.html and "lgrid" in drawn.html
    assert '<span class="lnk">Коды</span>' in drawn.html
    assert "Адреса (1)" in drawn.html


def test_taxi_has_no_dark_theme():
    with pytest.raises(ValueError, match="no dark theme for Taxi"):
        form_view.build(view(), interface="taxi", theme="dark")


def test_unknown_elements_and_unused_data_are_reported():
    model = view()
    model["lay"]["children"][0]["children"].append({"_c": "HippoLaySomethingNew", "name": "X"})
    drawn = form_view.build(model, data={"values": {"НетТакого": 1}}, assets=ASSETS)
    assert any("HippoLaySomethingNew" in w for w in drawn.warnings)
    assert any("НетТакого" in w for w in drawn.warnings)


@pytest.mark.parametrize("given, expected", [("VERSION8_5", "v85"), ("8.5", "v85"), ("Taxi", "taxi"),
                                             ("v85", "v85"), ("TAXI", "taxi")])
def test_interface_spellings(given, expected):
    assert form_view.normalize_interface(given) == expected


def test_sprite_and_pictures_are_read_from_resource_bytes():
    small = b'<svg><symbol id="svg_x" viewBox="0 0 1 1"><path d="M0"/></symbol></svg>'
    symbols = "".join(f'<symbol id="{s}" viewBox="0 0 24 24"><path d="M1"/></symbol>'
                      for s in client_assets.REQUIRED_SYMBOLS)
    resource = b"junk" + small + b"\x00" + ("<svg>" + symbols + "</svg>").encode()
    sprite, ids = client_assets.extract_sprite(resource)
    assert set(client_assets.REQUIRED_SYMBOLS) <= set(ids) and "svg_x" not in ids
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("_plus.svg", '<svg viewBox="0 0 24 24" fill="none"><path d="M13 5" fill="#404040"/></svg>')
    pictures = client_assets.extract_pictures(b"prefix" + archive.getvalue())
    assert '<symbol id="pic_plus" viewBox="0 0 24 24"><path d="M13 5"/></symbol>' == pictures


def test_taxi_buttons_are_the_platform_rasters():
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_STORED) as z:
        z.writestr("_editRegUp.svg", "<svg/>")
        z.writestr("_editRegUp150.png", b"\x89PNG-150")
        z.writestr("_editRegUp100.png", b"\x89PNG-100")
        z.writestr("_editOpen100.png", b"not a png")
        z.writestr("_arrowUp_125.png", b"\x89PNG-125")
        z.writestr("_arrowUp_100.png", b"\x89PNG-100")
    found = client_assets.extract_taxi_pictures(b"prefix" + archive.getvalue())
    assert sorted(found) == ["arrowUp", "editRegUp"]
    assert found["editRegUp"].startswith("data:image/png;base64,iVBORy0xMDA")
    assets = form_view.Assets(taxi_pictures={"editDropDown": "data:image/png;base64,AA", "unused": "data:,"})
    drawn = form_view.build(view(), interface="taxi", assets=assets)
    # The owner field has a drop-down and an open button: the first from the platform, the second a glyph.
    assert '<span class="p-editDropDown"></span><span>⧉</span>' in drawn.html
    assert '.p-editDropDown { background-image: url("data:image/png;base64,AA"); }' in drawn.html
    assert ".p-unused" not in drawn.html


def test_taxi_table_bar_shows_picture_commands_and_the_search_field():
    model = view()
    bar = find(model["lay"], "АдресаКоманднаяПанель")
    bar["children"].append({"_c": "HippoLayCommand", "name": "АдресаВверх", "title": "Переместить вверх",
                            "picture": "PlatformPicture:MoveUp", "representation": "Picture",
                            "locationInCommandBar": "InCommandBar",
                            "formModelElement": {"_ref": "Button", "command": {"source": "standard", "name": "MoveUp"}}})
    assets = form_view.Assets(taxi_pictures={"arrowUp": "data:image/png;base64,AA"})
    drawn = form_view.build(model, interface="taxi", page="Адреса", assets=assets)
    assert '<span class="cbtn ponly"><span class="tpic p-arrowUp"></span></span>' in drawn.html
    assert ">Переместить вверх<" not in drawn.html and '<span class="cbtn">Добавить</span>' in drawn.html
    # Without the raster the title stands in for the picture.
    plain = form_view.build(model, interface="taxi", page="Адреса", assets=form_view.Assets())
    assert ">Переместить вверх<" in plain.html


def test_a_group_with_half_spacing_draws_its_items_closer_in_v85():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["verticalSpacing"] = "Half"
    assert 'style="gap:calc(var(--gap-v) * 3 / 7)"' in form_view.build(model, assets=ASSETS).html
    assert "gap:calc" not in form_view.build(model, interface="taxi", assets=ASSETS).html


def test_a_table_with_an_extended_tooltip_has_its_button_beside_the_bar():
    model = view()
    rich = find(model["lay"], "Адреса#richGroup")
    rich["extTooltipPlacement"] = "Right"
    rich["children"].append({"_c": "HippoLayImage", "name": "Адреса#extTooltipBtn"})
    taxi = form_view.build(model, interface="taxi", page="Адреса", assets=ASSETS)
    assert '<div class="trow stretch vgrow">' in taxi.html and '<span class="help">?</span></div>' in taxi.html
    v85 = form_view.build(model, page="Адреса", assets=ASSETS)
    assert '<span class="info">' in v85.html.split('<div class="trow', 1)[1]


def test_missing_platform_is_explained(tmp_path):
    with pytest.raises(client_assets.AssetError, match="mngsrv_root.res"):
        client_assets.platform_dirs(str(tmp_path))


def reply(payload):
    return {"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": json.dumps(payload)}]}}


def test_engine_edt_is_passed_to_the_bridge_unchanged():
    calls = []

    def bridge(name, args):
        calls.append((name, args))
        return reply({"ok": True, "pngPath": "x.png"})

    report = form_render.run({"projectName": "P", "formFqn": "CommonForm.F", "engine": "edt", "interface": "taxi",
                              "page": "ignored"}, bridge)
    assert report["pngPath"] == "x.png"
    assert calls == [("edt_form_render", {"projectName": "P", "formFqn": "CommonForm.F", "variant": "TAXI"})]


def test_engine_client_draws_the_model_and_names_what_is_missing(tmp_path, monkeypatch):
    calls = []

    def bridge(name, args):
        calls.append(args)
        return reply({"ok": True, "layModel": view()})

    def no_assets(platform=None):
        raise client_assets.AssetError("no platform here")

    monkeypatch.setattr(client_assets, "load", no_assets)
    monkeypatch.setattr(form_render, "shoot", lambda html, png, scale: (100, 50))
    out = tmp_path / "form.png"
    report = form_render.run({"projectName": "P", "formFqn": "CommonForm.F", "outPath": str(out), "theme": "dark"},
                             bridge)
    assert calls[0]["engine"] == "model" and calls[0]["variant"] == "VERSION8_5" and calls[0]["theme"] == "DARK"
    assert report["engine"] == "client" and report["interface"] == "v85" and report["theme"] == "dark"
    assert (tmp_path / "form.html").is_file() and report["width"] == 100
    assert any("no platform here" in w for w in report["warnings"])


def test_an_old_plugin_without_the_model_is_named(tmp_path):
    with pytest.raises(form_render.RenderError, match="engine \"model\""):
        form_render.run({"projectName": "P", "formFqn": "CommonForm.F", "outPath": str(tmp_path / "f.png")},
                        lambda name, args: reply({"ok": True, "pngPath": "x.png"}))


def test_the_wrapper_serves_edt_form_render_with_its_arguments():
    assert form_render.NAME in server.LOCAL_TOOL_NAMES
    assert server.unknown_arguments(form_render.NAME, {"projectName": "P", "formFqn": "F", "interface": "v85",
                                                       "theme": "dark", "page": "x", "data": {}}) is None
    assert "unknown argument" in server.unknown_arguments(form_render.NAME, {"projectName": "P", "colour": "x"})
