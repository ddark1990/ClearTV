#!/usr/bin/env bash
# Refresh the filter lists in filters-src/ and rebuild the bundled engine blob app/src/main/assets/filters/engine.bin.
# The app also updates its lists on the device (Settings > Update filter lists now); this only changes the snapshot
# a fresh install starts from. usage: tools/refresh_bundled_filters.sh [path/to/domain-list.txt]
#   With a domain list (one hostname per line), it replaces filters-src/ad-domains.txt.gz first.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dst="$root/filters-src"
fetch() { # name url min_lines
  local tmp; tmp="$(mktemp)"
  curl -fsSL --max-time 120 -o "$tmp" "$2"
  local n; n=$(wc -l < "$tmp")
  if [ "$n" -lt "$3" ]; then echo "REJECT $1: $n lines" >&2; rm -f "$tmp"; return 1; fi
  mv "$tmp" "$dst/$1"; echo "ok $1 ($n lines)"
}
fetch easylist.txt https://easylist.to/easylist/easylist.txt 20000
fetch easyprivacy.txt https://easylist.to/easylist/easyprivacy.txt 20000
fetch ubo-filters.txt https://ublockorigin.github.io/uAssets/filters/filters.min.txt 5000
fetch ubo-privacy.txt https://ublockorigin.github.io/uAssets/filters/privacy.min.txt 300
fetch ubo-badware.txt https://ublockorigin.github.io/uAssets/filters/badware.min.txt 500
fetch ubo-quick-fixes.txt https://ublockorigin.github.io/uAssets/filters/quick-fixes.min.txt 50
fetch ubo-unbreak.txt https://ublockorigin.github.io/uAssets/filters/unbreak.min.txt 100
fetch adguard-base.txt https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt 20000
fetch adguard-mobile.txt https://filters.adtidy.org/extension/ublock/filters/11.txt 1000
fetch peter-lowe.txt "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext" 1000
fetch public_suffix_list.dat https://publicsuffix.org/list/public_suffix_list.dat 5000
if [ $# -ge 1 ]; then
  [ -f "$1" ] || { echo "domain list $1 not found" >&2; exit 2; }
  gzip -9 -n -c "$1" > "$dst/ad-domains.txt.gz"; echo "ok ad-domains.txt.gz"
fi
"$root/tools/compile_filters.sh"
"$root/tools/enginetest/run.sh"
