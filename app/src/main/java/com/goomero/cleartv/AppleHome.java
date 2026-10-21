package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "apple" layout engine's home, built to the tvOS home screen's structure (see docs/THEMES.md, design notes):
 * <ul>
 * <li>one full-screen backdrop (the Top Shelf image of whatever is focused) that fades into a dark base through one
 * long scrim; it is the only image layer behind the screen;</li>
 * <li>the Top Shelf, the upper two thirds: the focused app's content as a sectioned carousel of wide 16:9 images
 * (that service's Continue watching and Watchlist), with a label only under the focused item;</li>
 * <li>below it an app grid in rows of five icons (unfocused 150x90 dp, the 300x180 px tvOS icon at 1080p): the four
 * services and Search on the first row, Themes and Settings on the second, which peeks in at the bottom; the name
 * appears only under the focused icon;</li>
 * <li>focus is a clean scale-up with a large soft shadow and a specular sweep; no borders, no outlines, no skew.</li>
 * </ul>
 */
final class AppleHome extends HomeBase {
    private static final int ICON_W = 150, ICON_H = 90, GAP = 28, SHELF_H = 352, CARD_W = 228, CARD_H = 128;
    private final ImageView backdrop;
    private final TextView kicker, title, desc, clock, status, empty;
    private final LinearLayout stage, carousel, grid;
    private final HorizontalScrollView carouselScroll;
    private final java.util.Map<String, String> notices = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> labels = new java.util.HashMap<>();
    private final java.util.HashMap<ImageView, String> boundArt = new java.util.HashMap<>();
    private final java.util.List<View> rowOne = new java.util.ArrayList<>();
    private java.util.List<HomeView.Card> cont = java.util.Collections.emptyList(), favs = java.util.Collections.emptyList();
    private com.goomero.cleartv.tv.Images art, heroArt;
    private String heroUrl;
    private Services.Service shelfFor;
    private View firstIcon, shelfOwner, themesIcon;

    AppleHome(Context c, HomeView.Callbacks callbacks, TileIcons icons) {
        super(c, callbacks, icons);
        setBackgroundColor(Ui.BG);
        setClipChildren(false);

        // the one backdrop layer, full screen, and one long scrim: clear at the top, the dark base from 60% down
        backdrop = new ImageView(c);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(backdrop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int bg = Ui.BG & 0x00FFFFFF;
        View scrimL = new View(c);
        scrimL.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{bg | 0xB3000000, bg | 0x4D000000, 0x00000000}));
        addView(scrimL, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrimB = new View(c);
        scrimB.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, bg | 0x1A000000, bg | 0x80000000, bg | 0xE6000000, Ui.BG}));
        addView(scrimB, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // everything else moves together: the shelf and the grid slide up when the second grid row takes focus
        stage = new LinearLayout(c);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setClipChildren(false);
        stage.setPadding(Ui.dp(c, Ui.SAFE_X), Ui.dp(c, Ui.SAFE_Y + 8), Ui.dp(c, Ui.SAFE_X), 0);
        addView(stage, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout shelf = new FrameLayout(c);
        shelf.setClipChildren(false);
        stage.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, SHELF_H - Ui.SAFE_Y - 8)));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        kicker = Ui.oneLine(Ui.kicker(c, "", 11, 0xCCFFFFFF));
        text.addView(kicker);
        title = Ui.lines(Ui.text(c, "", 28, 0xFFFFFFFF), 2);
        title.setTypeface(Ui.display(c));
        text.addView(title, Ui.below(c, Ui.S_HALF));
        desc = Ui.oneLine(Ui.text(c, "", 14, 0xD9FFFFFF));
        text.addView(desc, Ui.below(c, Ui.S1));
        for (TextView t : new TextView[]{kicker, title, desc}) t.setShadowLayer(Ui.dp(c, 8), 0, Ui.dp(c, 1), 0xB3000000);
        // a fixed box above the carousel: two title lines and a subtitle always fit, longer text is ellipsized
        shelf.addView(text, new FrameLayout.LayoutParams(Ui.dp(c, 600), Ui.dp(c, 118), Gravity.TOP | Gravity.START));
        carouselScroll = new HorizontalScrollView(c);
        carouselScroll.setHorizontalScrollBarEnabled(false);
        carouselScroll.setClipChildren(false);
        carouselScroll.setClipToPadding(false);
        carouselScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        carousel = new LinearLayout(c);
        carousel.setOrientation(LinearLayout.HORIZONTAL);
        carousel.setClipChildren(false);
        carousel.setPadding(0, Ui.dp(c, 10), Ui.dp(c, Ui.SAFE_X), 0);
        carouselScroll.addView(carousel);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        cl.topMargin = Ui.dp(c, 126);
        shelf.addView(carouselScroll, cl);
        empty = Ui.lines(Ui.text(c, "", 14, 0xB3FFFFFF), 2);
        empty.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(Ui.dp(c, 520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        el.topMargin = Ui.dp(c, 140);
        shelf.addView(empty, el);

        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        stage.addView(grid, Ui.below(c, Ui.S2));
        status = Ui.oneLine(Ui.kicker(c, "", 10, 0x99FFFFFF));
        stage.addView(status, Ui.below(c, Ui.S2));

        clock = Ui.oneLine(Ui.text(c, "", 18, 0xFFFFFFFF));
        clock.setTypeface(Ui.display(c));
        clock.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
        LayoutParams tp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tp.topMargin = Ui.dp(c, Ui.SAFE_Y);
        tp.rightMargin = Ui.dp(c, Ui.SAFE_X);
        addView(clock, tp);

        buildGrid(c);
        tick.run();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date()));
            removeCallbacks(this);
            postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tick.run();
    }

    private static int radius(Context c) {
        return Ui.dp(c, Math.max(6, Math.min(Ui.RADIUS, 12)));
    }

    // ---- the app grid ----------------------------------------------------------------------------------------

    private void buildGrid(Context c) {
        LinearLayout row = newRow(c);
        for (final Services.Service s : Services.ALL) {
            int brand = icons.colorFor(s.iconKey);
            if (brand == 0) brand = Ui.ACCENT;
            View tile = appIcon(c, row, s.name, s.id, brand, icons.bitmap(s.iconKey), -1, s.name.substring(0, 1));
            tile.setOnClickListener(new OnClickListener() { public void onClick(View v) { cb.onOpen(s); } });
            menuKeys(tile, new Runnable() { public void run() { cb.onServiceMenu(s); } });
            final View t = tile;
            focusHook(tile, new Runnable() { public void run() { showService(s, t); } }, 0);
            rowOne.add(tile);
            if (firstIcon == null) firstIcon = tile;
        }
        View search = appIcon(c, row, "Search all", null, 0xFF3A3D42, null, Glyph.SEARCH, null);
        search.setOnClickListener(new OnClickListener() { public void onClick(View v) { cb.onSearchAll(); } });
        focusHook(search, new Runnable() { public void run() { showTool("SEARCH", "Search all", "Find a title in every service at once."); } }, 0);
        rowOne.add(search);
        LinearLayout two = newRow(c);
        themesIcon = appIcon(c, two, "Themes", null, 0xFF3A3D42, null, Glyph.THEMES, null);
        themesIcon.setOnClickListener(new OnClickListener() { public void onClick(View v) { openThemes(); } });
        focusHook(themesIcon, new Runnable() { public void run() { showTool("THEMES", "Themes", "Change the look and layout, or import a theme from your phone."); } }, 1);
        View settings = appIcon(c, two, "Settings", null, 0xFF3A3D42, null, Glyph.SETTINGS, null);
        settings.setOnClickListener(new OnClickListener() { public void onClick(View v) { cb.onSettings(); } });
        focusHook(settings, new Runnable() { public void run() { showTool("SETTINGS", "Settings", "Accounts, playback, blocking and the phone link."); } }, 1);
        for (View v : rowOne) v.setNextFocusDownId(themesIcon.getId());
        themesIcon.setNextFocusUpId(firstIcon.getId());
        settings.setNextFocusUpId(rowOne.get(1).getId());
        showService(Services.ALL[0], firstIcon);
    }

    private LinearLayout newRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** An app icon: an opaque rounded tile (brand colour or system grey), the logo or glyph centred, its name below. */
    private View appIcon(Context c, LinearLayout row, String name, String noticeKey, int fill, Bitmap logo, int glyph, String initial) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        final FrameLayout tile = new FrameLayout(c);
        tile.setId(View.generateViewId());
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(name);
        int r = radius(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(fill), fill});
        bg.setCornerRadius(r);
        tile.setBackground(bg);
        tile.setClipToOutline(true);
        if (logo != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(logo);
            iv.setBackground(Ui.rounded(0, Ui.dp(c, 10)));
            iv.setClipToOutline(true);
            tile.addView(iv, new FrameLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54), Gravity.CENTER));
        } else if (glyph >= 0) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(new Glyph(glyph, 0xFFFFFFFF));
            tile.addView(iv, new FrameLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36), Gravity.CENTER));
        } else {
            TextView t = Ui.kicker(c, initial, 36, TileIcons.textOn(fill));
            tile.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        tile.addView(shine(c), new FrameLayout.LayoutParams(Ui.dp(c, 70), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        cell.addView(tile, new LinearLayout.LayoutParams(Ui.dp(c, ICON_W), Ui.dp(c, ICON_H)));
        TextView label = Ui.oneLine(Ui.text(c, name, 14, 0xFFFFFFFF));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        label.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
        cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = Ui.dp(c, 14);
        if (noticeKey != null) labels.put(noticeKey, label);
        tile.setTag(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, ICON_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(c, GAP);
        row.addView(cell, lp);
        return tile;
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 22), g = Math.min(255, ((c >> 8) & 0xFF) + 22), b = Math.min(255, (c & 0xFF) + 22);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The specular highlight: a soft diagonal band that sweeps across once when the item takes focus. */
    private static View shine(Context c) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}));
        v.setRotation(16);
        v.setScaleY(1.6f);
        v.setAlpha(0f);
        v.setTag("shine");
        return v;
    }

    /** Focus: grows about 10% (tvOS unfocused 300 to focused 370 px is more; 1.1 keeps rows clear), rises with a
     *  large soft shadow, sweeps the highlight once, shows the label. Grid row 2 slides the whole stage up. */
    private void focusHook(final View tile, final Runnable onFocus, final int gridRow) {
        tile.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                lift(v, has);
                Object l = v.getTag();
                if (l instanceof TextView) ((TextView) l).animate().alpha(has ? 1f : 0f).setDuration(160).start();
                if (!has) return;
                Ui.unclip(v);
                sweep(v);
                stageTo(gridRow == 1 ? -Ui.dp(getContext(), ICON_H + 40) : 0, 1f);
                onFocus.run();
            }
        });
    }

    private void lift(View v, boolean has) {
        float s = has ? Math.max(1.1f, Ui.FOCUS_SCALE) : 1f;
        v.setRotationX(0f);
        v.setRotationY(0f);
        v.animate().scaleX(s).scaleY(s).translationZ(has ? Ui.dp(getContext(), 24) : 0)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).setDuration(200).start();
    }

    private void sweep(View v) {
        if (!(v instanceof ViewGroup)) return;
        View sh = ((ViewGroup) v).findViewWithTag("shine");
        if (sh == null) return;
        sh.setAlpha(1f);
        sh.setTranslationX(-Ui.dp(getContext(), 80));
        sh.animate().translationX(v.getWidth() + Ui.dp(getContext(), 20)).setDuration(700).withEndAction(new Runnable() {
            public void run() { /* rests off the edge */ }
        }).start();
    }

    private void stageTo(float y, float shelfAlpha) {
        stage.animate().translationY(y).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    // ---- the Top Shelf ---------------------------------------------------------------------------------------

    /** A service icon took focus: the shelf shows its own content (Continue watching, then Watchlist). */
    private void showService(Services.Service s, View owner) {
        shelfOwner = owner;
        java.util.List<HomeView.Card> mine = new java.util.ArrayList<>();
        for (String id : contentRows(cont, favs)) {
            for (HomeView.Card k : id.equals("continue") ? cont : favs) if (k.service.equals(s.id)) mine.add(k);
        }
        int brand = icons.colorFor(s.iconKey);
        if (brand == 0) brand = Ui.ACCENT;
        kicker.setText("TOP SHELF");
        title.setText(s.name);
        String n = notices.get(s.id);
        desc.setText(n != null ? n : s.tagline);
        desc.setTextColor(n != null ? Ui.AMBER : 0xD9FFFFFF);
        if (s != shelfFor || carousel.getChildCount() != mine.size()) fillCarousel(mine, owner);
        shelfFor = s;
        if (mine.isEmpty()) {
            empty.setText("Nothing to continue yet. Open " + s.name + " and what you watch or save shows up here.");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
        heroUrl = null;
        for (HomeView.Card k : mine) if (!k.item.image.isEmpty()) { heroUrl = k.item.image; break; }
        if (heroUrl != null && setHero(heroUrl)) return;
        backdrop.setImageDrawable(new BrandArt(brand, icons.bitmap(s.iconKey), getResources().getDisplayMetrics().density));
    }

    private void showTool(String k, String t, String d) {
        shelfOwner = null;
        shelfFor = null;
        carousel.removeAllViews();
        boundArt.clear();
        empty.setVisibility(View.GONE);
        kicker.setText(k);
        title.setText(t);
        desc.setText(d);
        desc.setTextColor(0xD9FFFFFF);
        heroUrl = null;
        backdrop.setImageDrawable(new BrandArt(0xFF4A4F57, null, getResources().getDisplayMetrics().density));
    }

    private boolean setHero(String url) {
        heroUrl = url;
        ensureArt(getContext());
        Bitmap b = heroArt.get(url);
        if (b != null) backdrop.setImageBitmap(b);
        return b != null;
    }

    private void fillCarousel(java.util.List<HomeView.Card> mine, final View owner) {
        Context c = getContext();
        carousel.removeAllViews();
        boundArt.clear();
        carouselScroll.scrollTo(0, 0);
        if (mine.isEmpty()) return;
        ensureArt(c);
        int w = Math.round(CARD_W * Ui.TILE_SCALE), h = Math.round(CARD_H * Ui.TILE_SCALE);
        View first = null;
        for (final HomeView.Card card : mine) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            FrameLayout pic = new FrameLayout(c);
            pic.setId(View.generateViewId());
            pic.setFocusable(true);
            pic.setClickable(true);
            int r = radius(c);
            pic.setBackground(Ui.rounded(0xFF1C1E21, r));
            if (Ui.RING >= 3) pic.setForeground(ring(c, r)); // high-contrast themes keep a visible ring
            pic.setClipToOutline(true);
            ImageView img = new ImageView(c);
            img.setScaleType(card.item.kind == com.goomero.cleartv.tv.Item.CHANNEL ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            pic.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (!card.item.image.isEmpty()) {
                boundArt.put(img, card.item.image);
                Bitmap b = art.get(card.item.image);
                if (b != null) img.setImageBitmap(b);
            }
            if (card.progress > 0) {
                View track = new View(c);
                track.setBackgroundColor(0x66000000);
                pic.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM));
                View bar = new View(c);
                bar.setBackgroundColor(0xFFFFFFFF);
                bar.setPivotX(0);
                bar.setScaleX(Math.max(0.03f, card.progress));
                pic.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM));
            }
            pic.addView(shine(c), new FrameLayout.LayoutParams(Ui.dp(c, 90), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
            cell.addView(pic, new LinearLayout.LayoutParams(Ui.dp(c, w), Ui.dp(c, h)));
            final TextView label = Ui.oneLine(Ui.text(c, card.item.title, 14, 0xFFFFFFFF));
            label.setAlpha(0f);
            label.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
            cell.addView(label, Ui.below(c, Ui.S2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, w), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(c, GAP);
            carousel.addView(cell, lp);
            pic.setNextFocusDownId(owner.getId());
            pic.setOnClickListener(new OnClickListener() { public void onClick(View v) { cb.onOpenEntry(card.service, card.item); } });
            menuKeys(pic, new Runnable() { public void run() { cb.onEntryMenu(card.service, card.item, card.favorite); } });
            pic.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    lift(v, has);
                    label.animate().alpha(has ? 1f : 0f).setDuration(160).start();
                    if (!has) return;
                    Ui.unclip(v);
                    sweep(v);
                    stageTo(0, 1f);
                    kicker.setText((card.favorite ? "WATCHLIST \u00b7 " : "CONTINUE WATCHING \u00b7 ") + card.serviceName.toUpperCase(java.util.Locale.ROOT));
                    title.setText(card.item.title);
                    String d = !card.item.description.isEmpty() ? card.item.description : card.item.subtitle;
                    desc.setText(d);
                    desc.setTextColor(0xD9FFFFFF);
                    if (!card.item.image.isEmpty()) setHero(card.item.image);
                }
            });
            if (first == null) first = pic;
        }
        owner.setNextFocusUpId(first.getId());
    }

    private static android.graphics.drawable.Drawable ring(Context c, float r) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(r);
        f.setColor(0);
        f.setStroke(Ui.dp(c, Ui.RING), Ui.ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return s;
    }

    /** A service's Top Shelf art: a brand-coloured glow and the logo drawn large on the right, with soft echoes. */
    private static final class BrandArt extends android.graphics.drawable.Drawable {
        private final int brand;
        private final Bitmap logo;
        private final float d;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

        BrandArt(int brand, Bitmap logo, float density) {
            this.brand = brand;
            this.logo = logo;
            this.d = density;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height();
            int rgb = brand & 0x00FFFFFF;
            p.setShader(new android.graphics.LinearGradient(0, 0, w, h * 0.6f, new int[]{rgb | 0x14000000, rgb | 0x66000000},
                    null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            float cx = w * 0.74f, cy = h * 0.26f, rad = h * 0.55f;
            p.setShader(new android.graphics.RadialGradient(cx, cy, rad, new int[]{rgb | 0xB3000000, rgb | 0x33000000, 0x00000000},
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rad, p);
            p.setShader(null);
            if (logo == null) return;
            float s = 150 * d;
            android.graphics.RectF r = new android.graphics.RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            p.setAlpha(36);
            c.save();
            c.translate(-s * 0.9f, s * 0.35f);
            c.drawBitmap(logo, null, new android.graphics.RectF(r.left + s * 0.2f, r.top + s * 0.2f, r.right - s * 0.2f, r.bottom - s * 0.2f), p);
            c.restore();
            p.setAlpha(255);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, s * 0.22f, s * 0.22f, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(logo, null, r, p);
            c.restore();
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void ensureArt(Context c) {
        if (art != null) return;
        art = new com.goomero.cleartv.tv.Images(c, Ui.dp(c, CARD_W), new Runnable() {
            @Override
            public void run() {
                for (java.util.Map.Entry<ImageView, String> e : boundArt.entrySet()) {
                    Bitmap b = art.get(e.getValue());
                    if (b != null) e.getKey().setImageBitmap(b);
                }
            }
        });
        heroArt = new com.goomero.cleartv.tv.Images(c, Ui.dp(c, 640), new Runnable() {
            @Override
            public void run() {
                if (heroUrl == null) return;
                Bitmap b = heroArt.get(heroUrl);
                if (b != null) backdrop.setImageBitmap(b);
            }
        });
    }

    @Override
    void setStatus(String s) {
        status.setText(s.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    void setNotices(java.util.Map<String, String> n) {
        notices.clear();
        notices.putAll(n);
        for (java.util.Map.Entry<String, TextView> e : labels.entrySet()) {
            e.getValue().setTextColor(notices.containsKey(e.getKey()) ? Ui.AMBER : 0xFFFFFFFF);
        }
    }

    @Override
    void setRows(java.util.List<HomeView.Card> c, java.util.List<HomeView.Card> f) {
        cont = Ui.HOME_ROWS.contains("continue") ? c : java.util.Collections.<HomeView.Card>emptyList();
        favs = Ui.HOME_ROWS.contains("watchlist") ? f : java.util.Collections.<HomeView.Card>emptyList();
        boolean inShelf = carousel.findFocus() != null;
        Services.Service s = shelfFor != null ? shelfFor : Services.ALL[0];
        View owner = shelfOwner != null ? shelfOwner : firstIcon;
        shelfFor = null;
        if (shelfOwner != null || findFocus() == null) showService(s, owner);
        if (inShelf && carousel.findFocus() == null) owner.requestFocus();
    }

    @Override
    void focusDefault() {
        if (firstIcon != null) firstIcon.requestFocus();
    }

    @Override
    void prepare() {
        tick.run();
    }

    @Override
    void release() {
        if (art != null) art.trim();
        if (heroArt != null) heroArt.trim();
        backdrop.setImageDrawable(null);
    }
}
