# Building and updating ClearTV yourself

Signed releases are published by the owner, by hand, on [GitHub Releases](https://github.com/ddark1990/ClearTV/releases) (no automatic or CI releases; see [UPDATES.md](UPDATES.md#publishing-a-release-maintainer-only-by-hand)). This page is for building ClearTV from source yourself, for example for a fork.

## Build
1. Install **JDK 17** and the **Android SDK** (the easiest way is Android Studio).
2. Build:
   ```
   git clone https://github.com/ddark1990/ClearTV
   cd ClearTV
   ./gradlew assembleRelease
   ```
3. The app is `app/build/outputs/apk/release/app-release.apk`.

## Signing (optional)
Without a key, the build is signed with your Android debug key, which is fine for your own TV. To use your own release key, set it in one of these places:
- the environment: `CLEARTV_KEYSTORE`, `CLEARTV_KEYSTORE_PASSWORD`, `CLEARTV_KEY_ALIAS`, `CLEARTV_KEY_PASSWORD`
- `local.properties` (never committed): `cleartv.keystore`, `cleartv.keystorePassword`, `cleartv.keyAlias`, `cleartv.keyPassword`
- `signing/signing.properties` (git-ignored)

Always build updates with the **same key**. Android only installs an update over the old app when the signatures match.

## Install or update
```
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```
`-r` updates in place and keeps your sign-ins and settings. Don't uninstall first.

## Updating the code
`git pull`, then build and install again.
