import com.goomero.cleartv.theme.Layout;
import com.goomero.cleartv.theme.Theme;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** JVM tests for the theme parser and validator, the built-in themes, docs examples and the JSON Schema. */
public final class ThemeTest {
    private static int fails, runs;

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        File themes = new File(root, "app/src/main/assets/themes"), store = new File(root, "themes");
        // every built-in and store theme parses, has its file name as id, and the Default one equals the code defaults
        String[] builtIns = {"default", "topshelf", "billboard", "daylight", "oled", "candy"};
        for (String id : builtIns) {
            File tf = new File(themes, id + ".json").exists() ? new File(themes, id + ".json") : new File(store, id + ".json");
            Theme t = Theme.parse(read(tf));
            check(id + " id", t.id.equals(id));
            check(id + " round trip", Theme.parse(t.toJson()).toJson().equals(t.toJson()));
            if (!id.equals("default")) check(id + " is a v2 layout", t.version == 2 && t.ui != null && t.ui.home != null);
            else check("default stays the classic home", t.version == 1 && t.engine.equals("classic"));
            check(id + " has no design warnings", t.ui == null || t.ui.warnings.isEmpty());
            if (t.ui != null && !t.ui.warnings.isEmpty()) for (Theme.Problem w : t.ui.warnings) System.out.println("   " + w.toJson());
            check(id + " fits the cap", read(tf).length() < Theme.MAX_BYTES);
        }
        Theme dt = Theme.parse(read(new File(themes, "default.json")));
        dt.ui = null;
        dt.version = 1;
        check("default tokens equal code defaults", dt.toJson().equals(Theme.defaults().toJson()));
        check("topshelf service screens use tabs", Theme.parse(read(new File(themes, "topshelf.json"))).engine.equals("apple"));
        check("daylight service screens use the sidebar", Theme.parse(read(new File(store, "daylight.json"))).engine.equals("rail"));
        check("daylight is light", Theme.parse(read(new File(store, "daylight.json"))).light());

        String min = "{\"format\":\"cleartv-theme\",\"version\":1,\"id\":\"x\",\"name\":\"X\"}";
        Theme m = Theme.parse(min);
        check("minimal inherits defaults", m.background == Theme.defaults().background && m.radius == 6);

        bad("not json", "{", "bad_json");
        bad("trailing comma", "{\"format\":\"cleartv-theme\",}", "bad_json");
        bad("comment", "{/* x */}", "bad_json");
        bad("duplicate key", "{\"id\":\"a\",\"id\":\"b\"}", "bad_json");
        bad("array root", "[]", "type");
        bad("wrong format", min.replace("cleartv-theme", "other"), "required");
        bad("wrong version", min.replace("\"version\":1", "\"version\":3"), "unsupported");
        bad("v2 without screens", min.replace("\"version\":1", "\"version\":2"), "required");
        bad("v1 with screens", min.replace("}", ",\"screens\":{}}"), "unsupported");
        bad("bad id", min.replace("\"id\":\"x\"", "\"id\":\"../etc\""), "pattern");
        bad("markup name", min.replace("\"name\":\"X\"", "\"name\":\"<script>\""), "pattern");
        bad("unknown key", min.replace("}", ",\"script\":\"alert(1)\"}"), "unknown_key");
        bad("url field", min.replace("}", ",\"colors\":{\"background\":\"url(http://x)\"}}"), "color");
        bad("alpha colour", min.replace("}", ",\"colors\":{\"background\":\"#00000000\"}}"), "color");
        bad("named colour", min.replace("}", ",\"colors\":{\"accent\":\"red\"}}"), "color");
        bad("low contrast", min.replace("}", ",\"colors\":{\"text\":\"#111111\"}}"), "contrast");
        bad("accent invisible", min.replace("}", ",\"colors\":{\"accent\":\"#0C0E10\"}}"), "contrast");
        bad("font outside list", min.replace("}", ",\"font\":{\"display\":\"http://evil/font.ttf\"}}"), "enum");
        bad("radius range", min.replace("}", ",\"shape\":{\"radius\":99}}"), "range");
        bad("radius fraction", min.replace("}", ",\"shape\":{\"radius\":4.5}}"), "type");
        bad("tile size range", min.replace("}", ",\"tiles\":{\"size\":3}}"), "range");
        bad("rows without services", min.replace("}", ",\"layout\":{\"homeRows\":[\"continue\"]}}"), "required");
        bad("rows duplicate", min.replace("}", ",\"layout\":{\"homeRows\":[\"services\",\"services\"]}}"), "enum");
        StringBuilder deep = new StringBuilder();
        for (int k = 0; k < 40; k++) deep.append("{\"a\":");
        deep.append("1");
        for (int k = 0; k < 40; k++) deep.append("}");
        bad("deep nesting", deep.toString(), "bad_json");
        StringBuilder big = new StringBuilder(min.substring(0, min.length() - 1)).append(",\"author\":\"");
        while (big.length() < Theme.MAX_BYTES + 10) big.append('a');
        bad("size cap", big.append("\"}").toString(), "too_large");
        try {
            Theme.parse(min.replace("}", ",\"colors\":{\"text\":\"#111111\",\"accent\":\"nope\"},\"shape\":{\"radius\":-1}}"));
            check("collects all problems", false);
        } catch (Theme.Invalid e) {
            check("collects all problems", e.problems.size() >= 2 && e.problemsJson().contains("\"path\":\"shape.radius\""));
        }
        check("quote escapes markup", Theme.quote("<b>&").equals("\"\\u003cb\\u003e\\u0026\""));

        // docs example parses
        String doc = read(new File(root, "docs/THEMES.md"));
        int s = doc.indexOf("```json"), e = doc.indexOf("```", s + 7);
        check("THEMES.md has an example", s >= 0 && e > s);
        if (s >= 0 && e > s) Theme.parse(doc.substring(s + 7, e));

        // the JSON Schema is valid JSON, documents every field, and its enums match the parser's lists
        Map<?, ?> schema = (Map<?, ?>) new Theme.Json(read(new File(root, "docs/theme.schema.json")), 16, 4096).document();
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        for (String k : new String[]{"format", "version", "id", "name", "author", "colors", "font", "shape", "tiles", "layout", "assets", "screens"})
            check("schema has " + k, props.containsKey(k) && ((Map<?, ?>) props.get(k)).containsKey("description"));
        descriptions("", schema);
        check("schema colours", ((Map<?, ?>) sub(props, "colors").get("properties")).keySet().containsAll(Theme.COLOR_KEYS));
        check("schema display fonts", sub(sub(sub(props, "font"), "properties"), "display").get("enum").equals(Theme.DISPLAY_FONTS));
        check("schema body fonts", sub(sub(sub(props, "font"), "properties"), "body").get("enum").equals(Theme.BODY_FONTS));
        check("schema headers", sub(sub(sub(props, "layout"), "properties"), "header").get("enum").equals(Theme.HEADERS));
        check("schema backdrops", sub(sub(sub(props, "layout"), "properties"), "backdrop").get("enum").equals(Theme.BACKDROPS));
        check("schema engines", sub(sub(sub(props, "layout"), "properties"), "engine").get("enum").equals(Theme.ENGINES));
        bad("poster removed", "{\"format\":\"cleartv-theme\",\"version\":1,\"id\":\"x\",\"name\":\"X\",\"tiles\":{\"style\":\"poster\"}}", "unknown_key");
        bad("unknown engine", "{\"format\":\"cleartv-theme\",\"version\":1,\"id\":\"x\",\"name\":\"X\",\"layout\":{\"engine\":\"netflix\"}}", "enum");

        // v2 layout documents
        Map<?, ?> defs = (Map<?, ?>) schema.get("$defs");
        check("schema component types", sub(sub(sub(defs, "component"), "properties"), "type").get("enum").equals(Layout.TYPES));
        check("schema data bindings", sub(sub(sub(defs, "component"), "properties"), "data").get("enum").equals(Layout.DATA));
        check("schema colour tokens", sub(defs, "colorRef").get("description") instanceof String);
        descriptions("$defs.component.", sub(defs, "component"));
        String ok = v2("{\"type\":\"stack\",\"children\":[{\"type\":\"backdrop\"},{\"type\":\"nav\",\"id\":\"n\"}]}", "n");
        Theme v = Theme.parse(ok);
        check("v2 minimal parses", v.ui != null && v.ui.nodes().size() == 3);
        check("v2 round trip", Theme.parse(v.toJson()).toJson().equals(v.toJson()));
        bad("two backdrops", v2("{\"type\":\"overlay\",\"children\":[{\"type\":\"backdrop\"},{\"type\":\"backdrop\"},{\"type\":\"nav\",\"id\":\"n\"}]}", "n"), "one_backdrop");
        bad("poster card", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"row\",\"data\":\"continue\",\"card\":{\"poster\":true}}]}", "n"), "unsupported");
        bad("portrait aspect", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"row\",\"data\":\"continue\",\"card\":{\"aspect\":\"2:3\"}}]}", "n"), "enum");
        bad("unknown component", v2("{\"type\":\"webview\",\"url\":\"https://x.y/\"}", "n"), "enum");
        bad("script key", v2("{\"type\":\"nav\",\"id\":\"n\",\"onClick\":\"alert(1)\"}", "n"), "unknown_key");
        bad("themes unreachable", v2("{\"type\":\"nav\",\"id\":\"n\",\"items\":[\"services\",\"search\",\"settings\"]}", "n"), "unreachable");
        bad("focus target missing", v2("{\"type\":\"nav\",\"id\":\"n\",\"focus\":{\"up\":\"nope\"}}", "n"), "focus_target");
        bad("default focus missing", v2("{\"type\":\"nav\",\"id\":\"n\"}", "zz"), "focus_target");
        bad("duplicate id", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"spacer\",\"id\":\"n\"}]}", "n"), "duplicate_id");
        bad("children on a row", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"row\",\"data\":\"continue\",\"children\":[]}]}", "n"), "unknown_key");
        bad("duplicate navigation", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"sidebar\",\"id\":\"n\"},{\"type\":\"grid\",\"data\":\"services\"}]}", "n"), "duplicate_nav");
        bad("duplicate button", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"button\",\"action\":\"settings\"}]}", "n"), "duplicate_nav");
        warn("gap too small for the focus growth", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"row\",\"data\":\"continue\",\"gap\":4,\"card\":{\"width\":300},\"effect\":{\"scale\":1.2}}]}", "n"), "warn_gap");
        warn("radius the ring cannot follow", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"row\",\"data\":\"continue\",\"card\":{\"width\":100,\"radius\":40}}]}", "n"), "warn_radius");
        warn("slow backdrop fade", v2("{\"type\":\"overlay\",\"children\":[{\"type\":\"backdrop\",\"fade\":500},{\"type\":\"nav\",\"id\":\"n\"}]}", "n"), "warn_slow");
        warn("unreadable focused backdrop", v2("{\"type\":\"overlay\",\"children\":[{\"type\":\"backdrop\",\"scrim\":{\"bottom\":0,\"left\":0}},{\"type\":\"nav\",\"id\":\"n\"}]}", "n"), "warn_readability");
        bad("row without data", v2("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"},{\"type\":\"row\"}]}", "n"), "required");
        bad("bad size", v2("{\"type\":\"nav\",\"id\":\"n\",\"width\":\"100%\"}", "n"), "size");
        bad("bad colour ref", v2("{\"type\":\"nav\",\"id\":\"n\",\"background\":\"url(x)\"}", "n"), "color_ref");
        bad("http asset", v2a("{\"bg\":{\"url\":\"http://example.com/a.png\"}}"), "pattern");
        bad("asset not an image", v2a("{\"bg\":{\"url\":\"https://example.com/a.js\"}}"), "pattern");
        bad("asset credentials", v2a("{\"bg\":{\"url\":\"https://u:p@example.com/a.png\"}}"), "pattern");
        bad("asset too big", v2a("{\"bg\":{\"url\":\"https://example.com/a.png\",\"maxKb\":4096}}"), "range");
        bad("v2 engine key", ok.replace("\"screens\"", "\"layout\":{\"engine\":\"apple\"},\"screens\""), "unsupported");
        Theme.parse(v2a("{\"bg\":{\"url\":\"https://example.com/a.webp\",\"maxKb\":256}}"));
        check("https asset accepted", true);
        StringBuilder many = new StringBuilder("{\"type\":\"stack\",\"children\":[{\"type\":\"nav\",\"id\":\"n\"}");
        for (int g = 0; g < 4; g++) {
            many.append(",{\"type\":\"stack\",\"children\":[{\"type\":\"spacer\"}");
            for (int k = 0; k < 50; k++) many.append(",{\"type\":\"spacer\"}");
            many.append("]}");
        }
        bad("too many components", v2(many.append("]}").toString(), "n"), "too_many");
        String nest = "{\"type\":\"nav\",\"id\":\"n\"}";
        for (int k = 0; k < Layout.MAX_DEPTH + 1; k++) nest = "{\"type\":\"stack\",\"children\":[" + nest + "]}";
        bad("components too deep", v2(nest, "n"), "too_deep");

        System.out.println("theme tests: " + (runs - fails) + "/" + runs + " passed");
        if (fails > 0) System.exit(1);
    }

    private static String v2(String root, String focus) {
        return "{\"format\":\"cleartv-theme\",\"version\":2,\"id\":\"x\",\"name\":\"X\",\"screens\":{\"home\":{\"defaultFocus\":\"" + focus + "\",\"root\":" + root + "}}}";
    }

    private static String v2a(String assets) {
        return "{\"format\":\"cleartv-theme\",\"version\":2,\"id\":\"x\",\"name\":\"X\",\"assets\":" + assets
                + ",\"screens\":{\"home\":{\"defaultFocus\":\"n\",\"root\":{\"type\":\"overlay\",\"children\":[{\"type\":\"backdrop\",\"source\":\"asset\",\"asset\":\"bg\"},{\"type\":\"nav\",\"id\":\"n\"}]}}}}";
    }

    private static Map<?, ?> sub(Map<?, ?> m, String k) {
        return (Map<?, ?>) m.get(k);
    }

    /** Every property in the schema carries a description. */
    private static void descriptions(String path, Map<?, ?> node) {
        Object p = node.get("properties");
        if (p instanceof Map) {
            for (Map.Entry<?, ?> en : ((Map<?, ?>) p).entrySet()) {
                Map<?, ?> child = (Map<?, ?>) en.getValue();
                check("schema description " + path + en.getKey(), child.get("description") instanceof String);
                descriptions(path + en.getKey() + ".", child);
            }
        }
    }

    private static void warn(String name, String json, String code) {
        try {
            Theme t = Theme.parse(json);
            boolean hit = false;
            for (Theme.Problem p : t.ui.warnings) hit |= p.code.equals(code);
            check(name + " warns (" + code + ")", hit);
        } catch (Theme.Invalid e) {
            check(name + " parses with a warning", false);
            System.out.println("   " + e.problemsJson());
        }
    }

    private static void bad(String name, String json, String code) {
        try {
            Theme.parse(json);
            check(name + " rejected", false);
        } catch (Theme.Invalid e) {
            boolean hit = false;
            for (Theme.Problem p : e.problems) hit |= p.code.equals(code);
            check(name + " rejected (" + code + ")", hit);
            if (!hit) System.out.println("   got " + e.problemsJson());
        }
    }

    private static void check(String name, boolean ok) {
        runs++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
