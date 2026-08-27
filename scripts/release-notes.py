#!/usr/bin/env python
"""Extract one release's section from the changelog for the GitHub release body.

The changelog groups entries by day and names the released versions in the heading
(`## 2026-08-27 – 0.17.0`; a day may name several versions, and the VS Code extension's
changelog names the version alone: `## 0.67.1`). The section of the version being
released becomes the release body – subscribers then see the actual "what's new" in
their feed instead of a bare compare link.

    python scripts/release-notes.py 0.17.0 CHANGELOG.md --out notes.md

Exits 1 when no heading names the version – the workflow decides whether that kills
the release or falls back to the generated notes.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


def extract(text: str, version: str) -> str | None:
    """The body of the `## ` section whose heading names the version, or None.

    The version must stand alone in the heading (`0.17.0`, not a run of `10.17.0`
    or `0.17.0.1`), so the match guards both ends against word and dot characters.
    The heading line itself is dropped: the release page already carries the tag
    and the date.
    """
    lines = text.splitlines()
    token = re.compile(rf"(?<![\w.]){re.escape(version)}(?![\w.])")
    start = None
    for i, line in enumerate(lines):
        if line.startswith("## ") and token.search(line):
            start = i + 1
            break
    if start is None:
        return None
    end = len(lines)
    for j in range(start, len(lines)):
        if lines[j].startswith("## "):
            end = j
            break
    return "\n".join(lines[start:end]).strip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Print the changelog section of one released version.")
    parser.add_argument("version", help="released version, e.g. 0.17.0")
    parser.add_argument("changelog", help="path to the changelog file")
    parser.add_argument("--out", help="write the section here instead of stdout")
    args = parser.parse_args()

    text = Path(args.changelog).read_text(encoding="utf-8")
    section = extract(text, args.version)
    if section is None:
        print(f"no `## ` heading names {args.version} in {args.changelog}",
              file=sys.stderr)
        return 1
    if args.out:
        Path(args.out).write_text(section, encoding="utf-8", newline="")
    else:
        sys.stdout.write(section)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
