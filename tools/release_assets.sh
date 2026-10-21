#!/usr/bin/env bash
# After tools/build_release.sh: write dist/ClearTV-<version>.apk.sha256 for the GitHub release (docs/UPDATES.md)
# and print the APK's signing certificate. Publishes nothing.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/dist"
v="$(tr -d '[:space:]' < "$root/VERSION")"
apk="ClearTV-$v.apk"
[ -f "$apk" ] || { echo "dist/$apk missing: run tools/build_release.sh first" >&2; exit 1; }
sha256sum "$apk" > "$apk.sha256"
cat "$apk.sha256"
"${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0/apksigner" verify --print-certs "$apk" | grep -i "SHA-256" | head -1
echo "Attach dist/$apk and dist/$apk.sha256 to a release tagged v$v."
