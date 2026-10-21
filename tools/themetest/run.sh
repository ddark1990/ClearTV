#!/usr/bin/env bash
# Theme format tests on the JVM: parser, strict validation, built-in themes, the docs example and the JSON Schema.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" --release 8 -Xlint:-options "$root"/app/src/main/java/com/goomero/cleartv/theme/Theme.java "$root"/app/src/main/java/com/goomero/cleartv/theme/ThemeApi.java "$root"/app/src/main/java/com/goomero/cleartv/theme/Layout.java "$root"/app/src/main/java/com/goomero/cleartv/theme/StoreIndex.java "$root"/tools/themetest/*.java
java -Xmx128m -cp "$out" ThemeTest "$root"
java -Xmx128m -cp "$out" ApiTest "$root"
java -Xmx128m -cp "$out" StoreTest "$root"
python3 "$root"/tools/theme_index.py check
