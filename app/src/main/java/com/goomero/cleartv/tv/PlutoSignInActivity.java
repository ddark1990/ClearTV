package com.goomero.cleartv.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.goomero.cleartv.PhoneLink;
import com.goomero.cleartv.Ui;
import com.goomero.cleartv.ui.QrView;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pluto TV sign-in on ClearTV's own card, laid out like the code sign-in card ({@link SignInCard}): the QR code
 * opens the phone page, where the email and password are typed (or with the remote on the TV's keyboard). The
 * form posts to Pluto's account API in the background; pluto.tv's own page is never shown. Only the session
 * cookies Pluto sets are kept (in the WebView cookie store, like the other services); the password is never
 * stored or logged. Returns {@link RESULT_DONE} like the page it replaces.
 */
public final class PlutoSignInActivity extends Activity implements PhoneLink.TextTarget {
    private static final String TAG = "CTV.SignIn";
    public static final String RESULT_DONE = "done";
    static final String LOGIN = "https://api.pluto.tv/v1/auth/local";
    private static final int ACCENT = 0xFFFFF200;
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText email, password;
    private TextView button, status;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = Ui.rounded(Ui.BAR, Ui.dp(this, 12));
        bg.setStroke(Ui.dp(this, 2), ACCENT);
        card.setBackground(bg);
        int p = Ui.dp(this, Ui.S4);
        card.setPadding(p, p, p, p);
        QrView qr = new QrView(this);
        card.addView(qr, new LinearLayout.LayoutParams(Ui.dp(this, 248), Ui.dp(this, 248)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = Ui.dp(this, Ui.S4);
        card.addView(text, tl);
        text.addView(Ui.oneLine(Ui.kicker(this, "Sign in to Pluto TV", 13, ACCENT)));
        String phone = PhoneLink.start();
        TextView help = Ui.text(this, phone != null
                ? "Scan the code with your phone's camera and type your email and password there, or press OK on a field to type with the remote."
                : "Press OK on a field to type with the remote.", 16, Ui.MUTED);
        help.setLineSpacing(0, Ui.LINE_H);
        text.addView(help, Ui.below(this, Ui.S2));
        if (phone != null) qr.setText(phone);
        else qr.setVisibility(View.GONE);
        email = field("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_NEXT);
        text.addView(email, fieldLp());
        password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        text.addView(password, fieldLp());
        button = Ui.kicker(this, "Sign in", 14, Ui.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackground(Ui.focusBackground(this, Ui.SURFACE, Ui.SURFACE));
        button.setPadding(Ui.dp(this, Ui.S4), Ui.dp(this, Ui.S1 + 2), Ui.dp(this, Ui.S4), Ui.dp(this, Ui.S1 + 2));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, Ui.S3);
        text.addView(button, bl);
        status = Ui.oneLine(Ui.kicker(this, "Typed text goes to the selected field · BACK to cancel", 11, Ui.SOFT));
        text.addView(status, Ui.below(this, Ui.S3));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(this, 800), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, cl);
        setContentView(root);
        email.requestFocus();
    }

    private EditText field(String hint, int type, int action) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTypeface(android.graphics.Typeface.DEFAULT); // password type would switch to monospace
        e.setImeOptions(action | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setTextSize(18);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.SOFT);
        e.setBackground(Ui.focusBackground(this, Ui.SURFACE, Ui.SURFACE));
        e.setPadding(Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1 + 2), Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S1 + 2));
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                if (v == email) password.requestFocus();
                else submit();
                return true;
            }
        });
        return e;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = Ui.dp(this, Ui.S2);
        return l;
    }

    /** Text from the phone page: into the selected field (the email field when nothing is selected). */
    @Override
    public void onPhoneText(String t, boolean enter) {
        View f = getCurrentFocus();
        EditText e = f == password ? password : f == email ? email : email.getText().length() == 0 ? email : password;
        e.setText(t);
        e.setSelection(e.length());
        e.requestFocus();
        if (enter) {
            if (e == email) password.requestFocus();
            else submit();
        }
    }

    private void submit() {
        if (busy) return;
        final String user = email.getText().toString().trim(), pass = password.getText().toString();
        if (user.isEmpty()) {
            status.setText("ENTER YOUR EMAIL");
            email.requestFocus();
            return;
        }
        if (pass.isEmpty()) {
            status.setText("ENTER YOUR PASSWORD");
            password.requestFocus();
            return;
        }
        busy = true;
        status.setText("SIGNING IN…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    signIn(user, pass);
                } catch (Http.Error e) {
                    error = e.code == 400 || e.code == 401 || e.code == 403 || e.code == 409
                            ? "Email or password is not correct" : "Pluto TV didn't answer (" + e.code + ")";
                    Log.w(TAG, "pluto sign-in: HTTP " + e.code);
                } catch (IOException | org.json.JSONException e) {
                    error = "Pluto TV didn't answer";
                    Log.w(TAG, "pluto sign-in: " + e.getClass().getSimpleName());
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (err == null) {
                            finishWith(true);
                        } else {
                            status.setText(err.toUpperCase(java.util.Locale.ROOT));
                            password.setText("");
                            password.requestFocus();
                        }
                    }
                });
            }
        }, "pluto-signin").start();
    }

    /** Posts the form; keeps Pluto's session cookies and the profile's display name. */
    private void signIn(String user, String pass) throws IOException, org.json.JSONException {
        String form = "optIn=false&synced=false&userIdentity=" + URLEncoder.encode(user, "UTF-8")
                + "&password=" + URLEncoder.encode(pass, "UTF-8");
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Origin", "https://pluto.tv");
        h.put("Referer", "https://pluto.tv/");
        h.put("User-Agent", Http.DESKTOP_UA);
        try (Http.Response r = Http.open(LOGIN, h, form.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", true)) {
            String body = new String(Http.readAll(r.body, 256 * 1024), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(body);
            java.util.List<String> cookies = r.setCookies();
            WebActivity.setCookies("https://api.pluto.tv/", cookies);
            WebActivity.setCookies("https://pluto.tv/", cookies);
            Accounts.plutoSignedIn(this, o.optString("displayName", o.optString("firstName", "")));
            Log.i(TAG, "pluto signed in");
        }
    }

    private void finishWith(boolean done) {
        Intent r = new Intent();
        r.putExtra(RESULT_DONE, done);
        setResult(RESULT_OK, r);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
            finishWith(Accounts.signedIn(this, "pluto"));
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }
}
