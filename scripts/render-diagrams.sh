#!/usr/bin/env bash
# Render docs/*.svg into the PNGs the READMEs embed (GitHub does not render SVG from a README).
#
# Two flags are the whole point of having this script, and both have been forgotten before:
#
#   --default-background-color=00000000   the page background must be TRANSPARENT. A browser paints
#                                         white by default, and that white bakes into the corners of
#                                         the rounded frame - it shows up as white notches on GitHub.
#   --force-device-scale-factor=2         the images are 2x so they stay sharp on a HiDPI screen.
#
# The window size has to match the svg's own width/height, otherwise the shot is cropped or padded.
#
#   scripts/render-diagrams.sh              # render everything
#   BROWSER=/path/to/chrome scripts/render-diagrams.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

find_browser() {
  if [ -n "${BROWSER:-}" ]; then echo "$BROWSER"; return; fi
  for candidate in \
    "/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" \
    "/c/Program Files/Google/Chrome/Application/chrome.exe" \
    "$(command -v google-chrome || true)" \
    "$(command -v chromium || true)" \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  do
    [ -n "$candidate" ] && [ -x "$candidate" ] && { echo "$candidate"; return; }
  done
  echo "no Chrome or Edge found - set BROWSER=/path/to/chrome" >&2
  exit 1
}

browser="$(find_browser)"
# Windows shells rewrite arguments that look like POSIX paths; the switches below must survive intact.
export MSYS2_ARG_CONV_EXCL='*'

for svg in docs/*.svg; do
  png="${svg%.svg}.png"
  # The svg header carries the canvas size - render at exactly that, or the shot gets cropped.
  size="$(head -c 400 "$svg" | tr -d '\n' | sed -n 's/.*width="\([0-9]*\)"[^>]*height="\([0-9]*\)".*/\1,\2/p')"
  if [ -z "$size" ]; then
    echo "!! cannot read width/height from $svg - skipped" >&2
    continue
  fi
  abs_svg="$(cd "$(dirname "$svg")" && pwd)/$(basename "$svg")"
  abs_png="$(cd "$(dirname "$png")" && pwd)/$(basename "$png")"
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      abs_svg="$(cygpath -w "$abs_svg")"
      abs_png="$(cygpath -w "$abs_png")"
      url="file:///$(cygpath -m "$(cygpath -u "$abs_svg")")"
      ;;
    *) url="file://$abs_svg" ;;
  esac
  echo "  $svg -> $png ($size)"
  "$browser" --headless=new --disable-gpu --hide-scrollbars \
    --default-background-color=00000000 \
    --force-device-scale-factor=2 \
    --window-size="$size" \
    --screenshot="$abs_png" "$url" >/dev/null 2>&1
done

echo "done - check that every PNG kept an alpha channel (colour type 6), not a white background"
