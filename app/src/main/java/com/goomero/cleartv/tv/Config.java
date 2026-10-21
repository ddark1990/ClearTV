package com.goomero.cleartv.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The service adapters' moving parts (endpoints, client versions and keys, query ids, paths, the JSON keys the
 * parsers read or skip) as one versioned JSON document, built into the APK (assets/config.json). ClearTV never
 * downloads settings from anywhere: a change ships as a new app version (GitHub Releases, Settings > Updates).
 */
public final class Config {
    private static final String TAG = "CTV.Config";
    private static final int MAX_BYTES = 256 * 1024;

    private static volatile JSONObject doc = new JSONObject();
    private static volatile int version;

    private Config() {}

    /** Loads the bundled copy and deletes any settings file an older version downloaded (it must never override it). */
    public static synchronized void init(Context c) {
        for (String old : new String[]{"config.json", "config.sig", "config.json.tmp", "config.sig.tmp"}) {
            File f = new File(c.getFilesDir(), old);
            if (f.exists() && f.delete()) Log.i(TAG, "removed old downloaded " + old);
        }
        c.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().apply();
        try (InputStream in = c.getAssets().open("config.json")) {
            doc = new JSONObject(new String(read(in), StandardCharsets.UTF_8));
            version = doc.optInt("version");
            Log.i(TAG, "config v" + version + " (bundled)");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "bundled config unreadable", e);
        }
        for (Runnable r : listeners) r.run();
    }

    private static final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Adapters read their values through this (run once now, and again if the config is reloaded). */
    public static void onChange(Runnable r) {
        listeners.add(r);
        r.run();
    }

    public static int version() {
        return version;
    }

    // ------------------------------------------------------------------------------------- reading

    private static Object at(String path) {
        Object cur = doc;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(part);
        }
        return cur;
    }

    /** A string at a dotted path ("youtube.api"), or {@code def}. */
    public static String s(String path, String def) {
        Object v = at(path);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : def;
    }

    /** A string list at a dotted path as a set, or {@code def}. */
    public static Set<String> set(String path, String... def) {
        Object v = at(path);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            Set<String> out = new HashSet<>();
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            return out;
        }
        return new HashSet<>(Arrays.asList(def));
    }

    public static String[] list(String path, String... def) {
        Object v = at(path);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            String[] out = new String[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.optString(i);
            return out;
        }
        return def;
    }

    // ----------------------------------------------------------------------------------------- files

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            b.write(buf, 0, n);
            if (b.size() > MAX_BYTES) throw new IOException("too large");
        }
        return b.toByteArray();
    }
}
