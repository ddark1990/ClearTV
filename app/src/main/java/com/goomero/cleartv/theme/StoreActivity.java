package com.goomero.cleartv.theme;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.goomero.cleartv.Panel;
import com.goomero.cleartv.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings > Theme > Get themes: the store's themes with a preview of the focused one. OK opens Install / Update /
 * Use / Delete. Every download is size-, hash- and validator-checked by {@link ThemeStore}.
 */
public final class StoreActivity extends Activity {
    private LinearLayout list;
    private ImageView image;
    private TextView pName, pInfo, pDesc, status;
    private Panel panel;
    private List<StoreIndex.Entry> entries = new ArrayList<>();
    private String error;
    private boolean loading = true;
    private final Map<String, Bitmap> previews = new HashMap<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private StoreIndex.Entry shown;
    /** Focus and message carried over when a theme change rebuilds this screen. */
    private static String keepKey, keepStatus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y + Ui.S1), Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, Ui.S1), 0, Ui.dp(this, Ui.S1), Ui.dp(this, Ui.S2));
        sv.addView(list);
        cols.addView(sv, new LinearLayout.LayoutParams(Ui.dp(this, 360), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(Ui.dp(this, Ui.S3), Ui.dp(this, Ui.S1), 0, 0);
        right.addView(Ui.oneLine(Ui.kicker(this, "Preview", 11, Ui.SOFT)));
        pName = Ui.oneLine(Ui.kicker(this, "", 22, Ui.TEXT));
        right.addView(pName, Ui.below(this, Ui.S_HALF));
        pInfo = Ui.oneLine(Ui.text(this, "", 13, Ui.MUTED));
        right.addView(pInfo, Ui.below(this, Ui.S_HALF));
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.BAR);
        bg.setCornerRadius(Ui.dp(this, 8));
        image.setBackground(bg);
        image.setClipToOutline(true);
        right.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 512), Ui.dp(this, 288)));
        ((LinearLayout.LayoutParams) image.getLayoutParams()).topMargin = Ui.dp(this, Ui.S2);
        pDesc = Ui.lines(Ui.text(this, "", 14, Ui.MUTED), 3);
        right.addView(pDesc, Ui.below(this, Ui.S2));
        status = Ui.lines(Ui.text(this, "", 13, Ui.SOFT), 3);
        right.addView(status, Ui.below(this, Ui.S1));
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(cols);
        panel = new Panel(this, root);
        setContentView(root);
        render(null);
        load();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (panel.isShowing() && panel.handleKey(ev)) return true;
        return super.dispatchKeyEvent(ev);
    }

    private void load() {
        loading = true;
        error = null;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                List<StoreIndex.Entry> got = null;
                String err = null;
                try {
                    got = ThemeStore.index();
                } catch (Theme.Invalid e) {
                    err = "The store's index is not valid: " + e.problems.get(0);
                } catch (java.io.IOException e) {
                    err = "Could not reach the theme store: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
                final List<StoreIndex.Entry> g = got;
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        loading = false;
                        error = fe;
                        if (g != null) entries = g;
                        render(keepKey);
                        if (keepStatus != null) status.setText(keepStatus);
                        keepKey = null;
                        keepStatus = null;
                    }
                });
            }
        });
    }

    private String focusedKey() {
        View f = list.findFocus();
        return f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
    }

    private void render(String focusKey) {
        list.removeAllViews();
        list.addView(Ui.oneLine(Ui.kicker(this, "Theme", 11, Ui.SOFT)));
        list.addView(Ui.oneLine(Ui.kicker(this, "Get themes", 26, Ui.TEXT)), Ui.below(this, Ui.S_HALF));
        TextView src = Ui.lines(Ui.text(this, "From " + ThemeStore.source(), 12, Ui.SOFT), 2);
        list.addView(src, Ui.below(this, Ui.S1));
        View first = null, want = null;
        if (loading) {
            list.addView(Ui.text(this, "Loading the store…", 15, Ui.MUTED), Ui.below(this, Ui.S2));
        } else if (error != null) {
            list.addView(Ui.lines(Ui.text(this, error, 14, Ui.MUTED), 4), Ui.below(this, Ui.S2));
            first = row("retry", "Try again", null);
        } else if (entries.isEmpty()) {
            list.addView(Ui.text(this, "The store has no themes yet.", 15, Ui.MUTED), Ui.below(this, Ui.S2));
        }
        for (StoreIndex.Entry e : entries) {
            String a = ThemeStore.action(e);
            String tail = a.isEmpty() ? "  ·  installed" : a.equals("Update") ? "  ·  update" : "";
            if (e.id.equals(Themes.activeId())) tail += "  ·  on";
            View v = row("e:" + e.id, e.name + tail, e);
            if (first == null) first = v;
            if (("e:" + e.id).equals(focusKey)) want = v;
        }
        if (want == null) want = first;
        if (want != null) want.requestFocus();
        else show(null);
    }

    private View row(final String key, String label, final StoreIndex.Entry e) {
        TextView v = Ui.oneLine(Ui.text(this, label, 16, Ui.TEXT));
        v.setTag(key);
        v.setFocusable(true);
        v.setClickable(true);
        int p = Ui.dp(this, Ui.S2);
        v.setPadding(p, Ui.dp(this, Ui.S1 + Ui.S_HALF), p, Ui.dp(this, Ui.S1 + Ui.S_HALF));
        v.setBackground(Ui.focusBackground(this, Ui.BAR, Ui.SURFACE));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                if (e == null) load();
                else actions(e);
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View x, boolean has) {
                if (has) show(e);
            }
        });
        list.addView(v, Ui.below(this, Ui.S1));
        return v;
    }

    private void show(final StoreIndex.Entry e) {
        shown = e;
        if (e == null) {
            pName.setText("");
            pInfo.setText("");
            pDesc.setText("");
            image.setImageDrawable(null);
            return;
        }
        pName.setText(e.name.toUpperCase(java.util.Locale.ROOT));
        String a = ThemeStore.action(e);
        pInfo.setText("by " + e.author + "  ·  version " + e.version + "  ·  " + (a.isEmpty() ? "installed" : a.equals("Update") ? "update available" : "not installed"));
        pDesc.setText(e.description);
        Bitmap b = previews.get(e.id);
        image.setImageBitmap(b);
        if (b != null || previews.containsKey(e.id)) return;
        previews.put(e.id, null);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap got = null;
                try {
                    got = ThemeStore.preview(e, Ui.dp(StoreActivity.this, 512));
                } catch (java.io.IOException ignored) {
                    // no preview: the details still show
                }
                final Bitmap g = got;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (g == null) {
                            previews.remove(e.id);
                            return;
                        }
                        previews.put(e.id, g);
                        if (shown == e) image.setImageBitmap(g);
                    }
                });
            }
        });
    }

    private void actions(final StoreIndex.Entry e) {
        final List<String> rows = new ArrayList<>();
        String a = ThemeStore.action(e);
        if (!a.isEmpty()) rows.add(a + " " + e.name);
        if (Themes.find(e.id) != null && !e.id.equals(Themes.activeId())) rows.add("Use " + e.name);
        if (Themes.fromStore(e.id)) rows.add(Themes.isBuiltIn(e.id) ? "Go back to the built-in " + e.name : "Delete " + e.name);
        rows.add("Cancel");
        panel.show(e.name, rows.toArray(new String[0]), new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                String r = rows.get(which);
                if (r.startsWith("Install") || r.startsWith("Update")) install(e);
                else if (r.startsWith("Use")) {
                    keepKey = "e:" + e.id;
                    keepStatus = e.name + " is on.";
                    Themes.select(e.id);
                } else if (r.startsWith("Delete") || r.startsWith("Go back")) {
                    keepKey = "e:" + e.id;
                    keepStatus = r.startsWith("Delete") ? "Deleted " + e.name + "." : "Back to the built-in " + e.name + ".";
                    boolean rebuilds = e.id.equals(Themes.activeId());
                    Themes.removeFromStore(e.id);
                    status.setText(keepStatus);
                    render("e:" + e.id);
                    if (!rebuilds) keepKey = keepStatus = null;
                }
            }
        });
    }

    private void install(final StoreIndex.Entry e) {
        status.setText("Downloading " + e.name + "…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    ThemeStore.install(e);
                    msg = "Installed " + e.name + " (checked: size, SHA-256, theme validator). OK on it again to use it.";
                } catch (Theme.Invalid x) {
                    msg = "Not installed: " + x.problems.get(0);
                } catch (java.io.IOException x) {
                    msg = "Not installed: " + (x.getMessage() != null ? x.getMessage() : x.getClass().getSimpleName());
                }
                final String m = msg;
                keepKey = "e:" + e.id;
                keepStatus = m;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        // updating the theme on screen rebuilds this screen, which picks up keepKey/keepStatus
                        if (e.id.equals(Themes.activeId()) && Themes.previewing() == null) return;
                        keepKey = null;
                        keepStatus = null;
                        if (isFinishing()) return;
                        status.setText(m);
                        render("e:" + e.id);
                    }
                });
            }
        });
    }
}
