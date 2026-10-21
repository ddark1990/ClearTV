package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The "rail" layout engine's home, in the style of Google TV and Netflix: a frosted sidebar on the left with Search,
 * every service, Themes and Settings; on the right a large featured banner showing the focused service or title,
 * and below it a four-column grid of landscape cards (service cards and the Continue watching / Watchlist rows in the
 * theme's order), each with its title and service under the art.
 */
final class RailHome extends HomeBase {
    private static final int RAIL_W = 236, BANNER_H = 214, COLS = 4;
    private final LinearLayout rail, grid;
    private final ImageView bannerImg;
    private final View bannerWash;
    private final TextView bKicker, bTitle, bDesc, status;
    private final ScrollView gridScroll;
    private final java.util.Map<String, String> notices = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> railLabels = new java.util.HashMap<>();
    private final java.util.HashMap<ImageView, String> boundArt = new java.util.HashMap<>();
    private com.goomero.cleartv.tv.Images art, bannerArt;
    private String bannerUrl;
    private View firstService;
    private Bitmap backdrop;
    private java.util.List<HomeView.Card> lastCont = java.util.Collections.emptyList(), lastFavs = java.util.Collections.emptyList();

    RailHome(Context c, HomeView.Callbacks callbacks, TileIcons icons) {
        super(c, callbacks, icons);
        setBackgroundColor(Ui.BG);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the sidebar
        rail = new LinearLayout(c);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(Ui.CARD_FILL); // frosted: the panel at the theme's card opacity over the backdrop
        rail.setPadding(Ui.dp(c, Ui.SAFE_X - Ui.S2), Ui.dp(c, Ui.SAFE_Y + Ui.S1), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.SAFE_Y));
        TextView word = Ui.oneLine(Ui.kicker(c, c.getString(R.string.app_name), 20, Ui.TEXT));
        word.setPadding(Ui.dp(c, Ui.S2), 0, 0, 0);
        rail.addView(word);
        TextView ver = Ui.oneLine(Ui.kicker(c, "v" + BuildConfig.VERSION_NAME, 10, Ui.SOFT));
        ver.setPadding(Ui.dp(c, Ui.S2), 0, 0, Ui.dp(c, Ui.S2));
        rail.addView(ver, Ui.below(c, Ui.S_HALF));
        railItem(c, null, Glyph.SEARCH, "Search all", new Runnable() { public void run() { cb.onSearchAll(); } }, null, null);
        TextView sk = Ui.oneLine(Ui.kicker(c, "Services", 10, Ui.SOFT));
        sk.setPadding(Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S2), 0, Ui.dp(c, Ui.S_HALF));
        rail.addView(sk);
        for (final Services.Service s : Services.ALL) {
            View v = railItem(c, s, -1, s.name, new Runnable() { public void run() { cb.onOpen(s); } },
                    new Runnable() { public void run() { cb.onServiceMenu(s); } }, s);
            if (firstService == null) firstService = v;
        }
        rail.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1));
        railItem(c, null, Glyph.THEMES, "Themes", new Runnable() { public void run() { openThemes(); } }, null, null);
        railItem(c, null, Glyph.SETTINGS, "Settings", new Runnable() { public void run() { cb.onSettings(); } }, null, null);
        status = Ui.lines(Ui.kicker(c, "", 9, Ui.SOFT), 2);
        status.setPadding(Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1), 0, 0);
        rail.addView(status);
        row.addView(rail, new LinearLayout.LayoutParams(Ui.dp(c, RAIL_W), ViewGroup.LayoutParams.MATCH_PARENT));

        // the content: featured banner, then the grid
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(c, Ui.S3), Ui.dp(c, Ui.SAFE_Y), Ui.dp(c, Ui.SAFE_X), 0);
        FrameLayout banner = new FrameLayout(c);
        float br = Ui.dp(c, Math.max(Ui.RADIUS, 8));
        banner.setBackground(Ui.rounded(0xFF0E1114, br)); // dark in every theme: the banner text is light
        banner.setClipToOutline(true);
        bannerImg = new ImageView(c);
        bannerImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.addView(bannerImg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bannerWash = new View(c);
        banner.addView(bannerWash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE60E1114, 0x660E1114, 0x00000000}));
        banner.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout bt = new LinearLayout(c);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.setGravity(Gravity.BOTTOM);
        int bp = Ui.dp(c, Ui.S3);
        bt.setPadding(bp, bp, bp, bp);
        // the banner sits on the deep fill whatever the theme, so its text is always light
        bKicker = Ui.oneLine(Ui.kicker(c, "", 11, 0xFFDDE2E6));
        bt.addView(bKicker);
        bTitle = Ui.lines(Ui.text(c, "", 28, 0xFFFFFFFF), 2);
        bTitle.setTypeface(Ui.display(c));
        bt.addView(bTitle, Ui.below(c, Ui.S_HALF));
        bDesc = Ui.lines(Ui.text(c, "", 14, 0xFFDDE2E6), 2);
        bt.addView(bDesc, Ui.below(c, Ui.S1));
        banner.addView(bt, new FrameLayout.LayoutParams(Ui.dp(c, 460), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        content.addView(banner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, BANNER_H)));
        gridScroll = new ScrollView(c);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setClipToPadding(false);
        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setPadding(0, 0, 0, Ui.dp(c, Ui.SAFE_Y + Ui.S2));
        gridScroll.addView(grid);
        content.addView(gridScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        rebuildGrid();
        serviceBanner(Services.ALL[0]);
    }

    /** A sidebar entry: an icon (service logo or line glyph) and a label; OK runs {@code click}. */
    private View railItem(Context c, Services.Service logoOf, int glyph, String label, final Runnable click, final Runnable menu,
                          final Services.Service heroOf) {
        LinearLayout v = new LinearLayout(c);
        v.setId(View.generateViewId());
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding(Ui.dp(c, Ui.S2), 0, Ui.dp(c, Ui.S1), 0);
        v.setBackground(Ui.focusBackground(c, 0x00000000, Ui.SURFACE));
        ImageView ic = new ImageView(c);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (logoOf != null) {
            ic.setBackground(Ui.rounded(0, Ui.dp(c, 5)));
            ic.setClipToOutline(true);
            if (!icons.bind(ic, logoOf.iconKey)) ic.setImageDrawable(new Glyph(Glyph.THEMES, Ui.MUTED));
        } else {
            ic.setImageDrawable(new Glyph(glyph, Ui.TEXT));
        }
        v.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
        TextView t = Ui.oneLine(Ui.text(c, label, 16, Ui.TEXT));
        t.setPadding(Ui.dp(c, Ui.S2), 0, 0, 0);
        v.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (heroOf != null) railLabels.put(heroOf.id, t);
        v.setOnClickListener(new OnClickListener() { public void onClick(View x) { click.run(); } });
        if (menu != null) menuKeys(v, menu);
        v.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View x, boolean has) {
                if (has && heroOf != null) serviceBanner(heroOf);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 42));
        lp.topMargin = Ui.dp(c, Ui.S_HALF);
        rail.addView(v, lp);
        return v;
    }

    private void serviceBanner(Services.Service s) {
        bannerUrl = null;
        bannerImg.setImageDrawable(null);
        int brand = icons.colorFor(s.iconKey);
        if (brand == 0) brand = Ui.ACCENT;
        bannerWash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TR_BL,
                new int[]{(brand & 0x00FFFFFF) | 0xB3000000, (brand & 0x00FFFFFF) | 0x33000000}));
        bKicker.setText("SERVICE");
        bTitle.setText(s.name);
        String n = notices.get(s.id);
        bDesc.setText(n != null ? n : s.tagline + " · OK opens it, hold OK for options");
    }

    private void cardBanner(HomeView.Card card) {
        bannerWash.setBackground(null);
        bKicker.setText(card.serviceName.toUpperCase(java.util.Locale.ROOT));
        bTitle.setText(card.item.title);
        bDesc.setText(!card.item.description.isEmpty() ? card.item.description : card.item.subtitle);
        bannerUrl = card.item.image.isEmpty() ? null : card.item.image;
        bannerImg.setImageDrawable(null);
        if (bannerUrl != null && bannerArt != null) {
            Bitmap b = bannerArt.get(bannerUrl);
            if (b != null) bannerImg.setImageBitmap(b);
        }
    }

    @Override
    void setStatus(String s) {
        status.setText(s.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    void setNotices(java.util.Map<String, String> n) {
        notices.clear();
        notices.putAll(n);
        for (java.util.Map.Entry<String, TextView> e : railLabels.entrySet())
            e.getValue().setTextColor(notices.containsKey(e.getKey()) ? Ui.AMBER : Ui.TEXT);
    }

    @Override
    void setRows(java.util.List<HomeView.Card> cont, java.util.List<HomeView.Card> favs) {
        lastCont = Ui.HOME_ROWS.contains("continue") ? cont : java.util.Collections.<HomeView.Card>emptyList();
        lastFavs = Ui.HOME_ROWS.contains("watchlist") ? favs : java.util.Collections.<HomeView.Card>emptyList();
        View had = grid.findFocus();
        rebuildGrid();
        if (had != null && grid.findFocus() == null && firstService != null) firstService.requestFocus();
    }

    /** The grid sections in the theme's row order: service cards, then card rows of four. */
    private void rebuildGrid() {
        Context c = getContext();
        grid.removeAllViews();
        boundArt.clear();
        for (String id : Ui.HOME_ROWS) {
            if (id.equals("services")) {
                heading(c, "Services");
                LinearLayout line = line(c);
                for (final Services.Service s : Services.ALL) line.addView(serviceCard(c, s), cell(c));
            } else {
                java.util.List<HomeView.Card> cards = id.equals("continue") ? lastCont : lastFavs;
                if (cards.isEmpty()) continue;
                ensureArt(c);
                heading(c, rowTitle(id));
                LinearLayout line = null;
                for (int i = 0; i < cards.size(); i++) {
                    if (i % COLS == 0) line = line(c);
                    line.addView(contentCard(c, cards.get(i)), cell(c));
                }
                for (int k = cards.size() % COLS; k > 0 && k < COLS; k++) line.addView(new View(c), cell(c));
            }
        }
    }

    private void heading(Context c, String s) {
        TextView h = Ui.oneLine(Ui.kicker(c, s, 12, Ui.SOFT));
        h.setPadding(Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.ROW_GAP), 0, Ui.dp(c, Ui.S1));
        grid.addView(h);
    }

    private LinearLayout line(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setClipChildren(false);
        grid.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private LinearLayout.LayoutParams cell(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        int m = Ui.dp(c, Ui.S1);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private LinearLayout cardShell(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setFocusable(true);
        v.setClickable(true);
        int p = Ui.dp(c, Ui.S_HALF + 2);
        v.setPadding(p, p, p, Ui.dp(c, Ui.S1));
        v.setBackground(Ui.focusBackground(c, Ui.CARD_FILL, Ui.SURFACE));
        Ui.focusScale(v);
        return v;
    }

    private View serviceCard(Context c, final Services.Service s) {
        LinearLayout v = cardShell(c);
        int brand = icons.colorFor(s.iconKey);
        if (brand == 0) brand = Ui.ACCENT;
        AspectFrame plate = new AspectFrame(c);
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{(brand & 0x00FFFFFF) | 0x66000000, (brand & 0x00FFFFFF) | 0x1A000000});
        wash.setCornerRadius(Ui.dp(c, 4));
        plate.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{Ui.rounded(Ui.DEEP, Ui.dp(c, 4)), wash}));
        ImageView logo = new ImageView(c);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setBackground(Ui.rounded(0, Ui.dp(c, 10)));
        logo.setClipToOutline(true);
        if (icons.bind(logo, s.iconKey)) plate.addView(logo, new FrameLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52), Gravity.CENTER));
        v.addView(plate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.oneLine(Ui.text(c, s.name, 14, Ui.TEXT));
        t.setPadding(Ui.dp(c, Ui.S_HALF), 0, 0, 0);
        v.addView(t, Ui.below(c, Ui.S1));
        v.setOnClickListener(new OnClickListener() { public void onClick(View x) { cb.onOpen(s); } });
        menuKeys(v, new Runnable() { public void run() { cb.onServiceMenu(s); } });
        final OnFocusChangeListener scale = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View x, boolean has) {
                scale.onFocusChange(x, has);
                if (has) {
                    serviceBanner(s);
                    reveal(x);
                }
            }
        });
        return v;
    }

    private View contentCard(Context c, final HomeView.Card card) {
        LinearLayout v = cardShell(c);
        AspectFrame pic = new AspectFrame(c);
        pic.setBackground(Ui.rounded(Ui.DEEP, Ui.dp(c, 4)));
        pic.setClipToOutline(true);
        ImageView img = new ImageView(c);
        img.setScaleType(card.item.kind == com.goomero.cleartv.tv.Item.CHANNEL ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!card.item.image.isEmpty()) {
            boundArt.put(img, card.item.image);
            Bitmap b = art.get(card.item.image);
            if (b != null) img.setImageBitmap(b);
        }
        int brand = icons.colorFor(serviceIcon(card.service));
        if (brand == 0) brand = Ui.ACCENT;
        if (card.progress > 0) {
            View bar = new View(c);
            bar.setBackgroundColor(brand);
            bar.setPivotX(0);
            bar.setScaleX(Math.max(0.03f, card.progress));
            pic.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM));
        }
        v.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.oneLine(Ui.text(c, card.item.title, 14, Ui.TEXT));
        t.setPadding(Ui.dp(c, Ui.S_HALF), 0, Ui.dp(c, Ui.S_HALF), 0);
        v.addView(t, Ui.below(c, Ui.S1));
        TextView sub = Ui.oneLine(Ui.kicker(c, card.serviceName, 10, Ui.serviceAccent(brand)));
        sub.setPadding(Ui.dp(c, Ui.S_HALF), 0, Ui.dp(c, Ui.S_HALF), 0);
        v.addView(sub, Ui.below(c, Ui.S_HALF));
        v.setOnClickListener(new OnClickListener() { public void onClick(View x) { cb.onOpenEntry(card.service, card.item); } });
        menuKeys(v, new Runnable() { public void run() { cb.onEntryMenu(card.service, card.item, card.favorite); } });
        final OnFocusChangeListener scale = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View x, boolean has) {
                scale.onFocusChange(x, has);
                if (has) {
                    cardBanner(card);
                    reveal(x);
                }
            }
        });
        return v;
    }

    private void ensureArt(Context c) {
        if (art != null) return;
        art = new com.goomero.cleartv.tv.Images(c, Ui.dp(c, 200), new Runnable() {
            @Override
            public void run() {
                for (java.util.Map.Entry<ImageView, String> e : boundArt.entrySet()) {
                    Bitmap b = art.get(e.getValue());
                    if (b != null) e.getKey().setImageBitmap(b);
                }
            }
        });
        bannerArt = new com.goomero.cleartv.tv.Images(c, Ui.dp(c, 640), new Runnable() {
            @Override
            public void run() {
                if (bannerUrl == null) return;
                Bitmap b = bannerArt.get(bannerUrl);
                if (b != null) bannerImg.setImageBitmap(b);
            }
        });
    }

    /** A frame that is always 16:9 of its width, so artwork is shown landscape and never cut to another shape. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    @Override
    void focusDefault() {
        if (firstService != null) firstService.requestFocus();
    }

    @Override
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
        if (backdrop != null || Ui.BACKDROP.equals("plain")) return;
        backdrop = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.ARGB_8888);
        HomeView.drawBackdrop(new Canvas(backdrop), backdrop.getWidth(), backdrop.getHeight());
        BitmapDrawable d = new BitmapDrawable(getResources(), backdrop);
        d.setFilterBitmap(true);
        setBackground(d);
    }

    @Override
    void release() {
        if (art != null) art.trim();
        if (bannerArt != null) bannerArt.trim();
        bannerImg.setImageDrawable(null);
        if (backdrop == null) return;
        setBackgroundColor(Ui.BG);
        backdrop.recycle();
        backdrop = null;
    }

    /** Keeps the focused grid card, its focus growth and the text under it fully on screen. */
    private void reveal(final View v) {
        post(new Runnable() {
            @Override
            public void run() {
                int m = Ui.dp(getContext(), Ui.S2);
                v.requestRectangleOnScreen(new android.graphics.Rect(-m, -m, v.getWidth() + m, v.getHeight() + m), false);
            }
        });
    }
}
