import com.goomero.cleartv.theme.Theme;
import com.goomero.cleartv.theme.ThemeApi;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the phone page's theme API logic (ThemeApi, the same class the app uses) over an in-memory backend, the way an
 * outside agent would use it: fetch the schema, validate each example theme in docs/themes/, apply it with
 * auto-revert, confirm. Also checks every error path answers structured JSON.
 */
public final class ApiTest {
    private static int runs, fails;
    static Theme active = Theme.defaults(), pending;
    static int left;
    static final List<Theme> saved = new ArrayList<>();
    static long clock = 10_000;
    static String schemaText;

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        schemaText = read(new File(root, "docs/theme.schema.json"));
        final List<Theme> builtIns = new ArrayList<>();
        for (String id : new String[]{"default", "topshelf", "billboard"})
            builtIns.add(Theme.parse(read(new File(root, "app/src/main/assets/themes/" + id + ".json"))));
        ThemeApi api = new ThemeApi(new ThemeApi.Backend() {
            public Theme active() { return pending != null ? pending : active; }
            public String activeId() { return active.id; }
            public Theme previewing() { return pending; }
            public int previewSecondsLeft() { return pending == null ? 0 : left; }
            public List<Theme> builtIns() { return builtIns; }
            public List<Theme> customs() { return saved; }
            public Theme find(String id) {
                for (Theme t : builtIns) if (t.id.equals(id)) return t;
                for (Theme t : saved) if (t.id.equals(id)) return t;
                return null;
            }
            public String schema() { return schemaText; }
            public void preview(Theme t, int s) { pending = t; left = s; }
            public void applyNow(Theme t) { keep(t); pending = null; }
            public boolean confirm() { if (pending == null) return false; keep(pending); pending = null; return true; }
            public boolean revert() { if (pending == null) return false; pending = null; return true; }
            public void reset() { pending = null; active = builtIns.get(0); }
            public long now() { return clock += 1500; }
            void keep(Theme t) {
                saved.removeIf(x -> x.id.equals(t.id));
                if (!t.id.equals("default")) saved.add(t);
                active = t;
            }
        });
        String J = "application/json";

        // 1. discovery
        ThemeApi.Response r = api.handle("GET", "api/schema", null, "");
        check("schema 200", r.status == 200 && r.body.equals(schemaText));
        r = api.handle("GET", "api/themes", null, "");
        check("themes lists built-ins", r.status == 200 && r.body.contains("\"id\":\"billboard\""));
        r = api.handle("GET", "api/theme", null, "");
        check("current theme", r.status == 200 && r.body.contains("\"active\":\"default\"") && r.body.contains("\"preview\":null"));

        // 2. every example theme: validate, preview with auto-revert, confirm
        File[] ex = new File(root, "docs/themes").listFiles((d, n) -> n.endsWith(".json"));
        check("examples present", ex != null && ex.length >= 3);
        for (File f : ex) {
            String json = read(f);
            r = api.handle("POST", "api/validate", J, json);
            check(f.getName() + " validates", r.status == 200 && r.body.contains("\"valid\":true"));
            if (r.status != 200) System.out.println("   " + r.body);
            check(f.getName() + " has no design warnings", r.body.contains("\"warnings\":[]"));
            Theme t = Theme.parse(json);
            r = api.handle("POST", "api/apply", J, "{\"theme\":" + json + ",\"revertAfter\":20}");
            check(f.getName() + " preview", r.status == 200 && pending != null && pending.id.equals(t.id) && left == 20);
            r = api.handle("GET", "api/theme", null, "");
            check(f.getName() + " preview reported", r.body.contains("\"secondsLeft\":20"));
            r = api.handle("POST", "api/confirm", J, "{}");
            check(f.getName() + " confirmed", r.status == 200 && active.id.equals(t.id) && pending == null);
            r = api.handle("GET", "api/export", null, "");
            check(f.getName() + " export round trip", r.download && Theme.parse(r.body).toJson().equals(t.toJson()));
        }
        // apply by id, revert, reset
        r = api.handle("POST", "api/apply", J, "{\"id\":\"billboard\"}");
        check("apply built-in by id", r.status == 200 && pending.id.equals("billboard") && left == 30);
        r = api.handle("POST", "api/revert", J, "{}");
        check("revert", r.status == 200 && pending == null);
        r = api.handle("POST", "api/apply", J, "{\"id\":\"topshelf\",\"confirm\":true}");
        check("apply with confirm", r.status == 200 && active.id.equals("topshelf") && pending == null);
        r = api.handle("POST", "api/reset", J, "{}");
        check("reset", r.status == 200 && active.id.equals("default"));

        // 3. errors are structured JSON with precise paths
        err(api.handle("POST", "api/validate", J, "{\"format\":\"cleartv-theme\",\"version\":1,\"id\":\"x\",\"name\":\"X\",\"colors\":{\"text\":\"#121212\"}}"), 422, "colors.text");
        err(api.handle("POST", "api/validate", "application/x-www-form-urlencoded", "a=b"), 415, "content_type");
        err(api.handle("GET", "api/apply", null, ""), 405, "method");
        err(api.handle("POST", "api/schema", J, "{}"), 405, "method");
        err(api.handle("GET", "api/../../etc/passwd", null, ""), 404, "not_found");
        err(api.handle("POST", "api/apply", J, "{\"id\":\"nope\"}"), 404, "unknown_theme");
        err(api.handle("POST", "api/apply", J, "{\"id\":\"billboard\",\"revertAfter\":2}"), 400, "bad_request");
        err(api.handle("POST", "api/apply", J, "{\"id\":\"billboard\",\"revertAfter\":10,\"confirm\":true}"), 400, "bad_request");
        err(api.handle("POST", "api/apply", J, "{\"id\":\"billboard\",\"theme\":{}}"), 400, "bad_request");
        err(api.handle("POST", "api/apply", J, "{\"id\":\"billboard\",\"exec\":\"rm -rf\"}"), 400, "unknown_key");
        err(api.handle("POST", "api/apply", J, "{"), 400, "bad_json");
        err(api.handle("POST", "api/confirm", J, "{}"), 409, "no_preview");
        err(api.handle("POST", "api/apply", J, "{\"theme\":{\"format\":\"cleartv-theme\",\"version\":1,\"id\":\"billboard\",\"name\":\"Fake\"}}"), 422, "reserved");
        StringBuilder big = new StringBuilder("{\"id\":\"");
        while (big.length() < ThemeApi.MAX_BODY + 5) big.append('a');
        err(api.handle("POST", "api/validate", J, big.append("\"}").toString()), 413, "too_large");
        clock -= 1400; // two applies inside a second
        api.handle("POST", "api/apply", J, "{\"id\":\"billboard\"}");
        clock -= 1400;
        err(api.handle("POST", "api/apply", J, "{\"id\":\"topshelf\"}"), 429, "too_fast");

        System.out.println("api tests: " + (runs - fails) + "/" + runs + " passed");
        if (fails > 0) System.exit(1);
    }

    private static void err(ThemeApi.Response r, int status, String needle) {
        boolean ok = r.status == status && r.body.startsWith("{\"ok\":false,\"error\":{\"code\":") && r.body.contains(needle);
        check("error " + status + " " + needle, ok);
        if (!ok) System.out.println("   got " + r.status + " " + r.body);
        // the body is strict JSON
        new Theme.Json(r.body, 8, 4096).document();
    }

    private static void check(String n, boolean ok) {
        runs++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + n);
        }
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
