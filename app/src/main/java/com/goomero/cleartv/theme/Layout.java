package com.goomero.cleartv.theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Theme format v2: the composable, data-only layout document ({@code screens}, {@code assets}). Pure Java, so the
 * validator runs on the TV and in tests. A layout is a tree of components (containers, a backdrop, a hero, text,
 * buttons, navigation lists, rows and grids of landscape cards) with sizes, spacing, token-based styles, data
 * bindings, a focus graph and simple focus animations. It describes; it never runs anything: there are no
 * expressions, scripts, URLs other than https image assets, or file paths.
 */
public final class Layout {
    public static final int MAX_NODES = 160, MAX_DEPTH = 10, MAX_ASSETS = 8, MAX_ASSET_KB = 512;

    public static final List<String> TYPES = list("stack", "overlay", "spacer", "backdrop", "hero", "text", "clock",
            "logo", "image", "button", "nav", "dock", "sidebar", "tabs", "row", "grid");
    public static final List<String> COLOR_TOKENS = list("background", "deep", "panel", "surface", "line", "text",
            "muted", "soft", "accent", "good", "bad", "warn", "card", "brand", "white", "transparent");
    public static final List<String> GRAVITY = list("top-start", "top", "top-end", "start", "center", "end",
            "bottom-start", "bottom", "bottom-end");
    public static final List<String> ALIGN = list("start", "center", "end", "stretch");
    public static final List<String> ACTIONS = list("search", "themes", "settings");
    public static final List<String> NAV_ITEMS = list("services", "search", "themes", "settings");
    public static final List<String> ITEM_STYLES = list("tile", "icon", "text", "logo-text", "pill");
    public static final List<String> SERVICE_IDS = list("youtube", "prime", "tubi", "pluto", "paramount");
    public static final List<String> DATA = list("services", "continue", "watchlist", "favorites", "focused-service",
            "search", "service:youtube", "service:prime", "service:tubi", "service:pluto", "service:paramount");
    public static final List<String> ASPECTS = list("16:9", "2:1", "21:9", "4:3");
    public static final List<String> LABELS = list("none", "below", "focused", "overlay");
    public static final List<String> BINDS = list("clock", "version", "status", "app", "focused-title", "focused-service");
    public static final List<String> TEXT_STYLES = list("display", "body", "kicker");
    public static final List<String> SOURCES = list("focused", "asset", "none");
    public static final List<String> FALLBACKS = list("brand", "stars", "gradient", "plain");
    public static final List<String> SERVICE_NAV = list("list", "tabs", "sidebar");

    private static final List<String> COMMON = list("type", "id", "width", "height", "weight", "padding", "margin",
            "background", "radius", "opacity", "gravity", "focus", "fade");
    private static final Map<String, List<String>> KEYS = new HashMap<>();
    static {
        KEYS.put("stack", list("direction", "gap", "align", "scroll", "children"));
        KEYS.put("overlay", list("children"));
        KEYS.put("spacer", list());
        KEYS.put("backdrop", list("source", "asset", "fallback", "scrim"));
        KEYS.put("hero", list("titleSize", "titleLines", "descLines", "kicker", "shadow", "color"));
        KEYS.put("text", list("text", "bind", "size", "color", "style", "lines", "align", "shadow"));
        KEYS.put("clock", list("size", "color", "style", "shadow"));
        KEYS.put("logo", list("size", "color", "asset"));
        KEYS.put("image", list("asset", "scale"));
        KEYS.put("button", list("action", "label", "glyph", "style", "effect", "color", "size", "card"));
        List<String> nav = list("items", "direction", "gap", "itemStyle", "card", "effect", "align", "color", "size", "glyph");
        KEYS.put("nav", nav);
        KEYS.put("dock", nav);
        KEYS.put("sidebar", nav);
        KEYS.put("tabs", nav);
        List<String> row = list("data", "title", "titleColor", "card", "gap", "effect", "hideEmpty", "maxItems");
        KEYS.put("row", row);
        List<String> grid = new ArrayList<>(row);
        grid.add("columns");
        KEYS.put("grid", grid);
    }

    /** One component. Sizes are dp; -1 = fill the parent, -2 = wrap the content. */
    public static final class Node {
        public String type, id;
        public int width = -2, height = -2, gap = 0, radius = -1, size = 0, lines = 1, titleLines = 2, descLines = 1,
                titleSize = 28, columns = 4, maxItems = 20, fade = 0;
        public float weight = 0, opacity = 1f, scrimBottom = 0.85f, scrimLeft = 0.6f, scrimTop = 0f;
        public int[] padding = {0, 0, 0, 0}, margin = {0, 0, 0, 0};
        public String background, gravity = "top-start", direction = "column", align = "start", color, titleColor,
                text = "", bind, style = "body", action, label, itemStyle = "tile", data, title, source = "focused",
                asset, fallback = "plain", scale = "crop", textAlign = "start";
        public boolean scroll, kicker = true, shadow = true, glyph = true, hideEmpty = true;
        public List<String> items = new ArrayList<>();
        public Map<String, String> focus = new HashMap<>();
        public Card card = new Card();
        public Effect effect = new Effect();
        public List<Node> children = new ArrayList<>();

        /** dock/sidebar/tabs are nav with presets. */
        public boolean isNav() {
            return type.equals("nav") || type.equals("dock") || type.equals("sidebar") || type.equals("tabs");
        }

        public boolean focusable() {
            return isNav() || type.equals("row") || type.equals("grid") || type.equals("button");
        }
    }

    /** A card template: landscape only. */
    public static final class Card {
        public int width = 200, radius = -1, logoSize = 0;
        public String aspect = "16:9", label = "below", fill = "card";
        public boolean subtitle = false, progress = true;

        public float ratio() {
            switch (aspect) {
                case "2:1": return 2f;
                case "21:9": return 21f / 9f;
                case "4:3": return 4f / 3f;
                default: return 16f / 9f;
            }
        }
    }

    /** Focus animation: scale, a soft shadow (elevation), a coloured glow, the ring, a highlight sweep. */
    public static final class Effect {
        public float scale = 0f; // 0: the theme's shape.focusScale
        public int shadow = 0; // off: Default-speed focus is the rule
        public boolean glow = false, ring = true, shine = false;
    }

    public static final class Asset {
        public String url, bundled;
        public int maxKb = 256;
    }

    public Node home;
    public String defaultFocus;
    public String serviceNav = "list";
    public final Map<String, Asset> assets = new LinkedHashMap<>();
    /** Non-fatal design problems (see {@link #lint}); the theme still applies. */
    public final List<Theme.Problem> warnings = new ArrayList<>();
    /** The documents as parsed, for export. */
    public Object screensRaw, assetsRaw;

    /** The engine the service, guide and player chrome follow. */
    public String serviceEngine() {
        return serviceNav.equals("tabs") ? "apple" : serviceNav.equals("sidebar") ? "rail" : "classic";
    }

    /** Every node, depth first. */
    public List<Node> nodes() {
        List<Node> out = new ArrayList<>();
        walk(home, out);
        return out;
    }

    private static void walk(Node n, List<Node> out) {
        if (n == null) return;
        out.add(n);
        for (Node c : n.children) walk(c, out);
    }

    // ------------------------------------------------------------------------------------------------ parse

    static Layout parse(Object screens, Object assets, List<Theme.Problem> p) {
        Layout l = new Layout();
        l.screensRaw = screens;
        l.assetsRaw = assets;
        if (assets != null) {
            if (!(assets instanceof Map)) p.add(new Theme.Problem("assets", "type", "\"assets\" must be an object of name: asset."));
            else {
                Map<?, ?> am = (Map<?, ?>) assets;
                if (am.size() > MAX_ASSETS) p.add(new Theme.Problem("assets", "too_many", "At most " + MAX_ASSETS + " assets."));
                for (Map.Entry<?, ?> e : am.entrySet()) {
                    String k = (String) e.getKey(), path = "assets." + k;
                    if (!k.matches("[a-z][a-z0-9-]{0,23}")) p.add(new Theme.Problem(path, "pattern", "Asset names are 1-24 lowercase letters, digits and dashes."));
                    if (!(e.getValue() instanceof Map)) {
                        p.add(new Theme.Problem(path, "type", "An asset is an object: {\"url\": \"https://...\", \"maxKb\": 256} or {\"bundled\": \"logo\"}."));
                        continue;
                    }
                    Map<?, ?> a = (Map<?, ?>) e.getValue();
                    allow(a, path + ".", p, list("url", "bundled", "maxKb"));
                    Asset as = new Asset();
                    Object u = a.get("url"), b = a.get("bundled");
                    if ((u == null) == (b == null)) p.add(new Theme.Problem(path, "required", "An asset has exactly one of \"url\" or \"bundled\"."));
                    if (u != null) {
                        if (!(u instanceof String) || !safeUrl((String) u)) p.add(new Theme.Problem(path + ".url", "pattern",
                                "Asset URLs must be https:// links to an image (.png, .jpg, .jpeg or .webp), at most 300 characters, no credentials."));
                        else as.url = (String) u;
                    }
                    if (b != null) {
                        if (!"logo".equals(b) && !"stars".equals(b)) p.add(new Theme.Problem(path + ".bundled", "enum", "\"bundled\" must be one of [logo, stars]."));
                        else as.bundled = (String) b;
                    }
                    Integer kb = intIn(a, "maxKb", path + ".", 16, MAX_ASSET_KB, p);
                    if (kb != null) as.maxKb = kb;
                    l.assets.put(k, as);
                }
            }
        }
        if (!(screens instanceof Map)) {
            p.add(new Theme.Problem("screens", "required", "Version 2 themes need \"screens\" with at least \"home\"."));
            return l;
        }
        Map<?, ?> sm = (Map<?, ?>) screens;
        allow(sm, "screens.", p, list("home", "service"));
        Object svc = sm.get("service");
        if (svc != null) {
            if (!(svc instanceof Map)) p.add(new Theme.Problem("screens.service", "type", "\"service\" must be an object."));
            else {
                allow((Map<?, ?>) svc, "screens.service.", p, list("nav"));
                String n = oneOf((Map<?, ?>) svc, "nav", "screens.service.", SERVICE_NAV, p);
                if (n != null) l.serviceNav = n;
            }
        }
        Object home = sm.get("home");
        if (!(home instanceof Map)) {
            p.add(new Theme.Problem("screens.home", "required", "\"screens.home\" is required: {\"defaultFocus\": id, \"root\": component}."));
            return l;
        }
        Map<?, ?> hm = (Map<?, ?>) home;
        allow(hm, "screens.home.", p, list("root", "defaultFocus"));
        int[] count = {0};
        l.home = node(hm.get("root"), "screens.home.root", 1, count, p);
        Object df = hm.get("defaultFocus");
        if (!(df instanceof String)) p.add(new Theme.Problem("screens.home.defaultFocus", "required", "\"defaultFocus\" is the id of the component that has focus first."));
        else l.defaultFocus = (String) df;
        if (l.home != null && p.isEmpty()) check(l, p);
        return l;
    }

    private static Node node(Object o, String path, int depth, int[] count, List<Theme.Problem> p) {
        if (!(o instanceof Map)) {
            p.add(new Theme.Problem(path, "type", "A component is an object with a \"type\"."));
            return null;
        }
        if (depth > MAX_DEPTH) {
            p.add(new Theme.Problem(path, "too_deep", "Components nest at most " + MAX_DEPTH + " levels."));
            return null;
        }
        if (++count[0] > MAX_NODES) {
            if (count[0] == MAX_NODES + 1) p.add(new Theme.Problem(path, "too_many", "At most " + MAX_NODES + " components per screen."));
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) o;
        Node n = new Node();
        String pre = path + ".";
        String type = oneOf(m, "type", pre, TYPES, p);
        if (type == null) {
            if (!m.containsKey("type")) p.add(new Theme.Problem(pre + "type", "required", "\"type\" is required, one of " + TYPES + "."));
            return null;
        }
        n.type = type;
        List<String> ok = new ArrayList<>(COMMON);
        ok.addAll(KEYS.get(type));
        allow(m, pre, p, ok);
        String id = str(m, "id", pre, p);
        if (id != null) {
            if (!id.matches("[a-z][a-z0-9-]{0,31}")) p.add(new Theme.Problem(pre + "id", "pattern", "Ids are 1-32 lowercase letters, digits and dashes, starting with a letter."));
            else n.id = id;
        }
        n.width = size(m, "width", pre, p, n.width);
        n.height = size(m, "height", pre, p, n.height);
        Double w = num(m, "weight", pre, 0, 10, p);
        if (w != null) n.weight = w.floatValue();
        n.padding = box(m, "padding", pre, p, n.padding);
        n.margin = box(m, "margin", pre, p, n.margin);
        n.background = colorRef(m, "background", pre, p);
        Integer r = intIn(m, "radius", pre, 0, 48, p);
        if (r != null) n.radius = r;
        Double op = num(m, "opacity", pre, 0.1, 1, p);
        if (op != null) n.opacity = op.floatValue();
        String g = oneOf(m, "gravity", pre, GRAVITY, p);
        if (g != null) n.gravity = g;
        Integer fd = intIn(m, "fade", pre, 0, 600, p);
        if (fd != null) n.fade = fd;
        Object f = m.get("focus");
        if (f != null) {
            if (!(f instanceof Map)) p.add(new Theme.Problem(pre + "focus", "type", "\"focus\" maps up/down/left/right to component ids."));
            else {
                allow((Map<?, ?>) f, pre + "focus.", p, list("up", "down", "left", "right"));
                for (Map.Entry<?, ?> e : ((Map<?, ?>) f).entrySet()) {
                    if (e.getValue() instanceof String) n.focus.put((String) e.getKey(), (String) e.getValue());
                    else p.add(new Theme.Problem(pre + "focus." + e.getKey(), "type", "Focus targets are component ids."));
                }
            }
        }
        // type-specific
        String s;
        Integer i;
        if ((s = oneOf(m, "direction", pre, list("row", "column"), p)) != null) n.direction = s;
        if ((i = intIn(m, "gap", pre, 0, 96, p)) != null) n.gap = i;
        if ((s = oneOf(m, "align", pre, ALIGN, p)) != null) { n.align = s; n.textAlign = s; }
        Boolean b;
        if ((b = bool(m, "scroll", pre, p)) != null) n.scroll = b;
        if ((s = oneOf(m, "source", pre, SOURCES, p)) != null) n.source = s;
        if ((s = str(m, "asset", pre, p)) != null) n.asset = s;
        if ((s = oneOf(m, "fallback", pre, FALLBACKS, p)) != null) n.fallback = s;
        Object sc = m.get("scrim");
        if (sc != null) {
            if (!(sc instanceof Map)) p.add(new Theme.Problem(pre + "scrim", "type", "\"scrim\" is {\"bottom\": 0-1, \"left\": 0-1, \"top\": 0-1}."));
            else {
                Map<?, ?> sm = (Map<?, ?>) sc;
                allow(sm, pre + "scrim.", p, list("bottom", "left", "top"));
                Double v;
                if ((v = num(sm, "bottom", pre + "scrim.", 0, 1, p)) != null) n.scrimBottom = v.floatValue();
                if ((v = num(sm, "left", pre + "scrim.", 0, 1, p)) != null) n.scrimLeft = v.floatValue();
                if ((v = num(sm, "top", pre + "scrim.", 0, 1, p)) != null) n.scrimTop = v.floatValue();
            }
        }
        if ((i = intIn(m, "titleSize", pre, 16, 48, p)) != null) n.titleSize = i;
        if ((i = intIn(m, "titleLines", pre, 1, 3, p)) != null) n.titleLines = i;
        if ((i = intIn(m, "descLines", pre, 0, 3, p)) != null) n.descLines = i;
        if ((b = bool(m, "kicker", pre, p)) != null) n.kicker = b;
        if ((b = bool(m, "shadow", pre, p)) != null) n.shadow = b;
        n.color = colorRef(m, "color", pre, p);
        n.titleColor = colorRef(m, "titleColor", pre, p);
        if ((s = str(m, "text", pre, p)) != null) {
            if (!Theme.safeLabel(s, 80)) p.add(new Theme.Problem(pre + "text", "pattern", "\"text\" is at most 80 printable characters."));
            else n.text = s;
        }
        if ((s = oneOf(m, "bind", pre, BINDS, p)) != null) n.bind = s;
        if ((i = intIn(m, "size", pre, 8, 64, p)) != null) n.size = i;
        if ((s = oneOf(m, "style", pre, type.equals("button") ? ITEM_STYLES : TEXT_STYLES, p)) != null) n.style = s;
        if ((i = intIn(m, "lines", pre, 1, 4, p)) != null) n.lines = i;
        if ((s = oneOf(m, "scale", pre, list("crop", "fit"), p)) != null) n.scale = s;
        if ((s = oneOf(m, "action", pre, ACTIONS, p)) != null) n.action = s;
        if ((s = str(m, "label", pre, p)) != null) {
            if (!Theme.safeLabel(s, 32)) p.add(new Theme.Problem(pre + "label", "pattern", "\"label\" is at most 32 printable characters."));
            else n.label = s;
        }
        if ((b = bool(m, "glyph", pre, p)) != null) n.glyph = b;
        if (m.containsKey("items")) {
            Object it = m.get("items");
            boolean good = it instanceof List && !((List<?>) it).isEmpty();
            if (good) for (Object x : (List<?>) it) {
                if (!(x instanceof String) || !NAV_ITEMS.contains(x) || n.items.contains(x)) { good = false; break; }
                n.items.add((String) x);
            }
            if (!good) p.add(new Theme.Problem(pre + "items", "enum", "\"items\" lists each of " + NAV_ITEMS + " at most once."));
        } else if (n.isNav()) {
            n.items.addAll(NAV_ITEMS);
        }
        if (n.isNav()) {
            // presets for the aliases; explicit keys win
            if (!m.containsKey("direction")) n.direction = type.equals("sidebar") ? "column" : "row";
            if (!m.containsKey("itemStyle")) n.itemStyle = type.equals("dock") ? "icon" : type.equals("sidebar") ? "logo-text" : type.equals("tabs") ? "text" : "tile";
        }
        if ((s = oneOf(m, "itemStyle", pre, ITEM_STYLES, p)) != null) n.itemStyle = s;
        if ((s = oneOf(m, "data", pre, DATA, p)) != null) n.data = s;
        if ((type.equals("row") || type.equals("grid")) && n.data == null && !m.containsKey("data"))
            p.add(new Theme.Problem(pre + "data", "required", "Rows and grids need \"data\", one of " + DATA + "."));
        if ((s = str(m, "title", pre, p)) != null) {
            if (!Theme.safeLabel(s, 40) && !s.isEmpty()) p.add(new Theme.Problem(pre + "title", "pattern", "\"title\" is at most 40 printable characters."));
            else n.title = s;
        }
        if ((b = bool(m, "hideEmpty", pre, p)) != null) n.hideEmpty = b;
        if ((i = intIn(m, "maxItems", pre, 1, 40, p)) != null) n.maxItems = i;
        if ((i = intIn(m, "columns", pre, 2, 6, p)) != null) n.columns = i;
        Object cd = m.get("card");
        if (cd != null) {
            if (!(cd instanceof Map)) p.add(new Theme.Problem(pre + "card", "type", "\"card\" is a card template object."));
            else {
                Map<?, ?> cm = (Map<?, ?>) cd;
                String cp = pre + "card.";
                allow(cm, cp, p, list("width", "aspect", "radius", "label", "subtitle", "fill", "logoSize", "progress", "poster"));
                if (cm.containsKey("poster")) p.add(new Theme.Problem(cp + "poster", "unsupported", "Poster (portrait) cards are not supported: every card is landscape."));
                if ((i = intIn(cm, "width", cp, 80, 480, p)) != null) n.card.width = i;
                if ((s = oneOf(cm, "aspect", cp, ASPECTS, p)) != null) n.card.aspect = s;
                if ((i = intIn(cm, "radius", cp, 0, 48, p)) != null) n.card.radius = i;
                if ((s = oneOf(cm, "label", cp, LABELS, p)) != null) n.card.label = s;
                if ((b = bool(cm, "subtitle", cp, p)) != null) n.card.subtitle = b;
                if ((b = bool(cm, "progress", cp, p)) != null) n.card.progress = b;
                String fill = colorRef(cm, "fill", cp, p);
                if (fill != null) n.card.fill = fill;
                if ((i = intIn(cm, "logoSize", cp, 0, 160, p)) != null) n.card.logoSize = i;
            }
        }
        Object ef = m.get("effect");
        if (ef != null) {
            if (!(ef instanceof Map)) p.add(new Theme.Problem(pre + "effect", "type", "\"effect\" is {scale, shadow, glow, ring, shine}."));
            else {
                Map<?, ?> em = (Map<?, ?>) ef;
                String ep = pre + "effect.";
                allow(em, ep, p, list("scale", "shadow", "glow", "ring", "shine"));
                Double v;
                if ((v = num(em, "scale", ep, 1, 1.25, p)) != null) n.effect.scale = v.floatValue();
                if ((i = intIn(em, "shadow", ep, 0, 40, p)) != null) n.effect.shadow = i;
                if ((b = bool(em, "glow", ep, p)) != null) n.effect.glow = b;
                if ((b = bool(em, "ring", ep, p)) != null) n.effect.ring = b;
                if ((b = bool(em, "shine", ep, p)) != null) n.effect.shine = b;
            }
        }
        Object ch = m.get("children");
        if (ch != null) {
            if (!(ch instanceof List)) p.add(new Theme.Problem(pre + "children", "type", "\"children\" is a list of components."));
            else {
                List<?> cl = (List<?>) ch;
                for (int k = 0; k < cl.size(); k++) {
                    Node c = node(cl.get(k), pre + "children[" + k + "]", depth + 1, count, p);
                    if (c != null) n.children.add(c);
                }
            }
        }
        return n;
    }

    /** Whole-tree rules: unique ids, focus targets exist, one backdrop, every feature reachable. */
    private static void check(Layout l, List<Theme.Problem> p) {
        Map<String, Node> ids = new HashMap<>();
        int backdrops = 0;
        Set<String> reach = new HashSet<>();
        Map<String, Integer> navCount = new HashMap<>();
        for (Node n : l.nodes()) {
            if (n.id != null && ids.put(n.id, n) != null) p.add(new Theme.Problem("screens.home", "duplicate_id", "The id \"" + n.id + "\" is used more than once."));
            if (n.type.equals("backdrop")) {
                backdrops++;
                if (n.source.equals("asset") && (n.asset == null || !l.assets.containsKey(n.asset)))
                    p.add(new Theme.Problem("screens.home", "missing_asset", "A backdrop with source \"asset\" needs \"asset\" naming an entry in \"assets\"."));
            }
            if ((n.type.equals("image") || n.type.equals("logo")) && n.asset != null && !l.assets.containsKey(n.asset))
                p.add(new Theme.Problem("screens.home", "missing_asset", "The asset \"" + n.asset + "\" is not in \"assets\"."));
            if (n.type.equals("image") && n.asset == null) p.add(new Theme.Problem("screens.home", "required", "An image needs \"asset\"."));
            if (n.type.equals("button")) {
                if (n.action == null) p.add(new Theme.Problem("screens.home", "required", "A button needs \"action\", one of " + ACTIONS + "."));
                else { reach.add(n.action); bump(navCount, n.action); }
            }
            if (n.isNav()) { reach.addAll(n.items); for (String it : n.items) bump(navCount, it); }
            if ((n.type.equals("row") || n.type.equals("grid")) && n.data != null) {
                if (n.data.equals("services")) { reach.add("services"); bump(navCount, "services"); }
                if (n.data.equals("search")) { reach.add("search"); bump(navCount, "search"); }
            }
            if (!n.children.isEmpty() && !n.type.equals("stack") && !n.type.equals("overlay"))
                p.add(new Theme.Problem("screens.home", "children", "Only stack and overlay components have children (found on a " + n.type + ")."));
        }
        // each navigation option appears once: the same services (or Search, Themes, Settings) in a sidebar and again
        // in a grid is duplicate navigation, confusing to move through with a remote
        for (Map.Entry<String, Integer> e : navCount.entrySet()) {
            if (e.getValue() > 1) p.add(new Theme.Problem("screens.home", "duplicate_nav", "\"" + e.getKey() + "\" appears in "
                    + e.getValue() + " places (nav items, buttons, or rows/grids with that data); show each navigation option once."));
        }
        if (backdrops > 1) p.add(new Theme.Problem("screens.home", "one_backdrop", "A screen has at most one backdrop (it is the single full-screen image layer)."));
        for (Node n : l.nodes()) {
            for (Map.Entry<String, String> e : n.focus.entrySet()) {
                Node t = ids.get(e.getValue());
                if (t == null) p.add(new Theme.Problem("screens.home", "focus_target", "focus." + e.getKey() + " points at \"" + e.getValue() + "\", which is not an id in this screen."));
                else if (!hasFocusable(t)) p.add(new Theme.Problem("screens.home", "focus_target", "\"" + e.getValue() + "\" has nothing focusable (use a nav, row, grid or button, or a container holding one)."));
            }
        }
        Node df = l.defaultFocus == null ? null : ids.get(l.defaultFocus);
        if (df == null) p.add(new Theme.Problem("screens.home.defaultFocus", "focus_target", "\"defaultFocus\" must be the id of a component in the screen."));
        else if (!hasFocusable(df)) p.add(new Theme.Problem("screens.home.defaultFocus", "focus_target", "\"defaultFocus\" must hold something focusable."));
        for (String need : list("services", "search", "themes", "settings")) {
            if (!reach.contains(need)) p.add(new Theme.Problem("screens.home", "unreachable", "The home must reach \"" + need
                    + "\" (a nav item, a button action" + (need.equals("services") || need.equals("search") ? " or a row/grid with that data" : "") + ")."));
        }
    }

    private static void bump(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static boolean hasFocusable(Node n) {
        if (n.focusable()) return true;
        for (Node c : n.children) if (hasFocusable(c)) return true;
        return false;
    }

    /**
     * Focus and hover pitfalls that can be detected from the data: neighbours too close for the focus growth, corner
     * radii that the focus ring cannot follow, an unreadable focus-driven backdrop, slow or costly animations.
     */
    void lint(Theme t) {
        warnings.clear();
        List<Node> all = nodes();
        for (int i = 0; i < all.size(); i++) {
            Node n = all.get(i);
            String where = "screens.home" + (n.id != null ? " (" + n.id + ")" : " (" + n.type + " #" + i + ")");
            boolean items = n.isNav() || n.type.equals("row") || n.type.equals("grid");
            boolean textItems = n.isNav() && !n.itemStyle.equals("tile") && !n.itemStyle.equals("icon");
            if (items && !textItems) {
                float scale = n.effect.scale > 0 ? n.effect.scale : t.focusScale;
                int gap = n.gap > 0 ? n.gap : n.isNav() ? 16 : 20;
                float growth = n.card.width * t.tileSize * (scale - 1f);
                if (gap + 0.5f < growth) warnings.add(new Theme.Problem(where, "warn_gap", String.format(Locale.ROOT,
                        "A focused card grows %.0f dp (width %d x scale %.2f) but the gap is %d dp: it will overlap its neighbours. Use a gap of at least %d.",
                        growth, n.card.width, scale, gap, (int) Math.ceil(growth))));
                int r = n.card.radius >= 0 ? n.card.radius : t.radius;
                int h = Math.round(n.card.width / n.card.ratio());
                if (r * 2 > h) warnings.add(new Theme.Problem(where, "warn_radius", "Card radius " + r + " dp is more than half the card height (" + h
                        + " dp): the focus ring and the shape cannot match."));
            }
            if (n.focusable() && n.radius >= 0 && n.card.radius >= 0 && n.radius != n.card.radius)
                warnings.add(new Theme.Problem(where, "warn_radius", "The component radius (" + n.radius + ") differs from its card radius (" + n.card.radius
                        + "): the focus ring follows the card radius; use one value."));
            if (n.effect.shadow > 0 || n.effect.glow || n.effect.shine)
                warnings.add(new Theme.Problem(where, "warn_slow", "effect.shadow/glow/shine cost extra drawing on every focus move; themes must match Default's focus speed. Leave them off and use scale + ring only."));
            if (n.fade > 0) warnings.add(new Theme.Problem(where, "warn_slow", "fade " + n.fade + " ms animates a large view on focus; themes must match Default's focus speed. Use 0."));
            if ((n.type.equals("row") || n.isNav()) && n.card != null && n.card.width > 0 && n.effect.scale > 1f
                    && n.card.width * (n.effect.scale - 1f) / 2f + 3 > 16)
                warnings.add(new Theme.Problem(where, "warn_layout", "Focus scale " + n.effect.scale + " on " + n.card.width
                        + " dp cards grows more than the 16 dp a row keeps free at its edges: the first and last cards would be cut. Use a smaller scale or narrower cards."));
            if (n.type.equals("backdrop") && n.source.equals("asset") && n.asset != null && assets.get(n.asset) != null && assets.get(n.asset).maxKb < 128)
                warnings.add(new Theme.Problem(where, "warn_quality", "A full-screen backdrop asset capped at " + assets.get(n.asset).maxKb
                        + " KB is likely too small and will look blown up; use an image at least 1280 px wide and maxKb of 128 or more."));
            if (n.type.equals("backdrop")) {
                if (n.source.equals("focused") && n.scrimBottom < 0.5f && n.scrimLeft < 0.3f)
                    warnings.add(new Theme.Problem(where, "warn_readability", "A focus-driven backdrop changes under the text: add scrim.bottom of at least 0.5 or scrim.left of at least 0.3 so text stays readable on bright art."));
            }
            if (n.type.equals("stack") && n.scroll) {
                for (Node c : n.children) if (c.weight > 0) warnings.add(new Theme.Problem(where, "warn_layout",
                        "A scrolling stack has no leftover space, so the weight on its child has no effect."));
            }
        }
    }

    static boolean safeUrl(String u) {
        if (u.length() > 300 || !u.startsWith("https://")) return false;
        String rest = u.substring(8);
        int slash = rest.indexOf('/');
        if (slash <= 0) return false;
        String host = rest.substring(0, slash);
        if (host.contains("@") || host.contains(":") || !host.matches("[A-Za-z0-9.-]+") || !host.contains(".")) return false;
        String path = rest.substring(slash).toLowerCase(Locale.ROOT);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        for (int k = 0; k < u.length(); k++) if (u.charAt(k) <= ' ' || u.charAt(k) > '~') return false;
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static List<String> list(String... s) {
        return Collections.unmodifiableList(Arrays.asList(s));
    }

    private static void allow(Map<?, ?> m, String pre, List<Theme.Problem> p, List<String> ok) {
        for (Object k : m.keySet()) {
            if (!ok.contains(k)) p.add(new Theme.Problem(pre + k, "unknown_key", "Unknown key \"" + k + "\". Allowed here: " + ok + "."));
        }
    }

    private static String str(Map<?, ?> m, String k, String pre, List<Theme.Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (v instanceof String) return (String) v;
        p.add(new Theme.Problem(pre + k, "type", "\"" + k + "\" must be a string."));
        return null;
    }

    private static String oneOf(Map<?, ?> m, String k, String pre, List<String> ok, List<Theme.Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (v instanceof String && ok.contains(v)) return (String) v;
        p.add(new Theme.Problem(pre + k, "enum", "\"" + k + "\" must be one of " + ok + "."));
        return null;
    }

    private static Boolean bool(Map<?, ?> m, String k, String pre, List<Theme.Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        p.add(new Theme.Problem(pre + k, "type", "\"" + k + "\" must be true or false."));
        return null;
    }

    private static Double num(Map<?, ?> m, String k, String pre, double min, double max, List<Theme.Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (v instanceof Double && (Double) v >= min && (Double) v <= max) return (Double) v;
        p.add(new Theme.Problem(pre + k, "range", String.format(Locale.ROOT, "\"%s\" must be a number from %s to %s.", k, trim(min), trim(max))));
        return null;
    }

    private static Integer intIn(Map<?, ?> m, String k, String pre, int min, int max, List<Theme.Problem> p) {
        Double d = num(m, k, pre, min, max, p);
        if (d == null) return null;
        if (d != Math.floor(d)) {
            p.add(new Theme.Problem(pre + k, "type", "\"" + k + "\" must be a whole number."));
            return null;
        }
        return d.intValue();
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static int size(Map<?, ?> m, String k, String pre, List<Theme.Problem> p, int def) {
        if (!m.containsKey(k)) return def;
        Object v = m.get(k);
        if ("fill".equals(v)) return -1;
        if ("wrap".equals(v)) return -2;
        if (v instanceof Double && (Double) v >= 0 && (Double) v <= 1920 && (Double) v == Math.floor((Double) v)) return ((Double) v).intValue();
        p.add(new Theme.Problem(pre + k, "size", "\"" + k + "\" is a whole number of dp (0-1920), \"fill\" or \"wrap\"."));
        return def;
    }

    private static int[] box(Map<?, ?> m, String k, String pre, List<Theme.Problem> p, int[] def) {
        if (!m.containsKey(k)) return def;
        Object v = m.get(k);
        if (v instanceof Double && (Double) v >= 0 && (Double) v <= 400) {
            int x = ((Double) v).intValue();
            return new int[]{x, x, x, x};
        }
        if (v instanceof List && ((List<?>) v).size() == 4) {
            int[] out = new int[4];
            boolean ok = true;
            for (int j = 0; j < 4; j++) {
                Object x = ((List<?>) v).get(j);
                if (!(x instanceof Double) || (Double) x < 0 || (Double) x > 400) { ok = false; break; }
                out[j] = ((Double) x).intValue();
            }
            if (ok) return out;
        }
        p.add(new Theme.Problem(pre + k, "box", "\"" + k + "\" is a number of dp (0-400) or [left, top, right, bottom]."));
        return def;
    }

    private static String colorRef(Map<?, ?> m, String k, String pre, List<Theme.Problem> p) {
        if (!m.containsKey(k)) return null;
        Object v = m.get(k);
        if (v instanceof String && (COLOR_TOKENS.contains(v) || Theme.color((String) v) != null)) return (String) v;
        p.add(new Theme.Problem(pre + k, "color_ref", "\"" + k + "\" is a colour token " + COLOR_TOKENS + " or an opaque hex colour like \"#1A2B3C\"."));
        return null;
    }
}
