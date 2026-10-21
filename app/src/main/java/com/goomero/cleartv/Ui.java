package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * Shared colours, type, sizes and focus visuals for a 10-foot UI driven by a D-pad. The palette, radii and type
 * are ClearTV's own design (graphite surfaces, silver accent, Oxanium display face), with the focus ring
 * thickened from 2 px to 3 dp so it reads across a room.
 */
public final class Ui {
    /** Overscan-safe margins in dp (Amazon's guidance: keep UI inside about 5% of each edge at 960x540 dp). */
    public static final int SAFE_X = 48, SAFE_Y = 27;
    // Theme tokens: set by apply(Theme) from the active theme (theme.Themes) before any screen is built. Defaults
    // are the Default theme (graphite palette).
    public static int BG = 0xFF0B0D0F;
    public static int DEEP = 0xFF060809;
    public static int BAR = 0xFF15181B;       // panel-solid: cards, panels, menus
    public static int SURFACE = 0xFF22272C;   // hover: fields, buttons, focused rows
    public static int LINE = 0xFF3A3D3F;
    public static int LINE_SOFT = 0x17FFFFFF;
    public static int TEXT = 0xFFF3F5F7;
    public static int MUTED = 0xFFB8BBBD;     // secondary text, 10:1 on BG
    public static int SOFT = 0xFF858B90;      // kickers and hints, 5.6:1 on BG
    public static int ACCENT = 0xFFCBD1D8;    // silver
    public static int GREEN = 0xFF8FBCA2, RED = 0xFFC57474, AMBER = 0xFFD6B36A;
    public static int FOCUS_FILL = SURFACE;
    /** Translucent card fill (panel at 90%) for cards over the backdrop. */
    public static int CARD_FILL = 0xE615181B;
    public static int RADIUS = 6, PILL = 13;
    /** Focus ring width in dp and focused-tile growth. */
    public static int RING = 3;
    public static float FOCUS_SCALE = 1.04f;
    /** Text size multiplier, tile size multiplier, row gap in dp. */
    public static float FONT_SCALE = 1f, TILE_SCALE = 1f;
    /** Layout engine: "classic", "apple" (top shelf hero, dock, rows) or "rail" (sidebar, banner, grid). */
    public static String ENGINE = "classic";
    /** The active theme's v2 layout document, or null (version 1: the engine homes). */
    public static com.goomero.cleartv.theme.Layout LAYOUT;
    public static int ROW_GAP = 16;
    /** Home layout: header style, backdrop and the rows in order ("services", "continue", "watchlist"). */
    public static String HEADER = "full", BACKDROP = "stars";
    public static java.util.List<String> HOME_ROWS = java.util.Arrays.asList("services", "continue", "watchlist");
    /** False: every service screen uses ACCENT instead of its brand accent. */
    public static boolean SERVICE_ACCENTS = true;
    /** Home service tiles as a tall featured banner row instead of standard cards. */
    public static boolean HERO = false;
    public static boolean LIGHT = false;
    private static String displayName = "oxanium", bodyName = "sans";

    /**
     * Fixed palette for overlays drawn on top of video (player controls, captions, the live info bar): video is
     * dark whatever the theme, so these never change.
     */
    public static final int V_BG = 0xFF0B0D0F, V_TEXT = 0xFFF3F5F7, V_MUTED = 0xFFB8BBBD, V_SOFT = 0xFF9AA0A5, V_LINE = 0xFF3A3D3F;

    /**
     * Spacing tokens in dp on an 8 dp grid (S_HALF only between a label and its own sub-line). Every screen's
     * margins and gaps come from these so text never sits on top of its neighbour.
     */
    public static final int S_HALF = 4, S1 = 8, S2 = 16, S3 = 24, S4 = 32, S6 = 48;
    /** Line height multiplier for body text (multi-line descriptions). */
    public static final float LINE_H = 1.25f;

    private static Typeface display, body;

    private Ui() {}

    /** Loads a theme's tokens. Screens built after this use them; {@code theme.Themes} recreates open screens. */
    /** The theme the tokens were last set from. */
    public static com.goomero.cleartv.theme.Theme CURRENT;

    public static void apply(com.goomero.cleartv.theme.Theme t) {
        CURRENT = t;
        BG = t.background;
        DEEP = t.backgroundDeep;
        BAR = t.panel;
        SURFACE = t.surface;
        LINE = t.line;
        LINE_SOFT = (t.text & 0x00FFFFFF) | 0x17000000;
        TEXT = t.text;
        MUTED = t.textMuted;
        SOFT = t.textSoft;
        ACCENT = t.accent;
        GREEN = t.good;
        RED = t.bad;
        AMBER = t.warn;
        FOCUS_FILL = SURFACE;
        CARD_FILL = (t.panel & 0x00FFFFFF) | (Math.round(t.cardOpacity * 2.55f) << 24);
        HERO = "hero".equals(t.servicesStyle);
        RADIUS = t.radius;
        PILL = Math.max(t.radius, 13);
        RING = t.focusRing;
        FOCUS_SCALE = t.focusScale;
        FONT_SCALE = t.fontScale;
        TILE_SCALE = t.tileSize;
        ENGINE = t.engine;
        LAYOUT = t.ui;
        ROW_GAP = t.rowSpacing;
        HEADER = t.header;
        BACKDROP = t.backdrop;
        HOME_ROWS = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(t.homeRows));
        SERVICE_ACCENTS = t.serviceAccents;
        LIGHT = t.light();
        if (!t.displayFont.equals(displayName)) display = null;
        if (!t.bodyFont.equals(bodyName)) body = null;
        displayName = t.displayFont;
        bodyName = t.bodyFont;
    }

    /** A service's accent on its screens: its brand colour, or the theme accent when the theme says so. */
    public static int serviceAccent(int brand) {
        return SERVICE_ACCENTS ? brand : ACCENT;
    }

    /** Readable text colour on a fill (dark on light fills, light on dark ones). */
    public static int onColor(int fill) {
        return com.goomero.cleartv.theme.Theme.luminance(fill) > 0.35 ? 0xFF0B0D0F : 0xFFF3F5F7;
    }

    /** One of the theme font names as a Typeface (Oxanium from the APK, the rest are system families). */
    public static Typeface face(Context c, String name) {
        switch (name) {
            case "oxanium":
                try {
                    return Typeface.createFromAsset(c.getAssets(), "fonts/oxanium_semibold.ttf");
                } catch (RuntimeException e) {
                    return Typeface.create("sans-serif-medium", Typeface.NORMAL);
                }
            case "sans-medium": return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case "sans-light": return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case "condensed": return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            case "serif": return Typeface.create(Typeface.SERIF, Typeface.NORMAL);
            case "mono": return Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
            default: return Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
        }
    }

    /** The theme's body face (titles, descriptions). */
    public static Typeface body(Context c) {
        if (body == null) body = face(c, bodyName);
        return body;
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /**
     * The theme's display face: by default Oxanium SemiBold (subset to ASCII, 8 KB),
     * falling back to the system sans.
     */
    public static Typeface display(Context c) {
        if (display == null) display = face(c, displayName);
        return display;
    }

    /** Text size in px for {@code sp}, with the theme's text scale (for views that draw their own text). */
    public static float sp(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * FONT_SCALE, c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A card: solid fill, 1 dp hairline border, 6 dp corners. */
    public static GradientDrawable card(Context c, int fill, int border) {
        GradientDrawable g = rounded(fill, dp(c, RADIUS));
        g.setStroke(Math.max(1, dp(c, 1)), border);
        return g;
    }

    public static Drawable focusBackground(Context c, int normal, int focused) {
        float r = dp(c, RADIUS);
        StateListDrawable s = new StateListDrawable();
        // the focus is a silver outline; 3 dp so it reads from the sofa
        GradientDrawable f = rounded(focused, r);
        f.setStroke(dp(c, RING), ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{android.R.attr.state_pressed}, f);
        s.addState(new int[]{}, rounded(normal, r));
        return s;
    }

    /** A pill button with the hairline border at rest and the silver ring when focused. */
    public static Drawable pillBackground(Context c) {
        float r = dp(c, PILL + 6);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = rounded(SURFACE, r);
        f.setStroke(dp(c, RING), ACCENT);
        GradientDrawable n = rounded((BAR & 0x00FFFFFF) | 0x99000000, r);
        n.setStroke(Math.max(1, dp(c, 1)), LINE);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{android.R.attr.state_pressed}, f);
        s.addState(new int[]{}, n);
        return s;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * FONT_SCALE);
        t.setTextColor(color);
        Typeface b = body(c);
        if (!"sans".equals(bodyName)) t.setTypeface(b);
        return t;
    }

    /** Uppercase, letter-spaced Oxanium label (a "kicker"). */
    public static TextView kicker(Context c, String s, float sp, int color) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.ROOT), sp, color);
        t.setTypeface(display(c));
        t.setLetterSpacing(0.14f);
        return t;
    }

    /** One line, ellipsized at the end: for labels that must not wrap into the next element. */
    public static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** At most {@code n} lines with body line height, ellipsized at the end. */
    public static TextView lines(TextView t, int n) {
        t.setMaxLines(n);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setLineSpacing(0, LINE_H);
        return t;
    }

    /** Vertical LinearLayout params with a top margin in dp (0 for none). */
    public static android.widget.LinearLayout.LayoutParams below(Context c, int topDp) {
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    public static void focusScale(View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                float s = hasFocus ? FOCUS_SCALE : 1f;
                // the scaled-up tile and its focus outline must not be cut by the containers around it
                if (hasFocus) unclip(view);
                view.animate().scaleX(s).scaleY(s).setDuration(120).start();
            }
        });
    }

    /** Lets a focused, scaled-up view draw past its parents' bounds (up to three levels). */
    public static void unclip(View v) {
        android.view.ViewParent p = v.getParent();
        for (int i = 0; i < 3 && p instanceof android.view.ViewGroup; i++) {
            ((android.view.ViewGroup) p).setClipChildren(false);
            p = p.getParent();
        }
    }
}
