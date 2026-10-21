# Theme control API (for AI agents)

ClearTV's phone page doubles as a small JSON API so an AI agent (or a script) on your home network can read the
current theme, fetch the JSON Schema, generate a theme, validate it, show it on the TV with automatic revert, and keep
it. It is the same server, token and checks as the phone remote; nothing new listens on the network.

**Change the look with a theme, not with code.** A version 2 theme describes the whole home layout (backdrop, hero,
docks, sidebars, tabs, rows, grids, cards, focus rules and animations) plus the service screen chrome, as data. It
keeps working across ClearTV updates and can be shared and pulled as a file or link. Edit ClearTV's source (a
reskin) only for behaviour or components the format does not have; see "Retheme or reskin?" in
[THEMES.md](THEMES.md).

## Security model

- **Off by default.** The server runs only while you have opened a phone QR code (Settings > Theme > Import from phone,
  or Settings > Phone remote), and stops after 10 minutes without requests or 30 s after ClearTV leaves the screen.
- **Token.** Every URL starts with a random 128-bit token from the QR code: `http://<tv-ip>:<port>/<token>/`.
  Wrong tokens get 404; ten wrong tries stop the server.
- **LAN only.** Bound to the TV's home-network address; callers outside private address ranges are dropped.
- **Request checks.** `Host` must be exactly `<tv-ip>:<port>`; an `Origin` header, if sent, must be this server;
  `Sec-Fetch-Site` must be `same-origin` or `none` (so web pages cannot drive it, including DNS rebinding). POST bodies
  must be `application/json`, at most 66 560 bytes (a 64 KB theme plus its wrapper); no chunked bodies; the whole
  request must arrive within 6 s.
- **Data only.** Themes are colours, numbers, names and component trees from fixed lists. Nothing in a theme is
  run: there are no keys for code, scripts, expressions, HTML or file paths, and unknown keys are errors. The API
  never fetches URLs, runs code, or reads or writes files other than ClearTV's own theme store. The only network
  access a theme can cause is its optional image `assets`, fetched by the home screen when it is shown: https only,
  png/jpg/webp, no credentials, no redirects, at most 512 KB each and 8 per theme.
- **Gentle.** At most one apply per second, and previews revert by themselves.

## Endpoints

All paths are relative to `http://<tv-ip>:<port>/<token>/`. Every answer is JSON.

| Method | Path | Body | Answer |
| --- | --- | --- | --- |
| GET | `api/theme` | | `{"ok":true,"active":"<saved id>","preview":null or {"id","secondsLeft"},"theme":{...on screen now...}}` |
| GET | `api/schema` | | The JSON Schema (`theme.schema.json`) |
| GET | `api/themes` | | `{"ok":true,"active":"..","builtIn":[{"id","name","author","light"}],"custom":[...]}` |
| GET | `api/export` | | The theme on screen as a complete theme file (download) |
| POST | `api/validate` | a theme | `{"ok":true,"valid":true,"id":"..","normalized":{...every field filled...}}` or a 422 error |
| POST | `api/apply` | `{"theme":{...}}` or `{"id":".."}`, plus `"revertAfter": 5-300` (default 30) or `"confirm": true` | `{"ok":true,"applied":"..","preview":{"id","secondsLeft"}}` |
| POST | `api/confirm` | `{}` | Keeps the preview (custom themes are saved to Settings > Theme): `{"ok":true,"active":".."}` |
| POST | `api/revert` | `{}` | Ends the preview now: `{"ok":true,"active":".."}` |
| POST | `api/reset` | `{}` | Back to Default: `{"ok":true,"active":"default"}` |

### Errors

```json
{"ok":false,"error":{"code":"invalid_theme","message":"2 problem(s); fix every entry in \"errors\"."},
 "errors":[{"path":"colors.text","code":"contrast","message":"Contrast of text on background is 1.20:1; at least 4.5:1 is needed so it reads across a room."},
           {"path":"shape.radius","code":"range","message":"\"radius\" must be a number from 0 to 24."}]}
```

| HTTP | `error.code` | When |
| --- | --- | --- |
| 400 | `bad_json`, `bad_request`, `unknown_key` | Malformed body, wrong combination of keys, wrong Host/Origin |
| 404 | `not_found`, `unknown_theme` | Unknown endpoint or token; `id` that is not a built-in or saved theme |
| 405 | `method` | GET on a POST endpoint or the reverse |
| 409 | `no_preview` | `confirm` or `revert` without a running preview |
| 413 | `too_large` | Body over the limit |
| 415 | `content_type` | POST without `Content-Type: application/json` |
| 422 | `invalid_theme` | The theme failed validation; `errors` lists every problem with its JSON path and a stable code (`required`, `type`, `enum`, `range`, `pattern`, `color`, `contrast`, `unknown_key`, `reserved`, `too_large`, `bad_json`, `unsupported`, `limit`; layouts: `size`, `box`, `color_ref`, `duplicate_id`, `focus_target`, `one_backdrop`, `unreachable`, `children`, `missing_asset`, `too_many`, `too_deep`, `duplicate_nav`). A valid theme can still carry `warnings` (same shape as errors) in the validate and apply answers. |
| 429 | `too_fast` | Two applies within a second |

## Step-by-step flow for an agent

1. **Discover.** Ask the user to open **Settings > Theme > Import from phone** on the TV and send you the link under
   the QR code (or a photo of it). It looks like `http://<tv-ip>:<port>/<token>/theme`; the base is everything up to
   and including `<token>/`. Keep the token private; it is only valid while the page is on.
2. **Read the format.** `GET api/schema`, and the component reference in [THEMES.md](THEMES.md). `GET api/theme`
   returns the theme on screen; `GET api/themes` lists the built-ins, and `POST api/apply {"id": "topshelf",
   "revertAfter": 20}` shows one (their files are full version 2 examples; `GET api/export` downloads the one on
   screen).
3. **Generate** a version 2 theme: `format`, `version: 2`, your own `id` (not a built-in id), `name`, the tokens you
   need (opaque `#RRGGBB` colours with enough contrast), and `screens`:
   - `screens.home.root`: usually an `overlay` with one `backdrop`, a scrolling `stack` with the content, and
     anything pinned (a `clock` at `top-end`);
   - navigation to the services, Search, Themes and Settings (a `dock`, `sidebar`, `tabs` or `nav`, or buttons and
     a services row): required;
   - content rows and grids bound to `continue`, `watchlist`, `focused-service` or `service:<id>`, with a landscape
     `card` template;
   - ids on the parts focus moves between, `focus` rules where the nearest-item default is not what you want, and
     `defaultFocus`;
   - `screens.service.nav` (`list`, `tabs` or `sidebar`) for the service screens.
   To copy another product's design, describe its structure in these components (what is on top, what scrolls,
   where the services are, what a focused card does) and match the proportions in dp (the screen is 960 x 540 dp,
   keep 48 dp side margins and 27 dp top/bottom margins).
4. **Validate.** `POST api/validate` with the theme. On 422, fix every entry in `errors` (the paths are exact, for
   example `screens.home.root.children[1].children[2].card.aspect`) and validate again until it passes. Then fix
   every entry in `warnings` (focus and hover pitfalls: `warn_gap`, `warn_radius`, `warn_readability`, `warn_slow`,
   `warn_layout`) and go through the checklist in "Focus and hover pitfalls" in [THEMES.md](THEMES.md).
5. **Preview with auto-revert.** `POST api/apply` with `{"theme": {...}, "revertAfter": 30}`. The TV shows it with a
   countdown and goes back by itself unless confirmed.
6. **Confirm.** Ask the user whether they like it, then `POST api/confirm` (or `api/revert`). Confirmed themes are
   saved under Settings > Theme > Your themes. To share it, give the user the file or host it at an https link
   (Settings > Theme > Import from a link).

### Example with curl

```sh
BASE='http://<tv-ip>:<port>/<token>'          # from the QR code link, without the trailing "theme"

curl -s "$BASE/api/schema" -o theme.schema.json
curl -s "$BASE/api/theme"

curl -s -X POST -H 'Content-Type: application/json' --data-binary @my-theme.json "$BASE/api/validate"

# preview for 30 seconds (wrap the theme as {"theme": ...})
printf '{"theme":%s,"revertAfter":30}' "$(cat my-theme.json)" |
  curl -s -X POST -H 'Content-Type: application/json' --data-binary @- "$BASE/api/apply"

curl -s -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/confirm"   # keep it
curl -s -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/revert"    # or go back now

curl -s -X POST -H 'Content-Type: application/json' -d '{"id":"billboard","revertAfter":20}' "$BASE/api/apply"  # try a built-in
curl -s -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/reset"     # back to Stargazer (id default)
curl -s "$BASE/api/export" -o current-theme.json
```

## Example prompt for your agent

> My Fire TV runs ClearTV. Its theme API is at `<paste the link from Settings > Theme > Import from phone>`; the
> docs are docs/AGENTS-API.md and docs/THEMES.md in the ClearTV repo. Change the look only with a version 2 theme,
> not by editing code. Fetch the schema, then design a theme called "Forest": a Top Shelf home like Apple TV (one
> full-screen backdrop, the focused service's Continue watching above an app dock), deep green background, warm
> cream text, amber focus, serif headings, tabs on the service screens. Validate it and fix every error, preview it
> on my TV for 60 seconds and ask me whether to keep it. Only call confirm after I say yes.
