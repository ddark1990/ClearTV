import com.goomero.cleartv.engine.CosmeticEngine;
import com.goomero.cleartv.engine.FilterCompiler;
import com.goomero.cleartv.engine.FilterEngine;
import com.goomero.cleartv.engine.HostContext;
import com.goomero.cleartv.engine.Request;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Checks that the compiled engine (blob produced by FilterCompiler, read by FilterEngine) gives exactly the same
 * answers as the v0.1.0 engine, which
 * is kept unchanged as the reference implementation in tools/enginetest/ref (package cleartvref) and built from
 * the same source lists. A deterministic corpus is generated from the lists themselves: requests to DNS-list hosts
 * and their subdomains, to "||host^" list hosts, URLs synthesized from network filters (with their $domain= pages
 * and types), ordinary first-party and CDN URLs, documents and popups; strong blocking on and off; page flags;
 * cosmetic snapshots for hosts named by cosmetic filters; and lowly-generic selector lookups.
 *
 * usage: EquivalenceTest <filters-src dir> <assets/filters dir>
 */
public class EquivalenceTest {
    static final String[] LISTS = {"easylist.txt", "easyprivacy.txt", "ubo-filters.txt", "ubo-privacy.txt",
            "ubo-badware.txt", "ubo-quick-fixes.txt", "peter-lowe.txt"};
    static final int[] TYPES = {Request.SCRIPT, Request.IMAGE, Request.STYLESHEET, Request.XHR, Request.SUBDOCUMENT,
            Request.MEDIA, Request.FONT, Request.UNKNOWN, Request.DOCUMENT, Request.POPUP};
    static final String[] PAGES = {"www.cnn.com", "www.youtube.com", "pluto.tv", "www.amazon.com", "www.foxnews.com",
            "duckduckgo.com", "www.google.com", "tubitv.com", "therokuchannel.roku.com", "www.bbc.co.uk",
            "www.reddit.com", "news.yahoo.com", "www.dailymail.co.uk", "www.twitch.tv", "www.espn.com",
            "www.imdb.com", "www.nytimes.com", "www.theguardian.com", "watch.plex.tv", "play.xumo.com"};
    static final String[] FIRST_PARTY_PATHS = {"/", "/assets/app.js", "/static/css/main.css", "/img/logo.png",
            "/api/v1/feed?page=2", "/video/master.m3u8", "/fonts/inter.woff2", "/watch?v=abc123", "/search?q=news"};
    static final Pattern HOST = Pattern.compile("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$");

    static int compared, mismatches, blocks, allows, nones;

    static Reader reader(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        if (f.getName().endsWith(".gz")) in = new GZIPInputStream(in, 1 << 16);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    static List<String> lines(File f) throws IOException {
        ArrayList<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader(f), 1 << 16)) {
            String l;
            while ((l = br.readLine()) != null) out.add(l);
        }
        return out;
    }

    static void mismatch(String what) {
        mismatches++;
        if (mismatches <= 25) System.out.println("MISMATCH " + what);
    }

    static final class Pair {
        final cleartvref.FilterEngine ref;
        final FilterEngine eng;
        final String label;

        Pair(cleartvref.FilterEngine ref, FilterEngine eng, String label) {
            this.ref = ref;
            this.eng = eng;
            this.label = label;
        }

        void request(String url, String pageHost, int type, boolean strong, boolean skip) {
            cleartvref.FilterEngine.Decision a = ref.decide(url, "GET", type, ref.hostContext(pageHost), strong, skip);
            FilterEngine.Decision b = eng.decide(url, "GET", type, eng.hostContext(pageHost), strong, skip);
            compared++;
            if (a.kind == FilterEngine.BLOCK) blocks++;
            else if (a.kind == FilterEngine.ALLOW) allows++;
            else nones++;
            if (a.kind != b.kind || !eq(a.redirect, b.redirect) || a.byDomainSet != b.byDomainSet) {
                mismatch(label + " decide " + url + " page=" + pageHost + " type=" + type + " strong=" + strong + " skip="
                        + skip + ": ref=" + a.kind + "/" + a.redirect + "/" + a.byDomainSet + " new=" + b.kind + "/"
                        + b.redirect + "/" + b.byDomainSet);
            }
        }

        void pageFlags(String url) {
            String h = host(url);
            int a = ref.pageFlags(url, ref.hostContext(h));
            int b = eng.pageFlags(url, eng.hostContext(h));
            compared++;
            if (a != b) mismatch(label + " pageFlags " + url + ": ref=" + a + " new=" + b);
            if (!eq(ref.psl.registrable(h), eng.psl.registrable(h))) {
                mismatch(label + " registrable " + h + ": ref=" + ref.psl.registrable(h) + " new=" + eng.psl.registrable(h));
            }
        }

        void cosmetic(String h, boolean cosOn, boolean slOn) {
            String url = "https://" + h + "/";
            cleartvref.HostContext rc = ref.hostContext(h);
            HostContext nc = eng.hostContext(h);
            int rf = ref.pageFlags(url, rc), nf = eng.pageFlags(url, nc);
            cleartvref.CosmeticEngine.Snapshot a = ref.cosmetic.snapshot(rc, rf, cosOn, slOn);
            CosmeticEngine.Snapshot b = eng.cosmetic.snapshot(nc, nf, cosOn, slOn);
            compared++;
            if (!a.hide.equals(b.hide) || !flat(a.styles).equals(flat(b.styles))
                    || !flat(a.scriptlets).equals(flat(b.scriptlets)) || a.lowGeneric != b.lowGeneric) {
                mismatch(label + " snapshot " + h + " cos=" + cosOn + " sl=" + slOn + ": ref hide=" + a.hide.size()
                        + " styles=" + a.styles.size() + " sl=" + a.scriptlets.size() + " new hide=" + b.hide.size()
                        + " styles=" + b.styles.size() + " sl=" + b.scriptlets.size());
            }
        }

        void lowGeneric(String h, String[] tokens) {
            ArrayList<String> a = ref.cosmetic.lowGeneric(ref.hostContext(h), tokens);
            ArrayList<String> b = eng.cosmetic.lowGeneric(eng.hostContext(h), tokens);
            compared++;
            // duplicate filter lines are compiled once, so compare distinct selectors in first-seen order
            if (!new ArrayList<>(new LinkedHashSet<>(a)).equals(new ArrayList<>(new LinkedHashSet<>(b)))) {
                mismatch(label + " lowGeneric " + h + " " + Arrays.toString(tokens) + ": ref=" + a + " new=" + b);
            }
        }
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Scriptlets added in 0.1.1 (cookie notices); v0.1.0 dropped rules using them, so they are not compared. */
    static final HashSet<String> NEW_SCRIPTLETS = new HashSet<>(Arrays.asList("trusted-click-element",
            "trusted-set-cookie", "trusted-set-local-storage-item", "trusted-set-session-storage-item"));

    static List<String> flat(List<String[]> l) {
        ArrayList<String> out = new ArrayList<>();
        for (String[] x : l) if (!NEW_SCRIPTLETS.contains(x[0])) out.add(String.join("\u0001", x));
        return out;
    }

    static String host(String url) {
        String u = url.substring(url.indexOf("://") + 3);
        int s = 0;
        while (s < u.length() && "/?#:".indexOf(u.charAt(s)) < 0) s++;
        return u.substring(0, s).toLowerCase(Locale.ROOT);
    }

    /** A URL that a network filter pattern should match (or nearly match), or null for regex filters. */
    static String urlFor(String pat, Random rnd) {
        if (pat.startsWith("/") && pat.endsWith("/") && pat.length() > 2) return null;
        String p = pat;
        boolean hostAnchor = p.startsWith("||"), start = !hostAnchor && p.startsWith("|");
        if (hostAnchor) p = p.substring(2);
        else if (start) p = p.substring(1);
        if (p.endsWith("|")) p = p.substring(0, p.length() - 1);
        p = p.replace("*", rnd.nextBoolean() ? "abc" : "x/y").replace("^", rnd.nextBoolean() ? "/" : "?");
        if (hostAnchor) {
            if (p.indexOf('/') < 0 && p.indexOf('?') < 0) p = p + "/";
            int cut = 0;
            while (cut < p.length() && "/?".indexOf(p.charAt(cut)) < 0) cut++;
            String hp = p.substring(0, cut);
            if (hp.isEmpty() || hp.startsWith(".")) hp = "cdn" + hp;
            if (hp.endsWith(".")) hp = hp + "com";
            if (hp.indexOf('.') < 0) hp = hp + ".com";
            return "https://" + (rnd.nextInt(4) == 0 ? "sub." : "") + hp + p.substring(cut);
        }
        if (start && p.startsWith("http")) return p.contains("://") ? p : "https://example.com/" + p;
        if (!p.startsWith("/")) p = "/" + p;
        String[] hosts = {"cdn.example.net", "www.cnn.com", "static.site.org", "media.tubitv.com"};
        return "https://" + hosts[rnd.nextInt(hosts.length)] + p;
    }

    static int typeOf(String opts, Random rnd) {
        if (opts != null) {
            for (String o : opts.split(",")) {
                int t = Request.typeFromName(o.equals("xmlhttprequest") ? "xhr" : o.equals("subdocument") ? "subdocument" : o);
                if (t != Request.UNKNOWN && !o.startsWith("~")) return t;
            }
        }
        return TYPES[rnd.nextInt(TYPES.length)];
    }

    static String firstDomainOpt(String opts, boolean excluded) {
        if (opts == null) return null;
        for (String o : opts.split(",")) {
            if (o.startsWith("domain=") || o.startsWith("from=")) {
                for (String d : o.substring(o.indexOf('=') + 1).split("\\|")) {
                    if (d.startsWith("~") == excluded) {
                        String x = excluded ? d.substring(1) : d;
                        if (x.endsWith(".*")) x = x.substring(0, x.length() - 2) + ".com";
                        if (HOST.matcher(x).matches()) return x;
                    }
                }
            }
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File assets = new File(a[1]);
        long t0 = System.currentTimeMillis();

        // ---- reference engine (v0.1.0 code), built the way v0.1.0 built it at startup
        HashSet<String> allowed = new HashSet<>();
        for (String l : lines(new File(assets, "allowlist.txt"))) {
            l = l.trim();
            if (!l.isEmpty() && !l.startsWith("#")) allowed.add(l.split("\\s+")[0].toLowerCase(Locale.ROOT));
        }
        ArrayList<String> dns = new ArrayList<>();
        HashSet<String> dnsSet = new HashSet<>();
        for (String l : lines(new File(src, "ad-domains.txt.gz"))) {
            String d = l.trim().toLowerCase(Locale.ROOT);
            while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
            if (d.isEmpty() || d.startsWith("#") || allowed.contains(d) || !HOST.matcher(d).matches()) continue;
            if (dnsSet.add(d)) dns.add(d);
        }
        long[] hashes = new long[dns.size()];
        for (int i = 0; i < hashes.length; i++) hashes[i] = cleartvref.Hash.of(dns.get(i));
        cleartvref.PublicSuffix refPsl = new cleartvref.PublicSuffix(reader(new File(src, "public_suffix_list.dat")));
        cleartvref.FilterEngine.Builder rb = new cleartvref.FilterEngine.Builder(refPsl)
                .setDomainSet(cleartvref.LongSet.fromUnsorted(hashes, hashes.length));
        for (String n : LISTS) rb.addFilterList(reader(new File(src, n)));
        rb.addAllowlist(reader(new File(assets, "allowlist.txt")));
        rb.addAllowlist(reader(new File(assets, "allowlist-app.txt")));
        cleartvref.FilterEngine ref = rb.build();

        // ---- compiled engines from the same seven lists: the build-machine form (with PSL and DNS list, like
        // engine.bin) and the device form after a list update (lists only, on top of the build-machine blob).
        // The shipped engine.bin also carries the cookie-notice lists added in 0.1.1, which v0.1.0 did not have.
        FilterCompiler full = new FilterCompiler();
        FilterCompiler dc = new FilterCompiler();
        for (final String n : LISTS) {
            final File f = new File(src, n);
            FilterCompiler.Source so = new FilterCompiler.Source() {
                @Override
                public Reader open() throws IOException {
                    return reader(f);
                }
            };
            full.addFilterList(so);
            dc.addFilterList(so);
        }
        full.setPublicSuffixList(reader(new File(src, "public_suffix_list.dat")));
        full.setDomains(dnsSet);
        for (FilterCompiler fc : new FilterCompiler[]{full, dc}) {
            fc.addAllowlist(reader(new File(assets, "allowlist.txt")));
            fc.addAllowlist(reader(new File(assets, "allowlist-app.txt")));
        }
        ByteArrayOutputStream fo = new ByteArrayOutputStream(), bo = new ByteArrayOutputStream();
        full.compile(fo);
        dc.compile(bo);
        ByteBuffer bundled = ByteBuffer.wrap(fo.toByteArray());
        FilterEngine eng = FilterEngine.open(bundled, null, null);
        FilterEngine updated = FilterEngine.open(bundled, ByteBuffer.wrap(bo.toByteArray()), null);
        System.out.println("reference: " + ref.stats());
        System.out.println("compiled:  " + eng.stats());

        // ---- corpus inputs from the lists
        ArrayList<String> netLines = new ArrayList<>(), listHosts = new ArrayList<>(), cosDomains = new ArrayList<>(),
                genericTokens = new ArrayList<>();
        for (String n : LISTS) {
            for (String l : lines(new File(src, n))) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("!") || l.startsWith("[") || l.startsWith("# ")) continue;
                int sep = l.indexOf("#");
                boolean cosmetic = false;
                for (String m : new String[]{"##", "#@#", "#?#", "#$#"}) {
                    int k = l.indexOf(m);
                    if (k >= 0 && l.substring(0, k).indexOf('/') < 0 && l.substring(0, k).indexOf('$') < 0) {
                        cosmetic = true;
                        sep = k;
                        break;
                    }
                }
                if (cosmetic) {
                    String doms = l.substring(0, sep);
                    String body = l.substring(sep).replaceFirst("^#@?\\??\\$?#", "");
                    if (doms.isEmpty()) {
                        if (body.matches("^[.#][A-Za-z0-9_-]+.*")) {
                            String t = body.replaceFirst("^([.#][A-Za-z0-9_-]+).*", "$1");
                            genericTokens.add(t);
                        }
                    } else {
                        for (String d : doms.split(",")) {
                            d = d.trim().toLowerCase(Locale.ROOT);
                            if (d.startsWith("~")) d = d.substring(1);
                            if (d.endsWith(".*")) d = d.substring(0, d.length() - 2) + (d.length() % 2 == 0 ? ".com" : ".co.uk");
                            if (HOST.matcher(d).matches()) cosDomains.add(d);
                        }
                    }
                    continue;
                }
                if (l.matches("^\\|\\|[a-z0-9_.-]+\\^$")) listHosts.add(l.substring(2, l.length() - 1));
                netLines.add(l);
            }
        }
        Random rnd = new Random(20261017L);
        Pair[] pairs = {new Pair(ref, eng, "bundled"), new Pair(ref, updated, "updated")};

        for (Pair p : pairs) {
            Random r = new Random(rnd.nextLong());
            // DNS-list hosts, their subdomains and their parents
            for (int i = 0; i < 20000; i++) {
                String d = dns.get(r.nextInt(dns.size()));
                if (i % 3 == 1) d = "x" + r.nextInt(100) + "." + d;
                if (i % 3 == 2 && d.indexOf('.') != d.lastIndexOf('.')) d = d.substring(d.indexOf('.') + 1);
                String url = "https://" + d + FIRST_PARTY_PATHS[r.nextInt(FIRST_PARTY_PATHS.length)];
                p.request(url, PAGES[r.nextInt(PAGES.length)], TYPES[r.nextInt(TYPES.length)], i % 5 != 0, i % 17 == 0);
            }
            // "||host^" list hosts and subdomains, strong blocking on and off
            for (int i = 0; i < 10000; i++) {
                String d = listHosts.get(r.nextInt(listHosts.size()));
                if (i % 2 == 1) d = "cdn" + i % 7 + "." + d;
                p.request("https://" + d + "/pixel.gif?u=1", PAGES[r.nextInt(PAGES.length)], TYPES[r.nextInt(TYPES.length)],
                        i % 2 == 0, false);
            }
            // URLs synthesized from network filters, on their $domain= pages and on excluded ones
            for (int i = 0; i < 40000; i++) {
                String line = netLines.get(r.nextInt(netLines.size()));
                String l = line.startsWith("@@") ? line.substring(2) : line;
                int d = l.lastIndexOf('$');
                String pat = d > 0 ? l.substring(0, d) : l, opts = d > 0 ? l.substring(d + 1) : null;
                String url = urlFor(pat, r);
                if (url == null) continue;
                String page = firstDomainOpt(opts, false);
                if (page == null || r.nextInt(4) == 0) page = PAGES[r.nextInt(PAGES.length)];
                int type = typeOf(opts, r);
                p.request(url, page, type, r.nextBoolean(), false);
                String exc = firstDomainOpt(opts, true);
                if (exc != null) p.request(url, exc, type, true, false);
                if (line.startsWith("@@") || (opts != null && opts.contains("document"))) p.pageFlags(url);
            }
            // near misses: random hosts with paths stitched from filter-pattern fragments (mostly not blocked)
            for (int i = 0; i < 20000; i++) {
                StringBuilder path = new StringBuilder();
                for (int k = 0; k < 3; k++) {
                    String l = netLines.get(r.nextInt(netLines.size()));
                    String frag = l.replaceAll("^@@|^\\|\\||^\\||\\$.*$|[*^|]", "");
                    if (frag.length() > 12) frag = frag.substring(r.nextInt(frag.length() - 8), frag.length());
                    path.append('/').append(frag.replace("/", ""));
                }
                String h = "site" + r.nextInt(5000) + (r.nextBoolean() ? ".com" : ".example.org");
                p.request("https://" + h + path + (r.nextBoolean() ? ".js" : "?id=" + i), PAGES[r.nextInt(PAGES.length)],
                        TYPES[r.nextInt(TYPES.length)], r.nextBoolean(), false);
            }
            // ordinary first-party traffic and documents
            for (String pg : PAGES) {
                for (String path : FIRST_PARTY_PATHS) {
                    for (int t : TYPES) p.request("https://" + pg + path, pg, t, true, false);
                    p.pageFlags("https://" + pg + path);
                }
            }
            // cosmetic snapshots
            for (int i = 0; i < 6000; i++) {
                String h = cosDomains.get(r.nextInt(cosDomains.size()));
                if (i % 3 == 0) h = "www." + h;
                p.cosmetic(h, i % 5 != 1, i % 7 != 2);
            }
            for (String pg : PAGES) p.cosmetic(pg, true, true);
            // lowly generic
            for (int i = 0; i < 3000; i++) {
                String[] toks = new String[20];
                for (int k = 0; k < toks.length; k++) toks[k] = genericTokens.get(r.nextInt(genericTokens.size()));
                String h = r.nextBoolean() ? PAGES[r.nextInt(PAGES.length)] : cosDomains.get(r.nextInt(cosDomains.size()));
                p.lowGeneric(h, toks);
            }
        }
        System.out.println("equivalence: " + compared + " checks (requests: " + blocks + " block, " + allows + " allow, "
                + nones + " none), " + mismatches + " mismatches, " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println(mismatches == 0 ? "EQUIVALENT" : "NOT EQUIVALENT");
        System.exit(mismatches == 0 ? 0 : 1);
    }
}
