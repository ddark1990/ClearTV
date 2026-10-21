# Glossary

One row per id. Add new ids at the bottom.

| Id | Meaning |
| --- | --- |
| VERSION | Root file holding MAJOR.MINOR.PATCH; the single source for `versionName`, and `versionCode` = MAJOR*10000 + MINOR*100 + PATCH |
| ClearTV | The app (package `com.goomero.cleartv`), shown on the launcher as "ClearTV Browser" in 0.1.0 and "ClearTV" from 0.1.1 |
| network filter | An ABP/uBO rule (`||host^`, `/path/`, `@@exception`, `$options`) matched against every subresource request in `shouldInterceptRequest` |
| domain set | Sorted array of 64-bit FNV-1a hashes of ad/tracker hostnames (a section of `assets/filters/engine.bin`, compiled by `tools/compile_filters.sh` from `filters-src/ad-domains.txt.gz`); a host matches if it or any parent domain (not the bare TLD) is in the set |
| strong blocking | The setting that turns the domain set on; the network filters stay on either way |
| allowlist | Hosts exempt from the domain set only (`assets/filters/allowlist.txt` plus `allowlist-app.txt`), so player SDKs such as IMA still load |
| cosmetic filter | An element-hiding rule (`##selector`, `example.com##selector`, `#@#` exception), injected as CSS by `content.js` |
| lowly generic | uBO's way of sending generic `.class` / `#id` selectors only for class and id tokens that actually appear in the page |
| scriptlet | A uBO `+js(...)` rule that runs a small script in the page (for example `set-constant`, `abort-on-property-read`, `json-prune`, `no-setTimeout-if`) |
| redirect resource | A harmless stand-in (`noopjs`, `noopvast-4.0`, a 1x1 gif, a googletagmanager stub, …) returned with status 200 instead of the blocked file, so pages that need the script don't break |
| block page | The local page shown when a main-frame navigation hits an ad/tracker host, with "Continue to site" (allowed for that session) and "Go back" |
| cursor mode | The D-pad moves an on-screen pointer and OK dispatches a touch tap |
| keys mode | D-pad and OK keys go straight to the page, for TV-designed sites |
| site version | The per-site user agent: Desktop (Windows Chrome), Mobile (Android Chrome) or TV (Tizen smart TV) |
| bridge | The `@JavascriptInterface` object exposed to `content.js` under a random per-launch name |
| fallback injection | `evaluateJavascript` of `content.js` from page callbacks when the WebView lacks `DOCUMENT_START_SCRIPT`; it reaches the main frame only and can run after page scripts |
| SSAI | Server-side ad insertion: ads stitched into the video stream itself, which no request blocker can remove |
| engine blob | `assets/filters/engine.bin` (format `CTVE` v2): the compiled filter engine, memory-mapped at startup instead of parsed into Java objects |
| HostSet | Sorted 64-bit host-hash array inside the engine blob, searched by binary search for a host and its parent domains |
| MultiMap | Engine-blob table mapping a key hash (token, host) to a run of rule records, read in place from the mapped file |
| focus mode | The default remote mode: the D-pad moves a highlighted focus between links, buttons and cards chosen spatially by `content.js`, and OK clicks the focused element |
| service | One entry in `Services.java` (Prime Video, YouTube, Pluto TV, Tubi): start URL, user agent, remote mode, allowed domains, official app, optional native viewer |
| domain lock | Main-frame navigations outside the open service's domains are refused |
| service menu | The panel MENU opens over a service: home, reload, back, start over, blocking toggle, remote mode, official app, settings |
| native Pluto viewer | `pluto.PlutoActivity`: Pluto TV played natively (boot.pluto.tv session, /v2 stitched HLS through Media3 ExoPlayer) instead of the website |
| guide grid | `pluto.GuideView`, the self-drawn channel-by-time program guide of the native Pluto viewer |
| UA client hints | `navigator.userAgentData` and `Sec-CH-UA-*` headers; set with `WebSettingsCompat.setUserAgentMetadata` so they match the user agent string |
| brand colour | A service's colour from `assets/icons/colors.json`, used for its card wash, stripe and the page-load bar |
| backdrop | The still star-field image behind home, drawn once at half resolution and freed when home is hidden |
| app rules | `assets/filters/app-rules.txt`: ClearTV's own filter rules, compiled after the lists; holds the content allowlist (exceptions for service content endpoints, thumbnails and video streams) |
| Cobalt agent | The user agent of YouTube's own TV runtime (Cobalt), used for the YouTube service so YouTube serves its full living-room interface |
| app link | A link that would open another app (`intent:`, `vnd.youtube:`, `market:`, …); ClearTV loads its page in the WebView when it belongs to the open service and refuses it otherwise |
| native screen | `tv/ServiceActivity`: ClearTV's own browse UI for a service (rail, header, shelves) in that service's accent, fed by a `tv/Source` |
| Source | `tv/Source` subclass per service (YouTube, Tubi, Prime Video) that loads shelves, search, pages and playback, and runs that service's sign-in |
| accent | A service's brand colour used for focus rings, rail selection, meta text and the player overlay (YouTube red, Prime blue, Tubi yellow/purple) |
| shelf | A titled horizontal row of 16:9 cards drawn by `tv/ShelfView` |
| embed player | YouTube's iframe player on a ClearTV page (`tv/WebActivity`, referrer `https://com.goomero.cleartv/`), used for YouTube videos |
| device code | Sign-in where the TV shows a short code that the user enters on a phone or computer (YouTube at google.com/device, Tubi at tubitv.com/activate) |
| web sign-in | Sign-in on the service's own page inside ClearTV (Prime Video, Pluto TV); cookies and storage keep the session |
| spacing tokens | `Ui.S_HALF/S1/S2/S3/S4/S6` (4/8/16/24/32/48 dp) and `Ui.LINE_H`; all margins and gaps come from them |
| protected media | WebView's `RESOURCE_PROTECTED_MEDIA_ID` permission (Widevine EME); granted only to the open service's own domains |
| QR sign-in card | `tv/SignInCard`: large QR code, short code and address for a device-code sign-in. It polls until approved and renews expired codes |
| Sign in to all | `tv/SignInAllActivity`: Settings flow through every service's sign-in, one after another |
| phone page | `PhoneLink`: tiny LAN web page (random key, same Wi-Fi only, auto-off) that gives a phone a D-pad, media keys and a keyboard for the TV |
| Continue watching | Home row of started titles: local progress (`tv/Library`) plus the service's own history when signed in |
| favorite | A show or Pluto TV channel pinned to home with a long press or MENU |
| Search all | `tv/AllSource`: one query across every service, results grouped by service |
| markers | A service's intro and end-credit times (Tubi `credit_cuepoints`), used for Skip intro and the next-episode countdown |
| service settings | `assets/config.json`, built into the app and read by `tv/Config`: endpoints, client versions, keys, paths and JSON field names for the adapters. Never downloaded; changes ship as a new app version |
| health check | `tv/HealthCheck`: in-app background probe of each service (at most every 12 h); failures show as a quiet notice on the tile and in Settings > Services status |
| page cache | `tv/PageCache`: the first section of the last service, saved for 12 h so it shows instantly while fresh data loads |
| block stats | `BlockStats`: per-service counts (today, total) of blocked requests and skipped ad breaks, with the last 5 hosts; shown in Settings > Ad blocking and logged as `CTV.Block` |
| ad guard | `tv/AdGuardJs`: document-start script for on-demand web players. It strips YouTube ad placements, skips any ad that still starts, and fast-forwards Prime ad breaks muted at 16x |
| start trace | `tv/StartTrace`: logcat `CTV.Start` lines for one playback start (Play press, each request and whether it was blocked, playlist, license and segment loads with bitrate and timing, and the first frame), for 90 s after Play |
| TV-safe margins | `Ui.SAFE_X` / `Ui.SAFE_Y` (48 x 27 dp, 5% of a 960 x 540 dp screen): the inset from the screen edges for every overlay. Overlays sit against the screen, never against the video picture |
| profile picker | `tv/ProfileCard`: native "Who's watching?" card for services with profiles (Prime Video, YouTube brand channels), with a PIN pad; the choice is saved per service (`profile_id`, `profile_ask`) |
| code pairing | signing in by entering a short code shown on the TV at the service's site (YouTube, Tubi, Prime Video's amazon.com/code); ClearTV polls until it is entered. Shown in the shared `SignInCard` |
| web captions | `tv/WebCaptions`: ClearTV's own caption line for the web players (YouTube's caption tracks, Prime Video's TTML subtitle files), with the CC chip in the controls |
| build number | repo-root `BUILD`: raised by every release build; the version code is version x 1000 + build |
| bare video | web players with every part of the page hidden except the `<video>` element (`BARE_VIDEO` in `tv/WebActivity`); ClearTV draws the controls, spinner and captions |
| caption file | a service's captions shipped as a separate SRT/VTT file (Tubi's `subtitles` list), loaded by `PlayerActivity` into ClearTV's own caption line instead of a text track in the stream |
| rendition ladder | the video renditions a stream offers (height, bitrate, codec, whether this device can decode it), logged as `CTV.Quality` when playback starts |
| row continuation | the token a service gives for a row's next tiles (`Item.Shelf.more`), loaded by `Source.moreRow` when focus nears the row's end. For YouTube it is sent with the answer's visitor id |
| page pagination | a page's next rows, loaded by `Source.more` when focus nears the last rows (Prime's getLandingPage, YouTube's section continuation). A long feed's next tiles continue its grid instead (`Item.Page.moreExtendsGrid`) |
| session exchange | Prime sign-in step: the device refresh token from `/auth/register` traded for primevideo.com cookies at `/ap/exchangetoken/cookies` |
| SignInError | a sign-in failure after the code was approved; `SignInCard` stops polling and shows it |
| LiveLists | per-service live Favorites and Recently watched channels, stored on the TV (`live_lists` preferences) |
| hover preview | a muted 480p preview of a focused on-demand tile after 1.5 s (`HoverPreview`), for services with a clear stream |
| Autoplay next | Settings switch (off by default); off, the Up next card waits for OK |
| theme | A ClearTV look and home layout in the JSON format of `docs/THEMES.md` (`format: cleartv-theme`, version 1); built-ins in `assets/themes/`, saved custom ones in the `themes` preferences |
| theme preview | A theme shown for 5-300 s (default 30) with a countdown banner; it reverts unless confirmed (Settings > Theme, the phone page or `api/confirm`) |
| Stargazer | The default and fallback theme (id `default`): the classic 0.1.16 home, formerly called Default |
| theme control API | JSON endpoints under the phone page (`/<token>/api/...`, `ThemeApi`) to read, validate, preview, confirm, revert, reset and export themes; documented in `docs/AGENTS-API.md` |
| video palette | Fixed `Ui.V_*` colours for overlays drawn on video (player controls, captions, Pluto info bar), unchanged by themes |
| hero row | `layout.servicesStyle: "hero"`: the home service tiles as a tall featured banner row |
| layout engine | `layout.engine` of a theme: `classic`, `apple` (tvOS-style Top Shelf, dock, rows; top tabs on service screens) or `rail` (sidebar, featured banner, grid); built by `HomeBase.create` and per engine in `ServiceActivity`, `ShelfView` and `PlutoActivity` |
| Top Shelf | The apple engine's full-bleed backdrop of the focused item across the top of the home and service screens |
| theme v2 / layout document | A theme with `"version": 2`: tokens plus `screens`, a data-only component tree for the home (`theme/Layout`) rendered by `ComposeHome` |
| retheme / reskin | Retheme: change the look with a theme file (data, update-compatible). Reskin: change the app's code, only for what the format cannot express |
| focused-service | Row/grid data binding: the Continue watching and Watchlist of the service focused on the home (a Top Shelf) |

| theme store / Get themes | The public repo's `themes/` folder (`index.json`, theme files, previews), browsed in Themes > Get themes; downloads checked against the index's size and SHA-256 and the theme validator |
| update source | `BuildConfig.UPDATE_URL`, the public repo's latest GitHub release; updates install only with the same signing certificate and a matching SHA-256 |
| store index | `themes/index.json` (`cleartv-theme-index` version 1): id, name, author, version, description, file, size, sha256, preview, previewSize, previewSha256 per theme |
