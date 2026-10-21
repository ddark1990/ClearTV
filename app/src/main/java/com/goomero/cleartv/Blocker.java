package com.goomero.cleartv;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.goomero.cleartv.engine.CosmeticEngine;
import com.goomero.cleartv.engine.FilterEngine;
import com.goomero.cleartv.engine.HostContext;
import com.goomero.cleartv.engine.Redirects;
import com.goomero.cleartv.engine.Request;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects the FilterEngine to one WebView: classifies every request from shouldInterceptRequest, serves block
 * pages and redirect resources, and answers the content script through a JavaScript bridge.
 */
public final class Blocker {
    private static final String TAG = "CTV.Blocker";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public interface Host {
        /** Called on the main thread when the blocked-request count of the current page changes. */
        void onBlockedCountChanged(int count);

        /** Called on the main thread when any frame starts or stops playing media. */
        void onMediaPlayingChanged(boolean playing);
    }

    private final FilterManager filters;
    private final Prefs prefs;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger blocked = new AtomicInteger();
    private final Set<String> proceedHosts = Collections.synchronizedSet(new HashSet<String>());
    private final Map<String, Boolean> playing = new ConcurrentHashMap<>();
    private boolean lastPlaying;
    private boolean countPosted;

    // state of the current top-level page; written by the IO thread that sees the main-frame request
    private volatile HostContext top;
    private volatile String topSite = "";
    private volatile String topOrigin;
    private volatile int topFlags;
    private volatile boolean siteDisabled;

    // settings snapshot (refreshed by MainActivity on resume)
    private volatile boolean blockingOn, strongOn, cosmeticOn, scriptletsOn;

    private volatile String service = "";

    /** The service this page belongs to, for the per-service numbers in Settings > Ad blocking. */
    public void setService(String id) {
        service = id == null ? "" : id;
    }

    public final String bridgeName;
    public final String token;

    public Blocker(FilterManager f, Prefs p, Host h) {
        filters = f;
        prefs = p;
        host = h;
        String rnd = Long.toHexString(Double.doubleToLongBits(Math.random()) ^ System.nanoTime());
        bridgeName = "__ctv_" + rnd;
        token = Long.toHexString(System.nanoTime() ^ 0x5DEECE66DL) + rnd.substring(0, 4);
        refreshSettings();
    }

    public void refreshSettings() {
        blockingOn = prefs.blockingOn();
        strongOn = prefs.strongOn();
        cosmeticOn = prefs.cosmeticOn();
        scriptletsOn = prefs.scriptletsOn();
        String s = topSite;
        siteDisabled = prefs.siteBlockingDisabled(s);
    }

    public int blockedCount() {
        return blocked.get();
    }

    public String topSite() {
        return topSite;
    }

    /** Site key (registrable domain) for a URL, e.g. "www.youtube.com" -> "youtube.com". */
    public String siteOf(String url) {
        String h = hostOf(url);
        FilterEngine e = filters.engine();
        if (h.isEmpty()) return "";
        return e != null ? e.psl.registrable(h) : stripWww(h);
    }

    private static String stripWww(String h) {
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Called when the user navigates (loadUrl / link) so page state is right even before the request. */
    public void onMainFrameNavigation(String url) {
        String h = hostOf(url);
        if (h.isEmpty()) return;
        FilterEngine e = filters.engine();
        HostContext ctx = e != null ? e.hostContext(h) : null;
        setTop(url, h, ctx, e);
    }

    private void setTop(String url, String h, HostContext ctx, FilterEngine e) {
        String site = ctx != null ? ctx.registrable : stripWww(h);
        top = ctx;
        topSite = site;
        Uri tu = Uri.parse(url);
        topOrigin = tu.getScheme() != null && tu.getHost() != null ? tu.getScheme() + "://" + tu.getHost() : null;
        siteDisabled = prefs.siteBlockingDisabled(site);
        topFlags = (e != null && ctx != null) ? e.pageFlags(url, ctx) : 0;
        // a new document: its counter and media state start fresh
        blocked.set(0);
        playing.clear();
        postCount();
        postMedia();
    }

    public void allowHostOnce(String h) {
        proceedHosts.add(h);
    }

    private boolean active() {
        return blockingOn && !siteDisabled && !FilterEngine.pageWhitelisted(topFlags);
    }

    /** shouldInterceptRequest body. Returns null to let the request through. */
    public WebResourceResponse intercept(WebResourceRequest req) {
        Uri uri = req.getUrl();
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
        String url = uri.toString();
        // the engine maps its blob in well under 200 ms; until then requests pass instead of stalling the page
        FilterEngine e = filters.awaitEngine(200);
        if (e == null) return null;
        String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (req.isForMainFrame()) {
            HostContext ctx = e.hostContext(h);
            setTop(url, h, ctx, e);
            if (!active()) return null;
            FilterEngine.Decision d = e.decide(url, req.getMethod(), Request.DOCUMENT, ctx, strongOn,
                    proceedHosts.contains(h));
            if (d.kind == FilterEngine.BLOCK) {
                increment();
                BlockStats.record(service, h, "page");
                return blockPage(url, h, d.byDomainSet);
            }
            return null;
        }
        HostContext page = top;
        if (page == null || !active()) return null;
        if (playerFirstParty(topSite, h)) return null;
        int type = typeOf(req, uri);
        FilterEngine.Decision d = e.decide(url, req.getMethod(), type, page, strongOn, proceedHosts.contains(h));
        if (d.kind != FilterEngine.BLOCK) return null;
        increment();
        BlockStats.record(service, h, d.byDomainSet ? "domain list" : "filter");
        Redirects.Resource r = Redirects.get(d.redirect);
        HashMap<String, String> headers = new HashMap<>();
        // a CORS-valid answer: with "*" a credentialed fetch fails with a CORS error, and pages (Prime Video's
        // player among them) retry or wait on telemetry that never "arrives"; echoing the origin lets it fail fast
        String origin = header(req, "Origin");
        if (origin == null) origin = topOrigin;
        if (origin != null) {
            headers.put("Access-Control-Allow-Origin", origin);
            headers.put("Access-Control-Allow-Credentials", "true");
            headers.put("Vary", "Origin");
        } else {
            headers.put("Access-Control-Allow-Origin", "*");
        }
        // with credentials "*" is taken literally, so the requested headers are echoed back
        String asked = header(req, "Access-Control-Request-Headers");
        headers.put("Access-Control-Allow-Headers", asked != null ? asked : "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        headers.put("Cache-Control", "no-store");
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            // a preflight must answer "ok" or the page reports a network error; the real request is blocked next
            return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", headers,
                    new ByteArrayInputStream(new byte[0]));
        }
        if (r != null) {
            return new WebResourceResponse(r.mime, "utf-8", 200, "OK", headers, new ByteArrayInputStream(r.body));
        }
        if (type == Request.SCRIPT || type == Request.STYLESHEET || type == Request.SUBDOCUMENT) {
            // a failed script fires its error handler at once, so a page that waits for an ad SDK falls back
            // immediately; an empty "successful" script would leave it waiting for globals that never appear
            return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked by ClearTV", headers,
                    new ByteArrayInputStream(new byte[0]));
        }
        // beacons, ad-break and tracking calls: an instant, empty success, so nothing retries or waits
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", headers,
                new ByteArrayInputStream(new byte[0]));
    }

    /**
     * Requests a service's own player waits on before it starts: Prime Video's player holds playback for about
     * ten seconds when its own session and metrics endpoints (Amazon hosts, no ads) fail. ClearTV blocks ads
     * only, so these first-party calls of the watched service pass.
     */
    static boolean playerFirstParty(String site, String host) {
        if (!"primevideo.com".equals(site)) return false;
        return host.endsWith(".primevideo.com") || host.endsWith(".a2z.com") || host.endsWith(".amazon.dev")
                || host.contains("unagi") && host.endsWith(".amazon.com") || host.equals("fls-na.amazon.com");
    }

    private static String header(WebResourceRequest req, String name) {
        java.util.Map<String, String> h = req.getRequestHeaders();
        if (h == null) return null;
        for (java.util.Map.Entry<String, String> e : h.entrySet()) if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        return null;
    }

    private void increment() {
        blocked.incrementAndGet();
        postCount();
    }

    private void postCount() {
        synchronized (this) {
            if (countPosted) return;
            countPosted = true;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (Blocker.this) {
                    countPosted = false;
                }
                host.onBlockedCountChanged(blocked.get());
            }
        }, 500); // shield count repaints at most twice a second
    }

    /** Resource type from what the WebView exposes: Accept header, frame flag and file extension. */
    static int typeOf(WebResourceRequest req, Uri uri) {
        Map<String, String> hdr = req.getRequestHeaders();
        String accept = null;
        if (hdr != null) {
            accept = hdr.get("Accept");
            if (accept == null) accept = hdr.get("accept");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (accept != null) {
            if (accept.startsWith("text/html")) return Request.SUBDOCUMENT;
            if (accept.startsWith("text/css")) return Request.STYLESHEET;
            if (accept.startsWith("image/")) return Request.IMAGE;
        }
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 && dot > path.lastIndexOf('/') ? path.substring(dot + 1) : "";
        switch (ext) {
            case "js": case "mjs": return Request.SCRIPT;
            case "css": return Request.STYLESHEET;
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "svg": case "ico": case "avif":
            case "bmp": return Request.IMAGE;
            case "woff": case "woff2": case "ttf": case "otf": case "eot": return Request.FONT;
            case "mp4": case "m4s": case "m4a": case "m4v": case "webm": case "mp3": case "ts": case "m3u8":
            case "mpd": case "aac": case "ogg": case "vtt": return Request.MEDIA;
            case "html": case "htm": return Request.SUBDOCUMENT;
            default: return Request.UNKNOWN;
        }
    }

    private WebResourceResponse blockPage(String url, String h, boolean byDomainSet) {
        String safeHost = TextUtils.htmlEncode(h);
        String proceed = "cleartv-proceed:" + Uri.encode(url);
        String html = "<!DOCTYPE html><html><head><meta name=viewport content='width=device-width'>"
                + "<title>Blocked: " + safeHost + "</title><style>"
                + "body{background:#101418;color:#e8edf2;font:22px/1.5 sans-serif;margin:0;display:flex;"
                + "align-items:center;justify-content:center;height:100vh}"
                + ".c{max-width:900px;padding:40px}h1{font-size:40px;margin:0 0 12px}"
                + "a{display:inline-block;margin:24px 16px 0 0;padding:14px 28px;border-radius:10px;background:#2b3a4a;"
                + "color:#fff;text-decoration:none;border:3px solid transparent}a:focus{border-color:#4fc3f7;outline:none}"
                + "</style></head><body><div class=c><h1>Page blocked</h1>"
                + "<p><b>" + safeHost + "</b> is on " + (byDomainSet ? "the ad/tracker domain list" : "an ad/tracker filter list")
                + ". Opening it was stopped so it cannot load ads or track you.</p>"
                + "<p>If you trust this site, continue to it for this session, or turn blocking off for it from the shield"
                + " button.</p><a id=go href='" + TextUtils.htmlEncode(proceed) + "'>Continue to site</a>"
                + "<a href='javascript:history.back()'>Go back</a></div>"
                + "<script>document.getElementById('go').focus()</script></body></html>";
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                new ByteArrayInputStream(html.getBytes(UTF8)));
    }

    private void postMedia() {
        main.post(new Runnable() {
            @Override
            public void run() {
                boolean p = playing.containsValue(Boolean.TRUE);
                if (p != lastPlaying) {
                    lastPlaying = p;
                    host.onMediaPlayingChanged(p);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------- JS bridge

    /** Object exposed to content.js as window[bridgeName]. Only returns filter data; it cannot change settings. */
    public final class Bridge {
        @JavascriptInterface
        public String snapshot(String frameUrl) {
            try {
                JSONObject o = new JSONObject();
                FilterEngine e = filters.awaitEngine(200);
                if (e == null || !active() || (!cosmeticOn && !scriptletsOn)) {
                    o.put("on", 0);
                    return o.toString();
                }
                String h = hostOf(frameUrl);
                if (h.isEmpty()) {
                    o.put("on", 0);
                    return o.toString();
                }
                HostContext fc = e.hostContext(h);
                int flags = e.pageFlags(frameUrl, fc) | topFlags;
                CosmeticEngine.Snapshot s = e.cosmetic.snapshot(fc, flags, cosmeticOn, scriptletsOn);
                o.put("on", 1);
                o.put("hide", new JSONArray(s.hide));
                JSONArray st = new JSONArray();
                for (String[] x : s.styles) st.put(new JSONArray().put(x[0]).put(x[1]));
                o.put("styles", st);
                JSONArray sl = new JSONArray();
                for (String[] x : s.scriptlets) {
                    JSONArray a = new JSONArray();
                    for (String y : x) a.put(y);
                    sl.put(a);
                }
                o.put("sl", sl);
                o.put("lg", s.lowGeneric ? 1 : 0);
                return o.toString();
            } catch (JSONException | RuntimeException ex) {
                Log.w(TAG, "snapshot failed", ex);
                return "{\"on\":0}";
            }
        }

        @JavascriptInterface
        public String generic(String frameUrl, String tokensJson) {
            try {
                FilterEngine e = filters.engine();
                if (e == null || !active() || !cosmeticOn) return "[]";
                JSONArray a = new JSONArray(tokensJson);
                int n = Math.min(a.length(), 5000);
                String[] toks = new String[n];
                for (int i = 0; i < n; i++) toks[i] = a.optString(i, "");
                ArrayList<String> sels = e.cosmetic.lowGeneric(e.hostContext(hostOf(frameUrl)), toks);
                return new JSONArray(sels).toString();
            } catch (JSONException | RuntimeException ex) {
                return "[]";
            }
        }

        private long lastAd;

        /**
         * The page-side ad guard ({@code tv/AdGuardJs}) removed or skipped an ad: counted for Settings > Ad
         * blocking. At most one report every 2 s, so a page cannot flood the numbers.
         */
        @JavascriptInterface
        public void ad(String what) {
            long now = android.os.SystemClock.uptimeMillis();
            if (what == null || what.length() > 80 || now - lastAd < 2000) return;
            lastAd = now;
            BlockStats.record(service, what, "ad guard");
        }

        @JavascriptInterface
        public void media(String frameId, boolean isPlaying) {
            if (frameId == null || frameId.length() > 32) return;
            if (isPlaying) playing.put(frameId, Boolean.TRUE);
            else playing.remove(frameId);
            if (playing.size() > 64) playing.clear();
            postMedia();
        }
    }
}
