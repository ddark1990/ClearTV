#!/usr/bin/env bash
# "More like this" descriptor tests on the JVM (Descriptor and Similar, no Android). With args: the offline Eval.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
tv="$root/app/src/main/java/com/goomero/cleartv/tv"
javac -d "$out" --release 8 -Xlint:-options "$tv/Descriptor.java" "$tv/Similar.java" "$tv/Item.java" "$tv/Fresh.java" "$root"/tools/similartest/*.java
if [ $# -gt 0 ]; then java -Xmx256m ${EVAL_OPTS:-} -cp "$out" com.goomero.cleartv.tv.Eval "$@"; exit; fi
java -Xmx128m -cp "$out" com.goomero.cleartv.tv.DescriptorTest
java -Xmx128m -cp "$out" com.goomero.cleartv.tv.FreshTest
