package com.goomero.cleartv.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.goomero.cleartv.Ui;
import com.goomero.cleartv.ui.QrView;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one sign-in card of every code sign-in (YouTube, Tubi), on the service screen and in "Sign in to all":
 * a large QR code that opens the service's activation page on the phone (with the code filled in where the page
 * takes it), the short code and the address for typing by hand. It asks for the code, polls the service until the
 * phone approves, fetches a fresh code by itself when one expires, and reports success once the session is stored.
 */
public final class SignInCard extends LinearLayout {
    public interface Listener {
        /** Signed in; the session is stored. Called on the main thread. */
        void onSignedIn(Source source);

        /** Sign-in cannot start (the service did not answer); message for a toast. */
        void onSignInFailed(Source source, String message);
    }

    private static final String TAG = "CTV.SignIn";
    private final Source source;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final QrView qr;
    private final TextView help, url, code, status;
    private Source.Code current;
    private boolean closed;
    private int refreshes;

    public SignInCard(Context c, Source source, int accent, Listener listener) {
        super(c);
        this.source = source;
        this.listener = listener;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = Ui.rounded(Ui.BAR, Ui.dp(c, 12));
        bg.setStroke(Ui.dp(c, 2), accent);
        setBackground(bg);
        int p = Ui.dp(c, Ui.S4);
        setPadding(p, p, p, p);
        setFocusable(true);
        qr = new QrView(c);
        addView(qr, new LayoutParams(Ui.dp(c, 248), Ui.dp(c, 248)));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        LayoutParams tl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = Ui.dp(c, Ui.S4);
        addView(text, tl);
        text.addView(Ui.oneLine(Ui.kicker(c, "Sign in to " + source.name(), 13, accent)));
        help = Ui.text(c, "Getting a code…", 16, Ui.MUTED);
        help.setLineSpacing(0, Ui.LINE_H);
        text.addView(help, Ui.below(c, Ui.S2));
        url = Ui.oneLine(Ui.text(c, "", 22, Ui.TEXT));
        url.setTypeface(Ui.display(c));
        text.addView(url, Ui.below(c, Ui.S1));
        TextView enter = Ui.oneLine(Ui.text(c, "and enter the code", 15, Ui.MUTED));
        text.addView(enter, Ui.below(c, Ui.S2));
        code = Ui.oneLine(Ui.text(c, "", 40, accent));
        code.setTypeface(Ui.display(c));
        code.setLetterSpacing(0.16f);
        text.addView(code, Ui.below(c, Ui.S_HALF));
        status = Ui.oneLine(Ui.kicker(c, "", 11, Ui.SOFT));
        text.addView(status, Ui.below(c, Ui.S3));
    }

    public Source source() {
        return source;
    }

    /** Asks for a code and starts polling. */
    public void start() {
        status.setText("CONTACTING " + source.name().toUpperCase(Locale.ROOT) + "…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Source.Code c = source.startCode();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (closed) return;
                            show(c);
                        }
                    });
                } catch (final IOException e) {
                    Log.w(TAG, source.id() + " code: " + e.getMessage());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (closed) return;
                            listener.onSignInFailed(source, "DETAIL:" + (e.getMessage() == null ? "no answer" : e.getMessage()));
                        }
                    });
                }
            }
        });
    }

    private void show(Source.Code c) {
        current = c;
        String page = c.url.startsWith("http") ? c.url : "https://" + c.url;
        qr.setText(source.codeQrUrl(c));
        help.setText("Scan the code with your phone's camera, or on any phone or computer go to");
        url.setText(page.replace("https://", "").replace("http://", ""));
        code.setText(c.userCode);
        status.setText("WAITING FOR YOUR PHONE · THE CODE RENEWS BY ITSELF");
        schedulePoll(c);
    }

    private void schedulePoll(final Source.Code c) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (closed || current != c) return;
                if (System.currentTimeMillis() > c.expiresAt) {
                    renew();
                    return;
                }
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = false;
                        boolean expired = false;
                        String fatal = null;
                        try {
                            ok = source.pollCode(c);
                        } catch (Source.SignInError e) {
                            fatal = e.getMessage();
                            Log.w(TAG, source.id() + " sign-in failed after approval: " + fatal);
                        } catch (IOException e) {
                            String m = e.getMessage() == null ? "" : e.getMessage();
                            expired = m.toLowerCase(Locale.ROOT).contains("expired");
                            if (!expired) Log.w(TAG, source.id() + " poll: " + m);
                        }
                        final boolean signed = ok, renew = expired;
                        final String failed = fatal;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (closed || current != c) return;
                                if (signed) {
                                    closed = true;
                                    io.shutdown();
                                    listener.onSignedIn(source);
                                } else if (failed != null) {
                                    closed = true;
                                    io.shutdown();
                                    listener.onSignInFailed(source, failed);
                                } else if (renew) {
                                    renew();
                                } else {
                                    schedulePoll(c);
                                }
                            }
                        });
                    }
                });
            }
        }, c.intervalSec * 1000L);
    }

    /** The code expired: a fresh one replaces it on the card (a few times, then the card asks to retry). */
    private void renew() {
        if (++refreshes > 6) {
            status.setText("CODE EXPIRED · OPEN SIGN-IN AGAIN WHEN READY");
            current = null;
            return;
        }
        status.setText("GETTING A NEW CODE…");
        start();
    }

    /** Stops polling (BACK, or the screen closes). */
    public void close() {
        closed = true;
        current = null;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    public boolean isClosed() {
        return closed;
    }

    /** For layouts that hide the card without closing it. */
    public void setShown(boolean shown) {
        setVisibility(shown ? View.VISIBLE : View.GONE);
    }
}
