#!/usr/bin/env bash
# Engine tests on the JVM:
#  1. EngineTest: correctness cases, open time and match speed of the compiled engine (assets/filters/engine.bin)
#  2. EquivalenceTest: the compiled engine (bundled and device-rebuilt forms) must answer exactly like the v0.1.0
#     engine (reference copy in ref/) on a corpus generated from filters-src/
#  3. HeapReport: retained heap of the v0.1.0 engine vs the compiled engine
# usage: run.sh [--no-heap]
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
assets="$root/app/src/main/assets/filters"
javac -d "$out" --release 8 -Xlint:-options "$root"/app/src/main/java/com/goomero/cleartv/engine/*.java \
  "$root"/tools/enginetest/ref/cleartvref/*.java "$root"/tools/enginetest/*.java
java -Xmx256m -cp "$out" EngineTest "$assets"
java -Xmx1g -cp "$out" EquivalenceTest "$root/filters-src" "$assets"
if [ "${1:-}" != "--no-heap" ]; then
  java -XX:+UseSerialGC -Xmx512m -cp "$out" HeapReport ref "$root/filters-src" "$assets"
  java -XX:+UseSerialGC -Xmx512m -cp "$out" HeapReport new "$root/filters-src" "$assets"
fi
