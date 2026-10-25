#!/usr/bin/env bash
# Build the signed release APK and copy it to dist/ClearTV-<version>.apk (version from the root VERSION file).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
[ -f signing/signing.properties ] || { echo "signing/signing.properties missing (see signing/README.md)" >&2; exit 1; }
# a new build number for each release build (versionCode = version * 1000 + build)
b=$(( $(tr -d '[:space:]' < BUILD 2>/dev/null || echo 0) + 1 )); echo "$b" > BUILD
tools/compile_filters.sh
tools/enginetest/run.sh --no-heap
tools/themetest/run.sh
tools/similartest/run.sh
./gradlew --no-daemon clean assembleRelease lintRelease
v="$(tr -d '[:space:]' < VERSION)"
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/ClearTV-$v.apk"
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs "dist/ClearTV-$v.apk" | head -3
ls -l "dist/ClearTV-$v.apk"
