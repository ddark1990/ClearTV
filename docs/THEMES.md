# ClearTV themes and layouts

A ClearTV theme is a small JSON file that sets the **whole look and layout** of the home screen, plus the chrome of
the service screens (YouTube, Prime Video, Tubi), the Pluto TV guide and the player overlays. Themes are **data**:
they are validated strictly, can be shared as files or links, and keep working when ClearTV updates, because the app
only reads them; nothing in a theme is ever run.

- **Version 2 (current)**: design tokens (colours, fonts, shape) plus a **layout document**, `screens`, built from
  components: backdrop, hero, nav (dock, sidebar, tabs), rows, grids, cards, text, clock, logo, images, and
  stack/overlay/spacer containers. With it an agent or a person can assemble almost any TV home, or copy another
  product's design, without touching code.
- **Version 1** (still accepted): tokens plus one of three built-in homes (`layout.engine`: `classic`, `apple`,
  `rail`).

## Retheme or reskin?

- **Retheme (a theme file): use this for any change of look, layout or structure.** Colours, fonts, corners, where the
  services sit, what the home shows, a Top Shelf, a sidebar, tabs, grids, focus animations, backdrops. A theme is
  update-compatible: install a new ClearTV and your theme still applies; publish a new theme and users can pull it
  as an update (Import from a link, or the phone page) without a new app build.
- **Reskin (code): only for things the format cannot express.** New behaviour, new data sources, new component
  types, new screens, changes to playback, sign-in or blocking. That means changing ClearTV's source and shipping an
  app update. If you find yourself wanting a reskin for something visual, first check the component reference
  below; if it is close but missing a knob, a small format addition (a new key in the schema and renderer) is better
  than a one-off code skin, because every theme can then use it.

## Built-in themes

The built-ins (Stargazer, Top Shelf, Billboard) live in `app/src/main/assets/themes/`; Daylight, OLED High Contrast and Candy moved to the theme store (`themes/` in the repo, **Get themes** in the picker; docs/UPDATES.md). All except Stargazer (id `default`) are version 2 documents, so they double as examples. Stargazer stays a version 1 theme on the original classic home, so it looks exactly as it did in 0.1.16.

| Id | Name | Home layout | Service screens |
| --- | --- | --- | --- |
| `default` | Stargazer | The original ClearTV home (version 1, classic engine): header with Search all, Themes and Settings, the service tiles, Continue watching and Watchlist rows, still star field. | Section list, artwork top right. |
| `topshelf` | Top Shelf | tvOS-style: one full-screen backdrop of the focused item fading into the background; a Top Shelf carousel of the focused service's Continue watching and Watchlist; an app-icon dock (four services and Search, then Themes and Settings); names only under the focused icon; clock top right. | Tabs across the top over a full-bleed backdrop. |
| `billboard` | Billboard | Streaming-app style: logo and text tabs across the top, a big billboard (title and description over the focused item's backdrop), then Continue watching, one shelf per service (YouTube, Prime Video, Tubi, Pluto TV) and My list; the services are opened from the tabs. Red accent, condensed type, square corners. | Tabs across the top. |
| `daylight` (store) | Daylight | Light: one navigation, a frosted sidebar (Search all, the four services, Themes, Settings); on the right a featured panel and grids of Continue watching and Watchlist. | Wide sidebar and banner. |
| `oled` (store) | OLED High Contrast | The Top Shelf layout on pure black with white text, a thick yellow focus ring and larger text. | Tabs. |
| `candy` (store) | Candy | A 2x2 grid of big service tiles on the left with glowing focus; on the right the clock, a large hero, Watchlist and Continue watching; Search, Themes and Settings as pills. | Sidebar. |

Pick one with the **Themes** button on the home screen or in **Settings > Theme**. Moving through the list stays
smooth, and the live preview updates when you pause on a theme: for version 2 themes it is the real home, built
with that theme and drawn small. OK applies it.

### Design notes: the tvOS look (Top Shelf, OLED High Contrast)

Research behind the apple engine (Apple's tvOS Human Interface Guidelines for the Home screen, Top Shelf, app icons
and focus, plus Apple's tvOS App Programming Guide):

- **Structure.** The tvOS home is an app-icon grid. The top row holds up to five apps; when one of them is focused,
  the area above it, the **Top Shelf**, shows that app's content. Content (continue watching, favourites) lives in
  the Top Shelf, not in separate full-height rows. ClearTV: services and Search all are the first row; the focused
  service's Continue watching and Watchlist fill the shelf; Themes and Settings are the second row.
- **Top Shelf size.** The static Top Shelf image is 1920x720 px at 1080p (2320x720 pt in current guidance): the upper
  two thirds of the screen. ClearTV: the shelf is the top 352 of 540 dp; the backdrop covers the whole screen and
  fades into the dark base, so there is never a hard edge, and it is the only image layer.
- **Sectioned content.** Shelf items are focusable and scroll horizontally; a label appears only when an item is
  focused. ClearTV: 228x128 dp 16:9 cards (scaled by tiles.size), label under the focused card.
- **App icons.** 400x240 px, safe zone 370x222; shown unfocused at 300x180 px and focused at 370x222 px at 1080p;
  the name appears below the icon only when it is focused; simple opaque backgrounds, one centred focal point.
  ClearTV: 150x90 dp icons (the 300x180 px size at 1080p), opaque brand-colour or system-grey tiles, logo centred,
  28 dp gutters, name below the focused icon.
- **Focus and parallax.** The focused item scales up, rises with a large soft shadow and catches a specular
  highlight; layered images tilt only under the remote's touch surface. A Fire TV remote has no touch surface, so
  ClearTV uses a clean 1.1x scale-up, a 24 dp shadow and a one-time highlight sweep with no tilt or skew.
- **Materials and type.** White text on content, translucent blurred materials, a San Francisco-like sans, no
  borders or outlines, rounded corners about 12 px. ClearTV: white text with a soft text shadow, 6-12 dp corners,
  no rings (except high-contrast themes with a ring of 3 dp or more). Live blur needs Android 12 and Fire TV
  devices run older versions, so scrims stand in for the blur. San Francisco cannot be redistributed; the theme's
  fonts (for example the system sans) are used.

## The format

The machine-readable JSON Schema is [`theme.schema.json`](theme.schema.json) (also served by the TV at `api/schema`).
Every field except `format`, `version`, `id`, `name` and (version 2) `screens.home` is optional; missing tokens
keep the Default theme's value. Maximum size **64 KB**.

### Example (paste this)

A complete version 2 theme: a Top Shelf with an app dock and a Watchlist row, serif headings, amber focus. Paste it
on the phone page (Settings > Theme > Import from phone) or send it to `api/validate`.

```json
{
  "format": "cleartv-theme",
  "version": 2,
  "id": "forest",
  "name": "Forest",
  "colors": { "background": "#0F1A14", "panel": "#17261D", "surface": "#24402F", "text": "#F3EBDD",
              "textMuted": "#CFC4B0", "textSoft": "#9FB3A5", "accent": "#F2B544" },
  "font": { "display": "serif", "body": "sans" },
  "shape": { "radius": 12, "focusScale": 1.08, "cardOpacity": 85 },
  "screens": {
    "service": { "nav": "tabs" },
    "home": {
      "defaultFocus": "apps",
      "root": { "type": "overlay", "width": "fill", "height": "fill", "children": [
        { "type": "backdrop", "source": "focused", "fallback": "brand", "fade": 120, "scrim": { "bottom": 1, "left": 0.7 } },
        { "type": "stack", "direction": "column", "scroll": true, "width": "fill", "height": "fill",
          "padding": [48, 40, 48, 0], "children": [
          { "type": "hero", "width": 620, "height": 118, "titleLines": 2, "descLines": 1 },
          { "type": "row", "id": "shelf", "data": "focused-service", "card": { "width": 228, "label": "focused" },
            "effect": { "scale": 1.1, "shadow": 24, "ring": false, "shine": true }, "focus": { "down": "apps" } },
          { "type": "dock", "id": "apps", "items": ["services", "search", "themes", "settings"], "gap": 20,
            "margin": [0, 12, 0, 0], "card": { "width": 104 }, "effect": { "scale": 1.1, "shadow": 24, "ring": false },
            "focus": { "up": "shelf" } },
          { "type": "row", "data": "watchlist", "title": "Watchlist", "margin": [0, 20, 0, 40],
            "card": { "width": 200, "label": "below" } }
        ] },
        { "type": "clock", "gravity": "top-end", "size": 18, "margin": [0, 27, 48, 0] }
      ] }
    }
  }
}
```

### Tokens

| `format` | `"cleartv-theme"` | Required, always this. |
| `version` | `1` or `2` | Required. 2 adds `screens` and `assets`. |
| `id` | `a-z`, `0-9`, `-`, 1-32 chars | Required. Same id as a saved custom theme replaces it. Built-in ids cannot be replaced. |
| `name` | 1-40 printable chars, no `<` `>` | Required. Shown in Settings. |
| `author` | up to 40 chars | Optional credit. |
| `colors.background` | `#RRGGBB` | Screen background. |
| `colors.backgroundDeep` | `#RRGGBB` | Behind artwork while it loads and behind logo plates. |
| `colors.panel` | `#RRGGBB` | Cards, panels, menus, settings rows. |
| `colors.surface` | `#RRGGBB` | Focused row, tile or button fill. |
| `colors.line` | `#RRGGBB` | Hairlines, borders, progress tracks. |
| `colors.text` / `textMuted` / `textSoft` | `#RRGGBB` | Primary, secondary and hint text. |
| `colors.accent` | `#RRGGBB` | Focus ring and highlights. |
| `colors.good` / `bad` / `warn` | `#RRGGBB` | On/playing, errors, warnings. |
| `font.display` | `oxanium`, `sans`, `sans-medium`, `sans-light`, `condensed`, `serif`, `mono` | Headings, kickers, buttons. |
| `font.body` | `sans`, `sans-medium`, `sans-light`, `condensed`, `serif`, `mono` | Titles and body text. |
| `font.scale` | 0.85 - 1.25 | Text size multiplier. |
| `shape.radius` | 0 - 24 (whole dp) | Corner radius. |
| `shape.focusRing` | 2 - 6 (whole dp) | Focus ring width. |
| `shape.focusScale` | 1.0 - 1.10 | How much a focused tile grows. |
| `shape.cardOpacity` | 60 - 100 (whole %) | Card opacity; below 100 cards are frosted and let the background show through. |
| `tiles.size` | 0.8 - 1.3 | Tile size multiplier (tiles are always 16:9). |
| `layout.engine` | `classic`, `apple`, `rail` | Version 1 only: one of the three built-in homes. Not allowed in version 2. |
| `layout.rowSpacing` | 8 - 48 (whole dp) | Space between rows. |
| `layout.header` | `full`, `compact`, `minimal` | Classic home header: name + kicker + version, name only, or buttons only (Search all, Themes and Settings always show). |
| `layout.backdrop` | `stars`, `gradient`, `plain` | Home background. |
| `layout.servicesStyle` | `cards`, `hero` | Classic: service tiles as standard cards, or a big, tall featured row with larger logos. |
| `layout.homeRows` | list of `services`, `continue`, `watchlist` | Which home rows show, top to bottom. `services` is required. Hidden rows keep their data. |
| `layout.serviceAccents` | `true`, `false` | Keep each service's brand accent, or use the theme accent everywhere. |

The `layout.*` keys other than `serviceAccents` only shape version 1 homes; a version 2 home is described entirely by `screens`.

### Layout document (`screens`)

```
"screens": {
  "service": { "nav": "list" | "tabs" | "sidebar" },
  "home":    { "defaultFocus": "<id>", "root": <component> }
}
```

- `screens.home.root` is a component tree, at most 10 levels deep and 160 components.
- `screens.home.defaultFocus` is the id of the component focused on launch.
- `screens.service.nav` sets the service screens: `list` (section list on the left, artwork top right), `tabs`
  (section tabs across the top over a full-bleed backdrop of the focused tile, title only under the focused card),
  `sidebar` (a wide frosted sidebar and a featured banner). The Pluto TV guide and the player overlays follow it.
  Settings keep their own chrome, coloured by the tokens.

### Component reference

Every component has `type` and may have these common keys:

| Key | Values | Meaning |
| --- | --- | --- |
| `id` | `a-z0-9-`, 1-32, starts with a letter | For focus rules and `defaultFocus`. Unique per screen. |
| `width`, `height` | dp 0-1920, `"fill"`, `"wrap"` | Size. Default `wrap`. |
| `weight` | 0-10 | Share of leftover space in a stack. |
| `padding`, `margin` | dp, or `[left, top, right, bottom]` | Inner and outer space (in an overlay, margin offsets from the gravity edge). |
| `background` | colour ref | Fill, rounded by `radius` (0-48, default `shape.radius`). |
| `opacity` | 0.1-1 | Opacity of the component. |
| `gravity` | `top-start` `top` `top-end` `start` `center` `end` `bottom-start` `bottom` `bottom-end` | Placement in an overlay; cross-axis placement in a stack. |
| `focus` | `{"up","down","left","right": "<id>"}` | Where DPAD goes from anything focusable inside. Unset directions use the nearest item on screen. |

**Colour refs** are tokens (`background`, `deep`, `panel`, `surface`, `line`, `text`, `muted`, `soft`, `accent`,
`good`, `bad`, `warn`, `card` = panel at `cardOpacity`, `brand` = the service's own colour on service items, `white`,
`transparent`) or an opaque `#RRGGBB`.

| Type | Extra keys | What it is |
| --- | --- | --- |
| `stack` | `direction` (`row`/`column`), `gap`, `align` (`start`/`center`/`end`/`stretch`), `scroll`, `children` | Children in a line. With `scroll: true` it scrolls, and the focused item is always brought fully on screen (with room for its focus growth). |
| `overlay` | `children` | Children layered on each other, placed by `gravity` and `margin`. |
| `spacer` | | Empty space; in a stack it takes the leftover space unless sized. |
| `backdrop` | `source` (`focused`/`asset`/`none`), `asset`, `fallback` (`brand`/`stars`/`gradient`/`plain`), `scrim` (`bottom`, `left`, `top`: 0-1), `fade` (ms) | The single full-screen image layer (at most one per screen). `focused` shows the focused card's art, or the focused service's first item; with no art, the fallback (`brand` = the service colour and its logo). `scrim.bottom` is one long eased fade into the background colour, so there is never a hard edge. |
| `hero` | `titleSize` (16-48), `titleLines` (1-3), `descLines` (0-3), `kicker`, `shadow`, `color` | Kicker, title and description of whatever is focused (a service, a card, Search, Themes, Settings). Fixed size: long titles end with an ellipsis instead of pushing the layout. |
| `text` | `text` (80 chars) or `bind` (`clock`, `version`, `status`, `app`, `focused-title`, `focused-service`), `size`, `color`, `style` (`display`/`body`/`kicker`), `lines`, `align`, `shadow` | Static or live text. |
| `clock` | `size`, `color`, `style`, `shadow` | The time. |
| `logo` | `size`, `color`, `asset` | The ClearTV wordmark, or an asset image. |
| `image` | `asset`, `scale` (`crop`/`fit`) | An asset image. |
| `button` | `action` (`search`/`themes`/`settings`), `label`, `style` (`tile`/`icon`/`text`/`logo-text`/`pill`), `glyph`, `size`, `color`, `card`, `effect` | One action. |
| `nav` | `items` (`services`, `search`, `themes`, `settings`, in order), `direction`, `gap`, `itemStyle` (`tile`/`icon`/`text`/`logo-text`/`pill`), `align`, `size`, `glyph`, `color`, `card`, `effect` | A list of services and actions. A row scrolls sideways if it is wider than the screen. |
| `dock` | as `nav` | Preset: a row of `icon` items (app icons in the brand colour, name only when focused). |
| `sidebar` | as `nav` | Preset: a column of `logo-text` items. |
| `tabs` | as `nav` | Preset: a row of `text` items. |
| `row` | `data`, `title`, `titleColor`, `card`, `gap`, `effect`, `hideEmpty`, `maxItems` | A sideways shelf of landscape cards. |
| `grid` | as `row`, plus `columns` (2-6) | The same cards in lines. |

**Data bindings** (`data` on rows and grids): `services`, `continue` (Continue watching), `watchlist` or `favorites`
(the Watchlist), `focused-service` (the Continue watching and Watchlist of the service focused in a nav or services
row: a Top Shelf), `search` (a Search all card), `service:youtube`, `service:prime`, `service:tubi`, `service:pluto`, `service:paramount`
(that service's items). Every row and grid works for all four services; empty ones hide unless `hideEmpty: false`.

**Card template** (`card`): `width` (80-480 dp, times `tiles.size`), `aspect` (`16:9`, `2:1`, `21:9`, `4:3`;
landscape only, there are no poster cards), `radius`, `label` (`below`, `focused` = only under the focused card,
`overlay` = on the art, `none`), `subtitle` (service name), `fill` (colour ref behind art or logo; service tiles:
`card` or `brand`), `logoSize`, `progress`.

**Focus effect** (`effect`): `scale` (1-1.25, default `shape.focusScale`), `shadow` (elevation dp, 0-40), `glow`
(soft accent ring), `ring` (the focus ring, default on), `shine` (a highlight that sweeps across once).

**Assets** (top level, version 2): up to 8 named images, each `{"url": "https://.../x.webp", "maxKb": 256}` (png,
jpg, jpeg or webp; at most 512 KB; no user name or password; redirects are not followed; decoded downsampled) or
`{"bundled": "logo"}` / `{"bundled": "stars"}`. Use them with `backdrop` (`source: "asset"`), `image` and `logo`.

**Whole-screen rules.** Ids are unique; every `focus` target and `defaultFocus` exists and holds something
focusable; at most one backdrop; the home must reach the services, Search, Themes and Settings (through nav
items, buttons, or rows with that data), so no theme can lock you out; and each of them appears **once**
(`duplicate_nav`): the services in a sidebar and again in a grid is rejected.

#### Backdrop quality

A full-screen backdrop must use full-size art. ClearTV fetches the largest variant of the focused card's art for the backdrop: YouTube `maxresdefault`, then `sddefault`, then `hqdefault`; Amazon art without its size suffix; a `w=` parameter raised to 1920. It decodes the image at screen width off the main thread, once focus rests for 300 ms. Art narrower than half the screen is shown dimmed into the background instead of blown up. For an `asset` backdrop, use an image at least 1280 px wide with `maxKb` of 128 or more. The validator warns (`warn_quality`) when a backdrop asset's `maxKb` is below 128.

### Performance rule: match Default's focus speed

Themes must move focus as fast as the Default home. Focus uses Default's mechanism: one 120 ms scale plus the focus ring, and everything else switches instantly. `effect.shadow`, `effect.glow`, `effect.shine` and `fade` are off by default. They cost drawing on every focus move, so the validator flags them with `warn_slow`. Agents should avoid animations: leave these off and use `effect.scale` and `effect.ring` only.

## Focus and hover pitfalls

On a TV everything happens on focus, so focus effects shape a theme as much as its colours. What to watch:

- **Backdrop swaps on focus.** A focus-driven backdrop changes the picture behind your text on every move. ClearTV
  swaps it 150 ms after focus settles (fast key presses never redraw it) and caps the fade at 120 ms. You still
  need scrims: `scrim.bottom` 0.5 or more, or `scrim.left` 0.3 or more behind text, or bright art makes the hero and
  labels unreadable. Only one backdrop per screen.
- **Growth needs room.** A focused card grows by `width x (scale - 1)`. Leave at least that much `gap` between cards
  (and that much padding around the row), or the focused card overlaps its neighbours or is cut at the edge. Text
  items (tabs, sidebar entries, pills) grow at most 4%.
- **The ring follows the shape.** The focus ring and the clip use the card's corner radius and its exact bounds, at
  every scale. Keep the radius at most half the card height, and do not give a nav a different `radius` from its
  `card.radius`.
- **Labels on focus must not move things.** Use `label: "focused"` (it fades in in its reserved space) rather than
  showing and hiding components. The hero has a fixed size and line counts, so long titles never push the layout.
- **One place per option.** Show each of the services, Search, Themes and Settings once. Two navigations with the same
  options (a sidebar and a grid of the same services) double the key presses and confuse focus.
- **Speed.** ClearTV keeps focus as fast as the Default home: a 100 ms scale, a pre-rendered shadow, no layout or
  decoding on a key press. Effects still cost a little each: prefer `shadow` 8-24, `shine` only on a few large items,
  and no backdrop fade longer than 120 ms.

`api/validate` and `api/apply` return `warnings` for the cases it can detect: `warn_gap` (gap smaller than the focus
growth), `warn_radius` (radius the ring cannot follow, or nav and card radius differ), `warn_readability` (focus-driven
backdrop without enough scrim), `warn_slow` (fade over 150 ms, shadow over 24 dp), `warn_layout` (weight inside a
scrolling stack). Duplicate navigation is an error (`duplicate_nav`).

**Checklist before applying a theme** (agents: go through it every time):

1. `api/validate` passes and `warnings` is empty.
2. Every option (each service, Search, Themes, Settings) appears exactly once.
3. Every row, grid and dock: `gap` at least `card.width x (scale - 1)`; content padding at least that at the edges.
4. Card radius at most half the card height; a nav's `radius` equals its `card.radius`.
5. A focus-driven backdrop has `scrim.bottom` 0.5 or more (or `scrim.left` 0.3 or more) and `fade` 120 or less.
6. Labels that appear on focus use `label: "focused"`; the hero has fixed `height`, `titleLines` and `descLines`.
7. Everything fits 960 x 540 dp with 48 dp side margins and 27 dp top/bottom margins, or sits in a scrolling stack.
8. `defaultFocus` is something that is always there (a nav, not a row that can be empty).
9. Preview with `revertAfter`, move focus through every row and both ends of each row, and confirm only after the
   user says yes.

### Validation (strict)

ClearTV refuses a theme, and lists every problem with its JSON path and a stable code, when:

- it is over 64 KB, is not strict JSON (comments, trailing commas, duplicate keys) or is nested too deeply;
- it has an unknown key anywhere (typos are caught, nothing unexpected rides along: there is no key for code,
  scripts, expressions, HTML or file paths);
- a colour is not a token or an opaque `#RRGGBB`; a font, component type, binding or other value is not in its list;
  a number is outside its range;
- a whole-screen rule above is broken;
- the merged colours are hard to read: text and muted text below 4.5:1 on the background, text below 4.5:1 on
  panels, translucent cards and focused rows, muted text below 3:1 on panels, soft text below 3:1 on the
  background, or the focus ring below 3:1 on the background (2:1 on focused rows).

If a theme validates but its home cannot be built on a device, ClearTV shows the Default home instead; a broken
theme never leaves you with a broken screen. Previews still revert by themselves.

### Performance

The composed home is built once when the screen opens. Moving focus changes only text, one bitmap, and
render-thread properties (scale, elevation, alpha); hero and label text have a fixed size, so changing them does not
re-measure the screen. Rows reuse their card views when the data changes, and a `focused-service` shelf keeps one
prebuilt row per service and switches by visibility. Backdrop art is downsampled to half the screen width (RGB_565)
and cached. Validation takes well under a millisecond per theme on a desktop JVM.

## Example themes

[`themes/`](themes/) holds examples written from the schema and checked with the validator
(`tools/themetest/run.sh`):

| File | Version | Look |
| --- | --- | --- |
| [`apple.json`](themes/apple.json) | 2 | Apple TV-like Top Shelf with an app dock, written as a layout document. |
| [`sepia.json`](themes/sepia.json) | 1 | Warm paper with serif type on the rail engine. |
| [`terminal.json`](themes/terminal.json) | 1 | Green-phosphor terminal on the classic engine: monospace, square corners, dense rows. |
| [`kids.json`](themes/kids.json) | 1 | Big and Bright on the classic engine: largest text and tiles, thick yellow ring. |

## Importing, exporting and resetting

**From your phone (recommended).** In **Settings > Theme**, choose **Import from phone** and scan the QR code.
The phone page has a theme section: paste JSON or pick a `.json` file, then:

- **Check** validates it and shows every problem;
- **Preview on TV** applies it for 30 seconds, then the TV goes back to the previous theme unless you press
  **Keep** (on the phone, or **Keep this theme** at the top of Settings > Theme on the TV);
- **Keep** saves it to your themes and keeps it applied.

**From a link.** **Settings > Theme > Import from a link** asks for an `https://` address (type it with the remote,
or with the phone keyboard). ClearTV downloads at most 64 KB, validates it the same way and shows it in the list with
its preview; OK applies it.

**Export.** **Export current theme** on the phone page downloads the theme that is on screen now as a complete JSON
file (every field filled in), a good starting point for your own.

**Reset.** **Settings > Theme > Reset to Stargazer** (or **Reset** on the phone page) goes back to Stargazer, the default theme.
Saved custom themes stay in the list; hold OK (or press Menu) on one to delete it.

## For AI agents

Agents should change the look by writing a theme, not by editing code (see Retheme or reskin). The TV's phone page
has a JSON API to validate a theme, preview it with automatic revert and confirm it. See
[AGENTS-API.md](AGENTS-API.md).
