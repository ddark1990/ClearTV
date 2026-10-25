# Changelog

What changed in each ClearTV version, newest first. ClearTV is made by [ddark1990](https://github.com/ddark1990) and is free software under the [GPL-3.0 license](LICENSE).

Download builds from [Releases](https://github.com/ddark1990/ClearTV/releases). Found a problem? Open an [issue](https://github.com/ddark1990/ClearTV/issues); for security problems see [SECURITY.md](SECURITY.md).

## 0.1.23 (2026-10-24)

- **More like this goes looking for new channels** (YouTube): the first row now shows one video from each of up to 30 different channels similar to the one you were watching, followed by a row of those channels themselves (OK opens the channel), a short row of a few more from the channel you were watching, and then more videos from the new channels. The first rows appear as soon as they are ready and fill in a moment later. Hidden channels stay out. Tubi and Prime Video now show one tile per title, so a show you are watching no longer fills the list with its own seasons, and Tubi's list reaches further than its own ten titles.
- **Movies like this** (YouTube): films and shows that YouTube suggests next to a video (its Free with ads and Buy or rent titles) now get their own row, **Movies like this**, after the channel rows and before the row from the channel you watched. The new-channels row and the channel tiles now hold only creators' videos and channels. OK on a movie opens its title page with Play movie and Trailer (or the buy/rent note), the same as movie tiles elsewhere. If YouTube suggests no movies for a video, the row is left out.
- **Channel pictures in More like this** (YouTube): every tile in the "Channels like …" row now shows the channel's picture. Before, some channels showed only their name on a grey tile, and waiting didn't help: the picture was never there to load.
- The README shows the new YouTube More like this rows (new channels, Movies like this) and the Up next card in its new top-left place, with new screenshots.
- **More like this:** hold OK on a title and pick **More like this** to open a row of similar titles, from the service itself: YouTube's related videos for that video, Tubi's related titles (for an episode, the show's), and Prime Video's "Customers also watched" and similar rows from the title's details. It is also in the player's **Options** menu (▲/▼ while watching); picking it there saves your place and opens the list. BACK returns to where you were. Pluto TV is live channels only and has no such data, so it doesn't show the entry.
- **Don't recommend channel / Hide channel** (YouTube): hold OK on a YouTube video to keep that channel off your screens. Where YouTube's own menu offers "Don't recommend channel" (its Home recommendations, signed in), ClearTV asks you to confirm with the channel's name, because YouTube's TV menu has no undo, then sends it to YouTube. Elsewhere (Gaming and other topic pages, search results, signed out) the menu offers **Hide channel** instead. Either way the channel goes on ClearTV's own list of hidden channels: its videos and its channel tile leave every YouTube row at once and stay off all YouTube screens, More like this grids and the Up next queue from then on, also after leaving and reopening YouTube. YouTube's own setting is slow and doesn't cover topic pages such as Gaming, which is why a channel could come back there before. **Settings → Hidden channels** lists them; OK on one shows it again in ClearTV. Prime Video, Tubi and Pluto TV have no channels to hide.
- The README now shows the newest features: YouTube chapters with SKIP SPONSOR and auto-skip, More like this, Continue watching inside each service and Don't recommend channel, with new screenshots.
- **Up next** at the end of an episode now sits in the top-left corner, so it no longer covers the progress bar and controls at the bottom. Its keys are the same; the hint now reads → for the autoplay switch and ← back to Play now, matching the two buttons side by side. The **Skip intro / Skip sponsor** button keeps its place, but moves up just above the controls while they're showing, so it doesn't cover the title either.
- **Go to channel works from home for every YouTube video**: on a video made by two creators together (a collaboration, such as the SourceCode documentaries), Go to channel on a home card said "No channel found". It now opens the first creator's channel, the same as inside YouTube. Go to channel shows only where the service has creator channels (YouTube); Tubi and Prime Video episodes keep **Go to show**, and Pluto TV cards no longer show it (a Pluto card already opens its channel). In the Pluto guide's hold-OK menu the same action is now called **Watch full screen**.
- **Lighter and smoother**: YouTube hover previews no longer ask YouTube twice for a video they already know has no clear stream, the preview's artwork stops drawing under the video, image loading threads close when you leave a screen, and the service screens draw one background layer instead of three. Home uses about 7 MB less memory and the APK is 83 KB smaller.
- **More like this finds channels about the same thing** (YouTube): ClearTV now reads what the video says about itself (its tags, hashtags, topic, game or music card, title, chapters and description) and uses that to look for channels on the same subject, then puts the channels that match it first. A small channel about the same game, recipe or synth now comes before a big channel that is only loosely related, and channels that YouTube's related lists missed can show up through a short search for the video's main subject. Hidden channels and the channel you were watching stay out, movies keep their own row, and the first rows still show in about 1.5 to 2 seconds.
- Channel tiles found this way show the channel's picture too.
- The README explains how YouTube More like this now finds channels about the same subject, with a new screenshot.

## 0.1.22 (2026-10-23)

- YouTube videos with chapters now show them: the progress bar is split into one segment per chapter with a small gap between them, and the name of the current chapter appears above the bar. While you move through the video with left/right, the name of the chapter you would land in is shown instead. Videos without chapters (and other services) look exactly as before.
- When a chapter is named as a sponsor or ad segment (for example "Sponsor", "Sponsored by", "Ad", "Advertisement", "Promo"), a **SKIP SPONSOR** button appears in the Skip intro spot. Press OK to jump to the start of the next chapter (or to the end if it is the last chapter). Words like "adventure" or "ad-free" do not count.
- New setting **Auto-skip sponsor chapters** in Settings, right after Autoplay next (off unless you turn it on). When on, sponsor chapters are skipped by themselves with a short "Skipped sponsor" note. Each chapter is skipped only once, and if you go back into a sponsor chapter yourself it shows the Skip sponsor button instead of skipping again. Chapters come from the video itself; ClearTV uses no outside skip service.
- Each service's own screen now starts with a **Continue watching** row (YouTube, Prime Video and Tubi), with only that service's titles and a bar showing how far you got. Picking a title offers to resume where you stopped, just like the home row. The row updates when you come back from watching. Pluto TV is live TV only, so it has nothing to resume and shows no row.

## 0.1.21 (2026-10-22)

- The "Up next" card at the end of an episode (and the Skip intro button, which uses the same spot) now sits closer to the right edge of the screen.
- The Up next card has an **Autoplay** switch: press down (or right) to reach it and OK to turn Autoplay next on or off. Turned on, the next episode starts after a 10 second countdown, even if the card was already waiting at the end; turned off, the countdown stops and the card waits for OK. It is the same setting as Settings > Autoplay next (still off unless you turn it on). OK on Play now and BACK work as before. Works in every service that shows the card.

## 0.1.20 (2026-10-21)

- If live TV or a video freezes because the stream stopped arriving, ClearTV now notices within about 10 seconds and reloads it by itself, the same as changing the channel and back. If it keeps freezing it tries a few more times with longer waits, then tells you instead of looping.
- A live channel that falls too far behind (after a long pause or a stall) jumps back to live instead of stopping.
- Hold OK on a video anywhere (home rows in every theme, search results, service rows, the Pluto guide) for a new **Go to channel** item: YouTube opens the video's channel, Pluto TV opens that live channel full screen, Tubi and Prime Video episodes get **Go to show** (the series or show page). It is left out where a service has no such page. A short press of OK works exactly as before.
- Pluto TV guide: the "hold OK · menu / ▶▶ · favorite" hint moved up next to "PLUTO TV · LIVE", so it no longer covers the last category chip; the chip row fades at the edge when there are more chips.
- Pluto TV guide: the info panel now moves on to the next show when the hour or half hour turns over, instead of still showing the one that just ended.
- Picking a theme no longer reopens a service that ClearTV was launched into from a link.
- A short network hiccup while a Pluto TV channel or a video in the native player (Tubi and others) is playing no longer shows "Tuning" or "Loading": a request that hangs is sent again within about 2.5 seconds, and Pluto TV live keeps more of the channel loaded ahead (it plays about 5 seconds further behind live, 20 s instead of 15 s). The hint only appears if the picture has been waiting for more than 2.5 seconds.
- Settings now built into the app: ClearTV no longer downloads its service settings from an external server. Fixes for a service change arrive with normal ClearTV updates (Settings > Updates).
- The install guide now points to the signed app file on GitHub Releases (published by the owner); building from source is optional.

## 0.1.19 (2026-10-20)

- ClearTV can update itself: Settings > Updates checks for a new version once a day (you can turn that off) or when you ask, and installs it only if it really comes from ClearTV. The first time, it shows you how to allow installs.
- New **Get themes** in the Themes picker: browse themes with a preview, install, update or delete them. Daylight, OLED High Contrast and Candy now live there (if you used one, install it again from Get themes).
- Pressing DOWN through a long grid (Daylight) moves one line at a time to the bottom and UP comes back the same way; focus no longer jumps back up.
- Theme fixes: the first card's focus ring is no longer cut off at the left edge, rows scroll so the focused card and its row title stay in view, and Candy's top right no longer pushes the hero down.

## 0.1.18 (2026-10-20)

- Settings > About now credits the handle ddark1990.

## 0.1.17 (2026-10-20)

- New Themes button at the top right of the home screen (also in Settings > Theme) with a live preview of the real home. Themes change the whole layout, not just colours, and YouTube, Prime Video, Tubi and Pluto TV follow along.
- Six built-in themes: Stargazer (the familiar home, still the default), Top Shelf (like the Apple TV home: a big backdrop, what to continue in the focused service, a dock of app icons), Billboard (like a streaming app: tabs, a big billboard, a shelf per service), Daylight (light, with a sidebar and grids), OLED High Contrast and Candy (big glowing service tiles). Pictures are always shown wide, never cut down to a tall shape.
- Make your own, or have an AI assistant make one: a theme file can now describe any home layout from parts (backdrop, dock, sidebar, tabs, rows, grids, cards, clock) and keeps working when ClearTV updates.
- Bring themes in from your phone (paste or upload) or from a web link, try them for 30 seconds before keeping them, save the current look to your phone, and go back to Stargazer any time.
- An AI assistant on your home network can design a theme for you and show it on the TV; it only stays if you say so.

## 0.1.16 (2026-10-20)

- Hold OK for about half a second on any tile or channel, on every service, to open a menu with Play and Add to / Remove from Favorites.
- A short press still opens things right away, and the ▶▶ shortcut still works.
- Fixed: pressing BACK on a home card menu now closes the menu instead of ClearTV.

## 0.1.15 (2026-10-20)

- Pluto TV no longer gets stuck on "Tuning..." when you open it.
- Hovering on the channel that is already playing no longer restarts it.

## 0.1.14 (2026-10-20)

- Opening a movie or show in YouTube Movies & TV now shows Play movie and Trailer (or a note that it must be bought or rented) instead of an empty page.
- YouTube hover previews now play real muted video.
- YouTube videos no longer play with sound only behind the loading screen; the picture shows as soon as it starts.

## 0.1.13 (2026-10-20)

- YouTube Movies & TV no longer comes up empty; if nothing is found, it says so plainly.
- Hover previews are back and now work on every service, as a muted clip or a moving picture of the title.

## 0.1.12 (2026-10-20)

- Tubi Movies and TV Shows now show Tubi's own lists instead of an empty screen.
- Hover previews were removed so every service browses the same way.

## 0.1.11 (2026-10-20)

- Security fixes: the phone remote is harder for other web pages or devices to abuse, and odd requests can no longer crash the app.
- Sign-in codes are kept out of logs, and backup restore is stricter.

## 0.1.10 (2026-10-20)

- The app icon now fills its Fire TV tile, with the ClearTV name under the TV picture.
- Installing this version resets your sign-ins and settings.

## 0.1.9 (2026-10-20)

- The app icon now matches the banner and looks sharp at every size.

## 0.1.7 (2026-10-19)

- Prime Video profiles now show up and switching between them works.
- The "Who's watching?" screen no longer flashes up and closes for no reason.
- You can switch profiles from Settings, and highlighted tiles are no longer cut off.

## 0.1.6 (2026-10-19)

- Prime Video sign-in now finishes properly after you approve it.
- Changing video quality now actually switches within seconds.
- New: Favorites and Recently watched for live channels, Tubi hover previews, a Resume or Start over prompt, an Up next card and an optional Autoplay next setting.
- Also: voice search (falls back to the keyboard), a Prime live guide, a home Watchlist row, and backup and restore of your settings.

## 0.1.5 (2026-10-17)

- Prime Video and YouTube now show many more rows and tiles; they keep loading as you scroll.
- Prime Video adds Live TV, Sports, News, Subscriptions, Store and Categories, plus ratings, cast and all episodes on title pages.
- YouTube adds a Shorts section and signed-in Subscriptions and Library tabs.

## 0.1.4 (2026-10-17)

- Search all no longer crashes.
- Tubi now has captions and plays in better quality instead of staying blurry.
- Pressing OK quickly no longer starts two players; the bottom row of home and focus after removing a card are fixed.
- The captions button works on Pluto TV.

## 0.1.3 (2026-10-17)

- Prime Video, YouTube and Tubi get their own ClearTV screens, and everything plays inside ClearTV.
- Sign in to every service by scanning a QR code; profiles, captions and a quality picker are added.
- New on home: Continue watching, Favorites and one search across all services; plus Skip intro, Up next and a phone remote and keyboard.
- On-demand shows play without ad breaks, videos start faster, and a new app icon.

## 0.1.2 (2026-10-17)

- YouTube now always stays inside ClearTV and shows the full TV version, including Shorts and Movies & TV.
- Ad blocking never hides real videos or titles, only ads.

## 0.1.1 (2026-10-17)

- ClearTV is now just for video services: Prime Video, YouTube, Pluto TV and Tubi, each as a card on home.
- Pluto TV has its own fast TV guide with full-screen live channels.
- The arrow keys now jump between buttons and tiles, with a clear highlight.
- New look, faster start and lower memory use; pop-ups and jumps to other sites are blocked.

## 0.1.0 (2026-10-16)

- First version: a TV web browser with built-in ad and tracker blocking.
- Move around pages with the remote using a pointer or the arrow keys, and watch videos full screen.
- A home screen with shortcut tiles to popular streaming sites.
- Ad-blocking lists can update themselves, and you can choose per-site settings.
