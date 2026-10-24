package com.goomero.cleartv;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import android.widget.ImageView;
import android.graphics.drawable.GradientDrawable;
import com.goomero.cleartv.tv.Images;

/**
 * Settings: launcher-style rows (label on the left, value on the right in the display face) grouped under
 * kickers; OK toggles or cycles the value. Lists and confirmations use the in-window {@link Panel}, not dialogs.
 */
public final class SettingsActivity extends Activity {
    private Prefs prefs;
    private FilterManager filters;
    private LinearLayout list;
    private Panel panel;

    private interface Row {
        String label();

        /** Value shown on the right, or null for an action row. */
        String value();

        void onClick();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        App app = (App) getApplication();
        prefs = app.prefs;
        filters = app.filters;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, Ui.SAFE_X);
        list.setPadding(pad, Ui.dp(this, Ui.SAFE_Y + Ui.S1), pad, Ui.dp(this, Ui.SAFE_Y + Ui.S1));
        sv.addView(list);
        root.addView(sv);
        panel = new Panel(this, root);
        setContentView(root);
        render();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (panel.isShowing() && panel.handleKey(ev)) return true;
        if (phoneOverlay != null) {
            int k = ev.getKeyCode();
            if (ev.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
                ((ViewGroup) phoneOverlay.getParent()).removeView(phoneOverlay);
                phoneOverlay = null;
                render();
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    private static String onOff(boolean b) {
        return b ? "On" : "Off";
    }

    private void render() {
        View focused = list.findFocus();
        int focusIndex = focused != null ? list.indexOfChild(focused) : -1;
        list.removeAllViews();
        list.addView(Ui.oneLine(Ui.kicker(this, getString(R.string.app_name) + " · v" + BuildConfig.VERSION_NAME, 11, Ui.SOFT)));
        TextView title = Ui.kicker(this, getString(R.string.settings), 26, Ui.TEXT);
        title.setLetterSpacing(0.12f);
        list.addView(Ui.oneLine(title), Ui.below(this, Ui.S_HALF));

        section("Look");
        add(new Row() {
            public String label() { return "Theme and layout (built-in themes, your own themes, import and export)"; }
            public String value() { return com.goomero.cleartv.theme.Themes.active().name; }
            public void onClick() { startActivity(new android.content.Intent(SettingsActivity.this, com.goomero.cleartv.theme.ThemeActivity.class)); }
        });

        section(getString(R.string.sec_blocking));
        add(new Row() {
            public String label() { return "Ad & tracker blocking"; }
            public String value() { return onOff(prefs.blockingOn()); }
            public void onClick() { prefs.setBlockingOn(!prefs.blockingOn()); }
        });
        add(new Row() {
            public String label() { return "Strong blocking (about 700k ad and tracker domains)"; }
            public String value() { return onOff(prefs.strongOn()); }
            public void onClick() { prefs.setStrongOn(!prefs.strongOn()); }
        });
        add(new Row() {
            public String label() { return "Hide ad and banner elements"; }
            public String value() { return onOff(prefs.cosmeticOn()); }
            public void onClick() { prefs.setCosmeticOn(!prefs.cosmeticOn()); }
        });
        add(new Row() {
            public String label() { return "Scriptlets (uBlock Origin +js rules)"; }
            public String value() { return onOff(prefs.scriptletsOn()); }
            public void onClick() { prefs.setScriptletsOn(!prefs.scriptletsOn()); }
        });
        add(new Row() {
            public String label() {
                long t = prefs.lastUpdate();
                String when = t == 0 ? "never (using bundled lists)"
                        : DateFormat.getMediumDateFormat(SettingsActivity.this).format(new Date(t)) + " "
                        + DateFormat.getTimeFormat(SettingsActivity.this).format(new Date(t));
                return (filters.isUpdating() ? "Updating filter lists…" : "Update filter lists now") + "\nLast update: " + when
                        + (prefs.lastUpdateResult().isEmpty() ? "" : "\n" + prefs.lastUpdateResult())
                        + "\n" + filters.status();
            }
            public String value() { return null; }
            public void onClick() { updateNow(); }
        });
        add(new Row() {
            public String label() { return "Update lists automatically every 4 days"; }
            public String value() { return onOff(prefs.autoUpdate()); }
            public void onClick() { prefs.setAutoUpdate(!prefs.autoUpdate()); }
        });
        add(new Row() {
            public String label() { return "Services with blocking turned off"; }
            public String value() {
                int n = prefs.disabledSites().size();
                return n == 0 ? "None" : String.valueOf(n);
            }
            public void onClick() { editDisabledSites(); }
        });
        add(new Row() {
            public String label() { return "Go back to the bundled filter lists"; }
            public String value() { return null; }
            public void onClick() {
                filters.resetToBundled();
                Toast.makeText(SettingsActivity.this, R.string.reset_lists_done, Toast.LENGTH_SHORT).show();
            }
        });

        section("Ad blocking");
        for (final String[] svc : new String[][]{
                {"youtube", "YouTube", "ad requests blocked, ad placements removed from the player"},
                {"prime", "Prime Video", "ad and tracker requests blocked; stitched ad breaks run muted at 16x"},
                {"tubi", "Tubi", "ClearTV's player never requests Tubi's ad breaks"},
                {"pluto", "Pluto TV", "live ads are part of the broadcast stream and stay"}}) {
            add(new Row() {
                public String label() {
                    BlockStats.Entry e = BlockStats.get(svc[0]);
                    String last = e.recent.isEmpty() ? "nothing yet" : android.text.TextUtils.join(", ", e.recent);
                    return svc[1] + " · " + e.today + " today · " + e.total + " in total\n" + svc[2]
                            + "\nLast: " + last;
                }
                public String value() { return String.valueOf(BlockStats.get(svc[0]).today); }
                public void onClick() { }
            });
        }
        add(new Row() {
            public String label() {
                BlockStats.Entry e = BlockStats.get("other");
                return "Reset these numbers" + (e.total > 0 ? "\nOther pages: " + e.total + " blocked" : "");
            }
            public String value() { return null; }
            public void onClick() {
                BlockStats.reset();
                render();
            }
        });

        section("Accounts");
        add(new Row() {
            public String label() { return "Sign in to all services, one QR code after another"; }
            public String value() { return null; }
            public void onClick() { startActivity(new android.content.Intent(SettingsActivity.this, com.goomero.cleartv.tv.SignInAllActivity.class)); }
        });
        for (final String[] svc : new String[][]{{"youtube", "YouTube"}, {"tubi", "Tubi"}, {"prime", "Prime Video"}, {"pluto", "Pluto TV"}}) {
            LinearLayout accountRow = add(new Row() {
                public String label() {
                    return com.goomero.cleartv.tv.Accounts.signedIn(SettingsActivity.this, svc[0])
                            ? svc[1] + " · " + com.goomero.cleartv.tv.Accounts.label(SettingsActivity.this, svc[0]) + " (OK to sign out)"
                            : svc[1] + " · sign in with your account";
                }
                public String value() {
                    return com.goomero.cleartv.tv.Accounts.signedIn(SettingsActivity.this, svc[0]) ? "Signed in" : "Sign in";
                }
                public void onClick() { account(svc[0], svc[1]); }
            });
            accountRow.addView(avatar(svc[0], svc[1]), 0);
            if (!svc[0].equals("pluto") && com.goomero.cleartv.tv.Accounts.signedIn(this, svc[0])) {
                final com.goomero.cleartv.tv.Source src = com.goomero.cleartv.tv.Source.create(this, svc[0]);
                if (src.hasProfiles()) add(new Row() {
                    public String label() {
                        String who = src.profileName();
                        return "   " + svc[1] + " profile" + (who.isEmpty() ? "" : " · " + who) + " (OK to switch)";
                    }
                    public String value() { return "Switch"; }
                    public void onClick() {
                        startActivity(new android.content.Intent(SettingsActivity.this, com.goomero.cleartv.tv.ServiceActivity.class)
                                .putExtra(com.goomero.cleartv.tv.ServiceActivity.EXTRA_SERVICE, svc[0])
                                .putExtra(com.goomero.cleartv.tv.ServiceActivity.EXTRA_PROFILES, true));
                    }
                });
            }
        }
        add(new Row() {
            public String label() {
                return PhoneLink.running() ? "Phone remote & keyboard · on (OK shows the QR code again)"
                        : "Phone remote & keyboard: scan a QR code to use your phone as remote and keyboard";
            }
            public String value() { return onOff(PhoneLink.running()); }
            public void onClick() { showPhoneCard(); }
        });
        if (PhoneLink.running()) {
            add(new Row() {
                public String label() { return "Turn the phone page off now"; }
                public String value() { return null; }
                public void onClick() {
                    PhoneLink.stopNow();
                    render();
                }
            });
        }

        section("Services status");
        final long checked = com.goomero.cleartv.tv.HealthCheck.lastRun(this);
        for (final String[] svc : new String[][]{{"youtube", "YouTube"}, {"tubi", "Tubi"}, {"prime", "Prime Video"}, {"pluto", "Pluto TV"}}) {
            final com.goomero.cleartv.tv.HealthCheck.Status st = com.goomero.cleartv.tv.HealthCheck.status(this, svc[0]);
            add(new Row() {
                public String label() {
                    if (checked == 0) return svc[1] + " · not checked yet";
                    return svc[1] + (st.ok() ? " · working" : " · not responding: " + st.failure) + "\nChecked " + when(checked);
                }
                public String value() { return checked == 0 ? "–" : st.ok() ? "OK" : "Problem"; }
                public void onClick() { checkServicesNow(); }
            });
        }
        add(new Row() {
            public String label() {
                return (checking ? "Checking services…" : "Check services now")
                        + "\nClearTV checks by itself at most every 12 hours, quietly in the background";
            }
            public String value() { return null; }
            public void onClick() { checkServicesNow(); }
        });
        add(new Row() {
            public String label() {
                return "Service settings v" + com.goomero.cleartv.tv.Config.version()
                        + "\nBuilt into this version of ClearTV · new settings arrive with app updates";
            }
            public String value() { return null; }
            public void onClick() { }
        });

        section(getString(R.string.sec_watching));
        add(new Row() {
            public String label() { return "Autoplay next"; }
            public String value() { return onOff(com.goomero.cleartv.tv.PlayerChrome.autoplayNext(SettingsActivity.this)); }
            public void onClick() {
                com.goomero.cleartv.tv.PlayerChrome.setAutoplayNext(SettingsActivity.this, !com.goomero.cleartv.tv.PlayerChrome.autoplayNext(SettingsActivity.this));
            }
        });
        add(new Row() {
            public String label() {
                return "Auto-skip sponsor chapters"
                        + "\nVideos with chapters (YouTube): chapters named as a sponsor or ad are skipped once; off shows a Skip sponsor button";
            }
            public String value() { return onOff(com.goomero.cleartv.tv.PlayerChrome.autoSkipSponsor(SettingsActivity.this)); }
            public void onClick() {
                com.goomero.cleartv.tv.PlayerChrome.setAutoSkipSponsor(SettingsActivity.this, !com.goomero.cleartv.tv.PlayerChrome.autoSkipSponsor(SettingsActivity.this));
            }
        });
        add(new Row() {
            public String label() {
                return "Hidden channels"
                        + "\nChannels you chose Don't recommend or Hide channel for; their videos stay off ClearTV's rows. OK to show one again";
            }
            public String value() {
                int n = com.goomero.cleartv.tv.HiddenChannels.all(SettingsActivity.this).size();
                return n == 0 ? "None" : String.valueOf(n);
            }
            public void onClick() { editHiddenChannels(); }
        });
        add(new Row() {
            public String label() { return "Hover previews"; }
            public String value() { return onOff(com.goomero.cleartv.tv.HoverPreview.enabled(SettingsActivity.this)); }
            public void onClick() {
                com.goomero.cleartv.tv.HoverPreview.setEnabled(SettingsActivity.this, !com.goomero.cleartv.tv.HoverPreview.enabled(SettingsActivity.this));
            }
        });
        add(new Row() {
            public String label() { return "Darken service pages"; }
            public String value() { return onOff(prefs.darkPages()); }
            public void onClick() { prefs.setDarkPages(!prefs.darkPages()); }
        });
        add(new Row() {
            public String label() { return "Text size"; }
            public String value() { return prefs.textZoom() + "%"; }
            public void onClick() {
                int[] steps = {100, 125, 150, 175, 200};
                int cur = prefs.textZoom(), next = steps[0];
                for (int i = 0; i < steps.length; i++) if (steps[i] == cur) next = steps[(i + 1) % steps.length];
                prefs.setTextZoom(next);
            }
        });
        add(new Row() {
            public String label() { return "Cursor speed (when a service uses the cursor)"; }
            public String value() { return new String[]{"Slow", "Normal", "Fast"}[prefs.cursorSpeed() % 3]; }
            public void onClick() { prefs.setCursorSpeed((prefs.cursorSpeed() + 1) % 3); }
        });
        add(new Row() {
            public String label() { return "Sign out of everything: clear cookies, site data and cache"; }
            public String value() { return null; }
            public void onClick() { confirmClear(); }
        });

        section("Updates");
        add(new Row() {
            public String label() { return "Check for updates automatically (at start, once a day)"; }
            public String value() { return onOff(com.goomero.cleartv.update.Updater.autoCheck(SettingsActivity.this)); }
            public void onClick() { com.goomero.cleartv.update.Updater.setAutoCheck(SettingsActivity.this, !com.goomero.cleartv.update.Updater.autoCheck(SettingsActivity.this)); }
        });
        add(new Row() {
            public String label() {
                String v = com.goomero.cleartv.update.Updater.available(SettingsActivity.this);
                return v.isEmpty() ? "Check for updates now" : "Install ClearTV " + v;
            }
            public String value() { return updateStatus; }
            public void onClick() { updateApp(); }
        });
        add(new Row() {
            public String label() { return "Update source: " + com.goomero.cleartv.update.Updater.source(); }
            public String value() { return null; }
            public void onClick() {
                Toast.makeText(SettingsActivity.this, "Updates install only when the APK is signed with the same key as this app and matches the release's SHA-256.", Toast.LENGTH_LONG).show();
            }
        });
        add(new Row() {
            public String label() { return "Theme store source: " + com.goomero.cleartv.theme.ThemeStore.source(); }
            public String value() { return null; }
            public void onClick() { startActivity(new android.content.Intent(SettingsActivity.this, com.goomero.cleartv.theme.StoreActivity.class)); }
        });

        section("Backup");
        add(new Row() {
            public String label() { return "Back up favorites, resume points and settings"; }
            public String value() { return Backup.file(SettingsActivity.this).exists() ? "Saved" : null; }
            public void onClick() {
                try {
                    java.io.File f = Backup.export(SettingsActivity.this);
                    android.widget.Toast.makeText(SettingsActivity.this, "Backup saved: " + f.getPath() + " (no sign-in data)", android.widget.Toast.LENGTH_LONG).show();
                } catch (java.io.IOException e) {
                    android.widget.Toast.makeText(SettingsActivity.this, "Backup failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
        add(new Row() {
            public String label() { return "Restore from backup"; }
            public String value() { return null; }
            public void onClick() {
                try {
                    int n = Backup.restore(SettingsActivity.this);
                    android.widget.Toast.makeText(SettingsActivity.this, "Restored " + n + " values. Sign-ins are unchanged.", android.widget.Toast.LENGTH_LONG).show();
                } catch (java.io.IOException e) {
                    android.widget.Toast.makeText(SettingsActivity.this, e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });

        section(getString(R.string.sec_about));
        TextView about = Ui.text(this, getString(R.string.about_text, BuildConfig.VERSION_NAME), 15, Ui.MUTED);
        about.setPadding(Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1), Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1));
        about.setLineSpacing(0, Ui.LINE_H);
        list.addView(about);
        View f = focusIndex >= 0 && focusIndex < list.getChildCount() ? list.getChildAt(focusIndex) : null;
        if (f == null || !f.isFocusable()) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) {
                    f = list.getChildAt(i);
                    break;
                }
            }
        }
        if (f != null) f.requestFocus();
    }

    private void account(final String id, final String name) {
        if (!com.goomero.cleartv.tv.Accounts.signedIn(this, id)) {
            com.goomero.cleartv.tv.Accounts.signIn(this, id);
            return;
        }
        panel.show(name, new String[]{"Sign out of " + name, "Cancel"}, new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                if (which != 0) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (!id.equals("pluto") && !id.equals("prime")) com.goomero.cleartv.tv.Accounts.signOut(SettingsActivity.this, id);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // the WebView cookie store is used on the main thread
                                if (id.equals("pluto") || id.equals("prime")) com.goomero.cleartv.tv.Accounts.signOut(SettingsActivity.this, id);
                                Toast.makeText(SettingsActivity.this, "Signed out of " + name, Toast.LENGTH_SHORT).show();
                                render();
                            }
                        });
                    }
                }).start();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == com.goomero.cleartv.tv.Accounts.REQ_PLUTO) {
            boolean ok = com.goomero.cleartv.tv.Accounts.onPlutoResult(this, data);
            Toast.makeText(this, ok ? "Signed in to Pluto TV" : "Not signed in to Pluto TV", Toast.LENGTH_SHORT).show();
            render();
        }
    }

    @Override
    protected void onDestroy() {
        if (avatars != null) avatars.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list.getChildCount() > 0) render();
    }

    private void section(String s) {
        TextView t = Ui.kicker(this, s, 12, Ui.SOFT);
        t.setPadding(Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S3), 0, Ui.dp(this, Ui.S1));
        list.addView(Ui.oneLine(t));
    }

    private boolean checking;

    private String when(long t) {
        return DateFormat.getMediumDateFormat(this).format(new Date(t)) + " " + DateFormat.getTimeFormat(this).format(new Date(t));
    }

    /** Runs the in-app health check now (background thread, the same probes as the 12-hourly one). */
    private void checkServicesNow() {
        if (checking) return;
        checking = true;
        render();
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                com.goomero.cleartv.tv.HealthCheck.runIfDue(getApplicationContext(), true);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (!isFinishing()) render();
                    }
                });
            }
        }, "ctv-health").start();
    }

    private String updateStatus;
    private boolean updating;

    /**
     * Check for updates now; with a newer release: allow installs if needed, download, verify (size, SHA-256, package,
     * version, signing key) and open the system installer.
     */
    private void updateApp() {
        if (updating) return;
        final android.content.Context app = getApplicationContext();
        if (!com.goomero.cleartv.update.Updater.available(this).isEmpty() && !com.goomero.cleartv.update.Updater.canInstall(this)) {
            installGuide();
            return;
        }
        updating = true;
        updateStatus = "Checking…";
        render();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                java.io.File apk = null;
                try {
                    com.goomero.cleartv.update.Updater.Release r = com.goomero.cleartv.update.Updater.check(app);
                    if (r == null) {
                        msg = "Up to date (" + BuildConfig.VERSION_NAME + ")";
                    } else if (!com.goomero.cleartv.update.Updater.canInstall(app)) {
                        msg = r.version + " available";
                    } else {
                        apk = com.goomero.cleartv.update.Updater.download(app, r, new com.goomero.cleartv.update.Net.Progress() {
                            int last = -1;
                            @Override
                            public void on(long done, long total) {
                                final int pc = (int) (done * 100 / Math.max(1, total));
                                if (pc == last) return;
                                last = pc;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateStatus = "Downloading " + pc + "%";
                                        if (!isFinishing()) render();
                                    }
                                });
                            }
                        });
                        msg = "Verified " + r.version + ": same signing key, SHA-256 matches";
                    }
                } catch (java.io.IOException e) {
                    msg = "Refused: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
                final String m = msg;
                final java.io.File f = apk;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updating = false;
                        updateStatus = m.startsWith("Refused") ? "Refused, not installed" : m.length() > 48 ? m.substring(0, 48) + "…" : m;
                        if (m.startsWith("Refused")) Toast.makeText(SettingsActivity.this, m, Toast.LENGTH_LONG).show();
                        if (isFinishing()) return;
                        render();
                        if (f != null) {
                            try {
                                startActivity(com.goomero.cleartv.update.Updater.installIntent(SettingsActivity.this, f));
                            } catch (android.content.ActivityNotFoundException e) {
                                Toast.makeText(SettingsActivity.this, "This device has no package installer.", Toast.LENGTH_LONG).show();
                            }
                        } else if (m.endsWith("available")) {
                            installGuide();
                        }
                    }
                });
            }
        }, "ctv-update").start();
    }

    /** First run: ClearTV needs "Install unknown apps" before it can hand an update to the installer. */
    private void installGuide() {
        final android.content.Intent screen = com.goomero.cleartv.update.Updater.permissionScreen(this);
        if (screen == null) Toast.makeText(this, "Settings > My Fire TV > Developer options > Install unknown apps > ClearTV: On", Toast.LENGTH_LONG).show();
        panel.show(screen != null ? "Allow ClearTV to install updates (one time)" : "Allow installs: Settings > My Fire TV > Developer options > Install unknown apps > ClearTV",
                screen != null ? new String[]{"Open settings: turn ClearTV on, then come back", "Not now"} : new String[]{"OK"}, new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                if (screen != null && which == 0) {
                    try {
                        startActivity(screen);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(SettingsActivity.this, "Settings > My Fire TV > Developer options > Install unknown apps > ClearTV", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

    private View phoneOverlay;

    /** The phone page's QR code over Settings; BACK or OK closes it, the page keeps running until idle. */
    private void showPhoneCard() {
        final FrameLayout root = (FrameLayout) list.getParent().getParent();
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xE6000000);
        PhoneCard card = new PhoneCard(this, Ui.ACCENT, false,
                "Scan with your phone's camera: a remote with arrows, OK, Back, play/pause and seek, and a keyboard for any text field.");
        dim.addView(card, new FrameLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dim.setFocusable(true);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim.requestFocus();
        phoneOverlay = dim;
    }

    private Images avatars;

    /** Account picture (YouTube) or the service's initial on its colour. */
    private View avatar(String id, String name) {
        final int size = Ui.dp(this, 36);
        final ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setClipToOutline(true);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        boolean in = com.goomero.cleartv.tv.Accounts.signedIn(this, id);
        disc.setColor(in ? (id.equals("pluto") ? 0xFFFFF200 : com.goomero.cleartv.tv.Source.create(this, id).accent()) : Ui.SURFACE);
        v.setBackground(disc);
        String who = com.goomero.cleartv.tv.Accounts.label(this, id);
        String letter = (in && !who.equals("Signed in") ? who : name).substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        v.setImageDrawable(new InitialDrawable(letter, in ? 0xFF000000 : Ui.MUTED, Ui.dp(this, 16)));
        String url = in && !id.equals("pluto") ? com.goomero.cleartv.tv.Source.create(this, id).accountAvatar() : "";
        if (!url.isEmpty()) {
            if (avatars == null) avatars = new Images(this, size, new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) render();
                }
            });
            android.graphics.Bitmap b = avatars.get(url);
            if (b != null) v.setImageBitmap(b);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = Ui.dp(this, Ui.S2);
        v.setLayoutParams(lp);
        return v;
    }

    /** A centred letter, the avatar when there is no picture. */
    private static final class InitialDrawable extends android.graphics.drawable.Drawable {
        private final String letter;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        InitialDrawable(String letter, int color, int textPx) {
            this.letter = letter;
            paint.setColor(color);
            paint.setTextSize(textPx);
            paint.setFakeBoldText(true);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float y = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2;
            c.drawText(letter, b.exactCenterX(), y, paint);
        }

        @Override
        public void setAlpha(int a) { paint.setAlpha(a); }

        @Override
        public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private LinearLayout add(final Row r) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        int p = Ui.dp(this, Ui.S2);
        row.setPadding(p, Ui.dp(this, Ui.S1 + Ui.S_HALF), p, Ui.dp(this, Ui.S1 + Ui.S_HALF));
        row.setMinimumHeight(Ui.dp(this, Ui.S6));
        row.setBackground(rowBackground());
        final TextView label = Ui.text(this, r.label(), 17, Ui.TEXT);
        label.setLineSpacing(Ui.dp(this, 2), 1f); // multi-line rows (status lines) get air between lines
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView value = Ui.kicker(this, "", 13, Ui.MUTED);
        value.setPadding(Ui.dp(this, Ui.S2), 0, 0, 0);
        Ui.oneLine(value);
        row.addView(value);
        setValue(value, r.value());
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.onClick();
                label.setText(r.label());
                setValue(value, r.value());
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, Ui.S1);
        list.addView(row, lp);
        return row;
    }

    private void setValue(TextView v, String s) {
        if (s == null) {
            v.setText("›");
            v.setTextColor(Ui.SOFT);
            return;
        }
        v.setText(s.toUpperCase(java.util.Locale.ROOT));
        v.setTextColor("On".equals(s) ? Ui.GREEN : "Off".equals(s) ? Ui.SOFT : Ui.MUTED);
    }

    private android.graphics.drawable.Drawable rowBackground() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable f = Ui.rounded(Ui.SURFACE, Ui.dp(this, Ui.RADIUS));
        f.setStroke(Ui.dp(this, Ui.RING), Ui.ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{android.R.attr.state_pressed}, f);
        s.addState(new int[]{}, Ui.card(this, Ui.BAR, Ui.LINE_SOFT));
        return s;
    }

    private void updateNow() {
        if (filters.isUpdating()) return;
        Toast.makeText(this, R.string.updating, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                filters.update(new FilterManager.UpdateCallback() {
                    @Override
                    public void onDone(String result) {
                        if (isFinishing()) return;
                        Toast.makeText(SettingsActivity.this, result, Toast.LENGTH_LONG).show();
                        render();
                    }
                });
            }
        }, "ctv-filters-update").start();
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                render();
            }
        }, 300);
    }

    private void editHiddenChannels() {
        final java.util.List<com.goomero.cleartv.tv.HiddenChannels.Entry> list = com.goomero.cleartv.tv.HiddenChannels.all(this);
        if (list.isEmpty()) {
            Toast.makeText(this, "No hidden channels", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] rows = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            com.goomero.cleartv.tv.HiddenChannels.Entry e = list.get(i);
            rows[i] = "Show again: " + (e.name.isEmpty() ? e.id : e.name) + " (" + serviceName(e.service) + ")";
        }
        rows[list.size()] = "Close";
        panel.show("Hidden channels", rows, new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                if (which >= list.size()) return;
                com.goomero.cleartv.tv.HiddenChannels.Entry e = list.get(which);
                com.goomero.cleartv.tv.HiddenChannels.remove(SettingsActivity.this, e);
                Toast.makeText(SettingsActivity.this, (e.name.isEmpty() ? e.id : e.name)
                        + " shows in ClearTV again", Toast.LENGTH_LONG).show();
                render();
            }
        });
    }

    private static String serviceName(String id) {
        switch (id) {
            case "youtube": return "YouTube";
            case "tubi": return "Tubi";
            case "prime": return "Prime Video";
            default: return id;
        }
    }

    private void editDisabledSites() {
        final ArrayList<String> sites = new ArrayList<>(prefs.disabledSites());
        if (sites.isEmpty()) {
            Toast.makeText(this, R.string.no_disabled_sites, Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(sites);
        String[] rows = new String[sites.size()];
        for (int i = 0; i < rows.length; i++) rows[i] = getString(R.string.turn_on_for, sites.get(i));
        panel.show(getString(R.string.disabled_sites_title), rows, new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                prefs.setSiteBlockingDisabled(sites.get(which), false);
                Toast.makeText(SettingsActivity.this, getString(R.string.site_now_on, sites.get(which)),
                        Toast.LENGTH_SHORT).show();
                render();
            }
        });
    }

    private void confirmClear() {
        panel.show(getString(R.string.clear_message), new String[]{getString(R.string.clear_confirm),
                getString(android.R.string.cancel)}, new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                if (which != 0) return;
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
                WebView tmp = new WebView(SettingsActivity.this);
                tmp.clearCache(true);
                tmp.destroy();
                Toast.makeText(SettingsActivity.this, R.string.cleared, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
