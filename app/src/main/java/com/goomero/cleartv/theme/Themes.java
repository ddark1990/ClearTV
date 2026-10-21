package com.goomero.cleartv.theme;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.goomero.cleartv.Ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The active theme, the built-in and saved custom themes, timed previews and import from a link. Applying a theme
 * loads its tokens into {@link Ui} and recreates every open ClearTV screen except the players (their overlays use the
 * fixed video palette), so the home screen, Settings and every service's screens change together.
 *
 * <p>Stored in its own preferences file: the active id, the saved custom themes as validated JSON, and a pending
 * preview. A preview that is not confirmed reverts after its time; if ClearTV stops during a preview, the next start
 * goes back to the previous theme.
 */
public final class Themes {
    public static final List<String> BUILT_IN = Collections.unmodifiableList(Arrays.asList("default", "topshelf", "billboard"));
    public static final int MAX_CUSTOM = 12;
    public static final int PREVIEW_DEFAULT_S = Theme.PREVIEW_DEFAULT_S, PREVIEW_MIN_S = Theme.PREVIEW_MIN_S, PREVIEW_MAX_S = Theme.PREVIEW_MAX_S;
    private static final String TAG = "CTV.Theme";
    private static final String PREFS = "themes", ACTIVE = "active", IDS = "custom_ids", CUSTOM = "custom:",
            P_JSON = "pending_json", P_PREV = "pending_prev",
            OVERRIDE = "override:", STORE = "store:";

    /** Screens that a theme change must not rebuild (playing video). */
    private static final List<String> KEEP = Arrays.asList("PlayerActivity", "WebActivity");

    private static Context app;
    private static SharedPreferences prefs;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Theme> builtIns = new ArrayList<>();
    private static final List<WeakReference<Activity>> live = new ArrayList<>();
    private static Theme active = Theme.defaults();
    private static String activeId = "default";
    /** The theme on screen during a preview (null when none) and when the preview ends (uptime ms). */
    private static Theme pending;
    private static long pendingUntil;
    private static final List<Runnable> listeners = new ArrayList<>();

    private Themes() {}

    /** Called once from App.onCreate, before any screen: loads the active theme into {@link Ui}. */
    public static void init(Application a) {
        app = a.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String id : BUILT_IN) {
            try {
                builtIns.add(Theme.parse(readAsset("themes/" + id + ".json")));
            } catch (IOException | Theme.Invalid e) {
                Log.e(TAG, "built-in theme " + id + ": " + e.getMessage());
            }
        }
        // a built-in refreshed from the theme store replaces the bundled file (validated again here)
        for (int i = 0; i < builtIns.size(); i++) {
            String o = prefs.getString(OVERRIDE + builtIns.get(i).id, null);
            if (o == null) continue;
            try {
                Theme t = Theme.parse(o);
                if (t.id.equals(builtIns.get(i).id)) builtIns.set(i, t);
            } catch (Theme.Invalid e) {
                prefs.edit().remove(OVERRIDE + builtIns.get(i).id).remove(STORE + builtIns.get(i).id).apply();
            }
        }
        // ClearTV stopped during a preview: the previous theme stays
        if (prefs.contains(P_JSON)) prefs.edit().remove(P_JSON).remove(P_PREV).apply();
        Theme t = find(prefs.getString(ACTIVE, "default"));
        if (t == null) t = Theme.defaults();
        active = t;
        activeId = t.id;
        Ui.apply(active);
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity x, Bundle b) {
                live.add(new WeakReference<>(x));
                // the window behind the first frame matches the theme (players stay dark)
                x.getWindow().setBackgroundDrawable(new ColorDrawable(isPlayer(x) ? Ui.V_BG : Ui.BG));
            }
            @Override public void onActivityStarted(Activity x) {}
            @Override public void onActivityResumed(Activity x) { banner(x); }
            @Override public void onActivityPaused(Activity x) {}
            @Override public void onActivityStopped(Activity x) {}
            @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {}
            @Override public void onActivityDestroyed(Activity x) {
                for (int i = live.size() - 1; i >= 0; i--) {
                    Activity l = live.get(i).get();
                    if (l == null || l == x) live.remove(i);
                }
            }
        });
    }

    private static boolean isPlayer(Activity a) {
        return KEEP.contains(a.getClass().getSimpleName());
    }

    private static String readAsset(String path) throws IOException {
        try (InputStream in = app.getAssets().open(path)) {
            return new String(readCapped(in, Theme.MAX_BYTES), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------------------------------- queries

    public static synchronized Theme active() {
        return pending != null ? pending : active;
    }

    /** The id of the saved active theme (not a preview). */
    public static synchronized String activeId() {
        return activeId;
    }

    public static synchronized List<Theme> builtIns() {
        return new ArrayList<>(builtIns);
    }

    public static synchronized List<Theme> customs() {
        List<Theme> out = new ArrayList<>();
        for (String id : customIds()) {
            try {
                out.add(Theme.parse(prefs.getString(CUSTOM + id, "")));
            } catch (Theme.Invalid e) {
                Log.w(TAG, "dropping unreadable custom theme");
            }
        }
        return out;
    }

    private static List<String> customIds() {
        String s = prefs.getString(IDS, "");
        List<String> l = new ArrayList<>();
        for (String id : s.split(",")) if (!id.isEmpty()) l.add(id);
        return l;
    }

    public static synchronized Theme find(String id) {
        for (Theme t : builtIns) if (t.id.equals(id)) return t.copy();
        String json = prefs.getString(CUSTOM + id, null);
        if (json == null) return null;
        try {
            return Theme.parse(json);
        } catch (Theme.Invalid e) {
            return null;
        }
    }

    public static boolean isBuiltIn(String id) {
        return BUILT_IN.contains(id);
    }

    /** Seconds left in the running preview, or 0 without one. */
    public static synchronized int previewSecondsLeft() {
        if (pending == null) return 0;
        return (int) Math.max(1, (pendingUntil - SystemClock.uptimeMillis() + 999) / 1000);
    }

    public static synchronized Theme previewing() {
        return pending;
    }

    /** Runs on the main thread whenever the theme list, the active theme or a preview changes. */
    public static void addListener(Runnable r) {
        listeners.add(r);
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    // ------------------------------------------------------------------------------------------- changes

    /** Saves a validated custom theme (replacing one with the same id). Built-in ids are refused. */
    public static synchronized void save(Theme t) throws Theme.Invalid {
        if (isBuiltIn(t.id)) throw invalid("id", "reserved", "\"" + t.id + "\" is a built-in theme id; pick another id.");
        List<String> ids = customIds();
        if (!ids.contains(t.id)) {
            if (ids.size() >= MAX_CUSTOM) throw invalid("", "limit", "At most " + MAX_CUSTOM + " custom themes; delete one in Settings > Theme first.");
            ids.add(t.id);
        }
        prefs.edit().putString(CUSTOM + t.id, t.toJson()).putString(IDS, join(ids)).apply();
        notifyChanged();
    }

    public static synchronized void delete(String id) {
        if (isBuiltIn(id)) return;
        List<String> ids = customIds();
        ids.remove(id);
        prefs.edit().remove(CUSTOM + id).remove(STORE + id).putString(IDS, join(ids)).apply();
        if (id.equals(activeId)) select("default");
        else notifyChanged();
    }

    // ------------------------------------------------------------------------------------------- theme store

    /**
     * Installs a theme downloaded from the store (already size-, hash- and validator-checked by {@link ThemeStore}).
     * A built-in id replaces the bundled file until {@link #removeFromStore}; any other id is saved like a custom
     * theme. {@code rev} is the store entry's version, used to offer updates.
     */
    public static synchronized void installFromStore(Theme t, int rev) throws Theme.Invalid {
        if (isBuiltIn(t.id)) {
            for (int i = 0; i < builtIns.size(); i++) if (builtIns.get(i).id.equals(t.id)) builtIns.set(i, t.copy());
            prefs.edit().putString(OVERRIDE + t.id, t.toJson()).putInt(STORE + t.id, rev).apply();
            if (t.id.equals(activeId) && pending == null) {
                active = t.copy();
                applyNow();
            } else {
                notifyChanged();
            }
            return;
        }
        save(t);
        prefs.edit().putInt(STORE + t.id, rev).apply();
        if (t.id.equals(activeId) && pending == null) select(t.id);
    }

    /** The installed store version of a theme: 0 when it is not installed, 1 for a bundled built-in. */
    public static synchronized int storeRev(String id) {
        int r = prefs.getInt(STORE + id, 0);
        if (r > 0) return r;
        if (isBuiltIn(id)) return 1;
        return prefs.contains(CUSTOM + id) ? 1 : 0;
    }

    /** Whether the theme came from the store (built-ins: whether a store update replaced the bundled file). */
    public static synchronized boolean fromStore(String id) {
        return prefs.contains(STORE + id);
    }

    /** Deletes a store theme; for a refreshed built-in, goes back to the bundled file. */
    public static synchronized void removeFromStore(String id) {
        if (!isBuiltIn(id)) {
            delete(id);
            return;
        }
        prefs.edit().remove(OVERRIDE + id).remove(STORE + id).apply();
        for (int i = 0; i < builtIns.size(); i++) {
            if (!builtIns.get(i).id.equals(id)) continue;
            try {
                builtIns.set(i, Theme.parse(readAsset("themes/" + id + ".json")));
            } catch (IOException | Theme.Invalid e) {
                Log.e(TAG, "built-in theme " + id + ": " + e.getMessage());
            }
        }
        if (id.equals(activeId)) select(id);
        else notifyChanged();
    }

    /** Applies a saved theme (built-in or custom) for good, ending any preview. */
    public static synchronized boolean select(String id) {
        Theme t = find(id);
        if (t == null) return false;
        cancelTimer();
        pending = null;
        active = t;
        activeId = t.id;
        prefs.edit().putString(ACTIVE, t.id).remove(P_JSON).remove(P_PREV).apply();
        applyNow();
        return true;
    }

    /** Back to the Stargazer theme (saved custom themes stay). */
    public static void reset() {
        select("default");
    }

    /**
     * Shows a theme for {@code seconds}, then goes back unless {@link #confirm} is called. The theme does not need
     * to be saved yet; confirming saves a custom one.
     */
    public static synchronized void preview(Theme t, int seconds) {
        int s = Math.max(PREVIEW_MIN_S, Math.min(PREVIEW_MAX_S, seconds));
        cancelTimer();
        pending = t.copy();
        pendingUntil = SystemClock.uptimeMillis() + s * 1000L;
        prefs.edit().putString(P_JSON, pending.toJson()).putString(P_PREV, activeId).apply();
        main.postDelayed(revertTimer, s * 1000L);
        main.postDelayed(tick, 1000);
        applyNow();
    }

    /** Keeps the previewed theme (saving it when it is a custom one). Returns false without a preview. */
    public static synchronized boolean confirm() throws Theme.Invalid {
        if (pending == null) return false;
        Theme t = pending;
        if (!isBuiltIn(t.id)) save(t);
        return select(t.id);
    }

    /** Ends a preview now and shows the saved theme again. Returns false without a preview. */
    public static synchronized boolean revert() {
        if (pending == null) return false;
        cancelTimer();
        pending = null;
        prefs.edit().remove(P_JSON).remove(P_PREV).apply();
        applyNow();
        return true;
    }

    private static final Runnable revertTimer = new Runnable() {
        @Override
        public void run() {
            revert();
        }
    };

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (previewing() == null) return;
            for (WeakReference<Activity> w : new ArrayList<>(live)) {
                Activity a = w.get();
                if (a != null && !a.isFinishing()) banner(a);
            }
            notifyChanged();
            main.postDelayed(this, 1000);
        }
    };

    private static void cancelTimer() {
        main.removeCallbacks(revertTimer);
        main.removeCallbacks(tick);
    }

    private static Theme.Invalid invalid(String path, String code, String msg) {
        return Theme.problem(path, code, msg);
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() == 0 ? "" : ",").append(s);
        return b.toString();
    }

    /** Loads the theme on screen into Ui and rebuilds every open screen (players excepted), on the main thread. */
    private static void applyNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    applyNow();
                }
            });
            return;
        }
        Ui.apply(active());
        for (WeakReference<Activity> w : new ArrayList<>(live)) {
            Activity a = w.get();
            if (a == null || a.isFinishing() || isPlayer(a)) continue;
            a.getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
            a.recreate();
        }
        notifyChanged();
    }

    private static void notifyChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    notifyChanged();
                }
            });
            return;
        }
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    // ------------------------------------------------------------------------------------------- banner

    private static final int BANNER_ID = 0x7F0C7E01;

    /** The preview countdown, pinned top-right at the safe margin of the screen in front. */
    private static void banner(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return;
        View old = content.findViewById(BANNER_ID);
        int left = previewSecondsLeft();
        if (left == 0 || isPlayer(a)) {
            if (old != null) content.removeView(old);
            return;
        }
        TextView t = old instanceof TextView ? (TextView) old : null;
        if (t == null) {
            t = Ui.oneLine(Ui.kicker(a, "", 11, Ui.TEXT));
            t.setId(BANNER_ID);
            t.setBackground(Ui.card(a, Ui.BAR, Ui.ACCENT));
            int p = Ui.dp(a, Ui.S1);
            t.setPadding(Ui.dp(a, Ui.S2), p, Ui.dp(a, Ui.S2), p);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            lp.topMargin = Ui.dp(a, Ui.SAFE_Y);
            lp.rightMargin = Ui.dp(a, Ui.SAFE_X);
            content.addView(t, lp);
        }
        t.setText(("Theme preview · back in " + left + " s · keep it in Settings > Theme or on the phone").toUpperCase(java.util.Locale.ROOT));
        t.bringToFront();
    }

    // ------------------------------------------------------------------------------------------- control API

    private static ThemeApi api;

    /** The phone page's theme API over this store (see {@link ThemeApi}). */
    public static synchronized ThemeApi api() {
        if (api == null) {
            api = new ThemeApi(new ThemeApi.Backend() {
                @Override public Theme active() { return Themes.active(); }
                @Override public String activeId() { return Themes.activeId(); }
                @Override public Theme previewing() { return Themes.previewing(); }
                @Override public int previewSecondsLeft() { return Themes.previewSecondsLeft(); }
                @Override public List<Theme> builtIns() { return Themes.builtIns(); }
                @Override public List<Theme> customs() { return Themes.customs(); }
                @Override public Theme find(String id) { return Themes.find(id); }
                @Override public String schema() {
                    try {
                        return readAsset("themes/theme.schema.json");
                    } catch (IOException e) {
                        return "{}";
                    }
                }
                @Override public void preview(Theme t, int seconds) { Themes.preview(t, seconds); }
                @Override public void applyNow(Theme t) throws Theme.Invalid { Themes.keep(t); }
                @Override public boolean confirm() throws Theme.Invalid { return Themes.confirm(); }
                @Override public boolean revert() { return Themes.revert(); }
                @Override public void reset() { Themes.reset(); }
                @Override public long now() { return SystemClock.uptimeMillis(); }
            });
        }
        return api;
    }

    /** Saves (when custom) and applies a theme for good. */
    public static synchronized void keep(Theme t) throws Theme.Invalid {
        if (!isBuiltIn(t.id)) save(t);
        select(t.id);
    }

    // ------------------------------------------------------------------------------------------- import

    /**
     * Downloads a theme from an {@code https://} link (at most {@link Theme#MAX_BYTES}) and validates it. Blocking:
     * call off the main thread. Nothing is applied or saved here.
     */
    public static Theme fetch(String link) throws IOException, Theme.Invalid {
        URL u;
        try {
            u = new URL(link.trim());
        } catch (java.net.MalformedURLException e) {
            throw Theme.problem("url", "url", "That is not a web address.");
        }
        if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getHost().isEmpty())
            throw Theme.problem("url", "url", "Only https:// links are accepted.");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true); // HttpURLConnection never follows https -> http
        c.setRequestProperty("Accept", "application/json, text/plain");
        try {
            int code = c.getResponseCode();
            if (!"https".equalsIgnoreCase(c.getURL().getProtocol())) throw Theme.problem("url", "url", "The link redirected away from https.");
            if (code != 200) throw Theme.problem("url", "http", "The link answered " + code + ".");
            if (c.getContentLength() > Theme.MAX_BYTES) throw Theme.problem("", "too_large", "The theme is larger than " + Theme.MAX_BYTES / 1024 + " KB.");
            byte[] b;
            try (InputStream in = c.getInputStream()) {
                b = readCapped(in, Theme.MAX_BYTES + 1);
            }
            if (b.length > Theme.MAX_BYTES) throw Theme.problem("", "too_large", "The theme is larger than " + Theme.MAX_BYTES / 1024 + " KB.");
            return Theme.parse(new String(b, StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, Math.min(n, max + 1 - out.size()));
            if (out.size() > max) break;
        }
        return out.toByteArray();
    }
}
