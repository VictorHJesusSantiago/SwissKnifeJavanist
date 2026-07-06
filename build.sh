#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$ROOT/build/classes"
find "$ROOT/src/main/java" -name '*.java' > "$ROOT/build/sources.txt"
javac --release 21 -encoding UTF-8 -d "$ROOT/build/classes" @"$ROOT/build/sources.txt"
printf 'Main-Class: dev.swissknife.Main\nImplementation-Version: 2.0.0\n' > "$ROOT/build/MANIFEST.MF"
jar --create --file "$ROOT/build/swissknife.jar" --manifest "$ROOT/build/MANIFEST.MF" -C "$ROOT/build/classes" .
printf '%s\n' "Criado build/swissknife.jar"
