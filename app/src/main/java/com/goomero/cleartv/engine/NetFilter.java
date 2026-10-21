package com.goomero.cleartv.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One compiled ABP / uBlock Origin network filter. */
final class NetFilter {
    // request types
    static final int T_OTHER = 1, T_SCRIPT = 1 << 1, T_IMAGE = 1 << 2, T_STYLESHEET = 1 << 3, T_OBJECT = 1 << 4,
            T_XHR = 1 << 5, T_SUBDOCUMENT = 1 << 6, T_PING = 1 << 7, T_MEDIA = 1 << 8, T_FONT = 1 << 9,
            T_WEBSOCKET = 1 << 10, T_DOCUMENT = 1 << 11, T_POPUP = 1 << 12;
    static final int T_ALL_SUB = T_OTHER | T_SCRIPT | T_IMAGE | T_STYLESHEET | T_OBJECT | T_XHR | T_SUBDOCUMENT
            | T_PING | T_MEDIA | T_FONT | T_WEBSOCKET;
    // page-level exception flags
    static final int P_DOCUMENT = 1, P_ELEMHIDE = 2, P_GENERICHIDE = 4, P_SPECIFICHIDE = 8;

    String pattern;         // lowercased unless matchCase; '*' and '^' kept
    String[] parts;         // pattern split on '*'
    Pattern regex;
    String regexSrc;        // source regex was compiled from (lowercased and simplified)
    String regexLiteral;    // literal every regex match contains (lowercase), checked first
    boolean regexAnchored;  // starts with '^': lookingAt() instead of find()
    Pattern hostRegex;      // necessary condition on the request host (see NetworkEngine.regexHostPart)
    String hostPart;        // source of hostRegex
    boolean hostAnchor, startAnchor, endAnchor, matchCase;
    boolean exception, important;
    boolean pureHostname;   // "||host^" with nothing else: also blocks the main document (interstitial)
    int types;              // 0 = any subresource
    int notTypes;
    int party;              // 0 any, 1 first-party only, 2 third-party only
    int pageFlags;          // for exceptions with $document / $elemhide / $generichide / $specifichide
    long[] domainsInc, domainsExc;     // $domain= / $from=, sorted
    long[] toInc, toExc;               // $to= (request host), sorted
    long[] denyallow;                  // $denyallow=, sorted
    String method;                     // upper-case, null = any
    String redirect;                   // resource name
    boolean redirectRule;              // $redirect-rule= (only applies to otherwise-blocked requests)

    boolean typeMatches(int reqType) {
        if (pageFlags != 0) return (reqType & T_DOCUMENT) != 0;
        int t = types;
        if (t == 0) {
            if ((reqType & T_POPUP) != 0) return false;
            if (reqType == T_DOCUMENT) return pureHostname || exception;
            return (notTypes & reqType) != reqType;
        }
        return (t & reqType) != 0 && (notTypes & reqType) != reqType;
    }

    boolean optionsMatch(Request r) {
        if (party == 1 && r.thirdParty) return false;
        if (party == 2 && !r.thirdParty) return false;
        if (method != null && r.method != null && !method.equalsIgnoreCase(r.method)) return false;
        if (domainsInc != null && !r.page.matchesAny(domainsInc)) return false;
        if (domainsExc != null && r.page.matchesAny(domainsExc)) return false;
        if (toInc != null && !r.req.matchesAny(toInc)) return false;
        if (toExc != null && r.req.matchesAny(toExc)) return false;
        if (denyallow != null && r.req.matchesAny(denyallow)) return false;
        return true;
    }

    boolean urlMatches(Request r) {
        String url = matchCase ? r.url : r.urlLower;
        if (regex != null) {
            if (regexLiteral != null && r.urlLower.indexOf(regexLiteral) < 0) return false;
            if (hostRegex != null && !hostRegex.matcher(r.host).matches()) return false;
            Matcher m = regex.matcher(url);
            return regexAnchored ? m.lookingAt() : m.find();
        }
        String[] p = parts;
        int len = url.length();
        if (hostAnchor) {
            int hs = r.hostStart, he = r.hostEnd;
            for (int pos = hs; pos < he; pos++) {
                if (pos != hs && url.charAt(pos - 1) != '.') continue;
                int e = matchSeg(url, pos, p[0]);
                if (e >= 0 && rest(url, e, p, 1)) return true;
            }
            return false;
        }
        if (startAnchor) {
            int e = matchSeg(url, 0, p[0]);
            return e >= 0 && rest(url, e, p, 1);
        }
        if (p.length == 1 && !endAnchor) return find(url, 0, p[0]) >= 0;
        // floating first segment: try every occurrence
        String first = p[0];
        if (first.isEmpty()) return rest(url, 0, p, 1);
        int from = 0;
        while (from <= len) {
            int s = findStart(url, from, first);
            if (s < 0) return false;
            int e = matchSeg(url, s, first);
            if (e >= 0 && rest(url, e, p, 1)) return true;
            from = s + 1;
        }
        return false;
    }

    /** Match parts[i..] floating after position pos; last part honours endAnchor. */
    private boolean rest(String url, int pos, String[] p, int i) {
        if (i == p.length) return !endAnchor || pos == url.length() || p[p.length - 1].isEmpty();
        String seg = p[i];
        if (i == p.length - 1) {
            if (seg.isEmpty()) return true; // trailing '*'
            if (endAnchor) {
                for (int s = url.length(); s >= pos; s--) {
                    int e = matchSeg(url, s, seg);
                    if (e == url.length()) return true;
                }
                return false;
            }
            return find(url, pos, seg) >= 0;
        }
        if (seg.isEmpty()) return rest(url, pos, p, i + 1);
        int from = pos;
        // leftmost occurrence is sufficient for '*'-separated segments
        int s = findStart(url, from, seg);
        while (s >= 0) {
            int e = matchSeg(url, s, seg);
            if (e >= 0) return rest(url, e, p, i + 1);
            s = findStart(url, s + 1, seg);
        }
        return false;
    }

    /** Returns end index if seg matches at pos, else -1. '^' matches a separator or the end of the URL. */
    static int matchSeg(String url, int pos, String seg) {
        int len = url.length();
        int u = pos;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '^') {
                if (u == len) {
                    // end of address matches only as the last token(s)
                    for (int j = i + 1; j < seg.length(); j++) if (seg.charAt(j) != '^') return -1;
                    return len;
                }
                if (!isSeparator(url.charAt(u))) return -1;
                u++;
            } else {
                if (u >= len || url.charAt(u) != c) return -1;
                u++;
            }
        }
        return u;
    }

    private static int find(String url, int from, String seg) {
        if (seg.indexOf('^') < 0) return url.indexOf(seg, from);
        int s = findStart(url, from, seg);
        while (s >= 0) {
            if (matchSeg(url, s, seg) >= 0) return s;
            s = findStart(url, s + 1, seg);
        }
        return -1;
    }

    /** Candidate start positions: next index where the first literal char of seg could match. */
    private static int findStart(String url, int from, String seg) {
        if (seg.isEmpty()) return from <= url.length() ? from : -1;
        char c = seg.charAt(0);
        if (c != '^') {
            int caret = seg.indexOf('^');
            if (caret < 0) return url.indexOf(seg, from);
            return url.indexOf(seg.substring(0, caret), from);
        }
        for (int i = from; i <= url.length(); i++) {
            if (i == url.length() || isSeparator(url.charAt(i))) return i;
        }
        return -1;
    }

    static boolean isSeparator(char c) {
        if (c >= 'a' && c <= 'z') return false;
        if (c >= 'A' && c <= 'Z') return false;
        if (c >= '0' && c <= '9') return false;
        return !(c == '_' || c == '-' || c == '.' || c == '%');
    }

    // ------------------------------------------------------------------ compiled form (see FilterCompiler)

    private static final int F_EXCEPTION = 1, F_IMPORTANT = 1 << 1, F_HOST_ANCHOR = 1 << 2, F_START_ANCHOR = 1 << 3,
            F_END_ANCHOR = 1 << 4, F_MATCH_CASE = 1 << 5, F_PURE_HOSTNAME = 1 << 6, F_REDIRECT_RULE = 1 << 7,
            F_REGEX = 1 << 8, F_METHOD = 1 << 9, F_REDIRECT = 1 << 10, F_DOMAINS_INC = 1 << 11,
            F_DOMAINS_EXC = 1 << 12, F_TO_INC = 1 << 13, F_TO_EXC = 1 << 14, F_DENYALLOW = 1 << 15;

    /** Interns long arrays (domain lists) in the records section; returns a non-zero relative offset. */
    interface ArrayPool {
        int ref(long[] a);
    }

    void encode(BlobWriter w, ArrayPool pool) {
        int fl = (exception ? F_EXCEPTION : 0) | (important ? F_IMPORTANT : 0) | (hostAnchor ? F_HOST_ANCHOR : 0)
                | (startAnchor ? F_START_ANCHOR : 0) | (endAnchor ? F_END_ANCHOR : 0) | (matchCase ? F_MATCH_CASE : 0)
                | (pureHostname ? F_PURE_HOSTNAME : 0) | (redirectRule ? F_REDIRECT_RULE : 0)
                | (regex != null ? F_REGEX : 0) | (method != null ? F_METHOD : 0) | (redirect != null ? F_REDIRECT : 0)
                | (domainsInc != null ? F_DOMAINS_INC : 0) | (domainsExc != null ? F_DOMAINS_EXC : 0)
                | (toInc != null ? F_TO_INC : 0) | (toExc != null ? F_TO_EXC : 0) | (denyallow != null ? F_DENYALLOW : 0);
        w.varint(fl);
        w.varint(types);
        w.varint(notTypes);
        w.u8(party | (pageFlags << 2));
        w.string(pattern);
        if (regex != null) {
            w.string(regexSrc);
            w.string(hostPart == null ? "" : hostPart);
            w.string(regexLiteral == null ? "" : regexLiteral);
        }
        if (method != null) w.string(method);
        if (redirect != null) w.string(redirect);
        if (domainsInc != null) w.varint(pool.ref(domainsInc));
        if (domainsExc != null) w.varint(pool.ref(domainsExc));
        if (toInc != null) w.varint(pool.ref(toInc));
        if (toExc != null) w.varint(pool.ref(toExc));
        if (denyallow != null) w.varint(pool.ref(denyallow));
    }

    /**
     * Cheap pre-check on the record header: the request type test of {@link #typeMatches} (skipped for page-flag
     * lookups) plus the party option, so most records are rejected without decoding their strings.
     */
    static boolean headerMatches(Blob.Cursor c, int reqType, boolean thirdParty, boolean pageLookup) {
        int fl = c.varint();
        int t = c.varint();
        int nt = c.varint();
        int pp = c.u8();
        int party = pp & 3, pageFlags = pp >>> 2;
        if (party == 1 && thirdParty) return false;
        if (party == 2 && !thirdParty) return false;
        if (pageLookup) return true;
        if (pageFlags != 0) return (reqType & T_DOCUMENT) != 0;
        if (t == 0) {
            if ((reqType & T_POPUP) != 0) return false;
            if (reqType == T_DOCUMENT) return (fl & (F_PURE_HOSTNAME | F_EXCEPTION)) != 0;
            return (nt & reqType) != reqType;
        }
        return (t & reqType) != 0 && (nt & reqType) != reqType;
    }

    static NetFilter decode(Blob.Cursor c, int base) {
        NetFilter f = new NetFilter();
        int fl = c.varint();
        f.types = c.varint();
        f.notTypes = c.varint();
        int pp = c.u8();
        f.party = pp & 3;
        f.pageFlags = pp >>> 2;
        f.exception = (fl & F_EXCEPTION) != 0;
        f.important = (fl & F_IMPORTANT) != 0;
        f.hostAnchor = (fl & F_HOST_ANCHOR) != 0;
        f.startAnchor = (fl & F_START_ANCHOR) != 0;
        f.endAnchor = (fl & F_END_ANCHOR) != 0;
        f.matchCase = (fl & F_MATCH_CASE) != 0;
        f.pureHostname = (fl & F_PURE_HOSTNAME) != 0;
        f.redirectRule = (fl & F_REDIRECT_RULE) != 0;
        f.pattern = c.string();
        if ((fl & F_REGEX) != 0) {
            f.regexSrc = c.string();
            f.regex = Pattern.compile(f.regexSrc);
            f.regexAnchored = f.regexSrc.startsWith("^");
            String hp = c.string();
            if (!hp.isEmpty()) {
                f.hostPart = hp;
                f.hostRegex = Pattern.compile("^(?:" + hp + ")$");
            }
            String lit = c.string();
            f.regexLiteral = lit.isEmpty() ? null : lit;
        } else {
            f.parts = f.pattern.split("\\*", -1);
        }
        if ((fl & F_METHOD) != 0) f.method = c.string();
        if ((fl & F_REDIRECT) != 0) f.redirect = c.string();
        if ((fl & F_DOMAINS_INC) != 0) f.domainsInc = c.longArrayAt(base);
        if ((fl & F_DOMAINS_EXC) != 0) f.domainsExc = c.longArrayAt(base);
        if ((fl & F_TO_INC) != 0) f.toInc = c.longArrayAt(base);
        if ((fl & F_TO_EXC) != 0) f.toExc = c.longArrayAt(base);
        if ((fl & F_DENYALLOW) != 0) f.denyallow = c.longArrayAt(base);
        return f;
    }
}
