#!/usr/bin/env python
"""Render docs/*.svg into the PNGs the READMEs embed.

The site shows the SVG itself, and that one follows the reader's theme through
`prefers-color-scheme`. A README on GitHub does not: it needs a PNG, and which palette that
PNG carries must not depend on the machine doing the rendering. Headless Chrome renders with
the system preference, so the script FORCES the palette instead - it drops the media query
and inlines the requested branch, and the same command then produces the same file anywhere.

    python scripts/render-diagrams.py                    # every diagram, the dark palette
    python scripts/render-diagrams.py --theme light
    python scripts/render-diagrams.py --only delivery.ru.svg

Two flags are the whole point of the browser invocation, and both have been forgotten before:

    --default-background-color=00000000   the page background must be TRANSPARENT. A browser
                                          paints white by default, and that white bakes into
                                          the corners of the rounded frame - it shows up as
                                          white notches on GitHub.
    --force-device-scale-factor=2         the images are 2x so they stay sharp on HiDPI.

The window size has to match the svg's own width/height, otherwise the shot is cropped or
padded. Chrome is looked up in the usual install locations, or pass --chrome.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

BROWSER_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
]

#: The media query holding the dark palette, as written in the sources.
_DARK_BLOCK = re.compile(r"@media \(prefers-color-scheme: dark\) \{\s*(:root \{.*?\})\s*\}", re.S)
_SIZE = re.compile(r'<svg[^>]*?width="(\d+)"[^>]*?height="(\d+)"')


def find_browser(explicit: str | None) -> str:
    if explicit:
        return explicit
    for candidate in BROWSER_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    found = shutil.which("chrome") or shutil.which("chromium") or shutil.which("google-chrome")
    if found:
        return found
    raise SystemExit("no Chrome/Edge found - pass the path with --chrome")


def force_theme(svg: str, theme: str) -> str:
    """Make the requested palette unconditional: a render must not depend on the machine."""
    dark = _DARK_BLOCK.search(svg)
    if not dark:
        raise SystemExit("the diagram carries no @media (prefers-color-scheme: dark) block")
    if theme == "light":
        return _DARK_BLOCK.sub("", svg)  # what is left is the default palette
    # Dark: drop the media query and append its :root after the light one - the later one wins.
    return _DARK_BLOCK.sub("", svg).replace("</style>", dark.group(1) + "\n  </style>", 1)


def render(browser: str, svg_path: Path, theme: str) -> Path:
    svg = svg_path.read_text(encoding="utf-8")
    size = _SIZE.search(svg)
    if not size:
        raise SystemExit(f"{svg_path.name}: cannot read width/height")
    width, height = size.group(1), size.group(2)
    out = svg_path.with_suffix(".png")
    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp) / svg_path.name
        staged.write_text(force_theme(svg, theme), encoding="utf-8")
        subprocess.run(
            [
                browser, "--headless=new", "--disable-gpu",
                "--force-device-scale-factor=2",
                f"--window-size={width},{height}",
                "--default-background-color=00000000",
                f"--screenshot={out}",
                staged.as_uri(),
            ],
            check=True, capture_output=True, timeout=120,
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--theme", choices=("dark", "light"), default="dark",
                        help="palette to render with (default: dark)")
    parser.add_argument("--only", help="a single .svg file name in docs/")
    parser.add_argument("--chrome", help="path to Chrome/Chromium/Edge")
    args = parser.parse_args()

    browser = find_browser(args.chrome)
    sources = [DOCS / args.only] if args.only else sorted(
        p for p in DOCS.glob("*.svg") if _DARK_BLOCK.search(p.read_text(encoding="utf-8"))
    )
    if not sources:
        print("nothing to render: no diagram in docs/ keeps its palette in variables")
        return 0
    for source in sources:
        out = render(browser, source, args.theme)
        print(f"{source.name} -> {out.name} ({out.stat().st_size // 1024} KB, {args.theme})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
