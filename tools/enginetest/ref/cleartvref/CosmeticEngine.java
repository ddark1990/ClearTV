package cleartvref;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Element hiding ("##"), exceptions ("#@#"), uBO ":style()" / ":remove()" rules and "+js()" scriptlet rules.
 * Generic selectors that start with a class or id are keyed by that token and only sent to a page once the
 * page's DOM contains it (uBO's "lowly generic" approach); other generic selectors are always injected.
 */
public final class CosmeticEngine {
    private static final String[] PROCEDURAL = {
            ":has-text(", ":upward(", ":xpath(", ":matches-css", ":matches-attr(", ":matches-path(", ":matches-prop(",
            ":min-text-length(", ":others(", ":watch-attr(", ":-abp-", ":contains(", ":if(", ":if-not(",
            ":nth-ancestor(", ":matches-media(", ":shadow(", ":remove-attr(", ":remove-class(", ":spath(",
            ":properties(", ":matches-property(", "::shadow", ":xpath"};

    /** Scriptlets implemented by assets/content.js; the canonical name is the uBO one. */
    private static final HashMap<String, String> SCRIPTLET_ALIASES = new HashMap<>();

    static {
        String[][] a = {
                {"set-constant", "set-constant"}, {"set", "set-constant"},
                {"abort-on-property-read", "abort-on-property-read"}, {"aopr", "abort-on-property-read"},
                {"abort-on-property-write", "abort-on-property-write"}, {"aopw", "abort-on-property-write"},
                {"abort-current-script", "abort-current-script"}, {"acs", "abort-current-script"},
                {"abort-current-inline-script", "abort-current-script"}, {"acis", "abort-current-script"},
                {"no-setTimeout-if", "no-setTimeout-if"}, {"nostif", "no-setTimeout-if"},
                {"prevent-setTimeout", "no-setTimeout-if"}, {"setTimeout-defuser", "no-setTimeout-if"},
                {"no-setInterval-if", "no-setInterval-if"}, {"nosiif", "no-setInterval-if"},
                {"prevent-setInterval", "no-setInterval-if"}, {"setInterval-defuser", "no-setInterval-if"},
                {"adjust-setTimeout", "adjust-setTimeout"}, {"nano-setTimeout-booster", "adjust-setTimeout"},
                {"nano-stb", "adjust-setTimeout"},
                {"adjust-setInterval", "adjust-setInterval"}, {"nano-setInterval-booster", "adjust-setInterval"},
                {"nano-sib", "adjust-setInterval"},
                {"addEventListener-defuser", "addEventListener-defuser"}, {"aeld", "addEventListener-defuser"},
                {"prevent-addEventListener", "addEventListener-defuser"},
                {"remove-attr", "remove-attr"}, {"ra", "remove-attr"},
                {"remove-class", "remove-class"}, {"rc", "remove-class"},
                {"set-attr", "set-attr"},
                {"no-window-open-if", "no-window-open-if"}, {"nowoif", "no-window-open-if"},
                {"prevent-window-open", "no-window-open-if"}, {"window.open-defuser", "no-window-open-if"},
                {"prevent-fetch", "prevent-fetch"}, {"no-fetch-if", "prevent-fetch"},
                {"prevent-xhr", "prevent-xhr"}, {"no-xhr-if", "prevent-xhr"},
                {"json-prune", "json-prune"},
                {"json-prune-fetch-response", "json-prune-fetch-response"}, {"jpfr", "json-prune-fetch-response"},
                {"json-prune-xhr-response", "json-prune-xhr-response"}, {"jpxr", "json-prune-xhr-response"},
                {"set-local-storage-item", "set-local-storage-item"},
                {"set-session-storage-item", "set-session-storage-item"},
                {"set-cookie", "set-cookie"},
                {"remove-node-text", "remove-node-text"}, {"rmnt", "remove-node-text"},
                {"noeval-if", "noeval-if"}, {"prevent-eval-if", "noeval-if"},
                {"window.name-defuser", "window.name-defuser"},
                {"disable-newtab-links", "disable-newtab-links"},
        };
        for (String[] x : a) SCRIPTLET_ALIASES.put(x[0], x[1]);
    }

    static final class Spec {
        String selector;     // CSS selector, or null for scriptlets
        String style;        // declarations for :style(), null = display:none
        String[] scriptlet;  // [name, args...]
        String key;          // exception key: selector or "+js(name,args)"
        long[] exclude;      // ~domains
    }

    private final LongObjMap<ArrayList<String>> lowBuild = new LongObjMap<>();
    private final LinkedHashSet<String> highBuild = new LinkedHashSet<>();
    private final ArrayList<Spec> genericExclBuild = new ArrayList<>();   // generic with ~domain exclusions
    private final ArrayList<Spec> genericScriptlets = new ArrayList<>();
    private final LongObjMap<ArrayList<Spec>> specific = new LongObjMap<>();
    private final LongObjMap<ArrayList<String>> exceptions = new LongObjMap<>();
    private final HashSet<String> globalExceptions = new HashSet<>();

    private LongObjMap<String[]> low;
    private String[] high;
    public int cosmeticCount, scriptletCount, droppedCount;

    /** Returns the index of the cosmetic separator in line, or -1 for network filters. */
    public static int separatorIndex(String line) {
        int i = line.indexOf('#');
        while (i >= 0 && i + 1 < line.length()) {
            char c = line.charAt(i + 1);
            if (c == '#' || c == '@' || c == '?' || c == '$' || c == '%') {
                String pre = line.substring(0, i);
                if (pre.indexOf('/') < 0 && pre.indexOf('|') < 0 && pre.indexOf('^') < 0 && pre.indexOf('$') < 0
                        && pre.indexOf('=') < 0) {
                    return i;
                }
            }
            i = line.indexOf('#', i + 1);
        }
        // "$$" / "domain$$" (AdGuard HTML filtering) also counts as cosmetic, so it is not mistaken for a network rule
        int h = line.indexOf("$$");
        if (h >= 0 && line.substring(0, h).indexOf('/') < 0) return h;
        return -1;
    }

    public boolean add(String line, int sep) {
        String domains = line.substring(0, sep).trim();
        String rest = line.substring(sep);
        boolean exception;
        String body;
        if (rest.startsWith("##")) { exception = false; body = rest.substring(2); }
        else if (rest.startsWith("#@#")) { exception = true; body = rest.substring(3); }
        else if (rest.startsWith("#?#")) { exception = false; body = rest.substring(3); }
        else if (rest.startsWith("#@?#")) { exception = true; body = rest.substring(4); }
        else { droppedCount++; return false; } // #$#, #%#, $$ (AdGuard CSS/JS/HTML rules)
        body = body.trim();
        if (body.isEmpty() || body.startsWith("^")) { droppedCount++; return false; }

        Spec s = new Spec();
        if (body.startsWith("+js(")) {
            if (!body.endsWith(")")) { droppedCount++; return false; }
            String inner = body.substring(4, body.length() - 1).trim();
            if (inner.isEmpty()) {
                if (!exception || domains.isEmpty()) { droppedCount++; return false; }
                s.key = "+js()";
            } else {
                String[] args = splitArgs(inner);
                String name = args[0];
                if (name.endsWith(".js")) name = name.substring(0, name.length() - 3);
                String canon = SCRIPTLET_ALIASES.get(name);
                if (canon == null) { droppedCount++; return false; }
                args[0] = canon;
                s.scriptlet = args;
                s.key = scriptletKey(args);
            }
        } else {
            String sel = body;
            if (sel.endsWith(":remove()")) sel = sel.substring(0, sel.length() - 9);
            int st = sel.lastIndexOf(":style(");
            if (st > 0 && sel.endsWith(")")) {
                s.style = sel.substring(st + 7, sel.length() - 1).trim();
                sel = sel.substring(0, st);
                if (s.style.isEmpty() || s.style.indexOf('{') >= 0 || s.style.indexOf('}') >= 0
                        || s.style.toLowerCase(Locale.ROOT).contains("url(")) { droppedCount++; return false; }
            }
            if (!validSelector(sel)) { droppedCount++; return false; }
            s.selector = sel;
            s.key = s.style == null ? sel : sel + ":style(" + s.style + ")";
        }

        ArrayList<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        if (!domains.isEmpty()) {
            for (String d : domains.split(",")) {
                d = d.trim().toLowerCase(Locale.ROOT);
                if (d.isEmpty()) continue;
                if (d.startsWith("/")) { droppedCount++; return false; }
                if (d.startsWith("~")) exc.add(d.substring(1)); else inc.add(d);
            }
        }
        if (!exc.isEmpty()) s.exclude = NetworkEngine.hashList(exc.toArray(new String[0]));

        if (exception) {
            if (inc.isEmpty()) {
                if (exc.isEmpty()) globalExceptions.add(s.key);
                return true;
            }
            for (String d : inc) {
                long h = Hash.of(d);
                ArrayList<String> l = exceptions.get(h);
                if (l == null) { l = new ArrayList<>(2); exceptions.put(h, l); }
                l.add(s.key);
            }
            return true;
        }
        if (s.scriptlet != null) scriptletCount++; else cosmeticCount++;
        if (inc.isEmpty()) {
            if (s.scriptlet != null) { genericScriptlets.add(s); return true; }
            if (s.exclude != null || s.style != null) { genericExclBuild.add(s); return true; }
            String tok = lowToken(s.selector);
            if (tok != null) {
                long h = Hash.of(tok);
                ArrayList<String> l = lowBuild.get(h);
                if (l == null) { l = new ArrayList<>(1); lowBuild.put(h, l); }
                l.add(s.selector);
            } else {
                highBuild.add(s.selector);
            }
            return true;
        }
        for (String d : inc) {
            long h = Hash.of(d);
            ArrayList<Spec> l = specific.get(h);
            if (l == null) { l = new ArrayList<>(2); specific.put(h, l); }
            l.add(s);
        }
        return true;
    }

    static String scriptletKey(String[] args) {
        StringBuilder sb = new StringBuilder("+js(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        return sb.append(')').toString();
    }

    /** uBO scriptlet argument splitting: commas separate, "\," escapes, quoted args keep commas. */
    static String[] splitArgs(String s) {
        ArrayList<String> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i <= n) {
            while (i < n && s.charAt(i) == ' ') i++;
            StringBuilder sb = new StringBuilder();
            if (i < n && (s.charAt(i) == '\'' || s.charAt(i) == '"')) {
                char q = s.charAt(i);
                int j = i + 1;
                while (j < n && !(s.charAt(j) == q && s.charAt(j - 1) != '\\')) j++;
                int k = j + 1;
                while (k < n && s.charAt(k) == ' ') k++;
                if (j < n && (k >= n || s.charAt(k) == ',')) {
                    sb.append(s, i + 1, j);
                    out.add(sb.toString().replace("\\" + q, String.valueOf(q)));
                    i = k + 1;
                    if (k >= n) break;
                    continue;
                }
            }
            int j = i;
            while (j < n) {
                char c = s.charAt(j);
                if (c == '\\' && j + 1 < n && s.charAt(j + 1) == ',') { sb.append(','); j += 2; continue; }
                if (c == ',') break;
                sb.append(c);
                j++;
            }
            out.add(sb.toString().trim());
            if (j >= n) break;
            i = j + 1;
        }
        return out.toArray(new String[0]);
    }

    static boolean validSelector(String sel) {
        if (sel.isEmpty() || sel.indexOf('{') >= 0 || sel.indexOf('}') >= 0 || sel.startsWith("+js")) return false;
        String l = sel.toLowerCase(Locale.ROOT);
        for (String p : PROCEDURAL) if (l.contains(p)) return false;
        return true;
    }

    /** ".class" / "#id" token of the first compound selector, or null if the selector is "highly generic". */
    static String lowToken(String sel) {
        if (sel.indexOf(',') >= 0) return null;
        int i = 0, n = sel.length();
        while (i < n && Character.isLetterOrDigit(sel.charAt(i))) i++; // optional tag name
        if (i >= n) return null;
        char c = sel.charAt(i);
        if (c != '.' && c != '#') return null;
        int j = i + 1;
        while (j < n) {
            char d = sel.charAt(j);
            if (Character.isLetterOrDigit(d) || d == '-' || d == '_') j++;
            else break;
        }
        if (j == i + 1) return null;
        if (j < n && sel.charAt(j) == '\\') return null; // escaped identifiers
        return sel.substring(i, j);
    }

    public void finish() {
        final LongObjMap<String[]> m = new LongObjMap<>(lowBuild.size());
        lowBuild.forEachEntry(new LongObjMap.Visitor<ArrayList<String>>() {
            @Override
            public void visit(long key, ArrayList<String> value) {
                ArrayList<String> keep = new ArrayList<>(value.size());
                for (String s : value) if (!globalExceptions.contains(s)) keep.add(s);
                if (!keep.isEmpty()) m.put(key, keep.toArray(new String[0]));
            }
        });
        low = m;
        ArrayList<String> h = new ArrayList<>(highBuild.size());
        for (String s : highBuild) if (!globalExceptions.contains(s)) h.add(s);
        high = h.toArray(new String[0]);
    }

    public String stats() {
        return "cosmetic=" + cosmeticCount + " (lowGenericKeys=" + low.size() + " highGeneric=" + high.length
                + " specificDomains=" + specific.size() + ") scriptlets=" + scriptletCount + " dropped=" + droppedCount;
    }

    /** Selectors and scriptlets for one frame. */
    public static final class Snapshot {
        public final ArrayList<String> hide = new ArrayList<>();
        public final ArrayList<String[]> styles = new ArrayList<>();   // [selector, declarations]
        public final ArrayList<String[]> scriptlets = new ArrayList<>();
        public boolean lowGeneric;
    }

    private HashSet<String> exceptionsFor(HostContext h) {
        HashSet<String> ex = new HashSet<>();
        for (long k : h.suffixHashes) addAll(ex, exceptions.get(k));
        for (long k : h.entityHashes) addAll(ex, exceptions.get(k));
        return ex;
    }

    private static void addAll(HashSet<String> set, ArrayList<String> l) {
        if (l != null) set.addAll(l);
    }

    public Snapshot snapshot(HostContext h, int pageFlags, boolean cosmeticOn, boolean scriptletsOn) {
        Snapshot out = new Snapshot();
        HashSet<String> ex = exceptionsFor(h);
        boolean elemhide = (pageFlags & NetFilter.P_ELEMHIDE) != 0;
        boolean generic = cosmeticOn && !elemhide && (pageFlags & NetFilter.P_GENERICHIDE) == 0;
        boolean spec = cosmeticOn && !elemhide && (pageFlags & NetFilter.P_SPECIFICHIDE) == 0;
        boolean noScriptlets = !scriptletsOn || ex.contains("+js()") || (pageFlags & NetFilter.P_DOCUMENT) != 0;
        HashSet<String> seen = new HashSet<>();
        ArrayList<Spec> specs = new ArrayList<>();
        for (long k : h.suffixHashes) addSpecs(specs, specific.get(k));
        for (long k : h.entityHashes) addSpecs(specs, specific.get(k));
        for (Spec s : specs) emit(out, s, h, ex, seen, spec, !noScriptlets);
        if (!noScriptlets) for (Spec s : genericScriptlets) emit(out, s, h, ex, seen, false, true);
        if (generic) {
            for (Spec s : genericExclBuild) emit(out, s, h, ex, seen, true, false);
            for (String sel : high) if (!ex.contains(sel) && seen.add(sel)) out.hide.add(sel);
            out.lowGeneric = true;
        }
        return out;
    }

    private static void addSpecs(ArrayList<Spec> out, ArrayList<Spec> l) {
        if (l != null) out.addAll(l);
    }

    private static void emit(Snapshot out, Spec s, HostContext h, HashSet<String> ex, HashSet<String> seen,
                             boolean css, boolean js) {
        if (s.exclude != null && h.matchesAny(s.exclude)) return;
        if (ex.contains(s.key) || !seen.add(s.key)) return;
        if (s.scriptlet != null) {
            if (js) out.scriptlets.add(s.scriptlet);
        } else if (css) {
            if (s.style != null) out.styles.add(new String[]{s.selector, s.style});
            else out.hide.add(s.selector);
        }
    }

    /** Lowly-generic selectors for the ".class" / "#id" tokens found in a page. */
    public ArrayList<String> lowGeneric(HostContext h, String[] tokens) {
        ArrayList<String> out = new ArrayList<>();
        HashSet<String> ex = null;
        for (String t : tokens) {
            String[] sels = low.get(Hash.of(t));
            if (sels == null) continue;
            if (ex == null) ex = exceptionsFor(h);
            for (String s : sels) if (!ex.contains(s)) out.add(s);
        }
        return out;
    }

    static String[] copy(String[] a) {
        return Arrays.copyOf(a, a.length);
    }
}
