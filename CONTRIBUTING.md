# Contributing to ClearTV

Thanks for helping! A few simple rules:

1. **Open an issue first** for anything bigger than a small fix, so we can agree on the approach.
2. **Build:** JDK 17 and the Android SDK (compileSdk 34). `./gradlew assembleRelease` works on a fresh clone; without a release key the APK is signed with the debug key.
3. **Keep the project's principles:** block ads and trackers, never content; never bypass DRM; no flashy UI; overlays stay in the TV-safe edges; no parental controls; native screens instead of the services' own web UI.
4. **Never commit secrets:** keystores, passwords, tokens, cookies, IP addresses or screenshots showing accounts. `.gitignore` covers the usual files; check `git status` before you commit.
5. **One version bump per release** (`VERSION`, `CHANGELOG.md`). Explain user-visible changes in the changelog in plain language.
6. **Test on a real Fire TV or Android TV** when you change playback or remote navigation, and say what you tested in the pull request.
7. By contributing you agree your work is licensed under GPL-3.0 and that the original credit (NOTICE, Settings > About, README "Credits & forks") stays in place.
