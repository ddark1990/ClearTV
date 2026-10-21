package com.goomero.cleartv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proof of blocking for Settings > Ad blocking: what was blocked per service (today and in total) and the last
 * few blocked hosts. Two kinds are counted: requests the filter engine blocked in web pages, and ad data ClearTV
 * removed itself (ad cue points it skipped in its own player, ad placements stripped from YouTube's player
 * answer). Counting is a few integer updates; the numbers are written at most every 5 s on a low-priority
 * thread. Every block is also logged as "CTV.Block".
 */
public final class BlockStats {
    public static final class Entry {
        public final String service;
        public final int today, total;
        public final List<String> recent;

        Entry(String service, int today, int total, List<String> recent) {
            this.service = service;
            this.today = today;
            this.total = total;
            this.recent = recent;
        }
    }

    private static final String TAG = "CTV.Block";
    private static final int RECENT = 5;
    private static final Map<String, int[]> counts = new HashMap<>(); // service -> {today, total}
    private static final Map<String, List<String>> recent = new HashMap<>();
    private static String day = "";
    private static SharedPreferences prefs;
    private static Handler writer;
    private static boolean writePending;

    private BlockStats() {}

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** Loads the saved numbers; once per process. */
    public static synchronized void init(Context c) {
        if (prefs != null) return;
        prefs = c.getApplicationContext().getSharedPreferences("blockstats", Context.MODE_PRIVATE);
        day = prefs.getString("day", today());
        try {
            JSONObject o = new JSONObject(prefs.getString("data", "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String svc = it.next();
                JSONObject s = o.getJSONObject(svc);
                counts.put(svc, new int[]{s.optInt("today"), s.optInt("total")});
                List<String> r = new ArrayList<>();
                JSONArray a = s.optJSONArray("recent");
                if (a != null) for (int i = 0; i < a.length(); i++) r.add(a.optString(i));
                recent.put(svc, r);
            }
        } catch (JSONException e) {
            Log.w(TAG, "saved block stats unreadable; starting over");
        }
        rollDay();
        HandlerThread t = new HandlerThread("ctv-blockstats", Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        writer = new Handler(t.getLooper());
    }

    private static void rollDay() {
        String d = today();
        if (d.equals(day)) return;
        day = d;
        for (int[] c : counts.values()) c[0] = 0;
    }

    /** One blocked item of {@code service} (a service id; empty means "other"). Any thread. */
    public static void record(String service, String host, String what) {
        String svc = service == null || service.isEmpty() ? "other" : service;
        Log.i(TAG, svc + " " + host + " (" + what + ")");
        synchronized (BlockStats.class) {
            if (prefs == null) return;
            rollDay();
            int[] c = counts.get(svc);
            if (c == null) counts.put(svc, c = new int[2]);
            c[0]++;
            c[1]++;
            List<String> r = recent.get(svc);
            if (r == null) recent.put(svc, r = new ArrayList<>());
            r.remove(host);
            r.add(0, host);
            while (r.size() > RECENT) r.remove(r.size() - 1);
            if (!writePending) {
                writePending = true;
                writer.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        save();
                    }
                }, 5000);
            }
        }
    }

    private static synchronized void save() {
        writePending = false;
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, int[]> e : counts.entrySet()) {
                JSONObject s = new JSONObject().put("today", e.getValue()[0]).put("total", e.getValue()[1]);
                List<String> r = recent.get(e.getKey());
                s.put("recent", new JSONArray(r == null ? new ArrayList<String>() : r));
                o.put(e.getKey(), s);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("day", day).putString("data", o.toString()).apply();
    }

    public static synchronized Entry get(String service) {
        rollDay();
        int[] c = counts.get(service);
        List<String> r = recent.get(service);
        return new Entry(service, c == null ? 0 : c[0], c == null ? 0 : c[1],
                r == null ? new ArrayList<String>() : new ArrayList<>(r));
    }

    public static synchronized void reset() {
        counts.clear();
        recent.clear();
        if (prefs != null) prefs.edit().remove("data").apply();
    }
}
