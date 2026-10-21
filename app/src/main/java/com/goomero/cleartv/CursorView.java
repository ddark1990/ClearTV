package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Mouse-style pointer over the WebView. It is a small fixed-size view on its own hardware layer, positioned with
 * translationX/Y only: moving it changes two render properties, so nothing is re-recorded, laid out or
 * invalidated, neither the pointer itself nor the WebView or the toolbar. Only the pressed ring redraws the layer.
 */
public final class CursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float tip;      // offset of the arrow tip (the hot spot) inside the view
    private final int size;
    private float x = -1, y = -1; // hot spot in parent (WebView) coordinates
    private boolean pressed;

    public CursorView(Context c) {
        super(c);
        float scale = c.getResources().getDisplayMetrics().density;
        tip = 18 * scale;
        size = Math.round(52 * scale);
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * scale);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        ring.setColor(0x804FC3F7);
        ring.setStyle(Paint.Style.FILL);
        float s = scale * 1.6f;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * s);
        arrow.lineTo(4.5f * s, 13 * s);
        arrow.lineTo(7.5f * s, 20 * s);
        arrow.lineTo(10.5f * s, 18.8f * s);
        arrow.lineTo(7.5f * s, 12 * s);
        arrow.lineTo(13 * s, 12 * s);
        arrow.close();
        setFocusable(false);
        setClickable(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setVisibility(INVISIBLE); // shown on the first arrow key in cursor mode
    }

    /** Side of the square view in pixels (use for its layout params). */
    public int sizePx() {
        return size;
    }

    public float cx() {
        return x;
    }

    public float cy() {
        return y;
    }

    private int areaW() {
        View p = (View) getParent();
        return p == null ? 0 : p.getWidth();
    }

    private int areaH() {
        View p = (View) getParent();
        return p == null ? 0 : p.getHeight();
    }

    private void place() {
        setTranslationX(x - tip);
        setTranslationY(y - tip);
    }

    public void center() {
        if (areaW() > 0) {
            x = areaW() / 2f;
            y = areaH() / 2f;
            place();
        }
    }

    /** Moves the hot spot, clamped to the parent; returns the part of (dx, dy) that could not be applied. */
    public float[] moveBy(float dx, float dy) {
        if (x < 0 || x >= areaW() || y >= areaH()) center();
        float nx = Math.max(0, Math.min(areaW() - 1, x + dx));
        float ny = Math.max(0, Math.min(areaH() - 1, y + dy));
        float[] rest = {dx - (nx - x), dy - (ny - y)};
        if (nx != x || ny != y) {
            x = nx;
            y = ny;
            place();
        }
        return rest;
    }

    public void setPressedState(boolean p) {
        if (pressed == p) return;
        pressed = p;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pressed) c.drawCircle(tip, tip, 18 * getResources().getDisplayMetrics().density, ring);
        c.save();
        c.translate(tip, tip);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, stroke);
        c.restore();
    }
}
