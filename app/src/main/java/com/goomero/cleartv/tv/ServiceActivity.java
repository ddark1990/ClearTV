package com.goomero.cleartv.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.goomero.cleartv.Panel;
import com.goomero.cleartv.Ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The native ClearTV screen for a video service (YouTube, Tubi, Prime Video): ClearTV's graphite layout and
 * D-pad focus with the service's logo and accent. A section rail on the left (search, the service's sections,
 * account), the focused title's details on top, and rows of artwork drawn by {@link ShelfView}. OK plays or opens,
 * BACK steps back through opened pages, MENU has reload and sign-in/out.
 */
public final class ServiceActivity extends Activity implements ShelfView.Listener {
    private static final String TAG = "CTV.Service";
    public static final String EXTRA_SERVICE = "service", EXTRA_URL = "url", EXTRA_SIGNIN = "signin", EXTRA_ITEM = "item";
    /** Open straight into the profile picker (Settings → "switch profile"). */
    public static final String EXTRA_PROFILES = "ctvProfiles";

    private static final class PageState {
        final String title;
        final Item.Page page;
        int row, col;

        PageState(String title, Item.Page page) {
            this.title = title;
            this.page = page;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Source source;
    private Images images, heroImages;
    private FrameLayout heroBox, previewBox;
    private HoverPreview preview;
    private ImageView hero;
    private LinearLayout actionsRow, seasonRow;
    private android.widget.HorizontalScrollView seasonScroll;
    private Item.Detail detail;
    private final List<TextView> actionButtons = new ArrayList<>();
    private FrameLayout root;
    private LinearLayout rail;
    private TextView accountItem, searchItem;
    private final List<TextView> navItems = new ArrayList<>();
    private TextView crumb, title, meta, desc, status;
    private EditText searchBox;
    private ShelfView shelf;
    private Panel panel;
    private View codeCard;
    private ProfileCard profileCard;
    /** Services whose picker already opened in this run of the app ("ask every time", first time after sign-in). */
    private static final java.util.Set<String> profileAsked = new java.util.HashSet<>();
    private int accent;
    private int selectedNav = -1;
    private String navTitle = "";
    private Item.Page current;
    /** The Continue watching row put on top of the first section, or null when there is none. */
    private volatile Item.Shelf continueRow;
    private final ArrayList<PageState> stack = new ArrayList<>();
    private boolean loadingMore;
    private int generation; // bumps on every navigation so late answers for an old page are dropped

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra(EXTRA_SERVICE);
        source = Source.create(this, id == null ? "youtube" : id);
        accent = Ui.serviceAccent(source.accent());
        buildUi();
        images = new Images(this, shelf.cardWidthPx(), new Runnable() {
            @Override
            public void run() {
                shelf.invalidate();
            }
        });
        shelf.setImages(images);
        heroImages = new Images(this, Ui.dp(this, 640), new Runnable() {
            @Override
            public void run() {
                refreshHero();
            }
        });
        selectNav(0);
        if (source.id().equals("all")) openSearch();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    public static final String EXTRA_VOICE = "ctvVoice";

    private void handleIntent(Intent in) {
        if (in.getBooleanExtra(EXTRA_VOICE, false)) {
            in.removeExtra(EXTRA_VOICE);
            main.post(new Runnable() {
                @Override
                public void run() {
                    voiceSearch();
                }
            });
        }
        boolean direct = in.getBooleanExtra(EXTRA_SIGNIN, false) || in.getStringExtra(EXTRA_ITEM) != null
                || in.getStringExtra(EXTRA_URL) != null;
        if (in.getBooleanExtra(EXTRA_PROFILES, false) && source.hasProfiles() && source.signedIn()) {
            in.removeExtra(EXTRA_PROFILES);
            profileAsked.add(source.id());
            direct = true;
            main.post(new Runnable() {
                @Override
                public void run() {
                    showProfiles(false);
                }
            });
        }
        // the profile picker: every time when asked to, else once while no profile was chosen yet
        if (!direct && source.hasProfiles() && source.signedIn() && !profileAsked.contains(source.id())
                && (source.askProfile() || source.profileId().isEmpty())) {
            profileAsked.add(source.id());
            main.post(new Runnable() {
                @Override
                public void run() {
                    showProfiles(true);
                }
            });
        }
        if (in.getBooleanExtra(EXTRA_SIGNIN, false)) {
            in.removeExtra(EXTRA_SIGNIN);
            main.post(new Runnable() {
                @Override
                public void run() {
                    account();
                }
            });
        }
        // a tile from home (Continue watching, favourites, all-services search): open it like a click here
        final Item fromHome = Library.decode(in.getStringExtra(EXTRA_ITEM));
        if (fromHome != null) {
            in.removeExtra(EXTRA_ITEM);
            main.post(new Runnable() {
                @Override
                public void run() {
                    onClickItem(fromHome);
                }
            });
            return;
        }
        String url = in.getStringExtra(EXTRA_URL);
        if (url != null && source.id().equals("prime")) {
            // a Prime Video title link opens its native title page
            in.removeExtra(EXTRA_URL);
            String path = Uri.parse(url).getPath();
            if (path != null && path.startsWith("/detail/")) {
                final Item it = new Item(Item.PAGE, path, "Title", "", "", "", path);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onClickItem(it);
                    }
                });
            }
            return;
        }
        if (url == null || !source.id().equals("youtube")) return;
        in.removeExtra(EXTRA_URL);
        Uri u = Uri.parse(url);
        String v = u.getQueryParameter("v");
        if (v == null && "youtu.be".equals(u.getHost()) && u.getPath() != null && u.getPath().length() > 1) v = u.getPath().substring(1);
        if (v == null && url.contains("#/watch?v=")) v = Uri.parse("https://x/?" + url.substring(url.indexOf("#/watch?") + 8)).getQueryParameter("v");
        if (v != null) play(new Item(Item.VIDEO, v, "", "", "", "", ""));
    }

    // ------------------------------------------------------------------------------------------- UI

    /** Layout engine (theme): "classic", "apple" (top tabs over a full-bleed backdrop) or "rail" (wide sidebar, banner). */
    private final boolean apple = Ui.ENGINE.equals("apple"), sidebar = Ui.ENGINE.equals("rail");
    private String focusArt;

    private void buildUi() {
        Context c = this;
        root = new FrameLayout(c);
        root.setBackgroundColor(Ui.BG);
        // the hint of the service's theme: a soft wash of its colour from the top left
        View wash = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{(Ui.serviceAccent(source.accent2()) & 0x00FFFFFF) | 0x38000000, (Ui.serviceAccent(source.accent2()) & 0x00FFFFFF) | 0x0A000000, 0x00000000});
        wash.setBackground(g);
        root.addView(wash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // title pages: the title's artwork top right, fading into the background on its left and bottom edges
        heroBox = new FrameLayout(c);
        hero = new ImageView(c);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBox.addView(hero, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View fadeL = new View(c);
        fadeL.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.BG, (Ui.BG & 0x00FFFFFF) | 0x99000000, 0x00000000}));
        heroBox.addView(fadeL, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View fadeB = new View(c);
        // apple: a long, eased fade so the full-bleed backdrop melts into the background with no visible edge
        fadeB.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, apple
                ? new int[]{Ui.BG, (Ui.BG & 0x00FFFFFF) | 0xCC000000, (Ui.BG & 0x00FFFFFF) | 0x40000000, 0x00000000}
                : new int[]{Ui.BG, 0x00000000}));
        heroBox.addView(fadeB, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, apple ? 300 : 120), Gravity.BOTTOM));
        heroBox.setVisibility(View.GONE);
        root.addView(heroBox, heroParams());
        // hover previews: the same corner box, its own fades, shown only while a preview plays
        previewBox = new FrameLayout(c);
        previewBox.addView(new View(c));
        View pfL = new View(c);
        pfL.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.BG, (Ui.BG & 0x00FFFFFF) | 0x99000000, 0x00000000}));
        previewBox.addView(pfL, new FrameLayout.LayoutParams(Ui.dp(c, 260), ViewGroup.LayoutParams.MATCH_PARENT));
        View pfB = new View(c);
        pfB.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{Ui.BG, 0x00000000}));
        previewBox.addView(pfB, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, apple ? 200 : 60), Gravity.BOTTOM));
        previewBox.setVisibility(View.GONE);
        root.addView(previewBox, heroParams());
        if (apple || sidebar) {
            // apple and rail: the backdrop of whatever is focused is always there
            heroBox.setVisibility(View.VISIBLE);
            if (sidebar) {
                heroBox.setBackground(Ui.rounded(Ui.DEEP, Ui.dp(c, Math.max(Ui.RADIUS, 8))));
                heroBox.setClipToOutline(true);
                previewBox.setBackground(Ui.rounded(Ui.DEEP, Ui.dp(c, Math.max(Ui.RADIUS, 8))));
                previewBox.setClipToOutline(true);
            }
        }
        preview = new HoverPreview(this, source, io, previewBox);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(apple ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(c, Ui.SAFE_X - 8), Ui.dp(c, Ui.SAFE_Y), Ui.dp(c, Ui.SAFE_X - 24), Ui.dp(c, Ui.SAFE_Y - 12));
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // section rail
        rail = new LinearLayout(c);
        rail.setOrientation(apple ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (apple) rail.setGravity(Gravity.CENTER_VERTICAL);
        if (sidebar) {
            // a wide frosted sidebar, full height
            rail.setBackground(Ui.rounded(Ui.CARD_FILL, Ui.dp(c, Math.max(Ui.RADIUS, 8))));
            rail.setPadding(Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S2));
        }
        LinearLayout brand = new LinearLayout(c);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(Ui.dp(c, Ui.S1), 0, apple ? Ui.dp(c, Ui.S3) : 0, apple ? 0 : Ui.dp(c, Ui.S1));
        ImageView logo = new ImageView(c);
        Bitmap lb = null;
        try (InputStream in = getAssets().open("icons/" + source.iconKey() + ".webp")) {
            lb = BitmapFactory.decodeStream(in);
        } catch (IOException ignored) {
        }
        if (lb != null) {
            logo.setImageBitmap(lb);
            brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)));
        }
        TextView name = Ui.kicker(c, source.name(), 13, Ui.TEXT);
        name.setPadding(Ui.dp(c, Ui.S1), 0, 0, 0);
        Ui.oneLine(name);
        brand.addView(name);
        rail.addView(brand);
        View bar = new View(c);
        bar.setBackgroundColor(accent);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 2));
        barLp.setMargins(Ui.dp(c, Ui.S1), 0, 0, Ui.dp(c, Ui.S1));
        if (!apple) rail.addView(bar, barLp);

        searchItem = railItem("Search", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch();
            }
        });
        final List<Source.Nav> navs = source.navs();
        for (int i = 0; i < navs.size(); i++) {
            final int idx = i;
            navItems.add(railItem(navs.get(i).label, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectNav(idx);
                }
            }));
        }
        View spacer = new View(c);
        rail.addView(spacer, apple ? new LinearLayout.LayoutParams(0, 1, 1) : new LinearLayout.LayoutParams(1, 0, 1));
        accountItem = railItem("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                account();
            }
        });
        updateAccountItem();
        if (apple) row.addView(rail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40)));
        else row.addView(rail, new LinearLayout.LayoutParams(Ui.dp(c, sidebar ? 212 : 172), ViewGroup.LayoutParams.MATCH_PARENT));

        // content
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        // apple: the title sits low on the backdrop; rail: inside the banner's lower half
        content.setPadding(apple ? Ui.dp(c, Ui.S1) : Ui.dp(c, Ui.S3), Ui.dp(c, apple ? 56 : sidebar ? 92 : 0), 0, 0);
        if (sidebar) content.setPadding(Ui.dp(c, Ui.S3 + Ui.S3), Ui.dp(c, 92), 0, 0);
        crumb = Ui.oneLine(Ui.kicker(c, source.name(), 11, Ui.SOFT));
        content.addView(crumb);
        title = Ui.text(c, "", 24, Ui.TEXT);
        title.setTypeface(Ui.display(c));
        Ui.oneLine(title);
        content.addView(title, Ui.below(c, Ui.S_HALF));
        meta = Ui.kicker(c, "", 11, accent);
        Ui.oneLine(meta);
        content.addView(meta, Ui.below(c, Ui.S_HALF));
        desc = Ui.text(c, "", 14, Ui.MUTED);
        Ui.lines(desc, 2);
        desc.setMinLines(2); // the header keeps its height whatever is focused, so the shelves never jump
        LinearLayout.LayoutParams dlp = Ui.below(c, Ui.S1);
        dlp.rightMargin = Ui.dp(c, Ui.S3);
        content.addView(desc, dlp);

        // title page controls: Play/Resume, trailer, watchlist; then the season selector
        actionsRow = new LinearLayout(c);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        actionsRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        alp.topMargin = Ui.dp(c, Ui.S2);
        content.addView(actionsRow, alp);
        seasonScroll = new android.widget.HorizontalScrollView(c);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonScroll.setClipToPadding(false);
        seasonScroll.setPadding(Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S_HALF));
        seasonRow = new LinearLayout(c);
        seasonRow.setOrientation(LinearLayout.HORIZONTAL);
        seasonScroll.addView(seasonRow);
        seasonScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        sslp.topMargin = Ui.dp(c, Ui.S1);
        content.addView(seasonScroll, sslp);

        searchBox = new EditText(c);
        searchBox.setId(View.generateViewId());
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setHint(source.searchHint());
        searchBox.setHintTextColor(Ui.SOFT);
        searchBox.setTextColor(Ui.TEXT);
        searchBox.setTextSize(16);
        searchBox.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
        searchBox.setBackground(focusBg(Ui.BAR));
        searchBox.setVisibility(View.GONE);
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                runSearch();
                return true;
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        slp.setMargins(0, Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S3), 0);
        content.addView(searchBox, slp);

        FrameLayout shelfFrame = new FrameLayout(c);
        shelf = new ShelfView(c);
        shelf.setId(View.generateViewId());
        shelf.setAccent(accent);
        shelf.setListener(this);
        shelfFrame.addView(shelf, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = Ui.kicker(c, "", 13, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(c, Ui.S4), 0, Ui.dp(c, Ui.S4), 0);
        status.setLineSpacing(0, Ui.LINE_H);
        shelfFrame.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        flp.topMargin = Ui.dp(c, Ui.S2);
        content.addView(shelfFrame, flp);
        if (apple) row.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        else row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        if (apple || sidebar) textRightMargin(Ui.S3);

        panel = new Panel(c, root);
        setContentView(root);
        updateAccountItem();
    }

    private Drawable focusBg(int normal) {
        float r = Ui.dp(this, Ui.RADIUS);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = Ui.rounded(Ui.SURFACE, r);
        f.setStroke(Ui.dp(this, Ui.RING), accent);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{android.R.attr.state_pressed}, f);
        s.addState(new int[]{}, Ui.rounded(normal, r));
        return s;
    }

    private TextView railItem(String label, View.OnClickListener click) {
        TextView t = Ui.text(this, label, 15, Ui.MUTED);
        t.setId(View.generateViewId());
        t.setFocusable(true);
        t.setClickable(true);
        Ui.oneLine(t);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, Ui.S2), 0, Ui.dp(this, Ui.S1), 0);
        t.setBackground(focusBg(0));
        t.setOnClickListener(click);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                styleRail();
            }
        });
        LinearLayout.LayoutParams lp = apple ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, sidebar ? 40 : Ui.S4));
        if (apple) {
            // tabs across the top: DOWN goes to the shelves
            lp.rightMargin = Ui.dp(this, Ui.S1);
            t.setPadding(Ui.dp(this, Ui.S2), 0, Ui.dp(this, Ui.S2), 0);
        }
        if (sidebar) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16 * Ui.FONT_SCALE);
        rail.addView(t, lp);
        return t;
    }

    private void styleRail() {
        if (searchBox == null || shelf == null || accountItem == null) return; // still building
        for (int i = 0; i < navItems.size(); i++) {
            TextView t = navItems.get(i);
            boolean sel = i == selectedNav && searchBox.getVisibility() != View.VISIBLE;
            t.setTextColor(t.isFocused() ? Ui.TEXT : sel ? accent : Ui.MUTED);
        }
        searchItem.setTextColor(searchItem.isFocused() ? Ui.TEXT : searchBox.getVisibility() == View.VISIBLE ? accent : Ui.MUTED);
        accountItem.setTextColor(accountItem.isFocused() ? Ui.TEXT : source.signedIn() ? Ui.GREEN : Ui.MUTED);
        View left = selectedNav >= 0 && searchBox.getVisibility() != View.VISIBLE ? navItems.get(selectedNav) : searchItem;
        shelf.setNextFocusLeftId(left.getId());
        int up = searchBox.getVisibility() == View.VISIBLE ? searchBox.getId() : detailUpTarget();
        // apple: UP from the shelves goes back to the tab row
        if (apple && up == View.NO_ID) up = left.getId();
        shelf.setNextFocusUpId(up);
        if (apple) {
            for (TextView t : navItems) t.setNextFocusDownId(shelf.getId());
            searchItem.setNextFocusDownId(searchBox.getVisibility() == View.VISIBLE ? searchBox.getId() : shelf.getId());
            accountItem.setNextFocusDownId(shelf.getId());
        }
    }

    private void updateAccountItem() {
        String label = !source.signedIn() ? "Sign in"
                : !source.profileName().isEmpty() ? source.profileName()
                : source.accountLabel().isEmpty() ? "Account · signed in" : source.accountLabel();
        accountItem.setText(label);
        // the all-services search has no account of its own
        accountItem.setVisibility(source.id().equals("all") ? View.GONE : View.VISIBLE);
        styleRail();
    }

    // --------------------------------------------------------------------------------- navigation

    private void selectNav(int idx) {
        showPhoneCard(false);
        final Source.Nav nav = source.navs().get(idx);
        selectedNav = idx;
        stack.clear();
        searchBox.setVisibility(View.GONE);
        navTitle = nav.label;
        styleRail();
        final boolean first = idx == 0;
        load(nav.label, new Loader() {
            @Override
            public Item.Page run() throws IOException {
                // the section chosen now: selectedNav may already be -1 (search opened) when this runs
                Item.Page p = source.load(nav);
                if (first) PageCache.put(ServiceActivity.this, source.id(), p);
                return first ? withContinue(p) : p;
            }
        }, false);
        // the first section opens instantly from the copy saved last time (or preloaded at start); the fresh
        // rows replace it in place when they arrive
        if (first) {
            Item.Page cached = PageCache.get(this, source.id());
            if (cached != null && !cached.isEmpty()) {
                showingCache = true;
                show(withContinue(cached), null, 0, 0);
            }
        }
    }

    /**
     * The first section with this service's Continue watching row on top: the same saved progress as home's row
     * (ClearTV's own first, then the signed-in account's history), only this service's titles. The page itself
     * (and the copy PageCache keeps) stays without it, so the row is always fresh.
     */
    private Item.Page withContinue(Item.Page raw) {
        Item.Shelf row = continueShelf();
        continueRow = row;
        if (raw == null || row == null) return raw;
        Item.Page p = new Item.Page();
        p.shelves.add(row);
        p.shelves.addAll(raw.shelves);
        p.more = raw.more;
        p.moreExtendsGrid = raw.moreExtendsGrid;
        p.empty = raw.empty;
        p.detail = raw.detail;
        p.seasons.addAll(raw.seasons);
        p.season = raw.season;
        p.grid = raw.grid >= 0 ? raw.grid + 1 : -1;
        return p;
    }

    private Item.Shelf continueShelf() {
        String svc = source.id();
        if (svc.equals("all")) return null;
        List<Item> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Library.Entry e : Library.continueWatching(this)) {
            if (!e.service.equals(svc) || !seen.add(e.item.id)) continue;
            e.item.progress = e.progress();
            items.add(e.item);
        }
        int n = 0;
        for (Item it : Library.history(this, svc)) {
            if (n++ >= 6 || items.size() >= Library.MAX_PROGRESS) break;
            if (seen.add(it.id)) items.add(it);
        }
        return items.isEmpty() ? null : new Item.Shelf("Continue watching", items);
    }

    /** Back from the player on the first section: the Continue watching row shows the new position. */
    @Override
    protected void onRestart() {
        super.onRestart();
        refreshContinue();
    }

    /** Rebuilds the Continue watching row of the first section when it is on screen. */
    private void refreshContinue() {
        Item.Page p = current;
        if (p == null || selectedNav != 0 || !stack.isEmpty() || p.detail != null) return;
        Item.Shelf old = continueRow;
        boolean had = old != null && !p.shelves.isEmpty() && p.shelves.get(0) == old;
        Item.Shelf row = continueShelf();
        if (!had && row == null) return;
        int fr = shelf.focusRow(), fc = shelf.focusCol();
        if (had) p.shelves.remove(0);
        if (row != null) p.shelves.add(0, row);
        int delta = (row != null ? 1 : 0) - (had ? 1 : 0);
        if (p.grid >= 0) p.grid += delta;
        continueRow = row;
        // the row on top: focus stays on it (first tile, the title just watched); else on the same tile below
        if (had && fr == 0) fc = 0;
        else fr = Math.max(0, fr + delta);
        shelf.setPage(p, fr, fc);
    }

    /** The page on screen is the saved copy; the live one replaces it keeping the focus. */
    private boolean showingCache;

    private interface Loader {
        Item.Page run() throws IOException;
    }

    /** Loads a page in the background; {@code push} keeps the current page for BACK. */
    private void load(final String pageTitle, final Loader loader, final boolean push) {
        final int gen = ++generation;
        showingCache = false;
        if (push && current != null) {
            PageState ps = new PageState(navTitle, current);
            ps.row = shelf.focusRow();
            ps.col = shelf.focusCol();
            stack.add(ps);
            searchBox.setVisibility(View.GONE); // an opened page (series, channel) covers the search box
        }
        navTitle = pageTitle;
        crumb.setText((source.name() + " · " + pageTitle).toUpperCase(Locale.ROOT));
        showStatus("Loading…");
        shelf.setPage(new Item.Page(), 0, 0);
        boolean keepFocus = actionsRow.hasFocus() || seasonRow.hasFocus();
        showDetail(null, null);
        if (keepFocus) shelf.requestFocus();
        current = null;
        loadingMore = false;
        io.execute(new Runnable() {
            @Override
            public void run() {
                Item.Page p;
                String err = null;
                try {
                    p = loader.run();
                } catch (IOException e) {
                    Log.w(TAG, source.id() + " " + pageTitle + ": " + e.getMessage());
                    p = null;
                    err = message(e);
                }
                final Item.Page page = p;
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || isFinishing()) return;
                        if (showingCache) {
                            showingCache = false;
                            if (page != null) show(page, null, shelf.focusRow(), shelf.focusCol());
                            return;
                        }
                        show(page, error, 0, 0);
                    }
                });
            }
        });
    }

    private static String message(IOException e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) return "Not reachable. Check the connection.";
        if (e instanceof Http.Error) return "The service answered " + ((Http.Error) e).code + ". Try again later.";
        String m = e.getMessage();
        return m == null ? "Something went wrong." : m;
    }

    private void show(Item.Page page, String error, int row, int col) {
        current = page;
        showDetail(page == null ? null : page.detail, page);
        if (page == null) {
            showStatus(error);
            shelf.setPage(new Item.Page(), 0, 0);
            return;
        }
        shelf.setPage(page, row, col);
        // a first part with only a row or two (Live TV): bring the next rows without waiting for focus
        if (page.more != null && shelf.nearEnd()) onNearEnd();
        if (page.isEmpty()) {
            showStatus(page.empty);
            title.setText("");
            meta.setText("");
            desc.setText("");
        } else {
            status.setVisibility(View.GONE);
            if (!rail.hasFocus() && !searchBox.hasFocus() && !panel.isShowing() && codeCard == null && detail == null) shelf.requestFocus();
        }
        if (detail != null) {
            showDetailText();
            if (page.isEmpty()) status.setVisibility(View.GONE);
            if (!rail.hasFocus() && !panel.isShowing() && codeCard == null) {
                if (!actionButtons.isEmpty()) actionButtons.get(0).requestFocus();
                else if (!shelf.isEmpty()) shelf.requestFocus();
            }
        }
        if (!shelf.hasFocus() && !rail.hasFocus() && !searchBox.hasFocus() && !panel.isShowing() && codeCard == null
                && !actionsRow.hasFocus() && !seasonRow.hasFocus()) {
            (selectedNav >= 0 ? navItems.get(selectedNav) : searchItem).requestFocus();
        }
    }

    private void showStatus(String s) {
        status.setText(s == null ? "" : s.toUpperCase(Locale.ROOT));
        status.setVisibility(View.VISIBLE);
    }

    private static final int VOICE_REQ = 4711;

    /**
     * The remote's voice/search key: Android's speech recognizer when this TV offers one to apps, else the
     * keyboard (Fire OS often keeps the mic for Alexa, so the keyboard is the usual fallback).
     */
    private void voiceSearch() {
        Intent v = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        v.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        v.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search " + source.name());
        if (v.resolveActivity(getPackageManager()) != null) {
            try {
                startActivityForResult(v, VOICE_REQ);
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
            }
        }
        Toast.makeText(this, "Voice search isn't available on this TV. Type your search instead.", Toast.LENGTH_SHORT).show();
        openSearch();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != VOICE_REQ) return;
        java.util.ArrayList<String> r = data == null ? null : data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        openSearch();
        if (result == RESULT_OK && r != null && !r.isEmpty()) {
            searchBox.setText(r.get(0));
            runSearch();
        }
    }

    private void openSearch() {
        stack.clear();
        selectedNav = -1;
        searchBox.setVisibility(View.VISIBLE);
        styleRail();
        crumb.setText((source.name() + " · Search").toUpperCase(Locale.ROOT));
        showPhoneCard(true);
        searchBox.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
    }

    /** While search is open: the phone page's QR in the corner, for typing the query on a phone. */
    private void showPhoneCard(boolean show) {
        if (show && phoneCard == null) {
            phoneCard = new com.goomero.cleartv.PhoneCard(this, accent, true, "Scan to type your search on your phone.");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y));
            root.addView(phoneCard, lp);
        } else if (!show && phoneCard != null) {
            root.removeView(phoneCard);
            phoneCard = null;
        }
    }

    private View phoneCard;

    private void runSearch() {
        final String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) return;
        showPhoneCard(false);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        stack.clear();
        shelf.requestFocus();
        load("Search: " + q, new Loader() {
            @Override
            public Item.Page run() throws IOException {
                return source.search(q);
            }
        }, false);
    }

    // ---------------------------------------------------------------------------- ShelfView.Listener

    @Override
    public void onFocusItem(Item it) {
        if (preview != null) preview.focus(it);
        if (detail != null) {
            // on a title page the show stays the title; the focused episode fills the lines under it
            if (!shelf.hasFocus()) return;
            meta.setText((it.title + (it.subtitle.isEmpty() ? "" : " · " + it.subtitle)).toUpperCase(Locale.ROOT));
            desc.setText(it.description.isEmpty() ? detail.synopsis : it.description);
            return;
        }
        title.setText(it.title);
        meta.setText(it.subtitle.toUpperCase(Locale.ROOT));
        desc.setText(it.description);
        if (apple || sidebar) {
            // the focused tile's artwork fills the backdrop (landscape art, centre-cropped to the wide area)
            focusArt = it.image == null || it.image.isEmpty() ? null : it.image;
            if (focusArt == null) hero.setImageDrawable(null);
            refreshHero();
        }
    }

    // ------------------------------------------------------------------------------- title page

    /** Header, buttons, season selector and artwork of a title page; hides them for browse pages. */
    private void showDetail(Item.Detail d, final Item.Page page) {
        detail = d;
        actionButtons.clear();
        actionsRow.removeAllViews();
        seasonRow.removeAllViews();
        if (d == null) {
            heroBox.setVisibility(apple || sidebar ? View.VISIBLE : View.GONE);
            actionsRow.setVisibility(View.GONE);
            seasonScroll.setVisibility(View.GONE);
            Ui.lines(desc, 2);
            textRightMargin(apple ? 380 : sidebar ? 240 : Ui.S3);
            styleRail();
            return;
        }
        // the text column stops where the artwork begins
        textRightMargin(apple ? 380 : sidebar ? 240 : 300);
        Ui.lines(desc, page != null && !page.seasons.isEmpty() ? 2 : 3);
        heroBox.setVisibility(View.VISIBLE);
        hero.setImageBitmap(null);
        refreshHero();
        if (d.play != null) {
            final Item play = d.play;
            addAction("▶  " + d.playLabel, accent, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    play(play);
                }
            });
        }
        if (d.trailer != null) {
            final Item trailer = d.trailer;
            addAction("Trailer", Ui.TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    play(trailer);
                }
            });
        }
        if (d.watchlist != Item.Detail.WL_NONE) {
            addAction(d.watchlist == Item.Detail.WL_REMOVE ? "✓  In watchlist" : "+  Watchlist", Ui.TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleWatchlist();
                }
            });
        }
        if (d.play == null && !d.note.isEmpty()) {
            TextView n = Ui.kicker(this, d.note, 12, Ui.MUTED);
            Ui.oneLine(n);
            n.setPadding(Ui.dp(this, Ui.S1), 0, 0, 0);
            actionsRow.addView(n);
        }
        actionsRow.setVisibility(actionsRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (page != null && page.seasons.size() > 1) {
            for (int i = 0; i < page.seasons.size(); i++) {
                final int idx = i;
                final Item.Season season = page.seasons.get(i);
                TextView chip = Ui.text(this, season.label, 14, i == page.season ? accent : Ui.MUTED);
                chip.setId(View.generateViewId());
                chip.setFocusable(true);
                chip.setClickable(true);
                Ui.oneLine(chip);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(Ui.dp(this, Ui.S2), 0, Ui.dp(this, Ui.S2), 0);
                chip.setBackground(focusBg(i == page.season ? Ui.SURFACE : 0));
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickSeason(page, idx, season);
                    }
                });
                chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean has) {
                        if (has) showDetailText();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, Ui.S4));
                lp.rightMargin = Ui.dp(this, Ui.S1);
                seasonRow.addView(chip, lp);
            }
            seasonScroll.setVisibility(View.VISIBLE);
        } else {
            seasonScroll.setVisibility(View.GONE);
        }
        // D-pad: buttons -> seasons -> grid, and back up the same way
        int down = seasonScroll.getVisibility() == View.VISIBLE && seasonRow.getChildCount() > 0
                ? seasonRow.getChildAt(Math.max(0, Math.min(page.season, seasonRow.getChildCount() - 1))).getId() : shelf.getId();
        for (TextView b : actionButtons) b.setNextFocusDownId(down);
        for (int i = 0; i < seasonRow.getChildCount(); i++) {
            seasonRow.getChildAt(i).setNextFocusDownId(shelf.getId());
            if (!actionButtons.isEmpty()) seasonRow.getChildAt(i).setNextFocusUpId(actionButtons.get(0).getId());
        }
        styleRail();
    }

    private void textRightMargin(int dpRight) {
        for (View v : new View[]{title, meta, desc}) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            int px = Ui.dp(this, dpRight);
            if (lp.rightMargin != px) {
                lp.rightMargin = px;
                v.setLayoutParams(lp);
            }
        }
    }

    private int detailUpTarget() {
        if (detail == null || seasonRow == null) return View.NO_ID;
        if (seasonScroll.getVisibility() == View.VISIBLE && seasonRow.getChildCount() > 0) {
            int sel = current == null ? 0 : Math.max(0, Math.min(current.season, seasonRow.getChildCount() - 1));
            return seasonRow.getChildAt(sel).getId();
        }
        return actionButtons.isEmpty() ? View.NO_ID : actionButtons.get(0).getId();
    }

    private void showDetailText() {
        if (detail == null) return;
        title.setText(detail.title);
        meta.setText(detail.meta.toUpperCase(Locale.ROOT));
        desc.setText(detail.synopsis);
    }

    private void addAction(String label, int color, View.OnClickListener click) {
        TextView b = Ui.text(this, label, 15, color);
        b.setId(View.generateViewId());
        b.setFocusable(true);
        b.setClickable(true);
        Ui.oneLine(b);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, Ui.S3), 0, Ui.dp(this, Ui.S3), 0);
        b.setBackground(focusBg(Ui.BAR));
        b.setOnClickListener(click);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (has) showDetailText();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = Ui.dp(this, Ui.S1);
        actionsRow.addView(b, lp);
        actionButtons.add(b);
    }

    private void refreshHero() {
        if (heroImages == null) return;
        String url = detail != null ? detail.image : focusArt;
        if (url == null || url.isEmpty()) return;
        Bitmap b = heroImages.get(url);
        if (b != null) hero.setImageBitmap(b);
    }

    /** Where the backdrop goes: classic top-right artwork, apple full-bleed top half, rail a banner beside the sidebar. */
    private FrameLayout.LayoutParams heroParams() {
        if (apple) return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 420), Gravity.TOP);
        if (sidebar) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 196), Gravity.TOP);
            lp.leftMargin = Ui.dp(this, Ui.SAFE_X - 8 + 212 + Ui.S3);
            lp.rightMargin = Ui.dp(this, Ui.SAFE_X - 24);
            lp.topMargin = Ui.dp(this, Ui.SAFE_Y);
            return lp;
        }
        return new FrameLayout.LayoutParams(Ui.dp(this, 520), Ui.dp(this, 292), Gravity.TOP | Gravity.END);
    }

    /** A season from the selector: episodes already loaded switch in place; otherwise its page loads. */
    private void pickSeason(final Item.Page page, int idx, final Item.Season season) {
        if (idx == page.season) {
            if (!shelf.isEmpty()) shelf.requestFocus();
            return;
        }
        if (season.episodes != null && page.grid >= 0) {
            page.season = idx;
            page.shelves.set(page.grid, season.episodes);
            if (detail != null && !season.episodes.items.isEmpty()) {
                detail.play = season.episodes.items.get(0);
                detail.playLabel = "Play " + Source.shortEpisode(detail.play.title);
            }
            show(page, null, 0, 0);
            seasonRow.getChildAt(idx).requestFocus();
            return;
        }
        if (season.link == null) return;
        load(navTitle, new Loader() {
            @Override
            public Item.Page run() throws IOException {
                return source.openDetail(season.link);
            }
        }, false);
    }

    private void toggleWatchlist() {
        final Item.Detail d = detail;
        if (d == null) return;
        if (d.watchlist == Item.Detail.WL_SIGN_IN) {
            Toast.makeText(this, "Sign in to " + source.name() + " to use your watchlist.", Toast.LENGTH_LONG).show();
            account();
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    int st = source.toggleWatchlist(d);
                    msg = st == Item.Detail.WL_REMOVE ? "Added to your watchlist" : "Removed from your watchlist";
                } catch (IOException e) {
                    msg = message(e);
                }
                final String m = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ServiceActivity.this, m, Toast.LENGTH_SHORT).show();
                        if (detail == d && current != null) {
                            int focusIdx = -1;
                            for (int i = 0; i < actionButtons.size(); i++) if (actionButtons.get(i).isFocused()) focusIdx = i;
                            showDetail(d, current);
                            if (focusIdx >= 0 && focusIdx < actionButtons.size()) actionButtons.get(focusIdx).requestFocus();
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onClickItem(final Item it) {
        // a tile from another service (all-services search) opens in that service's own screen
        if (it.service != null && !it.service.equals(source.id())) {
            openInService(this, it.service, it);
            return;
        }
        if (source.opensDetail(it)) {
            load(it.title, new Loader() {
                @Override
                public Item.Page run() throws IOException {
                    return source.openDetail(it);
                }
            }, true);
            return;
        }
        if (it.kind == Item.VIDEO || it.kind == Item.LIVE) {
            play(it);
            return;
        }
        load(it.title, new Loader() {
            @Override
            public Item.Page run() throws IOException {
                return source.open(it);
            }
        }, true);
    }

    @Override
    public void onNearEnd() {
        if (current == null || current.more == null || loadingMore) return;
        loadingMore = true;
        final String token = current.more;
        final Item.Page target = current;
        current.more = null;
        final int gen = generation;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Item.Page more = source.more(token);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            loadingMore = false;
                            if (gen != generation || more == null || current != target) return;
                            target.more = more.more;
                            if (more.moreExtendsGrid && target.grid >= 0 && target.grid == target.shelves.size() - 1) {
                                // a long feed (history, subscriptions): the new tiles continue the grid
                                List<Item> add = new java.util.ArrayList<>();
                                for (Item.Shelf s : more.shelves) add.addAll(s.items);
                                target.shelves.get(target.grid).items.addAll(add);
                                shelf.appendGrid(add);
                            } else {
                                target.shelves.addAll(more.shelves);
                                shelf.append(more);
                            }
                            if (!target.isEmpty()) status.setVisibility(View.GONE);
                            // the part brought too little to scroll into (or nothing ClearTV shows): fetch the next
                            if (target.more != null && shelf.nearEnd()) onNearEnd();
                        }
                    });
                } catch (IOException e) {
                    Log.w(TAG, "more rows: " + e.getMessage());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            loadingMore = false;
                        }
                    });
                }
            }
        });
    }

    /** Rows already loading their next tiles. */
    private final java.util.Set<Item.Shelf> rowsLoading = new java.util.HashSet<>();

    @Override
    public void onRowNearEnd(final Item.Shelf row) {
        if (row.more == null || rowsLoading.contains(row)) return;
        rowsLoading.add(row);
        final String token = row.more;
        final int gen = generation;
        io.execute(new Runnable() {
            @Override
            public void run() {
                Item.Shelf got = null;
                try {
                    got = source.moreRow(token);
                } catch (IOException e) {
                    Log.w(TAG, "more tiles: " + e.getMessage());
                }
                final Item.Shelf more = got;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        rowsLoading.remove(row);
                        if (gen != generation) return;
                        if (more == null) {
                            row.more = null; // the service gave nothing more (or failed): stop asking
                            return;
                        }
                        // skip tiles the row already has (services repeat a few across pages)
                        java.util.Set<String> have = new java.util.HashSet<>();
                        for (Item i : row.items) have.add(i.id);
                        for (Item i : more.items) if (have.add(i.id)) row.items.add(i);
                        row.more = more.more;
                        shelf.rowGrew(row);
                    }
                });
            }
        });
    }

    private void play(final Item it) {
        final long pressed = android.os.SystemClock.uptimeMillis();
        if (it.kind == Item.LIVE) LiveLists.watched(this, source.id(), it);
        // Continue watching is a mix of unrelated titles: nothing from it plays next
        Item.Shelf from = shelfOf(it);
        final java.util.List<Item> queue = Launcher.after(from != null && from == continueRow ? null : from, it);
        long saved = it.kind == Item.LIVE ? 0 : Library.resumeAt(this, source.id(), it.id);
        if (saved <= 0) {
            Toast.makeText(this, "Opening " + (it.title.isEmpty() ? "video" : it.title) + "…", Toast.LENGTH_SHORT).show();
            Launcher.play(this, source, it, queue, pressed);
            return;
        }
        // partly watched: ask, in ClearTV's own panel
        panel.show(it.title, new String[]{"Resume from " + Launcher.clock(saved), "Start over"}, new com.goomero.cleartv.Panel.OnPick() {
            @Override
            public void onPick(int which) {
                Launcher.play(ServiceActivity.this, source, it, queue, android.os.SystemClock.uptimeMillis(), which == 1);
            }
        });
    }

    /** The row on the current page holding {@code it} (the episode grid on a title page). */
    private Item.Shelf shelfOf(Item it) {
        if (current == null) return null;
        for (Item.Shelf s : current.shelves) if (s.items.contains(it)) return s;
        // Play on a title page starts an episode of the grid (or the first one)
        if (current.grid >= 0 && current.grid < current.shelves.size()) return current.shelves.get(current.grid);
        return null;
    }

    // -------------------------------------------------------------------------------------- account

    private void account() {
        if (source.id().equals("all")) return;
        if (source.signedIn()) {
            String who = !source.profileName().isEmpty() ? "Watching as " + source.profileName()
                    : source.accountLabel().isEmpty() ? "Signed in" : "Signed in as " + source.accountLabel();
            final java.util.List<String> rows = new java.util.ArrayList<>();
            rows.add(who);
            if (source.hasProfiles()) {
                rows.add("Switch profile");
                rows.add("Ask for a profile every time: " + (source.askProfile() ? "On" : "Off"));
            }
            rows.add("Sign out of " + source.name());
            rows.add("Cancel");
            panel.show(source.name() + " account", rows.toArray(new String[0]), new Panel.OnPick() {
                @Override
                public void onPick(int which) {
                    String r = rows.get(which);
                    if (r.equals("Switch profile")) showProfiles(false);
                    else if (r.startsWith("Ask for a profile")) {
                        source.setAskProfile(!source.askProfile());
                        Toast.makeText(ServiceActivity.this, source.askProfile() ? "ClearTV will ask who's watching each time"
                                : "ClearTV will use " + (source.profileName().isEmpty() ? "this profile" : source.profileName()), Toast.LENGTH_SHORT).show();
                    } else if (r.startsWith("Sign out")) signOut();
                }
            });
            return;
        }
        startCodeSignIn();
    }

    /** The shared profile picker over the screen ({@code auto}: opened by ClearTV, a lone profile is used silently). */
    private void showProfiles(boolean auto) {
        if (profileCard != null || codeCard != null || !source.hasProfiles()) return;
        profileCard = new ProfileCard(this, source, accent, auto, new ProfileCard.Listener() {
            @Override
            public void onPicked(Source.Profile p) {
                closeProfiles();
                updateAccountItem();
                Toast.makeText(ServiceActivity.this, "Watching as " + p.name, Toast.LENGTH_SHORT).show();
                reload();
            }

            @Override
            public void onClosed() {
                closeProfiles();
            }
        });
        setBehindFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 800), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(profileCard, lp);
        profileCard.start();
    }

    private void closeProfiles() {
        if (profileCard == null) return;
        profileCard.close();
        profileCard = null;
        setBehindFocusable(true);
        if (!shelf.isEmpty()) shelf.requestFocus();
        else accountItem.requestFocus();
    }

    /** Keeps focus inside a card over the screen. */
    private void setBehindFocusable(boolean on) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v == profileCard || !(v instanceof ViewGroup)) continue;
            ((ViewGroup) v).setDescendantFocusability(on ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    private void signOut() {
        if (source.id().equals("prime")) WebActivity.clearWebSession(new String[]{PrimeSource.BASE + "/", "https://www.amazon.com/"});
        io.execute(new Runnable() {
            @Override
            public void run() {
                source.signOut();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        updateAccountItem();
                        Toast.makeText(ServiceActivity.this, "Signed out of " + source.name(), Toast.LENGTH_SHORT).show();
                        reload();
                    }
                });
            }
        });
    }

    private void reload() {
        if (searchBox.getVisibility() == View.VISIBLE && searchBox.length() > 0) runSearch();
        else selectNav(Math.max(0, selectedNav));
    }

    /** The shared QR sign-in card over the screen; it polls and renews its code by itself. */
    private void startCodeSignIn() {
        SignInCard card = new SignInCard(this, source, accent, new SignInCard.Listener() {
            @Override
            public void onSignedIn(Source s) {
                closeCodeCard();
                updateAccountItem();
                String who = s.accountLabel();
                Toast.makeText(ServiceActivity.this, "✓ Signed in to " + s.name() + (who.isEmpty() ? "" : " as " + who),
                        Toast.LENGTH_LONG).show();
                reload();
                if (s.hasProfiles()) {
                    profileAsked.add(s.id());
                    showProfiles(true);
                }
            }

            @Override
            public void onSignInFailed(Source s, String message) {
                closeCodeCard();
                if (!message.startsWith("DETAIL:")) {
                    Toast.makeText(ServiceActivity.this, message, Toast.LENGTH_LONG).show();
                    return;
                }
                showSignInError(s, shortDetail(message.substring(7)));
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, lp);
        card.requestFocus();
        codeCard = card;
        card.start();
    }

    /** A friendly sign-in error with an optional details line (status and error name only, never the raw answer). */
    private void showSignInError(final Source s, final String detail) {
        panel.show("Couldn't get a sign-in code from " + s.name() + ". Please try again in a minute.",
                new String[]{"Try again", "Show details", "Close"}, new Panel.OnPick() {
                    @Override
                    public void onPick(int which) {
                        if (which == 0) startCodeSignIn();
                        else if (which == 1) panel.show("Details: " + detail, new String[]{"Try again", "Close"}, new Panel.OnPick() {
                            @Override
                            public void onPick(int w) { if (w == 0) startCodeSignIn(); }
                        });
                    }
                });
    }

    private static String shortDetail(String raw) {
        java.util.regex.Matcher http = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(raw);
        java.util.regex.Matcher err = java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"([A-Za-z_]{1,40})\"").matcher(raw);
        String d = (http.find() ? "HTTP " + http.group(1) : "") + (err.find() ? (" · " + err.group(1)) : "");
        d = d.replaceFirst("^ · ", "");
        return d.isEmpty() ? (raw.length() > 60 ? raw.substring(0, 60) + "…" : raw) : d;
    }

    private void closeCodeCard() {
        if (codeCard == null) return;
        ((SignInCard) codeCard).close();
        codeCard = null;
        if (!shelf.isEmpty()) shelf.requestFocus();
        else accountItem.requestFocus();
    }

    // ------------------------------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        // any key stops a hover preview at once (focus landing on the next tile may start a new one)
        if (preview != null && ev.getAction() == KeyEvent.ACTION_DOWN && preview.isActive()) {
            preview.stop();
            previewBox.setVisibility(View.GONE);
        }
        if (panel.isShowing() && panel.handleKey(ev)) return true;
        int code = ev.getKeyCode();
        if (codeCard != null) {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) closeCodeCard();
            return true;
        }
        if (profileCard != null) {
            if (profileCard.handleKey(ev)) return true;
            if (code == KeyEvent.KEYCODE_MENU) return true;
            return super.dispatchKeyEvent(ev);
        }
        if ((code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) && profileCard == null) {
            if (ev.getAction() == KeyEvent.ACTION_UP) voiceSearch();
            return true;
        }
        // >> on a live station: add it to or remove it from this TV's live Favorites
        if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && shelf.hasFocus() && shelf.focusedItem() != null
                && shelf.focusedItem().kind == Item.LIVE) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                Item f = shelf.focusedItem();
                boolean now = LiveLists.toggleFavorite(this, source.id(), f);
                Toast.makeText(this, (now ? "★ Added to Favorites: " : "Removed from Favorites: ") + f.title, Toast.LENGTH_SHORT).show();
                reload();
            }
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_MENU) {
            if (shelf.hasFocus() && shelf.focusedItem() != null) onLongClickItem(shelf.focusedItem());
            else showMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) return true;
        if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN && searchBox.hasFocus() && !shelf.isEmpty()) {
            shelf.requestFocus();
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT && rail.hasFocus()) {
            if (!shelf.isEmpty()) shelf.requestFocus();
            else if (searchBox.getVisibility() == View.VISIBLE) searchBox.requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (ev.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    private void back() {
        if (!stack.isEmpty()) {
            PageState ps = stack.remove(stack.size() - 1);
            generation++;
            navTitle = ps.title;
            crumb.setText((source.name() + " · " + ps.title).toUpperCase(Locale.ROOT));
            if (stack.isEmpty() && selectedNav < 0) searchBox.setVisibility(View.VISIBLE); // back to the results
            show(ps.page, null, ps.row, ps.col);
            // back from a title page opened from the first section: what was just watched is on top
            if (stack.isEmpty()) refreshContinue();
            shelf.requestFocus();
            return;
        }
        if (shelf.hasFocus() || searchBox.hasFocus()) {
            (selectedNav >= 0 ? navItems.get(selectedNav) : searchItem).requestFocus();
            return;
        }
        finish();
    }

    /** Opens a tile in its service: Pluto TV tunes the channel, the others open their screen on the tile. */
    public static void openInService(android.app.Activity a, String service, Item it) {
        Intent i;
        if (service.equals("pluto")) {
            i = new Intent(a, com.goomero.cleartv.pluto.PlutoActivity.class);
            if (it != null) i.putExtra(com.goomero.cleartv.pluto.PlutoActivity.EXTRA_CHANNEL, it.id);
        } else {
            i = new Intent(a, ServiceActivity.class);
            i.putExtra(EXTRA_SERVICE, service);
            if (it != null) {
                Item copy = new Item(it.kind, it.id, it.title, it.subtitle, it.image, it.badge, it.extra);
                copy.description = it.description;
                i.putExtra(EXTRA_ITEM, Library.encode(copy));
            }
        }
        a.startActivity(i);
    }

    /** This screen's source when it is {@code service}'s, else null (GoToChannel reuses it). */
    Source sourceFor(String service) {
        return source != null && source.id().equals(service) ? source : null;
    }

    /** OK held (or MENU) on a tile: Play, Go to channel, Favorites (live stations), the Home watchlist and Continue watching. */
    @Override
    public void onLongClickItem(final Item it) {
        final String svc = it.service != null ? it.service : source.id();
        if (svc.equals("all")) return;
        final java.util.List<String> rows = new java.util.ArrayList<>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<>();
        rows.add(it.kind == Item.LIVE ? "Watch now" : "Play / open");
        acts.add(new Runnable() { public void run() { onClickItem(it); } });
        // the tile's channel, creator, series or show page (hidden where the service has none for it)
        String ch = GoToChannel.label(this, svc, it);
        if (ch != null) {
            rows.add(ch);
            acts.add(new Runnable() { public void run() {
                GoToChannel.open(ServiceActivity.this, svc, it, svc.equals(source.id()) ? new GoToChannel.Opener() {
                    @Override
                    public void openHere(Item page) {
                        onClickItem(page);
                    }
                } : null);
            } });
        }
        if (it.kind == Item.LIVE) {
            final boolean fav = LiveLists.isFavorite(this, svc, it.id);
            rows.add(fav ? "Remove from Favorites" : "★ Add to Favorites");
            acts.add(new Runnable() { public void run() {
                boolean now = LiveLists.toggleFavorite(ServiceActivity.this, svc, it);
                Toast.makeText(ServiceActivity.this, (now ? "★ Added to Favorites: " : "Removed from Favorites: ") + it.title, Toast.LENGTH_SHORT).show();
                if (svc.equals(source.id())) reload();
            } });
        }
        final boolean pinned = Library.isFavorite(this, svc, it.id);
        if (it.kind == Item.LIVE) rows.add(pinned ? "Remove from Home watchlist" : "Pin to Home watchlist");
        else rows.add(pinned ? "Remove from Favorites (Home watchlist)" : "★ Add to Favorites (Home watchlist)");
        acts.add(new Runnable() { public void run() {
            boolean now = Library.toggleFavorite(ServiceActivity.this, svc, it);
            Toast.makeText(ServiceActivity.this, now ? "★ Added to Favorites on Home: " + it.title : "Removed from Home: " + it.title, Toast.LENGTH_SHORT).show();
        } });
        if (Library.resumeAt(this, svc, it.id) > 0) {
            rows.add("Remove from Continue watching");
            acts.add(new Runnable() { public void run() {
                Library.forget(ServiceActivity.this, svc, it.id);
                Toast.makeText(ServiceActivity.this, "Removed from Continue watching", Toast.LENGTH_SHORT).show();
            } });
        }
        rows.add("Cancel");
        acts.add(null);
        panel.show(it.title, rows.toArray(new String[0]), new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                Runnable r = acts.get(which);
                if (r != null) r.run();
            }
        });
    }

    private void showMenu() {
        String acct = source.signedIn() ? "Sign out of " + source.name() : "Sign in to " + source.name();
        panel.show(source.name(), new String[]{"Reload", acct, "ClearTV home"}, new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                if (which == 0) reload();
                else if (which == 1) {
                    if (source.signedIn()) signOut();
                    else account();
                } else finish();
            }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            images.trim();
            heroImages.trim();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (preview != null) {
            preview.stop();
            previewBox.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (codeCard != null) ((SignInCard) codeCard).close();
        if (profileCard != null) profileCard.close();
        io.shutdownNow();
        images.shutdown();
        heroImages.shutdown();
        super.onDestroy();
    }
}
