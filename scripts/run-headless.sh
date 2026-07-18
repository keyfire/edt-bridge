#!/usr/bin/env bash
# Run 1C:EDT headless (no GUI window) so the edt-bridge MCP server serves the live model.
# Uses the EDT CLI's INTERACTIVE mode + a keepalive on stdin; the bundle's DS component
# (OSGI-INF/edtbridge-mcp.xml) starts the server on activation, so no UI workbench is needed.
#
#   Start: ./run-headless.sh --workspace <ws> [--edt <.../Contents/Eclipse>]
#   Stop : pkill -f 'edt.bridge.headless=1'; pkill -f 'sleep 2147483647'
#
# Why interactive + keepalive: bare "1cedtcli -data ws" idles without starting the runtime;
# piping a command engages it, and the interactive shell keeps the OSGi framework (and the
# DS-started server) alive. `sleep` keeps stdin open so the shell does not EOF-exit.
#
# -EdtDir is auto-detected (newest installed component) if omitted.
set -euo pipefail

WORKSPACE=""
EDT=""
PORT=8770
WAIT=360
while [ $# -gt 0 ]; do
  case "$1" in
    --workspace) WORKSPACE="${2:?--workspace needs a value}"; shift 2 ;;
    --edt)       EDT="${2:?--edt needs a value}"; shift 2 ;;
    --port)      PORT="${2:?--port needs a value}"; shift 2 ;;
    --wait)      WAIT="${2:?--wait needs a value}"; shift 2 ;;
    -h|--help)   grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$WORKSPACE" ] || { echo "--workspace <dir> is required." >&2; exit 2; }

# Auto-detect the 1C:EDT installation (its Contents/Eclipse dir, where 1cedtcli lives). EDT lands
# either as a RING component under /Applications, or a provisioned install under the start app's
# installations dir. Keep only ones with an executable 1cedtcli; newest (lexical) wins.
if [ -z "$EDT" ]; then
  while IFS= read -r cand; do
    [ -x "$cand/1cedtcli" ] && EDT="$cand"
  done < <(
    { ls -d /Applications/1C/1CE/components/1c-edt-[0-9]*/*.app/Contents/Eclipse 2>/dev/null
      ls -d "$HOME/Library/Application Support/1C/1cedtstart/installations/"*/1cedt.app/Contents/Eclipse 2>/dev/null
    } | sort)
  [ -n "$EDT" ] || { echo "Could not auto-detect a provisioned 1C:EDT (need a 1cedtcli) - pass --edt <.../Contents/Eclipse>." >&2; exit 2; }
fi
CLI="$EDT/1cedtcli"
DROPINS="$EDT/dropins"
[ -x "$CLI" ] || { echo "1cedtcli not found/executable: $CLI" >&2; exit 2; }

# 1) SAFETY: never touch a GUI 1C:EDT. If an EDT runtime is up that is NOT our headless one
#    (no edt.bridge.headless marker), abort - the user may be working in it (and it holds the
#    workspace lock). Only ever manage our own headless instance.
if ps -Ao command= | grep -i 'org.eclipse.equinox.launcher' | grep -i '1cedt' | grep -iqv 'edt.bridge.headless=1'; then
  echo "An EDT instance appears to be running (GUI or another session) - refusing to touch it." >&2
  echo "Close it first, or run headless on a separate --workspace/--port." >&2
  exit 2
fi
# Stop our prior headless CLI (marked JVM) and its keepalive, so repeated launches do not leave
# orphans that keep the old jar locked.
pkill -f 'edt.bridge.headless=1' 2>/dev/null || true
pkill -f 'sleep 2147483647'      2>/dev/null || true
sleep 3
lock="$WORKSPACE/.metadata/.lock"
[ -f "$lock" ] && rm -f "$lock" 2>/dev/null || true
# Keep only the newest edt-bridge jar in dropins: two singletons of the same bundle make Equinox
# resolve an arbitrary (often older) one. Purge the rest so the freshly built jar is the one loaded.
mkdir -p "$DROPINS"
ls -t "$DROPINS"/io.github.keyfire.edtbridge_*.jar 2>/dev/null | tail -n +2 | while IFS= read -r f; do rm -f "$f"; done

# 2) launch headless in the background. The marker -D lets us find/stop only our own instance;
#    the write-tool auth token (and a non-default port) are propagated through -vmargs, which the
#    native EDT launcher passes straight to the JVM (env is not inherited reliably).
vmargs=(-Dedt.bridge.headless=1)
[ -n "${EDT_BRIDGE_TOKEN:-}" ] && vmargs+=("-Dedt.bridge.token=${EDT_BRIDGE_TOKEN}")
[ "$PORT" != 8770 ] && vmargs+=("-Dedt.bridge.port=${PORT}")
nohup sh -c '(echo version; exec sleep 2147483647) | "$1" -data "$2" -nl en_US -vmargs '"${vmargs[*]}" \
  _ "$CLI" "$WORKSPACE" >/dev/null 2>&1 &
echo "Launched headless EDT (1cedtcli). Waiting up to ${WAIT}s for the server + model..."

# 3) poll readiness: server up AND at least one project open (model loadable).
deadline=$(( $(date +%s) + WAIT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  s="$(curl -s --max-time 3 "http://127.0.0.1:${PORT}/status" 2>/dev/null || true)"
  case "$s" in *'"openProjects":["'*) echo "READY: $s"; exit 0 ;; esac
  sleep 5
done
alive=no; pgrep -f 'edt.bridge.headless=1' >/dev/null 2>&1 && alive=yes
echo "NOT READY after ${WAIT}s (headless alive: $alive)"
exit 1
