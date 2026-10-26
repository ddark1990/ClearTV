package com.goomero.cleartv.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.goomero.cleartv.Ui;

import java.util.Locale;

/**
 * Settings > Sign in to all services: one service after another. YouTube and Tubi show the shared QR card and
 * move on by themselves once the phone approves; Prime Video and Pluto TV open their own sign-in page (with the
 * phone keyboard QR in the corner) and continue when it closes. Signed-in services are confirmed and skipped
 * with OK; RIGHT skips any step, BACK leaves.
 */
public final class SignInAllActivity extends Activity {
    private static final String[][] STEPS = {{"youtube", "YouTube"}, {"tubi", "Tubi"}, {"prime", "Prime Video"}, {"paramount", "Paramount+"}, {"pluto", "Pluto TV"}};
    private final int[] result = new int[STEPS.length]; // 0 pending, 1 signed in, 2 skipped
    private FrameLayout root;
    private LinearLayout chips, body;
    private TextView heading;
    private SignInCard card;
    private int step = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y + Ui.S1), Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(Ui.oneLine(Ui.kicker(this, "ClearTV · Settings", 11, Ui.SOFT)));
        heading = Ui.kicker(this, "Sign in to all services", 26, Ui.TEXT);
        heading.setLetterSpacing(0.08f);
        col.addView(Ui.oneLine(heading), Ui.below(this, Ui.S_HALF));
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(chips, Ui.below(this, Ui.S3));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        col.addView(Ui.oneLine(Ui.kicker(this, "OK continue   ·   ▶ skip this service   ·   BACK leave", 11, Ui.SOFT)));
        setContentView(root);
        next();
    }

    private void renderChips() {
        chips.removeAllViews();
        for (int i = 0; i < STEPS.length; i++) {
            String mark = result[i] == 1 ? "✓ " : result[i] == 2 ? "– " : i == step ? "● " : "";
            TextView t = Ui.kicker(this, mark + STEPS[i][1], 12, i == step ? Ui.TEXT : result[i] == 1 ? Ui.GREEN : Ui.SOFT);
            t.setPadding(Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1), Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1));
            t.setBackground(Ui.rounded(i == step ? Ui.SURFACE : Ui.BAR, Ui.dp(this, Ui.PILL)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, Ui.S1);
            chips.addView(t, lp);
        }
    }

    private void next() {
        closeCard();
        step++;
        body.removeAllViews();
        if (step >= STEPS.length) {
            finishAll();
            return;
        }
        renderChips();
        final String id = STEPS[step][0], name = STEPS[step][1];
        heading.setText(("Sign in to all services · " + (step + 1) + " of " + STEPS.length).toUpperCase(Locale.ROOT));
        if (Accounts.signedIn(this, id)) {
            String who = Accounts.label(this, id);
            message("✓ " + name, "Already signed in" + (who.isEmpty() || who.equals("Signed in") ? "." : " as " + who + "."),
                    "OK for the next service");
            return;
        }
        if (id.equals("pluto")) {
            message(name, name + " signs in on its own page. Press OK to open it; scan the small QR code there to type your email and password on your phone.",
                    "OK open " + name + " sign-in");
            return;
        }
        Source s = Source.create(this, id);
        card = new SignInCard(this, s, Ui.serviceAccent(s.accent()), new SignInCard.Listener() {
            @Override
            public void onSignedIn(Source src) {
                String who = src.accountLabel();
                Toast.makeText(SignInAllActivity.this, "✓ Signed in to " + src.name() + (who.isEmpty() ? "" : " as " + who), Toast.LENGTH_LONG).show();
                result[step] = 1;
                next();
            }

            @Override
            public void onSignInFailed(Source src, String message) {
                Toast.makeText(SignInAllActivity.this, message, Toast.LENGTH_LONG).show();
                result[step] = 2;
                next();
            }
        });
        body.addView(card, new LinearLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.requestFocus();
        card.start();
    }

    private void message(String title, String text, String action) {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setGravity(Gravity.CENTER_HORIZONTAL);
        m.setBackground(Ui.card(this, Ui.BAR, Ui.LINE));
        int p = Ui.dp(this, Ui.S4);
        m.setPadding(p, p, p, p);
        m.setFocusable(true);
        TextView t = Ui.text(this, title, 26, Ui.TEXT);
        t.setTypeface(Ui.display(this));
        t.setGravity(Gravity.CENTER);
        m.addView(Ui.oneLine(t), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView d = Ui.text(this, text, 16, Ui.MUTED);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(0, Ui.LINE_H);
        LinearLayout.LayoutParams dlp = Ui.below(this, Ui.S2);
        dlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        m.addView(d, dlp);
        TextView a = Ui.oneLine(Ui.kicker(this, action, 12, Ui.ACCENT));
        a.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = Ui.below(this, Ui.S3);
        alp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        m.addView(a, alp);
        body.addView(m, new LinearLayout.LayoutParams(Ui.dp(this, 640), ViewGroup.LayoutParams.WRAP_CONTENT));
        m.requestFocus();
    }

    private void finishAll() {
        renderChips();
        int ok = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < STEPS.length; i++) {
            boolean in = Accounts.signedIn(this, STEPS[i][0]);
            if (in) ok++;
            sb.append(in ? "✓ " : "– ").append(STEPS[i][1]).append(in ? "  " + Accounts.label(this, STEPS[i][0]) : "  not signed in").append('\n');
        }
        heading.setText("SIGN IN TO ALL SERVICES · DONE");
        message(ok + " of " + STEPS.length + " signed in", sb.toString().trim(), "OK or BACK to close");
        step = STEPS.length;
    }

    private void openWebSignIn() {
        Accounts.signIn(this, "pluto");
    }

    @Override
    protected void onActivityResult(int req, int res, final Intent data) {
        super.onActivityResult(req, res, data);
        if (step < 0 || step >= STEPS.length) return;
        if (req == Accounts.REQ_PLUTO) {
            result[step] = Accounts.onPlutoResult(this, data) ? 1 : 2;
            next();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (ev.getAction() != KeyEvent.ACTION_UP) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(ev);
        }
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (step < STEPS.length) {
                    result[step] = Accounts.signedIn(this, STEPS[step][0]) ? 1 : 2;
                    next();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (step >= STEPS.length) {
                    finish();
                } else if (Accounts.signedIn(this, STEPS[step][0])) {
                    result[step] = 1;
                    next();
                } else if (card == null) {
                    openWebSignIn();
                }
                return true;
            default:
                return super.dispatchKeyEvent(ev);
        }
    }

    private void closeCard() {
        if (card != null) {
            card.close();
            card = null;
        }
    }

    @Override
    protected void onDestroy() {
        closeCard();
        super.onDestroy();
    }
}
