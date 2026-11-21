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
        "(к нему относятся variant, density, ratio, width, height)."
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
                    "value} for fields (a boolean for a check box, an item title or index for a tumbler or "
                    "radio buttons); tables - {table name: {rows: [...], current: index}}, a row is a list of "
                    "cell values in column order or an object by column name, with _level, _open, _check for "
                    "trees and marks; hidden / shown - item names whose visibility the form code changes"),
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
            "width": {"type": "integer", "description": "edt engine: viewport width px (default 1280)"},
            "height": {"type": "integer", "description": "edt engine: viewport height px (default 800)"},
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
                browser = playwright.chromium.launch(args=["--no-proxy-server"], **options)
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
        raise RenderError("data must be an object: {title, values, tables, hidden, shown}")
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
    drawing = form_view.build(model["layModel"], interface=interface, theme=theme, page=page, data=data,
                              assets=assets, density=None if density == "auto" else density)
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
    if drawing.warnings or warnings:
        report["warnings"] = warnings + drawing.warnings
    return report
