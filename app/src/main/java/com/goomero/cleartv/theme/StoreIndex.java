package com.goomero.cleartv.theme;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The theme store's {@code themes/index.json}, parsed strictly (plain Java, also run by tools/themetest and the
 * repo's index check). Every entry names a theme file and a preview image next to the index, with their sizes and
 * SHA-256 hashes; the app refuses a download that does not match them, then validates the theme like any import.
 *
 * <pre>
 * {"format": "cleartv-theme-index", "version": 1, "themes": [
 *   {"id": "candy", "name": "Candy", "author": "@handle", "version": 1, "description": "...",
 *    "file": "candy.json", "size": 5755, "sha256": "...",
 *    "preview": "previews/candy.png", "previewSize": 12345, "previewSha256": "..."}]}
 * </pre>
 */
public final class StoreIndex {
    public static final int MAX_BYTES = 64 * 1024, MAX_THEMES = 100, MAX_PREVIEW = 256 * 1024;

    private StoreIndex() {}

    public static final class Entry {
        public String id, name, author, description, file, sha256, preview, previewSha256;
        public int version, size, previewSize;
    }

    /** Parses and checks an index; throws {@link Theme.Invalid} listing every problem with its path. */
    public static List<Entry> parse(String json) throws Theme.Invalid {
        List<Theme.Problem> p = new ArrayList<>();
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw Theme.problem("", "too_large", "The index is larger than " + MAX_BYTES / 1024 + " KB.");
        Object root;
        try {
            root = new Theme.Json(json, 4, 512).document();
        } catch (IllegalArgumentException e) {
            throw Theme.problem("", "bad_json", "Not valid JSON: " + e.getMessage());
        }
        if (!(root instanceof Map)) throw Theme.problem("", "type", "The index must be a JSON object.");
        Map<?, ?> m = (Map<?, ?>) root;
        for (Object k : m.keySet()) if (!k.equals("format") && !k.equals("version") && !k.equals("themes")) p.add(new Theme.Problem(String.valueOf(k), "unknown_key", "Unknown key."));
        if (!"cleartv-theme-index".equals(m.get("format"))) p.add(new Theme.Problem("format", "value", "\"format\" must be \"cleartv-theme-index\"."));
        if (!Double.valueOf(1).equals(m.get("version"))) p.add(new Theme.Problem("version", "unsupported", "\"version\" must be 1."));
        Object list = m.get("themes");
        List<Entry> out = new ArrayList<>();
        if (!(list instanceof List)) {
            p.add(new Theme.Problem("themes", "type", "\"themes\" must be an array."));
        } else if (((List<?>) list).size() > MAX_THEMES) {
            p.add(new Theme.Problem("themes", "too_many", "At most " + MAX_THEMES + " themes."));
        } else {
            List<String> ids = new ArrayList<>();
            int i = 0;
            for (Object o : (List<?>) list) {
                String at = "themes[" + i++ + "]";
                if (!(o instanceof Map)) {
                    p.add(new Theme.Problem(at, "type", "Each theme must be an object."));
                    continue;
                }
                Entry e = entry((Map<?, ?>) o, at, p);
                if (e == null) continue;
                if (ids.contains(e.id)) p.add(new Theme.Problem(at + ".id", "duplicate", "Duplicate id \"" + e.id + "\"."));
                ids.add(e.id);
                out.add(e);
            }
        }
        if (!p.isEmpty()) throw new Theme.Invalid(p);
        return out;
    }

    private static Entry entry(Map<?, ?> m, String at, List<Theme.Problem> p) {
        int before = p.size();
        String[] keys = {"id", "name", "author", "version", "description", "file", "size", "sha256", "preview", "previewSize", "previewSha256"};
        for (Object k : m.keySet()) {
            boolean ok = false;
            for (String x : keys) ok |= x.equals(k);
            if (!ok) p.add(new Theme.Problem(at + "." + k, "unknown_key", "Unknown key."));
        }
        Entry e = new Entry();
        e.id = str(m, "id", at, p);
        if (e.id != null && !e.id.matches("[a-z0-9][a-z0-9-]{0,31}")) p.add(new Theme.Problem(at + ".id", "pattern", "Lowercase letters, digits and dashes, 1-32 characters."));
        e.name = label(m, "name", 40, at, p, true);
        e.author = label(m, "author", 40, at, p, true);
        e.description = label(m, "description", 160, at, p, false);
        e.version = num(m, "version", 1, 1_000_000, at, p);
        e.file = path(m, "file", ".json", at, p);
        e.size = num(m, "size", 1, Theme.MAX_BYTES, at, p);
        e.sha256 = hash(m, "sha256", at, p);
        e.preview = path(m, "preview", null, at, p);
        if (e.preview != null && !e.preview.matches(".*\\.(png|jpg|jpeg|webp)")) p.add(new Theme.Problem(at + ".preview", "type", "The preview must be a .png, .jpg or .webp image."));
        e.previewSize = num(m, "previewSize", 1, MAX_PREVIEW, at, p);
        e.previewSha256 = hash(m, "previewSha256", at, p);
        return p.size() == before ? e : null;
    }

    private static String str(Map<?, ?> m, String k, String at, List<Theme.Problem> p) {
        Object v = m.get(k);
        if (v instanceof String) return (String) v;
        p.add(new Theme.Problem(at + "." + k, v == null ? "required" : "type", "\"" + k + "\" must be a string."));
        return null;
    }

    private static String label(Map<?, ?> m, String k, int max, String at, List<Theme.Problem> p, boolean required) {
        if (!required && !m.containsKey(k)) return "";
        String s = str(m, k, at, p);
        if (s != null && !Theme.safeLabel(s, max)) p.add(new Theme.Problem(at + "." + k, "value", "1-" + max + " characters of plain text."));
        return s == null ? "" : s.trim();
    }

    private static int num(Map<?, ?> m, String k, int min, int max, String at, List<Theme.Problem> p) {
        Object v = m.get(k);
        if (!(v instanceof Double) || (Double) v != Math.floor((Double) v) || (Double) v < min || (Double) v > max) {
            p.add(new Theme.Problem(at + "." + k, v == null ? "required" : "range", "\"" + k + "\" must be a whole number from " + min + " to " + max + "."));
            return 0;
        }
        return ((Double) v).intValue();
    }

    private static String hash(Map<?, ?> m, String k, String at, List<Theme.Problem> p) {
        String s = str(m, k, at, p);
        if (s != null && !s.matches("[0-9a-f]{64}")) p.add(new Theme.Problem(at + "." + k, "pattern", "64 lowercase hex characters (SHA-256)."));
        return s;
    }

    /** A file next to the index: letters, digits, '.', '_', '-' and '/', no "..", no leading '/'. */
    private static String path(Map<?, ?> m, String k, String ext, String at, List<Theme.Problem> p) {
        String s = str(m, k, at, p);
        if (s == null) return null;
        if (!s.matches("[A-Za-z0-9._/-]{1,120}") || s.startsWith("/") || s.contains("..") || s.contains("//") || (ext != null && !s.endsWith(ext)))
            p.add(new Theme.Problem(at + "." + k, "pattern", "A relative path next to index.json" + (ext != null ? " ending in " + ext : "") + " (no \"..\")."));
        return s;
    }
}
