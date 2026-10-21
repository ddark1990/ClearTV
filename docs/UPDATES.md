# Updates and the theme store

ClearTV can update itself from the GitHub Releases of the public repo and download themes from the public repo's
`themes/` folder. Both sources are fixed when the APK is built and shown in **Settings > Updates**.

| Build property | Default | Used for |
| --- | --- | --- |
| `cleartv.updateUrl` | `https://api.github.com/repos/ddark1990/ClearTV/releases/latest` | the latest release (GitHub API JSON) |
| `cleartv.storeUrl` | `https://raw.githubusercontent.com/ddark1990/ClearTV/main/themes/index.json` | the theme store index |
| `cleartv.allowLocalHttp` | `false` | test builds only: also accept `http://127.0.0.1` (a mock server through `adb reverse`) |

Set them with `-P` on the Gradle command line or in `local.properties`. Forks must point both URLs at their own repo
(see README "Credits & forks").

## How an update is checked and installed

1. At start, once a day (Settings > Updates > Check for updates automatically, on by default), or with
   **Check for updates now**, ClearTV reads the latest release. Drafts and pre-releases are ignored.
2. The release counts when its tag (`v0.1.19` or `0.1.19`) is newer than the installed version, it has exactly one
   `.apk` file, and it has `<apk name>.sha256` (or a `SHA256SUMS` file listing the APK).
3. ClearTV downloads over https into its own cache and refuses the update, deleting the file, unless all of these hold:
   - the size equals the size GitHub lists for the asset;
   - the SHA-256 equals the published one;
   - the APK is the same app (package `com.goomero.cleartv`) with a higher version code;
   - its signing certificate is the same as the installed app's.
4. The first time, ClearTV asks you to allow it to install apps and opens that settings screen (on Fire TV: My Fire TV >
   Developer options > Install unknown apps > ClearTV). Then the system installer opens and asks you before it
   installs. Your settings and sign-ins stay.

A release signed with another key can never be installed over yours, by ClearTV or by Android.

## Publishing a release (maintainer only, by hand)

Releases are never made by CI. To publish one:

1. Bump `VERSION`, build with your release key: `tools/build_release.sh`, which writes `dist/ClearTV-<version>.apk`.
2. `tools/release_assets.sh` writes `dist/ClearTV-<version>.apk.sha256` and prints the signing certificate's SHA-256.
3. On the public repo, create a release with tag `v<version>`, attach both files, and publish it (not as a draft or
   pre-release).

Keep the keystore and its passwords out of every repo (`signing/` is git-ignored; see `signing/README.md`). Losing the
key means existing installs cannot update; leaking it means anyone can make an update your TVs accept.

## The theme store

The public repo's `themes/` folder holds the store: theme files, `previews/` and `index.json`:

```json
{"format": "cleartv-theme-index", "version": 1, "themes": [
  {"id": "candy", "name": "Candy", "author": "your-handle", "version": 1,
   "description": "One sentence, at most 160 characters.",
   "file": "candy.json", "size": 5755, "sha256": "<64 hex>",
   "preview": "previews/candy.png", "previewSize": 3931, "previewSha256": "<64 hex>"}]}
```

- `file` and `preview` are paths next to `index.json` (no `..`). Themes are at most 64 KB, previews 256 KB
  (png, jpg or webp, 960x540 recommended), at most 100 themes, index at most 64 KB.
- `version` is the theme's own revision: raise it when you change the file, and installed copies show **Update**.
- An entry whose id is a built-in (`default`, `topshelf`, `billboard`) replaces the bundled file when installed; Go
  back to the built-in undoes it.

In the app: **Themes** (or Settings > Theme) > **Get themes**. The list shows each theme's preview, author and
whether it is installed or has an update. OK offers Install, Update, Use and Delete. Every download must match the
index's size and SHA-256 and pass the same strict validator as every other import; themes are data only, nothing in
them can run. Installed store themes also appear under Your themes in the picker.

Built in: Stargazer, Top Shelf and Billboard. Daylight, OLED High Contrast and Candy are in the store.

### Submitting a theme

1. Make the theme (docs/THEMES.md) and check it on a TV with the phone page or `api/validate`: no errors, no warnings.
2. In a fork of the public repo, add `themes/<id>.json` and an index entry (id, name, your handle, version 1,
   description, file, preview). Do not put personal information in the theme or preview.
3. `tools/theme_index.py previews` draws `themes/previews/<id>.png` from the theme's colours if you have none (a
   schematic, so no screenshots of your watch history end up in the repo); or add your own 960x540 image.
4. `tools/theme_index.py write` fills in the sizes and hashes; `tools/themetest/run.sh` must pass (it validates every
   store theme, the index and the hashes).
5. Open a pull request. After review and merge, every ClearTV sees it in Get themes.
