package com.goomero.cleartv.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import com.goomero.cleartv.Ui;

/**
 * A live miniature of the home screen drawn from any {@link Theme} (not the active one): background and backdrop,
 * header style, the home rows in the theme's order with banner or poster cards, a focused tile with the theme's ring,
 * radius and focus growth, and the text colours. Used by Settings > Theme while moving through the list.
 */
public final class ThemePreview extends View {
    private static final String[] SERVICES = {"YOUTUBE", "PRIME VIDEO", "TUBI", "PLUTO TV"};
    private static final int[] BRANDS = {0xFFFF3B30, 0xFF1A98FF, 0xFFFFDF00, 0xFFFFF200};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Theme theme = Theme.defaults();
    private Typeface display, body;

    public ThemePreview(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        setTheme(Themes.active());
    }

    private android.graphics.Bitmap snapshot;
    private Theme snapFor;

    public void setTheme(Theme t) {
        theme = t;
        if (t.ui == null) snapshot = null;
        else if (t != snapFor && getWidth() > 0) {
            snapshot = com.goomero.cleartv.LayoutSnapshot.render(getContext(), t, getWidth());
            snapFor = t;
        }
        display = Ui.face(getContext(), t.displayFont);
        body = Ui.face(getContext(), t.bodyFont);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, width * 9 / 16);
    }

    @Override
    protected void onDraw(Canvas cv) {
        Theme t = theme;
        float w = getWidth(), h = getHeight();
        if (t.ui != null) {
            // version 2: the real composed home, drawn small
            if (snapFor != t || snapshot == null) {
                snapshot = com.goomero.cleartv.LayoutSnapshot.render(getContext(), t, getWidth());
                snapFor = t;
            }
            if (snapshot != null) {
                cv.drawBitmap(snapshot, 0, 0, null);
                return;
            }
        }
        float u = w / 960f; // one home-screen dp at this size
        r.set(0, 0, w, h);
        fill.setShader(null);
        fill.setColor(t.background);
        cv.save();
        cv.clipRect(r);
        cv.drawRoundRect(r, 10 * u, 10 * u, fill);
        if (t.backdrop.equals("gradient")) {
            int a = t.accent & 0x00FFFFFF;
            fill.setShader(new RadialGradient(w * 0.82f, h * 0.12f, w * 0.6f, a | 0x2E000000, a, Shader.TileMode.CLAMP));
            cv.drawRect(r, fill);
            fill.setShader(null);
        } else if (t.backdrop.equals("stars")) {
            fill.setShader(new RadialGradient(w * 0.78f, h * 0.18f, w * 0.55f, 0x332B3A4A, 0x002B3A4A, Shader.TileMode.CLAMP));
            cv.drawRect(r, fill);
            fill.setShader(null);
            fill.setColor(0x99CBD5DF);
            int seed = 4211;
            for (int i = 0; i < 60; i++) {
                seed = seed * 1664525 + 1013904223;
                float x = (seed >>> 8) / 16777216f;
                seed = seed * 1664525 + 1013904223;
                float y = (seed >>> 8) / 16777216f;
                cv.drawCircle(x * w, y * h, Math.max(0.6f, 1.1f * u), fill);
            }
        }
        if (t.engine.equals("apple")) apple(cv, t, w, h, u);
        else if (t.engine.equals("rail")) rail(cv, t, w, h, u);
        else classic(cv, t, w, h, u);
        cv.restore();
        stroke.setStrokeWidth(Math.max(1, u));
        stroke.setColor(t.line);
        r.set(0, 0, w, h);
        cv.drawRoundRect(r, 10 * u, 10 * u, stroke);
    }

    /** Classic: header, service tiles, rows. */
    private void classic(Canvas cv, Theme t, float w, float h, float u) {
        float fs = t.fontScale;
        float x0 = 48 * u, y = 34 * u;
        // header
        if (!t.header.equals("minimal")) {
            txt.setTypeface(display);
            txt.setColor(t.text);
            txt.setTextSize((t.header.equals("compact") ? 20 : 26) * fs * u);
            txt.setLetterSpacing(0.12f);
            cv.drawText("CLEARTV", x0, y + txt.getTextSize(), txt);
            if (t.header.equals("full")) {
                txt.setTextSize(11 * fs * u);
                txt.setColor(t.textSoft);
                cv.drawText("AD-FREE TV", x0, y + 26 * fs * u + 18 * u, txt);
            }
        }
        pill(cv, w - 48 * u - 110 * u, y, 110 * u, 40 * u, "SETTINGS", u, fs);
        pill(cv, w - 48 * u - 240 * u, y, 120 * u, 40 * u, "SEARCH ALL", u, fs);
        y += 80 * u;
        for (String row : t.homeRows) {
            if (y > h) break;
            if (row.equals("services")) {
                float gap = 16 * u, tw = (w - 2 * x0 - 3 * gap) / 4, th = (t.servicesStyle.equals("hero") ? 210 : 150) * u;
                for (int i = 0; i < 4; i++) {
                    float x = x0 + i * (tw + gap);
                    boolean focus = i == 0;
                    float g = focus ? tw * (t.focusScale - 1) / 2 : 0;
                    r.set(x - g, y - g, x + tw + g, y + th + g);
                    fill.setColor(focus ? t.surface : (t.panel & 0x00FFFFFF) | (Math.round(t.cardOpacity * 2.55f) << 24));
                    cv.drawRoundRect(r, t.radius * u, t.radius * u, fill);
                    int brand = t.serviceAccents ? BRANDS[i] : t.accent;
                    fill.setColor((brand & 0x00FFFFFF) | 0x40000000);
                    RectF plate = new RectF(r.left + 8 * u, r.top + 8 * u, r.right - 8 * u, r.bottom - 52 * u);
                    cv.drawRoundRect(plate, 4 * u, 4 * u, fill);
                    if (focus) ring(cv, t, u);
                    txt.setTypeface(display);
                    txt.setLetterSpacing(0.12f);
                    txt.setTextSize(13 * fs * u);
                    txt.setColor(t.text);
                    cv.drawText(SERVICES[i], r.left + 14 * u, r.bottom - 26 * u, txt);
                    txt.setTextSize(9 * fs * u);
                    txt.setColor(t.textSoft);
                    cv.drawText("WATCH", r.left + 14 * u, r.bottom - 12 * u, txt);
                }
                y += th + t.rowSpacing * u + 8 * u;
            } else {
                txt.setTypeface(display);
                txt.setLetterSpacing(0.14f);
                txt.setTextSize(12 * fs * u);
                txt.setColor(t.textSoft);
                cv.drawText(row.equals("continue") ? "CONTINUE WATCHING" : "WATCHLIST", x0 + 8 * u, y + 12 * u, txt);
                y += 24 * u;
                float cw = 208 * t.tileSize * u, ch = 117 * t.tileSize * u;
                for (int i = 0; x0 + i * (cw + 16 * u) < w; i++) {
                    float x = x0 + i * (cw + 16 * u);
                    r.set(x, y, x + cw, y + ch);
                    fill.setColor(t.backgroundDeep);
                    cv.drawRoundRect(r, Math.min(t.radius, 8) * u, Math.min(t.radius, 8) * u, fill);
                    if (row.equals("continue")) {
                        fill.setColor(t.line);
                        cv.drawRect(r.left, r.bottom - 4 * u, r.right, r.bottom, fill);
                        fill.setColor(t.serviceAccents ? BRANDS[i % 4] : t.accent);
                        cv.drawRect(r.left, r.bottom - 4 * u, r.left + cw * 0.4f, r.bottom, fill);
                    }
                    txt.setTypeface(body);
                    txt.setLetterSpacing(0);
                    txt.setTextSize(14 * fs * u);
                    txt.setColor(t.text);
                    cv.drawText("Title " + (i + 1), x + 4 * u, y + ch + 18 * u, txt);
                    txt.setTextSize(10 * fs * u);
                    txt.setColor(t.textMuted);
                    cv.drawText("Service", x + 4 * u, y + ch + 32 * u, txt);
                }
                y += ch + 40 * u + t.rowSpacing * u;
            }
        }
    }

    /** A stand-in for artwork: a soft landscape (sky, hills) so previews read as pictures. */
    private void art(Canvas cv, RectF a, int seed) {
        int[] skies = {0xFF3B5A7A, 0xFF6A4A7A, 0xFF2F6A62, 0xFF7A5A3A};
        int sky = skies[Math.abs(seed) % skies.length];
        fill.setShader(new android.graphics.LinearGradient(0, a.top, 0, a.bottom, sky, 0xFF101418, Shader.TileMode.CLAMP));
        cv.drawRect(a, fill);
        fill.setShader(null);
        fill.setColor(0x55000000);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(a.left, a.bottom);
        p.lineTo(a.left, a.top + a.height() * 0.7f);
        p.quadTo(a.left + a.width() * 0.3f, a.top + a.height() * 0.45f, a.left + a.width() * 0.6f, a.top + a.height() * 0.68f);
        p.quadTo(a.left + a.width() * 0.8f, a.top + a.height() * 0.8f, a.right, a.top + a.height() * 0.6f);
        p.lineTo(a.right, a.bottom);
        p.close();
        cv.drawPath(p, fill);
    }

    /** The apple layout: one full-screen backdrop, the Top Shelf carousel, then the app grid (rows of five). */
    private void apple(Canvas cv, Theme t, float w, float h, float u) {
        float fs = t.fontScale;
        RectF full = new RectF(0, 0, w, h);
        art(cv, full, 2);
        int bg = t.background & 0x00FFFFFF;
        fill.setShader(new android.graphics.LinearGradient(0, 0, w * 0.7f, 0, bg | 0xB3000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(full, fill);
        fill.setShader(new android.graphics.LinearGradient(0, 0, 0, h, new int[]{0x00000000, bg | 0x1A000000, bg | 0x80000000, bg | 0xE6000000, t.background},
                null, Shader.TileMode.CLAMP));
        cv.drawRect(full, fill);
        fill.setShader(null);
        float x0 = 48 * u;
        txt.setTypeface(display);
        txt.setLetterSpacing(0.14f);
        txt.setTextSize(11 * fs * u);
        txt.setColor(0xCCFFFFFF);
        cv.drawText("CONTINUE WATCHING", x0, 64 * u, txt);
        txt.setLetterSpacing(0);
        txt.setTextSize(28 * fs * u);
        txt.setColor(0xFFFFFFFF);
        cv.drawText("A Great Title", x0, 100 * u, txt);
        txt.setTypeface(body);
        txt.setTextSize(14 * fs * u);
        txt.setColor(0xD9FFFFFF);
        cv.drawText("The focused app's content fills the top shelf.", x0, 126 * u, txt);
        txt.setTypeface(display);
        txt.setTextSize(18 * fs * u);
        txt.setColor(0xFFFFFFFF);
        txt.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("9:41", w - x0, 48 * u, txt);
        txt.setTextAlign(Paint.Align.LEFT);
        float r = Math.max(6, Math.min(t.radius, 12)) * u;
        // the Top Shelf carousel: wide cards, label only under the focused one
        float cw = 228 * t.tileSize * u, ch = 128 * t.tileSize * u, cy = 170 * u;
        for (int i = 0; x0 + i * (cw + 28 * u) < w; i++) {
            float x = x0 + i * (cw + 28 * u);
            this.r.set(x, cy, x + cw, cy + ch);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(this.r, r, r, android.graphics.Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            art(cv, this.r, 5 + i);
            cv.restore();
        }
        // the app grid: five icons, the first focused (scaled, shadow, label), a second row peeking in
        float iw = 150 * u, ih = 90 * u, gy = 368 * u;
        String[] names = {"YouTube", "Prime Video", "Tubi", "Pluto TV", "Search all"};
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < (row == 0 ? 5 : 2); i++) {
                float x = x0 + i * (iw + 28 * u), y = gy + row * (ih + 52 * u);
                boolean focus = row == 0 && i == 0;
                float g = focus ? iw * 0.05f : 0;
                this.r.set(x - g, y - g * 0.6f, x + iw + g, y + ih + g * 0.6f);
                if (focus) {
                    fill.setColor(0x66000000);
                    cv.drawRoundRect(this.r.left + 6 * u, this.r.top + 14 * u, this.r.right - 6 * u, this.r.bottom + 16 * u, r, r, fill);
                }
                fill.setColor(row == 0 && i < 4 ? (t.serviceAccents ? BRANDS[i] : t.accent) : 0xFF3A3D42);
                cv.drawRoundRect(this.r, r, r, fill);
                if (focus) {
                    fill.setColor(0x26FFFFFF);
                    cv.drawRect(this.r.left + iw * 0.2f, this.r.top, this.r.left + iw * 0.35f, this.r.bottom, fill);
                    txt.setTypeface(body);
                    txt.setTextSize(14 * fs * u);
                    txt.setColor(0xFFFFFFFF);
                    txt.setTextAlign(Paint.Align.CENTER);
                    cv.drawText(names[i], this.r.centerX(), y + ih + 30 * u, txt);
                    txt.setTextAlign(Paint.Align.LEFT);
                }
            }
        }
    }

    private void rail(Canvas cv, Theme t, float w, float h, float u) {
        float fs = t.fontScale;
        float rw = 236 * u;
        fill.setColor((t.panel & 0x00FFFFFF) | (Math.round(t.cardOpacity * 2.55f) << 24));
        cv.drawRect(0, 0, rw, h, fill);
        txt.setTypeface(display);
        txt.setLetterSpacing(0.12f);
        txt.setTextSize(20 * fs * u);
        txt.setColor(t.text);
        cv.drawText("CLEARTV", 48 * u, 58 * u, txt);
        String[] items = {"Search all", "YouTube", "Prime Video", "Tubi", "Pluto TV"};
        float y = 90 * u;
        for (int i = 0; i < items.length; i++) {
            if (i == 1) {
                r.set(32 * u, y, rw - 16 * u, y + 42 * u);
                fill.setColor(t.surface);
                cv.drawRoundRect(r, t.radius * u, t.radius * u, fill);
                ring(cv, t, u);
            }
            fill.setColor(i == 0 ? t.textMuted : (t.serviceAccents ? BRANDS[i - 1] : t.accent));
            cv.drawRoundRect(48 * u, y + 9 * u, 72 * u, y + 33 * u, 5 * u, 5 * u, fill);
            txt.setTypeface(body);
            txt.setLetterSpacing(0);
            txt.setTextSize(16 * fs * u);
            txt.setColor(t.text);
            cv.drawText(items[i], 88 * u, y + 27 * u, txt);
            y += 46 * u;
        }
        txt.setColor(t.textMuted);
        cv.drawText("Themes", 88 * u, h - 100 * u, txt);
        cv.drawText("Settings", 88 * u, h - 54 * u, txt);
        // banner
        float x0 = rw + 24 * u, x1 = w - 48 * u;
        RectF banner = new RectF(x0, 27 * u, x1, 241 * u);
        cv.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(banner, Math.max(t.radius, 8) * u, Math.max(t.radius, 8) * u, android.graphics.Path.Direction.CW);
        cv.clipPath(clip);
        art(cv, banner, 1);
        fill.setShader(new android.graphics.LinearGradient(x0, 0, x0 + (x1 - x0) * 0.7f, 0, 0xE60E1114, 0x000E1114, Shader.TileMode.CLAMP));
        cv.drawRect(banner, fill);
        fill.setShader(null);
        cv.restore();
        txt.setTypeface(display);
        txt.setTextSize(28 * fs * u);
        txt.setColor(0xFFFFFFFF);
        cv.drawText("YouTube", x0 + 24 * u, 200 * u, txt);
        // grid
        float gy = 241 * u + t.rowSpacing * u + 20 * u;
        txt.setTypeface(display);
        txt.setLetterSpacing(0.14f);
        txt.setTextSize(12 * fs * u);
        txt.setColor(t.textSoft);
        cv.drawText("SERVICES", x0 + 4 * u, gy, txt);
        gy += 12 * u;
        float gap = 16 * u, cw = (x1 - x0 - 3 * gap) / 4, ch = cw * 9 / 16;
        for (int rowI = 0; rowI < 2; rowI++) {
            for (int i = 0; i < 4; i++) {
                float x = x0 + i * (cw + gap);
                r.set(x, gy, x + cw, gy + ch + 28 * u);
                fill.setColor((t.panel & 0x00FFFFFF) | (Math.round(t.cardOpacity * 2.55f) << 24));
                cv.drawRoundRect(r, t.radius * u, t.radius * u, fill);
                RectF a = new RectF(x + 5 * u, gy + 5 * u, x + cw - 5 * u, gy + ch);
                if (rowI == 0) {
                    fill.setColor(((t.serviceAccents ? BRANDS[i] : t.accent) & 0x00FFFFFF) | 0x66000000);
                    cv.drawRoundRect(a, 4 * u, 4 * u, fill);
                } else {
                    art(cv, a, i);
                }
                txt.setTypeface(body);
                txt.setLetterSpacing(0);
                txt.setTextSize(12 * fs * u);
                txt.setColor(t.text);
                cv.drawText(rowI == 0 ? SERVICES[i] : "Title " + (i + 1), x + 8 * u, gy + ch + 20 * u, txt);
            }
            gy += ch + 28 * u + gap + 20 * u;
            if (gy > h) break;
        }
    }

    private void ring(Canvas cv, Theme t, float u) {
        stroke.setStrokeWidth(t.focusRing * u);
        stroke.setColor(t.accent);
        cv.drawRoundRect(r, t.radius * u, t.radius * u, stroke);
    }

    private void pill(Canvas cv, float x, float y, float pw, float ph, String label, float u, float fs) {
        r.set(x, y, x + pw, y + ph);
        float rad = Math.max(theme.radius, 13) * u + 6 * u;
        fill.setColor(theme.panel);
        cv.drawRoundRect(r, rad, rad, fill);
        stroke.setStrokeWidth(Math.max(1, u));
        stroke.setColor(theme.line);
        cv.drawRoundRect(r, rad, rad, stroke);
        txt.setTypeface(display);
        txt.setLetterSpacing(0.12f);
        txt.setTextSize(11 * fs * u);
        txt.setColor(theme.textMuted);
        float tw = txt.measureText(label);
        cv.drawText(label, x + (pw - tw) / 2, y + ph / 2 + txt.getTextSize() / 3, txt);
    }
}
