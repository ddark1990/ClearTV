package com.goomero.cleartv.tv;

import android.content.Context;
import android.util.JsonReader;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * YouTube through its own TV API (youtubei, the TVHTML5 client the living-room app uses): the same home, topic,
 * Movies & TV, channel and search shelves as YouTube on a TV. Ad slots in those answers are dropped; everything
 * else is shown. Live streams ask the iOS client for an HLS stream (plain H.264/AAC, no DRM) for ClearTV's player;
 * videos plays in YouTube's embed player inside ClearTV (WebActivity).
 * Sign-in is YouTube's TV device code (google.com/device), the tokens kept in this app's private storage.
 */
final class YouTubeSource extends Source {
    private static final String TAG = "CTV.YouTube";
    // endpoints, client versions, OAuth client and parser keys come from the bundled config (Config)
    private static String API, TV_CLIENT, TV_VERSION, IOS_CLIENT, IOS_VERSION, CLIENT_ID, CLIENT_SECRET, DEVICE_CODE_URL,
            TOKEN_URL, SCOPE;
    static String IOS_UA;
    private static Set<String> CAPTURE, SKIP;
    private static final Set<String> NONE = new HashSet<>();
    private static final String SHORTS = "shorts";

    static {
        Config.onChange(new Runnable() {
            @Override
            public void run() {
                API = Config.s("youtube.api", "https://www.youtube.com/youtubei/v1/");
                TV_CLIENT = Config.s("youtube.tvClientName", "TVHTML5");
                TV_VERSION = Config.s("youtube.tvClientVersion", "7.20261015.10.00");
                IOS_CLIENT = Config.s("youtube.iosClientName", "IOS");
                IOS_VERSION = Config.s("youtube.iosClientVersion", "20.10.4");
                IOS_UA = Config.s("youtube.iosUserAgent", "com.google.ios.youtube/{v} (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
                        .replace("{v}", IOS_VERSION);
                // the YouTube TV app's public OAuth client (device flow); it ships in every TV app
                CLIENT_ID = Config.s("youtube.oauthClientId", "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com");
                CLIENT_SECRET = Config.s("youtube.oauthClientSecret", "SboVhoG9s0rNafixCSGGKXAT");
                DEVICE_CODE_URL = Config.s("youtube.deviceCodeUrl", "https://www.youtube.com/o/oauth2/device/code");
                TOKEN_URL = Config.s("youtube.tokenUrl", "https://www.youtube.com/o/oauth2/token");
                SCOPE = Config.s("youtube.oauthScope", "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content");
                CAPTURE = Config.set("youtube.capture", "shelfRenderer", "nextContinuationData");
                // ads (the only thing removed from the answers) and keys a tile does not need
                SKIP = Config.set("youtube.skip", "adSlotRenderer", "inFeedAdLayoutRenderer", "promotedSparklesWebRenderer",
                        "adSlotMetadata", "menu", "trackingParams", "clickTrackingParams", "loggingDirectives",
                        "loggingContext", "onLongPress", "onLongPressCommand", "accessibility", "accessibilityData",
                        "accessibilityContext", "commandMetadata", "onFocusCommand", "reelPlayerOverlayRenderer",
                        "itemPlayback", "watchEndpointSupportedOnesieConfig", "frameworkUpdates", "responseContext",
                        "topbar", "engagementPanels", "playerOverlays");
                // read whatever the bundled config says: the long-feed grids, the signed-in section tabs
                // (Subscriptions' channels, Library's lists), row continuations and the visitor id they need
                Set<String> cap = new HashSet<>(CAPTURE);
                cap.addAll(Arrays.asList("gridRenderer", "tvSecondaryNavRenderer", "horizontalListContinuation",
                        "gridContinuation", "responseContext"));
                CAPTURE = cap;
                Set<String> sk = new HashSet<>(SKIP);
                sk.remove("responseContext");
                SKIP = sk;
            }
        });
    }

    YouTubeSource(Context c) {
        super(c, "youtube");
    }

    /** Videos and live streams preview through the iOS client's clear HLS stream (no ads, no DRM). */
    @Override
    public boolean previewVideo(Item item) {
        return item.kind == Item.LIVE || item.kind == Item.VIDEO || isMovie(item);
    }

    /** A YouTube Movies tile: browseId "MV" + the movie's own video id. */
    private static boolean isMovie(Item item) {
        return item.kind == Item.PAGE && item.id.matches("MV[A-Za-z0-9_-]{11}");
    }

    @Override
    public Playback previewPlayback(Item item) throws IOException {
        Playback p = new Playback();
        p.title = item.title;
        p.subtitle = item.subtitle;
        Item v = isMovie(item) ? new Item(Item.VIDEO, item.id.substring(2), item.title, item.subtitle, item.image,
                item.badge, "") : item;
        iosHls(v, p);
        // on-demand videos: the iOS client often answers without HLS on home connections; the VR client's muxed
        // 360p MP4 (itag 18: plain H.264/AAC, no ads, no DRM) is plenty for a muted preview
        if (p.hls == null && v.kind == Item.VIDEO) {
            // (YouTube may answer LOGIN_REQUIRED here on some home connections; the account's TV sign-in is not
            // accepted by this client, so the preview then falls back to the muted embed player)
            String mp4 = vrProgressive(v.id, false);
            if (mp4 != null) {
                p.hls = mp4;
                p.userAgent = VR_UA;
                p.live = false;
            }
        }
        Log.i(TAG, "preview " + v.id + ": " + (p.hls == null ? "no clear stream (artwork)" : p.hls.contains(".m3u8") || p.hls.contains("manifest") ? "HLS" : "MP4"));
        return p;
    }

    /** Muted embed player for the preview box: the same embed ClearTV plays videos in, ad-blocked the same way. */
    @Override
    public String previewWebPage(Item item) {
        String id = isMovie(item) ? item.id.substring(2) : item.kind == Item.VIDEO ? item.id : null;
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return null;
        return "<!doctype html><html><head><meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;left:0;top:0;width:100%;height:100%;border:0}</style>"
                + "</head><body><iframe allow=\"autoplay; encrypted-media\" referrerpolicy=\"strict-origin-when-cross-origin\" src=\"https://www.youtube.com/embed/"
                + id + "?autoplay=1&mute=1&controls=0&disablekb=1&rel=0&fs=0&playsinline=1&iv_load_policy=3&cc_load_policy=0&modestbranding=1&vq=small"
                + "&origin=https%3A%2F%2Fcom.goomero.cleartv\"></iframe></body></html>";
    }

    private static final String VR_UA = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    /** The VR client's muxed progressive MP4 (itag 18) for a video, or null. */
    private String vrProgressive(String id, boolean auth) {
        try {
            JSONObject client = new JSONObject().put("clientName", "ANDROID_VR").put("clientVersion", "1.62.27")
                    .put("androidSdkVersion", 32).put("hl", "en").put("gl", "US");
            JSONObject body = new JSONObject().put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true)
                    .put("context", new JSONObject().put("client", client));
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", VR_UA);
            h.put("X-Youtube-Client-Name", "28");
            h.put("X-Youtube-Client-Version", "1.62.27");
            String token = auth ? accessToken() : null;
            if (auth && token == null) return null;
            if (token != null) h.put("Authorization", "Bearer " + token);
            JSONObject o = new JSONObject(Http.postJson(API + "player?prettyPrint=false", h, body.toString()));
            JSONArray f = JsonScan.path(o, "streamingData") == null ? null : o.optJSONObject("streamingData").optJSONArray("formats");
            if (f != null) for (int i = 0; i < f.length(); i++) {
                JSONObject x = f.optJSONObject(i);
                if (x != null && !x.optString("url").isEmpty()) return x.optString("url");
            }
            Log.i(TAG, "preview " + id + ": VR client" + (auth ? " (account) " : " ") + (JsonScan.path(o, "playabilityStatus") == null ? "?" : o.optJSONObject("playabilityStatus").optString("status")));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "preview " + id + ": " + e.getMessage());
        }
        return null;
    }

    @Override public String id() { return "youtube"; }
    @Override public String name() { return "YouTube"; }
    @Override public int accent() { return 0xFFFF0033; }
    @Override public int accent2() { return 0xFFCC0000; }
    @Override public String iconKey() { return "www.youtube.com"; }

    @Override
    public List<Nav> navs() {
        List<Nav> l = new ArrayList<>();
        l.add(new Nav("FEtopics", "Home"));
        l.add(new Nav(SHORTS, "Shorts"));
        l.add(new Nav("FEsubscriptions", "Subscriptions"));
        l.add(new Nav("FElibrary", "Library"));
        l.add(new Nav("FEtopics_movies", "Movies & TV"));
        l.add(new Nav("FEstorefront|ogUCKAY%3D", "Free with ads"));
        l.add(new Nav("FEtopics_live", "Live"));
        l.add(new Nav("FEtopics_music", "Music"));
        l.add(new Nav("FEtopics_gaming", "Gaming"));
        l.add(new Nav("FEtopics_news", "News"));
        l.add(new Nav("FEtopics_sports", "Sports"));
        l.add(new Nav("FEtopics_podcasts", "Podcasts"));
        return l;
    }

    // ------------------------------------------------------------------------------------- browse

    @Override
    public Item.Page load(Nav nav) throws IOException {
        boolean personal = nav.id.equals("FEsubscriptions") || nav.id.equals("FElibrary");
        if (personal && !signedIn()) {
            Item.Page p = new Item.Page();
            p.empty = "Sign in to see your " + (nav.id.equals("FElibrary") ? "history, playlists and Watch later." : "subscriptions.");
            return p;
        }
        if (nav.id.equals(SHORTS)) return shorts("CA8=");
        String[] parts = nav.id.split("\\|", 2);
        Item.Page p = browse(parts[0], parts.length > 1 ? parts[1] : null);
        // YouTube's own Home is empty while signed out (it only offers a search box); show the top rows of each
        // topic instead, the closest thing to the TV app's signed-out start page
        if (nav.id.equals("FEtopics") && p.isEmpty()) p = topicsHome();
        if (nav.id.equals("FEtopics_movies") && p.isEmpty()) p = moviesFallback();
        return p;
    }

    /**
     * Movies & TV never ends on an empty page: when the topic answers without rows (a signed-in account, region or
     * a new answer shape), try it signed out, then YouTube's Movies storefront, the YouTube Movies channel and
     * finally a search for free full movies.
     */
    private Item.Page moviesFallback() {
        Object[][] tries = {
                {"browse", "FEtopics_movies", Boolean.FALSE},
                {"browse", "FEstorefront", Boolean.TRUE},
                {"browse", "FEstorefront", Boolean.FALSE},
                {"browse", "UCuVPpxrm2VAgpH3Ktln4HXg", Boolean.FALSE},
                {"search", "free full movies", Boolean.FALSE}};
        for (Object[] t : tries) {
            boolean auth = (Boolean) t[2];
            if (auth && !signedIn()) continue;
            if (!auth && t[1].equals("FEtopics_movies") && !signedIn()) continue;
            try {
                JSONObject extra = new JSONObject().put(t[0].equals("search") ? "query" : "browseId", t[1]);
                Item.Page p = shelves((String) t[0], extra, auth);
                Log.i(TAG, "Movies & TV fallback " + t[1] + (auth ? " (account)" : " (signed out)") + ": "
                        + p.shelves.size() + " rows");
                if (!p.isEmpty()) return p;
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Movies & TV fallback " + t[1] + ": " + e.getMessage());
            }
        }
        Item.Page p = new Item.Page();
        p.empty = "YouTube didn't send any movies right now. Try again in a moment.";
        return p;
    }

    private Item.Page topicsHome() throws IOException {
        final String[] topics = {"FEtopics_music", "FEtopics_live", "FEtopics_gaming", "FEtopics_news",
                "FEtopics_sports", "FEtopics_movies", "FEtopics_podcasts"};
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            List<java.util.concurrent.Future<Item.Page>> parts = new ArrayList<>();
            for (final String t : topics) {
                parts.add(pool.submit(new java.util.concurrent.Callable<Item.Page>() {
                    @Override
                    public Item.Page call() throws IOException {
                        return browse(t, null);
                    }
                }));
            }
            Item.Page out = new Item.Page();
            IOException last = null;
            for (int round = 0; round < 3; round++) {
                for (java.util.concurrent.Future<Item.Page> f : parts) {
                    try {
                        Item.Page p = f.get();
                        int seen = 0;
                        for (Item.Shelf s : p.shelves) {
                            if (s.items.isEmpty()) continue;
                            if (seen++ == round) {
                                out.shelves.add(s);
                                break;
                            }
                        }
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (e.getCause() instanceof IOException) last = (IOException) e.getCause();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
            }
            if (out.isEmpty() && last != null) throw last;
            if (out.isEmpty()) out.empty = "Nothing here yet.";
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Item.Page browse(String browseId, String params) throws IOException {
        JSONObject extra = new JSONObject();
        try {
            extra.put("browseId", browseId);
            if (params != null && !params.isEmpty()) extra.put("params", java.net.URLDecoder.decode(params, "UTF-8"));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return shelves("browse", extra);
    }

    /**
     * The next part of a page: the next shelves, or the next tiles of a long grid (history, a channel list),
     * or the next Shorts. The token carries the visitor id of the answer it came from: YouTube only continues
     * a signed-out feed for the same visitor.
     */
    @Override
    public Item.Page more(String token) throws IOException {
        if (token.startsWith(SHORTS + ":")) return shorts(token.substring(SHORTS.length() + 1));
        return shelves("browse", continuation(token));
    }

    /** The next tiles of a row (YouTube's horizontal list continuation). */
    @Override
    public Item.Shelf moreRow(String token) throws IOException {
        Item.Page p = shelves("browse", continuation(token));
        if (p.shelves.isEmpty()) return null;
        Item.Shelf s = p.shelves.get(0);
        return s.items.isEmpty() ? null : s;
    }

    private static JSONObject continuation(String token) throws IOException {
        String[] t = token.split("\n", 2);
        JSONObject extra = new JSONObject();
        try {
            extra.put("continuation", t[0]);
            if (t.length > 1 && !t[1].isEmpty()) extra.put(VISITOR, t[1]);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return extra;
    }

    /** Key (in a request being built) for the visitor id; tvContext moves it into the client context. */
    private static final String VISITOR = "_visitor";

    /**
     * Shorts, as the TV app's Shorts tab plays them: YouTube's Shorts sequence (video ids, ~10 a page), with
     * each title from YouTube's public oEmbed. Shown as a grid that keeps loading as you scroll down.
     */
    private Item.Page shorts(String params) throws IOException {
        List<String> ids = new ArrayList<>();
        String next = params;
        for (int page = 0; page < 2 && next != null; page++) {
            JSONObject extra = new JSONObject();
            try {
                extra.put("sequenceParams", next);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            String body;
            try (Http.Response r = call("reel/reel_watch_sequence", tvContext(extra), true)) {
                body = new String(Http.readAll(r.body, 2 << 20), StandardCharsets.UTF_8);
            }
            next = null;
            try {
                JSONObject o = new JSONObject(body);
                JSONArray e = o.optJSONArray("entries");
                if (e != null) for (int i = 0; i < e.length(); i++) {
                    JSONObject w = JsonScan.path(e.optJSONObject(i), "command", "reelWatchEndpoint");
                    String id = w == null ? "" : w.optString("videoId");
                    if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
                }
                String c = o.optString("continuation", "");
                if (!c.isEmpty()) next = java.net.URLDecoder.decode(c, "UTF-8");
            } catch (JSONException e) {
                throw new IOException("YouTube sent unreadable Shorts");
            }
        }
        final List<Item> items = new ArrayList<>();
        final String[] titles = new String[ids.size()], authors = new String[ids.size()];
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                final int k = i;
                final String id = ids.get(i);
                jobs.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try (Http.Response r = Http.open("https://www.youtube.com/oembed?format=json&url="
                                + java.net.URLEncoder.encode("https://www.youtube.com/shorts/" + id, "UTF-8"), new HashMap<String, String>(), null, null, true)) {
                            if (r.code != 200) return;
                            JSONObject o = new JSONObject(new String(Http.readAll(r.body, 64 << 10), StandardCharsets.UTF_8));
                            titles[k] = o.optString("title", "");
                            authors[k] = o.optString("author_name", "");
                        } catch (IOException | JSONException ignored) {
                            // no title: the tile is left out rather than shown without one
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : jobs) {
                try {
                    f.get(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // a slow title is skipped
                }
            }
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < ids.size(); i++) {
            if (titles[i] == null || titles[i].isEmpty()) continue;
            Item it = new Item(Item.VIDEO, ids.get(i), titles[i], authors[i] == null ? "" : authors[i],
                    "https://i.ytimg.com/vi/" + ids.get(i) + "/mqdefault.jpg", "Shorts", "");
            items.add(it);
        }
        Item.Page p = new Item.Page();
        p.shelves.add(new Item.Shelf("Shorts", items));
        p.grid = 0;
        p.moreExtendsGrid = true;
        if (next != null) p.more = SHORTS + ":" + next;
        if (items.isEmpty()) p.empty = "No Shorts right now.";
        return p;
    }

    @Override
    public Item.Page search(String query) throws IOException {
        JSONObject extra = new JSONObject();
        try {
            extra.put("query", query);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return shelves("search", extra);
    }

    /** Every video and live tile has a channel (looked up from the video when the tile did not carry it). */
    @Override
    public boolean hasChannel(Item it) {
        return it.channel != null || ((it.kind == Item.VIDEO || it.kind == Item.LIVE) && it.id.matches("[A-Za-z0-9_-]{11}"));
    }

    /**
     * The uploader's channel: the TV player answer's videoDetails (small), else the owner on the web watch page
     * data ("next", videoOwnerRenderer), which also answers for videos the signed-out TV player refuses
     * (LOGIN_REQUIRED). No sign-in needed for either.
     */
    @Override
    public Item channelOf(Item it) throws IOException {
        if (it.channel != null) return it.channel;
        if (!hasChannel(it)) return null;
        String name = it.subtitle.split(" · ")[0].trim();
        try {
            JSONObject body = new JSONObject().put("videoId", it.id).put("contentCheckOk", true).put("racyCheckOk", true);
            String raw;
            try (Http.Response r = call("player", tvContext(body), false)) {
                raw = new String(Http.readAll(r.body, 1 << 20), StandardCharsets.UTF_8);
            }
            JSONObject vd = new JSONObject(raw).optJSONObject("videoDetails");
            String ch = vd == null ? "" : vd.optString("channelId", "");
            if (ch.startsWith("UC")) {
                String author = vd.optString("author", "");
                return channelTile(ch, author.isEmpty() ? name : author);
            }
            JSONObject client = new JSONObject().put("clientName", "WEB").put("clientVersion", WEB_VERSION).put("hl", "en").put("gl", "US");
            JSONObject nb = new JSONObject().put("videoId", it.id).put("context", new JSONObject().put("client", client));
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", Http.DESKTOP_UA);
            h.put("Origin", "https://www.youtube.com");
            try (Http.Response r = Http.open(API + "next?prettyPrint=false", h, nb.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json", true)) {
                raw = new String(Http.readAll(r.body, 8 << 20), StandardCharsets.UTF_8);
            }
            JSONObject owner = findKey(new JSONObject(raw), "videoOwnerRenderer", 0);
            JSONObject be = owner == null ? null : JsonScan.path(owner, "navigationEndpoint", "browseEndpoint");
            ch = be == null ? "" : be.optString("browseId", "");
            if (!ch.startsWith("UC")) {
                Log.i(TAG, "no channel for " + it.id);
                return null;
            }
            String title = JsonScan.text(owner.optJSONObject("title"));
            return channelTile(ch, title.isEmpty() ? name : title);
        } catch (JSONException e) {
            throw new IOException("YouTube sent an unreadable video answer");
        }
    }

    private static final String WEB_VERSION = "2.20261015.00.00";

    private static Item channelTile(String id, String name) {
        return new Item(Item.CHANNEL, id, name.isEmpty() ? "Channel" : name, "", "", "", "");
    }

    /** First object under {@code key}, depth-first. */
    private static JSONObject findKey(Object o, String key, int depth) {
        if (depth > 40) return null;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONObject hit = j.optJSONObject(key);
            if (hit != null) return hit;
            for (java.util.Iterator<String> i = j.keys(); i.hasNext(); ) {
                JSONObject r = findKey(j.opt(i.next()), key, depth + 1);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = findKey(a.opt(i), key, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    @Override
    public Item.Page open(Item item) throws IOException {
        return browse(item.id, item.extra);
    }

    /** Playlists (and mixes) open as the shared title page with their videos in the episode grid. */
    @Override
    public boolean opensDetail(Item item) {
        return item.kind == Item.SERIES || (item.kind == Item.PAGE && item.id.startsWith("VL"));
    }

    @Override
    public Item.Page openDetail(Item item) throws IOException {
        Item.Page p = super.openDetail(item);
        if (p.detail != null && p.detail.play != null) p.detail.playLabel = "Play all";
        // a playlist is one list: keep the grid on its videos even when YouTube splits it into several shelves
        if (!p.seasons.isEmpty()) {
            Item.Shelf all = new Item.Shelf("Videos", new ArrayList<Item>());
            for (Item.Season s : p.seasons) all.items.addAll(s.episodes.items);
            p.seasons.clear();
            p.shelves.clear();
            p.shelves.add(all);
            p.grid = 0;
        }
        return p;
    }

    private Item.Page shelves(String endpoint, JSONObject extra) throws IOException {
        return shelves(endpoint, extra, true);
    }

    private Item.Page shelves(String endpoint, JSONObject extra, boolean auth) throws IOException {
        final Item.Page page = new Item.Page();
        final String[] visitor = {extra.optString(VISITOR, "")};
        final String what = extra.optString("browseId", extra.has("query") ? "search" : "continuation");
        byte[] raw;
        try (Http.Response r = call(endpoint, tvContext(extra), auth)) {
            raw = Http.readAll(r.body, 8 << 20);
        }
        {
            JsonReader jr = new JsonReader(new InputStreamReader(new java.io.ByteArrayInputStream(raw), StandardCharsets.UTF_8));
            JsonScan.scan(jr, CAPTURE, NONE, SKIP, new JsonScan.Sink() {
                @Override
                public void onValue(String key, String name, JSONObject v) {
                    switch (key) {
                        case "responseContext":
                            if (!v.optString("visitorData").isEmpty()) visitor[0] = v.optString("visitorData");
                            return;
                        case "nextContinuationData":
                            page.more = v.optString("continuation", null);
                            return;
                        case "horizontalListContinuation": {
                            // a row's next tiles (moreRow)
                            Item.Shelf s = new Item.Shelf("", items(v.optJSONArray("items"), ""));
                            s.more = nextToken(v.optJSONArray("continuations"));
                            page.shelves.add(s);
                            return;
                        }
                        case "gridContinuation": {
                            // a long grid's next tiles (more): they continue the grid
                            page.shelves.add(new Item.Shelf("", items(v.optJSONArray("items"), "")));
                            page.more = nextToken(v.optJSONArray("continuations"));
                            page.moreExtendsGrid = true;
                            return;
                        }
                        default:
                            collect(key, v, page);
                    }
                }
            });
        }
        // a continuation is only honoured for the visitor whose answer it came from
        if (!visitor[0].isEmpty()) {
            String vd = "\n" + visitor[0];
            if (page.more != null && !page.more.startsWith(SHORTS)) page.more += vd;
            for (Item.Shelf s : page.shelves) if (s.more != null) s.more += vd;
        }
        if (page.isEmpty() && !extra.has("continuation")) deepRows(raw, page, what, auth);
        if (page.isEmpty() && page.empty.isEmpty()) page.empty = "Nothing here yet.";
        return page;
    }

    /**
     * Rows from one captured renderer: a shelf; a grid (a long feed such as History or a playlist list; at most one
     * per page is laid out as the grid); or the signed-in section tabs of Subscriptions and Library (each tab a
     * tile: a channel, History, Watch later, Liked videos, a playlist) with the open tab's own rows.
     */
    private static void collect(String key, JSONObject v, Item.Page page) {
        switch (key) {
            case "shelfRenderer": {
                Item.Shelf s = parseShelf(v);
                if (s != null) page.shelves.add(s);
                break;
            }
            case "gridRenderer": {
                String title = JsonScan.text(JsonScan.path(v, "header", "gridHeaderRenderer", "title"));
                Item.Shelf s = new Item.Shelf(title, items(v.optJSONArray("items"), title));
                if (s.items.isEmpty()) break;
                if (page.grid < 0) {
                    page.shelves.add(s);
                    page.grid = page.shelves.size() - 1;
                    String more = nextToken(v.optJSONArray("continuations"));
                    if (more != null) {
                        page.more = more;
                        page.moreExtendsGrid = true;
                    }
                } else {
                    page.shelves.add(s);
                }
                break;
            }
            case "tvSecondaryNavRenderer": {
                JSONArray sections = v.optJSONArray("sections");
                List<Item> tabs = new ArrayList<>();
                List<JSONObject> contents = new ArrayList<>();
                String heading = "";
                if (sections != null) for (int i = 0; i < sections.length(); i++) {
                    JSONObject sec = JsonScan.path(sections.optJSONObject(i), "tvSecondaryNavSectionRenderer");
                    if (sec == null) continue;
                    if (heading.isEmpty()) heading = JsonScan.text(sec.optJSONObject("title"));
                    JSONArray ts = sec.optJSONArray("tabs");
                    if (ts != null) for (int k = 0; k < ts.length(); k++) {
                        JSONObject tab = JsonScan.path(ts.optJSONObject(k), "tabRenderer");
                        if (tab == null) continue;
                        if (tab.optJSONObject("content") != null) contents.add(tab.optJSONObject("content"));
                        String title = JsonScan.text(tab.optJSONObject("title"));
                        JSONObject th = tab.optJSONObject("thumbnail");
                        String image = th == null ? "" : bestThumb(th.optJSONArray("thumbnails"));
                        Item it = fromCommand(tab.optJSONObject("endpoint"), title, "", image, "", "");
                        if (it != null && !title.isEmpty()) tabs.add(it);
                    }
                }
                if (!tabs.isEmpty()) page.shelves.add(new Item.Shelf(heading.isEmpty() ? "Sections" : heading, tabs));
                for (JSONObject c : contents) walk(c, page, 0);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Last resort for an answer the streaming scan found no rows in: parse it whole and take any list of tiles
     * (tileRenderer / lockupViewModel) wherever it sits, so a new wrapper renderer does not empty the page.
     * Logs the answer's renderer names (never its contents) so an empty page can be diagnosed from logcat.
     */
    private static void deepRows(byte[] raw, Item.Page page, String what, boolean auth) {
        try {
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            deepWalk(o, "", page, 0);
            if (page.isEmpty()) titlePage(o, page);
            if (page.isEmpty()) {
                Map<String, Integer> names = new java.util.TreeMap<>();
                countRenderers(o, names, 0);
                Log.w(TAG, "no rows in " + what + " (" + raw.length + " bytes, " + (auth ? "account" : "signed out")
                        + "); renderers: " + names);
            } else {
                Log.i(TAG, what + ": " + page.shelves.size() + " rows from the deep parse");
            }
        } catch (JSONException e) {
            Log.w(TAG, "no rows in " + what + ": unreadable answer (" + raw.length + " bytes)");
        }
    }

    /**
     * A YouTube Movies / TV title page (an "MV" movie or "SC" show: a channelHeaderRenderer with buttons and no
     * rows): its watch buttons (Play movie, Trailer, episodes) become one row; a title only sold or rented on
     * YouTube says so instead of "Nothing here yet.".
     */
    private static void titlePage(JSONObject o, Item.Page page) {
        JSONObject h = JsonScan.path(o, "contents", "tvBrowseRenderer", "content", "tvSurfaceContentRenderer", "header",
                "channelHeaderRenderer");
        if (h == null) return;
        String title = JsonScan.text(h.optJSONObject("title"));
        String desc = JsonScan.text(JsonScan.path(h, "selectableDescription", "selectableTextRenderer", "compactDescription"));
        String meta = "";
        JSONArray md = h.optJSONArray("metadata");
        if (md != null && md.length() > 0) meta = JsonScan.text(JsonScan.path(md.optJSONObject(0), "lineRenderer") == null ? null
                : JsonScan.path(md.optJSONObject(0).optJSONObject("lineRenderer").optJSONArray("items").optJSONObject(0), "lineItemRenderer", "text"));
        List<Item> items = new ArrayList<>();
        JSONArray buttons = h.optJSONArray("buttons");
        if (buttons != null) for (int i = 0; i < buttons.length(); i++) {
            JSONObject bt = JsonScan.path(buttons.optJSONObject(i), "buttonRenderer");
            JSONObject w = bt == null ? null : JsonScan.path(bt, "navigationEndpoint", "watchEndpoint");
            if (w == null || w.optString("videoId").isEmpty()) continue;
            String label = JsonScan.text(bt.optJSONObject("text"));
            String id = w.optString("videoId");
            Item it = new Item(Item.VIDEO, id, label.isEmpty() ? title : label + ": " + title, meta,
                    "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", "", "");
            it.description = desc;
            items.add(it);
        }
        if (!items.isEmpty()) {
            page.shelves.add(new Item.Shelf(title, items));
        } else {
            page.empty = (title.isEmpty() ? "This title" : title) + " is only available to buy, rent or with a membership on YouTube.";
        }
        Log.i(TAG, "title page " + title + ": " + items.size() + " watch buttons");
    }

    private static void deepWalk(Object o, String title, Item.Page page, int depth) {
        if (depth > 30) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String t = JsonScan.text(j.optJSONObject("title"));
            if (t.isEmpty()) t = title;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.opt(k);
                if (v instanceof JSONArray && looksLikeTiles((JSONArray) v)) {
                    List<Item> items = items((JSONArray) v, t);
                    if (!items.isEmpty()) page.shelves.add(new Item.Shelf(t, items));
                } else if (!SKIP.contains(k)) {
                    deepWalk(v, t, page, depth + 1);
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) deepWalk(a.opt(i), title, page, depth + 1);
        }
    }

    private static boolean looksLikeTiles(JSONArray a) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && (x.has("tileRenderer") || x.has("lockupViewModel"))) return true;
        }
        return false;
    }

    private static void countRenderers(Object o, Map<String, Integer> names, int depth) {
        if (depth > 30) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.endsWith("Renderer") || k.endsWith("ViewModel") || k.equals("alerts") || k.equals("error")) {
                    Integer n = names.get(k);
                    names.put(k, n == null ? 1 : n + 1);
                }
                countRenderers(j.opt(k), names, depth + 1);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) countRenderers(a.opt(i), names, depth + 1);
        }
    }

    /** Finds shelves and grids inside a tab's content (they nest a few renderers deep). */
    private static void walk(Object o, Item.Page page, int depth) {
        if (depth > 12) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.opt(k);
                if ((k.equals("shelfRenderer") || k.equals("gridRenderer")) && v instanceof JSONObject) {
                    collect(k, (JSONObject) v, page);
                } else {
                    walk(v, page, depth + 1);
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), page, depth + 1);
        }
    }

    private static String nextToken(JSONArray continuations) {
        if (continuations == null) return null;
        for (int i = 0; i < continuations.length(); i++) {
            JSONObject n = JsonScan.path(continuations.optJSONObject(i), "nextContinuationData");
            if (n != null && !n.optString("continuation").isEmpty()) return n.optString("continuation");
        }
        return null;
    }

    private JSONObject tvContext(JSONObject extra) throws IOException {
        try {
            JSONObject client = new JSONObject().put("clientName", TV_CLIENT).put("clientVersion", TV_VERSION)
                    .put("hl", "en").put("gl", "US");
            String visitor = (String) extra.remove(VISITOR);
            if (visitor != null && !visitor.isEmpty()) client.put("visitorData", visitor);
            JSONObject ctx = new JSONObject().put("client", client);
            // a brand-account channel picked in the profile picker acts for that channel
            String onBehalf = prefs.getString("on_behalf", "");
            if (!onBehalf.isEmpty()) ctx.put("user", new JSONObject().put("onBehalfOfUser", onBehalf));
            extra.put("context", ctx);
            return extra;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private Http.Response call(String endpoint, JSONObject body, boolean auth) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.TV_UA);
        h.put("Origin", "https://www.youtube.com");
        h.put("Referer", "https://www.youtube.com/tv");
        String token = auth ? accessToken() : null;
        if (token != null) h.put("Authorization", "Bearer " + token);
        return Http.open(API + endpoint + "?prettyPrint=false", h, body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json", true);
    }

    private static Item.Shelf parseShelf(JSONObject shelf) {
        String title = JsonScan.text(JsonScan.path(shelf, "headerRenderer", "shelfHeaderRenderer", "avatarLockup",
                "avatarLockupRenderer", "title"));
        if (title.isEmpty()) title = JsonScan.text(shelf.optJSONObject("title"));
        JSONObject list = JsonScan.path(shelf, "content", "horizontalListRenderer");
        if (list == null) return null;
        Item.Shelf s = new Item.Shelf(title, items(list.optJSONArray("items"), title));
        s.more = nextToken(list.optJSONArray("continuations"));
        return s;
    }

    private static List<Item> items(JSONArray items, String title) {
        List<Item> out = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                Item it = null;
                if (o.has("tileRenderer")) it = parseTile(o.optJSONObject("tileRenderer"));
                else if (o.has("lockupViewModel")) it = parseLockup(o.optJSONObject("lockupViewModel"));
                if (it != null && it.title.isEmpty() && !title.isEmpty()) {
                    // a shelf's lead "Play all" card is titled only by artwork: name it after the shelf
                    Item named = new Item(it.kind, it.id, title, it.subtitle.isEmpty() ? "Play all" : it.subtitle,
                            it.image, it.badge, it.extra);
                    named.description = it.description;
                    it = named;
                }
                if (it != null) out.add(it);
            }
        }
        return out;
    }

    private static Item parseTile(JSONObject t) {
        if (t == null) return null;
        JSONObject md = JsonScan.path(t, "metadata", "tileMetadataRenderer");
        if (md == null || md.optJSONObject("title") == null) {
            // "elevated" music shelves carry the title and lines in an overlay on the thumbnail instead
            JSONArray hov = JsonScan.path(t, "header", "tileHeaderRenderer") == null ? null
                    : JsonScan.path(t, "header", "tileHeaderRenderer").optJSONArray("thumbnailOverlays");
            if (hov != null) for (int i = 0; i < hov.length(); i++) {
                JSONObject m = JsonScan.path(hov.optJSONObject(i), "tileMetadataRenderer");
                if (m != null && m.optJSONObject("title") != null) {
                    md = m;
                    break;
                }
            }
        }
        String title = JsonScan.text(md == null ? null : md.optJSONObject("title"));
        StringBuilder sub = new StringBuilder();
        JSONArray lines = md == null ? null : md.optJSONArray("lines");
        if (lines != null && lines.length() > 0) {
            JSONArray li = JsonScan.path(lines.optJSONObject(0), "lineRenderer") != null
                    ? JsonScan.path(lines.optJSONObject(0), "lineRenderer").optJSONArray("items") : null;
            if (li != null) for (int i = 0; i < li.length(); i++) {
                String s = JsonScan.text(JsonScan.path(li.optJSONObject(i), "lineItemRenderer", "text"));
                if (s.isEmpty() || s.equals("•")) continue;
                if (sub.length() > 0) sub.append(" · ");
                sub.append(s);
            }
        }
        JSONObject header = JsonScan.path(t, "header", "tileHeaderRenderer");
        String image = bestThumb(JsonScan.path(header, "thumbnail") == null ? null
                : header.optJSONObject("thumbnail").optJSONArray("thumbnails"));
        String badge = "";
        JSONArray ov = header == null ? null : header.optJSONArray("thumbnailOverlays");
        if (ov != null) for (int i = 0; i < ov.length(); i++) {
            JSONObject ts = JsonScan.path(ov.optJSONObject(i), "thumbnailOverlayTimeStatusRenderer");
            if (ts != null) {
                badge = JsonScan.text(ts.optJSONObject("text"));
                if ("LIVE".equals(ts.optString("style"))) badge = "LIVE";
            }
        }
        JSONObject cmd = t.optJSONObject("onSelectCommand");
        return fromCommand(cmd, title, sub.toString(), image, badge, t.optString("contentType"));
    }

    private static Item parseLockup(JSONObject l) {
        if (l == null) return null;
        String title = JsonScan.text(JsonScan.path(l, "metadata", "lockupMetadataViewModel", "title"));
        StringBuilder sub = new StringBuilder();
        JSONObject cm = JsonScan.path(l, "metadata", "lockupMetadataViewModel", "metadata", "contentMetadataViewModel");
        JSONArray rows = cm == null ? null : cm.optJSONArray("metadataRows");
        if (rows != null) for (int i = 0; i < rows.length() && i < 2; i++) {
            JSONArray parts = rows.optJSONObject(i) == null ? null : rows.optJSONObject(i).optJSONArray("metadataParts");
            if (parts == null) continue;
            for (int k = 0; k < parts.length(); k++) {
                String s = JsonScan.text(JsonScan.path(parts.optJSONObject(k), "text"));
                if (s.isEmpty()) continue;
                if (sub.length() > 0) sub.append(" · ");
                sub.append(s);
            }
        }
        JSONObject img = JsonScan.path(l, "contentImage", "thumbnailViewModel", "image");
        if (img == null) img = JsonScan.path(l, "contentImage", "collectionThumbnailViewModel", "primaryThumbnail",
                "thumbnailViewModel", "image");
        String image = img == null ? "" : bestThumb(img.optJSONArray("sources"));
        String badge = "";
        JSONObject tv = JsonScan.path(l, "contentImage", "thumbnailViewModel");
        JSONArray ov = tv == null ? null : tv.optJSONArray("overlays");
        if (ov != null) for (int i = 0; i < ov.length(); i++) {
            JSONObject b = JsonScan.path(ov.optJSONObject(i), "thumbnailBottomOverlayViewModel");
            JSONArray badges = b == null ? null : b.optJSONArray("badges");
            if (badges != null && badges.length() > 0) {
                badge = JsonScan.path(badges.optJSONObject(0), "thumbnailBadgeViewModel") == null ? ""
                        : badges.optJSONObject(0).optJSONObject("thumbnailBadgeViewModel").optString("text");
            }
        }
        JSONObject cmd = JsonScan.path(l, "rendererContext", "commandContext", "onTap", "innertubeCommand");
        return fromCommand(cmd, title, sub.toString(), image, badge, l.optString("contentType"));
    }

    private static Item fromCommand(JSONObject cmd, String title, String sub, String image, String badge, String type) {
        if (cmd == null) return null;
        JSONObject w = cmd.optJSONObject("watchEndpoint");
        if (w == null) w = cmd.optJSONObject("reelWatchEndpoint");
        if (w != null && !w.optString("videoId").isEmpty()) {
            boolean live = "LIVE".equalsIgnoreCase(badge);
            return new Item(live ? Item.LIVE : Item.VIDEO, w.optString("videoId"), title, sub, image, badge, "");
        }
        JSONObject b = cmd.optJSONObject("browseEndpoint");
        if (b != null && !b.optString("browseId").isEmpty()) {
            boolean channel = type.contains("CHANNEL") || b.optString("browseId").startsWith("UC");
            return new Item(channel ? Item.CHANNEL : Item.PAGE, b.optString("browseId"), title, sub, image, badge,
                    b.optString("params"));
        }
        JSONObject pl = cmd.optJSONObject("watchPlaylistEndpoint");
        if (pl != null && !pl.optString("playlistId").isEmpty()) {
            return new Item(Item.PAGE, "VL" + pl.optString("playlistId"), title, sub, image, badge, "");
        }
        return null;
    }

    /** The smallest thumbnail at least 320 px wide; i.ytimg.com video stills use the 320x180 "mq" size. */
    private static String bestThumb(JSONArray a) {
        if (a == null || a.length() == 0) return "";
        String best = null;
        int bestW = Integer.MAX_VALUE;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            int w = t.optInt("width", 0);
            if (w >= 320 && w < bestW) {
                bestW = w;
                best = t.optString("url");
            }
        }
        if (best == null) best = a.optJSONObject(a.length() - 1).optString("url");
        if (best.startsWith("//")) best = "https:" + best;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https://i\\.ytimg\\.com/(vi|vi_webp)/([^/]+)/[^?]+")
                .matcher(best);
        if (m.find()) best = "https://i.ytimg.com/vi/" + m.group(2) + "/mqdefault.jpg";
        return best;
    }

    // ----------------------------------------------------------------------------------- playback

    @Override
    public Playback play(Item item) throws IOException {
        Playback p = new Playback();
        p.title = item.title;
        p.subtitle = item.subtitle;
        // anything that isn't a clear HLS stream plays in YouTube's embed player (WebActivity)
        p.webUrl = "https://www.youtube.com/watch?v=" + item.id;
        p.webTvUa = false;
        // the iOS client still answers live streams with HLS; for videos it now offers only SABR, so skip the call
        if (item.kind != Item.LIVE) return p;
        iosHls(item, p);
        return p;
    }

    /** Asks the iOS client for the clear HLS stream; sets p.hls when YouTube offers one. */
    private void iosHls(Item item, Playback p) throws IOException {
        try {
            JSONObject client = new JSONObject().put("clientName", IOS_CLIENT).put("clientVersion", IOS_VERSION)
                    .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2").put("osName", "iPhone")
                    .put("osVersion", "18.3.2.22D82").put("hl", "en").put("gl", "US");
            JSONObject body = new JSONObject().put("videoId", item.id).put("contentCheckOk", true).put("racyCheckOk", true)
                    .put("context", new JSONObject().put("client", client));
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", IOS_UA);
            h.put("X-Youtube-Client-Name", "5");
            h.put("X-Youtube-Client-Version", IOS_VERSION);
            JSONObject o = new JSONObject(Http.postJson(API + "player?prettyPrint=false", h, body.toString()));
            String status = JsonScan.path(o, "playabilityStatus") == null ? "" : o.optJSONObject("playabilityStatus").optString("status");
            JSONObject sd = o.optJSONObject("streamingData");
            String hls = sd == null ? null : sd.optString("hlsManifestUrl", null);
            JSONObject vd = o.optJSONObject("videoDetails");
            if (vd != null) {
                p.title = vd.optString("title", p.title);
                p.subtitle = vd.optString("author", p.subtitle);
                p.live = vd.optBoolean("isLive", false) || vd.optBoolean("isLiveContent", false) && item.kind == Item.LIVE;
            }
            if ("OK".equals(status) && hls != null && !hls.isEmpty()) {
                p.hls = hls;
                p.userAgent = IOS_UA;
            } else {
                Log.i(TAG, "no clear stream for " + item.id + " (" + status + "); using the embed player");
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------------------------ account

    @Override public int signInKind() { return SIGNIN_CODE; }

    @Override
    public String codeHelp() {
        return "On your phone or computer, go to";
    }

    @Override
    public Code startCode() throws IOException {
        String dev = prefs.getString("device_id", null);
        if (dev == null) {
            dev = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", dev).apply();
        }
        try {
            JSONObject b = new JSONObject().put("client_id", CLIENT_ID)
                    .put("scope", SCOPE)
                    .put("device_id", dev).put("device_model", "ytlr::");
            JSONObject o = new JSONObject(Http.postJson(DEVICE_CODE_URL, ytHeaders(), b.toString()));
            return new Code(o.getString("user_code"), o.optString("verification_url", "https://www.google.com/device"),
                    o.getString("device_code"), o.optInt("interval", 5),
                    System.currentTimeMillis() + o.optInt("expires_in", 1800) * 1000L);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean pollCode(Code c) throws IOException {
        try {
            JSONObject b = new JSONObject().put("client_id", CLIENT_ID).put("client_secret", CLIENT_SECRET)
                    .put("code", c.deviceCode).put("grant_type", "http://oauth.net/grant_type/device/1.0");
            JSONObject o = new JSONObject(Http.postJson(TOKEN_URL, ytHeaders(), b.toString()));
            String err = o.optString("error", "");
            if (err.equals("authorization_pending") || err.equals("slow_down")) return false;
            if (!err.isEmpty()) throw new IOException(err.equals("expired_token") ? "The code expired." : err);
            storeTokens(o);
            fetchAccountName();
            return true;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static Map<String, String> ytHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.TV_UA);
        h.put("Origin", "https://www.youtube.com");
        h.put("Referer", "https://www.youtube.com/tv");
        return h;
    }

    private void storeTokens(JSONObject o) {
        android.content.SharedPreferences.Editor e = prefs.edit();
        e.putString("access_token", o.optString("access_token"));
        e.putLong("expires_at", System.currentTimeMillis() + (o.optInt("expires_in", 3600) - 120) * 1000L);
        String rt = o.optString("refresh_token", "");
        if (!rt.isEmpty()) e.putString("refresh_token", rt);
        e.apply();
    }

    /** A valid access token (refreshed when it is about to expire), or null when signed out. */
    private synchronized String accessToken() throws IOException {
        String rt = prefs.getString("refresh_token", null);
        if (rt == null) return null;
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return prefs.getString("access_token", null);
        try {
            JSONObject b = new JSONObject().put("client_id", CLIENT_ID).put("client_secret", CLIENT_SECRET)
                    .put("refresh_token", rt).put("grant_type", "refresh_token");
            JSONObject o = new JSONObject(Http.postJson(TOKEN_URL, ytHeaders(), b.toString()));
            if (o.has("error")) {
                Log.w(TAG, "token refresh: " + o.optString("error"));
                if ("invalid_grant".equals(o.optString("error"))) signOut();
                return null;
            }
            storeTokens(o);
            return o.optString("access_token");
        } catch (Http.Error e) {
            if (e.code == 400 || e.code == 401) {
                Log.w(TAG, "refresh rejected; signing out: " + e.body);
                signOut();
                return null;
            }
            throw e;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private void fetchAccountName() {
        try (Http.Response r = call("account/accounts_list", tvContext(new JSONObject()), true)) {
            final String[] name = {""}, photo = {""};
            JsonReader jr = new JsonReader(new InputStreamReader(r.body, StandardCharsets.UTF_8));
            JsonScan.scan(jr, new HashSet<>(Arrays.asList("accountItemRenderer", "accountItem")), NONE, SKIP,
                    new JsonScan.Sink() {
                        @Override
                        public void onValue(String key, String n, JSONObject v) {
                            if (name[0].isEmpty()) {
                                name[0] = JsonScan.text(v.optJSONObject("accountName"));
                                // the account picture: the largest thumbnail of accountPhoto
                                org.json.JSONArray th = v.optJSONObject("accountPhoto") == null ? null
                                        : v.optJSONObject("accountPhoto").optJSONArray("thumbnails");
                                if (th != null && th.length() > 0) {
                                    String u = th.optJSONObject(th.length() - 1).optString("url", "");
                                    photo[0] = u.startsWith("//") ? "https:" + u : u;
                                }
                            }
                        }
                    });
            prefs.edit().putString("account", name[0]).putString("avatar", photo[0]).apply();
        } catch (IOException e) {
            Log.w(TAG, "account name: " + e.getMessage());
        }
    }

    @Override
    public void refreshSession() throws IOException {
        if (accessToken() != null && prefs.getString("avatar", "").isEmpty()) fetchAccountName();
    }

    /** The account's watch history (FEhistory), newest first. */
    @Override
    public List<Item> history() throws IOException {
        if (!signedIn()) return new java.util.ArrayList<>();
        Item.Page p = load(new Nav("FEhistory", "History"));
        for (Item.Shelf sh : p.shelves) if (!sh.items.isEmpty()) return sh.items;
        return new java.util.ArrayList<>();
    }

    @Override
    public boolean signedIn() {
        return prefs.getString("refresh_token", null) != null;
    }

    @Override
    public void signOut() {
        prefs.edit().remove("refresh_token").remove("access_token").remove("expires_at").remove("account").remove("avatar")
                .remove("on_behalf").apply();
        forgetProfile();
    }

    // ---------------------------------------------------------------------------------- profiles

    @Override public boolean hasProfiles() { return true; }

    /**
     * The account's channels as profiles: its own and any brand-account channels it manages, from the TV app's
     * account switcher (account/accounts_list). A brand channel is used through context.user.onBehalfOfUser.
     */
    @Override
    public List<Profile> profiles() throws IOException {
        final List<Profile> out = new ArrayList<>();
        if (accessToken() == null) return out;
        final String current = prefs.getString("on_behalf", "");
        try (Http.Response r = call("account/accounts_list", tvContext(new JSONObject()), true)) {
            JsonReader jr = new JsonReader(new InputStreamReader(r.body, StandardCharsets.UTF_8));
            JsonScan.scan(jr, new HashSet<>(Arrays.asList("accountItemRenderer", "accountItem")), NONE, SKIP,
                    new JsonScan.Sink() {
                        @Override
                        public void onValue(String key, String n, JSONObject v) {
                            String name = JsonScan.text(v.optJSONObject("accountName"));
                            String photo = "";
                            org.json.JSONArray th = v.optJSONObject("accountPhoto") == null ? null
                                    : v.optJSONObject("accountPhoto").optJSONArray("thumbnails");
                            if (th != null && th.length() > 0) {
                                String u = th.optJSONObject(th.length() - 1).optString("url", "");
                                photo = u.startsWith("//") ? "https:" + u : u;
                            }
                            String pageId = "";
                            org.json.JSONArray tokens = JsonScan.path(v, "serviceEndpoint", "selectActiveIdentityEndpoint") == null ? null
                                    : JsonScan.path(v, "serviceEndpoint", "selectActiveIdentityEndpoint").optJSONArray("supportedTokens");
                            if (tokens != null) for (int i = 0; i < tokens.length(); i++) {
                                JSONObject pt = tokens.optJSONObject(i) == null ? null : tokens.optJSONObject(i).optJSONObject("pageIdToken");
                                if (pt != null && !pt.optString("pageId").isEmpty()) pageId = pt.optString("pageId");
                            }
                            boolean active = pageId.equals(current) || (current.isEmpty() && v.optBoolean("isSelected") && pageId.isEmpty());
                            String id = pageId.isEmpty() ? "main" : pageId;
                            for (Profile o : out) if (o.id.equals(id)) return;
                            out.add(new Profile(id, name, photo, false, active, "", pageId));
                        }
                    });
        }
        Log.i(TAG, "profiles: " + out.size());
        return out;
    }

    @Override
    public void selectProfile(Profile p, String pin) {
        prefs.edit().putString("on_behalf", p.data).putString("account", p.name).putString("avatar", p.avatar).apply();
        rememberProfile(p);
    }
}
