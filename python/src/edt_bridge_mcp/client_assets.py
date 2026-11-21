"""The web client's own icons and fonts, read from the 1C:Enterprise platform installed here.

The "Version 8.5" web client draws its icons from an SVG sprite and its text with Roboto (Almarai
only for Arabic). Both ship inside the platform's resource files: the sprite with the client
scripts in bin/mngsrv_root.res, the fonts in bin/mngcore_root.res. Command buttons show platform
pictures (a plus for "Add", arrows for "Move up"): small zip archives with an SVG per interface in
bin/core85_root.res, recolored by the client to the theme. They belong to the platform,
so edt-bridge does not carry them: the first drawing reads them from the installation found on
this machine and keeps them in the user cache, one entry per platform build.

Nothing here parses the resource container itself. The sprite is the <svg> block holding the most
<symbol id="svg_..."> elements together with the symbols the drawing needs; a font is a WOFF file
whose header states its own length, named by its "name" table.
"""
from __future__ import annotations

import base64
import json
import os
import re
import struct
import zlib
from pathlib import Path

from . import form_view

ENV_PLATFORM = "EDT_BRIDGE_PLATFORM"
ENV_CACHE = "EDT_BRIDGE_ASSET_CACHE"
LAYOUT = 4
#: Symbols the 8.5 drawing uses; a sprite without them is not the client's.
REQUIRED_SYMBOLS = ("svg_moreTop", "svg_closeBig", "svg_context", "svg_search", "svg_plus", "svg_minus",
                    "svg_cornerBottom", "svg_open", "svg_infoSmall", "svg_checkbox")
#: Font files the client uses, by the family/style their name table gives.
FONTS = {("Roboto", "Regular"): ("Roboto", 400), ("Roboto Medium", "Regular"): ("Roboto Medium", 400),
         ("Roboto", "Bold"): ("Roboto", 700), ("Roboto", "Medium"): ("Roboto Medium", 400),
         ("Almarai", "Regular"): ("Almarai", 400), ("Almarai", "Bold"): ("Almarai", 700)}
#: The client restricts Almarai to Arabic; Latin, digits and Cyrillic are Roboto (measured on 8.5.1).
ALMARAI_RANGE = "U+0600-06FF, U+200C-200E, U+2010-2011, U+204F, U+2E41, U+FB50-FDFF, U+FE80-FEFC"


class AssetError(RuntimeError):
    pass


def _version_key(name: str) -> tuple:
    return tuple(int(part) for part in re.findall(r"\d+", name))


def platform_dirs(explicit: str | None = None, environ=os.environ) -> list[Path]:
    """bin directories of 8.5+ installations, newest first; an explicit path comes alone."""
    given = explicit or environ.get(ENV_PLATFORM)
    if given:
        path = Path(given)
        for candidate in (path, path / "bin"):
            if (candidate / "mngsrv_root.res").is_file():
                return [candidate]
        raise AssetError(f"no web client resources (mngsrv_root.res) under {given}")
    roots = []
    for base in (environ.get("ProgramFiles"), environ.get("ProgramFiles(x86)"), environ.get("ProgramW6432")):
        if base:
            roots.append(Path(base) / "1cv8")
    roots += [Path("/opt/1cv8/x86_64"), Path("/opt/1cv8")]
    found = []
    for root in roots:
        if not root.is_dir():
            continue
        for entry in root.iterdir():
            key = _version_key(entry.name)
            if len(key) < 2 or key[:2] < (8, 5):
                continue
            for candidate in (entry / "bin", entry):
                if (candidate / "mngsrv_root.res").is_file():
                    found.append((key, candidate))
                    break
    found.sort(key=lambda item: item[0], reverse=True)
    seen, result = set(), []
    for _, path in found:
        if path not in seen:
            seen.add(path)
            result.append(path)
    return result


def cache_root(environ=os.environ) -> Path:
    value = (environ.get(ENV_CACHE) or "").strip()
    if value:
        return Path(value)
    base = environ.get("XDG_CACHE_HOME")
    return (Path(base) if base else Path.home() / ".cache") / "edt-bridge" / "client-assets"


def _sprite_blocks(resource: bytes) -> list[tuple[bytes, list[str]]]:
    """Every <svg> block of the resource that defines svg_* symbols, with the ids it defines."""
    blocks = []
    for match in re.finditer(rb"<svg\b[^>]*>", resource):
        end = resource.find(b"</svg>", match.end())
        if end < 0:
            continue
        block = resource[match.start():end + 6]
        ids = re.findall(rb'<symbol\b[^>]*?\bid="(svg_[^"]+)"', block)
        if ids:
            blocks.append((block, [i.decode("ascii", "replace") for i in ids]))
    return blocks


def _sprite_markup(block: bytes) -> str:
    symbols = re.findall(rb"<symbol\b.*?</symbol>", block, re.S)
    return ('<svg xmlns="http://www.w3.org/2000/svg" style="position:absolute;width:0;height:0;overflow:hidden">'
            + b"".join(symbols).decode("utf-8", "replace") + "</svg>")


def extract_sprite(resource: bytes) -> tuple[str, list[str]]:
    """The 8.5 client's icon sprite: (<svg> markup hidden from view, symbol ids)."""
    blocks = _sprite_blocks(resource)
    if not blocks:
        raise AssetError("no icon sprite in the web client resources")
    block, names = max(blocks, key=lambda item: len(item[1]))
    missing = [s for s in REQUIRED_SYMBOLS if s not in names]
    if missing:
        raise AssetError("the largest sprite in the web client resources lacks " + ", ".join(missing))
    return _sprite_markup(block), sorted(set(names))


def extract_taxi_sprite(resource: bytes) -> tuple[str, list[str]] | None:
    """The Taxi client's sprite: the largest block besides the 8.5 one that draws the check box mark
    on a 16 px grid (measured: the 8.5.1 web client in Taxi loads a sprite of 141 such symbols)."""
    blocks = _sprite_blocks(resource)
    if not blocks:
        return None
    main = max(blocks, key=lambda item: len(item[1]))
    others = [b for b in blocks if b is not main and "svg_favorite" in b[1]
              and re.search(rb'<symbol\b[^>]*id="svg_checkbox"[^>]*viewBox="0 0 16 16"', b[0])]
    if not others:
        return None
    block, names = max(others, key=lambda item: len(item[1]))
    return _sprite_markup(block), sorted(set(names))


def _woff_name(blob: bytes) -> tuple[str, str] | None:
    """(family, subfamily) from the name table of a WOFF 1 font."""
    try:
        tables = struct.unpack(">H", blob[12:14])[0]
        for index in range(tables):
            entry = blob[44 + index * 20: 64 + index * 20]
            tag, offset, comp, orig, _ = struct.unpack(">4sIIII", entry)
            if tag != b"name":
                continue
            data = blob[offset:offset + comp]
            if comp < orig:
                data = zlib.decompress(data)
            count, string_offset = struct.unpack(">HH", data[2:6])
            names = {}
            for r in range(count):
                platform, encoding, language, name_id, length, start = struct.unpack(
                    ">HHHHHH", data[6 + r * 12: 18 + r * 12])
                raw = data[string_offset + start: string_offset + start + length]
                if name_id in (1, 2, 16, 17) and platform == 3:
                    names.setdefault(name_id, raw.decode("utf-16-be", "replace"))
            family = names.get(16) or names.get(1)
            style = names.get(17) or names.get(2)
            return (family, style) if family and style else None
    except (struct.error, zlib.error, UnicodeDecodeError):
        return None
    return None


def extract_fonts(resource: bytes) -> dict[tuple[str, int], bytes]:
    """WOFF fonts of the client by (CSS family, weight). WOFF 1 is used: its tables need only zlib."""
    fonts = {}
    for match in re.finditer(b"wOFF", resource):
        start = match.start()
        try:
            length = struct.unpack(">I", resource[start + 8:start + 12])[0]
        except struct.error:
            continue
        if length < 100 or start + length > len(resource):
            continue
        blob = resource[start:start + length]
        named = _woff_name(blob)
        if named is None:
            continue
        family = FONTS.get(named)
        if family and family not in fonts:
            fonts[family] = blob
    if ("Roboto", 400) not in fonts:
        raise AssetError("no Roboto font in the web client resources")
    return fonts


def extract_pictures(resource: bytes) -> str:
    """Platform pictures of the 8.5 interface as <symbol id="pic_<name>">, fill left to the drawing."""
    symbols = {}
    for match in re.finditer(rb"PK\x03\x04", resource):
        start = match.start()
        try:
            _, _, method, _, _, _, size, _, name_len, extra_len = struct.unpack(
                "<HHHHHIIIHH", resource[start + 4:start + 30])
        except struct.error:
            continue
        name = resource[start + 30:start + 30 + name_len]
        if not (name.startswith(b"_") and name.endswith(b".svg")) or size > 200_000:
            continue
        key = name[:-4].decode("ascii", "replace")
        if key in symbols:
            continue
        body = resource[start + 30 + name_len + extra_len:start + 30 + name_len + extra_len + size]
        try:
            text = (zlib.decompress(body, -15) if method == 8 else body).decode("utf-8")
        except (zlib.error, UnicodeDecodeError):
            continue
        view = re.search(r'viewBox="([^"]+)"', text)
        inner = re.search(r"<svg\b[^>]*>(.*)</svg>", text, re.S)
        if not view or not inner:
            continue
        paths = re.sub(r'\sfill="(?!none)[^"]*"', "", inner.group(1))
        symbols[key] = f'<symbol id="pic{key}" viewBox="{view.group(1)}">{paths.strip()}</symbol>'
    return "".join(symbols[k] for k in sorted(symbols))


def font_css(fonts: dict[tuple[str, int], bytes]) -> str:
    rules = []
    for (family, weight), blob in sorted(fonts.items()):
        data = base64.b64encode(blob).decode("ascii")
        extra = f"unicode-range:{ALMARAI_RANGE};" if family == "Almarai" else ""
        rules.append(f"@font-face{{font-family:'{family}';font-style:normal;font-weight:{weight};"
                     f"src:url(data:font/woff;base64,{data}) format('woff');{extra}}}")
    return "\n".join(rules)


def load(platform: str | None = None, environ=os.environ) -> form_view.Assets:
    """The client's icons and fonts from the newest 8.5 installation (or the given one), cached."""
    dirs = platform_dirs(platform, environ)
    if not dirs:
        raise AssetError("no 1C:Enterprise 8.5 installation found; install the platform or set "
                         f"{ENV_PLATFORM} (or platformPath) to its bin directory")
    errors = []
    for directory in dirs:
        try:
            return _load_from(directory, environ)
        except (AssetError, OSError) as error:
            errors.append(f"{directory}: {error}")
    raise AssetError("; ".join(errors))


def _load_from(directory: Path, environ) -> form_view.Assets:
    srv, core = directory / "mngsrv_root.res", directory / "mngcore_root.res"
    pictures = directory / "core85_root.res"
    stamp = [LAYOUT, str(directory.resolve())]
    for path in (srv, core) + ((pictures,) if pictures.is_file() else ()):
        stat = path.stat()
        stamp += [stat.st_size, stat.st_mtime_ns]
    key = re.sub(r"[^0-9.]", "", directory.parent.name if directory.name == "bin" else directory.name) or "platform"
    entry = cache_root(environ) / key
    manifest = entry / "manifest.json"
    try:
        known = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        known = None
    if known and known.get("stamp") == stamp:
        sprite = (entry / "sprite.svg").read_text(encoding="utf-8")
        css = (entry / "fonts.css").read_text(encoding="utf-8")
        taxi_path = entry / "sprite-taxi.svg"
        taxi = taxi_path.read_text(encoding="utf-8") if taxi_path.is_file() else None
        return form_view.Assets(sprite=sprite, symbols=frozenset(known.get("symbols") or []), font_css=css,
                                source=str(directory), taxi_sprite=taxi,
                                taxi_symbols=frozenset(known.get("taxiSymbols") or []))
    resource = srv.read_bytes()
    sprite, symbols = extract_sprite(resource)
    taxi = extract_taxi_sprite(resource)
    if pictures.is_file():
        extra = extract_pictures(pictures.read_bytes())
        sprite = sprite.replace("</svg>", extra + "</svg>")
        symbols = sorted(set(symbols) | set(re.findall(r'<symbol id="(pic_[^"]+)"', extra)))
    css = font_css(extract_fonts(core.read_bytes()))
    try:
        entry.mkdir(parents=True, exist_ok=True)
        (entry / "sprite.svg").write_text(sprite, encoding="utf-8", newline="")
        (entry / "fonts.css").write_text(css, encoding="utf-8", newline="")
        if taxi:
            (entry / "sprite-taxi.svg").write_text(taxi[0], encoding="utf-8", newline="")
        manifest.write_text(json.dumps({"stamp": stamp, "symbols": symbols,
                                        "taxiSymbols": taxi[1] if taxi else []}), encoding="utf-8", newline="")
    except OSError:
        pass  # the drawing works without a cache, only slower next time
    return form_view.Assets(sprite=sprite, symbols=frozenset(symbols), font_css=css, source=str(directory),
                            taxi_sprite=taxi[0] if taxi else None,
                            taxi_symbols=frozenset(taxi[1]) if taxi else frozenset())
