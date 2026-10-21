import com.goomero.cleartv.engine.*;

import java.io.File;

/** JVM test of the compiled filter engine (engine.bin): correctness cases plus open and match timing. */
public class EngineTest {
    static int fails = 0;

    static void expect(FilterEngine e, String page, String url, int type, int want, String label) {
        HostContext pc = e.hostContext(host(page));
        FilterEngine.Decision d = e.decide(url, "GET", type, pc, true, false);
        String got = d.kind == FilterEngine.BLOCK ? "BLOCK" : d.kind == FilterEngine.ALLOW ? "ALLOW" : "NONE";
        String w = want == FilterEngine.BLOCK ? "BLOCK" : want == FilterEngine.ALLOW ? "ALLOW" : "NONE";
        boolean ok = (want == FilterEngine.BLOCK) == (d.kind == FilterEngine.BLOCK);
        if (!ok) fails++;
        System.out.println((ok ? "PASS " : "FAIL ") + label + ": " + got + (d.redirect != null ? " ->" + d.redirect : "")
                + (d.byDomainSet ? " (domain set)" : "") + " want " + w + "  " + url);
    }

    static String host(String url) {
        String u = url.substring(url.indexOf("://") + 3);
        int s = u.indexOf('/');
        return s < 0 ? u : u.substring(0, s);
    }

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        long t0 = System.nanoTime();
        FilterEngine e = FilterEngine.open(FilterEngine.map(new File(dir, "engine.bin")), null, null);
        long t1 = System.nanoTime();
        System.out.println("open engine.bin: " + ((t1 - t0) / 1000) + " us");
        System.out.println(e.stats());
        System.out.println("registrable(www.bbc.co.uk)=" + e.psl.registrable("www.bbc.co.uk")
                + " registrable(a.b.blogspot.com)=" + e.psl.registrable("a.b.blogspot.com"));

        int B = FilterEngine.BLOCK, N = FilterEngine.NONE;
        expect(e, "https://www.cnn.com/", "https://securepubads.g.doubleclick.net/tag/js/gpt.js", Request.SCRIPT, B, "gpt.js");
        expect(e, "https://www.cnn.com/", "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", Request.SCRIPT, B, "adsbygoogle");
        expect(e, "https://www.cnn.com/", "https://www.google-analytics.com/analytics.js", Request.SCRIPT, B, "GA");
        expect(e, "https://www.cnn.com/", "https://www.cnn.com/assets/app.js", Request.SCRIPT, N, "first-party app js");
        expect(e, "https://www.youtube.com/", "https://rr3---sn-abc.googlevideo.com/videoplayback?id=1", Request.MEDIA, N, "youtube video (allowlisted suffix)");
        expect(e, "https://www.youtube.com/", "https://i.ytimg.com/vi/x/hqdefault.jpg", Request.IMAGE, N, "ytimg thumbnail");
        expect(e, "https://pluto.tv/live-tv", "https://boot.pluto.tv/v4/start?appName=web", Request.XHR, N, "pluto boot");
        expect(e, "https://pluto.tv/live-tv", "https://stitcher.pluto.tv/stitch/hls/channel/x/master.m3u8", Request.UNKNOWN, N, "pluto stitcher");
        expect(e, "https://example.org/", "https://example.org/", Request.DOCUMENT, N, "plain document");
        expect(e, "https://www.amazon.com/gp/video/storefront", "https://www.amazon.com/gp/video/storefront", Request.DOCUMENT, N, "prime storefront doc");
        expect(e, "https://www.amazon.com/", "https://aax-us-east.amazon-adsystem.com/e/dtb/bid", Request.XHR, B, "amazon-adsystem");
        expect(e, "https://www.foxnews.com/", "https://c.amazon-adsystem.com/aax2/apstag.js", Request.SCRIPT, B, "apstag");
        expect(e, "https://site.com/", "https://imasdk.googleapis.com/js/sdkloader/ima3.js", Request.SCRIPT, N, "ima3 (not blocked by default lists)");
        expect(e, "https://site.com/", "https://www.googletagmanager.com/gtm.js?id=GTM-X", Request.SCRIPT, B, "gtm");
        expect(e, "https://site.com/", "https://cdn.taboola.com/libtrc/site/loader.js", Request.SCRIPT, B, "taboola");
        expect(e, "https://site.com/", "https://site.com/ads/footer.gif", Request.IMAGE, B, "pattern /ads/footer.$~xhr");
        expect(e, "https://site.com/", "https://site.com/ads/footer.json", Request.XHR, N, "pattern /ads/footer. excluded for xhr");
        expect(e, "https://site.com/", "https://cdn.site.com/adfox/loader.js", Request.SCRIPT, B, "pattern /adfox/loader.js$script");
        expect(e, "https://site.com/", "https://cdn.site.com/adfox/loader.js", Request.IMAGE, N, "pattern $script not image");
        expect(e, "https://www.github.com/", "https://github.githubassets.com/assets/app.js", Request.SCRIPT, N, "github assets");
        expect(e, "https://duckduckgo.com/", "https://duckduckgo.com/dist/app.js", Request.SCRIPT, N, "ddg app");

        // cosmetic snapshot
        HostContext yt = e.hostContext("www.youtube.com");
        int pf = e.pageFlags("https://www.youtube.com/watch?v=x", yt);
        CosmeticEngine.Snapshot s = e.cosmetic.snapshot(yt, pf, true, true);
        System.out.println("youtube: hide=" + s.hide.size() + " styles=" + s.styles.size() + " scriptlets=" + s.scriptlets.size() + " lowGeneric=" + s.lowGeneric);
        for (int i = 0; i < Math.min(8, s.scriptlets.size()); i++) System.out.println("  sl " + String.join(" | ", s.scriptlets.get(i)));
        HostContext cnn = e.hostContext("www.cnn.com");
        CosmeticEngine.Snapshot s2 = e.cosmetic.snapshot(cnn, e.pageFlags("https://www.cnn.com/", cnn), true, true);
        System.out.println("cnn: hide=" + s2.hide.size() + " styles=" + s2.styles.size() + " scriptlets=" + s2.scriptlets.size());
        System.out.println("lowGeneric(.adsbygoogle,#ad_banner,.ad-slot)=" + e.cosmetic.lowGeneric(cnn, new String[]{".adsbygoogle", "#ad_banner", ".ad-slot"}));

        // match benchmark
        String[] urls = {"https://www.cnn.com/assets/app.js", "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
                "https://rr3---sn-abc.googlevideo.com/videoplayback?id=1&itag=22&source=youtube&mime=video%2Fmp4",
                "https://images.example.com/photos/2026/10/16/very-long-image-name-for-testing-1234567890.jpg?w=1200&h=800",
                "https://api.example.net/v2/graphql?operationName=GetFeed&variables=%7B%22id%22%3A1%7D"};
        HostContext pc = e.hostContext("www.cnn.com");
        int iters = 200000;
        long m0 = System.nanoTime();
        for (int i = 0; i < iters; i++) e.decide(urls[i % urls.length], "GET", Request.UNKNOWN, pc, true, false);
        long m1 = System.nanoTime();
        System.out.println("match: " + ((m1 - m0) / iters / 1000.0) + " us/request (JVM)");
        System.out.println("decoded filters cached after benchmark: " + e.network.cachedFilters());
        e.trim();
        if (e.network.cachedFilters() != 0) { fails++; System.out.println("FAIL trim() left decoded filters"); }
        // the same request after trim() must give the same answer (decoded again from the blob)
        expect(e, "https://www.cnn.com/", "https://securepubads.g.doubleclick.net/tag/js/gpt.js", Request.SCRIPT, B, "gpt.js after trim");
        expect(e, "https://site.com/", "https://site.com/ads/footer.gif", Request.IMAGE, B, "pattern after trim");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
