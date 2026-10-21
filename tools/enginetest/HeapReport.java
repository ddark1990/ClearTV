import com.goomero.cleartv.engine.FilterEngine;
import com.goomero.cleartv.engine.HostContext;
import com.goomero.cleartv.engine.Request;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Retained Java heap of the filter engine: the v0.1.0 engine (reference copy, parsed from the text lists at start
 * as v0.1.0 did on the device) against the compiled engine (mapped engine.bin), measured with repeated full GCs
 * while the engine is strongly reachable. Run each in its own JVM: HeapReport ref|new <filters-src> <assets>.
 */
public class HeapReport {
    static Object keep;
    static final String[] LISTS = {"easylist.txt", "easyprivacy.txt", "ubo-filters.txt", "ubo-privacy.txt",
            "ubo-badware.txt", "ubo-quick-fixes.txt", "peter-lowe.txt"};

    static long used() throws InterruptedException {
        Runtime r = Runtime.getRuntime();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            System.gc();
            Thread.sleep(80);
            best = Math.min(best, r.totalMemory() - r.freeMemory());
        }
        return best;
    }

    static Reader reader(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        if (f.getName().endsWith(".gz")) in = new GZIPInputStream(in, 1 << 16);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    static String mb(long b) {
        return String.format(Locale.US, "%.2f MB", b / 1048576.0);
    }

    /** Typical browsing mix used to warm the decode caches before measuring. */
    static void browse(FilterEngine e) {
        String[] pages = {"www.cnn.com", "www.youtube.com", "pluto.tv", "www.foxnews.com", "www.dailymail.co.uk"};
        String[] urls = {"https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://www.cnn.com/assets/app.js",
                "https://cdn.site.com/adfox/loader.js", "https://site.com/ads/footer.gif",
                "https://images.example.com/photos/2026/10/16/very-long-image-name-1234567890.jpg?w=1200&h=800",
                "https://api.example.net/v2/graphql?operationName=GetFeed&variables=%7B%22id%22%3A1%7D",
                "https://www.youtube.com/api/stats/ads?ver=2&ns=1", "https://static.adsafeprotected.com/iasPET.1.js",
                "https://cdn.taboola.com/libtrc/site/loader.js", "https://www.google.com/pagead/1p-user-list/1/?x=1",
                "https://ads.pubmatic.com/AdServer/js/pwt/1/2/pwt.js", "https://player.example.tv/vast/preroll.xml"};
        for (String p : pages) {
            HostContext pc = e.hostContext(p);
            for (String u : urls) for (int t : new int[]{Request.SCRIPT, Request.IMAGE, Request.XHR, Request.UNKNOWN})
                e.decide(u, "GET", t, pc, true, false);
            e.cosmetic.snapshot(pc, e.pageFlags("https://" + p + "/", pc), true, true);
            e.cosmetic.lowGeneric(pc, new String[]{".adsbygoogle", "#ad_banner", ".ad-slot", ".sponsored"});
        }
    }

    public static void main(String[] a) throws Exception {
        File src = new File(a[1]);
        File assets = new File(a[2]);
        long u0 = used();
        long t0 = System.nanoTime();
        if (a[0].equals("ref")) {
            Pattern host = Pattern.compile("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$");
            HashSet<String> allowed = new HashSet<>();
            try (BufferedReader br = new BufferedReader(reader(new File(assets, "allowlist.txt")))) {
                String l;
                while ((l = br.readLine()) != null) {
                    l = l.trim();
                    if (!l.isEmpty() && !l.startsWith("#")) allowed.add(l.split("\\s+")[0].toLowerCase(Locale.ROOT));
                }
            }
            cleartvref.LongSet.Builder hb = new cleartvref.LongSet.Builder();
            try (BufferedReader br = new BufferedReader(reader(new File(src, "ad-domains.txt.gz")), 1 << 16)) {
                String l;
                while ((l = br.readLine()) != null) {
                    String d = l.trim().toLowerCase(Locale.ROOT);
                    while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
                    if (d.isEmpty() || d.startsWith("#") || allowed.contains(d) || !host.matcher(d).matches()) continue;
                    hb.add(cleartvref.Hash.of(d));
                }
            }
            cleartvref.LongSet dom = hb.build();
            hb = null;
            u0 = used(); // the v0.1.0 app read a precompiled domain hash file directly; do not count the text parse
            t0 = System.nanoTime();
            cleartvref.FilterEngine.Builder b = new cleartvref.FilterEngine.Builder(
                    new cleartvref.PublicSuffix(reader(new File(src, "public_suffix_list.dat")))).setDomainSet(dom);
            dom = null;
            for (String n : LISTS) b.addFilterList(reader(new File(src, n)));
            b.addAllowlist(reader(new File(assets, "allowlist.txt")));
            b.addAllowlist(reader(new File(assets, "allowlist-app.txt")));
            keep = b.build();
            long t1 = System.nanoTime();
            System.out.println("v0.1.0 engine: load " + (t1 - t0) / 1000000 + " ms, retained heap " + mb(used() - u0));
        } else {
            FilterEngine e = FilterEngine.open(FilterEngine.map(new File(assets, "engine.bin")), null, null);
            long t1 = System.nanoTime();
            keep = e;
            long cold = used() - u0;
            browse(e);
            long warm = used() - u0;
            int cached = e.network.cachedFilters();
            e.trim();
            long trimmed = used() - u0;
            System.out.println("compiled engine: open " + (t1 - t0) / 1000 + " us, retained heap " + mb(cold)
                    + " after open, " + mb(warm) + " after browsing (" + cached
                    + " decoded filters cached), " + mb(trimmed) + " after trim(); " + mb(e.mappedBytes)
                    + " mapped from engine.bin (page cache, loaded on touch)");
        }
    }
}
