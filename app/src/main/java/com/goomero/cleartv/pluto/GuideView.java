package com.goomero.cleartv.pluto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import com.goomero.cleartv.Ui;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * The channel guide as one self-drawn view: a channel column (number and logo) and a two-hour time grid with a
 * 30-minute ruler. Only the visible rows are drawn and there is no view per cell or per row, so moving around a
 * 400-channel guide is a single cheap redraw. Focus is a (row, time) cursor: up/down change channel, left/right
 * step through programs (the grid scrolls in time), FF/RW and channel up/down page by a screen of rows.
 */
final class GuideView extends View {
    interface Listener {
        void onGuideFocus(PlutoApi.Channel c, PlutoApi.Program p);

        void onGuideActivate(PlutoApi.Channel c);

        /** OK held on a channel row: the channel's actions (favorites). */
        void onGuideMenu(PlutoApi.Channel c);
    }

    private boolean okDown, okLong;

    private static final long SPAN = 2 * 3600_000L, HALF_HOUR = 1800_000L;

    private List<PlutoApi.Channel> rows = Collections.emptyList();
    private Listener listener;
    private PlutoLogos logos;
    private String playingId = "";
    private int focusRow, topRow;
    private long focusTime, windowStart;

    private final int chanW, headerH, rowH, gap, pad, ring, radius;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint number = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Rect src = new Rect();
    private final DateFormat timeFmt;
    private final Date date = new Date(); // reused by onDraw
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onClock();
            postDelayed(this, 30_000);
        }
    };

    GuideView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        chanW = Ui.dp(c, 196);
        headerH = Ui.dp(c, Ui.S4);
        rowH = Ui.dp(c, 56); // 8 dp grid: room for a title line, 4 dp, a time line, and 8 dp above and below
        gap = Ui.dp(c, Ui.S_HALF);
        pad = Ui.dp(c, Ui.S1 + Ui.S_HALF);
        ring = Ui.dp(c, Ui.RING);
        radius = Ui.dp(c, Ui.RADIUS);
        title.setTextSize(sp(c, 15));
        title.setColor(Ui.TEXT);
        small.setTextSize(sp(c, 11));
        small.setColor(Ui.SOFT);
        small.setTypeface(Ui.display(c));
        small.setLetterSpacing(0.08f);
        number.setTextSize(sp(c, 13));
        number.setColor(Ui.SOFT);
        number.setTypeface(Ui.display(c));
        stroke.setStyle(Paint.Style.STROKE);
        timeFmt = android.text.format.DateFormat.getTimeFormat(c);
        windowStart = floorHalfHour(System.currentTimeMillis());
    }

    private static float sp(Context c, float v) {
        return Ui.sp(c, v);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLogos(PlutoLogos l) {
        logos = l;
    }

    /** New row list (category change or fresh data); keeps focus on {@code focusId} when it is in the list. */
    void setRows(List<PlutoApi.Channel> list, String focusId) {
        rows = list;
        int idx = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(focusId)) idx = i;
        focusRow = Math.min(idx, Math.max(0, list.size() - 1));
        long now = System.currentTimeMillis();
        if (focusTime < now) focusTime = now;
        ensureVisible();
        invalidate();
        notifyFocus();
    }

    /** Guide data changed (programs filled in); redraw and refresh the info panel. */
    void dataChanged() {
        invalidate();
        notifyFocus();
    }

    void setPlaying(String id) {
        playingId = id == null ? "" : id;
        invalidate();
    }

    PlutoApi.Channel focusedChannel() {
        return focusRow < rows.size() ? rows.get(focusRow) : null;
    }

    private PlutoApi.Program focusedProgram() {
        PlutoApi.Channel c = focusedChannel();
        return c == null ? null : c.at(Math.max(focusTime, System.currentTimeMillis()));
    }

    private int visibleRows() {
        return Math.max(1, (getHeight() - headerH) / rowH);
    }

    private static long floorHalfHour(long t) {
        return t - t % HALF_HOUR;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(tick, 30_000);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    /** Time moves on: the window never starts before the current half hour, and focus never sits in the past. */
    private void onClock() {
        PlutoApi.Program before = focusedProgram();
        long now = System.currentTimeMillis();
        if (focusTime < now) focusTime = now;
        long floor = floorHalfHour(now);
        if (windowStart < floor) windowStart = floor;
        invalidate();
        // the focused program ended (1:00 PM came): the info panel moves on to what is on now
        if (focusedProgram() != before) notifyFocus();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        ensureVisible();
    }

    private void ensureVisible() {
        int vis = visibleRows();
        if (focusRow < topRow) topRow = focusRow;
        if (focusRow >= topRow + vis) topRow = focusRow - vis + 1;
        topRow = Math.max(0, Math.min(topRow, Math.max(0, rows.size() - vis)));
        long now = System.currentTimeMillis();
        PlutoApi.Program p = focusedProgram();
        long start = p != null ? Math.max(p.start, now) : focusTime;
        long floor = floorHalfHour(now);
        if (start < windowStart) windowStart = Math.max(floor, floorHalfHour(start));
        if (start >= windowStart + SPAN - HALF_HOUR) windowStart = Math.max(floor, floorHalfHour(start) - HALF_HOUR);
    }

    private void notifyFocus() {
        PlutoApi.Channel c = focusedChannel();
        if (listener != null && c != null) listener.onGuideFocus(c, focusedProgram());
    }

    private void moveRow(int delta) {
        int n = Math.max(0, Math.min(rows.size() - 1, focusRow + delta));
        if (n == focusRow) return;
        focusRow = n;
        changed();
    }

    private void changed() {
        ensureVisible();
        invalidate();
        notifyFocus();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (rows.isEmpty()) return super.onKeyDown(code, ev);
        long now = System.currentTimeMillis();
        PlutoApi.Channel c = focusedChannel();
        PlutoApi.Program p = focusedProgram();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusRow == 0) return false; // up to the category chips
                moveRow(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveRow(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                moveRow(visibleRows());
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                moveRow(-visibleRows());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                PlutoApi.Program next = p != null && c != null ? c.after(p) : null;
                if (next != null) {
                    focusTime = next.start;
                    changed();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                if (p != null && c != null) {
                    List<PlutoApi.Program> l = c.programs;
                    int i = l.indexOf(p);
                    if (i > 0 && l.get(i - 1).stop > now) {
                        focusTime = Math.max(now, l.get(i - 1).start);
                        changed();
                    } else if (focusTime > now) {
                        focusTime = now;
                        changed();
                    }
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // a tap tunes on release; held it opens the channel's actions instead
                if (ev.getRepeatCount() == 0) {
                    okDown = true;
                    okLong = false;
                } else if (okDown && !okLong && com.goomero.cleartv.tv.ShelfView.isHeld(ev)) {
                    okLong = true;
                    if (listener != null && c != null) listener.onGuideMenu(c);
                }
                return true;
            default:
                return super.onKeyDown(code, ev);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            boolean click = okDown && !okLong;
            okDown = false;
            okLong = false;
            PlutoApi.Channel c = focusedChannel();
            if (click && listener != null && c != null) listener.onGuideActivate(c);
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    private String fmt(long t) {
        date.setTime(t);
        return timeFmt.format(date);
    }

    private float toX(long t) {
        float gridX = chanW + gap;
        return gridX + (t - windowStart) * (getWidth() - gridX) / (float) SPAN;
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        long now = System.currentTimeMillis();
        long end = windowStart + SPAN;
        float gridX = chanW + gap;
        boolean focused = hasFocus();

        // ruler: a label at every half hour
        for (long t = windowStart; t < end; t += HALF_HOUR) {
            float x = toX(t);
            fill.setColor(Ui.LINE);
            cv.drawRect(x, headerH - Ui.dp(getContext(), 8), x + 1, headerH - gap, fill);
            cv.drawText(fmt(t).toUpperCase(java.util.Locale.ROOT), x + gap * 2,
                    headerH - Ui.dp(getContext(), 10), small);
        }
        String today = (now >= windowStart && now < end) ? "ON NOW" : "LATER";
        small.setColor(Ui.MUTED);
        cv.drawText(today, gap, headerH - Ui.dp(getContext(), 10), small);
        small.setColor(Ui.SOFT);

        int vis = visibleRows(); // whole rows only; the rest scrolls into view
        for (int i = 0; i < vis && topRow + i < rows.size(); i++) {
            int row = topRow + i;
            PlutoApi.Channel c = rows.get(row);
            float y = headerH + i * rowH;
            if (y >= getHeight()) break;
            boolean isFocusRow = row == focusRow;
            boolean playing = c.id.equals(playingId);

            // channel cell
            r.set(0, y, chanW, y + rowH - gap);
            fill.setColor(isFocusRow ? Ui.SURFACE : Ui.CARD_FILL);
            cv.drawRoundRect(r, radius, radius, fill);
            if (playing) {
                fill.setColor(Ui.GREEN);
                cv.drawRect(0, y + gap * 2, gap, y + rowH - gap * 3, fill);
            }
            number.setColor(playing ? Ui.GREEN : Ui.SOFT);
            cv.drawText(String.valueOf(c.number), pad, y + (rowH - gap) / 2f + number.getTextSize() / 3f, number);
            float logoX = pad + number.measureText("8888") + Ui.dp(getContext(), Ui.S2);
            float logoMaxW = chanW - logoX - pad, logoMaxH = rowH - gap - pad * 1.6f;
            Bitmap b = logos != null ? logos.get(c) : null;
            if (b != null) {
                float s = Math.min(logoMaxW / b.getWidth(), logoMaxH / b.getHeight());
                float bw = b.getWidth() * s, bh = b.getHeight() * s;
                float by = y + (rowH - gap - bh) / 2f;
                src.set(0, 0, b.getWidth(), b.getHeight());
                r.set(logoX, by, logoX + bw, by + bh);
                cv.drawBitmap(b, src, r, bmp);
            } else {
                title.setColor(Ui.MUTED);
                CharSequence n = TextUtils.ellipsize(c.name, title, logoMaxW, TextUtils.TruncateAt.END);
                cv.drawText(n, 0, n.length(), logoX, y + (rowH - gap) / 2f + title.getTextSize() / 3f, title);
                title.setColor(Ui.TEXT);
            }

            // program cells
            List<PlutoApi.Program> progs = c.programs;
            boolean any = false;
            for (int k = 0; k < progs.size(); k++) {
                PlutoApi.Program p = progs.get(k);
                if (p.stop <= windowStart || p.start >= end) continue;
                any = true;
                boolean past = p.stop <= now;
                float x0 = Math.max(gridX, toX(p.start)), x1 = Math.min(w, toX(p.stop)) - gap;
                if (x1 - x0 < 2) continue;
                boolean isFocus = isFocusRow && p.start <= Math.max(focusTime, now) && Math.max(focusTime, now) < p.stop;
                drawCell(cv, x0, y, x1, p.title, p.start <= now ? null : fmt(p.start),
                        past ? 1f : p.start <= now ? (now - p.start) / (float) (p.stop - p.start) : -1f,
                        isFocus, focused, past);
            }
            if (!any) drawCell(cv, gridX, y, w, "No guide information", null, -1f, isFocusRow, focused, false);
        }

        // the current time
        if (now >= windowStart && now < end) {
            float x = toX(now);
            fill.setColor(Ui.AMBER);
            cv.drawRect(x - 1, headerH - gap, x + 1, getHeight(), fill);
        }
    }

    private void drawCell(Canvas cv, float x0, float y, float x1, String text, String sub, float elapsed,
                          boolean isFocus, boolean viewFocused, boolean past) {
        r.set(x0, y, x1, y + rowH - gap);
        fill.setColor(isFocus && viewFocused ? Ui.SURFACE : Ui.CARD_FILL);
        cv.drawRoundRect(r, radius, radius, fill);
        if (elapsed > 0) {
            // how far into the program we are: a faint wash over the elapsed part, and a thin bar at the bottom
            fill.setColor(0x0FFFFFFF);
            cv.save();
            cv.clipRect(x0, y, x0 + (x1 - x0) * elapsed, y + rowH - gap);
            cv.drawRoundRect(r, radius, radius, fill);
            cv.restore();
        }
        if (isFocus) {
            stroke.setStrokeWidth(ring);
            stroke.setColor(viewFocused ? Ui.ACCENT : Ui.LINE);
            r.inset(ring / 2f, ring / 2f);
            cv.drawRoundRect(r, radius, radius, stroke);
        }
        float avail = x1 - x0 - pad * 2;
        if (avail < Ui.dp(getContext(), 16)) return;
        title.setColor(isFocus && viewFocused ? Ui.TEXT : past ? Ui.SOFT : Ui.MUTED);
        CharSequence t = TextUtils.ellipsize(text, title, avail, TextUtils.TruncateAt.END);
        float mid = y + (rowH - gap) / 2f;
        if (sub != null) {
            // title and time line centred as one block, 4 dp apart, from the real font metrics
            Paint.FontMetrics tf = title.getFontMetrics(), sf = small.getFontMetrics();
            float space = Ui.dp(getContext(), Ui.S_HALF);
            float block = (tf.descent - tf.ascent) + space + (sf.descent - sf.ascent);
            float top = mid - block / 2f;
            float tb = top - tf.ascent;
            float sb = tb + tf.descent + space - sf.ascent;
            cv.drawText(t, 0, t.length(), x0 + pad, tb, title);
            CharSequence st = TextUtils.ellipsize(sub.toUpperCase(java.util.Locale.ROOT), small, avail, TextUtils.TruncateAt.END);
            cv.drawText(st, 0, st.length(), x0 + pad, sb, small);
        } else {
            cv.drawText(t, 0, t.length(), x0 + pad, mid + title.getTextSize() / 3f, title);
        }
    }
}
