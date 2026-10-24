package com.goomero.cleartv.tv;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Rows of artwork tiles as one self-drawn view, the same approach as the Pluto guide: no view per tile, only
 * what is on screen is drawn, and moving focus is a redraw with a short glide. Up/down change row (the focused
 * row glides to the top), left/right move along it; at the left edge or top row the key is left for the screen
 * so focus moves to the section rail or search box. A page's grid shelf (episodes) is drawn as wrapping rows of
 * {@link #COLUMNS} cards sized to the width: up/down keep the column, the view scrolls only as far as needed to
 * keep the focused row in sight, and nothing runs off the right edge.
 */
public final class ShelfView extends View {
    public interface Listener {
        void onFocusItem(Item item);

        void onClickItem(Item item);

        /** OK held on a tile (or MENU): the tile's actions, such as pinning it to home. */
        void onLongClickItem(Item item);

        /** Focus reached one of the last rows: a good time to append the next rows. */
        void onNearEnd();

        /** Focus came near the end of a row the service pages (shelf.more): a good time to load its next tiles. */
        void onRowNearEnd(Item.Shelf shelf);
    }

    public static final int COLUMNS = 4;
    private final boolean APPLE = Ui.ENGINE.equals("apple");
    private final List<Item.Shelf> rows = new ArrayList<>();
    /** Per row: part of the grid, and its top in the scrolled content. */
    private boolean[] gridRow = new boolean[0];
    private int[] rowTop = new int[0];
    private int contentH;
    private int gridW, gridH, gridStep;
    private float[] rowX = new float[0];
    private int[] rowCol = new int[0];
    private int focusRow, focusCol;
    private float curY, targetY;
    private int accent = Ui.ACCENT;
    private Images images;
    private Listener listener;

    private final int cardW, cardH, step, titleH, rowH, textH, ring, radius, pad, focusGrow, gap;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint rowTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badge = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Rect src = new Rect();
    private final android.graphics.Path clip = new android.graphics.Path();

    public ShelfView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        // theme: tile size multiplier (landscape 16:9 tiles only, so artwork is never cut to a portrait)
        cardW = Ui.dp(c, (Ui.ENGINE.equals("apple") ? 200 : 188) * Ui.TILE_SCALE);
        cardH = cardW * 9 / 16;
        step = cardW + Ui.dp(c, Ui.S2);
        ring = Ui.dp(c, Ui.RING);
        radius = Ui.dp(c, Ui.RADIUS);
        pad = Ui.dp(c, Ui.S1);
        rowTitle.setTextSize(sp(c, 12));
        rowTitle.setTypeface(Ui.display(c));
        rowTitle.setLetterSpacing(0.12f);
        rowTitle.setColor(Ui.SOFT);
        title.setTextSize(sp(c, 14));
        title.setTypeface(Ui.body(c));
        title.setColor(Ui.TEXT);
        sub.setTextSize(sp(c, 11));
        sub.setColor(Ui.SOFT);
        badge.setTextSize(sp(c, 10));
        badge.setTypeface(Ui.display(c));
        badge.setColor(0xFFFFFFFF); // on the dark/red badge fill, any theme
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(ring);
        // vertical rhythm from the real font metrics (8 dp grid): row title, 16 dp, card, 8 dp, title, 4 dp,
        // sub-line, 16 dp to the next row's title
        Paint.FontMetrics rt = rowTitle.getFontMetrics(), tf = title.getFontMetrics(), sf = sub.getFontMetrics();
        focusGrow = Ui.dp(c, APPLE ? 8 : 5) * 9 / 16 + ring; // a focused card grows and gains its ring on every side
        gap = Ui.dp(c, Ui.S2);
        rowTitleBase = Math.round(-rt.ascent);
        titleH = Math.round(rt.descent - rt.ascent) + Ui.dp(c, Ui.S1) + focusGrow;
        titleBase = Ui.dp(c, Ui.S1) + focusGrow + Math.round(-tf.ascent);
        subBase = titleBase + Math.round(tf.descent) + Ui.dp(c, Ui.S_HALF) + Math.round(-sf.ascent);
        textH = subBase + Math.round(sf.descent);
        rowH = titleH + cardH + textH + Ui.dp(c, Ui.ROW_GAP);
    }

    private int rowTitleBase, titleBase, subBase;

    private static float sp(Context c, float v) {
        return Ui.sp(c, v);
    }

    public int cardWidthPx() {
        return cardW;
    }

    public void setAccent(int c) {
        accent = c;
        invalidate();
    }

    public void setImages(Images i) {
        images = i;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Replaces all rows; focus goes to (row, col) when given, else the first tile. */
    public void setPage(Item.Page page, int row, int col) {
        rows.clear();
        List<Boolean> grid = new ArrayList<>();
        for (int i = 0; i < page.shelves.size(); i++) {
            Item.Shelf s = page.shelves.get(i);
            if (s.items.isEmpty()) continue;
            if (i == page.grid) {
                for (int k = 0; k < s.items.size(); k += COLUMNS) {
                    // copies, not views: a long feed's grid grows later (appendGrid) and the page's list with it
                    rows.add(new Item.Shelf(k == 0 ? s.title : "",
                            new ArrayList<>(s.items.subList(k, Math.min(s.items.size(), k + COLUMNS)))));
                    grid.add(true);
                }
            } else {
                rows.add(s);
                grid.add(false);
            }
        }
        gridRow = new boolean[rows.size()];
        for (int i = 0; i < gridRow.length; i++) gridRow[i] = grid.get(i);
        rowX = new float[rows.size()];
        rowCol = new int[rows.size()];
        layoutRows();
        focusRow = Math.max(0, Math.min(row, rows.size() - 1));
        if (!rows.isEmpty()) {
            focusCol = Math.max(0, Math.min(col, rows.get(focusRow).items.size() - 1));
            rowCol[focusRow] = focusCol;
        } else {
            focusCol = 0;
        }
        for (int i = 0; i < rows.size(); i++) rowX[i] = targetX(i, rowCol[i]);
        curY = targetY = scrollFor(focusRow, 0);
        invalidate();
        notifyFocus();
    }

    /** Card size of grid rows (four across the width) and every row's top. */
    private void layoutRows() {
        int w = getWidth() > 0 ? getWidth() : cardW * COLUMNS + gap * COLUMNS;
        gridW = Math.max(Ui.dp(getContext(), 96), (w - 2 * pad - (COLUMNS - 1) * gap) / COLUMNS);
        gridH = gridW * 9 / 16;
        gridStep = gridW + gap;
        rowTop = new int[rows.size()];
        int y = 0;
        for (int i = 0; i < rows.size(); i++) {
            rowTop[i] = y;
            y += heightOf(i);
        }
        contentH = y;
    }

    private int heightOf(int row) {
        boolean titled = !rows.get(row).title.isEmpty();
        int head = titled ? titleH : focusGrow;
        return head + (gridRow[row] ? gridH : cardH) + textH + Ui.dp(getContext(), Ui.ROW_GAP);
    }

    /**
     * Scroll position for a focused row: browse rows glide to the top; grid rows move only as far as needed to
     * be fully visible (and the grid's title stays when its first row is focused).
     */
    private float scrollFor(int row, float current) {
        if (rows.isEmpty()) return 0;
        if (!gridRow[row]) return rowTop[row];
        int h = getHeight() > 0 ? getHeight() : rowH * 2;
        int top = rowTop[row], bottom = top + heightOf(row);
        // the first grid row brings its title along
        if (row > 0 && !rows.get(row).title.isEmpty()) top = rowTop[row];
        float y = current;
        if (top < y) y = top;
        if (bottom > y + h) y = bottom - h;
        return Math.max(0, Math.min(y, Math.max(0, contentH - h)));
    }

    /** Appends rows (next page of a long home screen); focus stays where it is. */
    public void append(Item.Page page) {
        int old = rows.size();
        for (Item.Shelf s : page.shelves) if (!s.items.isEmpty()) rows.add(s);
        float[] nx = new float[rows.size()];
        int[] nc = new int[rows.size()];
        boolean[] ng = new boolean[rows.size()];
        System.arraycopy(rowX, 0, nx, 0, old);
        System.arraycopy(rowCol, 0, nc, 0, old);
        System.arraycopy(gridRow, 0, ng, 0, old);
        rowX = nx;
        rowCol = nc;
        gridRow = ng;
        layoutRows();
        invalidate();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int focusRow() {
        return focusRow;
    }

    public int focusCol() {
        return focusCol;
    }

    public Item focusedItem() {
        if (focusRow >= rows.size()) return null;
        List<Item> l = rows.get(focusRow).items;
        return focusCol < l.size() ? l.get(focusCol) : null;
    }

    private float targetX(int row, int col) {
        if (gridRow[row]) return 0;
        int n = rows.get(row).items.size();
        float max = Math.max(0, n * step - (getWidth() > 0 ? getWidth() : step * 4) + Ui.dp(getContext(), 8));
        return Math.max(0, Math.min(max, (col - 1) * step + step / 3f));
    }

    private void notifyFocus() {
        Item it = focusedItem();
        if (listener != null && it != null) listener.onFocusItem(it);
        if (listener != null && focusRow >= rows.size() - 2) listener.onNearEnd();
        if (listener != null && focusRow < rows.size() && !gridRow[focusRow]) {
            Item.Shelf s = rows.get(focusRow);
            if (s.more != null && focusCol >= s.items.size() - 6) listener.onRowNearEnd(s);
        }
    }

    /** Focus is on one of the last two rows (more rows should follow when the page has them). */
    public boolean nearEnd() {
        return focusRow >= rows.size() - 2;
    }

    /** A paged row got more tiles (added to shelf.items): redraw; focus and scroll stay where they are. */
    public void rowGrew(Item.Shelf shelf) {
        invalidate();
    }

    /**
     * Continues the page's grid (the last rows) with more tiles: the last, partly filled grid row is completed
     * first, then new rows of COLUMNS follow. Focus stays where it is.
     */
    public void appendGrid(List<Item> items) {
        if (items.isEmpty()) return;
        int last = rows.size() - 1;
        List<Item> rest = new ArrayList<>(items);
        if (last >= 0 && gridRow[last] && rows.get(last).items.size() < COLUMNS) {
            Item.Shelf old = rows.get(last);
            List<Item> fill = new ArrayList<>(old.items);
            while (fill.size() < COLUMNS && !rest.isEmpty()) fill.add(rest.remove(0));
            rows.set(last, new Item.Shelf(old.title, fill));
        }
        int old = rows.size();
        for (int k = 0; k < rest.size(); k += COLUMNS) {
            rows.add(new Item.Shelf("", new ArrayList<>(rest.subList(k, Math.min(rest.size(), k + COLUMNS)))));
        }
        float[] nx = new float[rows.size()];
        int[] nc = new int[rows.size()];
        boolean[] ng = new boolean[rows.size()];
        System.arraycopy(rowX, 0, nx, 0, old);
        System.arraycopy(rowCol, 0, nc, 0, old);
        System.arraycopy(gridRow, 0, ng, 0, old);
        for (int i = old; i < rows.size(); i++) ng[i] = true;
        rowX = nx;
        rowCol = nc;
        gridRow = ng;
        layoutRows();
        invalidate();
    }

    private void moveTo(int row, int col) {
        rowCol[focusRow] = focusCol;
        focusRow = row;
        focusCol = Math.max(0, Math.min(col, rows.get(row).items.size() - 1));
        rowCol[row] = focusCol;
        targetY = scrollFor(focusRow, targetY);
        invalidate();
        notifyFocus();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (rows.isEmpty()) return super.onKeyDown(code, ev);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusRow == 0) return false;
                // within the grid the column carries over, as in any grid
                moveTo(focusRow - 1, gridRow[focusRow] && gridRow[focusRow - 1] ? focusCol : rowCol[focusRow - 1]);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusRow < rows.size() - 1) {
                    moveTo(focusRow + 1, gridRow[focusRow] && gridRow[focusRow + 1] ? focusCol : rowCol[focusRow + 1]);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focusCol == 0) return false;
                moveTo(focusRow, focusCol - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focusCol < rows.get(focusRow).items.size() - 1) moveTo(focusRow, focusCol + 1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                moveTo(focusRow, focusCol + 4);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                moveTo(focusRow, focusCol - 4);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // a tap opens the tile on release; held (key repeat) it offers the tile's actions instead
                if (ev.getRepeatCount() == 0) {
                    okDown = true;
                    okLong = false;
                } else if (okDown && !okLong && isHeld(ev)) {
                    okLong = true;
                    if (listener != null && focusedItem() != null) listener.onLongClickItem(focusedItem());
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (ev.getRepeatCount() == 0 && listener != null && focusedItem() != null) {
                    listener.onLongClickItem(focusedItem());
                    return true;
                }
                return super.onKeyDown(code, ev);
            default:
                return super.onKeyDown(code, ev);
        }
    }

    /** OK counts as held after ~500 ms (real remote key repeat) or when the system flags a long press. */
    public static boolean isHeld(KeyEvent ev) {
        return ev.getRepeatCount() > 0 && (ev.isLongPress() || ev.getEventTime() - ev.getDownTime() >= 500);
    }

    private boolean okDown, okLong;

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            boolean click = okDown && !okLong;
            okDown = false;
            okLong = false;
            if (click && listener != null && focusedItem() != null) listener.onClickItem(focusedItem());
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    @Override
    protected void onFocusChanged(boolean gain, int dir, Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        if (gain) notifyFocus();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutRows();
        for (int i = 0; i < rows.size(); i++) rowX[i] = targetX(i, rowCol[i]);
        if (!rows.isEmpty()) curY = targetY = scrollFor(focusRow, targetY);
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (rows.isEmpty()) return;
        boolean animating = false;
        float dy = targetY - curY;
        if (Math.abs(dy) > 1) {
            curY += dy * 0.35f;
            animating = true;
        } else {
            curY = targetY;
        }
        boolean hasFocus = hasFocus();
        int h = getHeight(), w = getWidth();
        for (int ri = 0; ri < rows.size(); ri++) {
            float top = rowTop[ri] - curY;
            if (top > h) break;
            if (top + heightOf(ri) < 0) continue;
            boolean g = gridRow[ri];
            int cw = g ? gridW : cardW, ch = g ? gridH : cardH, st = g ? gridStep : step;
            float head = s0(ri) ? titleH : focusGrow;
            Item.Shelf s = rows.get(ri);
            int col = ri == focusRow ? focusCol : rowCol[ri];
            float tx = targetX(ri, col);
            float dx = tx - rowX[ri];
            if (Math.abs(dx) > 1) {
                rowX[ri] += dx * 0.35f;
                animating = true;
            } else {
                rowX[ri] = tx;
            }
            if (!s.title.isEmpty()) {
                rowTitle.setColor(ri == focusRow && hasFocus ? Ui.MUTED : Ui.SOFT);
                cv.drawText(s.title.toUpperCase(java.util.Locale.ROOT), pad, top + rowTitleBase, rowTitle);
            }
            int firstCol = Math.max(0, (int) (rowX[ri] / st) - 1);
            for (int ci = firstCol; ci < s.items.size(); ci++) {
                float x = pad + ci * st - rowX[ri];
                if (x > w) break;
                if (x + cw < 0) continue;
                drawCard(cv, s.items.get(ci), x, top + head, cw, ch, hasFocus && ri == focusRow && ci == focusCol);
            }
        }
        if (animating) postInvalidateOnAnimation();
    }

    private boolean s0(int row) {
        return !rows.get(row).title.isEmpty();
    }

    private void drawCard(Canvas cv, Item it, float x, float y, int cardW, int cardH, boolean focused) {
        float grow = focused ? Ui.dp(getContext(), APPLE ? 8 : 5) : 0;
        r.set(x - grow, y - grow * 9 / 16f, x + cardW + grow, y + cardH + grow * 9 / 16f);
        if (focused && APPLE) {
            // focus lift: a soft shadow under the raised card
            float d = Ui.dp(getContext(), 6);
            fill.setColor(0x40000000);
            cv.drawRoundRect(r.left + d / 2, r.top + d * 1.5f, r.right - d / 2, r.bottom + d * 1.5f, radius, radius, fill);
            fill.setColor(0x26000000);
            cv.drawRoundRect(r.left, r.top + d * 2.5f, r.right, r.bottom + d * 2.5f, radius * 1.4f, radius * 1.4f, fill);
        }
        fill.setColor(Ui.BAR);
        cv.drawRoundRect(r, radius, radius, fill);
        Bitmap b = images != null ? images.get(it.image) : null;
        if (b != null) {
            // centre-crop into 16:9
            float bw = b.getWidth(), bh = b.getHeight();
            float want = r.width() / r.height();
            if (bw / bh > want) {
                int cw = (int) (bh * want);
                src.set((int) (bw - cw) / 2, 0, (int) (bw + cw) / 2, (int) bh);
            } else {
                int ch = (int) (bw / want);
                src.set(0, (int) (bh - ch) / 2, (int) bw, (int) (bh + ch) / 2);
            }
            cv.drawBitmap(b, src, r, bmp);
        } else {
            title.setColor(Ui.SOFT);
            CharSequence t = TextUtils.ellipsize(it.title, title, r.width() - pad * 2, TextUtils.TruncateAt.END);
            cv.drawText(t, 0, t.length(), r.left + pad, r.centerY() + title.getTextSize() / 3f, title);
        }
        if (it.progress > 0) {
            // how far it was watched, like the cards of home's Continue watching row
            float h = Ui.dp(getContext(), 4);
            cv.save();
            clip.reset();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            cv.clipPath(clip);
            fill.setColor(0x99000000);
            cv.drawRect(r.left, r.bottom - h, r.right, r.bottom, fill);
            fill.setColor(accent);
            cv.drawRect(r.left, r.bottom - h, r.left + r.width() * Math.max(0.03f, Math.min(1f, it.progress)), r.bottom, fill);
            cv.restore();
        }
        if (!it.badge.isEmpty()) {
            float bw = badge.measureText(it.badge) + pad;
            float bh = badge.getTextSize() + pad * 0.8f;
            boolean live = it.kind == Item.LIVE || "LIVE".equals(it.badge);
            fill.setColor(live ? 0xE6CC1F2E : 0xD9000000);
            RectF br = new RectF(r.right - bw - pad / 2f, r.bottom - bh - pad / 2f, r.right - pad / 2f, r.bottom - pad / 2f);
            cv.drawRoundRect(br, radius / 2f, radius / 2f, fill);
            cv.drawText(it.badge, br.left + pad / 2f, br.bottom - pad * 0.55f, badge);
        }
        if (focused) {
            stroke.setColor(accent);
            RectF fr = new RectF(r);
            fr.inset(-ring / 2f, -ring / 2f);
            cv.drawRoundRect(fr, radius + ring / 2f, radius + ring / 2f, stroke);
        }
        float ty = y + cardH + titleBase;
        if (it.drawTitle == null || it.drawW != cardW) {
            it.drawW = cardW;
            it.drawTitle = TextUtils.ellipsize(it.title, title, cardW, TextUtils.TruncateAt.END);
            it.drawSub = TextUtils.ellipsize(it.subtitle, sub, cardW, TextUtils.TruncateAt.END);
        }
        // apple layout: like tvOS, only the focused card shows its title
        if (APPLE && !focused) return;
        title.setColor(focused ? Ui.TEXT : Ui.MUTED);
        cv.drawText(it.drawTitle, 0, it.drawTitle.length(), x, ty, title);
        cv.drawText(it.drawSub, 0, it.drawSub.length(), x, y + cardH + subBase, sub);
    }
}
