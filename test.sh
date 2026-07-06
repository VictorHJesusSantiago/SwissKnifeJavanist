#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$ROOT/build.sh"
mkdir -p "$ROOT/build/test-classes"
find "$ROOT/src/main/java" "$ROOT/src/test/java" -name '*.java' > "$ROOT/build/test-sources.txt"
javac --release 21 -encoding UTF-8 -d "$ROOT/build/test-classes" @"$ROOT/build/test-sources.txt"
java -ea -cp "$ROOT/build/test-classes" dev.swissknife.AllTests
