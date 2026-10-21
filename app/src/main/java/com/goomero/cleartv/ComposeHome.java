package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.goomero.cleartv.theme.Layout;
import com.goomero.cleartv.theme.Layout.Node;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen of a version 2 theme: builds the theme's component tree ({@link Layout}) into views once.
 *
 * <p>Built for speed, the same as the Default home: every view is created when the screen is built; a key press only
 * changes text, a bitmap, alpha or a hardware-accelerated scale/elevation animation. Hero and label text views have a
 * fixed size and a fixed line count, so changing their text does not re-measure the screen. Rows rebind their existing
 * card views when the data changes (adding or removing only the difference); the focused-service shelf keeps one
 * prebuilt row per service and switches between them with visibility, not by rebuilding. Backdrop art is downsampled
 * to half the screen width in RGB_565 and cached by {@link com.goomero.cleartv.tv.Images}.
 */
final class ComposeHome extends HomeBase {

    /** LEFT/RIGHT only move to an item that is really on that side: at the end of a row or the tabs, focus does not drop into another row. */
    @Override
    public View focusSearch(View focused, int direction) {
        View next = super.focusSearch(focused, direction);
        if (focused == null || next == null) return next;
        // never into the part of another scrolling column that is scrolled out of sight (it counts as "above")
        Column col = column(next);
        if (col != null && col != column(focused)) {
            int[] c = new int[2], n = new int[2];
            col.getLocationOnScreen(c);
            next.getLocationOnScreen(n);
            if (n[1] + next.getHeight() / 2 < c[1] || n[1] + next.getHeight() / 2 > c[1] + col.getHeight()) return focused;
        }
        if (direction != FOCUS_LEFT && direction != FOCUS_RIGHT) return next;
        if (focused.getNextFocusLeftId() != NO_ID || focused.getNextFocusRightId() != NO_ID) return next;
        int[] a = new int[2], b = new int[2];
        focused.getLocationOnScreen(a);
        next.getLocationOnScreen(b);
        // only an item really on that side: not one below that merely starts further left or right
        int slack = focused.getWidth() / 4;
        boolean beside = direction == FOCUS_LEFT ? b[0] + next.getWidth() <= a[0] + slack : b[0] >= a[0] + focused.getWidth() - slack;
        return beside ? next : focused;
    }

    /**
     * A scrolling column whose UP/DOWN keys only ever move focus to the next item that way (the same search as the
     * rest of the home, including focus mappings); reveal() and snap() then scroll it into view. ScrollView's own
     * arrow scroll would instead scroll half a page when the next item is further than that, and when that pushed the
     * focused card off screen it gave focus to the ScrollView itself, so the next DOWN restarted from the top.
     */
    private static final class Column extends ScrollView {
        Column(Context c) {
            super(c);
            setFocusable(false);
            setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        }

        @Override
        public boolean executeKeyEvent(android.view.KeyEvent e) {
            int k = e.getKeyCode();
            if (k != android.view.KeyEvent.KEYCODE_DPAD_DOWN && k != android.view.KeyEvent.KEYCODE_DPAD_UP) return super.executeKeyEvent(e);
            if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            View f = findFocus();
            if (f == null || f == this) return false;
            int dir = k == android.view.KeyEvent.KEYCODE_DPAD_DOWN ? FOCUS_DOWN : FOCUS_UP;
            View next = f.focusSearch(dir);
            if (next == null || next == this) return dir == FOCUS_DOWN; // the bottom: stay; the top: let it leave
            return next.requestFocus(dir);
        }
    }

    private static Column column(View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) if (p instanceof Column) return (Column) p;
        return null;
    }

    private static final String TAG = "CTV.Compose";
    private final Layout layout;
    private final Map<Node, List<View>> focusables = new HashMap<>();
    private final Map<String, Node> ids = new HashMap<>();
    private final List<Hero> heroes = new ArrayList<>();
    private final List<TextView> clocks = new ArrayList<>(), statuses = new ArrayList<>(), focusTitles = new ArrayList<>(), focusServices = new ArrayList<>();
    private final Map<String, List<TextView>> serviceLabels = new HashMap<>();
    private final List<DataList> lists = new ArrayList<>();
    private final List<ServiceShelf> shelves = new ArrayList<>();
    private final Map<String, String> notices = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Backdrop backdrop;
    private com.goomero.cleartv.tv.Images art, heroArt;
    /** Set while LayoutSnapshot builds a preview. */
    static boolean SNAPSHOT;

    private List<HomeView.Card> cont = Collections.emptyList(), favs = Collections.emptyList();
    private String status = "";
    private Bitmap stars;

    ComposeHome(Context c, HomeView.Callbacks cb, TileIcons icons, Layout layout) {
        super(c, cb, icons);
        this.layout = layout;
        setBackgroundColor(Ui.BG);
        setClipChildren(false);
        for (Node n : layout.nodes()) if (n.id != null) ids.put(n.id, n);
        art = new com.goomero.cleartv.tv.Images(c, Ui.dp(c, 240), new Runnable() {
            public void run() { for (DataList d : lists) d.rebindArt(); for (ServiceShelf s : shelves) s.rebindArt(); }
        });
        // backdrops decode at full screen width (off the main thread): a half-size picture looks blown up full screen
        heroArt = new com.goomero.cleartv.tv.Images(c, Math.max(c.getResources().getDisplayMetrics().widthPixels, 1280), new Runnable() {
            public void run() { if (backdrop != null) backdrop.refresh(); }
        });
        if (SNAPSHOT) { art.diskSync = true; heroArt.diskSync = true; } // previews draw once, so art must be there now
        View root = build(layout.home);
        addView(root, params(layout.home, null));
        wireFocus();
        tick.run();
        showService(Services.ALL[0]);
    }

    // ------------------------------------------------------------------------------------------------ build

    private View build(Node n) {
        Context c = getContext();
        View v;
        switch (n.type) {
            case "stack": v = stack(c, n); break;
            case "overlay": v = overlay(c, n); break;
            case "spacer": v = new View(c); break;
            case "backdrop": v = backdropView(c, n); break;
            case "hero": v = hero(c, n); break;
            case "text": v = text(c, n); break;
            case "clock": { TextView t = textView(c, n, n.size > 0 ? n.size : 18, "display"); clocks.add(t); v = t; break; }
            case "logo": v = logo(c, n); break;
            case "image": v = image(c, n); break;
            case "button": v = button(c, n); break;
            case "row": case "grid": v = list(c, n); break;
            default: v = nav(c, n); break; // nav, dock, sidebar, tabs
        }
        int[] pd = n.padding;
        if (!(v instanceof TextView) || n.type.equals("text")) v.setPadding(v.getPaddingLeft() + Ui.dp(c, pd[0]), v.getPaddingTop() + Ui.dp(c, pd[1]), v.getPaddingRight() + Ui.dp(c, pd[2]), v.getPaddingBottom() + Ui.dp(c, pd[3]));
        if (n.background != null) v.setBackground(Ui.rounded(color(n.background, 0), Ui.dp(c, n.radius >= 0 ? n.radius : Ui.RADIUS)));
        if (n.opacity < 1f) v.setAlpha(n.opacity);
        return v;
    }

    private ViewGroup.LayoutParams params(Node n, ViewGroup parent) {
        Context c = getContext();
        int w = n.width == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : n.width == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT : Ui.dp(c, n.width);
        int h = n.height == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : n.height == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT : Ui.dp(c, n.height);
        if (n.type.equals("backdrop")) { w = ViewGroup.LayoutParams.MATCH_PARENT; h = ViewGroup.LayoutParams.MATCH_PARENT; }
        int[] m = n.margin;
        if (parent instanceof LinearLayout) {
            boolean row = ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL;
            if (n.type.equals("spacer") && n.weight == 0 && n.width == -2 && n.height == -2) n.weight = 1;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(n.weight > 0 && row ? 0 : w, n.weight > 0 && !row ? 0 : h, n.weight);
            lp.setMargins(Ui.dp(c, m[0]), Ui.dp(c, m[1]), Ui.dp(c, m[2]), Ui.dp(c, m[3]));
            lp.gravity = gravity(n.gravity);
            return lp;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity(n.gravity));
        lp.setMargins(Ui.dp(c, m[0]), Ui.dp(c, m[1]), Ui.dp(c, m[2]), Ui.dp(c, m[3]));
        return lp;
    }

    private View stack(Context c, Node n) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(n.direction.equals("row") ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        boolean row = n.direction.equals("row");
        if (!n.align.equals("stretch")) l.setGravity(row ? alignV(n.align) : alignH(n.align));
        for (int i = 0; i < n.children.size(); i++) {
            Node ch = n.children.get(i);
            View cv = build(ch);
            ViewGroup.LayoutParams lp = params(ch, l);
            if (i > 0 && n.gap > 0) {
                LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) lp;
                if (row) ll.leftMargin += Ui.dp(c, n.gap); else ll.topMargin += Ui.dp(c, n.gap);
            }
            if (n.align.equals("stretch")) {
                if (row) lp.height = ViewGroup.LayoutParams.MATCH_PARENT; else lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            l.addView(cv, lp);
        }
        if (!n.scroll) return l;
        // room past the last item so the bottom row, its focus growth and its label can always scroll fully into view
        if (row) l.setPadding(l.getPaddingLeft(), l.getPaddingTop(), l.getPaddingRight() + Ui.dp(c, 48), l.getPaddingBottom());
        else l.setPadding(l.getPaddingLeft(), l.getPaddingTop(), l.getPaddingRight(),
                l.getPaddingBottom() + c.getResources().getDisplayMetrics().heightPixels / 2); // room to snap any block to the top
        // a scrolling stack: the focused child is brought fully on screen (with room for its focus growth)
        FrameLayout sv = row ? new HorizontalScrollView(c) : new Column(c);
        sv.setClipChildren(false); // a scrolling stack fills its area; rows clip themselves (clipRow)
        sv.setClipToPadding(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // no animated arrow-key scroll: it would finish after (and undo) reveal()'s exact jump
        if (sv instanceof ScrollView) ((ScrollView) sv).setSmoothScrollingEnabled(false);
        else ((HorizontalScrollView) sv).setSmoothScrollingEnabled(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setHorizontalScrollBarEnabled(false);
        if (!row) {
            sv.setTag(R.id.home_shadow, "snap");
            // content scrolled up never draws above the column's own top (over a pinned hero, or off its edge);
            // sideways it may still grow into the margins for focus
            sv.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int a, int t, int r, int b, int oa, int ot, int or, int ob) {
                    int big = Ui.dp(v.getContext(), 64);
                    android.graphics.Rect want = new android.graphics.Rect(-big, 0, (r - a) + big, (b - t) + big);
                    if (!want.equals(v.getClipBounds())) v.setClipBounds(want);
                }
            });
        }
        sv.addView(l, new FrameLayout.LayoutParams(row ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                row ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private View overlay(Context c, Node n) {
        FrameLayout f = new FrameLayout(c);
        f.setClipChildren(false);
        f.setClipToPadding(false);
        for (Node ch : n.children) f.addView(build(ch), params(ch, f));
        return f;
    }

    private TextView textView(Context c, Node n, int sp, String style) {
        int col = color(n.color != null ? n.color : style.equals("kicker") ? "muted" : "text", Ui.TEXT);
        TextView t = style.equals("kicker") ? Ui.kicker(c, "", sp, col) : Ui.text(c, "", sp, col);
        if (style.equals("display")) t.setTypeface(Ui.display(c));
        t.setLines(Math.max(1, n.lines));
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(alignH(n.textAlign) | Gravity.CENTER_VERTICAL);
        if (n.shadow && !Ui.LIGHT) t.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
        return t;
    }

    private View text(Context c, Node n) {
        TextView t = textView(c, n, n.size > 0 ? n.size : 14, n.style);
        if (n.bind == null) t.setText(n.style.equals("kicker") ? n.text.toUpperCase(java.util.Locale.ROOT) : n.text);
        else switch (n.bind) {
            case "clock": clocks.add(t); break;
            case "version": t.setText("v" + BuildConfig.VERSION_NAME); break;
            case "status": statuses.add(t); break;
            case "app": t.setText(c.getString(R.string.app_name)); break;
            case "focused-title": focusTitles.add(t); break;
            default: focusServices.add(t); break;
        }
        return t;
    }

    private View logo(Context c, Node n) {
        if (n.asset != null) return image(c, n);
        TextView t = textView(c, n, n.size > 0 ? n.size : 26, "display");
        t.setText(c.getString(R.string.app_name).toUpperCase(java.util.Locale.ROOT));
        t.setLetterSpacing(0.12f);
        return t;
    }

    private View image(Context c, Node n) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(n.scale.equals("fit") ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        loadAsset(n.asset, iv);
        return iv;
    }

    // ---- backdrop: the single full-screen image layer --------------------------------------------------------

    private final class Backdrop {
        final Node n;
        final ImageView img;
        String url;
        Drawable fallback;
        int brand = Ui.ACCENT;
        String logoKey;

        Backdrop(Node n, ImageView img) {
            this.n = n;
            this.img = img;
        }

        private final Map<Integer, Drawable> brandArt = new HashMap<>();
        private final Runnable swap = new Runnable() {
            public void run() { refresh(true); }
        };

        /** Focus moved: swap the image 300 ms after focus settles, so fast key presses never decode or redraw it. */
        void show(String imageUrl, int brand, String logoKey) {
            if (n.source.equals("none") || n.source.equals("asset")) return;
            boolean same = imageUrl == null ? url == null && this.brand == brand : imageUrl.equals(url);
            this.url = imageUrl;
            this.brand = brand;
            this.logoKey = logoKey;
            removeCallbacks(swap);
            if (!same) postDelayed(swap, 300); // only once focus rests: browsing never re-renders the backdrop layer
        }

        void refresh() {
            refresh(false);
        }

        void refresh(boolean fade) {
            Bitmap b = url == null ? null : best(url); // cached, or loaded and decoded off the main thread
            int screen = getResources().getDisplayMetrics().widthPixels;
            if (b != null) {
                img.setImageBitmap(b);
                // art under half the screen width would look blown up: shown dimmed into the background instead
                boolean low = b.getWidth() < screen / 2;
                img.setColorFilter(low ? (Ui.BG & 0x00FFFFFF) | 0x99000000 : 0, android.graphics.PorterDuff.Mode.SRC_ATOP);
                if (!low) img.clearColorFilter();
            } else if (url == null || img.getDrawable() == null) {
                img.clearColorFilter();
                img.setImageDrawable(fallback());
            }
            if (fade && n.fade > 0) { // opt-in only (warn_slow); short, and on the image alone
                img.setAlpha(0.85f);
                img.animate().alpha(1f).setDuration(Math.min(n.fade, 100)).start();
            }
        }

        /** The largest variant of the art that has loaded; asks for the next variant when a bigger one is missing. */
        private Bitmap best(String u) {
            for (String v : com.goomero.cleartv.tv.Images.largest(u)) {
                if (heroArt.failed(v)) continue;
                return heroArt.get(v); // null while it loads; refresh() runs again when it arrives or fails
            }
            return null;
        }

        Drawable fallback() {
            switch (n.fallback) {
                case "brand": {
                    Drawable d = brandArt.get(brand);
                    if (d == null) brandArt.put(brand, d = new BrandArt(brand, logoKey == null ? null : icons.bitmap(logoKey), getResources().getDisplayMetrics().density));
                    return d;
                }
                case "stars": return starsDrawable();
                case "gradient": return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.DEEP, Ui.BG, (Ui.ACCENT & 0x00FFFFFF) | 0x33000000});
                default: return new ColorDrawable(Ui.BG);
            }
        }
    }

    private View backdropView(Context c, Node n) {
        FrameLayout f = new FrameLayout(c);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int bg = Ui.BG & 0x00FFFFFF;
        if (n.scrimLeft > 0) {
            View s = new View(c);
            s.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{bg | a(n.scrimLeft), bg | a(n.scrimLeft * 0.4f), 0}));
            f.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (n.scrimTop > 0) {
            View s = new View(c);
            s.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg | a(n.scrimTop), 0, 0}));
            f.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (n.scrimBottom > 0) {
            // one long eased scrim: clear at the top, the solid background at the bottom; no hard edge anywhere
            View s = new View(c);
            float k = n.scrimBottom;
            s.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0, bg | a(0.12f * k), bg | a(0.55f * k), bg | a(0.9f * k), bg | a(k)}));
            f.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // the image and its scrims are flattened into one GPU layer: focus animations elsewhere blit one texture
        // instead of redrawing a full-screen bitmap and up to three full-screen gradients every frame
        f.setLayerType(LAYER_TYPE_HARDWARE, null);
        backdrop = new Backdrop(n, img);
        if (n.source.equals("asset")) loadAsset(n.asset, img);
        else img.setImageDrawable(backdrop.fallback());
        return f;
    }

    private static int a(float f) {
        return Math.round(Math.max(0, Math.min(1, f)) * 255) << 24;
    }

    private Drawable starsDrawable() {
        if (stars == null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            stars = Bitmap.createBitmap(Math.max(1, dm.widthPixels / 2), Math.max(1, dm.heightPixels / 2), Bitmap.Config.ARGB_8888);
            HomeView.drawBackdrop(new Canvas(stars), stars.getWidth(), stars.getHeight());
        }
        BitmapDrawable d = new BitmapDrawable(getResources(), stars);
        d.setFilterBitmap(true);
        return d;
    }

    // ---- hero: kicker, title, description of whatever is focused ----------------------------------------------

    private static final class Hero {
        TextView kicker, title, desc;
    }

    private View hero(Context c, Node n) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.BOTTOM);
        Hero h = new Hero();
        int col = color(n.color != null ? n.color : "text", Ui.TEXT);
        if (n.kicker) {
            h.kicker = Ui.kicker(c, "", 11, (col & 0x00FFFFFF) | 0xCC000000);
            fixed(h.kicker, 1, n.shadow);
            l.addView(h.kicker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        h.title = Ui.text(c, "", n.titleSize, col);
        h.title.setTypeface(Ui.display(c));
        fixed(h.title, n.titleLines, n.shadow);
        l.addView(h.title, Ui.below(c, Ui.S_HALF));
        h.title.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (n.descLines > 0) {
            h.desc = Ui.text(c, "", 14, (col & 0x00FFFFFF) | 0xD9000000);
            fixed(h.desc, n.descLines, n.shadow);
            l.addView(h.desc, Ui.below(c, Ui.S1));
            h.desc.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        heroes.add(h);
        return l;
    }

    /** Fixed line count: setText never re-measures the screen (TextView skips requestLayout when min = max lines). */
    private void fixed(TextView t, int lines, boolean shadow) {
        t.setLines(lines);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (shadow && !Ui.LIGHT) t.setShadowLayer(Ui.dp(getContext(), 8), 0, Ui.dp(getContext(), 1), 0xB3000000);
    }

    private void heroText(String kicker, String title, String desc, int descColor) {
        for (Hero h : heroes) {
            if (h.kicker != null) h.kicker.setText(kicker.toUpperCase(java.util.Locale.ROOT));
            h.title.setText(title);
            if (h.desc != null) {
                h.desc.setText(desc);
                h.desc.setTextColor(descColor != 0 ? descColor : (h.title.getCurrentTextColor() & 0x00FFFFFF) | 0xD9000000);
            }
        }
        for (TextView t : focusTitles) t.setText(title);
    }

    // ---- buttons and nav ---------------------------------------------------------------------------------------

    private View button(Context c, Node n) {
        View v = toolItem(c, n, n.action, n.style, n.label);
        register(n, v);
        return v;
    }

    private View nav(Context c, Node n) {
        LinearLayout l = new LinearLayout(c);
        boolean row = n.direction.equals("row");
        l.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        l.setGravity(row ? alignH(n.align) | Gravity.CENTER_VERTICAL : alignH(n.align));
        int gap = Ui.dp(c, n.gap > 0 ? n.gap : n.itemStyle.equals("text") ? 8 : 16);
        for (String item : n.items) {
            if (item.equals("services")) {
                for (Services.Service s : Services.ALL) add(l, serviceItem(c, n, s), row, gap);
            } else {
                add(l, toolItem(c, n, item, n.itemStyle, null), row, gap);
            }
        }
        if (!row) return l;
        // a row that is wider than the screen scrolls with focus instead of being cut off
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        clipRow(c, hs);
        hs.setFillViewport(false);
        l.setPadding(0, Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        hs.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    private void add(LinearLayout l, View item, boolean row, int gap) {
        LinearLayout.LayoutParams lp = item.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) item.getLayoutParams()
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (l.getChildCount() > 0) { if (row) lp.leftMargin = gap; else lp.topMargin = gap; }
        if (!row && lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        l.addView(item, lp);
    }

    /** Search, Themes or Settings as a nav item or a button. */
    private View toolItem(Context c, Node n, final String action, String style, String label) {
        final String name = label != null ? label : action.equals("search") ? "Search all" : action.equals("themes") ? "Themes" : "Settings";
        int glyph = action.equals("search") ? Glyph.SEARCH : action.equals("themes") ? Glyph.THEMES : Glyph.SETTINGS;
        final String desc = action.equals("search") ? "Find a title in every service at once."
                : action.equals("themes") ? "Change the look and layout, or import a theme from your phone." : "Accounts, playback, blocking and the phone link.";
        View v;
        if (style.equals("tile") || style.equals("icon")) {
            ImageView g = new ImageView(c);
            g.setImageDrawable(new Glyph(glyph, 0xFFFFFFFF));
            v = cardView(c, n, name, null, 0xFF3A3D42, g, style.equals("icon") ? "focused" : n.card.label, false);
        } else {
            v = textItem(c, n, name, style, n.glyph ? new Glyph(glyph, color(n.color != null ? n.color : "text", Ui.TEXT)) : null, null);
        }
        View f = focusTarget(v);
        f.setContentDescription(name);
        f.setOnClickListener(new OnClickListener() {
            public void onClick(View x) {
                if (action.equals("search")) cb.onSearchAll();
                else if (action.equals("themes")) openThemes();
                else cb.onSettings();
            }
        });
        effects(f, n, new Runnable() {
            public void run() {
                heroText(action.toUpperCase(java.util.Locale.ROOT), name, desc, 0);
                for (TextView t : focusServices) t.setText(name);
                for (ServiceShelf s : shelves) s.show(null);
                if (backdrop != null) backdrop.show(null, 0xFF4A4F57, null);
            }
        });
        register(n, f);
        return v;
    }

    private View serviceItem(Context c, Node n, final Services.Service s) {
        int brand = icons.colorFor(s.iconKey);
        if (brand == 0) brand = Ui.ACCENT;
        View v;
        TextView label;
        if (n.itemStyle.equals("tile") || n.itemStyle.equals("icon")) {
            ImageView logo = new ImageView(c);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setBackground(Ui.rounded(0, Ui.dp(c, 10)));
            logo.setClipToOutline(true);
            if (!icons.bind(logo, s.iconKey)) logo = null;
            int fill = n.card.fill.equals("card") && !n.itemStyle.equals("icon") ? Ui.CARD_FILL : color(n.card.fill.equals("card") ? "brand" : n.card.fill, brand, brand);
            v = cardView(c, n, s.name, null, fill, logo, n.itemStyle.equals("icon") ? "focused" : n.card.label, false);
            label = (TextView) v.getTag(R.id.home_label);
        } else {
            ImageView logo = null;
            if (n.itemStyle.equals("logo-text")) {
                logo = new ImageView(c);
                logo.setBackground(Ui.rounded(0, Ui.dp(c, 6)));
                logo.setClipToOutline(true);
                if (!icons.bind(logo, s.iconKey)) logo = null;
            }
            v = textItem(c, n, s.name, n.itemStyle, null, logo);
            label = (TextView) v.getTag(R.id.home_label);
        }
        if (label != null) {
            List<TextView> l = serviceLabels.get(s.id);
            if (l == null) serviceLabels.put(s.id, l = new ArrayList<>());
            l.add(label);
        }
        View f = focusTarget(v);
        f.setContentDescription(s.name);
        f.setOnClickListener(new OnClickListener() { public void onClick(View x) { cb.onOpen(s); } });
        menuKeys(f, new Runnable() { public void run() { cb.onServiceMenu(s); } });
        effects(f, n, new Runnable() { public void run() { showService(s); } });
        register(n, f);
        return v;
    }

    /** A text item (tabs, sidebar entries, pills): the row itself takes focus and gets the surface fill. */
    private View textItem(Context c, Node n, String name, String style, Drawable glyph, ImageView logo) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        int r = Ui.dp(c, style.equals("pill") ? Ui.PILL : n.card.radius >= 0 ? n.card.radius : Ui.RADIUS);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(r);
        on.setColor(style.equals("text") && n.effect.ring ? 0 : Ui.SURFACE);
        if (n.effect.ring) on.setStroke(Ui.dp(c, style.equals("text") ? 2 : Ui.RING), n.effect.glow ? (Ui.ACCENT & 0x00FFFFFF) | 0xCC000000 : Ui.ACCENT);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, style.equals("pill") ? Ui.rounded(Ui.CARD_FILL, r) : new ColorDrawable(0));
        row.setBackground(bg);
        exactShape(row, r);
        row.setClipToOutline(false);
        row.setTag(R.id.home_text_item, Boolean.TRUE);
        int ph = Ui.dp(c, style.equals("text") ? 14 : 12), pv = Ui.dp(c, style.equals("text") ? 6 : 10);
        row.setPadding(ph, pv, ph, pv);
        if (logo != null) row.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
        else if (glyph != null) {
            ImageView g = new ImageView(c);
            g.setImageDrawable(glyph);
            row.addView(g, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        }
        TextView t = Ui.oneLine(Ui.text(c, name, n.size > 0 ? n.size : 16, color(n.color != null ? n.color : "text", Ui.TEXT)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (logo != null || glyph != null) tl.leftMargin = Ui.dp(c, Ui.S2 - 4);
        row.addView(t, tl);
        row.setTag(R.id.home_label, t);
        return row;
    }

    // ---- cards ----------------------------------------------------------------------------------------------

    /**
     * A landscape card: a fixed-size picture (art, or a fill with a centred logo/glyph) and an optional label.
     * The picture is the focus target; its label is shown always, only on focus, over the art, or never.
     */
    private View cardView(Context c, Node n, String title, String sub, int fill, View center, String label, boolean art) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        int w = Ui.dp(c, Math.round(n.card.width * Ui.TILE_SCALE)), h = Math.round(w / n.card.ratio());
        FrameLayout pic = new FrameLayout(c);
        pic.setFocusable(true);
        pic.setClickable(true);
        float r = Ui.dp(c, n.card.radius >= 0 ? n.card.radius : Ui.RADIUS);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(fill), fill});
        bg.setCornerRadius(r);
        pic.setBackground(bg);
        exactShape(pic, r);
        if (n.effect.ring || n.effect.glow) pic.setForeground(ring(c, r, n.effect));
        if (art) {
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pic.setTag(R.id.home_art, img);
            View bar = new View(c);
            bar.setBackgroundColor(Ui.ACCENT);
            bar.setPivotX(0);
            bar.setVisibility(View.INVISIBLE);
            pic.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4), Gravity.BOTTOM));
            pic.setTag(R.id.home_progress, bar);
        }
        if (center != null) {
            int s = n.card.logoSize > 0 ? Ui.dp(c, n.card.logoSize) : Math.min(h * 6 / 10, Ui.dp(c, 64));
            pic.addView(center, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        if (n.effect.shine) {
            View sh = new View(c);
            sh.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}));
            sh.setRotation(16);
            sh.setScaleY(1.6f);
            sh.setAlpha(0f);
            pic.addView(sh, new FrameLayout.LayoutParams(Math.max(w / 3, 1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
            pic.setTag(R.id.home_shine, sh);
        }
        TextView t = null;
        if (label.equals("overlay")) {
            t = Ui.oneLine(Ui.text(c, title, 13, 0xFFFFFFFF));
            t.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
            t.setPadding(Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 6));
            pic.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        // the picture sits in a frame with a pre-rendered soft shadow behind it (faded in on focus): no elevation
        // shadows to render per frame
        FrameLayout frame = new FrameLayout(c);
        frame.setClipChildren(false);
        if (n.effect.shadow > 0) {
            View shadow = new View(c);
            int d = Ui.dp(c, Math.min(n.effect.shadow, 24));
            GradientDrawable sd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x22000000, 0x66000000});
            sd.setCornerRadius(r + d / 2f);
            shadow.setBackground(sd);
            shadow.setVisibility(INVISIBLE);
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(w + d, h + d, Gravity.CENTER);
            shadow.setTranslationY(d * 0.6f);
            frame.addView(shadow, sl);
            pic.setTag(R.id.home_shadow, shadow);
        }
        frame.addView(pic, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        cell.addView(frame, new LinearLayout.LayoutParams(w, h));
        if (label.equals("below") || label.equals("focused")) {
            t = Ui.text(c, title, 14, color(n.color != null ? n.color : "text", Ui.TEXT));
            t.setLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(alignH(n.align.equals("center") ? "center" : "start"));
            if (!Ui.LIGHT) t.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0x99000000);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Ui.dp(c, 10);
            cell.addView(t, tl);
            if (label.equals("focused")) {
                t.setAlpha(0f);
                pic.setTag(R.id.home_focus_label, t);
            }
        }
        TextView st = null;
        if (n.card.subtitle) {
            st = Ui.kicker(c, sub == null ? "" : sub, 10, Ui.MUTED);
            st.setLines(1);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = Ui.dp(c, 2);
            cell.addView(st, sl);
        }
        cell.setTag(R.id.home_label, t);
        cell.setTag(R.id.home_sub, st);
        cell.setTag(R.id.home_pic, pic);
        return cell;
    }

    private static View focusTarget(View v) {
        Object pic = v.getTag(R.id.home_pic);
        return pic instanceof View ? (View) pic : v;
    }

    private static Drawable ring(Context c, float r, Layout.Effect e) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(r);
        f.setColor(0);
        int w = Ui.dp(c, e.glow ? Ui.RING + 2 : Ui.RING);
        f.setStroke(w, e.glow ? (Ui.ACCENT & 0x00FFFFFF) | 0xB3000000 : Ui.ACCENT);
        // a thin line in the background colour inside the accent ring keeps the ring visible on any fill, even a
        // tile painted in the accent colour itself
        GradientDrawable in = new GradientDrawable();
        in.setCornerRadius(Math.max(0, r - w));
        in.setColor(0);
        in.setStroke(Math.max(1, Ui.dp(c, 2)), Ui.LIGHT ? 0xFFFFFFFF : 0xFF000000);
        android.graphics.drawable.LayerDrawable both = new android.graphics.drawable.LayerDrawable(new Drawable[]{f, in});
        both.setLayerInset(1, w, w, w, w);
        s.addState(new int[]{android.R.attr.state_focused}, both);
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    private static int lighten(int c) {
        int a = c & 0xFF000000;
        int r = Math.min(255, ((c >> 16) & 0xFF) + 18), g = Math.min(255, ((c >> 8) & 0xFF) + 18), b = Math.min(255, (c & 0xFF) + 18);
        return a | (r << 16) | (g << 8) | b;
    }

    /**
     * Focus animation, mirroring the Default home: a 100 ms scale on the render thread, the pre-rendered shadow and the
     * focus label fading with it, the highlight sweep if asked for. Nothing here measures or lays out, decodes a
     * bitmap or allocates a drawable; the backdrop follows 150 ms after focus settles.
     */
    private void effects(final View v, final Node n, final Runnable onFocus) {
        float sc = n.effect.scale > 0 ? n.effect.scale : Ui.FOCUS_SCALE;
        if (v.getTag(R.id.home_text_item) != null) sc = Math.min(sc, 1.04f); // text items sit close together
        final float scale = sc;
        v.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View x, boolean has) {
                float s = has ? scale : 1f;
                // exactly the Default home's mechanism (Ui.focusScale): unclip, then one 120 ms scale; everything
                // else switches instantly
                if (has) unclip(x);
                x.animate().scaleX(s).scaleY(s).setDuration(120).start();
                Object sh = x.getTag(R.id.home_shadow);
                if (sh instanceof View) { View d = (View) sh; d.setVisibility(has ? VISIBLE : INVISIBLE); d.setScaleX(s); d.setScaleY(s); }
                Object l = x.getTag(R.id.home_focus_label);
                if (l instanceof View) ((View) l).setAlpha(has ? 1f : 0f);
                if (!has) return;
                Object shine = x.getTag(R.id.home_shine);
                if (shine instanceof View) {
                    View sw = (View) shine;
                    sw.setAlpha(1f);
                    sw.setTranslationX(-sw.getWidth());
                    sw.animate().translationX(x.getWidth() + sw.getWidth()).setDuration(400).start();
                }
                reveal(x);
                if (onFocus != null) onFocus.run();
            }
        });
    }

    /** The outline (clip and focus ring) follows the view's exact bounds and corner radius at every scale. */
    private static void exactShape(View v, final float r) {
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /**
     * In a scrolling column, a block (hero, row with its title, grid line) is never left half cut at the top: once
     * the column has to scroll, it scrolls so the block holding the focused item starts exactly at the column's top.
     */
    private void snap(View v) {
        View child = v;
        android.view.ViewParent p = v.getParent();
        while (p instanceof View && !"snap".equals(((View) p).getTag(R.id.home_shadow))) {
            child = (View) p;
            p = p.getParent();
        }
        if (!(p instanceof ScrollView)) return;
        ScrollView sv = (ScrollView) p;
        if (!(child instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) child;
        int y = sv.getScrollY();
        android.graphics.Rect r = new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight());
        content.offsetDescendantRectToMyCoords(v, r);
        // block tops: each child of the column, and each line of a multi-line grid (a row stays with its title)
        java.util.List<Integer> tops = new ArrayList<>();
        for (int i = 0; i < content.getChildCount(); i++) {
            View b = content.getChildAt(i);
            if (b.getVisibility() == GONE) continue;
            tops.add(b.getTop() - content.getPaddingTop());
            if (b instanceof LinearLayout && ((LinearLayout) b).getOrientation() == LinearLayout.VERTICAL) {
                for (int j = 1; j < ((ViewGroup) b).getChildCount(); j++) if (!(((ViewGroup) b).getChildAt(j - 1) instanceof TextView)) tops.add(b.getTop() + ((ViewGroup) b).getChildAt(j).getTop() - content.getPaddingTop());
            }
        }
        int want = y;
        int view = sv.getHeight() - sv.getPaddingTop() - sv.getPaddingBottom();
        int below = v.getTag(R.id.home_focus_label) != null || v.getTag(R.id.home_art) != null ? Ui.dp(getContext(), 56) : Ui.dp(getContext(), 16);
        // fits with the column at the top: keep everything above it (hero text, clock) on screen
        if (r.bottom + below <= view) want = 0;
        else if (y > 0 || r.bottom > y + sv.getHeight() - sv.getPaddingTop() - sv.getPaddingBottom()) {
            int best = 0;
            for (int t : tops) if (t <= r.top - content.getPaddingTop() && t > best) best = t;
            want = best;
            // a block taller than the screen (a long grid): its top would leave the focused line below the screen,
            // so the focused line itself goes to the top instead
            if (r.bottom + below > best + view) want = r.top - content.getPaddingTop() - Ui.dp(getContext(), 16);
        }
        if (want != y) sv.scrollTo(0, Math.max(0, want));
    }

    /** Brings the focused item, its focus growth and its label fully on screen, without an animated scroll. */
    private void reveal(View v) {
        int m = Ui.dp(getContext(), 16);
        int below = v.getTag(R.id.home_focus_label) != null || v.getTag(R.id.home_art) != null ? Ui.dp(getContext(), 40) : 0;
        final android.graphics.Rect r = new android.graphics.Rect(-m, -m, v.getWidth() + m, v.getHeight() + m + below);
        v.requestRectangleOnScreen(new android.graphics.Rect(r), true); // a copy: the call rewrites its argument in each parent's coordinates
        final View f = v;
        // again after the key event is done: the scroll container's own arrow-key scroll runs after the focus change
        v.post(new Runnable() {
            public void run() {
                if (!f.isFocused()) return;
                f.requestRectangleOnScreen(new android.graphics.Rect(r), true);
                snap(f);
            }
        });
    }

    private void register(Node n, View focusable) {
        List<View> l = focusables.get(n);
        if (l == null) focusables.put(n, l = new ArrayList<>());
        l.add(focusable);
        if (focusable.getId() == View.NO_ID) focusable.setId(View.generateViewId());
    }

    // ---- rows and grids ------------------------------------------------------------------------------------------

    /** A data-bound list (row or grid). Its card views are reused on every data change. */
    private final class DataList {
        final Node n;
        final View whole;
        final LinearLayout line; // row: the cards; grid: the lines
        final TextView heading;
        final List<View> cells = new ArrayList<>();
        final List<HomeView.Card> bound = new ArrayList<>();
        final String fixedService; // focused-service shelves: one list per service

        DataList(Node n, View whole, LinearLayout line, TextView heading, String fixedService) {
            this.n = n;
            this.whole = whole;
            this.line = line;
            this.heading = heading;
            this.fixedService = fixedService;
        }

        List<HomeView.Card> data() {
            List<HomeView.Card> out = new ArrayList<>();
            String d = n.data;
            if (d.equals("continue")) out.addAll(cont);
            else if (d.equals("watchlist") || d.equals("favorites")) out.addAll(favs);
            else {
                String sid = fixedService != null ? fixedService : d.startsWith("service:") ? d.substring(8) : null;
                if (sid != null) {
                    for (HomeView.Card k : cont) if (k.service.equals(sid)) out.add(k);
                    for (HomeView.Card k : favs) if (k.service.equals(sid)) out.add(k);
                }
            }
            return out.size() > n.maxItems ? out.subList(0, n.maxItems) : out;
        }

        void refill() {
            List<HomeView.Card> d = data();
            View had = findFocus();
            int focusedIdx = -1;
            for (int i = 0; i < cells.size(); i++) if (had != null && focusTarget(cells.get(i)) == had) focusedIdx = i;
            Context c = getContext();
            // reuse: rebind what exists, add or remove only the difference
            while (cells.size() > d.size()) {
                View v = cells.remove(cells.size() - 1);
                ((ViewGroup) v.getParent()).removeView(v);
                List<View> f = focusables.get(n);
                if (f != null) f.remove(focusTarget(v));
            }
            for (int i = 0; i < d.size(); i++) {
                final HomeView.Card k = d.get(i);
                View cell;
                if (i < cells.size()) cell = cells.get(i);
                else {
                    cell = cardView(c, n, "", "", color(n.card.fill, Ui.CARD_FILL), null, n.card.label, true);
                    place(cell, i);
                    cells.add(cell);
                    register(n, focusTarget(cell));
                }
                bind(cell, k);
            }
            bound.clear();
            bound.addAll(d);
            boolean empty = d.isEmpty();
            if (fixedService == null) whole.setVisibility(empty && n.hideEmpty ? View.GONE : View.VISIBLE);
            if (focusedIdx >= 0) {
                if (focusedIdx < cells.size()) focusTarget(cells.get(focusedIdx)).requestFocus();
                else focusDefault();
            }
        }

        private void place(View cell, int i) {
            Context c = getContext();
            int gap = Ui.dp(c, n.gap > 0 ? n.gap : 20);
            if (n.type.equals("grid")) {
                int col = i % n.columns;
                if (col == 0) {
                    LinearLayout l = new LinearLayout(c);
                    l.setOrientation(LinearLayout.HORIZONTAL);
                    l.setClipChildren(false);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (line.getChildCount() > 0) lp.topMargin = gap;
                    line.addView(l, lp);
                }
                LinearLayout l = (LinearLayout) line.getChildAt(line.getChildCount() - 1);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (col > 0) lp.leftMargin = gap;
                l.addView(cell, lp);
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.leftMargin = gap;
                line.addView(cell, lp);
            }
        }

        private void bind(View cell, final HomeView.Card k) {
            final View pic = focusTarget(cell);
            TextView t = (TextView) cell.getTag(R.id.home_label);
            if (t != null) t.setText(k.item.title);
            TextView st = (TextView) cell.getTag(R.id.home_sub);
            if (st != null) st.setText(k.serviceName.toUpperCase(java.util.Locale.ROOT));
            ImageView img = (ImageView) pic.getTag(R.id.home_art);
            img.setScaleType(k.item.kind == com.goomero.cleartv.tv.Item.CHANNEL ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            img.setTag(k.item.image);
            Bitmap b = k.item.image.isEmpty() ? null : art.get(k.item.image);
            img.setImageBitmap(b);
            img.setTag(R.id.home_art, b != null ? k.item.image : null); // which art is really shown
            View bar = (View) pic.getTag(R.id.home_progress);
            bar.setVisibility(n.card.progress && k.progress > 0 ? View.VISIBLE : View.INVISIBLE);
            bar.setScaleX(Math.max(0.03f, k.progress));
            pic.setContentDescription(k.item.title);
            pic.setOnClickListener(new OnClickListener() { public void onClick(View v) { cb.onOpenEntry(k.service, k.item); } });
            menuKeys(pic, new Runnable() { public void run() { cb.onEntryMenu(k.service, k.item, k.favorite); } });
            effects(pic, n, new Runnable() {
                public void run() {
                    heroText((k.favorite ? "Watchlist \u00b7 " : "Continue watching \u00b7 ") + k.serviceName, k.item.title,
                            !k.item.description.isEmpty() ? k.item.description : k.item.subtitle, 0);
                    for (TextView x : focusServices) x.setText(k.serviceName);
                    if (backdrop != null) {
                        Services.Service s = service(k.service);
                        backdrop.show(k.item.image.isEmpty() ? null : k.item.image, brandOf(s), s == null ? null : s.iconKey);
                    }
                }
            });
        }

        void rebindArt() {
            for (View cell : cells) {
                ImageView img = (ImageView) focusTarget(cell).getTag(R.id.home_art);
                Object u = img.getTag();
                // setImageBitmap(null) leaves a non-null empty drawable, so track the shown url instead
                if (u instanceof String && !((String) u).isEmpty() && !u.equals(img.getTag(R.id.home_art))) {
                    Bitmap b = art.get((String) u);
                    if (b != null) {
                        img.setImageBitmap(b);
                        img.setTag(R.id.home_art, u);
                    }
                }
            }
        }
    }

    /** A row whose content follows the focused service: one prebuilt list per service, switched by visibility. */
    private final class ServiceShelf {
        final Map<String, DataList> per = new HashMap<>();
        final TextView empty;
        String showing;

        ServiceShelf(TextView empty) {
            this.empty = empty;
        }

        void show(String sid) {
            showing = sid;
            for (Map.Entry<String, DataList> e : per.entrySet()) {
                boolean on = e.getKey().equals(sid);
                e.getValue().whole.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            }
            DataList d = sid == null ? null : per.get(sid);
            boolean none = d == null || d.cells.isEmpty();
            empty.setVisibility(sid != null && none ? View.VISIBLE : View.INVISIBLE);
            if (sid != null && none) {
                Services.Service s = service(sid);
                empty.setText("Nothing to continue in " + (s == null ? sid : s.name) + " yet. What you watch or save shows up here.");
            }
            wireFocus(); // UP into the shelf goes to the row that is showing now (ids only, no layout)
        }

        void refill() {
            for (DataList d : per.values()) d.refill();
            show(showing);
        }

        void rebindArt() {
            for (DataList d : per.values()) d.rebindArt();
        }
    }

    private View list(Context c, Node n) {
        LinearLayout whole = new LinearLayout(c);
        whole.setOrientation(LinearLayout.VERTICAL);
        whole.setClipChildren(false);
        whole.setClipToPadding(false);
        TextView heading = null;
        if (n.title != null && !n.title.isEmpty()) {
            heading = Ui.oneLine(Ui.kicker(c, n.title, 12, color(n.titleColor != null ? n.titleColor : "muted", Ui.MUTED)));
            if (!Ui.LIGHT) heading.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0xCC000000);
            whole.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (n.data.equals("services") || n.data.equals("search")) {
            // static lists: the services (or the Search card) as cards
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            int gap = Ui.dp(c, n.gap > 0 ? n.gap : 20);
            if (n.data.equals("services")) {
                int i = 0;
                LinearLayout cur = line;
                LinearLayout col = null;
                if (n.type.equals("grid")) {
                    col = new LinearLayout(c);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setClipChildren(false);
                }
                for (Services.Service s : Services.ALL) {
                    if (col != null && i % n.columns == 0) {
                        cur = new LinearLayout(c);
                        cur.setOrientation(LinearLayout.HORIZONTAL);
                        cur.setClipChildren(false);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        if (col.getChildCount() > 0) lp.topMargin = gap;
                        col.addView(cur, lp);
                    }
                    Node itemNode = n;
                    View v = serviceItem(c, asTiles(itemNode), s);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (cur.getChildCount() > 0) lp.leftMargin = gap;
                    cur.addView(v, lp);
                    i++;
                }
                if (col != null) line = col;
            } else {
                line.addView(toolItem(c, asTiles(n), "search", "tile", null));
            }
            whole.addView(wrapRow(c, n, line), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((LinearLayout.LayoutParams) whole.getChildAt(whole.getChildCount() - 1).getLayoutParams()).topMargin = heading != null ? Ui.dp(c, 12) : 0;
            return whole;
        }
        if (n.data.equals("focused-service")) {
            FrameLayout stackF = new FrameLayout(c);
            stackF.setClipChildren(false);
            TextView empty = Ui.lines(Ui.text(c, "", 14, color("muted", Ui.MUTED)), 2);
            if (!Ui.LIGHT) empty.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
            ServiceShelf shelf = new ServiceShelf(empty);
            for (Services.Service s : Services.ALL) {
                LinearLayout line = new LinearLayout(c);
                line.setOrientation(n.type.equals("grid") ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                View wrapped = wrapRow(c, n, line);
                wrapped.setVisibility(View.INVISIBLE);
                stackF.addView(wrapped, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                DataList d = new DataList(n, wrapped, line, heading, s.id);
                shelf.per.put(s.id, d);
                lists.add(d);
            }
            FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(Ui.dp(c, 560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            el.topMargin = Ui.dp(c, 12);
            stackF.addView(empty, el);
            // reserve the card height so switching services never changes the layout
            int w = Ui.dp(c, Math.round(n.card.width * Ui.TILE_SCALE));
            stackF.setMinimumHeight(Math.round(w / n.card.ratio()) + Ui.dp(c, n.card.label.equals("none") || n.card.label.equals("overlay") ? 12 : 40));
            shelves.add(shelf);
            whole.addView(stackF, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((LinearLayout.LayoutParams) stackF.getLayoutParams()).topMargin = heading != null ? Ui.dp(c, 12) : 0;
            return whole;
        }
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(n.type.equals("grid") ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        View wrapped = wrapRow(c, n, line);
        whole.addView(wrapped, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) wrapped.getLayoutParams()).topMargin = heading != null ? Ui.dp(c, 12) : 0;
        DataList d = new DataList(n, whole, line, heading, null);
        lists.add(d);
        whole.setVisibility(n.hideEmpty ? View.GONE : View.VISIBLE);
        return whole;
    }

    /** Room left of a row for the first card's focus growth. */
    private static final int EDGE = 16;

    /**
     * A sideways-scrolling row draws its cards up to the right edge of the screen and EDGE dp past its left edge (room
     * for focus growth), and no further: cards scrolled away never draw over the column to its left.
     */
    private static void clipRow(final Context c, final HorizontalScrollView hs) {
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int[] at = new int[2];
                v.getLocationInWindow(at);
                int screen = v.getRootView().getWidth();
                int e = Ui.dp(c, EDGE);
                int right = screen > 0 ? Math.max(r - l, screen - at[0]) : (r - l);
                v.setTag(R.id.home_label, right);
                edge(v, e);
                // a parent clipping its children would cut the row at its own bounds, under the EDGE room
                android.view.ViewParent p = v.getParent();
                for (int i = 0; i < 4 && p instanceof ViewGroup && !(p instanceof ScrollView) && !(p instanceof HorizontalScrollView); i++) {
                    ((ViewGroup) p).setClipChildren(false);
                    ((ViewGroup) p).setClipToPadding(false);
                    p = p.getParent();
                }
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            hs.setOnScrollChangeListener(new OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int x, int y, int ox, int oy) {
                    if ((x == 0) != (ox == 0)) edge(v, Ui.dp(c, EDGE));
                }
            });
        }
    }

    /** Focus-growth room on the left only while the row is at its start; once scrolled, nothing peeks past the edge. */
    private static void edge(View v, int e) {
        Object r = v.getTag(R.id.home_label);
        int right = r instanceof Integer ? (Integer) r : v.getWidth();
        android.graphics.Rect want = new android.graphics.Rect(v.getScrollX() == 0 ? -e : 0, -e, right, v.getHeight() + e);
        if (!want.equals(v.getClipBounds())) v.setClipBounds(want);
    }

    /** Lets focus growth draw past its parents, but never through a scrolling container (that would show scrolled-off content). */
    private static void unclip(View v) {
        android.view.ViewParent p = v.getParent();
        for (int i = 0; i < 3 && p instanceof ViewGroup; i++) {
            if (p instanceof HorizontalScrollView || p instanceof ScrollView) return;
            ((ViewGroup) p).setClipChildren(false);
            p = p.getParent();
        }
    }

    /** Rows scroll sideways (the focused card is kept on screen); grids lay out in lines. */
    private View wrapRow(Context c, Node n, LinearLayout line) {
        line.setClipChildren(false);
        line.setClipToPadding(false); // clipping to its padding would cut the focused card's growth and ring
        if (n.type.equals("grid")) {
            line.setPadding(0, Ui.dp(c, 8), Ui.dp(c, 24), Ui.dp(c, 8));
            return line;
        }
        line.setPadding(0, Ui.dp(c, 8), Ui.dp(c, 24), Ui.dp(c, 8));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        clipRow(c, hs);
        hs.addView(line);
        return hs;
    }

    /** Service and Search cards in a row/grid use the tile style of the row's card template. */
    private static Node asTiles(Node n) {
        if (!n.isNav() && !n.itemStyle.equals("tile") && !n.itemStyle.equals("icon")) n.itemStyle = "tile";
        return n;
    }

    // ---- focus graph ------------------------------------------------------------------------------------------

    private View first(Node n) {
        List<View> l = focusables.get(n);
        if (l != null) for (View v : l) if (visible(v)) return v;
        for (Node c : n.children) {
            View v = first(c);
            if (v != null) return v;
        }
        return null;
    }

    /** Visible up to this home (works before the views are attached, unlike isShown). */
    private boolean visible(View v) {
        while (v != null && v != this) {
            if (v.getVisibility() != View.VISIBLE) return false;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return true;
    }

    private void wireFocus() {
        for (Node n : layout.nodes()) applyFocus(n);
    }

    /** Applies a component's focus rules to every focusable it holds (or that its children hold). */
    private void applyFocus(Node n) {
        if (n.focus.isEmpty()) return;
        List<View> mine = new ArrayList<>();
        collect(n, mine);
        for (Map.Entry<String, String> e : n.focus.entrySet()) {
            Node t = ids.get(e.getValue());
            View target = t == null ? null : first(t);
            int id = target == null ? View.NO_ID : target.getId(); // nothing there right now: geometric focus
            for (View v : mine) {
                switch (e.getKey()) {
                    case "up": v.setNextFocusUpId(id); break;
                    case "down": v.setNextFocusDownId(id); break;
                    case "left": v.setNextFocusLeftId(id); break;
                    default: v.setNextFocusRightId(id); break;
                }
            }
        }
    }

    private void collect(Node n, List<View> out) {
        List<View> l = focusables.get(n);
        if (l != null) out.addAll(l);
        for (Node c : n.children) collect(c, out);
    }

    // ---- state ----------------------------------------------------------------------------------------------

    private int brandOf(Services.Service s) {
        int b = s == null ? 0 : icons.colorFor(s.iconKey);
        return b == 0 ? Ui.ACCENT : b;
    }

    private void showService(Services.Service s) {
        String n = notices.get(s.id);
        heroText("Service", s.name, n != null ? n : s.tagline, n != null ? Ui.AMBER : 0);
        for (TextView t : focusServices) t.setText(s.name);
        for (ServiceShelf sh : shelves) sh.show(s.id);
        if (backdrop != null) {
            String img = null;
            for (HomeView.Card k : cont) if (k.service.equals(s.id) && !k.item.image.isEmpty()) { img = k.item.image; break; }
            if (img == null) for (HomeView.Card k : favs) if (k.service.equals(s.id) && !k.item.image.isEmpty()) { img = k.item.image; break; }
            backdrop.show(img, brandOf(s), s.iconKey);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String now = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date());
            for (TextView t : clocks) t.setText(now);
            removeCallbacks(this);
            if (!clocks.isEmpty()) postDelayed(this, 30_000L);
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

    @Override
    void setStatus(String s) {
        status = s;
        for (TextView t : statuses) t.setText(s.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    void setNotices(Map<String, String> n) {
        notices.clear();
        notices.putAll(n);
        for (Map.Entry<String, List<TextView>> e : serviceLabels.entrySet()) {
            for (TextView t : e.getValue()) t.setTextColor(notices.containsKey(e.getKey()) ? Ui.AMBER : Ui.TEXT);
        }
    }

    @Override
    void setRows(List<HomeView.Card> c, List<HomeView.Card> f) {
        cont = c;
        favs = f;
        for (DataList d : lists) if (d.fixedService == null) d.refill();
        for (ServiceShelf s : shelves) s.refill();
        if (backdrop != null && backdrop.n.source.equals("focused")) {
            int k = 0;
            for (List<HomeView.Card> l : java.util.Arrays.asList(cont, favs)) {
                for (HomeView.Card card : l) if (!card.item.image.isEmpty() && k++ < 3) heroArt.get(com.goomero.cleartv.tv.Images.largest(card.item.image).get(0));
            }
        }
        wireFocus(); // focus targets may have appeared or gone with the data
    }

    @Override
    void focusDefault() {
        Node n = layout.defaultFocus == null ? null : ids.get(layout.defaultFocus);
        View v = n == null ? null : first(n);
        if (v == null) v = first(layout.home);
        if (v != null) v.requestFocus();
    }

    @Override
    void prepare() {
        tick.run();
    }

    @Override
    void release() {
        art.trim();
        heroArt.trim();
    }

    /** A throwaway home (the picker snapshot): stops its image loaders. */
    void dispose() {
        art.shutdown();
        heroArt.shutdown();
    }

    // ---- assets ------------------------------------------------------------------------------------------------

    /** A theme asset: bundled, or an https image fetched once with its size cap (maxKb), decoded downsampled. */
    private void loadAsset(String name, final ImageView into) {
        final Layout.Asset a = name == null ? null : layout.assets.get(name);
        if (a == null) return;
        if (a.bundled != null) {
            if (a.bundled.equals("stars")) into.setImageDrawable(starsDrawable());
            else into.setImageResource(R.drawable.banner);
            return;
        }
        final int target = getResources().getDisplayMetrics().widthPixels / 2;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = fetch(a.url, a.maxKb * 1024, target);
                if (b != null) main.post(new Runnable() { public void run() { into.setImageBitmap(b); } });
            }
        }, "theme-asset").start();
    }

    static Bitmap fetch(String url, int cap, int targetW) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); // an https link only; a redirect could go anywhere
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200 || c.getContentLength() > cap) return null;
            String type = c.getContentType();
            if (type == null || !type.startsWith("image/")) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    bo.write(buf, 0, n);
                    if (bo.size() > cap) return null;
                }
            }
            byte[] d = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (o.outWidth <= 0 || o.outWidth > 8192 || o.outHeight > 8192) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= targetW) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(d, 0, d.length, o);
        } catch (Exception e) {
            android.util.Log.w(TAG, "asset: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- tokens ------------------------------------------------------------------------------------------------

    private static int color(String ref, int def) {
        return color(ref, def, Ui.ACCENT);
    }

    private static int color(String ref, int def, int brand) {
        if (ref == null) return def;
        switch (ref) {
            case "background": return Ui.BG;
            case "deep": return Ui.DEEP;
            case "panel": return Ui.BAR;
            case "surface": return Ui.SURFACE;
            case "line": return Ui.LINE;
            case "text": return Ui.TEXT;
            case "muted": return Ui.MUTED;
            case "soft": return Ui.SOFT;
            case "accent": return Ui.ACCENT;
            case "good": return Ui.GREEN;
            case "bad": return Ui.RED;
            case "warn": return Ui.AMBER;
            case "card": return Ui.CARD_FILL;
            case "brand": return Ui.SERVICE_ACCENTS ? brand : Ui.ACCENT;
            case "white": return 0xFFFFFFFF;
            case "transparent": return 0;
            default:
                Integer c = com.goomero.cleartv.theme.Theme.parseColor(ref);
                return c == null ? def : c;
        }
    }

    private static int gravity(String g) {
        switch (g) {
            case "top": return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case "top-end": return Gravity.TOP | Gravity.END;
            case "start": return Gravity.CENTER_VERTICAL | Gravity.START;
            case "center": return Gravity.CENTER;
            case "end": return Gravity.CENTER_VERTICAL | Gravity.END;
            case "bottom-start": return Gravity.BOTTOM | Gravity.START;
            case "bottom": return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            case "bottom-end": return Gravity.BOTTOM | Gravity.END;
            default: return Gravity.TOP | Gravity.START;
        }
    }

    private static int alignH(String a) {
        return a.equals("center") ? Gravity.CENTER_HORIZONTAL : a.equals("end") ? Gravity.END : Gravity.START;
    }

    private static int alignV(String a) {
        return a.equals("center") ? Gravity.CENTER_VERTICAL : a.equals("end") ? Gravity.BOTTOM : Gravity.TOP;
    }

    /** A service's backdrop when it has no art: a brand-coloured glow and its logo drawn large on the right. */
    static final class BrandArt extends Drawable {
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
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height();
            int rgb = brand & 0x00FFFFFF;
            c.drawColor(Ui.BG);
            p.setShader(new android.graphics.LinearGradient(0, 0, w, h * 0.6f, new int[]{rgb | 0x14000000, rgb | 0x59000000}, null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            float cx = w * 0.74f, cy = h * 0.28f, rad = h * 0.55f;
            p.setShader(new android.graphics.RadialGradient(cx, cy, rad, new int[]{rgb | 0x99000000, rgb | 0x26000000, 0}, new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rad, p);
            p.setShader(null);
            if (logo == null) return;
            float s = 140 * d;
            android.graphics.RectF r = new android.graphics.RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
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
}
