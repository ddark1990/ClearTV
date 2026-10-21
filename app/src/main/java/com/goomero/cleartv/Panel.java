package com.goomero.cleartv;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Side panel inside the activity window, replacing dialogs. A dialog is a second window: opening it makes the
 * WebView lose window focus and the system compose a dimmed, blurred layer over a live page, which is slow on a
 * Fire Stick and loses D-pad focus. This is a plain view on an opaque background with no elevation, focus kept
 * inside its rows, closed with BACK or MENU.
 */
public final class Panel extends LinearLayout {
    public interface OnPick {
        void onPick(int which);
    }

    private final FrameLayout host;
    private View restoreFocus;

    public Panel(Context c, FrameLayout host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackground(Ui.card(c, Ui.BAR, Ui.LINE));
        int p = Ui.dp(c, Ui.S2);
        setPadding(p, Ui.dp(c, Ui.S2), p, Ui.dp(c, Ui.S2));
        setClickable(true); // swallow touches so they never reach the page below
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    public boolean isShowing() {
        return getParent() != null;
    }

    /** Shows title and rows at the right edge, inside the overscan-safe area; the first row gets focus. */
    public void show(String title, String[] rows, final OnPick pick) {
        if (isShowing()) dismiss();
        removeAllViews();
        Context c = getContext();
        TextView t = Ui.kicker(c, title, 12, Ui.SOFT);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, Ui.S1), 0, Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S1));
        t.setLineSpacing(0, Ui.LINE_H);
        addView(t);
        View first = null, last = null;
        for (int i = 0; i < rows.length; i++) {
            final int which = i;
            TextView r = Ui.text(c, rows[i], 17, Ui.TEXT);
            r.setSingleLine(true);
            r.setEllipsize(TextUtils.TruncateAt.END);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setFocusable(true);
            r.setClickable(true);
            r.setId(View.generateViewId());
            r.setPadding(Ui.dp(c, Ui.S2), 0, Ui.dp(c, Ui.S2), 0);
            r.setBackground(Ui.focusBackground(c, 0, Ui.FOCUS_FILL));
            r.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    pick.onPick(which);
                }
            });
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, Ui.S6));
            lp.topMargin = Ui.dp(c, Ui.S_HALF);
            addView(r, lp);
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
            if (first == null) first = r;
            last = r;
        }
        if (first != null) {
            first.setNextFocusUpId(last.getId());
            last.setNextFocusDownId(first.getId());
        }
        restoreFocus = host.findFocus();
        int safeX = Ui.dp(c, Ui.SAFE_X), safeY = Ui.dp(c, Ui.SAFE_Y);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(c, 380), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.TOP);
        lp.setMargins(safeX, safeY, safeX, safeY);
        host.addView(this, lp);
        if (first != null) first.requestFocus();
    }

    public void dismiss() {
        if (!isShowing()) return;
        host.removeView(this);
        removeAllViews();
        View f = restoreFocus;
        restoreFocus = null;
        if (f != null && f.isAttachedToWindow() && f.getVisibility() == VISIBLE) f.requestFocus();
    }

    /** Key handling while showing: BACK/MENU close, everything else moves focus among the rows. */
    public boolean handleKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (ev.getAction() == KeyEvent.ACTION_UP) dismiss();
            return true;
        }
        if (findFocus() == null && getChildCount() > 1) getChildAt(1).requestFocus();
        return false;
    }
}
