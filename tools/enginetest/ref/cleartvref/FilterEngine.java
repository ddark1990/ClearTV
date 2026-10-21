package cleartvref;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The whole blocker: domain set (DNS-style lists, wildcard semantics), network filters, cosmetic filters and
 * scriptlets. Immutable once built, so it can be shared by the WebView IO threads without locking.
 */
public final class FilterEngine {
    public final PublicSuffix psl;
    private final LongSet domains;          // bundled/updated domain list + "||host^" rules from filter lists
    private final LongSet allowSuffixes;    // playback-critical domains never blocked by the domain set
    private final LongSet allowExact;
    public final NetworkEngine network;
    public final CosmeticEngine cosmetic;
    public final int listDomainCount;
    public final long buildMillis;
    private final LongSet listOnly;         // "||host^" rules from filter lists only

    private FilterEngine(PublicSuffix psl, LongSet domains, LongSet listOnly, LongSet allowSuffixes, LongSet allowExact,
                         NetworkEngine n, CosmeticEngine c, long ms) {
        this.listOnly = listOnly;
        this.psl = psl;
        this.domains = domains;
        this.allowSuffixes = allowSuffixes;
        this.allowExact = allowExact;
        this.network = n;
        this.cosmetic = c;
        this.listDomainCount = listOnly.size();
        this.buildMillis = ms;
    }

    public static final class Builder {
        private final PublicSuffix psl;
        private final NetworkEngine net = new NetworkEngine();
        private final CosmeticEngine cos = new CosmeticEngine();
        private final ArrayList<String> pending = new ArrayList<>();
        private final LongSet.Builder allowS = new LongSet.Builder();
        private final LongSet.Builder allowE = new LongSet.Builder();
        private LongSet domainSet = LongSet.EMPTY;
        private final long start = System.currentTimeMillis();

        public Builder(PublicSuffix psl) {
            this.psl = psl;
        }

        /** Add an ABP / uBO filter list. Lines are held until build() so $badfilter can be applied first. */
        public Builder addFilterList(Reader r) throws IOException {
            BufferedReader br = new BufferedReader(r, 1 << 16);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                char c = line.charAt(0);
                if (c == '!' || c == '[' || (c == '#' && line.length() > 1 && line.charAt(1) == ' ')) continue;
                if (line.startsWith("# ") || line.equals("#")) continue;
                net.preScan(line);
                pending.add(line);
            }
            return this;
        }

        public Builder setDomainSet(LongSet s) {
            domainSet = s;
            return this;
        }

        /** allowlist.txt format: "domain  # exact|suffix | ..." */
        public Builder addAllowlist(Reader r) throws IOException {
            BufferedReader br = new BufferedReader(r);
            String line;
            while ((line = br.readLine()) != null) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                String d = l.split("[\\s#]")[0].toLowerCase(Locale.ROOT);
                if (d.isEmpty()) continue;
                boolean suffix = l.contains("# suffix");
                if (suffix) allowS.add(Hash.of(d)); else allowE.add(Hash.of(d));
            }
            return this;
        }

        public FilterEngine build() {
            for (String line : pending) {
                int sep = CosmeticEngine.separatorIndex(line);
                if (sep >= 0) cos.add(line, sep);
                else net.add(line);
            }
            pending.clear();
            net.finish();
            cos.finish();
            LongSet listHosts = net.hostnameBlocks.build();
            LongSet all = LongSet.union(domainSet, listHosts);
            return new FilterEngine(psl, all, listHosts, allowS.build(), allowE.build(), net, cos,
                    System.currentTimeMillis() - start);
        }
    }

    public int domainCount() {
        return domains.size();
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
        for (int i = 0; i < s.length - 1; i++) if (domains.contains(s[i])) return true;
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
        // filter-list "||host^" rules are inside the same set; when the big list is off we still need those
        if (!useDomainSet && !skipDomainSet) dom = listHostBlocked(r.req);
        NetworkEngine.Verdict v = network.match(r, dom, new NetworkEngine.Verdict());
        d.kind = v.kind;
        d.redirect = v.redirect;
        d.byDomainSet = dom && v.kind == BLOCK;
        return d;
    }

    /** "||host^" rules from filter lists only (used when the big domain list is disabled). */
    private boolean listHostBlocked(HostContext h) {
        LongSet l = listOnly;
        long[] s = h.suffixHashes;
        if (s.length == 0) return false;
        if (allowExact.contains(s[0])) return false;
        for (long v : s) if (allowSuffixes.contains(v)) return false;
        for (int i = 0; i < s.length - 1; i++) if (l.contains(s[i])) return true;
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
        return "domains=" + domains.size() + " (from lists " + listDomainCount + ") net{" + network.stats() + "} "
                + cosmetic.stats() + " built in " + buildMillis + " ms";
    }
}
