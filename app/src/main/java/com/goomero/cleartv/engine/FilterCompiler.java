package com.goomero.cleartv.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Compiles filter lists into the engine blob that {@link FilterEngine} maps at run time. The bundled blob is built
 * on the build machine (tools/compile_filters.sh); filter-list updates run the same compiler on the device.
 *
 * <p>File layout (big-endian): int magic "CTVE", int version, int total length, int section count, then for each
 * section an (int offset, int length) pair, then the sections, each 8-byte aligned. A length of 0 means absent.
 * Sections are listed in {@link Blob}; records sections start with 8 zero bytes followed by the interned long
 * arrays (domain lists), then the records themselves.
 */
public final class FilterCompiler {
    /** A filter list that can be read twice ($badfilter targets are collected in a first pass). */
    public interface Source {
        Reader open() throws IOException;
    }

    private final ArrayList<Source> lists = new ArrayList<>();
    private final LongSet.Builder allowSuffix = new LongSet.Builder(), allowExact = new LongSet.Builder();
    private LongSet.Builder[] psl;
    private Collection<String> domains;
    private Result result;

    /** Counts from the last {@link #compile}. */
    public static final class Result {
        public int domainsSource, domainsStored, listHostsSource, listHostsStored, networkFilters, networkDropped,
                cosmetic, scriptlets, cosmeticDropped, duplicateLines, bytes;

        @Override
        public String toString() {
            return "domains " + domainsSource + " -> " + domainsStored + " stored, list hosts " + listHostsSource
                    + " -> " + listHostsStored + ", network filters " + networkFilters + " (dropped " + networkDropped
                    + "), cosmetic " + cosmetic + ", scriptlets " + scriptlets + " (dropped " + cosmeticDropped
                    + "), duplicate lines skipped " + duplicateLines + ", " + bytes + " bytes";
        }
    }

    public FilterCompiler addFilterList(Source s) {
        lists.add(s);
        return this;
    }

    /** Public Suffix List; only the bundled blob carries it. */
    public FilterCompiler setPublicSuffixList(Reader r) throws IOException {
        psl = PublicSuffix.parse(r);
        return this;
    }

    /** DNS-style domain list (lowercase host names, allowlist already removed); only the bundled blob carries it. */
    public FilterCompiler setDomains(Collection<String> hosts) {
        domains = hosts;
        return this;
    }

    /** allowlist.txt format: "domain  # exact|suffix | ..." */
    public FilterCompiler addAllowlist(Reader r) throws IOException {
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String d = l.split("[\\s#]")[0].toLowerCase(Locale.ROOT);
            if (d.isEmpty()) continue;
            if (l.contains("# suffix")) allowSuffix.add(Hash.of(d)); else allowExact.add(Hash.of(d));
        }
        return this;
    }

    public Result result() {
        return result;
    }

    private static boolean skipLine(String line) {
        if (line.isEmpty()) return true;
        char c = line.charAt(0);
        if (c == '!' || c == '[' || (c == '#' && line.length() > 1 && line.charAt(1) == ' ')) return true;
        return line.startsWith("# ") || line.equals("#");
    }

    /** Parses every list and writes the blob to out. */
    public Result compile(OutputStream out) throws IOException {
        Result res = new Result();
        NetworkCompiler net = new NetworkCompiler();
        CosmeticCompiler cos = new CosmeticCompiler();
        for (Source s : lists) {
            try (BufferedReader br = new BufferedReader(s.open(), 1 << 16)) {
                String line;
                while ((line = br.readLine()) != null) if (!skipLine(line)) net.preScan(line);
            }
        }
        // identical lines in several lists would only produce identical filters tested one after the other
        LongObjMap<Boolean> seen = new LongObjMap<>(1 << 18);
        for (Source s : lists) {
            try (BufferedReader br = new BufferedReader(s.open(), 1 << 16)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (skipLine(line)) continue;
                    long h = Hash.of(line);
                    if (seen.get(h) != null) { res.duplicateLines++; continue; }
                    seen.put(h, Boolean.TRUE);
                    int sep = CosmeticCompiler.separatorIndex(line);
                    if (sep >= 0) cos.add(line, sep);
                    else net.add(line);
                }
            }
        }
        seen = null;

        Container c = new Container();

        // domain sets: drop entries already implied by a parent entry (a host matches when any parent does)
        HashSet<String> listHosts = net.hostnameBlocks;
        res.listHostsSource = listHosts.size();
        ArrayList<String> listKeep = new ArrayList<>();
        for (String hst : listHosts) if (!coveredByParent(hst, listHosts, null)) listKeep.add(hst);
        res.listHostsStored = writeHostSet(c, Blob.S_LIST_HOSTS, listKeep);
        if (domains != null) {
            HashSet<String> dset = domains instanceof HashSet ? (HashSet<String>) domains : new HashSet<>(domains);
            int union = dset.size();
            for (String hst : listHosts) if (!dset.contains(hst)) union++;
            res.domainsSource = union;
            ArrayList<String> keep = new ArrayList<>();
            for (String d : dset) if (!listHosts.contains(d) && !coveredByParent(d, dset, listHosts)) keep.add(d);
            res.domainsStored = writeHostSet(c, Blob.S_DOMAINS, keep);
        }
        writeHostSet(c, Blob.S_ALLOW_EXACT, allowExact);
        writeHostSet(c, Blob.S_ALLOW_SUFFIX, allowSuffix);
        if (psl != null) {
            writeHostSet(c, Blob.S_PSL_RULES, psl[0]);
            writeHostSet(c, Blob.S_PSL_WILD, psl[1]);
            writeHostSet(c, Blob.S_PSL_EXC, psl[2]);
        }

        // network filters
        Records netRecs = new Records();
        MultiMap.Builder[] tok = new MultiMap.Builder[6], dom = new MultiMap.Builder[6];
        ArrayList<ArrayList<Integer>> fb = new ArrayList<>();
        net.write(netRecs.recs, netRecs, tok, dom, fb);
        c.put(Blob.S_NET_RECORDS, netRecs.finish());
        int[] idxSections = {Blob.S_NET_BLOCK, Blob.S_NET_DOC_BLOCK, Blob.S_NET_EXCEPT, Blob.S_NET_IMPORTANT,
                Blob.S_NET_REDIRECT, Blob.S_NET_PAGE};
        for (int k = 0; k < 6; k++) {
            BlobWriter tw = new BlobWriter(1024), dw = new BlobWriter(1024), fw = new BlobWriter(64);
            tok[k].write(tw, netRecs.delta());
            dom[k].write(dw, netRecs.delta());
            intList(fw, fb.get(k), netRecs.delta());
            BlobWriter w = new BlobWriter(tw.size() + dw.size() + fw.size() + 32);
            w.i32(16);
            w.i32(16 + tw.size());
            w.i32(16 + tw.size() + dw.size());
            w.i32(0);
            w.append(tw);
            w.append(dw);
            w.append(fw);
            c.put(idxSections[k], w);
        }
        res.networkFilters = net.filterCount;
        res.networkDropped = net.droppedCount;

        // cosmetic filters
        Records cosRecs = new Records();
        CosmeticCompiler.Output co = cos.write(cosRecs.recs, cosRecs);
        c.put(Blob.S_COS_RECORDS, cosRecs.finish());
        c.put(Blob.S_COS_SPECIFIC, map(co.specific, cosRecs.delta()));
        c.put(Blob.S_COS_EXCEPT, map(co.exceptions, cosRecs.delta()));
        c.put(Blob.S_COS_LOW, map(co.low, cosRecs.delta()));
        c.put(Blob.S_COS_HIGH, list(co.high, cosRecs.delta()));
        c.put(Blob.S_COS_GENERIC_EXCL, list(co.genericExcl, cosRecs.delta()));
        c.put(Blob.S_COS_GENERIC_SCRIPTLETS, list(co.genericScriptlets, cosRecs.delta()));
        res.cosmetic = cos.cosmeticCount;
        res.scriptlets = cos.scriptletCount;
        res.cosmeticDropped = cos.droppedCount;

        int[] st = new int[Blob.STATS];
        st[Blob.ST_DOMAINS_SOURCE] = res.domainsSource;
        st[Blob.ST_LIST_HOSTS_SOURCE] = res.listHostsSource;
        st[Blob.ST_NET_FILTERS] = res.networkFilters;
        st[Blob.ST_NET_DROPPED] = res.networkDropped;
        st[Blob.ST_COSMETIC] = res.cosmetic;
        st[Blob.ST_SCRIPTLETS] = res.scriptlets;
        st[Blob.ST_COS_DROPPED] = res.cosmeticDropped;
        st[Blob.ST_LOW_KEYS] = co.lowKeys;
        st[Blob.ST_HIGH] = co.highCount;
        st[Blob.ST_SPECIFIC_DOMAINS] = co.specificDomains;
        st[Blob.ST_DOMAINS_STORED] = res.domainsStored;
        st[Blob.ST_LIST_HOSTS_STORED] = res.listHostsStored;
        c.stats(st);
        res.bytes = c.writeTo(out);
        result = res;
        return res;
    }

    /**
     * Writes a blob holding only a domain set (hashes of DNS-list hosts downloaded on the device). Returns the
     * number of distinct entries.
     */
    public static int writeDomainSet(LongSet.Builder hashes, int sourceCount, OutputStream out) throws IOException {
        Container c = new Container();
        int stored = writeHostSet(c, Blob.S_DOMAINS, hashes);
        int[] st = new int[Blob.STATS];
        st[Blob.ST_DOMAINS_SOURCE] = sourceCount;
        st[Blob.ST_DOMAINS_STORED] = stored;
        c.stats(st);
        c.writeTo(out);
        return stored;
    }

    /** True if a proper parent of host (one with a dot, so never the bare TLD) is in a or b. */
    static boolean coveredByParent(String host, HashSet<String> a, HashSet<String> b) {
        int dot = host.indexOf('.');
        while (dot >= 0) {
            String p = host.substring(dot + 1);
            if (p.indexOf('.') < 0) return false;
            if (a.contains(p) || (b != null && b.contains(p))) return true;
            dot = host.indexOf('.', dot + 1);
        }
        return false;
    }

    private static int writeHostSet(Container c, int section, Collection<String> hosts) {
        LongSet.Builder b = new LongSet.Builder();
        for (String h : hosts) b.add(Hash.of(h));
        return writeHostSet(c, section, b);
    }

    private static int writeHostSet(Container c, int section, LongSet.Builder b) {
        if (b.count() == 0) return 0;
        BlobWriter w = new BlobWriter(8 + b.count() * 5);
        int n = HostSet.write(w, b.raw(), b.count());
        c.put(section, w);
        return n;
    }

    private static BlobWriter map(MultiMap.Builder m, int delta) {
        if (m.keyCount() == 0) return null;
        BlobWriter w = new BlobWriter(m.keyCount() * 20);
        m.write(w, delta);
        return w;
    }

    private static BlobWriter list(ArrayList<Integer> l, int delta) {
        if (l.isEmpty()) return null;
        BlobWriter w = new BlobWriter(4 + l.size() * 4);
        intList(w, l, delta);
        return w;
    }

    private static void intList(BlobWriter w, ArrayList<Integer> l, int delta) {
        w.i32(l.size());
        for (int v : l) w.i32(v + delta);
    }

    /** A records section under construction: interned long arrays first, then records. */
    private static final class Records implements NetFilter.ArrayPool {
        final BlobWriter pool = new BlobWriter(1 << 16), recs = new BlobWriter(1 << 20);
        private final HashMap<String, Integer> interned = new HashMap<>();

        Records() {
            pool.i64(0); // offset 0 means "no array"
        }

        @Override
        public int ref(long[] a) {
            StringBuilder k = new StringBuilder(a.length * 17);
            for (long v : a) k.append(Long.toHexString(v)).append(',');
            String key = k.toString();
            Integer o = interned.get(key);
            if (o != null) return o;
            int off = pool.size();
            pool.i32(a.length);
            for (long v : a) pool.i64(v);
            interned.put(key, off);
            return off;
        }

        /** Offset of record 0 inside the finished section. */
        int delta() {
            return pool.size();
        }

        BlobWriter finish() {
            BlobWriter w = new BlobWriter(pool.size() + recs.size());
            w.append(pool);
            w.append(recs);
            return w;
        }
    }

    /** Section table plus section bodies. */
    private static final class Container {
        private final BlobWriter[] sections = new BlobWriter[Blob.SECTIONS];

        void put(int id, BlobWriter w) {
            sections[id] = w != null && w.size() > 0 ? w : null;
        }

        void stats(int[] st) {
            BlobWriter w = new BlobWriter(st.length * 4);
            for (int v : st) w.i32(v);
            sections[Blob.S_STATS] = w;
        }

        int writeTo(OutputStream out) throws IOException {
            int header = 16 + Blob.SECTIONS * 8;
            int[] off = new int[Blob.SECTIONS];
            int pos = header;
            for (int i = 0; i < Blob.SECTIONS; i++) {
                if (sections[i] == null) continue;
                pos = (pos + 7) & ~7;
                off[i] = pos;
                pos += sections[i].size();
            }
            int total = (pos + 7) & ~7;
            BlobWriter h = new BlobWriter(header);
            h.i32(Blob.MAGIC);
            h.i32(Blob.VERSION);
            h.i32(total);
            h.i32(Blob.SECTIONS);
            for (int i = 0; i < Blob.SECTIONS; i++) {
                h.i32(sections[i] == null ? 0 : off[i]);
                h.i32(sections[i] == null ? 0 : sections[i].size());
            }
            h.writeTo(out);
            int written = header;
            byte[] zeros = new byte[8];
            for (int i = 0; i < Blob.SECTIONS; i++) {
                if (sections[i] == null) continue;
                out.write(zeros, 0, off[i] - written);
                sections[i].writeTo(out);
                written = off[i] + sections[i].size();
            }
            out.write(zeros, 0, total - written);
            return total;
        }
    }
}
