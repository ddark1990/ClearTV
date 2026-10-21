package com.goomero.cleartv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.goomero.cleartv.ui.QrView;

/**
 * The QR code for the phone page ({@link PhoneLink}): scanning it gives the phone a remote and a keyboard for the
 * TV. Starting the card starts the page; it turns itself off when idle. Compact form for corners (sign-in pages,
 * search), full form for Settings.
 */
public final class PhoneCard extends LinearLayout {
    public PhoneCard(Context c, int accent, boolean compact, String purpose) {
        this(c, accent, compact, purpose, "", "Phone remote & keyboard");
    }

    /** A card whose QR code opens {@code page} of the phone site (such as "theme"), under the given heading. */
    public PhoneCard(Context c, int accent, boolean compact, String purpose, String page, String heading) {
        super(c);
        setOrientation(compact ? HORIZONTAL : VERTICAL);
        setGravity(Gravity.CENTER);
        GradientDrawable bg = Ui.rounded(Ui.BAR, Ui.dp(c, 10));
        bg.setStroke(Ui.dp(c, compact ? 1 : 2), compact ? Ui.LINE : accent);
        setBackground(bg);
        int p = Ui.dp(c, compact ? Ui.S2 : Ui.S4);
        setPadding(p, p, p, p);
        String url = PhoneLink.start();
        QrView qr = new QrView(c);
        int side = Ui.dp(c, compact ? 112 : 240);
        addView(qr, new LayoutParams(side, side));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        text.setGravity(compact ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        LayoutParams tl = new LayoutParams(compact ? Ui.dp(c, 210) : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (compact) tl.leftMargin = Ui.dp(c, Ui.S2);
        else tl.topMargin = Ui.dp(c, Ui.S3);
        addView(text, tl);
        if (url == null) {
            text.addView(Ui.text(c, "The phone page needs the TV on a home network (Wi-Fi or Ethernet).", compact ? 13 : 16, Ui.MUTED));
            return;
        }
        url = url + page;
        qr.setText(url);
        text.addView(Ui.oneLine(Ui.kicker(c, compact ? "Type with your phone" : heading, compact ? 11 : 13, accent)));
        TextView t = Ui.text(c, purpose, compact ? 13 : 16, Ui.MUTED);
        t.setLineSpacing(0, Ui.LINE_H);
        text.addView(t, Ui.below(c, Ui.S1));
        if (!compact) {
            TextView u = Ui.oneLine(Ui.text(c, url.substring(0, url.indexOf('/', 8) + 1) + "…", 14, Ui.SOFT));
            text.addView(u, Ui.below(c, Ui.S2));
            TextView n = Ui.text(c, "Same Wi-Fi only · private link with a random key · turns off after 10 minutes without use or when ClearTV closes",
                    12, Ui.SOFT);
            n.setGravity(Gravity.CENTER);
            n.setLineSpacing(0, Ui.LINE_H);
            text.addView(n, Ui.below(c, Ui.S1));
        }
    }
}
