package com.goomero.cleartv.theme;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.goomero.cleartv.PhoneCard;
import com.goomero.cleartv.Ui;

/**
 * Settings > Theme: the built-in and saved themes with a live preview of the focused one (OK applies it), the
 * running phone/agent preview with Keep and Revert, import from the phone page or a link, export via the phone page,
 * delete a saved theme (hold OK or Menu, then confirm), Get themes (the theme store) and Reset to Stargazer.
 */
public final class ThemeActivity extends Activity {
    private LinearLayout list;
    private ThemePreview preview;
    private TextView pName, pInfo, status;
    private View overlay;
    private String confirmDelete;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onChange = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && overlay == null) render(focusedKey());
        }
    };

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
        right.addView(Ui.oneLine(Ui.kicker(this, "Live preview", 11, Ui.SOFT)));
        pName = Ui.oneLine(Ui.kicker(this, "", 22, Ui.TEXT));
        right.addView(pName, Ui.below(this, Ui.S_HALF));
        pInfo = Ui.lines(Ui.text(this, "", 13, Ui.MUTED), 2);
        right.addView(pInfo, Ui.below(this, Ui.S_HALF));
        preview = new ThemePreview(this);
        right.addView(preview, Ui.below(this, Ui.S2));
        status = Ui.lines(Ui.text(this, "", 13, Ui.SOFT), 3);
        right.addView(status, Ui.below(this, Ui.S2));
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(cols);
        setContentView(root);
        render(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Themes.addListener(onChange);
        render(focusedKey());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Themes.removeListener(onChange);
    }

    private String focusedKey() {
        View f = list.findFocus();
        return f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
    }

    private interface Action {
        void run();
    }

    private void render(String focusKey) {
        list.removeAllViews();
        list.addView(Ui.oneLine(Ui.kicker(this, "Settings", 11, Ui.SOFT)));
        TextView title = Ui.kicker(this, "Theme", 26, Ui.TEXT);
        list.addView(Ui.oneLine(title), Ui.below(this, Ui.S_HALF));
        View first = null, want = null;
        // the quickest way in for your own themes: right under the title, one UP from the theme list
        row("phone", "Import from phone (paste or upload)", null, new Action() {
            public void run() {
                showPhone("Scan to paste or upload a theme file, check it, preview it here and keep it. AI agents use the same link (docs/AGENTS-API.md).");
            }
        });
        row("store", "Get themes (theme store)", null, new Action() {
            public void run() {
                startActivity(new android.content.Intent(ThemeActivity.this, StoreActivity.class));
            }
        });
        final Theme pending = Themes.previewing();
        if (pending != null) {
            section("Preview running");
            View k = row("keep", "Keep " + pending.name + " (back in " + Themes.previewSecondsLeft() + " s)", pending, new Action() {
                public void run() {
                    try {
                        Themes.confirm();
                    } catch (Theme.Invalid e) {
                        status.setText(e.getMessage());
                    }
                }
            });
            row("revert", "Go back now", null, new Action() {
                public void run() {
                    Themes.revert();
                }
            });
            first = k;
        }
        section("Built-in");
        String activeId = Themes.activeId();
        for (final Theme t : Themes.builtIns()) {
            View v = row("t:" + t.id, t.name + (t.id.equals(activeId) ? "  ·  on" : ""), t, select(t));
            if (first == null) first = v;
        }
        java.util.List<Theme> customs = Themes.customs();
        if (!customs.isEmpty()) {
            section("Your themes · hold OK to delete");
            for (final Theme t : customs) {
                String label = confirmDelete != null && confirmDelete.equals(t.id) ? "Press OK again to delete " + t.name
                        : t.name + (t.id.equals(activeId) ? "  ·  on" : "");
                View v = row("t:" + t.id, label, t, confirmDelete != null && confirmDelete.equals(t.id) ? new Action() {
                    public void run() {
                        confirmDelete = null;
                        Themes.delete(t.id);
                        status.setText("Deleted " + t.name + ".");
                    }
                } : select(t));
                v.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View x) {
                        askDelete(t.id);
                        return true;
                    }
                });
                v.setOnKeyListener(new View.OnKeyListener() {
                    @Override
                    public boolean onKey(View x, int code, KeyEvent e) {
                        if (code != KeyEvent.KEYCODE_MENU) return false;
                        if (e.getAction() == KeyEvent.ACTION_UP) askDelete(t.id);
                        return true;
                    }
                });
            }
        }
        section("More ways in");
        row("link", "Import from a link (https)", null, new Action() {
            public void run() {
                askLink();
            }
        });
        row("export", "Export current theme (on the phone page)", null, new Action() {
            public void run() {
                showPhone("Scan, then tap Export current theme: the theme on screen now downloads as a complete JSON file.");
            }
        });
        row("reset", "Reset to Stargazer", null, new Action() {
            public void run() {
                Themes.reset();
                status.setText("Back to the Stargazer theme.");
            }
        });
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (focusKey != null && focusKey.equals(c.getTag())) want = c;
        }
        if (want == null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                View c = list.getChildAt(i);
                if (("t:" + activeId).equals(c.getTag())) want = c;
            }
        }
        if (want == null) want = first;
        // The focused row's listener points the preview at that row. Fall back only when nothing took focus.
        if (want == null || !want.requestFocus()) show(Themes.active());
    }

    private Action select(final Theme t) {
        return new Action() {
            public void run() {
                Themes.select(t.id);
            }
        };
    }

    private void askDelete(String id) {
        confirmDelete = id;
        render("t:" + id);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (confirmDelete != null) {
                    confirmDelete = null;
                    if (!isFinishing()) render(focusedKey());
                }
            }
        }, 5000);
    }

    private void section(String s) {
        TextView t = Ui.kicker(this, s, 12, Ui.SOFT);
        t.setPadding(Ui.dp(this, Ui.S1), Ui.dp(this, Ui.S3), 0, Ui.dp(this, Ui.S_HALF));
        list.addView(Ui.oneLine(t));
    }

    private View row(String key, String label, final Theme theme, final Action a) {
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
                a.run();
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View x, boolean has) {
                // The name updates now. The picture waits until the highlight rests (ThemePreview).
                if (has) show(theme != null ? theme : Themes.active());
            }
        });
        LinearLayout.LayoutParams lp = Ui.below(this, Ui.S1);
        list.addView(v, lp);
        return v;
    }

    private void show(Theme t) {
        pName.setText(t.name.toUpperCase(java.util.Locale.ROOT));
        StringBuilder b = new StringBuilder();
        if (!t.author.isEmpty()) b.append("by ").append(t.author).append(" · ");
        b.append(t.light() ? "light" : "dark").append(" · ");
        if (t.ui != null) b.append("composed layout (").append(t.ui.nodes().size()).append(" components) · service screens: ").append(t.ui.serviceNav);
        else b.append(t.engine).append(" layout · ").append(t.header).append(" header");
        if (t.servicesStyle.equals("hero")) b.append(" · hero row");
        pInfo.setText(b.toString());
        preview.setTheme(t);
    }

    // ------------------------------------------------------------------------------------------- overlays

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (overlay != null && overlay.getTag() == null) {
            int k = ev.getKeyCode();
            if (ev.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) closeOverlay();
            return true;
        }
        if (overlay != null && ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (ev.getAction() == KeyEvent.ACTION_UP) closeOverlay();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    private void closeOverlay() {
        ((ViewGroup) overlay.getParent()).removeView(overlay);
        overlay = null;
        render(focusedKey());
    }

    private FrameLayout dim() {
        FrameLayout root = (FrameLayout) findViewById(android.R.id.content);
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xE6000000);
        dim.setFocusable(true);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = dim;
        return dim;
    }

    private void showPhone(String purpose) {
        FrameLayout d = dim();
        d.addView(new PhoneCard(this, Ui.ACCENT, false, purpose, "theme", "Themes on your phone"),
                new FrameLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        d.requestFocus();
    }

    /** A text box for an https link (remote keyboard or the phone keyboard); OK downloads, checks and saves it. */
    private void askLink() {
        FrameLayout d = dim();
        d.setTag("input");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.card(this, Ui.BAR, Ui.ACCENT));
        int p = Ui.dp(this, Ui.S3);
        box.setPadding(p, p, p, p);
        box.addView(Ui.oneLine(Ui.kicker(this, "Import from a link", 13, Ui.ACCENT)));
        box.addView(Ui.lines(Ui.text(this, "Type an https:// address of a theme JSON file (16 KB at most), then press OK. "
                + "It is checked, saved to Your themes and shown in the preview. BACK cancels.", 14, Ui.MUTED), 3), Ui.below(this, Ui.S1));
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText("https://");
        in.setSelection(in.length());
        in.setTextColor(Ui.TEXT);
        in.setHintTextColor(Ui.SOFT);
        in.setBackground(Ui.focusBackground(this, Ui.SURFACE, Ui.SURFACE));
        in.setPadding(Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1), Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1));
        in.setImeOptions(EditorInfo.IME_ACTION_GO);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(in, Ui.below(this, Ui.S2));
        final TextView msg = Ui.lines(Ui.text(this, "", 13, Ui.SOFT), 4);
        box.addView(msg, Ui.below(this, Ui.S1));
        in.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                fetchLink(in.getText().toString(), msg);
                return true;
            }
        });
        d.addView(box, new FrameLayout.LayoutParams(Ui.dp(this, 600), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        in.requestFocus();
    }

    private void fetchLink(final String link, final TextView msg) {
        msg.setText("Downloading…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                Theme got = null;
                try {
                    got = Themes.fetch(link);
                    Themes.save(got);
                } catch (Theme.Invalid e) {
                    StringBuilder b = new StringBuilder();
                    for (Theme.Problem p : e.problems) b.append(b.length() == 0 ? "" : "\n").append(p.toString());
                    err = b.toString();
                } catch (java.io.IOException e) {
                    err = "Could not download it (" + e.getClass().getSimpleName() + ").";
                }
                final String fe = err;
                final Theme ft = got;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (fe != null) {
                            msg.setText(fe);
                            return;
                        }
                        closeOverlay();
                        status.setText("Saved " + ft.name + ". OK on it applies it.");
                        render("t:" + ft.id);
                        show(ft);
                    }
                });
            }
        }, "theme-link").start();
    }
}
