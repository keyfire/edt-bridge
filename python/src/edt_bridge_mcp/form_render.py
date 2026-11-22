"""edt_form_render in the wrapper: a managed form drawn the way the 1C client shows it.

The bridge inside EDT knows the form: it lays it out with EDT's own engine and hands over the client
view model (edt_form_render with engine "model"). The wrapper draws that model as an HTML page in the
look of the web client - "Version 8.5" or "Taxi", light or dark theme, any page of the form, sample
data from the call - and takes a PNG of it with Chromium. Icons and fonts are the web client's own,
read from the 1C:Enterprise platform installed here (client_assets.py).

engine "edt" keeps the earlier behaviour: the bridge's picture of the EDT form editor, passed through
unchanged.
"""
from __future__ import annotations

import json
import os
import re
import tempfile
from dataclasses import replace
from functools import cache
from pathlib import Path
from typing import Any, Callable

from . import client_assets, form_view, toolsets

NAME = "edt_form_render"
ENGINES = ("client", "edt")

DESCRIPTOR = {
    "name": NAME,
    "description": (
        "Draw a managed form the way the 1C client shows it and save a PNG. engine \"client\" (default): "
        "the form laid out by EDT and drawn in the look of the web client - interface v85 (\"Version 8.5\") "
        "or taxi, theme light or dark (dark exists in v85 only), the page chosen by name or title, titles "
        "in the configuration language including the standard commands, the command bars the platform "
        "fills (autoCommandBar), sample values and table rows from data. Icons and fonts are read from the "
        "1C:Enterprise 8.5 platform installed on this machine; Chromium makes the PNG (pip extra "
        "edt-bridge-mcp[web], then playwright install chromium). Returns the PNG and HTML paths, the pages "
        "of the form and warnings about anything left out. engine \"edt\": EDT's form editor preview, as "
        "before (variant, density, ratio, width, height apply to it)."
    ),
    "descriptionRu": (
        "Нарисовать управляемую форму так, как ее показывает клиент 1С, и сохранить PNG. engine \"client\" "
        "(по умолчанию): форма, разложенная EDT, в оформлении веб-клиента - интерфейс v85 (\"Версия 8.5\") "
        "или taxi, тема light или dark (темная есть только в 8.5), страница по имени или заголовку, подписи "
        "на языке конфигурации, включая стандартные команды, командные панели, которые заполняет платформа "
        "(autoCommandBar), значения и строки таблиц примера из data. Значки и шрифты берутся из платформы "
        "1С:Предприятие 8.5, установленной на этой машине; PNG делает Chromium (дополнение "
        "edt-bridge-mcp[web], затем playwright install chromium). Возвращает пути PNG и HTML, страницы формы "
        "и предупреждения о том, что не нарисовано. engine \"edt\": снимок редактора форм EDT, как раньше "
        "(параметры variant, density, ratio, width, height)."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "projectName": {"type": "string", "description": "EDT project name"},
            "formFqn": {"type": "string",
                        "description": "Form FQN, e.g. CommonForm.МояФорма, Catalog.Контрагенты.Form.ФормаЭлемента"},
            "fqn": {"type": "string", "description": "Alias for formFqn. Pass only one name."},
            "engine": {"type": "string", "enum": list(ENGINES),
                       "description": "client (default): the look of the 1C client; edt: EDT's form editor preview"},
            "interface": {"type": "string",
                          "description": "client engine: v85 (default, \"Version 8.5\") or taxi"},
            "theme": {"type": "string", "description": "light (default) or dark; dark exists in v85 only"},
            "page": {"type": "string",
                     "description": "client engine: the page to show, by name or title (default: the first one)"},
            "data": {
                "type": "object",
                "description": (
                    "client engine: sample data. title - the window title; values - {item name or data path: "
                    "value} for fields (a boolean for a check box, 2 for its third state, an item title or "
                    "index for a tumbler or radio buttons; {items: [display text, ...], more: count} "
                    "for tokens in a field or table cell); tables - {table name: {rows: [...], current: "
                    "index, title: caption}}, a row is a list of cell values in column order or an object by column name, "
                    "with _level, _open, _check for trees and marks; hidden / shown - item names whose "
                    "visibility the form code changes; disabled - unavailable item and command names; "
                    "pageTitles - {page name: displayed caption}; messages - [{text, type: info / warning / success / "
                    "error, group}] important messages the form code puts in a group (none: under the "
                    "header)"),
            },
            "density": {"type": "string",
                        "description": "client engine: auto (default, the form's own scale variant), normal or "
                                       "compact; edt engine: NORMAL or COMPACT"},
            "scale": {"type": "integer",
                      "description": "PNG scale in percent: client engine renders at it (default 150), edt "
                                     "engine upscales its raster"},
            "outPath": {"type": "string", "description": "Absolute PNG path (default: <tmp>/edt_form_<...>.png)"},
            "platformPath": {"type": "string",
                             "description": "client engine: the bin directory of the 1C:Enterprise 8.5 platform to "
                                            "read the web client icons and fonts from (default: the newest one "
                                            "installed; EDT_BRIDGE_PLATFORM)"},
            "variant": {"type": "string",
                        "description": "edt engine: TAXI or VERSION8_5; also accepted for interface"},
            "ratio": {"type": "integer", "description": "edt engine: interface common ratio in percent"},
            "width": {"type": "integer", "description": "Window width in px before scaling; client default: "
                                                        "fit the form, edt default: 1280"},
            "height": {"type": "integer", "description": "Height in px before scaling; client: window height "
                                                         "(v85) or minimum height (taxi), edt: viewport height "
                                                         "(default 800)"},
        },
        "required": ["projectName"],
    },
    "toolsets": list(toolsets.KNOWN),
}

#: Arguments the bridge's own edt_form_render takes (engine "edt").
_BRIDGE_ARGUMENTS = ("projectName", "formFqn", "fqn", "variant", "theme", "density", "ratio", "width", "height",
                     "scale", "outPath")


class RenderError(RuntimeError):
    pass


#: Library calls in a form module that add what the form as written does not have.
RUNTIME_ADDITIONS = {
    "ДобавитьСтатус": "a status in the header", "AddStatus": "a status in the header",
    "ДобавитьВажноеСообщение": "an important message under the header",
    "AddImportantMessage": "an important message under the header",
    "ВставитьВажноеСообщение": "an important message in a group",
    "InsertImportantMessage": "an important message in a group",
    "УправлениеСвойствами.ПриСозданииНаСервере": "the additional attributes page",
    "PropertyManager.OnCreateAtServer": "the additional attributes page",
    "Элементы.Добавить(": "new items", "Items.Add(": "new items",
}
#: Checks a form module makes to lay the form out one way for "Taxi" and another for 8.5.
INTERFACE_BRANCHES = ("ЭтоВариантИнтерфейса85", "IsInterfaceVariant85", "ВариантИнтерфейсаКлиентскогоПриложения",
                      "ClientApplicationInterfaceVariant")
_SET_BY_CODE = re.compile(r"(?:Элементы|Items)\.(\w+)\.(Видимость|Visible|РежимВыбораИзСписка|ListChoiceMode)\s*=")


def code_notes(source: str) -> list[str]:
    """What the drawing cannot show because the form code decides it, named from the module text."""
    notes = []
    added = sorted({what for call, what in RUNTIME_ADDITIONS.items() if call in source})
    if added:
        notes.append("the form code adds at runtime: " + ", ".join(added)
                     + " - not drawn unless passed in data (messages for important messages)")
    if any(marker in source for marker in INTERFACE_BRANCHES):
        notes.append("the form code lays the form out differently by interface variant: what it changes for "
                     "one interface is not drawn")
    found = {"visible": set(), "choice": set()}
    for match in _SET_BY_CODE.finditer(source):
        found["visible" if match.group(2) in ("Видимость", "Visible") else "choice"].add(match.group(1))
    if found["visible"]:
        notes.append("the form code sets the visibility of: " + ", ".join(sorted(found["visible"]))
                     + " - pass hidden or shown in data for the case to draw")
    if found["choice"]:
        notes.append("the form code turns on choice from a list for: " + ", ".join(sorted(found["choice"]))
                     + " - their drop-down buttons are not drawn")
    return notes


def _module_source(bridge: Callable[[str, dict], Any], project: str, fqn: str) -> str:
    """The form module text, or nothing when the bridge cannot give it (an old plugin, no module)."""
    try:
        module = _bridge_json(bridge("edt_module_text", {"projectName": project, "fqn": fqn,
                                                         "includeMethods": False}))
    except RenderError:
        return ""
    text = module.get("text") if isinstance(module, dict) else None
    return text if isinstance(text, str) else ""


def _default_path(fqn: str, interface: str, theme: str, page: str | None) -> Path:
    safe = re.sub(r"[^\w.-]", "_", fqn)
    suffix = f"_{re.sub(r'[^0-9A-Za-zА-Яа-яЁё_-]', '_', page)}" if page else ""
    return Path(tempfile.gettempdir()) / f"edt_form_{safe}_{interface}_{theme}{suffix}.png"


def _bridge_json(reply: Any) -> dict:
    """The JSON a bridge tool answered with, or RenderError with its message."""
    if not isinstance(reply, dict):
        raise RenderError("the bridge gave no answer")
    if "error" in reply:
        raise RenderError(str(reply["error"].get("message") if isinstance(reply["error"], dict) else reply["error"]))
    result = reply.get("result") or {}
    text = "".join(part.get("text", "") for part in result.get("content") or [] if isinstance(part, dict))
    if result.get("isError"):
        raise RenderError(text or "the bridge refused the call")
    try:
        return json.loads(text)
    except ValueError as error:
        raise RenderError(f"the bridge answered with something that is not JSON: {text[:200]}") from error


def _project_picture(bridge: Callable[[str, dict], Any], project: str, name: str,
                     interface: str = "taxi", theme: str = "light") -> str:
    """Select the project's picture matching the interface and theme."""
    params = {"projectName": project, "fqn": f"CommonPicture.{name}"}
    supported = ("image/png", "image/svg+xml", "image/gif", "image/jpeg", "image/bmp")
    try:
        collection = _bridge_json(bridge("edt_picture_export", params))
    except RenderError:
        collection = {}
    if collection.get("found") is False:
        return ""
    if interface == "v85":
        wanted_variants = ("", "version8_5", "version85", "v85", "8.5")
        wanted_themes = ("", theme)
    else:
        wanted_variants = ("", "taxi", "version8_2")
        wanted_themes = ("", "light")
    variants = [v for v in collection.get("variants") or [] if isinstance(v, dict)
                and isinstance(v.get("name"), str) and v.get("contentType") in supported
                and (v.get("interfaceVariant") or "").lower() in wanted_variants
                and (v.get("theme") or "").lower() in wanted_themes]
    if interface == "v85":
        variants.sort(key=lambda v: (not v.get("interfaceVariant"), (v.get("theme") or "").lower() != theme,
                                     v.get("contentType") != "image/svg+xml", v.get("screenDensity") != "ldpi"))
        fallbacks = ("svg", "best", "100.png", "Picture.png")
    else:
        variants.sort(key=lambda v: (not v.get("interfaceVariant"), v.get("screenDensity") != "ldpi",
                                     v.get("contentType") != "image/png", v.get("name") != "100.png"))
        fallbacks = ("100.png", "Picture.png", "best")
    preferred = [v["name"] for v in variants]
    for variant in dict.fromkeys([*preferred, *fallbacks]):
        try:
            image = _bridge_json(bridge("edt_picture_export", {**params, "variant": variant}))
        except RenderError:
            return ""
        if image.get("found") is False:
            return ""
        mime, encoded = image.get("contentType"), image.get("base64")
        if mime in supported and isinstance(encoded, str) \
                and re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", encoded):
            return f"data:{mime};base64,{encoded}"
    return ""


def _taxi_project_picture(bridge: Callable[[str, dict], Any], project: str, name: str) -> str:
    """Select the project's light Taxi picture at the default density."""
    return _project_picture(bridge, project, name, interface="taxi", theme="light")


def shoot(html_path: Path, png_path: Path, scale: int) -> tuple[int, int]:
    """PNG of the window on the page, at `scale` percent. Returns its size in pixels."""
    try:
        from playwright.sync_api import Error, sync_playwright
    except ImportError as error:
        raise RenderError("Chromium is not available to take the picture: install the extra "
                          "edt-bridge-mcp[web] and run playwright install chromium") from error
    factor = max(50, min(int(scale or 150), 400)) / 100
    with sync_playwright() as playwright:
        browser = None
        problems = []
        for options in ({}, {"channel": "msedge"}, {"channel": "chrome"}):
            try:
                browser = playwright.chromium.launch(args=["--no-proxy-server"],
                                                    ignore_default_args=["--hide-scrollbars"], **options)
                break
            except Error as error:
                problems.append(str(error).splitlines()[0])
        if browser is None:
            raise RenderError("no Chromium could be started: " + "; ".join(problems)
                              + ". Run playwright install chromium.")
        try:
            page = browser.new_page(viewport={"width": 1600, "height": 1200}, device_scale_factor=factor)
            page.goto(html_path.resolve().as_uri())
            page.evaluate("document.fonts.ready")
            window = page.locator(".stage")
            window.screenshot(path=str(png_path))
            box = window.bounding_box() or {"width": 0, "height": 0}
        finally:
            browser.close()
    return int(round(box["width"] * factor)), int(round(box["height"] * factor))


def run(arguments: dict, bridge: Callable[[str, dict], Any]) -> dict:
    """Draw the form. `bridge(name, args)` calls a bridge tool and returns its JSON-RPC reply."""
    engine = str(arguments.get("engine") or "client").lower()
    if engine not in ENGINES:
        raise RenderError(f"engine must be one of {', '.join(ENGINES)}")
    if engine == "edt":
        forwarded = {k: v for k, v in arguments.items() if k in _BRIDGE_ARGUMENTS}
        if arguments.get("interface") and not forwarded.get("variant"):
            forwarded["variant"] = "TAXI" if form_view.normalize_interface(arguments["interface"]) == "taxi" else "VERSION8_5"
        return _bridge_json(bridge(NAME, forwarded))
    fqn = arguments.get("formFqn") or arguments.get("fqn")
    project = arguments.get("projectName")
    if not project or not fqn:
        raise RenderError("projectName and formFqn are required")
    interface = form_view.normalize_interface(arguments.get("interface") or arguments.get("variant") or "v85")
    theme = str(arguments.get("theme") or "light").lower()
    theme = "dark" if "dark" in theme else "light" if "light" in theme else theme
    density = str(arguments.get("density") or "auto").lower()
    if density not in ("auto", "normal", "compact"):
        raise RenderError("density must be auto, normal or compact")
    data = arguments.get("data")
    if data is not None and not isinstance(data, dict):
        raise RenderError("data must be an object: {title, values, tables, hidden, shown, messages}")
    if interface == "taxi" and theme == "dark":
        raise RenderError("the platform has no dark theme for Taxi: use theme light, or interface v85")
    page = arguments.get("page")
    out = Path(arguments.get("outPath") or _default_path(str(fqn), interface, theme, page))
    if not out.is_absolute():
        raise RenderError("outPath must be an absolute path")
    model = _bridge_json(bridge(NAME, {
        "projectName": project, "formFqn": fqn, "engine": "model",
        "variant": "TAXI" if interface == "taxi" else "VERSION8_5",
        "theme": "DARK" if theme == "dark" else "LIGHT",
        "outPath": str(out.with_suffix(".edt.png")),
    }))
    if not model.get("ok") or not isinstance(model.get("layModel"), dict):
        raise RenderError(model.get("message") or "the bridge returned no layout of the form - update the plugin "
                          "in EDT to a version with engine \"model\"")
    warnings = []
    try:
        assets = client_assets.load(arguments.get("platformPath"))
    except client_assets.AssetError as error:
        assets = form_view.Assets()
        warnings.append(f"the web client icons and fonts were not found ({error}); drawn with system fonts "
                        "and plain marks")
    assets = replace(assets, project_picture=cache(
        lambda name: _project_picture(bridge, project, name, interface=interface, theme=theme)))
    drawing = form_view.build(model["layModel"], interface=interface, theme=theme, page=page, data=data,
                              assets=assets, density=None if density == "auto" else density,
                              width=arguments.get("width"), height=arguments.get("height"))
    html_path = out.with_suffix(".html")
    out.parent.mkdir(parents=True, exist_ok=True)
    html_path.write_text(drawing.html, encoding="utf-8", newline="")
    try:
        os.remove(out.with_suffix(".edt.png"))
    except OSError:
        pass
    scale = int(arguments.get("scale") or 150)
    width, height = shoot(html_path, out, scale)
    report = {
        "ok": True, "formFqn": fqn, "engine": "client", "interface": interface, "theme": theme,
        "density": drawing.density, "page": drawing.page, "pages": drawing.pages, "title": drawing.title,
        "pngPath": str(out), "htmlPath": str(html_path), "width": width, "height": height, "scale": scale,
        "assets": assets.source,
    }
    warnings += code_notes(_module_source(bridge, project, str(fqn)))
    if drawing.warnings or warnings:
        report["warnings"] = warnings + drawing.warnings
    return report
