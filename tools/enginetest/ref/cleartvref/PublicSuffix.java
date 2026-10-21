package cleartvref;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public Suffix List lookups (rules, wildcards, exceptions) to compute registrable domains. */
public final class PublicSuffix {
    private final HashSet<String> rules = new HashSet<>();
    private final HashSet<String> wildcards = new HashSet<>(); // "*.ck" stored as "ck"
    private final HashSet<String> exceptions = new HashSet<>(); // "!www.ck" stored as "www.ck"
    private final Map<String, String> cache = new LinkedHashMap<String, String>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 2048;
        }
    };

    public PublicSuffix(Reader r) throws IOException {
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            int sp = line.indexOf(' ');
            if (sp > 0) line = line.substring(0, sp);
            line = java.net.IDN.toASCII(line, java.net.IDN.ALLOW_UNASSIGNED).toLowerCase(java.util.Locale.ROOT);
            if (line.startsWith("!")) exceptions.add(line.substring(1));
            else if (line.startsWith("*.")) wildcards.add(line.substring(2));
            else rules.add(line);
        }
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

    private String compute(String host) {
        if (isIp(host)) return host;
        // positions of label starts
        int n = 1;
        for (int i = 0; i < host.length(); i++) if (host.charAt(i) == '.') n++;
        int[] starts = new int[n];
        int k = 1;
        for (int i = 0; i < host.length(); i++) if (host.charAt(i) == '.') starts[k++] = i + 1;
        int psIndex = n - 1; // default rule "*": last label
        for (int i = 0; i < n; i++) {
            String s = host.substring(starts[i]);
            if (exceptions.contains(s)) { psIndex = Math.min(i + 1, n - 1); break; }
            if (rules.contains(s)) { psIndex = i; break; }
            if (i + 1 < n && wildcards.contains(host.substring(starts[i + 1]))) { psIndex = i; break; }
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
