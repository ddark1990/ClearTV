package com.goomero.cleartv.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Paramount+ through the JSON API its own TV apps use (apps-api): the home carousels, Movies and Shows storefronts,
 * search, show seasons and episodes, related shows, live channels, and, once signed in, Keep Watching and My List.
 * Sign-in is Paramount+'s activation code (paramountplus.com/activate/androidtv); the session is the account's cookie,
 * also handed to the web player. Episodes and films are Widevine DASH and play in ClearTV's own player from the
 * ad-free rendition, licensed with the account's DRM session (Paramount+'s web player is the fallback, with ad
 * requests blocked and stitched ad breaks muted and sped up); trailers, Extras and hover previews are clear HLS.
 */
final class ParamountSource extends Source {
    private static final String TAG = "CTV.Paramount";
    static final String BASE = "https://www.paramountplus.com";
    private static String API, AT, LOCALE, UA, IMG, THUMB, PREVIEW, ACTIVATE, PLATFORM, REGION, SMIL, DAI, DRM_SESSION, DASH_SMIL, STREAMS;

    static {
        Config.onChange(new Runnable() {
            @Override
            public void run() {
                API = Config.s("paramount.api", BASE + "/apps-api");
                AT = Config.s("paramount.at", "");
                LOCALE = Config.s("paramount.locale", "en-us");
                UA = Config.s("paramount.ua", Http.DESKTOP_UA);
                PLATFORM = Config.s("paramount.platform", "androidtv");
                REGION = Config.s("paramount.region", "US");
                IMG = Config.s("paramount.images", "https://wwwimage-us.pplusstatic.com/thumbnails/photos/w400/");
                THUMB = Config.s("paramount.thumbs", "https://thumbnails.cbsig.net/_x/w400/");
                PREVIEW = Config.s("paramount.preview", "https://splice.paramountplus.com/api/sas/{type}/{id}/US/en-us/previewhls");
                ACTIVATE = Config.s("paramount.activateUrl", "paramountplus.com/activate/androidtv");
                SMIL = Config.s("paramount.smil", "https://link.theplatform.com/s/dJ5BDC/media/guid/2198311517/{id}"
                        + "?format=SMIL&formats=M3U,MPEG4&assetTypes=HLS_TA&manifest=m3u&mbr=true");
                DAI = Config.s("paramount.dai", "https://dai.google.com/linear/v1/hls/event/{key}/stream");
                DRM_SESSION = Config.s("paramount.drmSession", "/v3.1/{platform}/irdeto-control/session-token.json");
                DASH_SMIL = Config.s("paramount.dashSmil", "https://link.theplatform.com/s/dJ5BDC/media/guid/2198311517/{id}"
                        + "?format=SMIL&formats=MPEG-DASH&assetTypes={types}");
                STREAMS = Config.s("paramount.streams", "/v3.1/{platform}/video/streams.json");
            }
        });
    }

    ParamountSource(Context c) {
        super(c, "paramount");
    }

    @Override public String id() { return "paramount"; }
    @Override public String name() { return "Paramount+"; }
    @Override public int accent() { return 0xFF0064FF; }
    @Override public int accent2() { return 0xFF0A1E5A; }
    @Override public String iconKey() { return "www.paramountplus.com"; }

    @Override
    public List<Nav> navs() {
        List<Nav> l = new ArrayList<>();
        l.add(new Nav("home", "Home"));
        l.add(new Nav("movies", "Movies"));
        l.add(new Nav("shows", "Shows"));
        l.add(new Nav("live", "Live TV"));
        l.add(new Nav("mine", "My Stuff"));
        return l;
    }

    // ---------------------------------------------------------------------------------------- http

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        String c = prefs.getString("cookies", "");
        if (!c.isEmpty()) h.put("Cookie", c);
        return h;
    }

    /** apps-api URL for a path (which may carry its own query), with the app token and locale. */
    private static String url(String path, String query) {
        StringBuilder u = new StringBuilder(path.startsWith("http") ? path : API + path);
        u.append(u.indexOf("?") >= 0 ? '&' : '?').append("at=").append(enc(AT)).append("&locale=").append(LOCALE);
        if (query != null && !query.isEmpty()) u.append('&').append(query);
        return u.toString();
    }

    private JSONObject get(String path, String query) throws IOException {
        try {
            return new JSONObject(new String(Http.get(url(path, query), headers()), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Paramount+ sent an unreadable answer", e);
        }
    }

    private Http.Response post(String path, Map<String, String> form) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return Http.open(url(path, null), headers(), b.toString().getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", true);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------------------------ mapping

    private static String img(String path) {
        if (path == null || path.isEmpty() || "null".equals(path)) return "";
        if (path.startsWith("http")) return path;
        return IMG + (path.startsWith("files/") ? path.substring(6) : path);
    }

    /** Episode stills come full size (1920 px); the thumbnail service gives 400 px. */
    private static String still(String u) {
        if (u == null || u.isEmpty()) return "";
        String host = "https://thumbnails.cbsig.net/";
        if (u.startsWith(host) && !u.contains("/_x/")) return THUMB + u.substring(host.length());
        return u;
    }

    private static String firstAsset(JSONObject assets, String... keys) {
        if (assets == null) return "";
        for (String k : keys) {
            String v = assets.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return img(v);
        }
        return "";
    }

    private static final String[] SHOW_ART = {"filepath_tile_wide_xr", "filepath_show_hero_landscape",
            "filepath_video_endcard_show_image", "filepath_apple_airplay", "filepath_show_hero_regular",
            "filepath_show_page_header", "filepath_show_poster"};
    private static final String[] MOVIE_ART = {"filepath_movie_keep_watching", "filepath_movie_hero_regular",
            "filepath_movie_hero", "filepath_movie_poster"};

    private static String preview(String type, String id) {
        return PREVIEW.replace("{type}", type).replace("{id}", id);
    }

    private static String minutes(long sec) {
        if (sec <= 0) return "";
        return sec >= 3600 ? (sec / 3600) + " h " + (sec % 3600) / 60 + " min" : Math.max(1, sec / 60) + " min";
    }

    private static String str(JSONObject o, String k) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v);
        return "null".equals(s) || "None".equals(s) ? "" : s;
    }

    private static String genre(JSONObject o) {
        JSONArray g = o.optJSONArray("unifiedGenre");
        if (g != null && g.length() > 0) return g.optString(0);
        String s = str(o, "genre");
        if (s.isEmpty()) s = str(o, "category");
        return s;
    }

    /** A show tile (carousel, show group, related list, search, show detail). */
    private Item show(String id, String title, String about, JSONObject assets, String sub, String previewUrl) {
        if (id.isEmpty() || title.isEmpty()) return null;
        Item it = new Item(Item.SERIES, id, title, sub, firstAsset(assets, SHOW_ART), "",
                previewUrl.isEmpty() ? preview("show", id) : previewUrl);
        it.description = about;
        return it;
    }

    /** A film tile; {@code extra} is its clear preview (trailer) stream. */
    private Item movie(String contentId, String title, String about, JSONObject assets, String sub, String previewUrl) {
        if (contentId.isEmpty() || title.isEmpty()) return null;
        Item it = new Item(Item.VIDEO, contentId, title, sub, firstAsset(assets, MOVIE_ART), "",
                previewUrl.isEmpty() ? preview("movie", contentId) : previewUrl);
        it.description = about;
        return it;
    }

    /** Any tile the API sends in a row: shows, films (plain or wrapped as trending), episodes, search terms. */
    private Item toItem(JSONObject o) {
        if (o == null) return null;
        JSONObject wrapped = o.optJSONObject("content");
        if (wrapped != null && o.has("content_type")) return toItem(wrapped);
        String termType = str(o, "term_type");
        if (termType.equals("movie")) {
            return movie(str(o, "movie_content_id"), str(o, "title"), "", o.optJSONObject("movieAssets"), "Movie",
                    str(o, "videoPreviewURL"));
        }
        if (termType.equals("show")) {
            return show(str(o, "show_id"), str(o, "title"), "", o.optJSONObject("showAssets"),
                    str(o, "tv_age_rating"), str(o, "videoPreviewURL"));
        }
        if (o.has("relatedShowId")) {
            return show(str(o, "relatedShowId"), str(o, "relatedShowTitle"), str(o, "relatedShowAboutCopy"),
                    o.optJSONObject("showAssets"), str(o, "relatedShowRating"), "");
        }
        JSONObject mc = o.optJSONObject("movieContent");
        if (mc != null || "movie".equals(str(o, "contentType")) || o.has("movieAssets")) {
            String cid = str(o, "contentId");
            if (cid.isEmpty()) cid = str(o, "content_id");
            if (cid.isEmpty() && mc != null) cid = str(mc, "contentId");
            String about = mc != null ? str(mc, "description") : str(o, "description");
            long dur = mc != null ? mc.optLong("duration", 0) : 0;
            String sub = join(genre(o), minutes(dur));
            return movie(cid, str(o, "title"), about, o.optJSONObject("movieAssets"), sub, str(o, "videoPreviewURL"));
        }
        if (o.has("mediaType") || o.has("seriesTitle") && o.has("contentId")) return episode(o);
        String sid = str(o, "showId");
        if (sid.isEmpty()) sid = str(o, "show_id");
        if (!sid.isEmpty()) {
            String title = str(o, "showTitle");
            if (title.isEmpty()) title = str(o, "title");
            String about = str(o, "about");
            return show(sid, title, about, o.optJSONObject("showAssets"), genre(o), str(o, "videoPreviewURL"));
        }
        return null;
    }

    /** An episode (or a film from a video feed): "E3 · Title" with "S2 · 22 min" underneath, its show as Go to show. */
    private Item episode(JSONObject e) {
        String cid = str(e, "contentId");
        if (cid.isEmpty()) return null;
        String type = str(e, "mediaType");
        String label = str(e, "label");
        if (label.isEmpty()) label = str(e, "title");
        long dur = e.optLong("duration", 0);
        String sid = str(e, "cbsShowId");
        String series = str(e, "seriesTitle");
        if ("Movie".equalsIgnoreCase(type)) {
            Item m = new Item(Item.VIDEO, cid, label, minutes(dur), still(str(e, "thumbnail")), "", preview("movie", cid));
            m.description = str(e, "description");
            return m;
        }
        String ep = str(e, "episodeNum");
        String season = str(e, "seasonNum");
        boolean full = e.optBoolean("fullEpisode", "Full Episode".equalsIgnoreCase(type));
        String title = full && !ep.isEmpty() ? "E" + ep + " · " + label : label;
        String sub = join(full && !season.isEmpty() ? "S" + season : type, minutes(dur));
        // clips, recaps and "Next on" teasers are free and unprotected: they play without an account
        boolean free = "FREE".equalsIgnoreCase(str(e, "subscriptionLevel")) && !e.optBoolean("isProtected", true);
        Item it = new Item(Item.VIDEO, cid, title, sub, still(str(e, "thumbnail")), free ? "FREE" : "", "");
        it.description = str(e, "description");
        if (!sid.isEmpty() && !"0".equals(sid)) {
            it.channel = new Item(Item.SERIES, sid, series, "", "", "", preview("show", sid));
        }
        return it;
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " · " + b;
    }

    private List<Item> items(JSONArray a) {
        List<Item> out = new ArrayList<>();
        if (a == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            Item it = toItem(a.optJSONObject(i));
            if (it != null && seen.add(it.kind + ":" + it.id)) out.add(it);
        }
        return out;
    }

    // -------------------------------------------------------------------------------------- browse

    @Override
    public boolean opensDetail(Item item) {
        // shows and films open their title page; episodes (they know their show) and trailers play at once
        return item.kind == Item.SERIES || item.kind == Item.VIDEO && item.channel == null && !item.id.startsWith("preview:");
    }

    @Override
    public Item.Page load(Nav nav) throws IOException {
        switch (nav.id) {
            case "movies":
                return movies();
            case "shows":
                return shows();
            case "live":
                return live();
            case "mine":
                return mine();
            default:
                return home(0);
        }
    }

    private static final int HOME_ROWS = 12;

    /** The home screen as the app builds it: the configurator's rows, each carousel fetched in parallel. */
    private Item.Page home(int from) throws IOException {
        JSONObject cfg = get("/v3.0/" + PLATFORM + "/home/configurator.json",
                "platformType=" + PLATFORM + "&_clientRegion=" + REGION + "&rows=40&start=0");
        JSONArray rows = cfg.optJSONArray("config");
        List<String[]> want = new ArrayList<>();
        int idx = 0, next = -1;
        boolean liveRow = false;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String model = str(r, "model");
            String api = str(r, "apiUrl");
            boolean usable = model.equals("showMovieHybrid") && !api.isEmpty() || model.equals("onnow");
            if (!usable) continue;
            if (idx++ < from) continue;
            if (want.size() >= HOME_ROWS) {
                next = idx - 1;
                break;
            }
            if (model.equals("onnow")) liveRow = true;
            want.add(new String[]{model, str(r, "title"), api});
        }
        Item.Page p = new Item.Page();
        if (from == 0 && signedIn()) {
            List<Item> kw = keepWatching();
            if (!kw.isEmpty()) p.shelves.add(mixed(new Item.Shelf("Keep Watching", kw)));
        }
        List<Item.Shelf> got = parallel(want, liveRow);
        p.shelves.addAll(got);
        if (next >= 0) p.more = "home:" + next;
        if (p.isEmpty()) p.empty = "Paramount+ returned no rows right now. Try again later.";
        return p;
    }

    private List<Item.Shelf> parallel(List<String[]> rows, final boolean live) {
        final Item.Shelf[] out = new Item.Shelf[rows.size()];
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                final int n = i;
                final String[] r = rows.get(i);
                fs.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws IOException {
                        if (r[0].equals("onnow")) {
                            out[n] = new Item.Shelf(r[1].isEmpty() ? "On Now" : r[1], channels(12));
                        } else {
                            out[n] = carousel(r[1], r[2], 0);
                        }
                        return null;
                    }
                }));
            }
            long until = android.os.SystemClock.uptimeMillis() + 15_000;
            for (Future<?> f : fs) {
                try {
                    f.get(Math.max(1, until - android.os.SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    Log.w(TAG, "row failed: " + e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<Item.Shelf> l = new ArrayList<>();
        for (Item.Shelf s : out) if (s != null && !s.items.isEmpty()) l.add(s);
        return l;
    }

    /** One home carousel from {@code start}; its next tiles are another start. */
    private Item.Shelf carousel(String title, String api, int start) throws IOException {
        String path = api.replaceAll("([?&])start=\\d+", "$1start=" + start);
        if (!path.contains("start=")) path += (path.contains("?") ? "&" : "?") + "start=" + start + "&rows=20";
        JSONObject o = get(path, null);
        Item.Shelf s = new Item.Shelf(title, items(o.optJSONArray("carousel")));
        int total = o.optInt("total", 0), count = o.optInt("count", s.items.size());
        if (start + count < total && count > 0) s.more = "row:" + (start + count) + ":" + api;
        return s;
    }

    @Override
    public Item.Page more(String token) throws IOException {
        if (token.startsWith("home:")) return home(Integer.parseInt(token.substring(5)));
        return null;
    }

    @Override
    public Item.Shelf moreRow(String token) throws IOException {
        if (!token.startsWith("row:")) return null;
        int c = token.indexOf(':', 4);
        return carousel("", token.substring(c + 1), Integer.parseInt(token.substring(4, c)));
    }

    /** Movies: Trending, then a row per genre (the app's genre list), fetched in parallel. */
    private Item.Page movies() throws IOException {
        final List<Item.Shelf> rows = new ArrayList<>();
        final List<String[]> genres = new ArrayList<>();
        JSONArray g = get("/v3.0/" + PLATFORM + "/movies/genre.json", null).optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length() && genres.size() < 12; i++) {
            JSONObject x = g.optJSONObject(i);
            if (x != null && !str(x, "slug").isEmpty()) genres.add(new String[]{str(x, "slug"), str(x, "title")});
        }
        final Item.Shelf[] out = new Item.Shelf[genres.size() + 1];
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> fs = new ArrayList<>();
            fs.add(pool.submit(new Callable<Void>() {
                @Override
                public Void call() throws IOException {
                    out[0] = new Item.Shelf("Trending Movies", items(get("/v3.0/" + PLATFORM + "/movies/trending.json", null).optJSONArray("trending")));
                    return null;
                }
            }));
            for (int i = 0; i < genres.size(); i++) {
                final int n = i + 1;
                final String[] gr = genres.get(i);
                fs.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws IOException {
                        out[n] = new Item.Shelf(gr[1], genreMovies(gr[0], 24));
                        return null;
                    }
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Log.w(TAG, "movies row failed: " + e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Item.Page p = new Item.Page();
        for (Item.Shelf s : out) if (s != null && !s.items.isEmpty()) p.shelves.add(s);
        if (p.isEmpty()) p.empty = "Paramount+ returned no movies right now. Try again later.";
        return p;
    }

    private List<Item> genreMovies(String slug, int rows) throws IOException {
        return items(get("/v3.0/" + PLATFORM + "/movies.json", "genre=" + enc(slug) + "&start=0&rows=" + rows
                + "&includeContentInfo=true&platformType=" + PLATFORM).optJSONArray("movies"));
    }

    /** Shows: the app's show groups (Originals, Dramas, ...), a row each. */
    private Item.Page shows() throws IOException {
        JSONArray g = get("/v2.0/" + PLATFORM + "/shows/groups.json", "includeAllShowGroups=true").optJSONArray("showGroups");
        final List<String[]> groups = new ArrayList<>();
        if (g != null) for (int i = 0; i < g.length() && groups.size() < 14; i++) {
            JSONObject x = g.optJSONObject(i);
            if (x == null) continue;
            String title = str(x, "title");
            if (title.equalsIgnoreCase("All Shows")) continue;
            groups.add(new String[]{str(x, "id"), title});
        }
        final Item.Shelf[] out = new Item.Shelf[groups.size()];
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                final int n = i;
                final String[] gr = groups.get(i);
                fs.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws IOException {
                        JSONObject o = get("/v2.0/" + PLATFORM + "/shows/group/" + gr[0] + ".json",
                                "includeAllShowGroups=true&begin=0&rows=30").optJSONObject("group");
                        out[n] = new Item.Shelf(gr[1], items(o == null ? null : o.optJSONArray("showGroupItems")));
                        return null;
                    }
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Log.w(TAG, "shows row failed: " + e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Item.Page p = new Item.Page();
        for (Item.Shelf s : out) if (s != null && !s.items.isEmpty()) p.shelves.add(s);
        if (p.isEmpty()) p.empty = "Paramount+ returned no shows right now. Try again later.";
        return p;
    }

    /** Live TV: every channel with what is on now; a channel plays in the web player. */
    private Item.Page live() throws IOException {
        Item.Page p = new Item.Page();
        List<Item> ch = channels(0);
        if (ch.isEmpty()) {
            p.empty = "Paramount+ lists no live channels right now.";
            return p;
        }
        p.shelves.add(new Item.Shelf("Live TV", ch));
        p.grid = 0;
        return p;
    }

    private List<Item> channels(int max) throws IOException {
        JSONArray a = get("/v3.0/" + PLATFORM + "/live/channels.json", "start=0&_clientRegion=" + REGION + "&showListing=true")
                .optJSONArray("channels");
        List<Item> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length() && (max <= 0 || out.size() < max); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null || str(c, "slug").isEmpty()) continue;
            String now = "", thumb = "";
            JSONArray cur = c.optJSONArray("currentListing");
            if (cur != null && cur.length() > 0) {
                JSONObject l = cur.optJSONObject(0);
                if (l != null) {
                    now = str(l, "title");
                    thumb = img(str(l, "filePathThumb"));
                }
            }
            if (thumb.isEmpty()) thumb = img(str(c, "filepathFallbackImage"));
            if (thumb.isEmpty()) thumb = img(str(c, "filePathLogo"));
            Item it = new Item(Item.LIVE, str(c, "slug"), str(c, "channelName"), now.isEmpty() ? "Live" : "Now: " + now,
                    thumb, "LIVE", "");
            it.description = str(c, "description");
            out.add(it);
        }
        return out;
    }

    /** My Stuff: Keep Watching and My List of the signed-in profile. */
    private Item.Page mine() throws IOException {
        Item.Page p = new Item.Page();
        if (!signedIn()) {
            p.empty = "Sign in to Paramount+ to see Keep Watching and My List.";
            return p;
        }
        List<Item> kw = keepWatching();
        if (!kw.isEmpty()) p.shelves.add(mixed(new Item.Shelf("Keep Watching", kw)));
        List<Item> wl = myList();
        if (!wl.isEmpty()) p.shelves.add(mixed(new Item.Shelf("My List", wl)));
        if (p.isEmpty()) p.empty = "Nothing in Keep Watching or My List yet.";
        return p;
    }

    /** The profile's Keep Watching row, each tile with how far it was watched. */
    private List<Item> keepWatching() {
        try {
            JSONObject o = get("/v3.1/" + PLATFORM + "/home/configurator/keepwatching.json", "start=0&rows=50&name=Keep%20Watching");
            if (!o.optBoolean("isLoggedIn", true)) return new ArrayList<>();
            return progressItems(o);
        } catch (IOException e) {
            Log.w(TAG, "keep watching: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Item> myList() {
        try {
            JSONObject o = get("/v3.0/" + PLATFORM + "/user/watchlist.json", "start=0&rows=50");
            if (!o.optBoolean("isLoggedIn", true)) return new ArrayList<>();
            return progressItems(o);
        } catch (IOException e) {
            Log.w(TAG, "my list: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Tiles from a signed-in list whose exact shape was not seen signed out: the first array of title objects
     * anywhere in the answer, each mapped like a row tile (or its nested title), with watched progress when given.
     */
    private List<Item> progressItems(JSONObject o) {
        JSONArray a = firstTitleArray(o, 0);
        List<Item> out = new ArrayList<>();
        if (a == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            Item it = toItem(x);
            JSONObject inner = x;
            if (it == null) {
                for (java.util.Iterator<String> k = x.keys(); k.hasNext() && it == null; ) {
                    JSONObject n = x.optJSONObject(k.next());
                    if (n != null) {
                        it = toItem(n);
                        inner = n;
                    }
                }
            }
            if (it == null || !seen.add(it.id)) continue;
            double watched = firstNum(x, inner, "medTime", "mediaTime", "playhead", "progressTime", "resumeTime");
            double dur = firstNum(x, inner, "duration", "mediaDuration", "totalTime");
            double pct = firstNum(x, inner, "percentWatched", "progressPercent");
            if (pct > 0) it.progress = (float) Math.min(1, pct > 1 ? pct / 100 : pct);
            else if (watched > 0 && dur > 0) it.progress = (float) Math.min(1, watched / dur);
            out.add(it);
        }
        return out;
    }

    private static Item.Shelf mixed(Item.Shelf s) {
        s.mixed = true;
        return s;
    }

        private static double firstNum(JSONObject a, JSONObject b, String... keys) {
        for (String k : keys) {
            double v = a.optDouble(k, 0);
            if (v <= 0 && b != null) v = b.optDouble(k, 0);
            if (v > 0) return v;
        }
        return 0;
    }

    private static JSONArray firstTitleArray(Object v, int depth) {
        if (depth > 5 || v == null) return null;
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            JSONObject f = a.optJSONObject(0);
            if (f != null && (f.toString().contains("contentId") || f.toString().contains("showId") || f.has("show_id"))) return a;
            for (int i = 0; i < a.length() && i < 3; i++) {
                JSONArray r = firstTitleArray(a.opt(i), depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                JSONArray r = firstTitleArray(o.opt(k.next()), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    @Override
    public List<Item> history() throws IOException {
        if (!signedIn()) return new ArrayList<>();
        return keepWatching();
    }

    @Override
    public List<Item> watchlist() throws IOException {
        if (!signedIn()) return new ArrayList<>();
        return myList();
    }

    @Override
    public Item.Page search(String query) throws IOException {
        JSONObject o = get("/v3.0/" + PLATFORM + "/contentsearch/search.json",
                "term=" + enc(query) + "&termCount=50&showCanVids=true");
        Item.Page p = new Item.Page();
        List<Item> found = items(o.optJSONArray("terms"));
        if (found.isEmpty()) {
            p.empty = "Nothing on Paramount+ matches \"" + query + "\".";
            return p;
        }
        p.shelves.add(new Item.Shelf("Results", found));
        p.grid = 0;
        return p;
    }

    // ---------------------------------------------------------------------------------- title pages

    @Override
    public Item.Page open(Item item) throws IOException {
        return openDetail(item);
    }

    /** Show id and season of a SERIES tile; a season link's id is "showId#sN". */
    private static String[] showAndSeason(String id) {
        int h = id.indexOf("#s");
        return h < 0 ? new String[]{id, ""} : new String[]{id.substring(0, h), id.substring(h + 2)};
    }

    @Override
    public Item.Page openDetail(Item item) throws IOException {
        if (item.kind == Item.VIDEO) return movieDetail(item);
        if (item.kind != Item.SERIES) throw new IOException("Paramount+ cannot open this tile.");
        String[] ss = showAndSeason(item.id);
        String sid = ss[0];
        Item.Page p = new Item.Page();
        Item.Detail d = new Item.Detail();
        p.detail = d;
        d.title = item.title;
        d.meta = item.subtitle;
        d.synopsis = item.description;
        d.image = item.image;
        String previewUrl = item.extra.isEmpty() ? preview("show", sid) : item.extra;
        // show header (for tiles that know little: Go to show from an episode, search results)
        try {
            JSONObject so = get("/v3.0/" + PLATFORM + "/shows/" + sid + ".json", null);
            JSONArray res = so.optJSONObject("show") == null ? null : so.optJSONObject("show").optJSONArray("results");
            JSONObject r = res == null ? null : res.optJSONObject(0);
            if (r != null) {
                if (!str(r, "title").isEmpty()) d.title = str(r, "title");
                if (!str(r, "about").isEmpty()) d.synopsis = str(r, "about");
                d.meta = join(join(str(r, "category"), str(r, "tvAgeRating")), year(str(r, "showPremiereDateStr")));
                if (!str(r, "videoPreviewURL").isEmpty()) previewUrl = str(r, "videoPreviewURL");
            }
            String art = firstAsset(so.optJSONObject("showAssets"), "filepath_show_page_header", "filepath_show_hero_regular",
                    "filepath_apple_airplay", "filepath_video_endcard_show_image");
            if (!art.isEmpty()) d.image = art.replace("/w400/", "/w1920/");
        } catch (IOException e) {
            Log.w(TAG, "show " + sid + ": " + e.getMessage());
        }
        d.trailer = trailer(previewUrl, d.title);
        // seasons, newest last as the app lists them
        List<String> seasons = new ArrayList<>();
        JSONObject av = get("/v3.0/" + PLATFORM + "/shows/" + sid + "/video/season/availability.json", null);
        JSONObject vas = av.optJSONObject("video_available_season");
        JSONArray sl = vas == null ? null : vas.optJSONArray("itemList");
        if (sl != null) for (int i = 0; i < sl.length(); i++) {
            JSONObject s = sl.optJSONObject(i);
            if (s != null && s.optInt("totalCount", 1) > 0 && !str(s, "seasonNum").isEmpty()) seasons.add(str(s, "seasonNum"));
        }
        java.util.Collections.sort(seasons, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(num(a), num(b));
            }
        });
        String season = ss[1].isEmpty() ? (seasons.isEmpty() ? "" : seasons.get(0)) : ss[1];
        String section = section(sid);
        List<Item> eps = new ArrayList<>();
        if (!section.isEmpty()) {
            String q = "rows=100&begin=0" + (season.isEmpty() ? "" : "&params=" + enc("seasonNum=" + season) + "&seasonNum=" + season);
            JSONObject so = get("/v2.0/" + PLATFORM + "/videos/section/" + section + ".json", q);
            JSONObject items = so.optJSONObject("sectionItems");
            JSONArray il = items == null ? null : items.optJSONArray("itemList");
            Item seriesTile = new Item(Item.SERIES, sid, d.title, d.meta, item.image, "", previewUrl);
            seriesTile.description = d.synopsis;
            if (il != null) for (int i = 0; i < il.length(); i++) {
                Item e = episode(il.optJSONObject(i));
                if (e == null) continue;
                e.channel = seriesTile;
                eps.add(e);
            }
        }
        for (int i = 0; i < seasons.size(); i++) {
            String sn = seasons.get(i);
            Item link = new Item(Item.SERIES, sid + "#s" + sn, item.title, item.subtitle, item.image, "", previewUrl);
            link.description = item.description;
            p.seasons.add(new Item.Season("Season " + sn, link, null));
            if (sn.equals(season)) p.season = i;
        }
        if (seasons.size() < 2) p.seasons.clear();
        if (!eps.isEmpty()) {
            p.shelves.add(new Item.Shelf(season.isEmpty() ? "Episodes" : "Season " + season, eps));
            p.grid = 0;
            d.play = eps.get(0);
            d.playLabel = "Play " + shortEpisode(eps.get(0).title);
        } else if (d.trailer == null) {
            p.empty = "Paramount+ lists no episodes for this show.";
        }
        {
            List<Item> extras = extras(sid, d.title, item);
            if (!extras.isEmpty()) {
                p.shelves.add(new Item.Shelf("Extras · free", extras));
                if (p.empty != null && eps.isEmpty()) p.empty = null;
            }
        }
        return p;
    }

    /** The show's clips and teasers (its Extras), free and clear, so they play in ClearTV's player signed out. */
    private final Map<String, List<Item>> extrasCache = new HashMap<>();

    private List<Item> extras(String sid, String title, Item show) {
        synchronized (extrasCache) {
            List<Item> c = extrasCache.get(sid);
            if (c != null) return new ArrayList<>(c);
        }
        List<Item> out = new ArrayList<>();
        try {
            JSONObject o = get("/v2.0/" + PLATFORM + "/shows/" + sid + "/videos/config/SHOW_LANDING_CLIPS.json",
                    "platformType=apps&rows=1&begin=0");
            String section = findSection(o);
            if (section.isEmpty()) return out;
            JSONObject so = get("/v2.0/" + PLATFORM + "/videos/section/" + section + ".json", "rows=20&begin=0");
            JSONObject items = so.optJSONObject("sectionItems");
            JSONArray il = items == null ? null : items.optJSONArray("itemList");
            Item seriesTile = new Item(Item.SERIES, sid, title, "", show.image, "", show.extra);
            if (il != null) for (int i = 0; i < il.length(); i++) {
                Item e = episode(il.optJSONObject(i));
                if (e == null) continue;
                e.channel = seriesTile;
                out.add(e);
            }
        } catch (IOException e) {
            Log.w(TAG, "extras " + sid + ": " + e.getMessage());
            return out;
        }
        Log.i(TAG, "extras " + sid + ": " + out.size());
        synchronized (extrasCache) {
            extrasCache.put(sid, new ArrayList<>(out));
        }
        return out;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String year(String date) {
        return date != null && date.length() >= 4 && Character.isDigit(date.charAt(0)) ? date.substring(0, 4) : "";
    }

    /** The show's full-episodes section id (cached per show). */
    private String section(String sid) {
        String k = "section_" + sid;
        String s = prefs.getString(k, "");
        if (!s.isEmpty()) return s;
        // most shows answer on the apps' full-episodes config; others (several dramas) only on the show page's
        // Episodes config, which the show menu names
        List<String> names = new ArrayList<>();
        names.add("DEFAULT_APPS_FULL_EPISODES");
        names.add("SHOW_LANDING_EPISODES");
        for (int n = 0; n < names.size() && s.isEmpty(); n++) {
            try {
                JSONObject o = get("/v2.0/" + PLATFORM + "/shows/" + sid + "/videos/config/" + names.get(n) + ".json",
                        "platformType=apps&rows=1&begin=0");
                s = findSection(o);
            } catch (IOException e) {
                Log.w(TAG, "section " + sid + " " + names.get(n) + ": " + e.getMessage());
            }
            if (s.isEmpty() && n == names.size() - 1 && names.size() == 2) {
                String m = episodesConfig(sid);
                if (!m.isEmpty() && !names.contains(m)) names.add(m);
            }
        }
        if (!s.isEmpty()) prefs.edit().putString(k, s).apply();
        return s;
    }

    /** The video config behind the show menu's Episodes entry. */
    private String episodesConfig(String sid) {
        try {
            JSONArray menus = get("/v3.0/" + PLATFORM + "/shows/" + sid + "/menu.json", null).optJSONArray("showMenu");
            if (menus != null) for (int i = 0; i < menus.length(); i++) {
                JSONObject m = menus.optJSONObject(i);
                JSONArray links = m == null ? null : m.optJSONArray("links");
                if (links != null) for (int j = 0; j < links.length(); j++) {
                    JSONObject l = links.optJSONObject(j);
                    if (l != null && ("Episodes".equalsIgnoreCase(str(l, "title")) || "hero_video".equals(str(l, "page_type")))
                            && !str(l, "videoConfigUniqueName").isEmpty()) return str(l, "videoConfigUniqueName");
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "menu " + sid + ": " + e.getMessage());
        }
        return "";
    }

    private static String findSection(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            String id = str(o, "sectionId");
            if (!id.isEmpty()) return id;
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                String r = findSection(o.opt(k.next()));
                if (!r.isEmpty()) return r;
            }
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                String r = findSection(a.opt(i));
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private Item trailer(String previewUrl, String title) {
        if (previewUrl == null || previewUrl.isEmpty()) return null;
        return new Item(Item.VIDEO, "preview:" + previewUrl.hashCode(), title + " · Trailer", "Trailer", "", "", previewUrl);
    }

    /** A film's title page: header from the film, Play, and its trailer (clear HLS, plays natively). */
    private Item.Page movieDetail(Item item) throws IOException {
        Item.Page p = new Item.Page();
        Item.Detail d = new Item.Detail();
        p.detail = d;
        d.title = item.title;
        d.meta = item.subtitle;
        d.synopsis = item.description;
        d.image = item.image;
        d.play = item;
        String previewUrl = item.extra;
        if (item.channel == null) {
            // an episode opens straight onto Play; a film fills its header from the film record
            try {
                JSONObject m = get("/v3.0/" + PLATFORM + "/movies/" + item.id + ".json",
                        "includeTrailerInfo=true&includeContentInfo=true").optJSONObject("movie");
                if (m != null) {
                    if (!str(m, "title").isEmpty()) d.title = str(m, "title");
                    JSONObject mc = m.optJSONObject("movieContent");
                    if (mc != null) {
                        if (!str(mc, "description").isEmpty()) d.synopsis = str(mc, "description");
                        d.meta = join(join(genre(m), str(mc, "rating")), minutes(mc.optLong("duration", 0)));
                    }
                    String art = firstAsset(m.optJSONObject("movieAssets"), "filepath_movie_hero_regular", "filepath_movie_hero",
                            "filepath_movie_keep_watching");
                    if (!art.isEmpty()) d.image = art.replace("/w400/", "/w1920/");
                    if (!str(m, "videoPreviewURL").isEmpty()) previewUrl = str(m, "videoPreviewURL");
                }
            } catch (IOException e) {
                Log.w(TAG, "movie " + item.id + ": " + e.getMessage());
            }
            d.trailer = trailer(previewUrl, d.title);
        }
        return p;
    }

    // ------------------------------------------------------------------------------- more like this

    @Override
    public boolean hasSimilar(Item it) {
        return it != null && (it.kind == Item.VIDEO || it.kind == Item.SERIES) && !it.id.startsWith("preview:");
    }

    /**
     * Shows: Paramount+'s related shows for the show (an episode's: its show), widened with the related lists of its
     * first few shows. Films: the film's genre (Paramount+ has no related list for films), mixed with the shows
     * related to nothing; one tile per title, the title itself and its show left out.
     */
    @Override
    public Item.Page similar(final Item it) throws IOException {
        Similar sim = new Similar(new Similar.Key() {
            @Override
            public String of(Item x) {
                return Similar.titleKey(x.title);
            }
        });
        Item showOf = it.kind == Item.SERIES ? it : it.channel != null && it.channel.kind == Item.SERIES ? it.channel : null;
        if (showOf != null) {
            final String sid = showAndSeason(showOf.id)[0];
            List<Item> first = related(sid);
            sim.add(first, 1.0);
            List<String> seeds = new ArrayList<>();
            for (Item x : first) {
                if (seeds.size() >= Config.i("paramount.similar.seeds", 3)) break;
                if (!x.id.equals(sid)) seeds.add(x.id);
            }
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, seeds.size()));
            try {
                List<Future<List<Item>>> fs = new ArrayList<>();
                for (final String s : seeds) fs.add(pool.submit(new Callable<List<Item>>() {
                    @Override
                    public List<Item> call() throws IOException {
                        return related(s);
                    }
                }));
                long until = android.os.SystemClock.uptimeMillis() + Config.i("paramount.similar.waitMs", 5000);
                for (Future<List<Item>> f : fs) {
                    try {
                        sim.add(f.get(Math.max(1, until - android.os.SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS), 0.6);
                    } catch (Exception e) {
                        Log.w(TAG, "more like: a second list failed: " + e);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        } else {
            JSONObject m = get("/v3.0/" + PLATFORM + "/movies/" + it.id + ".json", null).optJSONObject("movie");
            JSONArray g = m == null ? null : m.optJSONArray("genre_slugs");
            for (int i = 0; g != null && i < g.length() && i < 2; i++) {
                sim.add(genreMovies(g.optString(i), 40), i == 0 ? 1.0 : 0.6);
            }
        }
        List<Item> found = new ArrayList<>();
        for (Similar.Group g : sim.ranked()) found.add(g.items.get(0));
        return similarPage(it, found, name());
    }

    private List<Item> related(String sid) throws IOException {
        return items(get("/v2.0/" + PLATFORM + "/shows/" + sid + "/related/shows.json", null).optJSONArray("relatedShows"));
    }

    // ------------------------------------------------------------------------------------- previews

    @Override
    public boolean previewVideo(Item item) {
        return item.extra.startsWith("https://splice");
    }

    @Override
    public Playback previewPlayback(Item item) throws IOException {
        if (!previewVideo(item)) throw new IOException("no preview");
        Playback p = new Playback();
        p.hls = item.extra;
        p.userAgent = Http.DESKTOP_UA;
        p.title = item.title;
        return p;
    }

    // ---------------------------------------------------------------------------------------- play

    @Override
    public Playback play(Item item) throws IOException {
        if (item.id.startsWith("preview:")) {
            // a trailer: Paramount+'s clear preview stream, in ClearTV's own player
            Playback p = previewPlayback(item);
            p.subtitle = "Trailer";
            return p;
        }
        Playback p = new Playback();
        p.webTvUa = false;
        p.title = item.title;
        p.subtitle = item.subtitle;
        if (item.kind == Item.LIVE) {
            if (!signedIn()) throw new IOException(SIGN_IN_TO_WATCH);
            p.live = true;
            p.webUrl = BASE + "/live-tv/stream/" + item.id + "/";
            // channels Paramount+ serves through Google DAI play in ClearTV's own player (the web page is the fallback)
            String key = daiKey(item.id);
            if (!key.isEmpty()) {
                try {
                    p.hls = daiStream(key);
                    p.userAgent = Http.DESKTOP_UA;
                } catch (IOException e) {
                    Log.w(TAG, "dai " + item.id + ": " + e.getMessage());
                }
            }
            return p;
        }
        String path = "";
        JSONObject v = null;
        try {
            JSONArray l = get("/v2.0/" + PLATFORM + "/video/cid/" + item.id + ".json", null).optJSONArray("itemList");
            v = l == null ? null : l.optJSONObject(0);
        } catch (IOException e) {
            Log.w(TAG, "video " + item.id + ": " + e.getMessage());
        }
        if (v != null && "FREE".equalsIgnoreCase(str(v, "subscriptionLevel")) && !v.optBoolean("isProtected", true)) {
            // free clips: Paramount+'s clear HLS rendition (published without ads), no account needed
            String hls = clearHls(item.id);
            if (!hls.isEmpty()) {
                p.hls = hls;
                p.userAgent = Http.DESKTOP_UA;
                if (!str(v, "label").isEmpty()) p.title = str(v, "label");
                p.subtitle = join(str(v, "seriesTitle"), str(v, "mediaType"));
                return p;
            }
        }
        if (!signedIn()) throw new IOException(SIGN_IN_TO_WATCH);
        {
            if (v != null) {
                path = str(v, "url");
                boolean movie = "Movie".equalsIgnoreCase(str(v, "mediaType"));
                if (movie) p.subtitle = minutes(v.optLong("duration", 0));
                else p.subtitle = join(str(v, "seriesTitle"), seasonEpisode(v));
                if (!movie && !str(v, "label").isEmpty()) p.title = str(v, "label");
                markers(p, v.optJSONObject("playbackEvents"));
                if (p.creditsMs <= 0) p.creditsMs = clock(str(v, "endCreditsChapterTime"));
            }
        }
        if (path.isEmpty()) path = (item.channel == null ? "movies/video/" : "shows/video/") + item.id + "/";
        p.webUrl = BASE + "/" + path.replaceFirst("^/+", "");
        p.resumeMs = accountPosition(item.id, v == null ? 0 : v.optLong("duration", 0));
        // episodes and films: the Widevine DASH stream in ClearTV's own player, licensed with the account's DRM
        // session; the web page stays as the fallback
        try {
            protectedStream(item.id, p);
        } catch (IOException e) {
            Log.w(TAG, "stream " + item.id + ": " + e.getMessage());
        }
        return p;
    }

    /**
     * Fills in the protected stream: the account's DRM session (license URL and bearer token) from apps-api, then
     * the title's DASH manifest from ThePlatform. The ad-free rendition (DASH_CENC) comes first; the
     * pre-conditioned one (DASH_CENC_PRECON, the same picture with empty seams where a stitcher would put ads) is
     * the fallback. Neither manifest carries ads: Paramount+ adds them only through a separate stitching session,
     * which ClearTV never opens.
     */
    private void protectedStream(String cid, Playback p) throws IOException {
        JSONObject s = get(DRM_SESSION.replace("{platform}", PLATFORM), "contentId=" + enc(cid));
        String license = str(s, "url"), token = str(s, "ls_session");
        Log.i(TAG, "drm session " + cid + ": success=" + s.optBoolean("success", true) + " license="
                + license.replaceFirst("\\?.*", "") + " token=" + (token.isEmpty() ? "none" : "yes")
                + " stream=" + (str(s, "streamingUrl").isEmpty() ? "none" : "yes")
                + (str(s, "errors").isEmpty() ? "" : " errors=" + str(s, "errors")));
        if (!s.optBoolean("success", true) || license.isEmpty() || token.isEmpty()) {
            String why = str(s, "errors");
            if (why.isEmpty()) why = str(s, "message");
            throw new IOException(why.isEmpty() ? "Paramount+ did not open a playback session." : why);
        }
        String mpd = "";
        for (String type : Config.list("paramount.dashAssetTypes", "DASH_CENC", "DASH_CENC_PRECON")) {
            String u = DASH_SMIL.replace("{id}", enc(cid)).replace("{types}", enc(type));
            try {
                String smil = new String(Http.get(u, headers()), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<video src=\"(https://[^\"]+\\.mpd[^\"]*)\"").matcher(smil);
                if (m.find()) {
                    mpd = m.group(1).replace("&amp;", "&");
                    Log.i(TAG, "stream " + cid + ": " + type + " DASH");
                    break;
                }
                Log.i(TAG, "stream " + cid + ": no " + type);
            } catch (IOException e) {
                Log.w(TAG, "dash smil " + type + " " + cid + ": " + e.getMessage());
            }
        }
        if (mpd.isEmpty()) {
            // the session can name the manifest itself; newer titles (ALVE ids) are not on ThePlatform and
            // only get one this way when the service includes it
            String su = str(s, "streamingUrl");
            Log.i(TAG, "stream " + cid + ": session stream " + (su.isEmpty() ? "none" : su.replaceFirst("\\?.*", "")));
            if (!su.isEmpty()) mpd = su;
        }
        if (mpd.isEmpty()) throw new IOException("no DASH stream");
        p.hls = mpd;
        p.dash = true;
        p.userAgent = Http.DESKTOP_UA;
        p.drmLicenseUrl = license;
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        p.drmHeaders = h;
    }

    /**
     * Where the account stopped this title (ms) on any device, for resuming: 0 when it was finished, not started,
     * or is within the last minute.
     */
    private long accountPosition(String cid, long durationSec) {
        try {
            JSONObject o = get(STREAMS.replace("{platform}", PLATFORM), "contentId=" + enc(cid));
            long sec = (long) o.optDouble("mediaTime", 0);
            boolean done = o.optBoolean("hasWatched", false);
            Log.i(TAG, "position " + cid + ": " + sec + " s" + (done ? " (watched)" : ""));
            if (done || sec <= 0 || (durationSec > 0 && sec > durationSec - 60)) return 0;
            return sec * 1000;
        } catch (IOException e) {
            Log.w(TAG, "position " + cid + ": " + e.getMessage());
            return 0;
        }
    }

    private static final String SIGN_IN_TO_WATCH =
            "Sign in to Paramount+ to watch this (Sign in, in the left menu). Trailers and Extras play without an account.";

    /** "00:22:35" or "22:35" as milliseconds; 0 when absent. */
    static long clock(String t) {
        if (t == null || t.isEmpty()) return 0;
        long ms = 0;
        for (String part : t.trim().split(":")) {
            try {
                ms = ms * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return ms * 1000;
    }

    /** The clear HLS master for an unprotected video, from its ThePlatform release (empty when there is none). */
    private String clearHls(String cid) {
        String u = SMIL.replace("{id}", enc(cid));
        try {
            String smil = new String(Http.get(u, headers()), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<video src=\"(https://[^\"]+\\.m3u8[^\"]*)\"").matcher(smil);
            if (m.find()) return m.group(1).replace("&amp;", "&");
        } catch (IOException e) {
            Log.w(TAG, "smil " + cid + ": " + e.getMessage());
        }
        return "";
    }

    /** The Google DAI live asset key of a channel's current listing (empty for channels not on DAI). */
    private String daiKey(String slug) throws IOException {
        JSONArray a = get("/v3.0/" + PLATFORM + "/live/channels.json", "start=0&_clientRegion=" + REGION + "&showListing=true")
                .optJSONArray("channels");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null || !slug.equals(str(c, "slug"))) continue;
            JSONArray cur = c.optJSONArray("currentListing");
            JSONObject l = cur == null ? null : cur.optJSONObject(0);
            JSONObject can = l == null ? null : l.optJSONObject("contentCANVideo");
            return can == null ? "" : str(can, "daistreamKey");
        }
        return "";
    }

    /** Registers a Google DAI live stream session and returns its HLS master (ads are stitched in by DAI). */
    private static String daiStream(String key) throws IOException {
        try (Http.Response r = Http.open(DAI.replace("{key}", enc(key)), new HashMap<String, String>(), new byte[0],
                "application/x-www-form-urlencoded", true)) {
            String m = new JSONObject(new String(Http.readAll(r.body, 1 << 16), StandardCharsets.UTF_8)).optString("stream_manifest", "");
            if (m.isEmpty()) throw new IOException("no DAI manifest");
            return m;
        } catch (JSONException e) {
            throw new IOException("DAI sent an unreadable answer", e);
        }
    }

    private static String seasonEpisode(JSONObject v) {
        String s = str(v, "seasonNum"), e = str(v, "episodeNum");
        return s.isEmpty() || e.isEmpty() ? "" : "S" + s + " E" + e;
    }

    /** Paramount+'s own markers: opening credits (Skip intro; a recap when there is no intro) and end credits. */
    private static void markers(Playback p, JSONObject ev) {
        if (ev == null) return;
        long is = ev.optLong("openCreditStartTime", 0), ie = ev.optLong("openCreditEndTimeMs", 0);
        if (ie <= is) {
            is = ev.optLong("previewStartTimeMs", 0);
            ie = ev.optLong("previewEndTimeMs", 0);
        }
        if (ie > is) {
            p.introStartMs = is;
            p.introEndMs = ie;
        }
        p.creditsMs = ev.optLong("endCreditChapterTimeMs", 0);
    }

    @Override
    public String[] webDomains() {
        return Config.list("paramount.webDomains", "paramountplus.com", "pplusstatic.com", "cbsi.com", "cbsig.net",
                "paramount.tech", "theplatform.com", "cbsaavideo.com", "irdeto.com", "dai.google.com",
                "imasdk.googleapis.com", "cbsivideo.com");
    }

    // ------------------------------------------------------------------------------------- account

    @Override public int signInKind() { return SIGNIN_CODE; }

    @Override
    public String codeHelp() {
        return "On your phone or computer, sign in to Paramount+ at";
    }

    private String deviceId() {
        String d = prefs.getString("device_id", null);
        if (d == null) {
            byte[] b = new byte[8];
            new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            d = s.toString();
            prefs.edit().putString("device_id", d).apply();
        }
        return d;
    }

    @Override
    public Code startCode() throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("deviceId", deviceId());
        try (Http.Response r = post("/v2.0/" + PLATFORM + "/ott/auth/code.json", f)) {
            JSONObject o = new JSONObject(new String(Http.readAll(r.body, 1 << 16), StandardCharsets.UTF_8));
            String code = str(o, "activationCode");
            if (code.isEmpty()) throw new IOException("Paramount+ sent no activation code.");
            long life = o.optLong("retryDuration", 1_800_000);
            return new Code(code, ACTIVATE, str(o, "deviceToken"), (int) Math.max(5, o.optLong("retryInterval", 5000) / 1000),
                    System.currentTimeMillis() + life);
        } catch (JSONException e) {
            throw new IOException("Paramount+ sent an unreadable code", e);
        }
    }

    @Override
    public boolean pollCode(Code c) throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("activationCode", c.userCode);
        f.put("deviceToken", c.deviceCode);
        f.put("deviceId", deviceId());
        List<String> cookies;
        JSONObject o;
        try (Http.Response r = post("/v2.0/" + PLATFORM + "/ott/auth/status.json", f)) {
            o = new JSONObject(new String(Http.readAll(r.body, 1 << 16), StandardCharsets.UTF_8));
            cookies = r.setCookies();
        } catch (JSONException e) {
            throw new IOException("Paramount+ sent an unreadable answer", e);
        }
        if (o.optBoolean("regenerateCode", false)) throw new IOException("The code expired.");
        if (!o.optBoolean("success", false) || cookies.isEmpty()) return false;
        storeCookies(cookies);
        loadAccount();
        return true;
    }

    /** Keeps the session cookies (sent as the Cookie header) and gives them to the web player too. */
    private void storeCookies(List<String> setCookies) {
        Map<String, String> jar = new LinkedHashMap<>();
        for (String part : prefs.getString("cookies", "").split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) jar.put(part.substring(0, eq), part.substring(eq + 1));
        }
        List<String> web = new ArrayList<>();
        for (String sc : setCookies) {
            String nv = sc.split(";", 2)[0].trim();
            int eq = nv.indexOf('=');
            if (eq <= 0) continue;
            jar.put(nv.substring(0, eq), nv.substring(eq + 1));
            web.add(nv + "; Domain=.paramountplus.com; Path=/; Secure");
        }
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (s.length() > 0) s.append("; ");
            s.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs.edit().putString("cookies", s.toString()).putLong("cookies_at", System.currentTimeMillis()).apply();
        if (!web.isEmpty()) WebActivity.setCookies(BASE + "/", web);
    }

    /**
     * Gives the web player the app's session cookies again right before a watch page loads (the copy made at
     * sign-in can be missing from the WebView's jar), and logs the cookie names on both sides (never values).
     */
    static void syncWebSession(Context c) {
        String jar = c.getSharedPreferences("svc_paramount", Context.MODE_PRIVATE).getString("cookies", "");
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        String before = cm.getCookie(BASE + "/");
        List<String> app = new ArrayList<>();
        for (String part : jar.split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            app.add(part.substring(0, eq));
            cm.setCookie(BASE + "/", part.trim() + "; Domain=.paramountplus.com; Path=/; Secure");
        }
        cm.flush();
        Log.i(TAG, "web session: app cookies " + app + ", web jar had " + cookieNames(before) + ", now " + cookieNames(cm.getCookie(BASE + "/")));
    }

    private static List<String> cookieNames(String header) {
        List<String> n = new ArrayList<>();
        if (header == null) return n;
        for (String part : header.split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) n.add(part.substring(0, eq).trim());
        }
        return n;
    }

    /** The signed-in account's name and active profile (login status). */
    private void loadAccount() {
        try {
            JSONObject o = get("/v3.0/" + PLATFORM + "/login/status.json", null);
            JSONObject prof = o.optJSONObject("activeProfile");
            String name = prof == null ? "" : str(prof, "name");
            String account = str(o, "firstName");
            if (account.isEmpty()) account = str(o, "email");
            if (account.isEmpty()) account = name;
            android.content.SharedPreferences.Editor e = prefs.edit().putBoolean("signed_in", true).putString("account", account);
            if (prof != null) {
                e.putString("profile_id", str(prof, "id")).putString("profile_name", name);
                String pic = str(prof, "profilePicPath");
                if (!pic.isEmpty()) e.putString("avatar", img(pic));
            }
            e.apply();
        } catch (IOException e) {
            Log.w(TAG, "login status: " + e.getMessage());
            prefs.edit().putBoolean("signed_in", true).apply();
        }
    }

    /**
     * Paramount+'s session cookie lasts about a day; the app renews it by switching to the active profile, which
     * answers with fresh cookies. Done at start-up when the cookie is older than 12 hours.
     */
    @Override
    public void refreshSession() throws IOException {
        if (!signedIn()) return;
        if (System.currentTimeMillis() - prefs.getLong("cookies_at", 0) < 12 * 3600_000L) return;
        String pid = prefs.getString("profile_id", "");
        if (pid.isEmpty()) {
            loadAccount();
            pid = prefs.getString("profile_id", "");
            if (pid.isEmpty()) return;
        }
        try (Http.Response r = post("/v2.0/" + PLATFORM + "/user/account/profile/switch/" + pid + ".json", new HashMap<String, String>())) {
            List<String> c = r.setCookies();
            if (!c.isEmpty()) storeCookies(c);
        } catch (Http.Error e) {
            if (e.code == 401 || e.code == 403) {
                Log.w(TAG, "session ended");
                signOut();
            } else {
                throw e;
            }
        }
    }

    @Override
    public boolean signedIn() {
        return prefs.getBoolean("signed_in", false) && !prefs.getString("cookies", "").isEmpty();
    }

    @Override
    public void signOut() {
        prefs.edit().remove("cookies").remove("cookies_at").remove("signed_in").remove("account").remove("avatar")
                .remove("profile_id").remove("profile_name").remove("profile_avatar").apply();
        // the web player's copy of the session goes too
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                WebActivity.clearWebSession(new String[]{BASE + "/"});
            }
        });
    }
}
