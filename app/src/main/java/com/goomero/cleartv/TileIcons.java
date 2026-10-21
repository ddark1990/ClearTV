package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;
import android.widget.ImageView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Service logos and brand colours, bundled in assets/icons by tools/fetch_icons.py at build time (112 px WebP,
 * about 5 KB each, plus colors.json). Decoded bitmaps are only kept while the home screen is showing.
 */
final class TileIcons {
    private static final String TAG = "CTV.Icons";
    private final Context app;
    private final HashMap<String, Bitmap> cache = new HashMap<>();
    private HashMap<String, Integer> colors;

    TileIcons(Context c) {
        app = c.getApplicationContext();
    }

    /** Sets the logo for an icon key (see {@link Services.Service#iconKey}); returns false when none is bundled. */
    boolean bind(ImageView v, String key) {
        Bitmap b = cache.get(key);
        if (b == null) {
            try (InputStream in = app.getAssets().open("icons/" + key + ".webp")) {
                b = BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                b = null;
            }
            if (b != null) cache.put(key, b);
        }
        v.setImageBitmap(b);
        return b != null;
    }

    /** The decoded logo for an icon key, or null when none is bundled. */
    Bitmap bitmap(String key) {
        Bitmap b = cache.get(key);
        if (b != null) return b;
        try (InputStream in = app.getAssets().open("icons/" + key + ".webp")) {
            b = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
        if (b != null) cache.put(key, b);
        return b;
    }

    /** Brand colour from colors.json, or 0 when the key has none. */
    int colorFor(String key) {
        if (colors == null) loadColors();
        Integer c = colors.get(key);
        return c == null ? 0 : c;
    }

    private void loadColors() {
        colors = new HashMap<>();
        try (InputStream in = app.getAssets().open("icons/colors.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                colors.put(k, Color.parseColor(o.getString(k)));
            }
        } catch (IOException | JSONException | IllegalArgumentException e) {
            Log.w(TAG, "colors.json: " + e);
        }
    }

    /** Text colour readable on a fill: near-black on light colours, white otherwise (relative luminance > 0.4). */
    static int textOn(int c) {
        double l = 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c));
        return l > 0.4 ? 0xFF111111 : 0xFFFFFFFF;
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Drops decoded logos (called when home is hidden or memory is low); they decode again in a millisecond. */
    void trim() {
        cache.clear();
    }
}
