#!/usr/bin/env bash
# edt-bridge - no-Maven build (macOS / Linux).
# Compiles + packages the OSGi bundle jar using a local JDK and the local 1C:EDT p2 bundle pool.
# No network, no install. (For the standard build use Maven + Tycho: pom.xml.)
# It does NOT install the bundle into EDT - copy the built jar to <EDT>/dropins/ and restart EDT.
#
# Defaults assume a typical install; override --pool / --jdk-home if yours differ.
#
#   ./build-nomaven.sh
#   ./build-nomaven.sh --pool "/path/to/plugins" --jdk-home "/path/to/jdk-17"
set -euo pipefail

POOL=""
JDK_HOME="${JAVA_HOME:-}"
DIST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --pool)     POOL="${2:?--pool needs a value}"; shift 2 ;;
    --jdk-home) JDK_HOME="${2:?--jdk-home needs a value}"; shift 2 ;;
    --dist)     DIST=1; shift ;;   # also place the jar into dist/ (the release asset)
    -h|--help)  grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

root="$(cd "$(dirname "$0")" && pwd)"
bundle="$root/io.github.keyfire.edtbridge"
src="$bundle/src"
out="$root/build"
bin="$out/bin"

# Resolve the JDK (need 17+). On macOS java_home can pick a matching JDK.
if [ -z "$JDK_HOME" ] && [ -x /usr/libexec/java_home ]; then
  JDK_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
[ -n "$JDK_HOME" ] || { echo "Set --jdk-home <path> or the JAVA_HOME env var to a JDK 17+ home." >&2; exit 1; }
JAVAC="$JDK_HOME/bin/javac"
JAREXE="$JDK_HOME/bin/jar"
[ -x "$JAVAC" ] || { echo "javac not found: $JAVAC (need a JDK 17+)" >&2; exit 1; }

# Locate the EDT bundle pool - it must hold both the Eclipse base and the com._1c.g5.* bundles.
# Candidates: each installed 1C:EDT component's self-contained pool (older RING components) plus
# the shared ~/.p2/pool (newer installs provision there). Pick whichever holds the newest
# com._1c.g5.v8.dt.core, so the default build targets the latest installed EDT.
if [ -z "$POOL" ]; then
  best_core=""
  # read line-by-line: the .app directory name contains spaces.
  while IFS= read -r cand; do
    [ -d "$cand" ] || continue
    core=""
    for f in "$cand"/com._1c.g5.v8.dt.core_[0-9]*.jar; do
      [ -e "$f" ] || continue
      case "$f" in *.source_*.jar) continue ;; esac
      core="$(basename "$f")"   # glob is sorted; last = newest within this pool
    done
    [ -n "$core" ] || continue
    if [ -z "$best_core" ] || [[ "$core" > "$best_core" ]]; then best_core="$core"; POOL="$cand"; fi
  done < <(
    ls -d /Applications/1C/1CE/components/1c-edt-[0-9]*/*.app/Contents/Eclipse/plugins 2>/dev/null
    echo "$HOME/.p2/pool/plugins")
fi
[ -n "$POOL" ] && [ -d "$POOL" ] || { echo "EDT bundle pool not found: '${POOL:-<empty>}' (pass --pool <.../Contents/Eclipse/plugins>)" >&2; exit 1; }

# SWT fragment differs per OS (the host org.eclipse.swt is a stub; compile vs the platform fragment).
case "$(uname -s)" in
  Darwin) swt_glob='org.eclipse.swt.cocoa.macosx.*_*.jar' ;;
  Linux)  swt_glob='org.eclipse.swt.gtk.linux.*_*.jar' ;;
  *)      swt_glob='org.eclipse.swt.*_*.jar' ;;
esac

# Newest pool jar named "<id>_<digit>...": a digit right after "<id>_" excludes version-specific
# siblings (e.g. com._1c.g5.v8.dt.platform_v8.3.27, which would shadow the real platform_11.x).
resolve_jar() {
  local id="$1" f b best=""
  for f in "$POOL/${id}_"*.jar; do
    [ -e "$f" ] || continue
    b="$(basename "$f")"
    case "$b" in "${id}_"[0-9]*) best="$f" ;; esac
  done
  printf '%s' "$best"
}
# Newest pool jar matching a glob, skipping ".source" siblings (used for the SWT fragment).
resolve_glob() {
  local pat="$1" f best=""
  for f in "$POOL/"$pat; do
    [ -e "$f" ] || continue
    case "$f" in *.source_*.jar) continue ;; esac
    best="$f"
  done
  printf '%s' "$best"
}

need=(
  org.eclipse.osgi
  org.eclipse.equinox.common
  org.eclipse.core.resources
  org.eclipse.core.runtime
  org.eclipse.core.jobs
  org.eclipse.ui.workbench
  org.eclipse.ui
  com.google.gson
  org.eclipse.emf.ecore
  org.eclipse.emf.common
  org.eclipse.xtext
  org.eclipse.xtext.util
  com.google.inject
  com.google.guava
  com._1c.g5.wiring
  com._1c.g5.v8.dt.core
  com._1c.g5.v8.dt.metadata
  com._1c.g5.v8.dt.mcore
  com._1c.g5.v8.dt.bsl.model
  com._1c.g5.v8.dt.bsl
  com._1c.g5.v8.dt.form.model
  com._1c.g5.v8.dt.form
  com._1c.g5.v8.dt.form.layout
  com._1c.g5.v8.dt.form.layout.model
  com._1c.g5.v8.dt.form.presentation
  com._1c.g5.v8.dt.platform
  com._1c.g5.v8.dt.platform.services.core
  com._1c.g5.v8.dt.platform.services.model
  com._1c.g5.designer.ssh.client
  com._1c.g5.v8.dt.refactoring.core
  com._1c.g5.v8.dt.md.refactoring
  com._1c.g5.v8.bm.core
  com._1c.g5.v8.bm.integration
)

echo "Pool: $POOL"
echo "Classpath bundles:"
cp=()
for n in "${need[@]}"; do
  j="$(resolve_jar "$n")"
  if [ -n "$j" ]; then cp+=("$j"); echo "  + $(basename "$j")"; else echo "  ! MISSING: $n"; fi
done
swt="$(resolve_glob "$swt_glob")"
if [ -n "$swt" ]; then cp+=("$swt"); echo "  + $(basename "$swt")"; else echo "  ! MISSING: SWT fragment ($swt_glob)"; fi
cpStr="$(IFS=:; printf '%s' "${cp[*]}")"

# Clean + compile.
rm -rf "$out"
mkdir -p "$bin"
sources=()
while IFS= read -r f; do sources+=("$f"); done < <(find "$src" -name '*.java')
echo "Compiling ${#sources[@]} sources with --release 17 ..."
"$JAVAC" --release 17 -encoding UTF-8 -cp "$cpStr" -d "$bin" "${sources[@]}"
echo "OK: compiled."

# Package the bundle jar (concrete version instead of .qualifier).
cp "$bundle/plugin.xml" "$bin/"
# The DS component descriptor (Service-Component in the manifest) must be inside the jar, or the
# lazy bundle never activates headless and the MCP server never starts (mirrors build.properties).
cp -R "$bundle/OSGI-INF" "$bin/"
ts="$(date +%Y%m%d%H%M)"
mf="$out/MANIFEST.MF"
# Base version comes from the manifest's Bundle-Version (X.Y.Z.qualifier), so a version bump
# there flows into the jar name; .qualifier is replaced with the build timestamp.
baseVer="$(sed -n 's/^Bundle-Version:[[:space:]]*\([0-9]*\.[0-9]*\.[0-9]*\)\.qualifier.*/\1/p' "$bundle/META-INF/MANIFEST.MF")"
baseVer="${baseVer:-0.0.1}"
sed "s/${baseVer}\.qualifier/${baseVer}.$ts/" "$bundle/META-INF/MANIFEST.MF" > "$mf"
jarPath="$out/io.github.keyfire.edtbridge_${baseVer}.$ts.jar"
"$JAREXE" cfm "$jarPath" "$mf" -C "$bin" .
echo "BUILT: $jarPath"
if [ "$DIST" = 1 ]; then
  # dist/ holds exactly one jar - the release asset the GitHub workflow publishes on a tag.
  mkdir -p "$root/dist"
  rm -f "$root/dist/io.github.keyfire.edtbridge_"*.jar
  cp "$jarPath" "$root/dist/"
  echo "DIST: $root/dist/$(basename "$jarPath") - commit it, tag vX.Y.Z, push the tag to release."
fi
echo "Install: copy to <EDT>/dropins/ and restart EDT, then curl http://127.0.0.1:8770/mcp"
