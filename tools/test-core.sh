#!/usr/bin/env bash
# Compiles the platform-free core plus the desktop host, then runs the core unit tests.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/desktop
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn --release 11 -d "$OUT" \
  android/app/src/main/java/com/saath/core/*.java \
  tools/desktop/src/com/saath/desktop/*.java
java -cp "$OUT" com.saath.desktop.CoreTests
