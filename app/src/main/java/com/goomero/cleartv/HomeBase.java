package com.goomero.cleartv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The home screen, built by the active theme's layout engine: {@link HomeView} (classic: header, service tiles, rows),
 * {@link AppleHome} (tvOS-style Top Shelf, dock and rows) or {@link RailHome} (sidebar, featured banner, grid). All
 * three take the same callbacks and data from MainActivity, so every feature works in every layout.
 */
abstract class HomeBase extends FrameLayout {
    /** The rows the home last showed, so the theme picker's preview can show the same real cards. */
    static java.util.List<HomeView.Card> lastCont = java.util.Collections.emptyList(), lastFavs = java.util.Collections.emptyList();

    protected final HomeView.Callbacks cb;
    protected final TileIcons icons;

    HomeBase(Context c, HomeView.Callbacks cb, TileIcons icons) {
        super(c);
        this.cb = cb;
        this.icons = icons;
    }

    /** The home for the active theme's layout engine. */
    static HomeBase create(Context c, HomeView.Callbacks cb, TileIcons icons) {
        if (Ui.LAYOUT != null) {
            try {
                return new ComposeHome(c, cb, icons, Ui.LAYOUT);
            } catch (RuntimeException e) {
                // a layout that validated but cannot be built on this device: the Default home, never a broken screen
                android.util.Log.w("CTV.Theme", "layout fallback: " + e);
                return new HomeView(c, cb, icons);
            }
        }
        switch (Ui.ENGINE) {
            case "apple": return new AppleHome(c, cb, icons);
            case "rail": return new RailHome(c, cb, icons);
            default: return new HomeView(c, cb, icons);
        }
    }

    abstract void setStatus(String s);

    /** Health notices per service id (null map entries: none). */
    abstract void setNotices(java.util.Map<String, String> notices);

    /** The Continue watching and Watchlist cards (either may be empty). */
    abstract void setRows(java.util.List<HomeView.Card> cont, java.util.List<HomeView.Card> favs);

    abstract void focusDefault();

    /** Shown: draw backgrounds. */
    abstract void prepare();

    /** Hidden or low memory: free bitmaps. */
    abstract void release();

    /** Opens Settings > Theme directly (the header Themes button in every layout). */
    void openThemes() {
        getContext().startActivity(new Intent(getContext(), com.goomero.cleartv.theme.ThemeActivity.class));
    }

    static String serviceIcon(String service) {
        for (Services.Service s : Services.ALL) if (s.id.equals(service)) return s.iconKey;
        return "";
    }

    static Services.Service service(String id) {
        for (Services.Service s : Services.ALL) if (s.id.equals(id)) return s;
        return null;
    }

    /** The rows the theme shows, in its order ("continue", "watchlist"), skipping empty ones. */
    static java.util.List<String> contentRows(java.util.List<HomeView.Card> cont, java.util.List<HomeView.Card> favs) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : Ui.HOME_ROWS) {
            if (id.equals("continue") && !cont.isEmpty()) out.add(id);
            if (id.equals("watchlist") && !favs.isEmpty()) out.add(id);
        }
        return out;
    }

    static String rowTitle(String id) {
        return id.equals("continue") ? "Continue watching" : "Watchlist";
    }

    private View okView;
    private boolean okLong;

    /** OK on a tile or card: a tap clicks on release, held ~500 ms it opens the menu (same rule as every ClearTV row). */
    protected boolean holdOk(View v, int code, KeyEvent e, Runnable menu) {
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                okView = v;
                okLong = false;
            } else if (okView == v && !okLong && com.goomero.cleartv.tv.ShelfView.isHeld(e)) {
                okLong = true;
                menu.run();
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            boolean tap = okView == v && !okLong;
            okView = null;
            okLong = false;
            if (tap) v.performClick();
        }
        return true;
    }

    /** Hold OK or MENU opens {@code menu}; a tap clicks. */
    protected void menuKeys(View v, final Runnable menu) {
        v.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View x, int keyCode, KeyEvent e) {
                if (holdOk(x, keyCode, e, menu)) return true;
                if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) menu.run();
                return true;
            }
        });
        v.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View x) {
                menu.run();
                return true;
            }
        });
    }

    /** A line icon drawn in code (search, themes, settings), in the given colour. */
    static final class Glyph extends Drawable {
        static final int SEARCH = 0, THEMES = 1, SETTINGS = 2;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(int kind, int color) {
            this.kind = kind;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), cx = b.exactCenterX(), cy = b.exactCenterY();
            p.setStrokeWidth(s * 0.09f);
            p.setStyle(Paint.Style.STROKE);
            if (kind == SEARCH) {
                c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.24f, p);
                c.drawLine(cx + s * 0.1f, cy + s * 0.1f, cx + s * 0.3f, cy + s * 0.3f, p);
            } else if (kind == THEMES) {
                c.drawCircle(cx, cy, s * 0.32f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - s * 0.12f, cy - s * 0.1f, s * 0.06f, p);
                c.drawCircle(cx + s * 0.1f, cy - s * 0.13f, s * 0.06f, p);
                c.drawCircle(cx + s * 0.15f, cy + s * 0.08f, s * 0.06f, p);
            } else {
                c.drawCircle(cx, cy, s * 0.13f, p);
                c.drawCircle(cx, cy, s * 0.27f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    c.drawLine(cx + (float) Math.cos(a) * s * 0.27f, cy + (float) Math.sin(a) * s * 0.27f,
                            cx + (float) Math.cos(a) * s * 0.37f, cy + (float) Math.sin(a) * s * 0.37f, p);
                }
            }
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
