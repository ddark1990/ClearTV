package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home: one row of service cards in ClearTV's style (graphite cards, hairline borders, Oxanium labels, silver
 * focus ring) over a still nebula-and-stars backdrop. The backdrop is drawn once into a bitmap when home is shown
 * and released when a service opens, so it costs nothing per frame and nothing while watching.
 */
final class HomeView extends HomeBase {
    interface Callbacks {
        void onOpen(Services.Service s);

        void onServiceMenu(Services.Service s);

        void onSettings();

        void onSearchAll();

        /** A Continue watching or favourite card: open it in its service. */
        void onOpenEntry(String service, com.goomero.cleartv.tv.Item item);

        /** OK held or MENU on such a card. */
        void onEntryMenu(String service, com.goomero.cleartv.tv.Item item, boolean favorite);
    }

    /** One card of a home row. */
    static final class Card {
        final String service, serviceName;
        final com.goomero.cleartv.tv.Item item;
        final float progress;
        final boolean favorite;

        Card(String service, String serviceName, com.goomero.cleartv.tv.Item item, float progress, boolean favorite) {
            this.service = service;
            this.serviceName = serviceName;
            this.item = item;
            this.progress = progress;
            this.favorite = favorite;
        }
    }

    private final int TILE_H = Ui.HERO ? 300 : 236, TILE_H_ROWS = Ui.HERO ? 236 : 168, LOGO = Ui.HERO ? 120 : 84;
    /** Home card width and picture height in dp, from the theme's tile size and style (banner 16:9, poster 2:3). */
    private final int CARD_W = Math.round(208 * Ui.TILE_SCALE), CARD_IMG_H = Math.round(117 * Ui.TILE_SCALE);
    private final LinearLayout row;
    private final LinearLayout rows;
    private final View spacerTop, spacerBottom;
    private final TextView searchAll, themes;
    private final java.util.HashMap<String, TextView> tileSubs = new java.util.HashMap<>();
    private final java.util.HashMap<String, String> taglines = new java.util.HashMap<>();
    private final java.util.HashMap<ImageView, String> boundArt = new java.util.HashMap<>();
    private com.goomero.cleartv.tv.Images art;
    private final TextView status;
    private final TextView settings;
    private View firstTile;
    private Bitmap backdrop;

    HomeView(Context c, Callbacks callbacks, TileIcons icons) {
        super(c, callbacks, icons);
        setBackgroundColor(Ui.BG);
        FitScroll scroll = new FitScroll(c);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setSmoothScrollingEnabled(true);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        // overscan-safe on all sides; tile margins (8 dp) sit inside the padding, and the focused card's 4% zoom
        // stays within its own margin
        int pad = Ui.dp(c, Ui.SAFE_X - Ui.S1);
        col.setPadding(pad, Ui.dp(c, Ui.SAFE_Y + Ui.S1), pad, Ui.dp(c, Ui.SAFE_Y));
        scroll.addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(c, Ui.S1), 0, Ui.dp(c, Ui.S1), 0);
        LinearLayout brand = new LinearLayout(c);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.kicker(c, c.getString(R.string.app_name), 26, Ui.TEXT);
        name.setLetterSpacing(0.12f);
        brand.addView(Ui.oneLine(name));
        // the kicker line, then the version, muted, in the top-left safe corner (always on screen)
        LinearLayout kickRow = new LinearLayout(c);
        kickRow.setOrientation(LinearLayout.HORIZONTAL);
        kickRow.addView(Ui.oneLine(Ui.kicker(c, c.getString(R.string.home_kicker), 11, Ui.SOFT)));
        TextView ver = Ui.oneLine(Ui.kicker(c, "v" + BuildConfig.VERSION_NAME, 10, Ui.SOFT));
        ver.setAlpha(0.55f);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        verLp.leftMargin = Ui.dp(c, Ui.S2);
        kickRow.addView(ver, verLp);
        brand.addView(kickRow, Ui.below(c, Ui.S_HALF));
        // theme header style: full (name, kicker, version), compact (smaller name only), minimal (buttons only)
        if ("compact".equals(Ui.HEADER)) {
            kickRow.setVisibility(View.GONE);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20 * Ui.FONT_SCALE);
        } else if ("minimal".equals(Ui.HEADER)) {
            brand.setVisibility(View.INVISIBLE);
        }
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        settings = Ui.kicker(c, c.getString(R.string.settings), 13, Ui.MUTED);
        settings.setFocusable(true);
        settings.setClickable(true);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(Ui.dp(c, Ui.S3), 0, Ui.dp(c, Ui.S3), 0);
        settings.setBackground(Ui.pillBackground(c));
        settings.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onSettings();
            }
        });
        searchAll = Ui.kicker(c, "Search all", 13, Ui.MUTED);
        searchAll.setFocusable(true);
        searchAll.setClickable(true);
        searchAll.setGravity(Gravity.CENTER);
        searchAll.setPadding(Ui.dp(c, Ui.S3), 0, Ui.dp(c, Ui.S3), 0);
        searchAll.setBackground(Ui.pillBackground(c));
        searchAll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onSearchAll();
            }
        });
        header.addView(searchAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 40)));
        // Themes: straight to the theme picker (same screen as Settings > Theme); header order Search all, Themes, Settings
        themes = Ui.kicker(c, "Themes", 13, Ui.MUTED);
        themes.setFocusable(true);
        themes.setClickable(true);
        themes.setGravity(Gravity.CENTER);
        themes.setPadding(Ui.dp(c, Ui.S3), 0, Ui.dp(c, Ui.S3), 0);
        themes.setBackground(Ui.pillBackground(c));
        themes.setId(View.generateViewId());
        themes.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getContext().startActivity(new android.content.Intent(getContext(), com.goomero.cleartv.theme.ThemeActivity.class));
            }
        });
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 40));
        thLp.leftMargin = Ui.dp(c, Ui.S2);
        header.addView(themes, thLp);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 40));
        setLp.leftMargin = Ui.dp(c, Ui.S2);
        header.addView(settings, setLp);
        col.addView(header);

        spacerTop = new View(c);
        col.addView(spacerTop, new LinearLayout.LayoutParams(1, 0, 1));
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // the service tiles live in the rows column so the theme can order them among the content rows
        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.addView(row, servicesLp());
        col.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacerBottom = new View(c);
        col.addView(spacerBottom, new LinearLayout.LayoutParams(1, 0, 1));

        LinearLayout footer = new LinearLayout(c);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(Ui.dp(c, Ui.S1), 0, Ui.dp(c, Ui.S1), 0);
        status = Ui.kicker(c, "", 11, Ui.SOFT);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        footer.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView hint = Ui.oneLine(Ui.kicker(c, c.getString(R.string.home_hint), 11, Ui.SOFT));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.leftMargin = Ui.dp(c, Ui.S3);
        footer.addView(hint, hintLp);
        col.addView(footer);
        bind();
    }

    void setStatus(String s) {
        status.setText(s.toUpperCase(java.util.Locale.ROOT));
    }

    private void bind() {
        Context c = getContext();
        row.removeAllViews();
        firstTile = null;
        for (final Services.Service s : Services.ALL) {
            LinearLayout tile = new LinearLayout(c);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setBackground(cardBackground(c));
            int p = Ui.dp(c, Ui.S1);
            tile.setPadding(p, p, p, Ui.dp(c, Ui.S2));

            int brandColor = icons.colorFor(s.iconKey);
            if (brandColor == 0) brandColor = Ui.ACCENT;
            FrameLayout plate = new FrameLayout(c);
            plate.setBackground(plateBackground(c, brandColor));
            View stripe = new View(c);
            stripe.setBackground(Ui.rounded(brandColor, Ui.dp(c, 2)));
            plate.addView(stripe, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 3), Gravity.BOTTOM));
            ImageView logo = new ImageView(c);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setBackground(Ui.rounded(0, Ui.dp(c, 14)));
            logo.setClipToOutline(true);
            if (icons.bind(logo, s.iconKey)) {
                plate.addView(logo, new LayoutParams(Ui.dp(c, LOGO), Ui.dp(c, LOGO), Gravity.CENTER));
            } else {
                TextView initial = Ui.kicker(c, s.name.substring(0, 1), 44, TileIcons.textOn(brandColor));
                plate.addView(initial, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            }
            tile.addView(plate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            TextView t = Ui.text(c, s.name.toUpperCase(java.util.Locale.ROOT), 17, Ui.TEXT);
            t.setTypeface(Ui.display(c));
            t.setLetterSpacing(0.12f);
            Ui.oneLine(t);
            t.setPadding(Ui.dp(c, Ui.S1), 0, Ui.dp(c, Ui.S1), 0);
            tile.addView(t, Ui.below(c, Ui.S2));
            TextView sub = Ui.kicker(c, s.tagline, 11, Ui.SOFT);
            Ui.oneLine(sub);
            tileSubs.put(s.id, sub);
            taglines.put(s.id, s.tagline);
            sub.setPadding(Ui.dp(c, Ui.S1), 0, Ui.dp(c, Ui.S1), 0);
            tile.addView(sub, Ui.below(c, Ui.S_HALF));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, TILE_H), 1);
            int m = Ui.dp(c, Ui.S1);
            lp.setMargins(m, m, m, m);
            row.addView(tile, lp);
            Ui.focusScale(tile);
            tile.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onOpen(s);
                }
            });
            tile.setOnKeyListener(new OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent e) {
                    if (holdOk(v, keyCode, e, new Runnable() { public void run() { cb.onServiceMenu(s); } })) return true;
                    if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                    if (e.getAction() == KeyEvent.ACTION_UP) cb.onServiceMenu(s);
                    return true;
                }
            });
            tile.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    cb.onServiceMenu(s);
                    return true;
                }
            });
            if (firstTile == null) firstTile = tile;
        }
        // up from a card goes to Settings; down stays on the row
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setId(View.generateViewId());
        }
        if (settings.getId() == View.NO_ID) settings.setId(View.generateViewId());
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setNextFocusUpId(settings.getId());
            v.setNextFocusDownId(v.getId());
        }
        settings.setNextFocusDownId(firstTile.getId());
        if (searchAll.getId() == View.NO_ID) searchAll.setId(View.generateViewId());
        searchAll.setNextFocusDownId(firstTile.getId());
        themes.setNextFocusDownId(firstTile.getId());
    }

    /**
     * Health notices under the service tiles: a quiet line ("No answer · 2:05", details in Settings > Services status) instead of the
     * tagline when the last background check of that service failed twice.
     */
    void setNotices(java.util.Map<String, String> notices) {
        for (java.util.Map.Entry<String, TextView> e : tileSubs.entrySet()) {
            String n = notices.get(e.getKey());
            e.getValue().setText(n != null ? n : taglines.get(e.getKey()));
            e.getValue().setTextColor(n != null ? Ui.AMBER : Ui.SOFT);
        }
    }

    /** The Continue watching and Favourites rows under the tiles (hidden when both are empty). */
    void setRows(java.util.List<Card> cont, java.util.List<Card> favs) {
        Context c = getContext();
        // the focused card may be the one just removed: remember where focus was so it lands on a neighbour
        int focusRow = -1, focusCol = -1;
        View had = rows.findFocus();
        if (had != null) {
            int r = 0;
            for (int i = 0; i < rows.getChildCount(); i++) {
                View ch = rows.getChildAt(i);
                if (!(ch instanceof android.widget.HorizontalScrollView)) continue;
                ViewGroup line = (ViewGroup) ((ViewGroup) ch).getChildAt(0);
                for (int j = 0; j < line.getChildCount(); j++) {
                    View card = line.getChildAt(j);
                    if (card == had || (card instanceof ViewGroup && had.getParent() != null && isInside(card, had))) {
                        focusRow = r;
                        focusCol = j;
                    }
                }
                r++;
            }
        }
        rows.removeAllViews();
        rowHeadings.clear();
        boundArt.clear();
        // rows the theme hides are left out here only; their data stays
        if (!Ui.HOME_ROWS.contains("continue")) cont = java.util.Collections.emptyList();
        if (!Ui.HOME_ROWS.contains("watchlist")) favs = java.util.Collections.emptyList();
        boolean any = !cont.isEmpty() || !favs.isEmpty();
        // with rows the service cards get shorter and the page starts at the top; without, they sit centred
        int tileH = Ui.dp(c, any ? TILE_H_ROWS : TILE_H);
        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = row.getChildAt(i).getLayoutParams();
            if (lp.height != tileH) {
                lp.height = tileH;
                row.getChildAt(i).setLayoutParams(lp);
            }
        }
        ((LinearLayout.LayoutParams) spacerTop.getLayoutParams()).weight = any ? 0 : 1;
        spacerTop.getLayoutParams().height = any ? Ui.dp(c, Ui.S2) : 0;
        spacerTop.requestLayout();
        if (!any) {
            rows.addView(row, servicesLp());
            linkServices(settings.getId(), View.NO_ID);
            if (focusRow >= 0 && firstTile != null) firstTile.requestFocus();
            return;
        }
        if (art == null) {
            art = new com.goomero.cleartv.tv.Images(c, Ui.dp(c, CARD_W), new Runnable() {
                @Override
                public void run() {
                    for (java.util.Map.Entry<ImageView, String> e : boundArt.entrySet()) {
                        android.graphics.Bitmap b = art.get(e.getValue());
                        if (b != null) e.getKey().setImageBitmap(b);
                    }
                }
            });
        }
        // rows in the theme's order; UP from a row goes to the row above (Settings above the top one), DOWN from
        // the service tiles to the first card of the row below them
        int above = settings.getId(), tilesUp = settings.getId(), tilesDown = View.NO_ID;
        boolean tilesPlaced = false;
        java.util.List<View> firsts = new java.util.ArrayList<>();
        for (String id : Ui.HOME_ROWS) {
            View first = null;
            if (id.equals("services")) {
                rows.addView(row, servicesLp());
                tilesUp = above;
                tilesPlaced = true;
                above = row.getChildAt(0).getId();
                continue;
            }
            if (id.equals("continue") && !cont.isEmpty()) first = addRow(c, "Continue watching", cont, above);
            else if (id.equals("watchlist") && !favs.isEmpty()) first = addRow(c, "Watchlist", favs, above);
            if (first == null) continue;
            firsts.add(first);
            if (tilesPlaced && tilesDown == View.NO_ID) tilesDown = first.getId();
            above = first.getId();
        }
        linkServices(tilesUp, tilesDown);
        // Settings and Search all lead down to whatever row is on top
        View top = rows.getChildAt(0) == row ? firstTile : (firsts.isEmpty() ? firstTile : firsts.get(0));
        if (top != null) {
            settings.setNextFocusDownId(top.getId());
            searchAll.setNextFocusDownId(top.getId());
            themes.setNextFocusDownId(top.getId());
        }
        if (focusRow >= 0) {
            java.util.List<ViewGroup> lines = new java.util.ArrayList<>();
            for (int i = 0; i < rows.getChildCount(); i++) {
                View ch = rows.getChildAt(i);
                if (ch instanceof android.widget.HorizontalScrollView) lines.add((ViewGroup) ((ViewGroup) ch).getChildAt(0));
            }
            if (lines.isEmpty()) {
                if (firstTile != null) firstTile.requestFocus();
            } else {
                ViewGroup line = lines.get(Math.min(focusRow, lines.size() - 1));
                line.getChildAt(Math.min(focusCol, line.getChildCount() - 1)).requestFocus();
            }
        }
    }

    private LinearLayout.LayoutParams servicesLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Focus links of the service tiles: UP to {@code up}, DOWN to {@code down} (or stay on the row). */
    private void linkServices(int up, int down) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setNextFocusUpId(up);
            v.setNextFocusDownId(down != View.NO_ID ? down : v.getId());
        }
        if (firstTile != null) {
            settings.setNextFocusDownId(firstTile.getId());
            searchAll.setNextFocusDownId(firstTile.getId());
            themes.setNextFocusDownId(firstTile.getId());
        }
    }

    private static boolean isInside(View ancestor, View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    private final java.util.List<TextView> rowHeadings = new java.util.ArrayList<>();

    /**
     * The page's scroller. Home is laid out for one screen: when the content comes out a little taller than the
     * viewport (a few dp of gaps), the flexible gaps (space above the tiles, space above each row heading) give
     * way first, so nothing scrolls. Only content that is really taller than the screen (several rows) scrolls,
     * and while everything fits, focus moves never nudge the page.
     */
    private final class FitScroll extends android.widget.ScrollView {
        FitScroll(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            View child = getChildAt(0);
            if (child == null) return;
            int over = child.getMeasuredHeight() - getMeasuredHeight();
            if (over <= 0) return;
            boolean changed = false;
            ViewGroup.LayoutParams sp = spacerTop.getLayoutParams();
            if (over > 0 && sp.height > 0) {
                int take = Math.min(over, sp.height);
                sp.height -= take; // direct field change: we are already measuring
                over -= take;
                changed = true;
            }
            int minTop = Ui.dp(getContext(), Ui.S1);
            for (TextView h : rowHeadings) {
                if (over <= 0) break;
                int take = Math.min(over, h.getPaddingTop() - minTop);
                if (take <= 0) continue;
                h.setPadding(h.getPaddingLeft(), h.getPaddingTop() - take, h.getPaddingRight(), h.getPaddingBottom());
                over -= take;
                changed = true;
            }
            if (changed) super.onMeasure(widthSpec, heightSpec);
        }

        private boolean fits() {
            View child = getChildAt(0);
            return child == null || child.getHeight() <= getHeight();
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
            if (fits()) return 0;
            // keep the focused card (and its 4% zoom) clear of the TV-safe margin: ScrollView alone stops with
            // the card's edge on the screen's edge, which cut the last row's label off. The bottom row scrolls the
            // page to its end, so the footer shows too.
            android.graphics.Rect r = new android.graphics.Rect(rect);
            int m = Ui.dp(getContext(), Ui.SAFE_Y + Ui.S3);
            r.top -= m;
            r.bottom += m;
            return super.computeScrollDeltaToGetChildRectOnScreen(r);
        }

        @Override
        public void scrollTo(int x, int y) {
            super.scrollTo(x, fits() ? 0 : y);
        }
    }

    /** A row of cards; UP from any of them goes to {@code upId} (the service tiles, or the row above). */
    private View addRow(Context c, String heading, java.util.List<Card> cards, int upId) {
        TextView h = Ui.kicker(c, heading, 12, Ui.SOFT);
        h.setPadding(Ui.dp(c, Ui.S1), Ui.dp(c, Ui.ROW_GAP + Ui.S1), 0, Ui.dp(c, Ui.S1));
        rowHeadings.add(h);
        rows.addView(h);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(line);
        rows.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View first = null;
        for (final Card card : cards) {
            View v = card(c, card);
            v.setId(View.generateViewId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, CARD_W), ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = Ui.dp(c, Ui.S1);
            lp.setMargins(m, m, m, m);
            v.setNextFocusUpId(upId);
            line.addView(v, lp);
            if (first == null) first = v;
        }
        return first;
    }

    private View card(Context c, final Card card) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(cardBackground(c));
        int p = Ui.dp(c, Ui.S_HALF + 2);
        v.setPadding(p, p, p, Ui.dp(c, Ui.S1));
        FrameLayout pic = new FrameLayout(c);
        pic.setBackground(Ui.rounded(Ui.DEEP, Ui.dp(c, 4)));
        pic.setClipToOutline(true);
        ImageView img = new ImageView(c);
        img.setScaleType(card.item.kind == com.goomero.cleartv.tv.Item.CHANNEL ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!card.item.image.isEmpty()) {
            boundArt.put(img, card.item.image);
            android.graphics.Bitmap b = art.get(card.item.image);
            if (b != null) img.setImageBitmap(b);
        }
        int brand = icons.colorFor(serviceIcon(card.service));
        if (brand == 0) brand = Ui.ACCENT;
        if (card.progress > 0) {
            View track = new View(c);
            track.setBackgroundColor(0x99000000);
            pic.addView(track, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM));
            View bar = new View(c);
            bar.setBackgroundColor(brand);
            bar.setPivotX(0);
            bar.setScaleX(Math.max(0.03f, card.progress));
            pic.addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM));
        }
        v.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, CARD_IMG_H)));
        TextView t = Ui.oneLine(Ui.text(c, card.item.title, 14, Ui.TEXT));
        t.setPadding(Ui.dp(c, Ui.S_HALF), 0, Ui.dp(c, Ui.S_HALF), 0);
        v.addView(t, Ui.below(c, Ui.S1));
        TextView sub = Ui.oneLine(Ui.kicker(c, card.serviceName, 10, brand));
        sub.setPadding(Ui.dp(c, Ui.S_HALF), 0, Ui.dp(c, Ui.S_HALF), 0);
        v.addView(sub, Ui.below(c, Ui.S_HALF));
        Ui.focusScale(v);
        v.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View x) {
                cb.onOpenEntry(card.service, card.item);
            }
        });
        v.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View x) {
                cb.onEntryMenu(card.service, card.item, card.favorite);
                return true;
            }
        });
        v.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View x, int keyCode, KeyEvent e) {
                if (holdOk(x, keyCode, e, new Runnable() { public void run() { cb.onEntryMenu(card.service, card.item, card.favorite); } }))
                    return true;
                if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) cb.onEntryMenu(card.service, card.item, card.favorite);
                return true;
            }
        });
        return v;
    }


    private static android.graphics.drawable.Drawable cardBackground(Context c) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable f = Ui.rounded(Ui.SURFACE, Ui.dp(c, Ui.RADIUS));
        f.setStroke(Ui.dp(c, Ui.RING), Ui.ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{android.R.attr.state_pressed}, f);
        s.addState(new int[]{}, Ui.card(c, Ui.CARD_FILL, Ui.LINE_SOFT));
        return s;
    }

    /** Logo plate background: a dark wash of the brand colour (the 3 dp brand stripe is a child view). */
    private static android.graphics.drawable.Drawable plateBackground(Context c, int brand) {
        float r = Ui.dp(c, 4);
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(brand & 0x00FFFFFF) | 0x52000000, (brand & 0x00FFFFFF) | 0x1F000000});
        wash.setCornerRadius(r);
        GradientDrawable base = Ui.rounded(Ui.DEEP, r);
        return new LayerDrawable(new android.graphics.drawable.Drawable[]{base, wash});
    }

    void focusDefault() {
        if (firstTile != null) firstTile.requestFocus();
    }

    /** Draws the still backdrop for the current size (once; about 2 MB at 960x540 px, released by {@link #release}). */
    void prepare() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (getWidth() > 0) prepare();
                }
            });
            return;
        }
        if (backdrop != null) return;
        // half resolution is plenty for soft gradients and sub-pixel stars; the drawable scales it with filtering
        int bw = Math.max(1, w / 2), bh = Math.max(1, h / 2);
        backdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        drawBackdrop(new Canvas(backdrop), bw, bh);
        BitmapDrawable d = new BitmapDrawable(getResources(), backdrop);
        d.setFilterBitmap(true);
        setBackground(d);
    }

    /** Frees the backdrop bitmap (a service is showing, or memory is low). */
    void release() {
        if (art != null) art.trim();
        if (backdrop == null) return;
        setBackgroundColor(Ui.BG);
        backdrop.recycle();
        backdrop = null;
    }

    /** Home background: graphite with two faint nebula glows and a sparse field of pale stars. */
    static void drawBackdrop(Canvas cv, int w, int h) {
        cv.drawColor(Ui.BG);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = Math.max(w, h) * 0.55f;
        if ("plain".equals(Ui.BACKDROP)) return;
        if ("gradient".equals(Ui.BACKDROP)) {
            // two soft glows of the theme accent, no stars
            int a = Ui.ACCENT & 0x00FFFFFF;
            p.setShader(new RadialGradient(w * 0.82f, h * 0.12f, rad * 1.1f, a | (Ui.LIGHT ? 0x26000000 : 0x2E000000), a, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.1f, h * 0.95f, rad, a | 0x1A000000, a, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            return;
        }
        p.setShader(new RadialGradient(w * 0.78f, h * 0.18f, rad, 0x332B3A4A, 0x002B3A4A, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.12f, h * 0.85f, rad, 0x263A2B4A, 0x003A2B4A, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // same generator and density as stars.ts (LCG seed 4211, one star per 5200 px^2 of the CSS-pixel area)
        int seed = 4211;
        int count = Math.min(280, Math.round(w * 2f * h * 2f / 5200f / 4f));
        float scale = w / 960f;
        for (int i = 0; i < count; i++) {
            seed = seed * 1664525 + 1013904223;
            float x = (seed >>> 8) / 16777216f;
            seed = seed * 1664525 + 1013904223;
            float y = (seed >>> 8) / 16777216f;
            seed = seed * 1664525 + 1013904223;
            float size = .3f + ((seed >>> 8) / 16777216f) * .6f;
            seed = seed * 1664525 + 1013904223; // phase (unused: the field is still)
            seed = seed * 1664525 + 1013904223;
            float glow = .12f + ((seed >>> 8) / 16777216f) * .3f;
            p.setColor(0xFFCBD5DF);
            p.setAlpha(Math.round(255 * Math.min(1f, glow * 1.6f)));
            cv.drawCircle(x * w, y * h, Math.max(0.5f, size * scale * 1.4f), p);
        }
    }
}
