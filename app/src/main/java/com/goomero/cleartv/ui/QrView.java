package com.goomero.cleartv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Draws a QR code as dark squares on a white plate with the quiet zone; no bitmap is kept. */
public final class QrView extends View {
    private static final int QUIET = 3;
    private final Paint dark = new Paint(), white = new Paint();
    private boolean[][] m;

    public QrView(Context c) {
        super(c);
        dark.setColor(0xFF000000);
        dark.setAntiAlias(false);
        white.setColor(0xFFFFFFFF);
    }

    public void setText(String text) {
        m = text == null || text.isEmpty() ? null : Qr.encode(text);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        int side = Math.min(w, h);
        if (m == null || side == 0) return;
        int n = m.length + QUIET * 2;
        // whole pixels per module keep the edges crisp for phone cameras
        int px = Math.max(1, side / n);
        int total = px * n;
        int ox = (w - total) / 2, oy = (h - total) / 2;
        cv.drawRect(ox, oy, ox + total, oy + total, white);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                if (!m[y][x]) continue;
                int l = ox + (x + QUIET) * px, t = oy + (y + QUIET) * px;
                cv.drawRect(l, t, l + px, t + px, dark);
            }
        }
    }
}
