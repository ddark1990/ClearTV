package com.goomero.cleartv.theme;

import java.util.List;
import java.util.Map;

/**
 * The theme control API behind the phone page ({@code /<token>/api/...}): routing, body checks and JSON answers.
 * Pure Java over a {@link Backend}, so the same logic runs in the app (PhoneLink with {@link Themes}) and in the JVM
 * test harness (tools/themetest). Every answer is JSON; errors are
 * {@code {"ok":false,"error":{"code":..,"message":..},"errors":[{"path","code","message"}...]}}.
 *
 * <p>Security: PhoneLink has already checked the token, LAN caller, Host, Origin and Sec-Fetch-Site before a request
 * gets here. POST bodies must be {@code application/json} (no form posts from other pages) and at most
 * {@link #MAX_BODY} bytes. Themes are data only: nothing is fetched, loaded or executed, and no file is read or
 * written except ClearTV's own theme store.
 */
public final class ThemeApi {
    public static final int MAX_BODY = Theme.MAX_BYTES + 1024;
    /** Shortest gap between two applies (a runaway agent cannot strobe the TV). */
    public static final long APPLY_GAP_MS = 1000;

    /** What the API needs from the app. */
    public interface Backend {
        Theme active();

        String activeId();

        /** The previewed theme, or null. */
        Theme previewing();

        int previewSecondsLeft();

        List<Theme> builtIns();

        List<Theme> customs();

        /** A built-in or saved theme by id, or null. */
        Theme find(String id);

        String schema();

        void preview(Theme t, int seconds);

        /** Saves (custom themes) and applies for good. */
        void applyNow(Theme t) throws Theme.Invalid;

        boolean confirm() throws Theme.Invalid;

        boolean revert();

        void reset();

        long now();
    }

    public static final class Response {
        public final int status;
        public final String type, body;
        public final boolean download;

        Response(int status, String type, String body, boolean download) {
            this.status = status;
            this.type = type;
            this.body = body;
            this.download = download;
        }
    }

    private final Backend b;
    private long lastApply = Long.MIN_VALUE / 2;

    public ThemeApi(Backend b) {
        this.b = b;
    }

    public static boolean handles(String rest) {
        return rest.equals("api") || rest.startsWith("api/");
    }

    /**
     * @param rest        path after {@code /<token>/}, query removed (for example {@code api/theme})
     * @param contentType the request's Content-Type, or null
     */
    public synchronized Response handle(String method, String rest, String contentType, String body) {
        String route = rest.length() > 4 ? rest.substring(4) : "";
        boolean get = method.equals("GET"), post = method.equals("POST");
        switch (route) {
            case "theme":
                if (!get) return notAllowed("GET");
                return ok("\"active\":" + Theme.quote(b.activeId()) + ",\"preview\":" + previewJson() + ",\"theme\":" + b.active().toJson());
            case "schema":
                if (!get) return notAllowed("GET");
                return new Response(200, "application/schema+json", b.schema(), false);
            case "themes":
                if (!get) return notAllowed("GET");
                return ok("\"active\":" + Theme.quote(b.activeId()) + ",\"builtIn\":" + list(b.builtIns()) + ",\"custom\":" + list(b.customs()));
            case "export":
                if (!get) return notAllowed("GET");
                return new Response(200, "application/json", b.active().toJson(), true);
            case "validate":
            case "apply":
            case "confirm":
            case "revert":
            case "reset":
                break;
            default:
                return error(404, "not_found", "No such endpoint. See docs/AGENTS-API.md: GET api/theme, api/schema, api/themes, api/export; POST api/validate, api/apply, api/confirm, api/revert, api/reset.", null);
        }
        if (!post) return notAllowed("POST");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
            return error(415, "content_type", "Send the body as application/json.", null);
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY)
            return error(413, "too_large", "The body is larger than " + MAX_BODY + " bytes.", null);
        try {
            switch (route) {
                case "validate": {
                    Theme t = Theme.parse(body);
                    return ok("\"valid\":true,\"id\":" + Theme.quote(t.id) + ",\"warnings\":" + warnings(t) + ",\"normalized\":" + t.toJson());
                }
                case "apply":
                    return apply(body);
                case "confirm":
                    if (!b.confirm()) return error(409, "no_preview", "There is no preview to confirm.", null);
                    return ok("\"active\":" + Theme.quote(b.activeId()));
                case "revert":
                    if (!b.revert()) return error(409, "no_preview", "There is no preview to revert.", null);
                    return ok("\"active\":" + Theme.quote(b.activeId()));
                default:
                    b.reset();
                    return ok("\"active\":\"default\"");
            }
        } catch (Theme.Invalid e) {
            return error(422, "invalid_theme", e.problems.size() + " problem(s); fix every entry in \"errors\".", e.problemsJson());
        }
    }

    /**
     * Body: {@code {"theme": {...}}} or {@code {"id": "oled"}}, plus optional {@code "revertAfter"} (5-300 s,
     * default 30) or {@code "confirm": true} to apply for good at once.
     */
    private Response apply(String body) throws Theme.Invalid {
        Object root;
        try {
            root = new Theme.Json(body, 30, 512).document();
        } catch (IllegalArgumentException e) {
            return error(400, "bad_json", "Not valid JSON: " + e.getMessage(), null);
        }
        if (!(root instanceof Map)) return error(400, "bad_request", "The body must be a JSON object.", null);
        Map<?, ?> m = (Map<?, ?>) root;
        for (Object k : m.keySet()) {
            if (!k.equals("theme") && !k.equals("id") && !k.equals("revertAfter") && !k.equals("confirm"))
                return error(400, "unknown_key", "Unknown key \"" + k + "\". Allowed: theme, id, revertAfter, confirm.", null);
        }
        if (m.containsKey("theme") == m.containsKey("id"))
            return error(400, "bad_request", "Send exactly one of \"theme\" (a theme object) or \"id\" (a built-in or saved theme).", null);
        Object conf = m.get("confirm");
        if (conf != null && !(conf instanceof Boolean)) return error(400, "bad_request", "\"confirm\" must be true or false.", null);
        boolean confirm = Boolean.TRUE.equals(conf);
        int seconds = Theme.PREVIEW_DEFAULT_S;
        if (m.containsKey("revertAfter")) {
            Object r = m.get("revertAfter");
            if (confirm) return error(400, "bad_request", "Use either \"revertAfter\" or \"confirm\": true, not both.", null);
            if (!(r instanceof Double) || (Double) r != Math.rint((Double) r) || (Double) r < Theme.PREVIEW_MIN_S || (Double) r > Theme.PREVIEW_MAX_S)
                return error(400, "bad_request", "\"revertAfter\" must be a whole number of seconds from " + Theme.PREVIEW_MIN_S + " to " + Theme.PREVIEW_MAX_S + ".", null);
            seconds = ((Double) r).intValue();
        }
        Theme t;
        if (m.containsKey("id")) {
            Object id = m.get("id");
            t = id instanceof String ? b.find((String) id) : null;
            if (t == null) return error(404, "unknown_theme", "No built-in or saved theme has that id. GET api/themes lists them.", null);
        } else {
            Object th = m.get("theme");
            if (!(th instanceof Map)) return error(400, "bad_request", "\"theme\" must be a theme object.", null);
            // validate the exact theme text: re-serialise the object strictly
            t = Theme.parse(toJson(th));
            for (Theme bi : b.builtIns()) {
                if (bi.id.equals(t.id)) throw Theme.problem("id", "reserved", "\"" + t.id + "\" is a built-in theme id; give your theme its own id (apply a built-in with {\"id\": \"" + t.id + "\"}).");
            }
        }
        long now = b.now();
        if (now - lastApply < APPLY_GAP_MS) return error(429, "too_fast", "Wait a second between applies.", null);
        lastApply = now;
        if (confirm) {
            b.applyNow(t);
            return ok("\"applied\":" + Theme.quote(t.id) + ",\"warnings\":" + warnings(t) + ",\"preview\":null");
        }
        b.preview(t, seconds);
        return ok("\"applied\":" + Theme.quote(t.id) + ",\"warnings\":" + warnings(t) + ",\"preview\":{\"id\":" + Theme.quote(t.id) + ",\"secondsLeft\":" + seconds
                + "},\"next\":\"POST api/confirm to keep it, or api/revert to go back now\"");
    }

    /** Design warnings (focus and hover pitfalls): the theme is valid, but these should be fixed. */
    static String warnings(Theme t) {
        StringBuilder b = new StringBuilder("[");
        if (t.ui != null) for (int i = 0; i < t.ui.warnings.size(); i++) b.append(i == 0 ? "" : ",").append(t.ui.warnings.get(i).toJson());
        return b.append(']').toString();
    }

    private String previewJson() {
        Theme p = b.previewing();
        if (p == null) return "null";
        return "{\"id\":" + Theme.quote(p.id) + ",\"secondsLeft\":" + b.previewSecondsLeft() + "}";
    }

    private static String list(List<Theme> l) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            Theme t = l.get(i);
            s.append(i == 0 ? "" : ",").append("{\"id\":").append(Theme.quote(t.id)).append(",\"name\":").append(Theme.quote(t.name))
                    .append(",\"author\":").append(Theme.quote(t.author)).append(",\"light\":").append(t.light()).append('}');
        }
        return s.append(']').toString();
    }

    /** Writes a parsed JSON value back as JSON (for the nested theme object). */
    static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return Theme.quote((String) v);
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Double) {
            double d = (Double) v;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        StringBuilder s = new StringBuilder();
        if (v instanceof Map) {
            s.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                s.append(first ? "" : ",").append(Theme.quote((String) e.getKey())).append(':').append(toJson(e.getValue()));
                first = false;
            }
            return s.append('}').toString();
        }
        s.append('[');
        List<?> l = (List<?>) v;
        for (int i = 0; i < l.size(); i++) s.append(i == 0 ? "" : ",").append(toJson(l.get(i)));
        return s.append(']').toString();
    }

    private static Response ok(String fields) {
        return new Response(200, "application/json", "{\"ok\":true," + fields + "}", false);
    }

    private static Response notAllowed(String allow) {
        return error(405, "method", "Use " + allow + " for this endpoint.", null);
    }

    public static Response error(int status, String code, String message, String problems) {
        return new Response(status, "application/json", "{\"ok\":false,\"error\":{\"code\":" + Theme.quote(code) + ",\"message\":"
                + Theme.quote(message) + "},\"errors\":" + (problems != null ? problems : "[]") + "}", false);
    }
}
