#!/usr/bin/env bash
# Compile filters-src/ (filter lists, Public Suffix List, DNS domain list) into the bundled engine blob
# app/src/main/assets/filters/engine.bin with the app's own compiler (engine/FilterCompiler.java).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" --release 8 -Xlint:-options "$root"/app/src/main/java/com/goomero/cleartv/engine/*.java "$root/tools/compiler/CompileFilters.java"
java -Xmx1g -cp "$out" CompileFilters "$root/filters-src" "$root/app/src/main/assets/filters" "$root/app/src/main/assets/filters/engine.bin"
