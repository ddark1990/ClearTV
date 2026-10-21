package com.goomero.cleartv.engine;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Matches requests against the network filters of a compiled engine blob. Filter records stay in the mapped file;
 * a record is decoded into a {@link NetFilter} only after its header (type and party) passes, and decoded filters
 * are kept in a small fixed-size cache so the hot ones are not rebuilt for every request. The cache is the only
 * heap this class grows, and {@link #trim()} empties it.
 */
public final class NetworkEngine {
    static final int NONE = 0, BLOCK = 1, ALLOW = 2;
    private static final int CACHE_SLOTS = 4096;

    private final ByteBuffer buf;
    private final int recBase;
    private final Index iBlock, iDocBlock, iExcept, iImportant, iRedirect, iPage;
    private final AtomicReferenceArray<Cached> cache = new AtomicReferenceArray<>(CACHE_SLOTS);
    public final int filterCount, droppedCount;

    private static final class Cached {
        final int offset;
        final NetFilter filter;

        Cached(int offset, NetFilter filter) {
            this.offset = offset;
            this.filter = filter;
        }
    }

    NetworkEngine(Blob b) {
        buf = b.buf;
        recBase = b.offset(Blob.S_NET_RECORDS);
        iBlock = new Index(b, Blob.S_NET_BLOCK);
        iDocBlock = new Index(b, Blob.S_NET_DOC_BLOCK);
        iExcept = new Index(b, Blob.S_NET_EXCEPT);
        iImportant = new Index(b, Blob.S_NET_IMPORTANT);
        iRedirect = new Index(b, Blob.S_NET_REDIRECT);
        iPage = new Index(b, Blob.S_NET_PAGE);
        filterCount = b.stat(Blob.ST_NET_FILTERS);
        droppedCount = b.stat(Blob.ST_NET_DROPPED);
    }

    /** Drops decoded filters (compiled regexes included); they are decoded again on demand. */
    public void trim() {
        for (int i = 0; i < CACHE_SLOTS; i++) cache.set(i, null);
    }

    public int cachedFilters() {
        int n = 0;
        for (int i = 0; i < CACHE_SLOTS; i++) if (cache.get(i) != null) n++;
        return n;
    }

    public String stats() {
        return "block=" + iBlock.stats() + " docBlock=" + iDocBlock.stats() + " except=" + iExcept.stats()
                + " important=" + iImportant.stats() + " redirectRule=" + iRedirect.stats() + " page=" + iPage.stats();
    }

    /** Result of a pattern match: verdict plus redirect resource (if any). */
    static final class Verdict {
        int kind;
        String redirect;
    }

    /**
     * @param domainBlocked whether the domain set already blocks this request host
     */
    Verdict match(Request r, boolean domainBlocked, Verdict out) {
        out.kind = NONE;
        out.redirect = null;
        long[] toks = NetworkCompiler.tokens(r);
        NetFilter imp = iImportant.find(r, toks);
        if (imp != null) {
            out.kind = BLOCK;
            out.redirect = imp.redirect;
            return out;
        }
        NetFilter b = iBlock.find(r, toks);
        if (b == null && (r.type & (NetFilter.T_DOCUMENT | NetFilter.T_POPUP)) != 0) b = iDocBlock.find(r, toks);
        if (b == null && !domainBlocked) return out;
        NetFilter e = iExcept.find(r, toks);
        if (e != null) {
            out.kind = ALLOW;
            return out;
        }
        out.kind = BLOCK;
        if (b != null && b.redirect != null) out.redirect = b.redirect;
        else {
            NetFilter rr = iRedirect.find(r, toks);
            if (rr != null) out.redirect = rr.redirect;
        }
        return out;
    }

    /** Page-level exception flags (P_*) for a top-level document URL. */
    int pageFlags(Request r) {
        return iPage.flags(r, NetworkCompiler.tokens(r));
    }

    /** Two-way set-associative slot pair for a record offset: slot and slot ^ 1. */
    private static int slot(int off) {
        return (off * 0x9E3779B1) >>> 20; // 12 bits = CACHE_SLOTS
    }

    private static boolean matches(NetFilter f, Request r, boolean page) {
        if (!page && !f.typeMatches(r.type)) return false;
        return f.optionsMatch(r) && f.urlMatches(r);
    }

    /** The filter at off if it matches r, else null. */
    private NetFilter hit(int off, Request r, boolean page) {
        int slot = slot(off);
        Cached c = cache.get(slot);
        if (c == null || c.offset != off) {
            Cached d = cache.get(slot ^ 1);
            if (d != null && d.offset == off) c = d;
        }
        if (c != null && c.offset == off) return matches(c.filter, r, page) ? c.filter : null;
        // not decoded yet: reject on the record header (type, party) before building the filter
        if (!NetFilter.headerMatches(new Blob.Cursor(buf, recBase + off), r.type, r.thirdParty, page)) return null;
        NetFilter f = NetFilter.decode(new Blob.Cursor(buf, recBase + off), recBase);
        // keep the newer entry in the primary slot, move the previous one to the secondary slot
        Cached prev = cache.get(slot);
        if (prev != null) cache.set(slot ^ 1, prev);
        cache.set(slot, new Cached(off, f));
        return matches(f, r, page) ? f : null;
    }

    private final class Index {
        final MultiMap tokens, byDomain;
        final NetFilter[] fallback;   // filters without a token are tested on every request, so keep them decoded

        Index(Blob b, int section) {
            int o = b.offset(section);
            if (!b.has(section)) {
                tokens = byDomain = MultiMap.EMPTY;
                fallback = new NetFilter[0];
                return;
            }
            // section: int tokenMapOffset, int domainMapOffset, int fallbackOffset (relative to section start)
            tokens = new MultiMap(buf, o + buf.getInt(o));
            byDomain = new MultiMap(buf, o + buf.getInt(o + 4));
            int fo = o + buf.getInt(o + 8);
            int n = buf.getInt(fo);
            fallback = new NetFilter[n];
            for (int i = 0; i < n; i++) fallback[i] = NetFilter.decode(new Blob.Cursor(buf, recBase + buf.getInt(fo + 4 + i * 4)), recBase);
        }

        String stats() {
            return tokens.size() + "t+d" + byDomain.size() + "+" + fallback.length;
        }

        NetFilter find(Request r, long[] toks) {
            for (long t : toks) {
                int idx = tokens.find(t);
                if (idx < 0) continue;
                for (int i = 0, e = tokens.count(idx); i < e; i++) {
                    NetFilter f = hit(tokens.value(idx, i), r, false);
                    if (f != null) return f;
                }
            }
            if (byDomain.size() != 0 && r.page != null) {
                NetFilter f = findDomain(r, r.page.suffixHashes);
                if (f == null) f = findDomain(r, r.page.entityHashes);
                if (f != null) return f;
            }
            for (NetFilter f : fallback) if (matches(f, r, false)) return f;
            return null;
        }

        private NetFilter findDomain(Request r, long[] hashes) {
            for (long h : hashes) {
                int idx = byDomain.find(h);
                if (idx < 0) continue;
                for (int i = 0, e = byDomain.count(idx); i < e; i++) {
                    NetFilter f = hit(byDomain.value(idx, i), r, false);
                    if (f != null) return f;
                }
            }
            return null;
        }

        int flags(Request r, long[] toks) {
            int flags = 0;
            for (long t : toks) {
                int idx = tokens.find(t);
                if (idx < 0) continue;
                for (int i = 0, e = tokens.count(idx); i < e; i++) {
                    NetFilter f = hit(tokens.value(idx, i), r, true);
                    if (f != null) flags |= f.pageFlags;
                }
            }
            if (byDomain.size() != 0 && r.page != null) {
                flags |= domainFlags(r, r.page.suffixHashes);
                flags |= domainFlags(r, r.page.entityHashes);
            }
            for (NetFilter f : fallback) if (matches(f, r, true)) flags |= f.pageFlags;
            return flags;
        }

        private int domainFlags(Request r, long[] hashes) {
            int flags = 0;
            for (long h : hashes) {
                int idx = byDomain.find(h);
                if (idx < 0) continue;
                for (int i = 0, e = byDomain.count(idx); i < e; i++) {
                    NetFilter f = hit(byDomain.value(idx, i), r, true);
                    if (f != null) flags |= f.pageFlags;
                }
            }
            return flags;
        }
    }
}
