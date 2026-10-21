package com.goomero.cleartv.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.goomero.cleartv.Ui;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The shared "Who's watching?" card of every service with profiles (Prime Video profiles, YouTube channels):
 * the account's profiles as avatar tiles (kids and PIN-locked ones marked), the "ask every time" switch, and a
 * native PIN pad for locked profiles (remote digits work too). Picking a profile switches the service's session
 * to it, so rows, watchlist, Continue watching and playback all follow.
 */
public final class ProfileCard extends LinearLayout {
    public interface Listener {
        /** The profile is now active (main thread). */
        void onPicked(Source.Profile p);

        /** Closed without a change (BACK, or nothing to pick). */
        void onClosed();
    }

    private static final String TAG = "CTV.Profiles";
    private final Source source;
    private final Listener listener;
    private final int accent;
    private final boolean auto;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final TextView status, ask, pinTitle, pinDots;
    private final LinearLayout tiles, pinBox;
    private final StringBuilder pin = new StringBuilder();
    private Source.Profile pinFor;
    private boolean closed, busy;

    /**
     * {@code auto}: opened by ClearTV (after sign-in, or "ask every time"); with a single profile it is used
     * without asking.
     */
    public ProfileCard(Context c, Source source, int accent, boolean auto, Listener listener) {
        super(c);
        this.source = source;
        this.listener = listener;
        this.accent = accent;
        this.auto = auto;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = Ui.rounded(Ui.BAR, Ui.dp(c, 12));
        bg.setStroke(Ui.dp(c, 2), accent);
        setBackground(bg);
        int p = Ui.dp(c, Ui.S4);
        setPadding(p, p, p, p);
        TextView kick = Ui.oneLine(Ui.kicker(c, source.name() + " · profiles", 13, accent));
        kick.setGravity(Gravity.CENTER);
        addView(kick, Ui.below(c, 0));
        TextView title = Ui.oneLine(Ui.text(c, "Who's watching?", 28, Ui.TEXT));
        title.setTypeface(Ui.display(c));
        title.setGravity(Gravity.CENTER);
        addView(title, Ui.below(c, Ui.S1));
        tiles = new LinearLayout(c);
        tiles.setOrientation(HORIZONTAL);
        tiles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, Ui.S3);
        // room for the focused tile's scale-up and outline, so the selection is never cut off
        tiles.setPadding(Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S1));
        tiles.setClipChildren(false);
        tiles.setClipToPadding(false);
        setClipChildren(false);
        addView(tiles, tl);

        pinBox = new LinearLayout(c);
        pinBox.setOrientation(VERTICAL);
        pinBox.setGravity(Gravity.CENTER_HORIZONTAL);
        pinBox.setVisibility(GONE);
        pinTitle = Ui.oneLine(Ui.text(c, "", 16, Ui.MUTED));
        pinTitle.setGravity(Gravity.CENTER);
        pinBox.addView(pinTitle, Ui.below(c, 0));
        pinDots = Ui.oneLine(Ui.text(c, "", 32, accent));
        pinDots.setTypeface(Ui.display(c));
        pinDots.setLetterSpacing(0.3f);
        pinDots.setGravity(Gravity.CENTER);
        pinBox.addView(pinDots, Ui.below(c, Ui.S1));
        GridLayout pad = new GridLayout(c);
        pad.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        for (final String k : keys) {
            TextView b = Ui.text(c, k, 20, Ui.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setBackground(Ui.focusBackground(c, Ui.SURFACE, Ui.SURFACE));
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pinKey(k);
                }
            });
            GridLayout.LayoutParams g = new GridLayout.LayoutParams();
            g.width = Ui.dp(c, 72);
            g.height = Ui.dp(c, 44);
            g.setMargins(Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S_HALF));
            pad.addView(b, g);
        }
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = Ui.dp(c, Ui.S2);
        pinBox.addView(pad, pl);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbl.topMargin = Ui.dp(c, Ui.S3);
        addView(pinBox, pbl);

        status = Ui.lines(Ui.text(c, "Loading profiles…", 14, Ui.MUTED), 2);
        status.setGravity(Gravity.CENTER);
        addView(status, Ui.below(c, Ui.S3));
        ask = Ui.oneLine(Ui.kicker(c, "", 12, Ui.MUTED));
        ask.setGravity(Gravity.CENTER);
        ask.setFocusable(true);
        ask.setBackground(Ui.pillBackground(c));
        ask.setPadding(Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S1));
        ask.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ProfileCard.this.source.setAskProfile(!ProfileCard.this.source.askProfile());
                showAsk();
            }
        });
        showAsk();
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Ui.dp(c, Ui.S2);
        addView(ask, al);
        TextView hint = Ui.oneLine(Ui.kicker(c, "OK choose · BACK " + (auto ? "keep the current profile" : "close"), 10, Ui.SOFT));
        hint.setGravity(Gravity.CENTER);
        addView(hint, Ui.below(c, Ui.S2));
        // opened by ClearTV itself: stay hidden until there is a real choice, so nothing flashes and vanishes
        if (auto) setVisibility(INVISIBLE);
    }

    private void showAsk() {
        ask.setText(("Ask every time · " + (source.askProfile() ? "On" : "Off")).toUpperCase(Locale.ROOT));
    }

    /** Loads the profiles and shows them. */
    public void start() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<Source.Profile> got;
                String err = null;
                try {
                    got = source.profiles();
                } catch (IOException e) {
                    got = null;
                    err = e.getMessage();
                    Log.w(TAG, source.id() + " profiles: " + err);
                }
                final List<Source.Profile> list = got;
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (closed) return;
                        if (list == null && auto) {
                            finish(null);
                        } else if (list == null) {
                            status.setText("Couldn't load profiles" + (error == null ? "." : ": " + error));
                            ask.requestFocus();
                        } else if (list.isEmpty()) {
                            if (auto) finish(null);
                            else status.setText("This account has no profiles to choose from.");
                        } else if (list.size() == 1 && auto) {
                            pick(list.get(0));
                        } else {
                            setVisibility(VISIBLE);
                            show(list);
                        }
                    }
                });
            }
        });
    }

    private void show(List<Source.Profile> list) {
        Context c = getContext();
        tiles.removeAllViews();
        View first = null;
        for (final Source.Profile p : list) {
            LinearLayout t = new LinearLayout(c);
            t.setOrientation(VERTICAL);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setFocusable(true);
            t.setBackground(tileBackground(c));
            int pad = Ui.dp(c, Ui.S1);
            t.setPadding(pad, pad + Ui.dp(c, Ui.S_HALF), pad, pad);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(c, 48)));
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            img.setClipToOutline(true);
            t.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 88), Ui.dp(c, 88)));
            loadAvatar(img, p.avatar);
            TextView name = Ui.oneLine(Ui.text(c, p.name, 15, Ui.TEXT));
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = Ui.below(c, Ui.S1);
            t.addView(name, nl);
            String tag = p.active ? "Current" : "";
            if (p.kids) tag = tag.isEmpty() ? "Kids" : tag + " · Kids";
            if (p.locked()) tag = tag.isEmpty() ? "PIN" : tag + " · PIN";
            TextView tg = Ui.oneLine(Ui.kicker(c, tag, 10, p.active ? accent : Ui.SOFT));
            tg.setGravity(Gravity.CENTER);
            t.addView(tg, Ui.below(c, Ui.S_HALF));
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick(p);
                }
            });
            Ui.focusScale(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 120), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = Ui.dp(c, Ui.S_HALF);
            tiles.addView(t, lp);
            if (first == null || p.active) first = t;
        }
        status.setText(list.size() + (list.size() == 1 ? " profile" : " profiles") + " on this account");
        if (first != null) first.requestFocus();
    }

    private Drawable tileBackground(Context c) {
        float r = Ui.dp(c, Ui.RADIUS + 4);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = Ui.rounded(Ui.SURFACE, r);
        f.setStroke(Ui.dp(c, Ui.RING), accent);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, Ui.rounded(0x00000000, r));
        return s;
    }

    private void loadAvatar(final ImageView into, final String url) {
        if (url.isEmpty()) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] b = Http.get(url, null);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= 176) sample *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    final Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    if (bm != null) main.post(new Runnable() {
                        @Override
                        public void run() {
                            into.setImageBitmap(bm);
                        }
                    });
                } catch (IOException e) {
                    Log.w(TAG, "avatar: " + e.getMessage());
                }
            }
        });
    }

    private void pick(final Source.Profile p) {
        if (busy) return;
        busy = true;
        final String code = pinFor == p ? pin.toString() : null;
        status.setText("Switching to " + p.name + "…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                IOException err = null;
                try {
                    source.selectProfile(p, code);
                } catch (IOException e) {
                    err = e;
                }
                final IOException error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (closed) return;
                        if (error == null) {
                            finish(p);
                        } else if (error instanceof Source.PinNeeded) {
                            askPin(p, error.getMessage());
                        } else {
                            status.setText(error.getMessage() == null ? "Couldn't switch profiles." : error.getMessage());
                        }
                    }
                });
            }
        });
    }

    private void askPin(Source.Profile p, String title) {
        pinFor = p;
        pin.setLength(0);
        pinTitle.setText(title == null || title.isEmpty() ? "Enter the PIN for " + p.name : title);
        pinDots.setText("");
        tiles.setVisibility(GONE);
        pinBox.setVisibility(VISIBLE);
        status.setText("PIN for " + p.name + " · remote digits work too");
        ViewGroup pad = (ViewGroup) pinBox.getChildAt(2);
        pad.getChildAt(0).requestFocus();
    }

    private void pinKey(String k) {
        if (pinFor == null || busy) return;
        if (k.equals("⌫")) {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
        } else if (k.equals("OK")) {
            if (pin.length() > 0) pick(pinFor);
            return;
        } else if (pin.length() < 8) {
            pin.append(k);
        }
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) dots.append('●');
        pinDots.setText(dots);
    }

    /** Remote keys while the card is up: digits for the PIN, BACK to leave the PIN or the card. */
    public boolean handleKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (pinFor != null && code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (ev.getAction() == KeyEvent.ACTION_UP) pinKey(String.valueOf(code - KeyEvent.KEYCODE_0));
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                if (pinFor != null) {
                    pinFor = null;
                    pinBox.setVisibility(GONE);
                    tiles.setVisibility(VISIBLE);
                    status.setText("");
                    if (tiles.getChildCount() > 0) tiles.getChildAt(0).requestFocus();
                } else {
                    finish(null);
                }
            }
            return true;
        }
        return false;
    }

    private void finish(Source.Profile p) {
        if (closed) return;
        close();
        if (p != null) listener.onPicked(p);
        else listener.onClosed();
    }

    public void close() {
        closed = true;
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }
}
