# ClearTV

ClearTV is an ad-free launcher for free and paid video services on Fire TV and Android TV, with built-in ad and tracker blocking. Package: `com.goomero.cleartv`. Version: see `VERSION`.

## Features
- **Services only.** Home is a row of service cards with logos and brand colours: Prime Video, YouTube, Pluto TV and Tubi. There is no URL bar and no general browsing.
  - The list lives in `app/src/main/java/com/goomero/cleartv/Services.java`. A new service is one entry there plus its icon in `assets/icons/` and colour in `colors.json`.
- **Native screens for every service.** YouTube, Prime Video and Tubi open ClearTV's own browse screen (left rail, header for the focused title, artwork shelves) in the service's accent and logo; Pluto TV has its native guide. MENU: Reload, Sign in/out, ClearTV home.
- **Sign-in for every service**, from each screen's account item or Settings › Accounts: YouTube and Tubi by TV code, Prime Video and Pluto TV on their own sign-in page inside ClearTV. Sessions persist; each has sign-out.
- **Never hands off to another app.** Every service plays inside ClearTV; `intent:`/app-scheme and off-domain navigations are refused.
- **Domain lock.** A service can only load its own domains. There are no popup windows; links that open a new window load in place.
- **Pluto TV is native.** No website: a guide grid, a preview player and full screen, played from Pluto's own stitched HLS streams with Media3 ExoPlayer. Ads that Pluto stitches into the stream stay in.
- **YouTube** shelves come from YouTube's TV API (ad slots dropped); videos play in YouTube's embed player inside ClearTV (OK play/pause, LEFT/RIGHT seek).
- **Tubi** plays its clear HLS streams in ClearTV's own player.
- **Content is never blocked**: `assets/filters/app-rules.txt` allowlists the services' content endpoints, thumbnails and video streams. Only ad requests are blocked.
- **Prime Video** plays in primevideo.com's own player inside ClearTV with Widevine (hardware-secure L1 where the device supports it); OK pauses, LEFT/RIGHT seek, BACK returns to the browse screen.
- **Remote modes, per service:**
  - **Focus** (default): the D-pad moves a silver highlight between links, buttons and cards.
  - **Cursor**: a pointer.
  - **Keys**: the D-pad goes straight to the page.
- **Design:** graphite and silver, with the Oxanium font.
- Fullscreen HTML5 video, screen kept on while playing.

## Remote controls
| Where | Key | Action |
| --- | --- | --- |
| Home | D-pad / OK | Move between cards / open the service |
| Home | MENU | Card options (on a card), Settings (elsewhere) |
| Home | BACK | Exit (asks first) |
| Service screen | D-pad / OK | Rail and shelves / open or play |
| Service screen | MENU | Reload, Sign in/out, ClearTV home |
| Service screen | BACK | Back to the previous page (channel, series, search), then home |
| Player | OK / Play-Pause | Play or pause |
| Player | LEFT / RIGHT (hold for 30 s), Rewind / Fast-forward | Seek 10 s / 30 s |
| Pluto guide | D-pad | Move through channels and time; FF/RW or CH± jump a page |
| Pluto guide | OK | Tune the channel; OK on the playing channel goes full screen |
| Pluto full screen | UP / DOWN | Previous / next channel |
| Pluto full screen | OK or BACK | Back to the guide |
| Pluto | MENU | Full screen / guide, captions, audio track, reload guide, ClearTV home |

## How blocking works
Everything runs inside the app. There is no VPN and no proxy.
1. **Network requests**
   - Every subresource the WebView loads goes through `shouldInterceptRequest`, then the filter engine, which is a memory-mapped blob (`engine.bin`, CTVE v2) read in place.
   - **Network filters** come from EasyList, EasyPrivacy, the uBlock Origin lists and Peter Lowe, parsed in ABP/uBO syntax:
     - Parsing supports `||`, `|`, `^`, `*`, regex, `@@`, `$important`, `$badfilter`, party, type, `domain=`, `to=`, `denyallow=`, `method=`, `redirect=` and `redirect-rule=`.
     - Filters are indexed by their rarest token, so matching costs about 8 µs per request on a desktop JVM.
     - Rules with options the engine can't honor (`csp`, `removeparam`, `replace`, …) are dropped, not applied loosely.
   - The **domain set** ("strong blocking") is about 700k ad/tracker hosts, stored as sorted 64-bit hashes. A host or any of its parent domains is checked with a binary search. The allowlist exempts player SDKs like Google IMA.
   - **What happens to a blocked request**:
     - It gets an empty 403 response.
     - When a rule asks for a redirect, it gets a harmless stand-in instead (`noopjs`, empty VAST/VMAP, 1x1 gif, Google Analytics / GTM / adsbygoogle stubs, …) so the page keeps working.
     - A blocked top-level navigation shows a block page with a "Continue to site" option.
2. **Cosmetic and scriptlet filtering**
   - **Injection**: `assets/js/content.js` is injected into every frame at document start with `WebViewCompat.addDocumentStartJavaScript`. On older WebViews it is injected into the main frame from the page callbacks instead.
   - **Element hiding**: the script asks the app over a JS bridge for element-hiding CSS (specific, plus generic selectors for the classes and ids actually present) and for the site's uBO scriptlets (`set-constant`, `abort-on-property-read`, `json-prune`, `no-setTimeout-if`, and others).
3. **Per service**: the service menu turns blocking off for that service. Settings has global switches for blocking, strong blocking, element hiding and scriptlets.
4. **Updates**
   - Settings › Update filter lists downloads the network lists plus the oisd big / HaGeZi Pro++ / StevenBlack domain lists. It also runs automatically every 4 days.
   - Each list must look like a filter list and pass a minimum size, or it is ignored.
   - The engine is rebuilt in the background and swapped in. "Go back to the bundled filter lists" undoes updates.

## Limits
- **Live TV ads** (Pluto TV live channels) are stitched into the broadcast stream, so they stay. On-demand content has no ad breaks: YouTube ad placements are removed before the player reads them, and Tubi's client-side ad breaks are never requested. Prime Video's stitched breaks would be fast-forwarded muted at 16x while its ad timer shows. Settings > Ad blocking shows what was blocked for each service.
- YouTube changes its ad delivery and anti-adblock checks often. Results depend on the current uBO filters (update the lists).
- **Pluto TV** uses Pluto's unofficial web API (boot.pluto.tv). It can change without notice.
- **Prime Video start time** is about 25 s on a Fire TV Stick. Almost all of it is Amazon's web player (7 MB of SDK script running on the stick's CPU), not ad blocking: it is the same with blocking off. Tubi starts in about 2.6 s, Pluto TV in 3.8 s, YouTube in 5 to 6 s. Logcat `CTV.Start` shows where each start's time goes.
- **Prime Video**: titles outside "Free with ads" need a Prime sign-in. Prime live stations are not listed.
- **YouTube** videos use the embed player: uploaders who disable embedding get a message instead of playback, and the embed player does not use your account (history, Premium).
- **Pluto TV sign-in** uses a form (email and password, typed on the phone page) because Pluto has no code sign-in; it posts to Pluto's older account API (`api.pluto.tv/v1/auth/local`), which may change.
- **Captions** on Prime Video come from the subtitle files Amazon lists for the title; titles without them show no CC chip. Caption styling (position, colours) from the files is not used.
- **Prime Video code sign-in and profiles** were built from Amazon's device-pairing and profile pages as used by other open-source players.
- **Older WebViews** without document-start scripts get cosmetic filters and scriptlets in the main frame only.
- No downloads; third-party cookies are off. Procedural cosmetic filters, HTML filtering and header/CSP rules are not supported.

## Build
Requirements: JDK 17+ (built with OpenJDK 21), Android SDK platform 34 and build-tools 34.0.0, and the Gradle wrapper (8.7, AGP 8.5.2). Set `local.properties` `sdk.dir`.

```
tools/build_release.sh          # engine tests, clean assembleRelease, lintRelease, copy to dist/ClearTV-<version>.apk
tools/enginetest/run.sh         # engine unit + performance test on the JVM
python3 tools/jstest/make_test.py   # content.js test in headless Chrome
tools/refresh_bundled_filters.sh    # re-download filters-src/ lists and rebuild engine.bin
```
Signing reads `signing/signing.properties`. Keep `signing/`, because updates must use the same key (see `signing/README.md`).

## Service settings
Service endpoints, client versions, keys, paths and JSON field names live in `app/src/main/assets/config.json`, which is built into the app and read by `tv/Config`. ClearTV never downloads settings from a server. When a service changes something, edit `config.json`, raise its `version`, and ship a new app version; installs pick it up through Settings > Updates (GitHub Releases). On start the app deletes any settings file an older version downloaded, so only the bundled copy is used.

## Health check
The app checks each service by itself, in the background and at most every 12 h. Results are shown on the home tiles and in Settings > Services status. For a manual check from a developer machine:
```
tools/healthcheck [youtube tubi prime pluto]   # uses the bundled config; JSON report, exit 1 when something failed
```

## Install on a Fire TV
1. On the Fire TV, turn on Settings › My Fire TV › Developer options › ADB debugging and apps from unknown sources. Note the IP under Settings › My Fire TV › About › Network.
2. From a computer:
   - `adb connect <tv-ip>:5555`
   - `adb install -r dist/ClearTV-<version>.apk`
3. Open **ClearTV** from Apps.
4. Pick a service with the D-pad and OK. MENU gives the service menu, and BACK returns home.
