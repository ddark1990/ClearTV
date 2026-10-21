package com.goomero.cleartv.tv;

import android.content.Context;
import android.util.JsonReader;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prime Video browse from the data primevideo.com embeds in each page (the "hydration" JSON): storefront rows,
 * search, the watchlist and series episodes, read as a stream so the 1-5 MB pages are never held whole. Prime's
 * video is Widevine-protected and only Amazon's players can license it, so playback opens primevideo.com's own
 * player for that title inside ClearTV (the WebView's Widevine). Sign-in is Amazon's web sign-in page in ClearTV;
 * the session cookies it leaves are what the native rows use for the watchlist and personal rows.
 */
final class PrimeSource extends Source {
    private static final String TAG = "CTV.Prime";
    // site address, the hydration marker and the parser keys come from the bundled config (Config)
    static String BASE;
    private static byte[] MARKER;
    private static Set<String> CONTAINERS, SKIP, DETAIL_KEYS, DETAIL_SKIP, STORE_SKIP;
    private static final Set<String> PAGINATION = new HashSet<>(Arrays.asList("pagination"));
    private static final Set<String> NONE = new HashSet<>();

    static {
        Config.onChange(new Runnable() {
            @Override
            public void run() {
                BASE = Config.s("prime.base", "https://www.primevideo.com");
                MARKER = Config.s("prime.hydrationMarker", "id=\"dv-web-page-hydration-data\"").getBytes(StandardCharsets.US_ASCII);
                CONTAINERS = Config.set("prime.containers", "containers");
                SKIP = Config.set("prime.skip", "overflowMenu", "watchlistAction", "impressionData", "itemAnalytics",
                        "customerReviews", "modelDebug", "station", "playbackAction", "playbackActions", "buyBoxActions",
                        "hoverInfo", "degradations", "strings", "inlineContainerUpdateActions", "journeyIngressContext",
                        "paginationServiceToken", "featureSwitches", "requestFeatureSwitches", "clickstreamPageParams",
                        "resiliencyMetadata", "swiftPageParameters", "sitewide", "audioTracks", "subtitles",
                        "enhancedSubtitles", "contributors", "reviews", "playbackTracks", "refund", "imdb", "buyBox",
                        "creative", "banner", "notification", "restriction", "extras", "tokens", "recordings",
                        "bundlesContent", "otherFormats", "widgets", "termsText", "bottomMenu", "metadata", "pageContext",
                        "action", "asins");
                // keys of a title page's hydration state the native detail screen reads
                DETAIL_KEYS = Config.set("prime.detailKeys", "detail", "self", "watchlist", "action", "seasons",
                        "episodeList", "containers");
                Set<String> ds = new HashSet<>(SKIP);
                ds.removeAll(Config.set("prime.detailKeep", "action", "metadata"));
                ds.addAll(Config.set("prime.detailSkip", "contributors", "reviews", "audioTracks", "subtitles",
                        "enhancedSubtitles", "playbackEnvelope", "correlationId", "studios", "autoplayTrailerHero",
                        "bottomBar", "pageContext", "reactionAction", "otherFormats"));
                ds.removeAll(Config.set("prime.detailKeepCast", "contributors"));
                DETAIL_SKIP = ds;
                // a storefront row's live stations, their "Watch live" link and the row's paging token are read
                Set<String> ss = new HashSet<>(SKIP);
                ss.removeAll(Config.set("prime.storefrontKeep", "station", "playbackAction", "paginationServiceToken", "metadata"));
                STORE_SKIP = ss;
            }
        });
    }

    PrimeSource(Context c) {
        super(c, "prime");
    }

    @Override public String id() { return "prime"; }
    @Override public String name() { return "Prime Video"; }
    @Override public int accent() { return 0xFF1A98FF; }
    @Override public int accent2() { return 0xFF0F79D0; }
    @Override public String iconKey() { return "www.primevideo.com"; }

    /** Prime Video's own sections (the site's navigation bar, in its order), then the account's watchlist. */
    @Override
    public List<Nav> navs() {
        List<Nav> l = new ArrayList<>();
        l.add(new Nav("/storefront/home/", "Home"));
        l.add(new Nav("/collection/streamfree", "Free to me"));
        l.add(new Nav("/movie", "Movies"));
        l.add(new Nav("/tv", "TV shows"));
        l.add(new Nav("/livetv", "Live TV"));
        l.add(new Nav("guide:/livetv", "Live guide"));
        l.add(new Nav("/sports", "Sports"));
        l.add(new Nav("/news", "News"));
        l.add(new Nav("/addons", "Subscriptions"));
        l.add(new Nav("/store", "Store"));
        l.add(new Nav("/categories", "Categories"));
        l.add(new Nav("/mystuff/watchlist/", "Watchlist"));
        return l;
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Accept", "text/html");
        h.put("Accept-Language", "en-US,en;q=0.9");
        String cookies = prefs.getString("cookies", "");
        if (!cookies.isEmpty()) h.put("Cookie", cookies);
        return h;
    }

    @Override
    public Item.Page load(Nav nav) throws IOException {
        recoverSession();
        if (nav.id.startsWith("/mystuff") && !signedIn()) {
            Item.Page p = new Item.Page();
            p.empty = "Sign in to see your Prime Video watchlist.";
            return p;
        }
        if (nav.id.startsWith("guide:")) return guide(storefront(BASE + nav.id.substring(6)));
        Item.Page page = storefront(BASE + nav.id);
        if (nav.id.startsWith("/livetv")) {
            // this TV's own Favorites and Recently watched rows head the Live TV page (>> on a station toggles)
            List<Item> rec = new ArrayList<>(LiveLists.recent(app, "prime"));
            List<Item> fav = LiveLists.favorites(app, "prime");
            if (!rec.isEmpty()) page.shelves.add(0, new Item.Shelf(fav.isEmpty() ? "Recently watched  ·  hold OK or ▶▶ on a station to add a Favorite" : "Recently watched", rec));
            page.shelves.add(0, new Item.Shelf(fav.isEmpty() ? "Favorites · hold OK or press ▶▶ on a station to add it" : "★ Favorites  ·  hold OK or ▶▶ adds or removes", fav));
        }
        return page;
    }

    @Override
    public Item.Page search(String query) throws IOException {
        return storefront(BASE + "/search/?phrase=" + URLEncoder.encode(query, "UTF-8"));
    }

    private Item.Page storefront(String url) throws IOException {
        final Item.Page page = new Item.Page();
        try (Http.Response r = Http.open(url, headers(), null, null, false)) {
            if (r.code >= 300) {
                // a redirect to amazon.com/ap/signin: the stored session has ended
                if (signedIn()) {
                    Log.w(TAG, "session ended (redirect " + r.code + ")");
                    signOut();
                }
                page.empty = "Sign in to Prime Video to see this.";
                return page;
            }
            InputStream in = new java.io.BufferedInputStream(r.body, 32768);
            if (!seek(in)) throw new IOException("Prime Video changed its page format (no hydration data)");
            scanRows(in, page);
        }
        if (page.isEmpty() && page.empty.isEmpty()) page.empty = "Nothing here yet.";
        return page;
    }

    /**
     * Rows (containers) and the "load more rows" pagination of a storefront page or of one of its next pages.
     * The site shows a page's first ~7 rows and fetches the rest (Movies and TV shows have about 100) page by page
     * as you scroll; ClearTV does the same through {@link #more}.
     */
    private void scanRows(InputStream in, final Item.Page page) throws IOException {
        final int[] n = {0};
        JsonScan.scan(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), PAGINATION, CONTAINERS, STORE_SKIP,
                new JsonScan.Sink() {
                    @Override
                    public void onValue(String key, String name, JSONObject c) {
                        if (key.equals("pagination")) {
                            if (page.more == null) page.more = nextPage(c);
                            return;
                        }
                        Item.Shelf s = shelf(c);
                        if (s != null && !s.items.isEmpty()) page.shelves.add(s);
                        n[0]++;
                    }
                });
        Log.i(TAG, "rows: " + page.shelves.size() + " of " + n[0] + (page.more != null ? ", more to load" : ""));
    }

    /** The site's next-page request (getLandingPage) from a page's pagination block, or null on the last page. */
    private static String nextPage(JSONObject pag) {
        JSONObject q = pag.optJSONObject("queryParameters");
        if (q == null || q.optString("serviceToken").isEmpty()) return null;
        StringBuilder b = new StringBuilder(Config.s("prime.landingApi", "/api/getLandingPage")).append('?');
        for (Iterator<String> it = q.keys(); it.hasNext(); ) {
            String k = it.next();
            String key = k.equals("contentId") ? "pageId" : k.equals("contentType") ? "pageType" : k;
            Object v = q.opt(k);
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) param(b, key, a.optString(i));
            } else if (v != null && v != JSONObject.NULL) {
                param(b, key, String.valueOf(v));
            }
        }
        param(b, "isCleanSlateActive", "1");
        param(b, "isDiscoverActive", "1");
        param(b, "isLivePageActive", "1");
        param(b, "variant", "desktopWindows");
        param(b, "payloadScheme", "default");
        return b.toString();
    }

    private static void param(StringBuilder b, String k, String v) {
        try {
            if (b.charAt(b.length() - 1) != '?') b.append('&');
            b.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ignored) {
            // UTF-8 is always there
        }
    }

    private Map<String, String> jsonHeaders() {
        Map<String, String> h = headers();
        h.put("Accept", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");
        return h;
    }

    /** The next rows of a storefront page (the token is the site's own next-page request). */
    @Override
    public Item.Page more(String token) throws IOException {
        Item.Page page = new Item.Page();
        try (Http.Response r = Http.open(BASE + token, jsonHeaders(), null, null, false)) {
            if (r.code >= 300) throw new Http.Error(r.code, "", "next rows");
            scanRows(new java.io.BufferedInputStream(r.body, 32768), page);
        }
        return page;
    }

    /**
     * The next tiles of a row (the site's paginateCollection, as its carousels call it when you scroll right).
     * The token holds the row's target id, the service token and the start index.
     */
    @Override
    public Item.Shelf moreRow(String token) throws IOException {
        String[] t = token.split("\n", 3);
        if (t.length < 3) return null;
        StringBuilder b = new StringBuilder(Config.s("prime.rowApi", "/api/paginateCollection")).append('?');
        param(b, "serviceToken", t[1]);
        param(b, "paginationTargetId", t[0]);
        param(b, "startIndex", t[2]);
        param(b, "pageId", "home");
        param(b, "pageType", "home");
        param(b, "pageSize", "20");
        param(b, "collectionType", "Container");
        param(b, "isCleanSlateActive", "1");
        param(b, "isDiscoverActive", "1");
        param(b, "isLivePageActive", "1");
        param(b, "variant", "desktopWindows");
        param(b, "payloadScheme", "default");
        param(b, "actionScheme", "default");
        param(b, "decorationScheme", "web-decoration-gti-v4");
        param(b, "featureScheme", "web-features-v6");
        param(b, "widgetScheme", "web-explorecs-v11");
        for (String f : Config.list("prime.rowFeatures", "integration", "CLIENT_DECORATION_ENABLE_DAAPI", "ENABLE_DRAPER_CONTENT",
                "HorizontalPagination", "CleanSlate", "EpgContainerPagination", "ENABLE_GPCI",
                "SupportsImageTextLinkTextInStandardHero", "LinearStationInAllCarousels")) param(b, "dynamicFeatures", f);
        String body;
        try (Http.Response r = Http.open(BASE + b, jsonHeaders(), null, null, false)) {
            if (r.code >= 300) throw new Http.Error(r.code, "", "more tiles");
            body = new String(Http.readAll(r.body, 4 << 20), StandardCharsets.UTF_8);
        }
        try {
            JSONObject o = new JSONObject(body);
            JSONArray ents = o.optJSONArray("entities");
            List<Item> items = new ArrayList<>();
            long now = System.currentTimeMillis();
            int start = 0;
            try {
                start = Integer.parseInt(t[2]);
            } catch (NumberFormatException ignored) {
                // a token from an older build
            }
            if (ents != null) for (int i = 0; i < ents.length(); i++) {
                Item it = entity(ents.optJSONObject(i), start + i, false, now);
                if (it != null) items.add(it);
            }
            Item.Shelf s = new Item.Shelf("", items);
            JSONObject q = JsonScan.path(o, "pagination", "queryParameters");
            if (o.optBoolean("hasMoreItems") && q != null && !q.optString("serviceToken").isEmpty() && !items.isEmpty()) {
                s.more = t[0] + "\n" + q.optString("serviceToken") + "\n" + o.optInt("startIndex", start + items.size());
            }
            return s;
        } catch (org.json.JSONException e) {
            throw new IOException("Prime Video sent an unreadable row");
        }
    }

    /** Advances the HTML stream past the hydration script's opening tag, so the JSON starts next. */
    private static boolean seek(InputStream in) throws IOException {
        int m = 0, b;
        while ((b = in.read()) >= 0) {
            if (b == MARKER[m]) {
                if (++m == MARKER.length) {
                    while ((b = in.read()) >= 0 && b != '>') { /* rest of the tag */ }
                    return b == '>';
                }
            } else {
                m = b == MARKER[0] ? 1 : 0;
            }
        }
        return false;
    }

    private static Item.Shelf shelf(JSONObject c) {
        JSONArray ents = c.optJSONArray("entities");
        if (ents == null) return null;
        String type = c.optString("containerType");
        String title = c.optString("title", "");
        if (title.isEmpty()) title = c.optString("text", "");
        if ("StandardHero".equals(type) || "Carousel Title".equals(title)) title = "Featured";
        if (title.isEmpty()) title = "Grid".equals(type) ? "Results" : "";
        boolean chart = "Charts".equals(type);
        long now = System.currentTimeMillis();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < ents.length(); i++) {
            Item it = entity(ents.optJSONObject(i), i, chart, now);
            if (it != null) items.add(it);
        }
        Item.Shelf s = new Item.Shelf(title, items);
        String target = c.optString("paginationTargetId"), token = c.optString("paginationServiceToken");
        if (!target.isEmpty() && !token.isEmpty()) s.more = target + "\n" + token + "\n" + c.optInt("paginationStartIndex", items.size());
        return s;
    }

    /**
     * One tile: a title (its page), a live station (plays its "Watch live" stream), or a link to another browse
     * page (a genre, a subscription's storefront, a league). Chart rows carry their rank as the badge.
     */
    private static Item entity(JSONObject e, int index, boolean chart, long now) {
        if (e == null) return null;
        JSONObject st = e.optJSONObject("station");
        if (st != null) return station(e, st, now);
        String name = e.optString("displayTitle", "");
        JSONObject lo = e.optJSONObject("link");
        String link = lo == null ? "" : lo.optString("url");
        if (name.isEmpty() || !link.startsWith("/")) return null;
        String image = image(e.optJSONObject("images"));
        if (!link.startsWith("/detail/")) {
            // a browse page: genre, collection, subscription storefront, league
            Item it = new Item(Item.PAGE, link, name, glance(e), image, "", dropRef(link));
            it.description = e.optString("synopsis", "");
            return it;
        }
        String type = e.optString("entityType", "");
        StringBuilder sub = new StringBuilder();
        String fm = JsonScan.path(e, "entitlementCues", "focusMessage") == null ? ""
                : e.optJSONObject("entitlementCues").optJSONObject("focusMessage").optString("message");
        if (!type.isEmpty()) sub.append(type);
        if (e.optInt("releaseYear", 0) > 0) sub.append(sub.length() > 0 ? " · " : "").append(e.optInt("releaseYear"));
        if (!fm.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(fm);
        boolean series = type.toLowerCase(java.util.Locale.ROOT).contains("tv");
        Item it = new Item(series ? Item.SERIES : Item.VIDEO, e.optString("titleID", link), name, sub.toString(),
                image, chart ? String.valueOf(index + 1) : "", stripQuery(link));
        it.description = e.optString("synopsis", "");
        return it;
    }

    private static String image(JSONObject img) {
        if (img == null) return "";
        for (String k : new String[]{"cover", "hero", "boxart", "packshot"}) {
            JSONObject o = img.optJSONObject(k);
            if (o != null && !o.optString("url").isEmpty()) return sized(o.optString("url"));
        }
        return "";
    }

    /** The short line Amazon shows on a browse tile ("Subscribe", "Free trial"), else empty. */
    private static String glance(JSONObject e) {
        JSONObject g = JsonScan.path(e, "entitlementCues", "glanceMessage");
        return g == null ? "" : g.optString("message", "");
    }

    /**
     * A live station tile: the station's name, what is on now (from its schedule) and its "Watch live" link,
     * which plays in Prime's own player like any title.
     */
    private static Item station(JSONObject e, JSONObject st, long now) {
        JSONObject pa = e.optJSONObject("playbackAction");
        String url = pa == null ? "" : pa.optString("fallbackUrl");
        // a channel of an add-on this account has not subscribed to: Amazon offers a trial instead of the stream
        JSONObject fmo = JsonScan.path(e, "entitlementCues", "focusMessage");
        String offer = fmo == null ? "" : fmo.optString("message", "");
        if (!url.startsWith("/") && offer.isEmpty()) return null;
        String name = st.optString("name", "");
        JSONArray sched = st.optJSONArray("schedule");
        JSONObject on = null, next = null;
        if (sched != null) for (int i = 0; i < sched.length(); i++) {
            JSONObject s = sched.optJSONObject(i);
            if (s == null) continue;
            if (on == null) on = s;
            if (s.optLong("start") <= now && now < s.optLong("end")) {
                on = s;
                for (int j = i + 1; j < sched.length() && next == null; j++) next = sched.optJSONObject(j);
                break;
            }
        }
        String nextLine = "";
        JSONObject nmd = next == null ? null : next.optJSONObject("metadata");
        if (nmd != null) {
            nextLine = nmd.optString("seriesTitle", "");
            if (nextLine.isEmpty() || nmd.optBoolean("hideSeriesTitle")) nextLine = nmd.optString("title", "");
            String nt = nmd.optString("localizedTimeRange", "");
            if (!nextLine.isEmpty()) nextLine = "Next: " + nextLine + (nt.isEmpty() ? "" : " · " + nt);
        }
        JSONObject md = on == null ? null : on.optJSONObject("metadata");
        String show = "", image = "";
        if (md != null) {
            show = md.optString("seriesTitle", "");
            String t = md.optString("title", "");
            if (show.isEmpty() || md.optBoolean("hideSeriesTitle")) show = t;
            else if (!t.isEmpty() && !t.equals(show)) show += ": " + t;
            JSONObject im = md.optJSONObject("image");
            if (im != null && !im.optString("url").isEmpty()) image = sized(im.optString("url"));
            String time = md.optString("localizedTimeRange", "");
            if (!time.isEmpty()) show += (show.isEmpty() ? "" : " · ") + time;
        }
        if (image.isEmpty()) image = st.optString("logo", "");
        if (name.isEmpty()) name = e.optString("title", "Live");
        Item it;
        if (url.startsWith("/")) {
            it = new Item(Item.LIVE, st.optString("id", url), name, show, image, "LIVE", dropRef(url));
        } else {
            it = new Item(Item.PAGE, "offer:" + st.optString("id", name), name, offer + (show.isEmpty() ? "" : " · " + show),
                    image, "", "");
        }
        String syn = md == null ? "" : md.optString("synopsis", "");
        it.description = nextLine.isEmpty() ? syn : nextLine + (syn.isEmpty() ? "" : "\n" + syn);
        return it;
    }

    /**
     * Live guide: every station of the Live TV page once, favorites first, each tile with what is on now
     * (subtitle) and next (first line of the description), in rows of eight.
     */
    private Item.Page guide(Item.Page src) {
        java.util.LinkedHashMap<String, Item> all = new java.util.LinkedHashMap<>();
        for (Item.Shelf sh : src.shelves) for (Item it : sh.items) if (it.kind == Item.LIVE && !all.containsKey(it.id)) all.put(it.id, it);
        Item.Page p = new Item.Page();
        List<Item> fav = new ArrayList<>();
        for (Item f : LiveLists.favorites(app, "prime")) {
            Item now = all.remove(f.id);
            if (now != null) fav.add(now);
        }
        if (!fav.isEmpty()) p.shelves.add(new Item.Shelf("★ Favorites · now and next", fav));
        List<Item> rest = new ArrayList<>(all.values());
        for (int i = 0; i < rest.size(); i += 8) {
            p.shelves.add(new Item.Shelf(i == 0 ? "Channels · now and next  ·  hold OK or ▶▶ adds a favorite" : "", rest.subList(i, Math.min(rest.size(), i + 8))));
        }
        if (p.shelves.isEmpty()) p.empty = src.empty.isEmpty() ? "No live channels right now." : src.empty;
        return p;
    }

    /** A site link without its click-tracking ref_ parameter (other parameters, such as tokens, stay). */
    private static String dropRef(String link) {
        String l = link.replaceAll("([?&])ref_=[^&]*&?", "$1");
        if (l.endsWith("?") || l.endsWith("&")) l = l.substring(0, l.length() - 1);
        return l;
    }

    private static String stripQuery(String link) {
        int q = link.indexOf('?');
        return q > 0 ? link.substring(0, q) : link;
    }

    /** Amazon's image service: drop the page's 1920 px overlay recipe and ask for a 480 px wide JPEG. */
    static String sized(String url) {
        int slash = url.lastIndexOf('/', url.indexOf("._") > 0 ? url.indexOf("._") : url.length() - 1);
        int dot = url.indexOf('.', slash);
        if (slash < 0 || dot < 0) return url;
        String ext = url.endsWith(".jpeg") ? ".jpeg" : url.endsWith(".png") ? ".png" : ".jpg";
        return url.substring(0, dot) + "._SX480_" + ext;
    }


    /**
     * A title page as native data: header (artwork, synopsis, Play/Resume with Amazon's resume point, trailer,
     * watchlist), the season selector, the episodes and "Customers also watched". Everything comes from the page's
     * hydration JSON; nothing of Amazon's page is shown.
     */
    @Override
    public Item.Page open(Item item) throws IOException {
        // a browse tile (genre, subscription, league, a person's titles): that storefront page
        if (item.id.startsWith("offer:")) {
            // subscribing happens on Amazon (a purchase); ClearTV does not take part in it
            Item.Page p = new Item.Page();
            p.empty = item.title + " is part of a Prime Video add-on this account does not have (" + item.subtitle.split(" · ")[0]
                    + "). Add it in Amazon's Prime Video app or at amazon.com; it then plays here.";
            return p;
        }
        if (!item.extra.startsWith("/detail/")) return storefront(BASE + item.extra);
        final Map<String, JSONObject> episodes = new HashMap<>();
        final Map<String, String> links = new HashMap<>();
        final Map<String, String> compact = new HashMap<>();
        final Map<String, JSONObject> actions = new HashMap<>();
        final JSONObject[] header = new JSONObject[1];
        final String[] headerGti = new String[1];
        final JSONObject[] watch = new JSONObject[1];
        final List<String> order = new ArrayList<>();
        final List<String> episodePages = new ArrayList<>();
        final List<Item.Season> seasons = new ArrayList<>();
        final int[] selectedSeason = {0};
        final List<Item.Shelf> more = new ArrayList<>();
        try (Http.Response r = Http.open(BASE + item.extra, headers(), null, null, true)) {
            InputStream in = new java.io.BufferedInputStream(r.body, 32768);
            if (!seek(in)) throw new IOException("Prime Video changed its page format (no hydration data)");
            JsonScan.scan(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), DETAIL_KEYS, NONE, DETAIL_SKIP,
                    new JsonScan.Sink() {
                        @Override
                        public void onValue(String key, String name, JSONObject v) {
                            switch (key) {
                                case "detail": {
                                    JSONObject hd = v.optJSONObject("headerDetail");
                                    if (hd != null && header[0] == null && hd.keys().hasNext()) {
                                        headerGti[0] = hd.keys().next();
                                        header[0] = hd.optJSONObject(headerGti[0]);
                                    }
                                    JSONObject map = v.optJSONObject("detail");
                                    if (map != null) for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                                        String gti = it.next();
                                        JSONObject d = map.optJSONObject(gti);
                                        if (d != null && "episode".equals(d.optString("titleType"))) episodes.put(gti, d);
                                    }
                                    break;
                                }
                                case "self":
                                    for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                                        String gti = it.next();
                                        JSONObject o = v.optJSONObject(gti);
                                        if (o == null) continue;
                                        links.put(gti, stripQuery(o.optString("link")));
                                        compact.put(gti, o.optString("compactGTI"));
                                    }
                                    break;
                                case "watchlist":
                                    if (watch[0] == null && v.keys().hasNext()) watch[0] = v.optJSONObject(v.keys().next());
                                    break;
                                case "action":
                                    for (String side : new String[]{"atf", "btf"}) {
                                        JSONObject m = v.optJSONObject(side);
                                        if (m == null) continue;
                                        for (Iterator<String> it = m.keys(); it.hasNext(); ) {
                                            String gti = it.next();
                                            if (m.optJSONObject(gti) != null) actions.put(gti, m.optJSONObject(gti));
                                        }
                                    }
                                    break;
                                case "seasons":
                                    for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                                        JSONArray a = v.optJSONArray(it.next());
                                        if (a == null) continue;
                                        for (int i = 0; i < a.length(); i++) {
                                            JSONObject o = a.optJSONObject(i);
                                            if (o == null || !o.optString("seasonLink").startsWith("/detail/")) continue;
                                            String link = stripQuery(o.optString("seasonLink"));
                                            String label = o.optString("displayName", "Season " + o.optInt("sequenceNumber"));
                                            if (o.optBoolean("isSelected")) selectedSeason[0] = seasons.size();
                                            seasons.add(new Item.Season(label,
                                                    new Item(Item.SERIES, link, item.title, label, "", "", link), null));
                                        }
                                    }
                                    break;
                                case "episodeList": {
                                    JSONArray ids = v.optJSONArray("cardTitleIds");
                                    if (ids != null) for (int i = 0; i < ids.length(); i++) order.add(ids.optString(i));
                                    // a long season comes in pages of ~24 episodes: the other pages' tokens
                                    JSONArray pages = JsonScan.path(v, "actions") == null ? null
                                            : v.optJSONObject("actions").optJSONArray("episodePages");
                                    if (pages != null) for (int i = 0; i < pages.length(); i++) {
                                        JSONObject o = pages.optJSONObject(i);
                                        if (o != null && !o.optBoolean("isSelected") && !o.optString("token").isEmpty()) {
                                            episodePages.add(o.optString("token"));
                                        }
                                    }
                                    break;
                                }
                                case "containers":
                                    for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                                        JSONArray a = v.optJSONArray(it.next());
                                        if (a == null) continue;
                                        for (int i = 0; i < a.length(); i++) {
                                            JSONObject c = a.optJSONObject(i);
                                            Item.Shelf sh = c == null ? null : shelf(c);
                                            if (sh != null && !sh.items.isEmpty()) more.add(sh);
                                        }
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                    });
        }
        if (headerGti[0] != null) {
            int max = 12; // pages of ~24: up to ~300 episodes of a season
            for (int i = 0; i < Math.min(max, episodePages.size()); i++) {
                try {
                    episodePage(headerGti[0], episodePages.get(i), episodes, actions, links, order);
                } catch (IOException e) {
                    Log.w(TAG, "episode page: " + e.getMessage());
                    break;
                }
            }
        }
        Item.Page p = new Item.Page();
        Item.Detail d = new Item.Detail();
        JSONObject h = header[0];
        JSONObject act = headerGti[0] == null ? null : actions.get(headerGti[0]);
        d.title = h == null ? item.title : h.optString("parentTitle", "").isEmpty() ? h.optString("title", item.title) : h.optString("parentTitle");
        d.synopsis = h == null ? item.description : h.optString("synopsis", item.description);
        d.image = item.image;
        if (h != null && h.optJSONObject("images") != null) {
            JSONObject im = h.optJSONObject("images");
            for (String k : new String[]{"heroshot", "covershot", "packshot"}) {
                if (!im.optString(k).isEmpty()) {
                    d.image = wide(im.optString(k));
                    break;
                }
            }
        }
        d.meta = meta(h, act);
        // Play / Resume and the trailer, with Amazon's own labels and resume point
        if (act != null) {
            for (String list : new String[]{"primaryActions", "secondaryActions"}) {
                JSONArray a = act.optJSONArray(list);
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject pb = JsonScan.path(a.optJSONObject(i), "payload", "playback");
                    if (pb == null || !pb.optString("playbackURL").startsWith("/")) continue;
                    Item play = new Item(Item.VIDEO, pb.optString("playbackID"), d.title, subtitle(pb.optString("label"), d.meta), d.image, "",
                            pb.optString("playbackURL"));
                    if (pb.optBoolean("isTrailer")) {
                        if (d.trailer == null) d.trailer = play;
                    } else if (d.play == null) {
                        d.play = play;
                        long resume = pb.optLong("resumeTime", 0);
                        d.playLabel = pb.optString("label", resume > 0 ? "Resume" : "Play");
                        if (resume > 0 && pb.optInt("minutesRemaining", 0) > 0) d.playLabel += " · " + pb.optInt("minutesRemaining") + " min left";
                    }
                }
            }
            if (d.play == null) {
                String why = entitlement(act);
                d.note = why.isEmpty() ? "Not available to play here" : why + " · not included for this account";
            }
        }
        if (watch[0] != null) {
            JSONObject ep = watch[0].optJSONObject("endpoint");
            String partial = ep == null ? "" : ep.optString("partialURL");
            if (partial.startsWith("/auth-redirect")) {
                d.watchlist = Item.Detail.WL_SIGN_IN;
            } else if (!partial.isEmpty()) {
                d.watchlist = "Remove".equalsIgnoreCase(watch[0].optString("tag")) ? Item.Detail.WL_REMOVE : Item.Detail.WL_ADD;
                d.watchlistUrl = BASE + partial + query(ep.optJSONObject("query"));
            }
        }
        p.detail = d;
        // episodes in Amazon's order, each playing straight away when this account may play it
        List<String> ids = new ArrayList<>(order);
        List<String> rest = new ArrayList<>(episodes.keySet());
        rest.removeAll(ids);
        Collections.sort(rest, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(episodes.get(a).optInt("episodeNumber"), episodes.get(b).optInt("episodeNumber"));
            }
        });
        ids.addAll(rest);
        List<Item> eps = new ArrayList<>();
        // the show's page (this page) for "Go to show" on an episode saved to Continue watching or the watchlist
        Item show = new Item(Item.SERIES, item.id, d.title, item.subtitle, d.image.isEmpty() ? item.image : d.image, "", item.extra);
        show.description = d.synopsis.length() > 300 ? d.synopsis.substring(0, 300) : d.synopsis;
        for (String gti : ids) {
            JSONObject e = episodes.get(gti);
            if (e == null) continue;
            JSONObject ea = actions.get(gti);
            String playUrl = null;
            JSONArray pa = ea == null ? null : ea.optJSONArray("primaryActions");
            if (pa != null) for (int i = 0; i < pa.length() && playUrl == null; i++) {
                JSONObject pb = JsonScan.path(pa.optJSONObject(i), "payload", "playback");
                if (pb != null && !pb.optBoolean("isTrailer") && pb.optString("playbackURL").startsWith("/")) playUrl = pb.optString("playbackURL");
            }
            String link = links.get(gti);
            if (playUrl == null && (link == null || link.isEmpty())) continue;
            JSONObject img = e.optJSONObject("images");
            String image = img == null ? "" : sized(img.optString("packshot", img.optString("covershot", "")));
            String sub = e.optString("runtime", "");
            if (playUrl == null) {
                String why = ea == null ? "" : entitlement(ea);
                sub += (sub.isEmpty() ? "" : " · ") + (why.isEmpty() ? "Not included" : why);
            }
            Item it = new Item(Item.VIDEO, gti, "E" + e.optInt("episodeNumber") + " · " + e.optString("title"), sub, image, "",
                    playUrl != null ? playUrl : link);
            it.description = e.optString("synopsis", "");
            it.channel = show;
            eps.add(it);
        }
        if (!eps.isEmpty()) {
            p.shelves.add(new Item.Shelf("Episodes", eps));
            p.grid = 0;
            if (d.play != null && d.play.title.equals(d.title)) {
                // a show's Play is its first (or next) episode: name it like Amazon does
                d.play = new Item(Item.VIDEO, d.play.id, d.title, subtitle(d.playLabel, d.meta), d.image, "", d.play.extra);
            }
        }
        if (seasons.size() > 1) {
            p.seasons.addAll(seasons);
            p.season = selectedSeason[0];
        }
        Item.Shelf cast = cast(h);
        if (cast != null) p.shelves.add(cast);
        p.shelves.addAll(more);
        if (p.isEmpty() && d.play == null && d.trailer == null && d.note.isEmpty()) p.empty = "Nothing to watch on this page.";
        return p;
    }

    /** One more page of a season's episodes (the site's getDetailWidgets EpisodeList widget). */
    private void episodePage(String titleId, String token, Map<String, JSONObject> episodes, Map<String, JSONObject> actions,
                             Map<String, String> links, List<String> order) throws IOException {
        String widgets = "[]";
        try {
            JSONObject w = new JSONObject();
            w.put("widgetType", "EpisodeList");
            w.put("widgetToken", token);
            widgets = new JSONArray().put(w).toString();
        } catch (org.json.JSONException ignored) {
            // plain strings always fit
        }
        String url = BASE + Config.s("prime.widgetsApi", "/api/getDetailWidgets") + "?titleID=" + URLEncoder.encode(titleId, "UTF-8")
                + "&isTvodOnRow=&widgets=" + URLEncoder.encode(widgets, "UTF-8");
        String body;
        try (Http.Response r = Http.open(url, jsonHeaders(), null, null, false)) {
            if (r.code >= 300) throw new Http.Error(r.code, "", "episodes");
            body = new String(Http.readAll(r.body, 4 << 20), StandardCharsets.UTF_8);
        }
        try {
            JSONArray eps = JsonScan.path(new JSONObject(body), "widgets", "episodeList") == null ? null
                    : new JSONObject(body).getJSONObject("widgets").getJSONObject("episodeList").optJSONArray("episodes");
            if (eps == null) return;
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                JSONObject det = e.optJSONObject("detail");
                String gti = e.optString("titleID", "");
                if (det == null || gti.isEmpty() || episodes.containsKey(gti)) continue;
                episodes.put(gti, det);
                if (e.optJSONObject("action") != null) actions.put(gti, e.optJSONObject("action"));
                JSONObject self = e.optJSONObject("self");
                if (self != null && !self.optString("link").isEmpty()) links.put(gti, stripQuery(self.optString("link")));
                order.add(gti);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Prime Video sent unreadable episodes");
        }
    }

    /** The title's cast and crew; each opens Prime Video's search for that name (the site's own person link). */
    private static Item.Shelf cast(JSONObject h) {
        JSONObject c = h == null ? null : h.optJSONObject("contributors");
        if (c == null) return null;
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[][] roles = {{"directors", "Director"}, {"cast", "Cast"}, {"producers", "Producer"}};
        for (String[] role : roles) {
            JSONArray a = c.optJSONArray(role[0]);
            if (a != null) for (int i = 0; i < a.length() && items.size() < 30; i++) {
                JSONObject o = a.optJSONObject(i);
                String name = o == null ? "" : o.optString("name", "").trim();
                if (name.isEmpty() || !seen.add(name + role[1])) continue;
                String q;
                try {
                    q = "/search/?phrase=" + URLEncoder.encode(name, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    continue;
                }
                items.add(new Item(Item.PAGE, "person:" + name, name, role[1], "", "", q));
            }
        }
        return items.isEmpty() ? null : new Item.Shelf("Cast and crew", items);
    }

    private static String entitlement(JSONObject act) {
        JSONObject m = act.optJSONObject("messages");
        JSONObject fm = m == null ? null : m.optJSONObject("focusMessage");
        JSONObject dv = fm == null ? null : fm.optJSONObject("dvMessage");
        return dv == null ? "" : dv.optString("string", "");
    }

    private static final java.util.regex.Pattern ACTION_LABEL = java.util.regex.Pattern.compile(
            "(?i)^\\s*(play|resume|watch|watch now|continue|continue watching|start over)\\b(\\s*[·•-].*)?$");

    /**
     * What to show under a title in the player and on Continue watching: Amazon's button label when it names
     * something ("Episode 3"), else the title's details, never a bare action such as "Play" or "Resume".
     */
    static String subtitle(String label, String meta) {
        if (label == null || label.isEmpty() || ACTION_LABEL.matcher(label).matches()) return meta == null ? "" : meta;
        return label;
    }

    private static String meta(JSONObject h, JSONObject act) {
        List<String> parts = new ArrayList<>();
        if (h != null) {
            if (h.optInt("seasonNumber", 0) > 0) parts.add("Season " + h.optInt("seasonNumber"));
            if (h.optInt("releaseYear", 0) > 0) parts.add(String.valueOf(h.optInt("releaseYear")));
            JSONObject rb = h.optJSONObject("ratingBadge");
            if (rb != null && !rb.optString("displayText").isEmpty()) parts.add(rb.optString("displayText"));
            long dur = h.optLong("duration", 0);
            if (dur > 0) parts.add(dur >= 3600 ? (dur / 3600) + " h " + (dur % 3600) / 60 + " min" : (dur / 60) + " min");
            JSONArray g = h.optJSONArray("genres");
            if (g != null) for (int i = 0; i < Math.min(2, g.length()); i++) {
                JSONObject o = g.optJSONObject(i);
                if (o != null && !o.optString("text").isEmpty()) parts.add(o.optString("text"));
            }
            JSONObject ar = h.optJSONObject("amazonRating");
            if (ar != null && ar.optDouble("value", 0) > 0) {
                parts.add(String.format(java.util.Locale.US, "★ %.1f", ar.optDouble("value"))
                        + (ar.optInt("count", 0) > 0 ? " (" + ar.optInt("count") + ")" : ""));
            }
            if (dur <= 0 && !h.optString("runtime").isEmpty()) parts.add(h.optString("runtime"));
            // the formats Amazon lists for the title
            if (h.optBoolean("isUhd")) parts.add("UHD");
            if (h.optBoolean("isDolbyVision")) parts.add("Dolby Vision");
            else if (h.optBoolean("isHdr") || h.optBoolean("isHdr10Plus")) parts.add("HDR");
            if (h.optBoolean("isDolbyAtmos")) parts.add("Dolby Atmos");
            if (h.optBoolean("isXRay")) parts.add("X-Ray");
            if (h.optBoolean("isClosedCaption")) parts.add("CC");
        }
        if (act != null && !entitlement(act).isEmpty()) parts.add(entitlement(act));
        return android.text.TextUtils.join(" · ", parts);
    }

    private static String query(JSONObject q) {
        if (q == null) return "";
        StringBuilder b = new StringBuilder();
        for (Iterator<String> it = q.keys(); it.hasNext(); ) {
            String k = it.next();
            try {
                b.append(b.length() == 0 ? "?" : "&").append(URLEncoder.encode(k, "UTF-8")).append('=')
                        .append(URLEncoder.encode(q.optString(k), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) {
                // UTF-8 is always there
            }
        }
        return b.toString();
    }

    /** A 1280 px wide hero for the detail screen. */
    private static String wide(String url) {
        String s = sized(url);
        return s.replace("._SX480_", "._SX1280_");
    }

    /**
     * Adds or removes the title from the watchlist with the page's own endpoint and the stored session; returns the
     * new state (Detail.WL_ADD or WL_REMOVE).
     */
    @Override
    public int toggleWatchlist(Item.Detail d) throws IOException {
        if (d.watchlistUrl.isEmpty()) throw new IOException("Sign in to Prime Video to use the watchlist.");
        Map<String, String> h = headers();
        h.put("Accept", "application/json, text/html");
        h.put("X-Requested-With", "XMLHttpRequest");
        try (Http.Response r = Http.open(d.watchlistUrl, h, null, null, false)) {
            if (r.code >= 400) throw new Http.Error(r.code, "", Http.shortUrl(d.watchlistUrl));
            if (r.code >= 300 && !signedIn()) throw new IOException("Sign in to Prime Video to use the watchlist.");
        }
        boolean adding = d.watchlist == Item.Detail.WL_ADD;
        String url = d.watchlistUrl;
        d.watchlistUrl = adding ? url.replace("tag=Add", "tag=Remove") : url.replace("tag=Remove", "tag=Add");
        d.watchlist = adding ? Item.Detail.WL_REMOVE : Item.Detail.WL_ADD;
        return d.watchlist;
    }

    @Override
    public boolean opensDetail(Item item) {
        return item.extra.startsWith("/detail/") && !item.extra.contains("autoplay");
    }

    @Override
    public Item.Page openDetail(Item item) throws IOException {
        return open(item);
    }

    /** Prime Video has no channels or creators; an episode's show page is the closest. Movies and stations have none. */
    @Override
    public String channelLabel() {
        return "Go to show";
    }

    @Override
    public Playback play(Item item) {
        Playback p = new Playback();
        p.title = item.title;
        // entries saved before 0.1.3 carry Amazon's button label ("Play") as their subtitle
        p.subtitle = subtitle(item.subtitle, "");
        String url = BASE + item.extra;
        if (!url.contains("autoplay=")) url += (url.contains("?") ? "&" : "?") + "autoplay=1";
        p.webUrl = url;
        p.webTvUa = false;
        return p;
    }

    // ----------------------------------------------------------------------------------- account
    // Sign-in pairs ClearTV like a TV: Amazon's code-based linking (the code is entered at amazon.com/code on a
    // phone) registers a device and hands back the account's website cookies. No Amazon page is ever shown.

    @Override public int signInKind() { return SIGNIN_CODE; }

    @Override
    public String[] webDomains() {
        return new String[]{"primevideo.com", "amazon.com", "media-amazon.com", "ssl-images-amazon.com", "amazon-adsystem.com"};
    }

    @Override
    public String codeHelp() {
        return "On your phone or computer, signed in to Amazon, go to";
    }

    private static String authApi() {
        return Config.s("prime.authApi", "https://api.amazon.com");
    }

    /** This TV's device serial for Amazon, made once. */
    private String serial() {
        String s = prefs.getString("serial", "");
        if (s.isEmpty()) {
            s = java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
            prefs.edit().putString("serial", s).apply();
        }
        return s;
    }

    private JSONObject registration() throws org.json.JSONException {
        return new JSONObject().put("domain", Config.s("prime.regDomain", "Device")) // codepair refuses "DeviceLegacy" (HTTP 400 InvalidValue)
                .put("device_type", Config.s("prime.deviceType", "A43PXU4ZN2AL1"))
                .put("device_serial", serial())
                .put("app_name", Config.s("prime.appName", "com.amazon.avod.thirdpartyclient"))
                .put("app_version", Config.s("prime.appVersion", "296016847"))
                .put("device_model", android.os.Build.MODEL)
                .put("os_version", android.os.Build.VERSION.RELEASE);
    }

    private static Map<String, String> authHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Accept-Language", "en-US");
        h.put("x-amzn-identity-auth-domain", authApi().replace("https://", ""));
        return h;
    }

    @Override
    public Code startCode() throws IOException {
        try {
            JSONObject o = new JSONObject(Http.postJson(authApi() + "/auth/create/codepair", authHeaders(),
                    new JSONObject().put("code_data", registration()).toString()));
            String code = o.optString("public_code");
            if (code.isEmpty()) throw new IOException("Amazon did not give a code");
            return new Code(code, Config.s("prime.activate", "amazon.com/code"), o.optString("private_code"),
                    o.optInt("polling_interval_in_seconds", 5), System.currentTimeMillis() + o.optInt("expires_in", 600) * 1000L);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public String codeQrUrl(Code c) {
        return Config.s("prime.activateQr", "https://www.amazon.com/a/code?cbl-code={code}").replace("{code}", android.net.Uri.encode(c.userCode));
    }

    /**
     * Asks Amazon whether the code was entered. Until then the register call is refused; once approved it returns
     * the device's website cookies, which become the session of the native rows and the hidden player.
     */
    @Override
    public boolean pollCode(Code c) throws IOException {
        String resp;
        try {
            JSONObject body = new JSONObject()
                    .put("auth_data", new JSONObject().put("code_pair",
                            new JSONObject().put("public_code", c.userCode).put("private_code", c.deviceCode)))
                    .put("registration_data", registration())
                    .put("requested_token_type", new JSONArray().put("bearer").put("website_cookies"))
                    .put("cookies", new JSONObject().put("domain", ".amazon.com").put("website_cookies", new JSONArray()))
                    .put("requested_extensions", new JSONArray().put("device_info").put("customer_info"));
            resp = Http.postJson(authApi() + "/auth/register", authHeaders(), body.toString());
        } catch (Http.Error e) {
            if (e.code >= 400 && e.code < 500) {
                if (e.body.toLowerCase(java.util.Locale.ROOT).contains("expired")) throw new IOException("code expired");
                return false; // not entered yet
            }
            throw e;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        JSONObject ok;
        try {
            ok = JsonScan.path(new JSONObject(resp), "response", "success");
        } catch (org.json.JSONException e) {
            throw new IOException("Amazon's answer was not readable");
        }
        if (ok == null) return false;
        // The register answer carries the account's amazon.com website cookies. primevideo.com accepts the same
        // cookies once each "-main" name gets an "-av" suffix (at-main -> at-main-av), the way open-source Prime
        // clients do it. 0.1.5 renamed at-main to at-av (wrong name); 0.1.6 tried Amazon's token-to-cookie exchange,
        // which answers HTTP 400 for this app identity. No exchange call is needed.
        JSONObject tokens = JsonScan.path(ok, "tokens");
        JSONObject bearer = tokens == null ? null : tokens.optJSONObject("bearer");
        String refresh = bearer == null ? "" : bearer.optString("refresh_token");
        JSONArray amazonCookies = tokens == null ? null : tokens.optJSONArray("website_cookies");
        if (!refresh.isEmpty()) prefs.edit().putString("refresh", refresh).apply();
        if (amazonCookies == null || amazonCookies.length() == 0) throw new Source.SignInError("Amazon approved the code but sent no session");
        WebActivity.setCookies("https://www.amazon.com/", cookieLines(amazonCookies, ".amazon.com", null));
        JSONObject who = JsonScan.path(ok, "extensions", "customer_info");
        prefs.edit().putString("account", who == null ? "" : who.optString("given_name", who.optString("name", ""))).apply();
        forgetProfile();
        Map<String, String> jar = new java.util.LinkedHashMap<>();
        for (int i = 0; i < amazonCookies.length(); i++) {
            JSONObject k = amazonCookies.optJSONObject(i);
            if (k != null && !k.optString("Name").isEmpty()) jar.put(k.optString("Name"), k.optString("Value").replace("\"", ""));
        }
        if (!adoptSession(jar)) throw new Source.SignInError("Amazon approved the code, but Prime Video did not accept the session");
        return true;
    }

    /** primevideo.com session from amazon.com account cookies: each "-main" cookie is also sent as "-main-av". */
    private boolean adoptSession(Map<String, String> amazon) throws IOException {
        Map<String, String> pv = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : amazon.entrySet()) {
            pv.put(e.getKey(), e.getValue());
            if (e.getKey().endsWith("-main")) pv.put(e.getKey() + "-av", e.getValue());
        }
        List<String> lines = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pv.entrySet()) {
            lines.add(e.getKey() + "=" + e.getValue() + "; Domain=.primevideo.com; Path=/; Secure");
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        if (!storeSession(sb.toString())) return false;
        WebActivity.setCookies(BASE + "/", lines);
        return true;
    }

    private static volatile boolean recoveryTried;

    /**
     * A code approved under 0.1.6's failed exchange left the account's amazon.com cookies in the web cookie store;
     * adopt them once instead of asking for a new code.
     */
    private void recoverSession() {
        if (recoveryTried || signedIn() || prefs.getString("refresh", "").isEmpty()) return;
        recoveryTried = true;
        try {
            String raw = android.webkit.CookieManager.getInstance().getCookie("https://www.amazon.com/");
            if (raw == null || !raw.contains("at-main=")) return;
            Map<String, String> jar = new java.util.LinkedHashMap<>();
            for (String part : raw.split(";\\s*")) {
                int eq = part.indexOf('=');
                if (eq > 0) jar.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
            boolean ok = adoptSession(jar);
            android.util.Log.i("CTV.Prime", "stored sign-in recovered: " + ok);
        } catch (Exception e) {
            android.util.Log.w("CTV.Prime", "stored sign-in not recovered: " + e.getClass().getSimpleName());
        }
    }

    /** Set-Cookie lines for the WebView; also folds name=value pairs into jar when given. */
    private static List<String> cookieLines(JSONArray wc, String domain, StringBuilder jar) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < wc.length(); i++) {
            JSONObject k = wc.optJSONObject(i);
            if (k == null || k.optString("Name").isEmpty()) continue;
            String name = k.optString("Name"), value = k.optString("Value").replace("\"", "");
            out.add(name + "=" + value + "; Domain=" + domain + "; Path=/; Secure"
                    + (k.optString("Expires").isEmpty() ? "" : "; Expires=" + k.optString("Expires")));
            if (jar != null) {
                if (jar.length() > 0) jar.append("; ");
                jar.append(name).append('=').append(value);
            }
        }
        return out;
    }

    /** Stores the session cookies and checks them: signed in when the watchlist opens. */
    private boolean storeSession(String cookies) throws IOException {
        prefs.edit().putString("cookies", cookies == null ? "" : cookies).apply();
        try (Http.Response r = Http.open(BASE + "/mystuff/watchlist/", headers(), null, null, false)) {
            boolean ok = r.code == 200;
            prefs.edit().putBoolean("signed_in", ok).apply();
            if (!ok) prefs.edit().remove("cookies").apply();
            return ok;
        }
    }

    /** Folds a response's Set-Cookie headers into the stored session and the web player's cookie store. */
    private void mergeCookies(List<String> setCookies) {
        if (setCookies.isEmpty()) return;
        Map<String, String> jar = new java.util.LinkedHashMap<>();
        for (String part : prefs.getString("cookies", "").split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) jar.put(part.substring(0, eq), part.substring(eq + 1));
        }
        for (String line : setCookies) {
            String first = line.split(";", 2)[0];
            int eq = first.indexOf('=');
            if (eq <= 0) continue;
            String name = first.substring(0, eq).trim(), value = first.substring(eq + 1).trim();
            String low = line.toLowerCase(java.util.Locale.ROOT);
            if (value.isEmpty() || low.contains("max-age=0") || low.contains("expires=thu, 01 jan 1970")) jar.remove(name);
            else jar.put(name, value);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs.edit().putString("cookies", sb.toString()).apply();
        WebActivity.setCookies(BASE + "/", setCookies);
    }

    // ---------------------------------------------------------------------------------- profiles

    @Override public boolean hasProfiles() { return true; }

    /**
     * The profiles data: the profile list and the PIN rules (each profile's enter permission). Asked for as JSON
     * first (the web app's own XHR form of the page, as the Kodi Prime add-on does), then the HTML page's data
     * scripts. Prime's current page no longer carries the list in the HTML, which is why the picker used to find
     * nothing and close at once.
     */
    private JSONObject[] profilesData() throws IOException {
        boolean answered = false;
        for (String path : new String[]{"/gp/video/profiles", "/profiles"}) {
            Map<String, String> h = headers();
            h.put("Accept", "application/json");
            h.put("X-Requested-With", "XMLHttpRequest");
            String body;
            try (Http.Response r = Http.open(BASE + path, h, null, null, false)) {
                if (r.code >= 300) {
                    Log.w(TAG, "profiles json " + path + ": HTTP " + r.code);
                    continue;
                }
                answered = true;
                body = new String(Http.readAll(r.body, 6 << 20), StandardCharsets.UTF_8).trim();
            }
            JSONObject[] d = body.startsWith("{") ? profilesFrom(Collections.singletonList(body)) : profilesFrom(scripts(body));
            if (d[0] != null) return d;
            Log.w(TAG, "profiles json " + path + ": no list (" + body.length() + " chars, keys " + keysOf(body) + ")");
        }
        String html;
        try (Http.Response r = Http.open(BASE + "/profiles", headers(), null, null, false)) {
            if (r.code >= 300) return answered ? new JSONObject[]{null, null} : null;
            html = new String(Http.readAll(r.body, 6 << 20), StandardCharsets.UTF_8);
        }
        return profilesFrom(scripts(html));
    }

    private static String keysOf(String body) {
        try {
            JSONObject o = new JSONObject(body);
            StringBuilder b = new StringBuilder();
            Iterator<String> it = o.keys();
            while (it.hasNext() && b.length() < 200) b.append(it.next()).append(',');
            return b.toString();
        } catch (org.json.JSONException e) {
            return "-";
        }
    }

    /** The JSON data scripts of a page. */
    private static List<String> scripts(String html) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int open = html.indexOf("<script", at);
            if (open < 0) break;
            int start = html.indexOf('>', open) + 1, end = html.indexOf("</script>", start);
            if (start <= 0 || end < 0) break;
            at = end + 9;
            String js = html.substring(start, end).trim();
            if (!js.startsWith("{") || !(js.contains("Profile") || js.contains("profile"))) continue;
            if (js.startsWith("{&#34;") || js.startsWith("{&quot;")) {
                js = js.replace("&#34;", "\"").replace("&quot;", "\"").replace("&amp;", "&");
            }
            out.add(js);
        }
        return out;
    }

    /** Finds the list ({activeProfile, otherProfiles} or a "profiles"/"cerberus" array) and the permissions. */
    private static JSONObject[] profilesFrom(List<String> docs) {
        JSONObject list = null, perms = null;
        for (String js : docs) {
            try {
                JSONObject o = new JSONObject(js);
                if (list == null) {
                    list = findWith(o, "activeProfile", 0);
                    if (list == null) list = findWith(o, "otherProfiles", 0);
                    if (list == null) {
                        JSONArray arr = findProfileArray(o, 0);
                        if (arr != null) {
                            list = new JSONObject();
                            list.put("otherProfiles", arr);
                        }
                    }
                }
                if (perms == null) perms = findWith(o, "editAndEnterPermissions", 0);
            } catch (org.json.JSONException ignored) {
                // a script that is not data
            }
            if (list != null && perms != null) break;
        }
        return new JSONObject[]{list, perms == null ? null : perms.optJSONObject("editAndEnterPermissions")};
    }

    /** A "profiles"/"cerberus" array whose items look like profiles (id + name + switchLink). */
    private static JSONArray findProfileArray(Object o, int depth) {
        if (depth > 14) return null;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.opt(k);
                if (v instanceof JSONArray && (k.equals("profiles") || k.equals("cerberus"))) {
                    JSONObject first = ((JSONArray) v).optJSONObject(0);
                    if (first != null && first.has("id") && first.has("name")) return (JSONArray) v;
                }
                JSONArray f = findProfileArray(v, depth + 1);
                if (f != null) return f;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONArray f = findProfileArray(a.opt(i), depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** The first object (depth-first) that has the key. */
    private static JSONObject findWith(Object o, String key, int depth) {
        if (depth > 14) return null;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has(key)) return j;
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                JSONObject f = findWith(j.opt(it.next()), key, depth + 1);
                if (f != null) return f;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = findWith(a.opt(i), key, depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    @Override
    public List<Profile> profiles() throws IOException {
        List<Profile> out = new ArrayList<>();
        if (!signedIn()) return out;
        JSONObject[] d = profilesData();
        if (d == null) {
            Log.w(TAG, "profiles: session ended");
            signOut();
            return out;
        }
        if (d[0] == null) {
            Log.w(TAG, "profiles: no profile list on the page");
            return out;
        }
        List<JSONObject> all = new ArrayList<>();
        if (d[0].optJSONObject("activeProfile") != null) all.add(d[0].optJSONObject("activeProfile"));
        JSONArray others = d[0].optJSONArray("otherProfiles");
        if (others != null) for (int i = 0; i < others.length(); i++) if (others.optJSONObject(i) != null) all.add(others.optJSONObject(i));
        for (JSONObject p : all) {
            String id = p.optString("id");
            if (id.isEmpty()) continue;
            JSONObject perm = d[1] == null ? null : JsonScan.path(d[1], id, "enterPermission");
            String pinTitle = "";
            JSONObject data = new JSONObject();
            try {
                data.put("switch", p.optJSONObject("switchLink"));
                if (perm != null && !"ALLOWED".equals(perm.optString("action", "ALLOWED"))) {
                    JSONObject ch = perm.optJSONObject("challenge");
                    pinTitle = ch == null ? "Enter the profile PIN" : ch.optString("title", "Enter the profile PIN");
                    if (ch != null) data.put("challenge", JsonScan.path(ch, "continueAction", "destructuredUrl"));
                }
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
            String type = (p.optString("profileType") + " " + p.optString("type")).toUpperCase(java.util.Locale.ROOT);
            boolean kids = p.optBoolean("isKid") || p.optBoolean("isKids") || p.optBoolean("isChild") || type.contains("KID") || type.contains("CHILD");
            String avatar = p.optString("avatarUrl", JsonScan.path(p, "avatar") == null ? "" : JsonScan.path(p, "avatar").optString("url"));
            out.add(new Profile(id, p.optString("name", "Default"), avatar, kids, p.optBoolean("isSelected"), pinTitle, data.toString()));
        }
        Log.i(TAG, "profiles: " + out.size());
        return out;
    }

    private static Map<String, String> form(JSONObject q) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (q == null) return m;
        Iterator<String> it = q.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = q.opt(k);
            m.put(k, v == null || v == JSONObject.NULL ? "" : String.valueOf(v));
        }
        return m;
    }

    /** Posts a profile action as Prime's own page does; the answer's cookies update the session. */
    private String post(String partialUrl, Map<String, String> form) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        Map<String, String> h = headers();
        h.put("Accept", "application/json, text/html");
        h.put("X-Requested-With", "XMLHttpRequest");
        String url = partialUrl.startsWith("http") ? partialUrl : BASE + partialUrl;
        try (Http.Response r = Http.open(url, h, b.toString().getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", false)) {
            List<String> sc = r.setCookies();
            mergeCookies(sc);
            StringBuilder names = new StringBuilder();
            for (String l : sc) names.append(l.split("=", 2)[0].trim()).append(' ');
            String out = new String(Http.readAll(r.body, 4 << 20), StandardCharsets.UTF_8);
            String loc = r.header("Location");
            // the switch answers with a redirect; the page it leads to finishes the switch (and sets its cookies)
            for (int hop = 0; hop < 4 && r.code >= 300 && r.code < 400 && !loc.isEmpty(); hop++) {
                String next = loc.startsWith("http") ? loc : loc.startsWith("/") ? BASE + loc : null;
                if (next == null || !(next.startsWith(BASE) || next.contains(".amazon.com/") || next.contains(".primevideo.com/"))) break;
                Log.i(TAG, "profile switch redirect -> " + android.net.Uri.parse(next).getPath());
                Map<String, String> g = headers();
                g.put("Accept", "text/html,application/json");
                try (Http.Response rr = Http.open(next, g, null, null, false)) {
                    mergeCookies(rr.setCookies());
                    out = new String(Http.readAll(rr.body, 6 << 20), StandardCharsets.UTF_8);
                    if (rr.code < 300 || rr.code >= 400) break;
                    loc = rr.header("Location");
                }
            }
            Log.i(TAG, "profile post: HTTP " + r.code + ", " + sc.size() + " cookies set (" + names.toString().trim() + "), " + out.length() + " chars, keys " + keysOf(out.trim()));
            return out;
        }
    }

    @Override
    public void selectProfile(Profile p, String pin) throws IOException {
        JSONObject d, sw;
        try {
            d = new JSONObject(p.data);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        sw = d.optJSONObject("switch");
        if (sw == null || sw.optString("partialURL").isEmpty()) throw new IOException("Prime Video offers no switch for " + p.name);
        Map<String, String> q = form(sw.optJSONObject("query"));
        if (p.locked()) {
            if (pin == null) throw new PinNeeded(p.pinTitle);
            JSONObject ch = d.optJSONObject("challenge");
            if (ch == null) throw new IOException("Prime Video asks for a PIN it cannot take here");
            Map<String, String> cq = form(ch.optJSONObject("query"));
            cq.put("pin", pin);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"pinProof\"\\s*:\\s*\"([^\"]+)\"").matcher(post(ch.optString("partialURL"), cq));
            if (!m.find()) throw new PinNeeded("Wrong PIN. " + p.pinTitle);
            q.put("pinProof", m.group(1));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String res = post(sw.optString("partialURL"), q).trim();
            JSONObject[] got = res.startsWith("{") ? profilesFrom(Collections.singletonList(res)) : profilesFrom(scripts(res));
            JSONObject act = got[0] == null ? null : got[0].optJSONObject("activeProfile");
            if (act != null) Log.i(TAG, "switch answer: active is " + (p.id.equals(act.optString("id")) ? "the picked profile" : "another profile"));
            if (act != null && p.id.equals(act.optString("id"))) {
                rememberProfile(p);
                Log.i(TAG, "profile switched");
                return;
            }
            for (Profile now : profiles()) {
                if (now.active && now.id.equals(p.id)) {
                    rememberProfile(p);
                    Log.i(TAG, "profile switched");
                    return;
                }
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("Prime Video didn't switch the profile. Try again.");
    }

    @Override
    public boolean signedIn() {
        return prefs.getBoolean("signed_in", false);
    }

    @Override
    public void signOut() {
        // a clean slate: session, device token and a new device serial for the next code; web cookies are cleared
        // by the caller (Accounts / ServiceActivity)
        prefs.edit().remove("cookies").remove("signed_in").remove("account").remove("refresh").remove("serial").apply();
        recoveryTried = true; // never re-adopt cookies after an explicit sign-out
        forgetProfile();
    }
}
