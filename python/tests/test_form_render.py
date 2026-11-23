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


@pytest.mark.parametrize("interface,theme", [("v85", "light"), ("v85", "dark"), ("taxi", "light")])
def test_generated_create_command_uses_the_v85_title_action(interface, theme):
    model = view()
    model["navigation"] = []
    model["lay"]["children"].insert(0, {
        "_c": "HippoLayCommandBar", "location": "FormTop", "children": [
            {"_c": "HippoLayCommand", "title": "Create", "picture": "PictureRef", "importance": "Supplementary",
             "locationInCommandBar": "InCommandBar", "formModelElement": {
                 "generated": True, "command": {"source": "standard", "name": "Create"}}},
            {"_c": "HippoLayCommand", "title": "Create a template", "locationInCommandBar": "InCommandBar",
             "formModelElement": {"command": {"source": "form", "name": "Create"}}},
        ],
    })
    drawn = form_view.build(model, interface=interface, theme=theme, data={"title": "Items"}, assets=ASSETS)

    if interface == "v85":
        assert '<span class="title">Items</span><span class="create-action">' in drawn.html
        assert 'href="#pic_plus"/></svg>Create</span><span class="right">' in drawn.html
        assert '<span class="pill">Create a template</span>' in drawn.html
        assert '<span class="pill">Create</span>' not in drawn.html
    else:
        assert '<span class="tbtn">Create</span><span class="tbtn">Create a template</span>' in drawn.html


def test_taxi_puts_titles_left_with_a_colon_and_links_the_navigation():
    drawn = form_view.build(view(), interface="taxi", data={"tables": {"Адреса": {"rows": [["x"]]}}}, assets=ASSETS)
    assert ">Наименование:<" in drawn.html and "lgrid" in drawn.html
    assert '<span class="lnk">Коды</span>' in drawn.html
    assert "Адреса (1)" in drawn.html


def test_taxi_underlines_hyperlink_decorations():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["children"].append({"_c": "HippoLayStaticText", "name": "Link", "title": "Set a limit",
                                "hyperlink": True})
    taxi = form_view.build(model, interface="taxi", assets=ASSETS).html
    assert '<div class="deco link" style="">Set a limit</div>' in taxi
    assert ".deco.link { text-decoration: underline; }" in taxi


def test_taxi_places_left_tabs_beside_the_page_content():
    model = view()
    find(model["lay"], "Страницы")["representation"] = "TabsOnLeftHorizontal"
    html = form_view.build(model, interface="taxi", page="Адреса", assets=ASSETS).html
    assert '<div class="pages stretch side-tabs"><div class="tabs">' in html
    assert '<span class="tab on">Адреса</span></div><div class="pbody">' in html
    default_html = form_view.build(view(), interface="taxi", assets=ASSETS).html
    assert '<div class="pages stretch"><div class="tabs">' in default_html


def test_taxi_preserves_the_contents_of_a_command_bar_submenu():
    model = view()
    model["lay"]["children"].insert(0, {
        "_c": "HippoLayCommandBar", "location": "FormTop", "children": [
            {"_c": "HippoLaySubMenu", "title": "Создать на основании", "children": [
                {"_c": "HippoLayCommand", "name": "CreatePublication", "title": "Публикация: generate"},
                {"_c": "HippoLayCommand", "name": "Hidden", "title": "Hidden command"},
            ]},
        ],
    })
    html = form_view.build(model, interface="taxi", assets=ASSETS, data={"hidden": ["Hidden"]}).html
    assert '<details class="tsubmenu"><summary class="tbtn tdrop">Создать на основании</summary>' in html
    assert '<span class="tmenuitem">Публикация: generate</span>' in html
    assert "Hidden command" not in html


def test_taxi_uses_observed_page_captions_and_window_size():
    drawn = form_view.build(view(), interface="taxi", page="Адреса", assets=ASSETS, width=800, height=450,
                            data={"pageTitles": {"Адреса": "Адреса (4)"}})
    assert drawn.page == "Адреса"
    assert '<span class="tab on">Адреса (4)</span>' in drawn.html
    assert ".win { width:800px; min-width:0; min-height:450px; }" in drawn.html


def test_taxi_places_generated_create_based_on_before_custom_commands_and_dims_disabled_buttons():
    model = view()
    top = {"_c": "HippoLayCommandBar", "location": "FormTop", "children": [
        {"_c": "HippoLayCommand", "title": "Save", "formModelElement": {"generated": True}},
        {"_c": "HippoLaySubMenu", "title": "Publication file", "name": "File"},
        {"_c": "HippoLaySubMenu", "title": "Create based on", "formModelElement": {"generated": True},
         "children": [{"_c": "HippoLayCommand", "title": "Create publication", "formModelElement": {
             "command": {"source": "StandardCommand", "name": "CreateBasedOn"}}}]},
    ]}
    model["lay"]["children"].insert(0, top)
    html = form_view.build(model, interface="taxi", assets=ASSETS, data={"disabled": ["File"]}).html
    bar = html.split('<div class="tbar">', 1)[1].split('<div class="body">', 1)[0]
    assert bar.index("Save") < bar.index("Create based on") < bar.index("Publication file")
    assert 'class="tbtn tdrop" aria-disabled="true">Publication file</span>' in bar


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


@pytest.mark.parametrize(("location", "style"), [("form", "tbtn"), ("table", "cbtn")])
def test_taxi_groups_the_list_search_controls_on_the_right(location, style):
    model = view()
    if location == "form":
        bar = {"_c": "HippoLayCommandBar", "location": "FormTop", "children": []}
        model["lay"]["children"].insert(0, bar)
    else:
        bar = find(model["lay"], "АдресаКоманднаяПанель")
    bar["children"].extend([
        {"_c": "HippoLaySubMenu", "title": "Управление поиском", "representation": "Picture", "children": [
            {"_c": "HippoLayCommand", "formModelElement": {"command": {"source": "standard", "name": name}}}
            for name in ("Find", "SearchHistory")
        ]},
        {"_c": "HippoLayEdit", "name": "СтрокаПоиска"},
        {"_c": "HippoLaySubMenu", "name": "Создание", "title": "Создать на основании"},
        {"_c": "HippoLayCommand", "name": "Отчеты", "title": "Отчеты..."},
        {"_c": "HippoLayCommand", "locationInCommandBar": "InAdditionalSubmenu", "title": "Обновить"},
        {"_c": "HippoLayCommand", "title": "Справка",
         "formModelElement": {"command": {"source": "standard", "name": "Help"}}},
    ])
    assets = form_view.Assets(symbols=frozenset({"svg_search"}))
    html = form_view.build(model, interface="taxi", page="Адреса", assets=assets, data={"hidden": ["Отчеты"]}).html
    left, right = html[html.index(f'<div class="{style[0]}bar">'):].split('<span class="right">', 1)
    assert f'<span class="{style} tdrop">Создать на основании</span>' in left
    assert "Отчеты..." not in left and "Управление поиском" not in left
    assert right.index('class="inp btns tsearch"') < right.index('title="Управление поиском"')
    assert right.index('href="#svg_search"') < right.index('tdrop">Еще<') < right.index(">?</span>")


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


def test_taxi_renders_command_pictures_in_form_and_table_bars(tmp_path, monkeypatch):
    model = view()
    select = {"_c": "HippoLayCommand", "title": "Select", "picture": "CommonPicture:SelectIcon",
              "formModelElement": {"command": {"representation": "TextPicture"}}}
    model["lay"]["children"].insert(0, {
        "_c": "HippoLayCommandBar", "location": "FormTop", "children": [
            {"_c": "HippoLayCommand", "title": "Copy", "picture": "PlatformPicture:CloneListItem",
             "representation": "Picture"},
            select,
            {**select, "title": "Text only", "picture": "CommonPicture:Unused", "representation": "Text"},
            {"_c": "HippoLaySubMenu", "title": "Actions"},
        ],
    })
    find(model["lay"], "АдресаКоманднаяПанель")["children"].append(select)
    pictures = []

    def bridge(name, args):
        if name == "edt_form_render":
            return reply({"ok": True, "layModel": model})
        if name == "edt_picture_export":
            pictures.append(args)
            return reply({"contentType": "image/png", "base64": "iVBORw=="})
        return reply({})

    assets = form_view.Assets(taxi_pictures={"paperDoublePlus": "data:image/png;base64,iVBORw=="})
    monkeypatch.setattr(client_assets, "load", lambda platform: assets)
    monkeypatch.setattr(form_render, "shoot", lambda html, png, scale: (100, 50))
    out = tmp_path / "form.png"
    form_render.run({"projectName": "P", "formFqn": "CommonForm.F", "interface": "taxi",
                     "page": "Адреса", "outPath": str(out)}, bridge)

    html = out.with_suffix(".html").read_text(encoding="utf-8")
    assert '<span class="tbtn ponly"><span class="tpic p-paperDoublePlus"></span></span>' in html
    assert '<span class="tbtn tdrop">Actions</span>' in html
    assert '<span class="tbtn">Text only</span>' in html
    assert html.count('<img class="tpic" src="data:image/png;base64,iVBORw==" alt="">Select') == 2
    assert [request.get("variant") for request in pictures] == [None, "100.png"]


def test_taxi_uses_the_ldpi_image_from_a_named_picture_collection():
    picked = []

    def bridge(name, args):
        variant = args.get("variant")
        if variant is None:
            return reply({"found": True, "recommended": "choice-v85.svg", "variants": [
                {"name": "choice-v85.svg", "contentType": "image/svg+xml", "screenDensity": "ldpi",
                 "interfaceVariant": "version8_5"},
                {"name": "choice-85.png", "contentType": "image/png", "screenDensity": "bldpi"},
                {"name": "choice-100.png", "contentType": "image/png", "screenDensity": "ldpi"},
            ]})
        picked.append(variant)
        return reply({"contentType": "image/png", "base64": "AQID"}) if variant == "choice-100.png" else reply({})

    assert form_render._taxi_project_picture(bridge, "P", "Choice") == "data:image/png;base64,AQID"
    assert picked == ["choice-100.png"]


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


def test_what_the_form_code_decides_is_named():
    source = """
    Процедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)
        ОбщегоНазначения.ВставитьВажноеСообщение(ЭтотОбъект, Текст, "Предупреждение", Элементы.Группа);
        Если Не ОбщегоНазначенияКлиентСервер.ЭтоВариантИнтерфейса85() Тогда
            Элементы.Адреса.Видимость = Ложь;
        КонецЕсли;
        Элементы.Срок.РежимВыбораИзСписка = Истина;
    КонецПроцедуры"""
    notes = form_render.code_notes(source)
    assert any("an important message in a group" in n for n in notes)
    assert any("by interface variant" in n for n in notes)
    assert any(n.startswith("the form code sets the visibility of: Адреса") for n in notes)
    assert any("choice from a list for: Срок" in n for n in notes)
    assert form_render.code_notes("Процедура А() КонецПроцедуры") == []


def test_messages_are_drawn_where_the_form_code_puts_them():
    data = {"messages": [{"text": "Адрес без указания разрешен", "type": "Информация", "group": "Адреса"},
                         {"text": "Запись заблокирована", "type": "Предупреждение"},
                         {"text": "Не на этой странице", "group": "Нет"}]}
    drawn = form_view.build(view(), page="Адреса", data=data, assets=ASSETS)
    assert 'style="background:#b8ebff"' in drawn.html and 'style="background:#fff1ab"' in drawn.html
    assert drawn.html.index("Запись заблокирована") < drawn.html.index("Адрес без указания разрешен")
    assert any("messages whose group is not on the drawn page: Нет" in w for w in drawn.warnings)
    taxi = form_view.build(view(), interface="taxi", page="Адреса", data=data, assets=ASSETS)
    assert "#0f77a1" in taxi.html


def test_a_message_in_an_empty_group_stands_where_the_group_is():
    model = view()
    model["emptyGroups"] = {"Контейнер": {"parent": "Адреса", "before": "Адреса"}}
    find(model["lay"], "Адреса#content")["logFormElementName"] = "Адреса"
    data = {"messages": [{"text": "Сообщение в контейнере", "group": "Контейнер"}]}
    drawn = form_view.build(model, page="Адреса", data=data, assets=ASSETS)
    assert not drawn.warnings
    assert drawn.html.index("Сообщение в контейнере") < drawn.html.index('class="tblock')


@pytest.mark.parametrize("interface", ["v85", "taxi"])
def test_a_message_in_an_empty_root_group_precedes_its_next_item(interface):
    model = view()
    panel = find(model["lay"], "$scrl")
    panel["children"] = [field("Код", "Код", "Объект.Code"), field("Имя", "Имя", "Объект.Description")]
    model["emptyGroups"] = {"Контейнер": {"before": "Имя"}}
    data = {"values": {"Код": "sample-id", "Имя": "sample-name"},
            "messages": [{"text": "Сообщение в контейнере", "group": "Контейнер"}]}
    drawn = form_view.build(model, interface=interface, data=data, assets=ASSETS)
    assert not drawn.warnings
    assert drawn.html.index("sample-id") < drawn.html.index("Сообщение в контейнере") < drawn.html.index("sample-name")


def test_read_only_fields_lose_their_choice_buttons_and_dates_get_a_calendar():
    model = view()
    owner = find(model["lay"], "Владелец")
    owner["readOnly"] = True
    name = find(model["lay"], "Наименование")
    name["formModelElement"]["valueKind"] = "dateTime"
    assets = form_view.Assets(sprite="<svg></svg>", symbols=frozenset({"svg_cornerBottom", "svg_open",
                                                                      "svg_calendarClock"}))
    drawn = form_view.build(model, assets=assets)
    in_field = 'class="ib"><svg width="20" height="20" style="fill:var(--icon-muted);flex:0 0 auto"><use href="#'
    assert in_field + "svg_cornerBottom" not in drawn.html and "#svg_open" in drawn.html
    assert "#svg_calendarClock" in drawn.html
    name["readOnly"] = True
    assert "#svg_calendarClock" not in form_view.build(model, assets=assets).html


def test_a_three_state_box_draws_its_middle_state():
    model = view()
    find(model["lay"], "Наименование")["_c"] = "HippoLayCheckBox"
    for interface in ("v85", "taxi"):
        drawn = form_view.build(model, interface=interface, data={"values": {"Наименование": 2}}, assets=ASSETS)
        assert '<span class="chk part"></span>' in drawn.html


def test_an_old_plugin_without_the_model_is_named(tmp_path):
    with pytest.raises(form_render.RenderError, match="engine \"model\""):
        form_render.run({"projectName": "P", "formFqn": "CommonForm.F", "outPath": str(tmp_path / "f.png")},
                        lambda name, args: reply({"ok": True, "pngPath": "x.png"}))


def test_the_wrapper_serves_edt_form_render_with_its_arguments():
    assert form_render.NAME in server.LOCAL_TOOL_NAMES
    assert server.unknown_arguments(form_render.NAME, {"projectName": "P", "formFqn": "F", "interface": "v85",
                                                       "theme": "dark", "page": "x", "data": {}}) is None
    assert "unknown argument" in server.unknown_arguments(form_render.NAME, {"projectName": "P", "colour": "x"})

def test_v85_left_tabs_keep_the_observed_count_separate_from_the_caption():
    model = view()
    find(model["lay"], "Страницы")["representation"] = "TabsOnLeftHorizontal"
    drawn = form_view.build(model, page="Адреса", width=900, height=600,
                            data={"pageTitles": {"Адреса": "Адреса (4)"}})
    markup = drawn.html.split('<div class="stage">', 1)[1]
    assert '<div class="pages stretch side-tabs">' in markup
    assert 'Адреса<span class="badge">4</span>' in markup


def test_v85_draws_boolean_switches_and_sample_tags():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["children"].insert(0, {"_c": "HippoLaySwitcher", "name": "Use", "value": True})
    content["children"].append(field("Issuers", "Issuers", "Object.Issuers", choiceButton=True))
    drawn = form_view.build(model, data={"values": {"Issuers": {"items": ["Apps"], "more": 1}}})
    markup = drawn.html.split('<div class="stage">', 1)[1]
    assert 'role="switch" aria-checked="true"' in markup
    assert '<span class="tag-item">Apps</span>' in markup
    assert '<span class="tag-more">+1</span>' in markup
    assert "items" not in markup


def test_v85_large_left_tab_form_uses_dialog_bounds_by_default():
    model = view()
    model["lay"].update(width=1800, height=950)
    find(model["lay"], "Страницы")["representation"] = "TabsOnLeftHorizontal"

    automatic = form_view.build(model).html
    explicit = form_view.build(model, width=900, height=600).html

    assert 'class="v85 light bounded-window"' in automatic
    assert 'width:min(1380px, calc(100vw - 36px))' in automatic
    assert 'height:min(818px, calc(100vh - 36px))' in automatic
    assert '.win { width:900px; min-width:0; height:600px; }' in explicit
    assert 'class="v85 light sized-window"' in explicit


def test_v85_preserves_grouped_table_headers_and_runtime_empty_state():
    model = view()
    grid = find(model["lay"], "Адреса")
    table_node = find(grid, "Адреса#group")["children"][1]
    address = table_node["children"][0]
    table_node["children"] = [
        {"_c": "HippoLayTableColumnsGroup", "grouping": "InCell", "children": [
            {"_c": "HippoLayTableColumn", "name": "Enabled", "columnMode": "Check", "showInHeader": False},
            address,
        ]},
        {"_c": "HippoLayTableColumnsGroup", "title": "Authentication", "grouping": "Horizontal",
         "showInHeader": True, "children": [
            {"_c": "HippoLayTableColumn", "name": "Issuer", "title": "Issuer"},
            {"_c": "HippoLayTableColumn", "name": "Audience", "title": "Audience"},
        ]},
    ]
    drawn = form_view.build(model, page="Адреса", data={"tables": {"Адреса": {"rows": [], "title": "Addresses"}}})
    markup = drawn.html.split('<div class="stage">', 1)[1]
    assert '<th rowspan="2">Адрес</th>' in markup
    assert '<th colspan="2">Authentication</th>' in markup
    assert '>Список пуст<' in markup
    assert '>Addresses<' in markup
    assert '>Список пуст<' not in form_view.build(model, page="Адреса").html


def test_v85_disables_toolbar_and_checkbox_controls_and_hides_the_disabled_dropdown():
    model = view()
    content = find(model["lay"], "Главное#content")
    content["children"].append({"_c": "HippoLayCheckBox", "name": "Remember"})
    assets = form_view.Assets(symbols=frozenset({"svg_cornerBottom", "pic_plus"}))
    drawn = form_view.build(model, data={"disabled": ["АдресаДобавить", "Владелец", "Remember"]}, assets=assets)
    markup = drawn.html.split('<div class="stage">', 1)[1]
    assert '<span class="cbtn" aria-disabled="true">' in markup
    assert '<span class="cbtn-label">Добавить</span>' in markup
    assert 'class="chkrow fixed dis" aria-disabled="true"' in markup
    assert 'href="#svg_cornerBottom"' not in markup.split('<div class="pages', 1)[1]


def test_v85_shows_extended_help_on_the_page():
    model = view()
    find(model["lay"], "Главное")["children"] = [{
        "_c": "HippoLayFormRichElement", "name": "Page#richGroup", "children": [
            find(model["lay"], "Главное#content"),
            {"_c": "HippoLayImage", "name": "Главное#extTooltipBtn", "extTooltip": "Page help"},
        ],
    }]
    drawn = form_view.build(model)
    markup = drawn.html.split('<div class="stage">', 1)[1]
    assert 'class="page-help" title="Page help"' in markup


def test_v85_orders_generated_create_based_on_before_the_custom_menu():
    model = view()
    model["lay"]["children"].insert(0, {
        "_c": "HippoLayCommandBar", "location": "FormTop", "children": [
            {"_c": "HippoLaySubMenu", "title": "Publication file"},
            {"_c": "HippoLaySubMenu", "title": "Create based on", "formModelElement": {"generated": True},
             "children": [{"_c": "HippoLayCommand", "formModelElement": {
                 "command": {"source": "StandardCommand", "name": "CreateBasedOn"}}}]},
        ],
    })
    markup = form_view.build(model).html.split('<div class="stage">', 1)[1]
    assert markup.index("Create based on") < markup.index("Publication file")


def test_v85_table_submenu_arrow_follows_its_caption_and_header_commands_can_be_disabled():
    model = view()
    model["lay"]["children"].insert(0, {
        "_c": "HippoLayCommandBar", "location": "FormTop", "children": [
            {"_c": "HippoLayCommand", "name": "Revoke", "title": "Revoke token",
             "locationInCommandBar": "InCommandBar"},
        ],
    })
    find(model["lay"], "АдресаКоманднаяПанель")["children"].append(
        {"_c": "HippoLaySubMenu", "name": "ListSettings", "title": "Настройки списка",
         "locationInCommandBar": "InCommandBar"},
    )
    assets = form_view.Assets(symbols=frozenset({"svg_cornerBottom"}))
    drawn = form_view.build(model, page="Адреса", data={"disabled": ["Revoke"]}, assets=assets)
    markup = drawn.html.split('<div class="stage">', 1)[1]
    assert '<span class="pill" aria-disabled="true">Revoke token</span>' in markup
    assert 'Настройки списка</span><svg width="16" height="16"' in markup


def test_v85_project_picture_prefers_version8_5_variant_for_the_requested_theme():
    picked = []

    def bridge(name, args):
        variant = args.get("variant")
        if variant is None:
            return reply({"found": True, "variants": [
                {"name": "choice-taxi.png", "contentType": "image/png", "interfaceVariant": "taxi"},
                {"name": "choice-v85-light.svg", "contentType": "image/svg+xml",
                 "interfaceVariant": "version8_5", "theme": "light"},
                {"name": "choice-v85-dark.svg", "contentType": "image/svg+xml",
                 "interfaceVariant": "version8_5", "theme": "dark"},
            ]})
        picked.append(variant)
        return reply({"contentType": "image/svg+xml", "base64": "PHN2Zz48L3N2Zz4="})

    assert form_render._project_picture(bridge, "P", "Choice", interface="v85", theme="dark") \
        == "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="
    assert picked == ["choice-v85-dark.svg"]


def test_v85_hides_list_settings_and_search_management_submenus_from_table_bar():
    model = view()
    bar = find(model["lay"], "АдресаКоманднаяПанель")
    bar["children"].extend([
        {"_c": "HippoLaySubMenu", "name": "СписокНастройки", "title": "Настройки списка",
         "formModelElement": {"generated": True}, "children": [
             {"_c": "HippoLayCommand", "title": "Настроить список...",
              "locationInCommandBar": "InAdditionalSubmenu", "important": False},
         ]},
        {"_c": "HippoLaySubMenu", "name": "СписокУправлениеПоиском", "title": "Управление поиском",
         "children": [
             {"_c": "HippoLayCommand", "formModelElement": {"command": {"source": "standard", "name": name}}}
             for name in ("Find", "FindByCurrentValue", "SearchHistory")
         ]},
        {"_c": "HippoLayEdit", "name": "СтрокаПоиска"},
    ])
    markup = form_view.build(model, page="Адреса", assets=ASSETS).html.split('<div class="stage">', 1)[1]
    assert "Настройки списка" not in markup
    assert "Управление поиском" not in markup
    assert "Поиск" in markup


def test_tree_indents_the_first_non_check_column():
    model = view()
    table = find(model["lay"], "Адреса#group")["children"][1]
    table["representation"] = "Tree"
    table["children"].insert(0, {"_c": "HippoLayTableColumn", "name": "Выбрать", "title": "Выбрать",
                                 "columnMode": "Check"})
    data = {"tables": {"Адреса": {"rows": [
        {"Выбрать": False, "Адрес": "Root", "_level": 0, "_open": True},
        {"Выбрать": True, "Адрес": "Child", "_level": 1},
    ]}}}
    assets = form_view.Assets(symbols=frozenset({"svg_cornerBottom", "svg_cornerRight"}))
    markup = form_view.build(model, page="Адреса", data=data, assets=assets).html.split('<div class="stage">', 1)[1]
    assert 'style="margin-left:0px"><svg width="16" height="16"' in markup
    assert 'style="margin-left:28px"></span>Child</td>' in markup





@pytest.mark.parametrize("interface, theme", [("taxi", "light"), ("v85", "light"), ("v85", "dark")])
def test_draws_runtime_statuses_with_configuration_pictures(interface, theme):
    picture = "data:image/png;base64,AA"
    pictures = {"ReadyStatus": picture}
    statuses = [{"text": "Ready & active", "picture": "ReadyStatus"}, {"text": "Text only"}]
    drawn = form_view.build(view(), interface=interface, theme=theme,
                           assets=form_view.Assets(project_picture=pictures.get), data={"statuses": statuses})

    assert '<div class="form-statuses card">' in drawn.html
    assert f'<img src="{picture}" alt="">' in drawn.html
    assert drawn.html.index("Ready &amp; active") < drawn.html.index("Text only")
    assert drawn.html.index('<div class="form-statuses card">') < drawn.html.index('class="fld')


def test_selecting_a_nested_page_opens_its_parent():
    model = view()
    outer = find(model["lay"], "Страницы")
    inner = {"_c": "HippoLayPages", "name": "AuthenticationPages", "children": [
        page("Provider", "Provider", [field("Endpoint", "Endpoint", "Endpoint")]),
        page("Blocking", "Blocking", []),
    ]}
    outer["children"][1]["children"].append(inner)

    drawn = form_view.build(model, page="Provider", data={"values": {"Endpoint": "example.test"}})

    assert drawn.page == "Provider"
    assert '<span class="tab on">Адреса' in drawn.html
    assert '<span class="tab on">Provider</span>' in drawn.html


def test_a_page_group_without_tabs_draws_only_the_active_page():
    model = view()
    find(model["lay"], "Страницы")["representation"] = "None"

    drawn = form_view.build(model, page="Адреса")

    assert drawn.page == "Адреса"
    assert '<div class="tabs">' not in drawn.html
    assert '<div class="page ghost">' not in drawn.html
    assert '<div class="page">' in drawn.html
