package cleartvref;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses ABP / uBlock Origin network filters and matches requests against them. Filters are indexed by their
 * rarest literal token (uBO-style) so each request only tests the few filters that share a token with its URL.
 */
public final class NetworkEngine {
    private static final HashSet<String> BAD_TOKENS = new HashSet<>(Arrays.asList(
            "http", "https", "www", "com", "net", "org", "js", "html", "htm", "php", "jpg", "jpeg", "png", "gif",
            "css", "static", "cdn", "img", "images", "assets", "api", "min", "v1", "v2", "id", "ad", "js?"));

    /** Hostnames from "||host^" filters without options (and hosts-file lines); merged with the domain set. */
    final LongSet.Builder hostnameBlocks = new LongSet.Builder();

    private final IndexBuilder block = new IndexBuilder();
    private final IndexBuilder docBlock = new IndexBuilder();  // filters for $document / $popup only
    private final IndexBuilder except = new IndexBuilder();
    private final IndexBuilder important = new IndexBuilder();
    private final IndexBuilder redirectRules = new IndexBuilder();
    private final IndexBuilder pageExcept = new IndexBuilder();
    private Index iBlock, iDocBlock, iExcept, iImportant, iRedirect, iPage;
    private final HashSet<String> badfilters = new HashSet<>();
    public int filterCount, droppedCount;

    /** First pass: remember "$badfilter" targets so the second pass can skip them. */
    public void preScan(String line) {
        if (line.indexOf("badfilter") < 0) return;
        String l = line.trim();
        int d = optionsStart(l);
        if (d < 0) return;
        String[] opts = l.substring(d + 1).split(",");
        StringBuilder sb = new StringBuilder();
        boolean had = false;
        for (String o : opts) {
            if (o.equals("badfilter")) { had = true; continue; }
            if (sb.length() > 0) sb.append(',');
            sb.append(o);
        }
        if (had) badfilters.add(sb.length() == 0 ? l.substring(0, d) : l.substring(0, d) + "$" + sb);
    }

    private static final HashSet<String> OPTION_NAMES = new HashSet<>(Arrays.asList(
            "script", "image", "stylesheet", "css", "object", "object-subrequest", "xmlhttprequest", "xhr",
            "subdocument", "frame", "ping", "beacon", "media", "font", "websocket", "other", "document", "doc",
            "popup", "popunder", "third-party", "3p", "first-party", "1p", "strict1p", "strict3p", "all", "important",
            "match-case", "domain", "from", "to", "denyallow", "method", "redirect", "redirect-rule", "empty", "mp4",
            "elemhide", "ehide", "generichide", "ghide", "specifichide", "shide", "reason", "_", "noop", "cname",
            "badfilter", "csp", "removeparam", "queryprune", "header", "permissions", "replace", "urltransform",
            "uritransform", "urlskip", "inline-script", "inline-font", "ipaddress", "rewrite", "genericblock",
            "webrtc", "content", "jsinject", "extension", "stealth", "cookie", "network", "app", "removeheader",
            "hls", "jsonprune", "xmlprune", "referrerpolicy", "webtransport", "~third-party", "~3p", "~1p",
            "~first-party", "~script", "~image", "~stylesheet", "~css", "~object", "~xmlhttprequest", "~xhr",
            "~subdocument", "~frame", "~ping", "~media", "~font", "~websocket", "~other", "~document", "~doc",
            "~popup", "~match-case"));

    /** Index of the '$' that starts the options, or -1. Regex filters end at their closing "/$". */
    private static int optionsStart(String l) {
        String p = l.startsWith("@@") ? l.substring(2) : l;
        int off = l.length() - p.length();
        if (p.startsWith("/")) {
            for (int i = 1; i + 2 < p.length(); i++) {
                if (p.charAt(i) == '/' && p.charAt(i + 1) == '$' && p.charAt(i - 1) != '\\'
                        && startsWithOption(p, i + 2)) return off + i + 1;
            }
        }
        int d = p.indexOf('$');
        while (d >= 0) {
            if (d + 1 < p.length() && startsWithOption(p, d + 1)) return off + d;
            d = p.indexOf('$', d + 1);
        }
        return -1;
    }

    private static boolean startsWithOption(String p, int from) {
        int e = from;
        while (e < p.length() && p.charAt(e) != ',' && p.charAt(e) != '=') e++;
        return OPTION_NAMES.contains(p.substring(from, e).toLowerCase(Locale.ROOT));
    }

    /** Parse one network filter line (cosmetic lines must be routed elsewhere). Returns true if kept. */
    public boolean add(String line) {
        String l = line.trim();
        if (l.isEmpty()) return false;
        if (badfilters.contains(l)) { droppedCount++; return false; }
        // hosts-file syntax
        if (l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ")) {
            String h = l.substring(l.indexOf(' ') + 1).trim();
            int sp = h.indexOf(' ');
            if (sp > 0) h = h.substring(0, sp);
            h = h.toLowerCase(Locale.ROOT);
            if (isHostname(h) && !h.equals("localhost") && !h.equals("0.0.0.0")) {
                hostnameBlocks.add(Hash.of(h));
                filterCount++;
                return true;
            }
            return false;
        }
        NetFilter f = new NetFilter();
        if (l.startsWith("@@")) { f.exception = true; l = l.substring(2); }
        String pat = l;
        int d = optionsStart(l);
        if (d >= 0) {
            pat = l.substring(0, d);
            if (!parseOptions(f, l.substring(d + 1))) { droppedCount++; return false; }
        }
        if (pat.length() >= 2 && pat.startsWith("/") && pat.endsWith("/") && pat.length() > 2) {
            try {
                String src = pat.substring(1, pat.length() - 1);
                // URLs are matched lowercased, so lowercase the regex (outside escapes) instead of using the much
                // slower CASE_INSENSITIVE mode; "[A-Z]" becomes "[a-z]", which is what case-insensitivity means.
                src = simplifyRegex(f.matchCase ? src : lowerRegex(src));
                f.regex = Pattern.compile(src);
                f.regexAnchored = src.startsWith("^");
                String hostPart = regexHostPart(src);
                if (hostPart != null) f.hostRegex = Pattern.compile("^(?:" + hostPart + ")$");
            } catch (PatternSyntaxException e) {
                droppedCount++;
                return false;
            }
            f.pattern = pat;
            f.regexLiteral = regexLiteral(pat.substring(1, pat.length() - 1));
        } else {
            if (pat.startsWith("||")) { f.hostAnchor = true; pat = pat.substring(2); }
            else if (pat.startsWith("|")) { f.startAnchor = true; pat = pat.substring(1); }
            if (pat.endsWith("|") && !pat.endsWith("\\|")) { f.endAnchor = true; pat = pat.substring(0, pat.length() - 1); }
            // collapse runs of '*' and strip leading/trailing '*'
            while (pat.contains("**")) pat = pat.replace("**", "*");
            if (pat.startsWith("*") && !f.hostAnchor) { pat = pat.substring(1); f.startAnchor = false; }
            if (pat.endsWith("*")) { pat = pat.substring(0, pat.length() - 1); f.endAnchor = false; }
            if (!f.matchCase) pat = pat.toLowerCase(Locale.ROOT);
            f.pattern = pat;
            f.parts = pat.split("\\*", -1);
            if (f.hostAnchor && pat.endsWith("^") && pat.indexOf('*') < 0 && isHostname(pat.substring(0, pat.length() - 1))) {
                f.pureHostname = true;
            }
        }
        if (f.pattern.isEmpty() && f.domainsInc == null && f.toInc == null && f.pageFlags == 0) {
            droppedCount++; // "*" style filters without any restriction would block everything
            return false;
        }
        // Plain "||host^" with no options: domain set (fast path)
        if (!f.exception && f.pureHostname && !f.important && f.types == 0 && f.notTypes == 0 && f.party == 0
                && f.domainsInc == null && f.domainsExc == null && f.toInc == null && f.toExc == null
                && f.denyallow == null && f.method == null && f.redirect == null) {
            hostnameBlocks.add(Hash.of(f.pattern, 0, f.pattern.length() - 1));
            filterCount++;
            return true;
        }
        IndexBuilder target;
        if (f.pageFlags != 0) target = f.exception ? pageExcept : null;
        else if (f.exception) target = except;
        else if (f.redirectRule) target = redirectRules;
        else if (f.important) target = important;
        else if (f.types != 0 && (f.types & ~(NetFilter.T_DOCUMENT | NetFilter.T_POPUP)) == 0) target = docBlock;
        else target = block;
        if (target == null) { droppedCount++; return false; }
        target.add(f, tokenOf(f));
        filterCount++;
        return true;
    }

    static boolean isHostname(String h) {
        if (h.isEmpty() || h.length() > 253 || h.indexOf('.') < 0) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_')) return false;
        }
        return h.charAt(0) != '.' && h.charAt(h.length() - 1) != '.';
    }

    private boolean parseOptions(NetFilter f, String optStr) {
        String[] opts = optStr.split(",");
        int types = 0, notTypes = 0;
        for (String raw : opts) {
            String o = raw.trim();
            if (o.isEmpty()) continue;
            boolean neg = o.startsWith("~");
            String name = neg ? o.substring(1) : o;
            String val = null;
            int eq = name.indexOf('=');
            if (eq >= 0) { val = name.substring(eq + 1); name = name.substring(0, eq); }
            name = name.toLowerCase(Locale.ROOT);
            int t = typeBit(name);
            if (t != 0) {
                if (neg) notTypes |= t; else types |= t;
                continue;
            }
            switch (name) {
                case "third-party": case "3p": f.party = neg ? 1 : 2; break;
                case "first-party": case "1p": f.party = neg ? 2 : 1; break;
                case "strict1p": f.party = 1; break;
                case "strict3p": f.party = 2; break;
                case "all": types |= NetFilter.T_ALL_SUB | NetFilter.T_DOCUMENT | NetFilter.T_POPUP; break;
                case "important": f.important = true; break;
                case "match-case": f.matchCase = true; break;
                case "domain": case "from":
                    if (val == null) return false;
                    if (!domainList(val, f, false)) return false;
                    break;
                case "to":
                    if (val == null) return false;
                    if (!domainList(val, f, true)) return false;
                    break;
                case "denyallow":
                    if (val == null) return false;
                    f.denyallow = hashList(val.split("\\|"));
                    break;
                case "method":
                    if (val == null || val.indexOf('|') >= 0 || val.startsWith("~")) return false;
                    f.method = val.toUpperCase(Locale.ROOT);
                    break;
                case "redirect":
                    if (val == null) return false;
                    f.redirect = Redirects.normalize(val);
                    if (f.redirect == null) return false;
                    break;
                case "redirect-rule":
                    if (val == null) return false;
                    f.redirect = Redirects.normalize(val);
                    if (f.redirect == null) return false;
                    f.redirectRule = true;
                    break;
                case "empty": f.redirect = "empty"; break;
                case "mp4": f.redirect = "noopmp4-1s"; types |= NetFilter.T_MEDIA; break;
                case "elemhide": case "ehide": f.pageFlags |= NetFilter.P_ELEMHIDE; break;
                case "generichide": case "ghide": f.pageFlags |= NetFilter.P_GENERICHIDE; break;
                case "specifichide": case "shide": f.pageFlags |= NetFilter.P_SPECIFICHIDE; break;
                case "reason": case "_": case "noop": case "cname": break;
                default:
                    return false; // csp, removeparam, header, replace, popunder, etc. are not applicable here
            }
        }
        if ((types & NetFilter.T_DOCUMENT) != 0 && f.exception && types == NetFilter.T_DOCUMENT) {
            f.pageFlags |= NetFilter.P_DOCUMENT;
            types = 0;
        }
        if (f.exception && f.redirect != null) return false; // redirect exceptions are not modelled
        if (f.pageFlags != 0 && !f.exception) return false;
        f.types = types;
        f.notTypes = notTypes;
        if (types == 0 && notTypes != 0) {
            // "~script" alone means all other subresource types
            f.types = NetFilter.T_ALL_SUB & ~notTypes;
            f.notTypes = 0;
        }
        return true;
    }

    private static int typeBit(String n) {
        switch (n) {
            case "script": return NetFilter.T_SCRIPT;
            case "image": return NetFilter.T_IMAGE;
            case "stylesheet": case "css": return NetFilter.T_STYLESHEET;
            case "object": case "object-subrequest": return NetFilter.T_OBJECT;
            case "xmlhttprequest": case "xhr": return NetFilter.T_XHR;
            case "subdocument": case "frame": return NetFilter.T_SUBDOCUMENT;
            case "ping": case "beacon": return NetFilter.T_PING;
            case "media": return NetFilter.T_MEDIA;
            case "font": return NetFilter.T_FONT;
            case "websocket": return NetFilter.T_WEBSOCKET;
            case "other": return NetFilter.T_OTHER;
            case "document": case "doc": return NetFilter.T_DOCUMENT;
            case "popup": return NetFilter.T_POPUP;
            default: return 0;
        }
    }

    private static boolean domainList(String val, NetFilter f, boolean to) {
        ArrayList<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        for (String d : val.split("\\|")) {
            d = d.trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty()) continue;
            if (d.startsWith("/")) return false; // regex domain lists are not supported
            if (d.startsWith("~")) exc.add(d.substring(1)); else inc.add(d);
        }
        long[] i = inc.isEmpty() ? null : hashList(inc.toArray(new String[0]));
        long[] e = exc.isEmpty() ? null : hashList(exc.toArray(new String[0]));
        if (to) { f.toInc = i; f.toExc = e; } else { f.domainsInc = i; f.domainsExc = e; }
        return true;
    }

    static long[] hashList(String[] names) {
        long[] a = new long[names.length];
        int n = 0;
        for (String s : names) {
            s = s.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) a[n++] = Hash.of(s);
        }
        a = Arrays.copyOf(a, n);
        Arrays.sort(a);
        return a;
    }

    /** Best literal token of the pattern (uBO-style), or 0 when the filter must go to the fallback bucket. */
    static long tokenOf(NetFilter f) {
        if (f.regex != null) return regexToken(f.pattern.substring(1, f.pattern.length() - 1));
        String p = f.pattern.toLowerCase(Locale.ROOT);
        int len = p.length();
        long best = 0;
        int bestScore = 0;
        int i = 0;
        while (i < len) {
            if (!isTokenChar(p.charAt(i))) { i++; continue; }
            int j = i;
            while (j < len && isTokenChar(p.charAt(j))) j++;
            boolean leftOk = i > 0 ? p.charAt(i - 1) != '*' : (f.hostAnchor || f.startAnchor);
            boolean rightOk = j < len ? p.charAt(j) != '*' : f.endAnchor;
            if (leftOk && rightOk && j - i >= 2) {
                String tok = p.substring(i, j);
                int score = (j - i) * 2 + (BAD_TOKENS.contains(tok) ? -100 : 0);
                if (best == 0 || score > bestScore) { best = Hash.of(p, i, j); bestScore = score; }
            }
            i = j;
        }
        return best;
    }

    /**
     * A literal token every match of the regex must contain as a whole URL token: a run of [a-z0-9%] outside
     * groups and classes, bounded on both sides by literal non-token characters and not made optional by a
     * quantifier. Returns 0 if none can be proven (the filter then goes to a fallback bucket).
     */
    static long regexToken(String re) {
        String r = re.toLowerCase(Locale.ROOT);
        int n = r.length();
        int depth = 0;
        for (int i = 0; i < n; i++) {
            char c = r.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '[') { while (i < n && r.charAt(i) != ']') { if (r.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '|' && depth == 0) return 0; // top-level alternation
        }
        long best = 0;
        int bestLen = 0;
        depth = 0;
        StringBuilder run = new StringBuilder();
        boolean leftOk = false;
        int i = 0;
        while (i <= n) {
            char c = i < n ? r.charAt(i) : 0;
            boolean literalTok = false, literalSep = false;
            int adv = 1;
            if (i == n) {
                literalSep = false;
            } else if (c == '\\' && i + 1 < n) {
                char e = r.charAt(i + 1);
                adv = 2;
                if ("./-_:?&=+#%~!@,;".indexOf(e) >= 0) literalSep = depth == 0 && e != '%';
                else literalSep = false; // \d \w \b etc.
            } else if (isTokenChar(c) && c != '%') {
                literalTok = depth == 0;
            } else if (c == '/' || c == '-' || c == '_' || c == ':' || c == '=' || c == '&' || c == ',' || c == ';') {
                literalSep = depth == 0;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '[') {
                while (i < n && r.charAt(i) != ']') { if (r.charAt(i) == '\\') i++; i++; }
            }
            // a quantifier after the current char makes it optional or repeatable
            char next = i + adv < n ? r.charAt(i + adv) : 0;
            boolean quant = next == '?' || next == '*' || next == '{' || next == '+';
            if (literalTok && !quant) {
                run.append(c);
            } else {
                boolean rightOk = literalSep && !(quant && next != '+');
                if (run.length() >= 2 && leftOk && rightOk && run.length() > bestLen) {
                    String tok = run.toString();
                    if (!BAD_TOKENS.contains(tok) || best == 0) { best = Hash.of(tok); bestLen = run.length(); }
                }
                run.setLength(0);
                leftOk = literalSep && !quant && depth == 0;
            }
            i += adv;
        }
        return best;
    }

    /**
     * Longest literal substring (lowercase, at least 3 chars) that every match of the regex contains, used as a
     * cheap indexOf() pre-check before running the regex. Null when none can be proven.
     */
    static String regexLiteral(String re) {
        String r = re.toLowerCase(Locale.ROOT);
        int n = r.length();
        int depth = 0;
        for (int i = 0; i < n; i++) {
            char c = r.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '[') { while (i < n && r.charAt(i) != ']') { if (r.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '|' && depth == 0) return null;
        }
        String best = null;
        StringBuilder run = new StringBuilder();
        depth = 0;
        int i = 0;
        while (i <= n) {
            char lit = 0;
            int adv = 1;
            boolean isLit = false;
            if (i < n) {
                char c = r.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    char e = r.charAt(i + 1);
                    adv = 2;
                    if (!Character.isLetterOrDigit(e)) { lit = e; isLit = depth == 0; }
                } else if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == '[') { while (i < n && r.charAt(i) != ']') { if (r.charAt(i) == '\\') i++; i++; } }
                else if (".^$*+?{}|".indexOf(c) < 0) { lit = c; isLit = depth == 0; }
            }
            char next = i + adv < n ? r.charAt(i + adv) : 0;
            boolean optional = next == '?' || next == '*' || next == '{';
            if (isLit && !optional) {
                run.append(lit);
                if (next == '+') { // repeatable char: keep it, but the run cannot continue past it
                    if (run.length() >= 3 && (best == null || run.length() > best.length()) && useful(run)) best = run.toString();
                    run.setLength(0);
                    run.append(lit);
                }
            } else {
                if (run.length() >= 3 && (best == null || run.length() > best.length()) && useful(run)) best = run.toString();
                run.setLength(0);
            }
            i += adv;
        }
        return best;
    }

    /**
     * For regexes shaped like "^https?:\/\/HOST\/..." where HOST can only consume hostname characters, returns
     * HOST so it can be tested against the request's hostname first. That test is a necessary condition for the
     * full match and costs a fraction of it (several uBO regexes backtrack heavily over the host part).
     */
    static String regexHostPart(String src) {
        String[] prefixes = {"^https?:\\/\\/", "^https:\\/\\/", "^http:\\/\\/"};
        String rest = null;
        for (String p : prefixes) if (src.startsWith(p)) { rest = src.substring(p.length()); break; }
        if (rest == null) return null;
        int depth = 0, end = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\\') {
                if (i + 1 < rest.length() && rest.charAt(i + 1) == '/' && depth == 0) { end = i; break; }
                i++;
                continue;
            }
            if (c == '[') { while (i < rest.length() && rest.charAt(i) != ']') { if (rest.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        if (end <= 0) return null;
        String h = rest.substring(0, end);
        // only hostname characters may be consumed
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '\\') {
                if (i + 1 >= h.length()) return null;
                char e = h.charAt(++i);
                if (e != '.' && e != 'd' && e != '-') return null;
                continue;
            }
            if (c == '/' || c == '$' || c == '^') return null;
            if (c == '.') return null; // unescaped dot = any char
            if (c == '[') {
                if (i + 1 < h.length() && h.charAt(i + 1) == '^') return null;
                int j = i + 1;
                while (j < h.length() && h.charAt(j) != ']') {
                    char d = h.charAt(j);
                    if (d == '\\') {
                        if (j + 1 < h.length() && "d.-".indexOf(h.charAt(j + 1)) < 0) return null;
                        j += 2;
                        continue;
                    }
                    if (!((d >= 'a' && d <= 'z') || (d >= 'A' && d <= 'Z') || (d >= '0' && d <= '9') || d == '-'
                            || d == '.' || d == '_')) return null;
                    j++;
                }
                if (j >= h.length()) return null;
                i = j;
                continue;
            }
            if (c == '(' && i + 2 < h.length() && h.charAt(i + 1) == '?' && h.charAt(i + 2) == '<') return null;
        }
        return h;
    }

    /**
     * Merge runs of the same single-character atom ("[a-z][a-z][a-z]?[a-z]?" -> "[a-z]{2,4}"). The language is
     * identical, but Java's backtracking matcher no longer explores every way to split the run, which made some
     * generated uBO filters cost tens of microseconds per URL.
     */
    static String simplifyRegex(String src) {
        ArrayList<String> atoms = new ArrayList<>();
        ArrayList<int[]> q = new ArrayList<>(); // {min, max(-1 = inf)} or null for non-mergeable
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            int start = i;
            boolean mergeable = true;
            if (c == '\\' && i + 1 < n) {
                i += 2;
                char e = src.charAt(i - 1);
                if (Character.isDigit(e) || e == 'b' || e == 'B' || e == 'p' || e == 'P' || e == 'u' || e == 'x'
                        || e == 'k' || e == 'Q' || e == 'E') mergeable = false;
            } else if (c == '[') {
                i++;
                if (i < n && src.charAt(i) == '^') i++;
                if (i < n && src.charAt(i) == ']') i++;
                while (i < n && src.charAt(i) != ']') { if (src.charAt(i) == '\\') i++; i++; }
                i++;
                if (i > n) return src;
            } else if ("()|^$".indexOf(c) >= 0) {
                i++;
                mergeable = false;
                if (c == '(' && i < n && src.charAt(i) == '?') {
                    // keep group-type prefix attached: "(?:", "(?=", "(?!", "(?<=", "(?<!"
                    int j = i + 1;
                    if (j < n && src.charAt(j) == '<') j++;
                    if (j < n) j++;
                    i = j;
                }
            } else if ("*+?{".indexOf(c) >= 0) {
                return src; // quantifier where an atom was expected: leave the regex alone
            } else {
                i++;
            }
            String atom = src.substring(start, i);
            int min = 1, max = 1;
            if (mergeable && i < n) {
                char d = src.charAt(i);
                if (d == '?') { min = 0; max = 1; i++; }
                else if (d == '*') { min = 0; max = -1; i++; }
                else if (d == '+') { min = 1; max = -1; i++; }
                else if (d == '{') {
                    int close = src.indexOf('}', i);
                    if (close < 0) return src;
                    String body = src.substring(i + 1, close);
                    try {
                        int comma = body.indexOf(',');
                        if (comma < 0) { min = max = Integer.parseInt(body.trim()); }
                        else {
                            min = Integer.parseInt(body.substring(0, comma).trim());
                            String mx = body.substring(comma + 1).trim();
                            max = mx.isEmpty() ? -1 : Integer.parseInt(mx);
                        }
                    } catch (NumberFormatException ex) {
                        return src;
                    }
                    i = close + 1;
                }
                if (i < n && (src.charAt(i) == '?' || src.charAt(i) == '+')) {
                    // lazy or possessive quantifier: do not merge
                    atoms.add(src.substring(start, i + 1));
                    q.add(null);
                    i++;
                    continue;
                }
            } else if (!mergeable && i < n && "*+?{".indexOf(src.charAt(i)) >= 0 && !(atom.equals("(") || atom.startsWith("(?") || atom.equals("|") || atom.equals("^"))) {
                // quantified non-mergeable atom (e.g. ")?" or "\\1+"): copy the quantifier verbatim
                int j = i;
                if (src.charAt(j) == '{') { j = src.indexOf('}', j); if (j < 0) return src; }
                j++;
                if (j < n && (src.charAt(j) == '?' || src.charAt(j) == '+')) j++;
                atom = src.substring(start, j);
                i = j;
            }
            atoms.add(atom);
            q.add(mergeable ? new int[]{min, max} : null);
        }
        StringBuilder sb = new StringBuilder(n);
        int k = 0;
        while (k < atoms.size()) {
            String a = atoms.get(k);
            int[] qq = q.get(k);
            if (qq == null) { sb.append(a); k++; continue; }
            int min = qq[0], max = qq[1];
            int m = k + 1;
            while (m < atoms.size() && q.get(m) != null && atoms.get(m).equals(a)) {
                int[] o = q.get(m);
                min += o[0];
                max = (max < 0 || o[1] < 0) ? -1 : max + o[1];
                m++;
            }
            sb.append(a);
            if (min == 1 && max == 1) { /* single */ }
            else if (min == 0 && max == 1) sb.append('?');
            else if (min == 0 && max < 0) sb.append('*');
            else if (min == 1 && max < 0) sb.append('+');
            else if (max < 0) sb.append('{').append(min).append(",}");
            else if (min == max) sb.append('{').append(min).append('}');
            else sb.append('{').append(min).append(',').append(max).append('}');
            k = m;
        }
        return sb.toString();
    }

    static String lowerRegex(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length()) {
                char e = src.charAt(i + 1);
                sb.append(c).append(e);
                i++;
                if ((e == 'p' || e == 'P') && i + 1 < src.length() && src.charAt(i + 1) == '{') {
                    int close = src.indexOf('}', i);
                    if (close > 0) { sb.append(src, i + 1, close + 1); i = close; }
                }
                continue;
            }
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    private static boolean useful(CharSequence run) {
        String r = run.toString();
        return !(r.equals("://") || r.equals("http") || r.equals("https") || r.equals("http://")
                || r.equals("https://") || r.equals("s://") || r.equals("www."));
    }

    static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '%' || (c >= 'A' && c <= 'Z');
    }

    public void finish() {
        iBlock = block.build();
        iDocBlock = docBlock.build();
        iExcept = except.build();
        iImportant = important.build();
        iRedirect = redirectRules.build();
        iPage = pageExcept.build();
    }

    public String stats() {
        return "block=" + iBlock.stats() + " docBlock=" + iDocBlock.stats() + " except=" + iExcept.stats() + " important=" + iImportant.stats()
                + " redirectRule=" + iRedirect.stats() + " page=" + iPage.stats();
    }

    static final int NONE = 0, BLOCK = 1, ALLOW = 2;

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
        long[] toks = tokens(r);
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
        long[] toks = tokens(r);
        return iPage.flags(r, toks);
    }

    static long[] tokens(Request r) {
        String u = r.urlLower;
        int len = Math.min(u.length(), 2048);
        long[] out = new long[32];
        int n = 0;
        int i = 0;
        while (i < len) {
            if (!isTokenChar(u.charAt(i))) { i++; continue; }
            int j = i;
            while (j < len && isTokenChar(u.charAt(j))) j++;
            if (j - i >= 2) {
                long h = Hash.of(u, i, j);
                boolean dup = false;
                for (int k = 0; k < n; k++) if (out[k] == h) { dup = true; break; }
                if (!dup) {
                    if (n == out.length) out = Arrays.copyOf(out, n * 2);
                    out[n++] = h;
                }
            }
            i = j;
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    private static final class IndexBuilder {
        final LongObjMap<ArrayList<NetFilter>> map = new LongObjMap<>();
        final ArrayList<NetFilter> fallback = new ArrayList<>();

        final LongObjMap<ArrayList<NetFilter>> byDomain = new LongObjMap<>();

        void add(NetFilter f, long tok) {
            if (tok == 0 && f.domainsInc != null) {
                // no URL token: index by the page domains it is restricted to
                for (long d : f.domainsInc) {
                    ArrayList<NetFilter> l = byDomain.get(d);
                    if (l == null) { l = new ArrayList<>(2); byDomain.put(d, l); }
                    l.add(f);
                }
                return;
            }
            if (tok == 0) { fallback.add(f); return; }
            ArrayList<NetFilter> l = map.get(tok);
            if (l == null) { l = new ArrayList<>(2); map.put(tok, l); }
            l.add(f);
        }

        Index build() {
            final LongObjMap<NetFilter[]> m = new LongObjMap<>(map.size());
            final int[] cnt = new int[1];
            map.forEachEntry(new LongObjMap.Visitor<ArrayList<NetFilter>>() {
                @Override
                public void visit(long key, ArrayList<NetFilter> value) {
                    m.put(key, value.toArray(new NetFilter[0]));
                    cnt[0] += value.size();
                }
            });
            final LongObjMap<NetFilter[]> d = new LongObjMap<>(byDomain.size());
            byDomain.forEachEntry(new LongObjMap.Visitor<ArrayList<NetFilter>>() {
                @Override
                public void visit(long key, ArrayList<NetFilter> value) {
                    d.put(key, value.toArray(new NetFilter[0]));
                }
            });
            return new Index(m, d, fallback.toArray(new NetFilter[0]), cnt[0]);
        }
    }

    private static final class Index {
        final LongObjMap<NetFilter[]> buckets;
        final LongObjMap<NetFilter[]> byDomain;
        final NetFilter[] fallback;
        final int indexed;

        Index(LongObjMap<NetFilter[]> b, LongObjMap<NetFilter[]> d, NetFilter[] f, int indexed) {
            buckets = b;
            byDomain = d;
            fallback = f;
            this.indexed = indexed;
        }

        String stats() {
            return indexed + "+d" + byDomain.size() + "+" + fallback.length;
        }

        NetFilter find(Request r, long[] toks) {
            for (long t : toks) {
                NetFilter[] fs = buckets.get(t);
                if (fs == null) continue;
                for (NetFilter f : fs) if (test(f, r)) return f;
            }
            if (byDomain.size() != 0 && r.page != null) {
                for (long h : r.page.suffixHashes) {
                    NetFilter[] fs = byDomain.get(h);
                    if (fs != null) for (NetFilter f : fs) if (test(f, r)) return f;
                }
                for (long h : r.page.entityHashes) {
                    NetFilter[] fs = byDomain.get(h);
                    if (fs != null) for (NetFilter f : fs) if (test(f, r)) return f;
                }
            }
            for (NetFilter f : fallback) if (test(f, r)) return f;
            return null;
        }

        int flags(Request r, long[] toks) {
            int flags = 0;
            for (long t : toks) {
                NetFilter[] fs = buckets.get(t);
                if (fs == null) continue;
                for (NetFilter f : fs) if (testPage(f, r)) flags |= f.pageFlags;
            }
            if (byDomain.size() != 0 && r.page != null) {
                for (long h : r.page.suffixHashes) {
                    NetFilter[] fs = byDomain.get(h);
                    if (fs != null) for (NetFilter f : fs) if (testPage(f, r)) flags |= f.pageFlags;
                }
                for (long h : r.page.entityHashes) {
                    NetFilter[] fs = byDomain.get(h);
                    if (fs != null) for (NetFilter f : fs) if (testPage(f, r)) flags |= f.pageFlags;
                }
            }
            for (NetFilter f : fallback) if (testPage(f, r)) flags |= f.pageFlags;
            return flags;
        }

        private static boolean test(NetFilter f, Request r) {
            return f.typeMatches(r.type) && f.optionsMatch(r) && f.urlMatches(r);
        }

        private static boolean testPage(NetFilter f, Request r) {
            return f.optionsMatch(r) && f.urlMatches(r);
        }
    }
}
