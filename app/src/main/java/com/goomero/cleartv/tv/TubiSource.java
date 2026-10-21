package com.goomero.cleartv.tv;

import android.content.Context;
import android.util.Base64;
import android.util.JsonReader;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tubi through the APIs tubitv.com itself uses: an anonymous device token (Tubi's signed PKCE exchange), the
 * personalised home screen, search, series episodes, and the clear HLS stream of each title for ClearTV's player.
 * Sign-in is Tubi's activation code (tubitv.com/activate); with a user token the home screen carries Continue
 * watching and My List. Tubi's video ads are requested by its own web player and are not part of the stream.
 */
final class TubiSource extends Source {
    private static final String TAG = "CTV.Tubi";
    // endpoints and parser keys come from the bundled config (Config)
    private static String ACCOUNT, TENSOR, CONTENT, SEARCH, PLATFORM, ACTIVATE;
    private static Set<String> CHILDREN, SKIP;
    private static final Set<String> NONE = new HashSet<>();

    static {
        Config.onChange(new Runnable() {
            @Override
            public void run() {
                ACCOUNT = Config.s("tubi.account", "https://account.production-public.tubi.io");
                TENSOR = Config.s("tubi.tensor", "https://tensor-cdn.production-public.tubi.io");
                CONTENT = Config.s("tubi.content", "https://content-cdn.production-public.tubi.io");
                SEARCH = Config.s("tubi.search", "https://search.production-public.tubi.io");
                PLATFORM = Config.s("tubi.platform", "web");
                ACTIVATE = Config.s("tubi.activateUrl", "tubitv.com/activate");
                CHILDREN = Config.set("tubi.children", "containers", "contents");
                SKIP = Config.set("tubi.skip", "video_resources", "gn_fields", "tubi_fields", "imdb_fields",
                        "video_metadata", "trailers", "video_previews", "awards", "rt_fields", "credit_cuepoints",
                        "subtitles", "schedule_data", "monetization", "video_renditions", "hero_images", "backgrounds",
                        "internal_tags", "ui_customization", "policy_match", "competitors", "teams", "league", "vibes");
            }
        });
    }

    TubiSource(Context c) {
        super(c, "tubi");
    }

    @Override
    public boolean previewVideo(Item item) {
        return item.kind == Item.VIDEO || item.kind == Item.LIVE;
    }

    @Override public String id() { return "tubi"; }
    @Override public String name() { return "Tubi"; }
    @Override public int accent() { return 0xFFFFDF00; }
    @Override public int accent2() { return 0xFF7408FF; }
    @Override public String iconKey() { return "tubitv.com"; }

    @Override
    public List<Nav> navs() {
        List<Nav> l = new ArrayList<>();
        l.add(new Nav("home", "Home"));
        l.add(new Nav("movies", "Movies"));
        l.add(new Nav("tv", "TV Shows"));
        l.add(new Nav("live", "Live TV"));
        l.add(new Nav("mine", "My Stuff"));
        return l;
    }

    private String deviceId() {
        String d = prefs.getString("device_id", null);
        if (d == null) {
            d = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", d).apply();
        }
        return d;
    }

    private Map<String, String> headers() throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Origin", "https://tubitv.com");
        h.put("Referer", "https://tubitv.com/");
        h.put("Authorization", "Bearer " + token());
        return h;
    }

    private String q() {
        return "platform=" + PLATFORM + "&device_id=" + deviceId();
    }

    // -------------------------------------------------------------------------------------- browse

    @Override
    public Item.Page load(Nav nav) throws IOException {
        if (nav.id.equals("mine") && !signedIn()) {
            Item.Page p = new Item.Page();
            p.empty = "Sign in to see Continue watching and My List.";
            return p;
        }
        if (nav.id.equals("movies") || nav.id.equals("tv")) {
            // Tubi's own Movies and TV Shows storefronts (content_mode), as tubitv.com shows them: whole rows of
            // that kind. Filtering the mixed home rows instead left Movies empty whenever no home row happened to
            // hold three movies.
            Item.Page p = containers(TENSOR + "/api/v8/homescreen?" + q() + "&limit=6&expand=1&content_mode="
                    + (nav.id.equals("movies") ? "movie" : "tv"));
            int want = nav.id.equals("movies") ? Item.VIDEO : Item.SERIES;
            Item.Page out = new Item.Page();
            for (Item.Shelf s : p.shelves) {
                List<Item> keep = new ArrayList<>();
                for (Item it : s.items) if (it.kind == want) keep.add(it);
                if (!keep.isEmpty()) out.shelves.add(new Item.Shelf(s.title, keep));
            }
            if (out.isEmpty()) out.empty = "Tubi returned no " + (nav.id.equals("movies") ? "movies" : "TV shows") + " right now. Try again later.";
            return out;
        }
        Item.Page home = containers(TENSOR + "/api/v8/homescreen?" + q() + "&limit=6&expand=1");
        if (nav.id.equals("home")) return home;
        Item.Page out = new Item.Page();
        for (Item.Shelf s : home.shelves) {
            List<Item> keep = new ArrayList<>();
            for (Item it : s.items) {
                boolean match = nav.id.equals("live") ? it.kind == Item.LIVE : isPersonal(s.title);
                if (match) keep.add(it);
            }
            if (nav.id.equals("mine") ? isPersonal(s.title) : !keep.isEmpty()) out.shelves.add(new Item.Shelf(s.title, keep));
        }
        if (out.isEmpty()) out.empty = nav.id.equals("mine") ? "Nothing in Continue watching or My List yet." : "Nothing here yet.";
        return out;
    }

    private static boolean isPersonal(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        return t.contains("continue") || t.contains("my list") || t.contains("queue") || t.contains("watched");
    }

    @Override
    public Item.Page search(String query) throws IOException {
        return containers(SEARCH + "/api/v3/search?search=" + URLEncoder.encode(query, "UTF-8") + "&" + q());
    }

    /** Reads a {containers:[...], contents:{id: {...}}} answer (home screen or search) into rows. */
    private Item.Page containers(String url) throws IOException {
        final List<JSONObject> conts = new ArrayList<>();
        final Map<String, JSONObject> contents = new HashMap<>();
        try (Http.Response r = Http.open(url, headers(), null, null, true)) {
            JsonScan.scan(new JsonReader(new InputStreamReader(r.body, StandardCharsets.UTF_8)), NONE, CHILDREN, SKIP,
                    new JsonScan.Sink() {
                        @Override
                        public void onValue(String key, String name, JSONObject v) {
                            if (key.equals("containers")) conts.add(v);
                            else contents.put(name, v);
                        }
                    });
        } catch (Http.Error e) {
            if (e.code == 401) prefs.edit().remove("anon_token").apply();
            throw e;
        }
        Item.Page page = new Item.Page();
        for (JSONObject c : conts) {
            List<Item> items = new ArrayList<>();
            JSONArray ch = c.optJSONArray("children");
            if (ch != null) for (int i = 0; i < ch.length(); i++) addItem(items, ch.optString(i), contents);
            JSONArray its = c.optJSONArray("items");
            if (its != null) for (int i = 0; i < its.length(); i++) {
                JSONObject o = its.optJSONObject(i);
                if (o != null) addItem(items, o.optString("id"), contents);
            }
            String title = c.optString("title", "");
            if (title.isEmpty() && "search".equals(c.optString("id"))) title = "Results";
            page.shelves.add(new Item.Shelf(title, items));
        }
        if (page.isEmpty()) page.empty = "Nothing found.";
        return page;
    }

    private static void addItem(List<Item> out, String id, Map<String, JSONObject> contents) {
        JSONObject o = contents.get(id);
        if (o == null) return;
        Item it = toItem(id, o);
        if (it != null) out.add(it);
    }

    private static Item toItem(String id, JSONObject o) {
        String type = o.optString("type");
        int kind = type.equals("s") ? Item.SERIES : type.equals("l") ? Item.LIVE : Item.VIDEO;
        String image = first(o.optJSONArray("thumbnails"));
        if (image.isEmpty()) image = first(o.optJSONArray("landscape_images"));
        if (image.isEmpty()) image = first(o.optJSONArray("posterarts"));
        StringBuilder sub = new StringBuilder();
        int year = o.optInt("year", 0);
        if (year > 0) sub.append(year);
        long dur = o.optLong("duration", 0);
        if (kind == Item.SERIES) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append("Series");
        } else if (kind == Item.LIVE) {
            sub.append("Live channel");
        } else if (dur > 0) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(dur / 3600 > 0 ? (dur / 3600) + " h " + (dur % 3600) / 60 + " min" : (dur / 60) + " min");
        }
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null && tags.length() > 0) sub.append(" · ").append(tags.optString(0));
        Item it = new Item(kind, id, o.optString("title"), sub.toString(), image, kind == Item.LIVE ? "LIVE" : "", "");
        it.description = o.optString("description", "");
        // an episode names its series: "Go to show" opens it
        String sid = seriesId(o.optString("series_id", ""));
        if (kind == Item.VIDEO && !sid.isEmpty()) it.channel = new Item(Item.SERIES, sid, "", "Series", "", "", "");
        return it;
    }

    private static String first(JSONArray a) {
        return a == null || a.length() == 0 ? "" : a.optString(0, "");
    }

    @Override
    public Item.Page open(Item item) throws IOException {
        final Item.Page page = new Item.Page();
        String url = CONTENT + "/api/v3/content?content_id=" + item.id + "&" + q();
        try (Http.Response r = Http.open(url, headers(), null, null, true)) {
            JsonScan.scan(new JsonReader(new InputStreamReader(r.body, StandardCharsets.UTF_8)), NONE,
                    new HashSet<>(Arrays.asList("children")), SKIP, new JsonScan.Sink() {
                        @Override
                        public void onValue(String key, String name, JSONObject season) {
                            List<Item> eps = new ArrayList<>();
                            JSONArray ch = season.optJSONArray("children");
                            if (ch != null) for (int i = 0; i < ch.length(); i++) {
                                JSONObject e = ch.optJSONObject(i);
                                if (e == null) continue;
                                Item it = toItem(e.optString("id"), e);
                                if (it != null) {
                                    Item ep = episode(it, e);
                                    if (item.kind == Item.SERIES) ep.channel = seriesTile(item);
                                    eps.add(ep);
                                }
                            }
                            page.shelves.add(new Item.Shelf(season.optString("title", "Episodes"), eps));
                        }
                    });
        }
        if (page.isEmpty()) page.empty = "No episodes are available right now.";
        return page;
    }

    /** Tubi's series ids carry a leading 0 that the series_id field of an episode leaves out. */
    private static String seriesId(String sid) {
        if (sid == null || sid.isEmpty() || sid.equals("0")) return "";
        return sid.startsWith("0") ? sid : "0" + sid;
    }

    /** A copy of a series tile without its own channel link (saved with each episode for Go to show). */
    private static Item seriesTile(Item s) {
        Item c = new Item(Item.SERIES, s.id, s.title, s.subtitle, s.image, "", s.extra);
        c.description = s.description.length() > 300 ? s.description.substring(0, 300) : s.description;
        return c;
    }

    private static final java.util.regex.Pattern EPISODE_TITLE = java.util.regex.Pattern.compile("^(S\\d+:E\\d+|E\\d+ · ).*");

    /** Tubi has no channels or creators; an episode's series is the closest page. Movies and live channels have none. */
    @Override
    public boolean hasChannel(Item it) {
        return it.channel != null || (it.kind == Item.VIDEO && EPISODE_TITLE.matcher(it.title).matches());
    }

    @Override
    public String channelLabel() {
        return "Go to show";
    }

    /** The episode's series tile (title, artwork, synopsis) from Tubi's content API. */
    @Override
    public Item channelOf(Item it) throws IOException {
        if (it.channel != null && !it.channel.title.isEmpty()) return it.channel;
        String sid = it.channel != null ? it.channel.id : null;
        if (sid == null) {
            if (!hasChannel(it)) return null;
            try {
                JSONObject o = new JSONObject(new String(Http.get(CONTENT + "/api/v3/content?content_id=" + it.id + "&" + q(), headers()), StandardCharsets.UTF_8));
                sid = seriesId(o.optString("series_id", ""));
            } catch (JSONException e) {
                throw new IOException("Tubi sent an unreadable title");
            }
            if (sid.isEmpty()) return null;
        }
        try {
            JSONObject o = new JSONObject(new String(Http.get(CONTENT + "/api/v3/content?content_id=" + sid + "&" + q(), headers()), StandardCharsets.UTF_8));
            Item s = toItem(sid, o);
            return s == null ? null : seriesTile(s);
        } catch (JSONException e) {
            throw new IOException("Tubi sent an unreadable series");
        }
    }

    /** Movies and series open the shared title page; live channels play at once. */
    @Override
    public boolean opensDetail(Item item) {
        return item.kind == Item.SERIES || item.kind == Item.VIDEO;
    }

    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile("^S(\\d+):E(\\d+)\\s*-\\s*(.*)$");

    /** "S01:E03 - Title" becomes "E3 · Title" with the running time underneath. */
    private static Item episode(Item it, JSONObject e) {
        java.util.regex.Matcher m = EPISODE.matcher(it.title);
        String title = m.matches() ? "E" + Integer.parseInt(m.group(2)) + " · " + m.group(3) : it.title;
        long dur = e.optLong("duration", 0);
        String sub = dur <= 0 ? "" : dur >= 3600 ? (dur / 3600) + " h " + (dur % 3600) / 60 + " min" : (dur / 60) + " min";
        Item out = new Item(it.kind, it.id, title, sub, it.image, it.badge, it.extra);
        out.description = it.description;
        return out;
    }

    @Override
    public Playback play(Item item) throws IOException {
        String url = CONTENT + "/api/v3/content?content_id=" + item.id + "&" + q() + "&video_resources%5B%5D=hlsv3";
        try {
            JSONObject o = new JSONObject(new String(Http.get(url, headers()), StandardCharsets.UTF_8));
            Playback p = new Playback();
            p.title = o.optString("title", item.title);
            p.subtitle = item.subtitle;
            p.live = item.kind == Item.LIVE || "l".equals(o.optString("type"));
            p.userAgent = Http.DESKTOP_UA;
            Map<String, String> h = new HashMap<>();
            h.put("Origin", "https://tubitv.com");
            h.put("Referer", "https://tubitv.com/");
            p.headers = h;
            JSONArray res = o.optJSONArray("video_resources");
            if (res != null) for (int i = 0; i < res.length() && p.hls == null; i++) {
                JSONObject vr = res.optJSONObject(i);
                if (vr == null || vr.has("license_server")) continue; // DRM variants need a license; the clear one plays
                String m = JsonScan.path(vr, "manifest") == null ? "" : vr.optJSONObject("manifest").optString("url");
                if (!m.isEmpty()) p.hls = m;
            }
            // Tubi's ads are client-side: the stream is the programme alone, and the web player fetches ad pods
            // from Tubi's ad server at these cue points. ClearTV never asks for them; each skipped break is counted.
            JSONArray breaks = JsonScan.path(o, Config.s("tubi.adCuePoints.field", "monetization")) == null ? null
                    : o.optJSONObject(Config.s("tubi.adCuePoints.field", "monetization")).optJSONArray(Config.s("tubi.adCuePoints.key", "cue_points"));
            if (breaks != null) {
                for (int i = 0; i < breaks.length(); i++) {
                    int sec = (int) breaks.optDouble(i, 0);
                    com.goomero.cleartv.BlockStats.record("tubi", String.format(Locale.ROOT, "ad break at %d:%02d not requested",
                            sec / 60, sec % 60), "cue point");
                }
            }
            // Tubi's own intro and end-credit markers (seconds), used for Skip intro and the next-episode countdown
            JSONObject cue = o.optJSONObject(Config.s("tubi.cuepoints.field", "credit_cuepoints"));
            if (cue != null) {
                p.introStartMs = (long) (cue.optDouble(Config.s("tubi.cuepoints.introStart", "intro_start"), 0) * 1000);
                p.introEndMs = (long) (cue.optDouble(Config.s("tubi.cuepoints.introEnd", "intro_end"), 0) * 1000);
                double credits = 0;
                for (String k : Config.list("tubi.cuepoints.credits", "earlycredits_start", "postlude")) {
                    if (credits <= 0) credits = cue.optDouble(k, 0);
                }
                p.creditsMs = (long) (credits * 1000);
                if (p.introEndMs <= p.introStartMs) p.introStartMs = p.introEndMs = 0;
            }
            // Tubi's captions are a separate SRT file per language (the HLS has no text track); English first
            JSONArray subs = o.optJSONArray("subtitles");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject sb = subs.optJSONObject(i);
                    String su = sb == null ? "" : sb.optString("url", "");
                    if (su.isEmpty()) continue;
                    boolean en = "eng".equals(sb.optString("lang_alpha3")) || "English".equalsIgnoreCase(sb.optString("lang"));
                    if (p.captionsUrl == null || en) {
                        // the http address times out on some networks; the same file is served over https
                        p.captionsUrl = su.startsWith("http://") ? "https://" + su.substring(7) : su;
                        p.captionsLabel = sb.optString("lang", "English");
                    }
                    if (en) break;
                }
            }
            if (p.hls == null) {
                if (o.optBoolean("needs_login", false)) throw new IOException("Sign in to Tubi to watch this title.");
                throw new IOException("Tubi has no stream for this title right now.");
            }
            return p;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ----------------------------------------------------------------------------------- tokens

    /** The user token when signed in (refreshed as needed), else an anonymous device token. */
    private synchronized String token() throws IOException {
        long now = System.currentTimeMillis();
        String user = prefs.getString("user_token", null);
        if (user != null) {
            if (now < prefs.getLong("user_expires", 0)) return user;
            String refreshed = refreshUser();
            if (refreshed != null) return refreshed;
        }
        String anon = prefs.getString("anon_token", null);
        if (anon != null && now < prefs.getLong("anon_expires", 0)) return anon;
        return anonymousToken();
    }

    private String refreshUser() throws IOException {
        String rt = prefs.getString("user_refresh", null);
        if (rt == null) return null;
        try {
            Map<String, String> h = baseHeaders();
            h.put("Authorization", "Bearer " + rt);
            JSONObject b = new JSONObject().put("platform", PLATFORM).put("device_id", deviceId());
            JSONObject o = new JSONObject(Http.postJson(ACCOUNT + "/user_device/login/refresh", h, b.toString()));
            String at = o.optString("access_token", "");
            if (at.isEmpty()) return null;
            storeUser(o);
            return at;
        } catch (Http.Error e) {
            if (e.code == 401 || e.code == 403) {
                Log.w(TAG, "user session ended: " + e.body);
                signOut();
                return null;
            }
            throw e;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Origin", "https://tubitv.com");
        h.put("Referer", "https://tubitv.com/");
        return h;
    }

    /**
     * Tubi's anonymous sign-up, as tubitv.com does it: register a SHA-256 challenge for a random verifier, get a
     * signing key, then exchange the verifier for a token in a request signed TUBI-HMAC-SHA256 (keyed with "TUBI"
     * + key, derived through the date and "tubi_request"); the signature parameters travel in the query string.
     */
    private String anonymousToken() throws IOException {
        try {
            byte[] rnd = new byte[16];
            new SecureRandom().nextBytes(rnd);
            String verifier = hex(rnd);
            String challenge = Base64.encodeToString(sha256(verifier.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP)
                    .replace('+', '-').replace('/', '_');
            JSONObject kb = new JSONObject().put("challenge", challenge).put("version", "1.0.0")
                    .put("platform", PLATFORM).put("device_id", deviceId());
            JSONObject key = new JSONObject(Http.postJson(ACCOUNT + "/device/anonymous/signing_key", baseHeaders(), kb.toString()));
            String body = "{\"verifier\":" + JSONObject.quote(verifier) + ",\"id\":" + JSONObject.quote(key.getString("id"))
                    + ",\"platform\":" + JSONObject.quote(PLATFORM) + ",\"device_id\":" + JSONObject.quote(deviceId()) + "}";
            String path = "/device/anonymous/token";
            String bodyHash = hex(sha256(body.getBytes(StandardCharsets.UTF_8)));
            String canonical = "POST\n" + path + "\n\ncontent-type:application/json\n\ncontent-type\n" + bodyHash;
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            String date = f.format(new Date());
            String toSign = "TUBI-HMAC-SHA256\n" + date + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
            byte[] k = concat("TUBI".getBytes(StandardCharsets.UTF_8), Base64.decode(key.getString("key"), Base64.DEFAULT));
            byte[] c = hmac(k, date.substring(0, 8));
            c = hmac(c, "tubi_request");
            String sig = hex(hmac(c, toSign));
            Map<String, String> params = new LinkedHashMap<>();
            params.put("X-Tubi-Algorithm", "TUBI-HMAC-SHA256");
            params.put("X-Tubi-Date", date);
            params.put("X-Tubi-Expires", "30");
            params.put("X-Tubi-SignedHeaders", "content-type");
            params.put("X-Tubi-Signature", sig);
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                qs.append(qs.length() == 0 ? '?' : '&').append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            JSONObject t = new JSONObject(Http.postJson(ACCOUNT + path + qs, baseHeaders(), body));
            String at = t.getString("access_token");
            long exp = Long.parseLong(t.optString("expires_in", "86400"));
            prefs.edit().putString("anon_token", at)
                    .putLong("anon_expires", System.currentTimeMillis() + (exp - 600) * 1000L).apply();
            return at;
        } catch (JSONException | GeneralSecurityException | NumberFormatException e) {
            throw new IOException("Tubi device sign-up failed: " + e.getMessage(), e);
        }
    }

    private static byte[] sha256(byte[] b) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    private static byte[] hmac(byte[] key, String msg) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    // ---------------------------------------------------------------------------------- account

    @Override public int signInKind() { return SIGNIN_CODE; }

    @Override
    public Code startCode() throws IOException {
        try {
            JSONObject b = new JSONObject().put("platform", PLATFORM).put("device_id", deviceId());
            JSONObject o = new JSONObject(Http.postJson(ACCOUNT + "/user_device/code/generate", baseHeaders(), b.toString()));
            return new Code(o.getString("activation_code"), ACTIVATE, o.getString("activation_token"), 5,
                    System.currentTimeMillis() + 15 * 60_000L);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean pollCode(Code c) throws IOException {
        try {
            JSONObject b = new JSONObject().put("activation_token", c.deviceCode).put("platform", PLATFORM)
                    .put("device_id", deviceId());
            JSONObject o = new JSONObject(Http.postJson(ACCOUNT + "/user_device/code/status", baseHeaders(), b.toString()));
            String status = o.optString("status", "");
            if ("pending".equalsIgnoreCase(status)) return false;
            if ("expired".equalsIgnoreCase(status)) throw new IOException("The code expired.");
            if (o.optString("access_token", "").isEmpty()) {
                Log.w(TAG, "activation answer without a token: " + o.names());
                return false;
            }
            storeUser(o);
            String name = o.optString("name", o.optString("email", ""));
            prefs.edit().putString("account", name).apply();
            return true;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private void storeUser(JSONObject o) {
        android.content.SharedPreferences.Editor e = prefs.edit();
        e.putString("user_token", o.optString("access_token"));
        long exp = o.optLong("expires_in", 0);
        if (exp <= 0) exp = o.optLong("access_token_expires_in", 3600);
        e.putLong("user_expires", System.currentTimeMillis() + Math.max(300, exp - 300) * 1000L);
        String rt = o.optString("refresh_token", "");
        if (!rt.isEmpty()) e.putString("user_refresh", rt);
        if (o.has("user_id")) e.putString("user_id", o.optString("user_id"));
        e.apply();
    }

    @Override
    public void refreshSession() throws IOException {
        if (signedIn()) token();
    }

    @Override
    public boolean signedIn() {
        return prefs.getString("user_token", null) != null;
    }

    @Override
    public void signOut() {
        final String tok = prefs.getString("user_token", null);
        prefs.edit().remove("user_token").remove("user_refresh").remove("user_expires").remove("user_id")
                .remove("account").apply();
        if (tok == null) return;
        // tell Tubi too, so the device drops off the account's device list; best effort
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> h = baseHeaders();
                    h.put("Authorization", "Bearer " + tok);
                    Http.postJson(ACCOUNT + "/user_device/logout", h, "{\"platform\":\"" + PLATFORM + "\",\"device_id\":\"" + deviceId() + "\"}");
                } catch (IOException e) {
                    Log.w(TAG, "logout: " + e.getMessage());
                }
            }
        }).start();
    }
}
