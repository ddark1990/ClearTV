package com.goomero.cleartv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Backup and restore of ClearTV's own choices to one JSON file on the TV (the app's folder on shared storage,
 * reachable with adb pull/push): favorites, live favorites and recents, resume points, the watchlist cache,
 * quality picks, settings and the profile chosen per service. Never sign-in data: no cookies, tokens, device
 * serials or session ids; services stay signed in (or not) as they are.
 */
public final class Backup {
    public static final String FILE = "cleartv-backup.json";
    private static final String[] WHOLE = {"library", "live_lists", "quality", "cleartv"};
    private static final String[] SERVICES = {"prime", "youtube", "tubi", "pluto"};
    private static final Set<String> SERVICE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "profile_id", "profile_name", "profile_avatar", "profile_ask", "cc_on"));
    /** Belt and braces: any key that looks like sign-in data is never written, whatever file it is in. */
    private static final Pattern SECRET = Pattern.compile("(?i).*(token|cookie|session|auth|refresh|serial|secret|password|pin|key|device).*");

    private Backup() {}

    public static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        return new File(dir != null ? dir : c.getFilesDir(), FILE);
    }

    /** Writes the backup; returns the file. */
    public static File export(Context c) throws IOException {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "ClearTV").put("format", 1).put("at", System.currentTimeMillis());
            JSONObject files = new JSONObject();
            for (String n : WHOLE) files.put(n, dump(c.getSharedPreferences(n, Context.MODE_PRIVATE), null));
            for (String s : SERVICES) files.put("svc_" + s, dump(c.getSharedPreferences("svc_" + s, Context.MODE_PRIVATE), SERVICE_KEYS));
            root.put("prefs", files);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File f = file(c);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return f;
    }

    private static JSONObject dump(SharedPreferences sp, Set<String> only) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (only != null && !only.contains(k)) continue;
            if (only == null && SECRET.matcher(k).matches()) continue;
            Object v = e.getValue();
            JSONObject t = new JSONObject();
            if (v instanceof Boolean) t.put("b", v);
            else if (v instanceof Integer) t.put("i", v);
            else if (v instanceof Long) t.put("l", v);
            else if (v instanceof Float) t.put("f", (double) (Float) v);
            else if (v instanceof String) t.put("s", v);
            else if (v instanceof Set) t.put("set", new JSONArray((Set<?>) v));
            else continue;
            o.put(k, t);
        }
        return o;
    }

    /** Reads the backup file back (merging over current values); returns how many values were restored. */
    public static int restore(Context c) throws IOException {
        File f = file(c);
        if (!f.exists()) throw new IOException("No backup file at " + f.getPath());
        byte[] b = new byte[(int) Math.min(f.length(), 8 << 20)];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
        }
        int count = 0;
        try {
            JSONObject root = new JSONObject(new String(b, StandardCharsets.UTF_8));
            if (!"ClearTV".equals(root.optString("app"))) throw new IOException("Not a ClearTV backup");
            JSONObject files = root.getJSONObject("prefs");
            java.util.Iterator<String> names = files.keys();
            while (names.hasNext()) {
                String n = names.next();
                boolean svc = n.startsWith("svc_") && java.util.Arrays.asList(SERVICES).contains(n.substring(4));
                if (!svc && !java.util.Arrays.asList(WHOLE).contains(n)) continue;
                JSONObject o = files.getJSONObject(n);
                SharedPreferences.Editor ed = c.getSharedPreferences(n, Context.MODE_PRIVATE).edit();
                java.util.Iterator<String> ks = o.keys();
                while (ks.hasNext()) {
                    String k = ks.next();
                    if (svc ? !SERVICE_KEYS.contains(k) : SECRET.matcher(k).matches()) continue;
                    JSONObject t = o.getJSONObject(k);
                    if (t.has("b")) ed.putBoolean(k, t.getBoolean("b"));
                    else if (t.has("i")) ed.putInt(k, t.getInt("i"));
                    else if (t.has("l")) ed.putLong(k, t.getLong("l"));
                    else if (t.has("f")) ed.putFloat(k, (float) t.getDouble("f"));
                    else if (t.has("s")) ed.putString(k, t.getString("s"));
                    else if (t.has("set")) {
                        Set<String> set = new java.util.HashSet<>();
                        JSONArray a = t.getJSONArray("set");
                        for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                        ed.putStringSet(k, set);
                    } else continue;
                    count++;
                }
                ed.apply();
            }
        } catch (JSONException e) {
            throw new IOException("The backup file is not readable");
        }
        return count;
    }
}
