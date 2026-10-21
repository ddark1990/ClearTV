package cleartvref;

/**
 * Precomputed hashes of a hostname's label suffixes ("a.b.example.com", "b.example.com", "example.com", "com")
 * plus entity forms ("example.*") used by $domain= and cosmetic domain lists.
 */
public final class HostContext {
    public final String host;
    public final String registrable;
    /** hashes of every suffix, longest first (includes the bare TLD). */
    public final long[] suffixHashes;
    /** hashes of entity forms such as "example.*" / "www.example.*". */
    public final long[] entityHashes;

    public HostContext(String host, PublicSuffix psl) {
        this.host = host == null ? "" : host;
        this.registrable = psl == null ? this.host : psl.registrable(this.host);
        int n = this.host.isEmpty() ? 0 : 1;
        for (int i = 0; i < this.host.length(); i++) if (this.host.charAt(i) == '.') n++;
        suffixHashes = new long[n];
        int k = 0;
        int start = 0;
        while (k < n) {
            suffixHashes[k++] = Hash.of(this.host, start, this.host.length());
            int dot = this.host.indexOf('.', start);
            if (dot < 0) break;
            start = dot + 1;
        }
        // Entity: strip the public suffix of the registrable domain, keep every left part. "www.google.co.uk" ->
        // "www.google.*", "google.*".
        String ps = registrable.indexOf('.') >= 0 ? registrable.substring(registrable.indexOf('.') + 1) : "";
        if (!ps.isEmpty() && this.host.length() > ps.length() + 1 && this.host.endsWith("." + ps)) {
            String left = this.host.substring(0, this.host.length() - ps.length() - 1);
            int m = 1;
            for (int i = 0; i < left.length(); i++) if (left.charAt(i) == '.') m++;
            entityHashes = new long[m];
            int s = 0;
            for (int j = 0; j < m; j++) {
                entityHashes[j] = Hash.of(left.substring(s) + ".*");
                int dot = left.indexOf('.', s);
                if (dot < 0) break;
                s = dot + 1;
            }
        } else {
            entityHashes = new long[0];
        }
    }

    public boolean matchesAny(long[] set) {
        if (set == null) return false;
        for (long h : suffixHashes) if (contains(set, h)) return true;
        for (long h : entityHashes) if (contains(set, h)) return true;
        return false;
    }

    static boolean contains(long[] sorted, long v) {
        int lo = 0, hi = sorted.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long m = sorted[mid];
            if (m < v) lo = mid + 1;
            else if (m > v) hi = mid - 1;
            else return true;
        }
        return false;
    }
}
