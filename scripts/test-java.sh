#!/usr/bin/env bash
# Unit tests for the EDT-independent core of the plugin (io.github.keyfire.edtbridge.core).
#
# The bundle as a whole cannot be compiled without the proprietary 1C:EDT SDK bundles, which is why
# there is no CI build for it. The core package is deliberately free of EDT and Eclipse types, so it
# compiles - and is tested - anywhere. Note that this script puts NOTHING but the JUnit jar on the
# classpath: if a dependency on the SDK ever creeps into core, this fails to compile, which is the
# point. Everything that needs the live model stays in the gateways and is verified against a real EDT.
#
#   scripts/test-java.sh            # download JUnit if needed, compile, run
#   JUNIT_VERSION=1.11.4 scripts/test-java.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

# Windows javac wants ';' between classpath entries and cannot read MSYS paths, so keep everything
# relative to the repository root and only switch the separator.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac

junit_version="${JUNIT_VERSION:-1.11.4}"
junit="build/tools/junit-platform-console-standalone-${junit_version}.jar"
core_src="io.github.keyfire.edtbridge/src/io/github/keyfire/edtbridge/core"
test_src="tests/java/io/github/keyfire/edtbridge/core"
classes="build/java-test/classes"
test_classes="build/java-test/test-classes"

mkdir -p "$(dirname "$junit")" "$classes" "$test_classes"
if [ ! -f "$junit" ]; then
  echo "downloading junit-platform-console-standalone ${junit_version} ..."
  curl -fsSL -o "$junit" \
    "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${junit_version}/junit-platform-console-standalone-${junit_version}.jar"
fi

echo "compiling core (no EDT bundles on the classpath) ..."
javac -encoding UTF-8 --release 17 -d "$classes" "$core_src"/*.java

echo "compiling tests ..."
javac -encoding UTF-8 --release 17 -cp "${classes}${SEP}${junit}" -d "$test_classes" "$test_src"/*.java

echo "running tests ..."
java -jar "$junit" execute \
  --class-path "${classes}${SEP}${test_classes}" \
  --scan-class-path \
  --details=summary \
  --disable-banner
