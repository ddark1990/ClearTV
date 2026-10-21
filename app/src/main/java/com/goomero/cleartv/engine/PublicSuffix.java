package com.goomero.cleartv.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Public Suffix List lookups (rules, wildcards, exceptions) to compute registrable domains. The list is compiled
 * into three {@link HostSet}s of suffix hashes, so lookups hash host substrings in place instead of keeping ~10k
 * rule strings on the heap.
 */
public final class PublicSuffix {
    private final HostSet rules, wildcards, exceptions; // "*.ck" stored as "ck", "!www.ck" as "www.ck"
    private final Map<String, String> cache = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 512;
        }
    };

    PublicSuffix(HostSet rules, HostSet wildcards, HostSet exceptions) {
        this.rules = rules;
        this.wildcards = wildcards;
        this.exceptions = exceptions;
    }

    /** Parses public_suffix_list.dat into the three hash lists (rules, wildcards, exceptions). */
    static LongSet.Builder[] parse(Reader r) throws IOException {
        LongSet.Builder[] out = {new LongSet.Builder(), new LongSet.Builder(), new LongSet.Builder()};
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            int sp = line.indexOf(' ');
            if (sp > 0) line = line.substring(0, sp);
            line = java.net.IDN.toASCII(line, java.net.IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            if (line.startsWith("!")) out[2].add(Hash.of(line.substring(1)));
            else if (line.startsWith("*.")) out[1].add(Hash.of(line.substring(2)));
            else out[0].add(Hash.of(line));
        }
        return out;
    }

    /** eTLD+1 of host, or the host itself if it is a public suffix or an IP literal. */
    public String registrable(String host) {
        if (host == null || host.isEmpty()) return "";
        synchronized (cache) {
            String c = cache.get(host);
            if (c != null) return c;
        }
        String r = compute(host);
        synchronized (cache) {
            cache.put(host, r);
        }
        return r;
    }

    void trim() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private String compute(String host) {
        if (isIp(host)) return host;
        int n = 1;
        for (int i = 0; i < host.length(); i++) if (host.charAt(i) == '.') n++;
        int[] starts = new int[n];
        int k = 1;
        for (int i = 0; i < host.length(); i++) if (host.charAt(i) == '.') starts[k++] = i + 1;
        int len = host.length();
        int psIndex = n - 1; // default rule "*": last label
        for (int i = 0; i < n; i++) {
            long s = Hash.of(host, starts[i], len);
            if (exceptions.contains(s)) { psIndex = Math.min(i + 1, n - 1); break; }
            if (rules.contains(s)) { psIndex = i; break; }
            if (i + 1 < n && wildcards.contains(Hash.of(host, starts[i + 1], len))) { psIndex = i; break; }
        }
        if (psIndex == 0) return host;
        return host.substring(starts[psIndex - 1]);
    }

    static boolean isIp(String h) {
        if (h.indexOf(':') >= 0) return true;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(c == '.' || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }
}
