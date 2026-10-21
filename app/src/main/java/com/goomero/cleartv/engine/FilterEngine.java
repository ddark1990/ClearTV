package com.goomero.cleartv.engine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * The whole blocker: domain set (DNS-style lists, wildcard semantics), network filters, cosmetic filters and
 * scriptlets, served from compiled engine blobs (see {@link FilterCompiler}). Opening an engine only reads the
 * blob headers; the data stays in the mapped files, so the engine adds almost nothing to the Java heap and starts
 * in milliseconds. Immutable apart from small decode caches, so the WebView IO threads share it without locking.
 */
public final class FilterEngine {
    public final PublicSuffix psl;
    private final HostSet[] strongSets;     // domain set: bundled DNS list, every list's "||host^", update extras
    private final HostSet listHosts;        // "||host^" rules of the active filter lists
    private final HostSet allowSuffixes;    // playback-critical domains never blocked by the domain set
    private final HostSet allowExact;
    public final NetworkEngine network;
    public final CosmeticEngine cosmetic;
    public final int listDomainCount;
    private final int domainCount;
    public final long buildMillis;
    /** Bytes of compiled data mapped by this engine (page cache, not Java heap). */
    public final long mappedBytes;

    private FilterEngine(Blob bundled, Blob lists, Blob extra, long ms) {
        Blob active = lists != null ? lists : bundled;
        psl = new PublicSuffix(bundled.hostSet(Blob.S_PSL_RULES), bundled.hostSet(Blob.S_PSL_WILD),
                bundled.hostSet(Blob.S_PSL_EXC));
        listHosts = active.hostSet(Blob.S_LIST_HOSTS);
        // The bundled domain set was pruned against the bundled list hosts, so those stay part of the domain set
        // even when updated lists no longer contain some of them.
        HostSet bundledDomains = bundled.hostSet(Blob.S_DOMAINS);
        HostSet bundledList = bundled.hostSet(Blob.S_LIST_HOSTS);
        HostSet extraDomains = extra != null ? extra.hostSet(Blob.S_DOMAINS) : HostSet.EMPTY;
        strongSets = lists != null
                ? new HostSet[]{bundledDomains, bundledList, listHosts, extraDomains}
                : new HostSet[]{bundledDomains, bundledList, extraDomains};
        allowSuffixes = active.hostSet(Blob.S_ALLOW_SUFFIX);
        allowExact = active.hostSet(Blob.S_ALLOW_EXACT);
        network = new NetworkEngine(active);
        cosmetic = new CosmeticEngine(active);
        listDomainCount = active.stat(Blob.ST_LIST_HOSTS_SOURCE);
        domainCount = bundled.stat(Blob.ST_DOMAINS_SOURCE) + (extra != null ? extra.stat(Blob.ST_DOMAINS_STORED) : 0);
        mappedBytes = bundled.buf.capacity() + (lists != null ? lists.buf.capacity() : 0)
                + (extra != null ? extra.buf.capacity() : 0);
        buildMillis = ms;
    }

    /**
     * @param bundled blob shipped with the app (Public Suffix List, DNS domain set, bundled filter lists)
     * @param lists   blob compiled on the device from updated filter lists, or null to use the bundled lists
     * @param extra   domain-set blob from downloaded DNS lists, or null
     */
    public static FilterEngine open(ByteBuffer bundled, ByteBuffer lists, ByteBuffer extra) throws IOException {
        long t0 = System.nanoTime();
        Blob b = new Blob(bundled);
        if (!b.has(Blob.S_PSL_RULES) || !b.has(Blob.S_DOMAINS)) throw new IOException("bundled engine file incomplete");
        Blob l = lists != null ? new Blob(lists) : null;
        Blob e = extra != null ? new Blob(extra) : null;
        return new FilterEngine(b, l, e, (System.nanoTime() - t0) / 1000000);
    }

    /** Maps a whole file read-only. The mapping stays valid after the file is replaced or deleted. */
    public static ByteBuffer map(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
    }

    /** Releases decode caches (decoded filters, compiled regexes, generic selectors, registrable-domain cache). */
    public void trim() {
        network.trim();
        cosmetic.trim();
        psl.trim();
    }

    public int domainCount() {
        return domainCount;
    }

    /** True if a host hash is in the bundled or downloaded domain set or the list hosts (used by updates). */
    public boolean inDomainSet(long hash) {
        for (HostSet s : strongSets) if (s.contains(hash)) return true;
        return false;
    }

    public HostContext hostContext(String host) {
        return new HostContext(host == null ? "" : host.toLowerCase(Locale.ROOT), psl);
    }

    /** Domain-set lookup with wildcard semantics; the allowlist wins. The bare TLD is never matched. */
    public boolean domainBlocked(HostContext h) {
        long[] s = h.suffixHashes;
        if (s.length == 0) return false;
        if (allowExact.contains(s[0])) return false;
        for (long v : s) if (allowSuffixes.contains(v)) return false;
        for (int i = 0; i < s.length - 1; i++) if (inDomainSet(s[i])) return true;
        return false;
    }

    public static final int NONE = 0, BLOCK = 1, ALLOW = 2;

    public static final class Decision {
        public int kind;
        public String redirect;
        public boolean byDomainSet;
    }

    /**
     * Classify one request.
     *
     * @param page          context of the top-level page (for $domain= and third-party checks)
     * @param useDomainSet  whether the large domain list participates (setting "Strong blocking")
     * @param skipDomainSet request host the user chose to visit anyway from the block page
     */
    public Decision decide(String url, String method, int type, HostContext page, boolean useDomainSet,
                           boolean skipDomainSet) {
        Request r = new Request(url, method, type, page, psl);
        Decision d = new Decision();
        boolean dom = useDomainSet && !skipDomainSet && domainBlocked(r.req);
        // the filter lists' own "||host^" rules still apply when the big list is off
        if (!useDomainSet && !skipDomainSet) dom = listHostBlocked(r.req);
        NetworkEngine.Verdict v = network.match(r, dom, new NetworkEngine.Verdict());
        d.kind = v.kind;
        d.redirect = v.redirect;
        d.byDomainSet = dom && v.kind == BLOCK;
        return d;
    }

    /** "||host^" rules from filter lists only (used when the big domain list is disabled). */
    private boolean listHostBlocked(HostContext h) {
        long[] s = h.suffixHashes;
        if (s.length == 0) return false;
        if (allowExact.contains(s[0])) return false;
        for (long v : s) if (allowSuffixes.contains(v)) return false;
        for (int i = 0; i < s.length - 1; i++) if (listHosts.contains(s[i])) return true;
        return false;
    }

    /** P_* flags from "@@...$document/$elemhide/$generichide/$specifichide" for a top-level URL. */
    public int pageFlags(String url, HostContext page) {
        Request r = new Request(url, "GET", Request.DOCUMENT, page, psl);
        return network.pageFlags(r);
    }

    public static boolean pageWhitelisted(int flags) {
        return (flags & NetFilter.P_DOCUMENT) != 0;
    }

    public String stats() {
        return "domains=" + domainCount + " (from lists " + listDomainCount + ") net{" + network.stats() + "} "
                + cosmetic.stats() + " opened in " + buildMillis + " ms, " + (mappedBytes >> 10) + " KiB mapped";
    }
}
