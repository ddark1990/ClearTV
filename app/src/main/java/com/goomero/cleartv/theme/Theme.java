package com.goomero.cleartv.theme;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One ClearTV theme: colours, type, shape, tile and home-layout choices, parsed from the documented JSON format
 * (docs/THEMES.md, docs/theme.schema.json). Pure Java (no Android classes) so the parser and validator are tested on
 * the JVM (tools/themetest).
 *
 * <p>Validation is strict and data-only: a size cap, a small strict JSON parser (no comments, no trailing commas,
 * bounded depth), no unknown keys, enumerated font and layout names, opaque {@code #RRGGBB} colours only, number ranges
 * and minimum contrast between text, accent and background. Nothing in a theme is ever loaded, fetched or executed:
 * there are no URLs, file paths, scripts or images in the format.
 */
public final class Theme {
    public static final String FORMAT = "cleartv-theme";
    public static final int VERSION = 2;
    /** Largest accepted theme document, in UTF-8 bytes. */
    public static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 28;
    /** Timed preview length in seconds: default, shortest and longest. */
    public static final int PREVIEW_DEFAULT_S = 30, PREVIEW_MIN_S = 5, PREVIEW_MAX_S = 300;

    public static final List<String> DISPLAY_FONTS = Collections.unmodifiableList(Arrays.asList(
            "oxanium", "sans", "sans-medium", "sans-light", "condensed", "serif", "mono"));
    public static final List<String> BODY_FONTS = Collections.unmodifiableList(Arrays.asList(
            "sans", "sans-medium", "sans-light", "condensed", "serif", "mono"));
    /** Layout engines: the structure of the home and service screens. */
    public static final List<String> ENGINES = Collections.unmodifiableList(Arrays.asList("classic", "apple", "rail"));
    public static final List<String> HEADERS = Collections.unmodifiableList(Arrays.asList("full", "compact", "minimal"));
    public static final List<String> BACKDROPS = Collections.unmodifiableList(Arrays.asList("stars", "gradient", "plain"));
    public static final List<String> SERVICE_STYLES = Collections.unmodifiableList(Arrays.asList("cards", "hero"));
    public static final List<String> ROWS = Collections.unmodifiableList(Arrays.asList("services", "continue", "watchlist"));
    public static final List<String> COLOR_KEYS = Collections.unmodifiableList(Arrays.asList(
            "background", "backgroundDeep", "panel", "surface", "line", "text", "textMuted", "textSoft", "accent",
            "good", "bad", "warn"));

    // ---------------------------------------------------------------------------------------------- values
    public String id = "default", name = "Stargazer", author = "";
    public int background = 0xFF0B0D0F, backgroundDeep = 0xFF060809, panel = 0xFF15181B, surface = 0xFF22272C,
            line = 0xFF3A3D3F, text = 0xFFF3F5F7, textMuted = 0xFFB8BBBD, textSoft = 0xFF858B90, accent = 0xFFCBD1D8,
            good = 0xFF8FBCA2, bad = 0xFFC57474, warn = 0xFFD6B36A;
    public String displayFont = "oxanium", bodyFont = "sans";
    public float fontScale = 1f;
    public int radius = 6, focusRing = 3, cardOpacity = 90;
    public float focusScale = 1.04f;
    public float tileSize = 1f;
    public int rowSpacing = 16;
    public String header = "full", backdrop = "stars", servicesStyle = "cards", engine = "classic";
    public List<String> homeRows = new ArrayList<>(ROWS);
    public boolean serviceAccents = true;
    /** 1: tokens and one of the engines; 2: tokens plus a composable layout document ({@link #ui}). */
    public int version = 1;
    /** The v2 layout document, or null for version 1 themes. */
    public Layout ui;

    /** The built-in look (the app's original graphite and silver design). */
    public static Theme defaults() {
        return new Theme();
    }

    public Theme copy() {
        try {
            return parse(toJson());
        } catch (Invalid e) {
            throw new IllegalStateException(e);
        }
    }

    /** The panel colour at {@link #cardOpacity} over the background: what translucent cards look like. */
    public int cardFill() {
        float a = cardOpacity / 100f;
        int r = Math.round((panel >> 16 & 0xFF) * a + (background >> 16 & 0xFF) * (1 - a));
        int g = Math.round((panel >> 8 & 0xFF) * a + (background >> 8 & 0xFF) * (1 - a));
        int b = Math.round((panel & 0xFF) * a + (background & 0xFF) * (1 - a));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public boolean light() {
        return luminance(background) > 0.4;
    }

    // ---------------------------------------------------------------------------------------------- errors

    /** One precise problem: a JSON path (such as {@code colors.text}), a stable code and a readable message. */
    public static final class Problem {
        public final String path, code, message;

        Problem(String path, String code, String message) {
            this.path = path;
            this.code = code;
            this.message = message;
        }

        public String toJson() {
            return "{\"path\":" + quote(path) + ",\"code\":" + quote(code) + ",\"message\":" + quote(message) + "}";
        }

        @Override
        public String toString() {
            return (path.isEmpty() ? "" : path + ": ") + message;
        }
    }

    /** Thrown with every problem found (not just the first), so a fix needs one round trip. */
    public static final class Invalid extends Exception {
        public final List<Problem> problems;

        Invalid(List<Problem> problems) {
            super(problems.isEmpty() ? "invalid theme" : problems.get(0).toString());
            this.problems = Collections.unmodifiableList(problems);
        }

        public String problemsJson() {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < problems.size(); i++) b.append(i == 0 ? "" : ",").append(problems.get(i).toJson());
            return b.append(']').toString();
        }
    }

    /** A single-problem {@link Invalid} (for checks outside the document, such as a reserved id or a bad link). */
    public static Invalid problem(String path, String code, String message) {
        List<Problem> l = new ArrayList<>();
        l.add(new Problem(path, code, message));
        return new Invalid(l);
    }

    // ---------------------------------------------------------------------------------------------- parse

    /** Parses and validates a theme document; throws {@link Invalid} listing every problem. */
    public static Theme parse(String json) throws Invalid {
        List<Problem> p = new ArrayList<>();
        if (json == null) json = "";
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            p.add(new Problem("", "too_large", "The theme is larger than " + MAX_BYTES / 1024 + " KB."));
            throw new Invalid(p);
        }
        Object root;
        try {
            root = new Json(json, MAX_DEPTH, 512).document();
        } catch (IllegalArgumentException e) {
            p.add(new Problem("", "bad_json", "Not valid JSON: " + e.getMessage()));
            throw new Invalid(p);
        }
        if (!(root instanceof Map)) {
            p.add(new Problem("", "type", "The theme must be a JSON object."));
            throw new Invalid(p);
        }
        Theme t = new Theme();
        Map<?, ?> m = (Map<?, ?>) root;
        allowOnly(m, "", p, "format", "version", "id", "name", "author", "colors", "font", "shape", "tiles", "layout", "assets", "screens");
        if (!FORMAT.equals(m.get("format"))) p.add(new Problem("format", "required", "\"format\" must be \"" + FORMAT + "\"."));
        Object v = m.get("version");
        if (!(v instanceof Double) || (((Double) v) != 1 && ((Double) v) != 2)) p.add(new Problem("version", "unsupported", "\"version\" must be 1 or 2."));
        else t.version = ((Double) v).intValue();
        if (t.version == 1 && (m.containsKey("screens") || m.containsKey("assets")))
            p.add(new Problem("version", "unsupported", "\"screens\" and \"assets\" need \"version\": 2."));
        if (t.version == 2) {
            t.ui = Layout.parse(m.get("screens"), m.get("assets"), p);
            t.engine = t.ui.serviceEngine();
            Object lay = m.get("layout");
            if (lay instanceof Map && ((Map<?, ?>) lay).containsKey("engine"))
                p.add(new Problem("layout.engine", "unsupported", "Version 2 themes describe the layout in \"screens\"; the service screens follow \"screens.service.nav\"."));
        }
        String id = str(m, "id", "", p, true);
        if (id != null) {
            if (!id.matches("[a-z0-9][a-z0-9-]{0,31}")) p.add(new Problem("id", "pattern", "\"id\" must be 1-32 characters: lowercase letters, digits and dashes, starting with a letter or digit."));
            else t.id = id;
        }
        String name = str(m, "name", "", p, true);
        if (name != null) {
            if (!safeLabel(name, 40)) p.add(new Problem("name", "pattern", "\"name\" must be 1-40 printable characters."));
            else t.name = name.trim();
        }
        String author = str(m, "author", "", p, false);
        if (author != null) {
            if (author.length() > 0 && !safeLabel(author, 40)) p.add(new Problem("author", "pattern", "\"author\" must be at most 40 printable characters."));
            else t.author = author.trim();
        }

        Map<?, ?> colors = obj(m, "colors", p);
        if (colors != null) {
            allowOnly(colors, "colors.", p, COLOR_KEYS.toArray(new String[0]));
            int[] vals = {t.background, t.backgroundDeep, t.panel, t.surface, t.line, t.text, t.textMuted, t.textSoft, t.accent, t.good, t.bad, t.warn};
            for (int i = 0; i < COLOR_KEYS.size(); i++) {
                String k = COLOR_KEYS.get(i);
                if (!colors.containsKey(k)) continue;
                Object cv = colors.get(k);
                Integer c = cv instanceof String ? color((String) cv) : null;
                if (c == null) p.add(new Problem("colors." + k, "color", "Colours are opaque hex strings like \"#1A2B3C\"."));
                else vals[i] = c;
            }
            t.background = vals[0]; t.backgroundDeep = vals[1]; t.panel = vals[2]; t.surface = vals[3]; t.line = vals[4];
            t.text = vals[5]; t.textMuted = vals[6]; t.textSoft = vals[7]; t.accent = vals[8]; t.good = vals[9];
            t.bad = vals[10]; t.warn = vals[11];
        }
        Map<?, ?> font = obj(m, "font", p);
        if (font != null) {
            allowOnly(font, "font.", p, "display", "body", "scale");
            String d = oneOf(font, "display", "font.", DISPLAY_FONTS, p);
            if (d != null) t.displayFont = d;
            String b = oneOf(font, "body", "font.", BODY_FONTS, p);
            if (b != null) t.bodyFont = b;
            Double s = num(font, "scale", "font.", 0.85, 1.25, p);
            if (s != null) t.fontScale = s.floatValue();
        }
        Map<?, ?> shape = obj(m, "shape", p);
        if (shape != null) {
            allowOnly(shape, "shape.", p, "radius", "focusRing", "focusScale", "cardOpacity");
            Double r = integer(shape, "radius", "shape.", 0, 24, p);
            if (r != null) t.radius = r.intValue();
            Double f = integer(shape, "focusRing", "shape.", 2, 6, p);
            if (f != null) t.focusRing = f.intValue();
            Double fs = num(shape, "focusScale", "shape.", 1.0, 1.10, p);
            if (fs != null) t.focusScale = fs.floatValue();
            Double co = integer(shape, "cardOpacity", "shape.", 60, 100, p);
            if (co != null) t.cardOpacity = co.intValue();
        }
        Map<?, ?> tiles = obj(m, "tiles", p);
        if (tiles != null) {
            allowOnly(tiles, "tiles.", p, "size");
            Double s = num(tiles, "size", "tiles.", 0.8, 1.3, p);
            if (s != null) t.tileSize = s.floatValue();
        }
        Map<?, ?> layout = obj(m, "layout", p);
        if (layout != null) {
            allowOnly(layout, "layout.", p, "engine", "rowSpacing", "header", "backdrop", "homeRows", "serviceAccents", "servicesStyle");
            Double rs = integer(layout, "rowSpacing", "layout.", 8, 48, p);
            if (rs != null) t.rowSpacing = rs.intValue();
            String h = oneOf(layout, "header", "layout.", HEADERS, p);
            if (h != null) t.header = h;
            String bd = oneOf(layout, "backdrop", "layout.", BACKDROPS, p);
            if (bd != null) t.backdrop = bd;
            String en = oneOf(layout, "engine", "layout.", ENGINES, p);
            if (en != null) t.engine = en;
            String ss = oneOf(layout, "servicesStyle", "layout.", SERVICE_STYLES, p);
            if (ss != null) t.servicesStyle = ss;
            if (layout.containsKey("serviceAccents")) {
                Object sa = layout.get("serviceAccents");
                if (sa instanceof Boolean) t.serviceAccents = (Boolean) sa;
                else p.add(new Problem("layout.serviceAccents", "type", "\"serviceAccents\" must be true or false."));
            }
            if (layout.containsKey("homeRows")) {
                Object hr = layout.get("homeRows");
                List<String> rows = new ArrayList<>();
                boolean ok = hr instanceof List && !((List<?>) hr).isEmpty() && ((List<?>) hr).size() <= ROWS.size();
                if (ok) {
                    for (Object o : (List<?>) hr) {
                        if (!(o instanceof String) || !ROWS.contains(o) || rows.contains(o)) {
                            ok = false;
                            break;
                        }
                        rows.add((String) o);
                    }
                }
                if (!ok) p.add(new Problem("layout.homeRows", "enum", "\"homeRows\" lists each of " + ROWS + " at most once, in the order to show them."));
                else if (!rows.contains("services")) p.add(new Problem("layout.homeRows", "required", "\"homeRows\" must include \"services\" (the service tiles open everything)."));
                else t.homeRows = rows;
            }
        }
        // readable colours: checked on the merged theme so partial themes are judged with the defaults they inherit
        if (p.isEmpty()) contrast(t, p);
        if (p.isEmpty() && t.ui != null) t.ui.lint(t);
        if (!p.isEmpty()) throw new Invalid(p);
        return t;
    }

    private static void contrast(Theme t, List<Problem> p) {
        need(t.text, t.background, 4.5, "colors.text", "text on background", p);
        need(t.text, t.panel, 4.5, "colors.text", "text on panel", p);
        need(t.text, t.cardFill(), 4.5, "colors.text", "text on translucent cards (panel at shape.cardOpacity over background)", p);
        need(t.text, t.surface, 4.5, "colors.text", "text on surface (focused rows)", p);
        need(t.textMuted, t.background, 4.5, "colors.textMuted", "muted text on background", p);
        need(t.textMuted, t.panel, 3.0, "colors.textMuted", "muted text on panel", p);
        need(t.textSoft, t.background, 3.0, "colors.textSoft", "soft text on background", p);
        need(t.accent, t.background, 3.0, "colors.accent", "accent (focus ring) on background", p);
        need(t.accent, t.surface, 2.0, "colors.accent", "accent (focus ring) on surface", p);
    }

    private static void need(int fg, int bg, double min, String path, String what, List<Problem> p) {
        double r = contrastRatio(fg, bg);
        if (r + 1e-9 < min) p.add(new Problem(path, "contrast", String.format(Locale.ROOT,
                "Contrast of %s is %.2f:1; at least %.1f:1 is needed so it reads across a room.", what, r, min)));
    }

    public static double luminance(int c) {
        double[] ch = {(c >> 16 & 0xFF) / 255.0, (c >> 8 & 0xFF) / 255.0, (c & 0xFF) / 255.0};
        for (int i = 0; i < 3; i++) ch[i] = ch[i] <= 0.03928 ? ch[i] / 12.92 : Math.pow((ch[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    }

    public static double contrastRatio(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** {@code #RRGGBB} (case-insensitive) to an opaque ARGB int, else null. Alpha, names and functions are refused. */
    /** An opaque #RRGGBB colour, or null. */
    public static Integer parseColor(String s) {
        return color(s);
    }

    static Integer color(String s) {
        if (s.length() != 7 || s.charAt(0) != '#') return null;
        int v = 0;
        for (int i = 1; i < 7; i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) return null;
            v = v * 16 + d;
        }
        return 0xFF000000 | v;
    }

    static boolean safeLabel(String s, int max) {
        String t = s.trim();
        if (t.isEmpty() || t.length() > max) return false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isISOControl(ch) || ch == '<' || ch == '>' || ch == '\u2028' || ch == '\u2029'
                    || Character.getType(ch) == Character.FORMAT) return false;
        }
        return true;
    }

    private static void allowOnly(Map<?, ?> m, String prefix, List<Problem> p, String... keys) {
        List<String> ok = Arrays.asList(keys);
        for (Object k : m.keySet()) {
            if (!ok.contains(k)) p.add(new Problem(prefix + k, "unknown_key", "Unknown key \"" + k + "\". Allowed here: " + ok + "."));
        }
    }

    private static String str(Map<?, ?> m, String k, String prefix, List<Problem> p, boolean required) {
        if (!m.containsKey(k)) {
            if (required) p.add(new Problem(prefix + k, "required", "\"" + k + "\" is required."));
            return null;
        }
        Object v = m.get(k);
        if (!(v instanceof String)) {
            p.add(new Problem(prefix + k, "type", "\"" + k + "\" must be a string."));
            return null;
        }
        return (String) v;
    }

    private static Map<?, ?> obj(Map<?, ?> m, String k, List<Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (!(v instanceof Map)) {
            p.add(new Problem(k, "type", "\"" + k + "\" must be an object."));
            return null;
        }
        return (Map<?, ?>) v;
    }

    private static String oneOf(Map<?, ?> m, String k, String prefix, List<String> allowed, List<Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (!(v instanceof String) || !allowed.contains(v)) {
            p.add(new Problem(prefix + k, "enum", "\"" + k + "\" must be one of " + allowed + "."));
            return null;
        }
        return (String) v;
    }

    private static Double num(Map<?, ?> m, String k, String prefix, double min, double max, List<Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (!(v instanceof Double) || (Double) v < min || (Double) v > max) {
            p.add(new Problem(prefix + k, "range", String.format(Locale.ROOT, "\"%s\" must be a number from %s to %s.", k, fmt(min), fmt(max))));
            return null;
        }
        return (Double) v;
    }

    private static Double integer(Map<?, ?> m, String k, String prefix, int min, int max, List<Problem> p) {
        Double d = num(m, k, prefix, min, max, p);
        if (d != null && d != Math.rint(d)) {
            p.add(new Problem(prefix + k, "type", "\"" + k + "\" must be a whole number."));
            return null;
        }
        return d;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ---------------------------------------------------------------------------------------------- write

    /** The full theme as pretty-printed JSON in the documented format (every field, so it is a complete export). */
    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"format\": \"").append(FORMAT).append("\",\n  \"version\": ").append(version).append(",\n");
        b.append("  \"id\": ").append(quote(id)).append(",\n  \"name\": ").append(quote(name)).append(",\n");
        if (!author.isEmpty()) b.append("  \"author\": ").append(quote(author)).append(",\n");
        int[] vals = {background, backgroundDeep, panel, surface, line, text, textMuted, textSoft, accent, good, bad, warn};
        b.append("  \"colors\": {\n");
        for (int i = 0; i < vals.length; i++) {
            b.append("    \"").append(COLOR_KEYS.get(i)).append("\": \"").append(hex(vals[i])).append('"')
                    .append(i < vals.length - 1 ? ",\n" : "\n");
        }
        b.append("  },\n");
        b.append("  \"font\": { \"display\": ").append(quote(displayFont)).append(", \"body\": ").append(quote(bodyFont))
                .append(", \"scale\": ").append(num(fontScale)).append(" },\n");
        b.append("  \"shape\": { \"radius\": ").append(radius).append(", \"focusRing\": ").append(focusRing)
                .append(", \"focusScale\": ").append(num(focusScale)).append(", \"cardOpacity\": ").append(cardOpacity).append(" },\n");
        b.append("  \"tiles\": { \"size\": ").append(num(tileSize)).append(" },\n");
        b.append("  \"layout\": {\n    ").append(version == 1 ? "\"engine\": " + quote(engine) + ",\n    " : "").append("\"rowSpacing\": ").append(rowSpacing).append(",\n    \"header\": ").append(quote(header))
                .append(",\n    \"backdrop\": ").append(quote(backdrop)).append(",\n    \"servicesStyle\": ").append(quote(servicesStyle)).append(",\n    \"homeRows\": [");
        for (int i = 0; i < homeRows.size(); i++) b.append(i == 0 ? "" : ", ").append(quote(homeRows.get(i)));
        b.append("],\n    \"serviceAccents\": ").append(serviceAccents).append("\n  }");
        if (ui != null) {
            if (ui.assetsRaw != null) b.append(",\n  \"assets\": ").append(ThemeApi.toJson(ui.assetsRaw));
            b.append(",\n  \"screens\": ").append(ThemeApi.toJson(ui.screensRaw));
        }
        b.append("\n}\n");
        return b.toString();
    }

    public static String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    private static String num(float f) {
        String s = String.format(Locale.ROOT, "%.2f", f);
        while (s.endsWith("0") && !s.endsWith(".0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    // < > & escaped too, so a name can never become markup on the phone page
                    if (ch < 0x20 || ch == '<' || ch == '>' || ch == '&' || ch == '\u2028' || ch == '\u2029')
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    // ---------------------------------------------------------------------------------------------- JSON

    /**
     * A strict RFC 8259 parser for small documents: objects (insertion-ordered, duplicate keys refused), arrays,
     * strings, numbers (as Double), booleans and null, at most {@link #MAX_DEPTH} levels deep.
     */
    public static final class Json {
        private final String s;
        private final int maxDepth, maxString;
        private int i, depth;

        /** Limits for theme documents: {@link #MAX_DEPTH} levels, 512-character strings. */
        public Json(String s) {
            this(s, MAX_DEPTH, 512);
        }

        public Json(String s, int maxDepth, int maxString) {
            this.s = s;
            this.maxDepth = maxDepth;
            this.maxString = maxString;
        }

        public Object document() {
            ws();
            Object v = value();
            ws();
            if (i != s.length()) throw err("unexpected text after the end");
            return v;
        }

        private IllegalArgumentException err(String m) {
            return new IllegalArgumentException(m + " at character " + i);
        }

        private void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
        }

        private Object value() {
            if (i >= s.length()) throw err("unexpected end");
            char ch = s.charAt(i);
            if (ch == '{') return object();
            if (ch == '[') return array();
            if (ch == '"') return string();
            if (ch == '-' || (ch >= '0' && ch <= '9')) return number();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("unexpected character");
        }

        private Map<String, Object> object() {
            if (++depth > maxDepth) throw err("nested too deeply");
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                depth--;
                return m;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("expected a key");
                String k = string();
                if (m.containsKey(k)) throw err("duplicate key \"" + k + "\"");
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                i++;
                ws();
                m.put(k, value());
                if (m.size() > 64) throw err("too many keys");
                ws();
                if (i >= s.length()) throw err("unexpected end");
                char ch = s.charAt(i++);
                if (ch == '}') break;
                if (ch != ',') throw err("expected ',' or '}'");
            }
            depth--;
            return m;
        }

        private List<Object> array() {
            if (++depth > maxDepth) throw err("nested too deeply");
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                depth--;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                if (l.size() > 64) throw err("array too long");
                ws();
                if (i >= s.length()) throw err("unexpected end");
                char ch = s.charAt(i++);
                if (ch == ']') break;
                if (ch != ',') throw err("expected ',' or ']'");
            }
            depth--;
            return l;
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char ch = s.charAt(i++);
                if (ch == '"') return b.toString();
                if (ch < 0x20) throw err("control character in string");
                if (b.length() > maxString) throw err("string too long");
                if (ch != '\\') {
                    b.append(ch);
                    continue;
                }
                if (i >= s.length()) throw err("unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("bad \\u escape");
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i++), 16);
                            if (d < 0) throw err("bad \\u escape");
                            v = v * 16 + d;
                        }
                        b.append((char) v);
                        break;
                    default:
                        throw err("bad escape");
                }
            }
        }

        private Double number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw err("bad number");
            if (s.charAt(i) == '0') i++;
            else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            else throw err("bad number");
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int d = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (i == d) throw err("bad number");
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int d = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (i == d) throw err("bad number");
            }
            if (i - start > 24) throw err("number too long");
            return Double.valueOf(s.substring(start, i));
        }
    }
}
