package com.goomero.cleartv.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Channels the user took off a service's rows ("Don't recommend channel" / "Hide channel"). The service's own
 * feedback is slow and leaves topic pages alone, so ClearTV keeps the list itself and drops those channels' tiles
 * from every row it shows. Kept in the "cleartv" preferences (so Backup carries it); Settings > Hidden channels
 * takes one back.
 */
public final class HiddenChannels {
    private static final String KEY = "hidden_channels";

    /** A hidden channel: the service, the channel id when known, and the name shown (and matched without an id). */
    public static final class Entry {
        public final String service, id, name;

        Entry(String service, String id, String name) {
            this.service = service;
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        /** Whether a tile of the channel {id, name} is this one: by id when both have one, else by name. */
        boolean matches(String[] key) {
            if (!id.isEmpty() && !key[0].isEmpty()) return id.equals(key[0]);
            return !name.isEmpty() && name.equalsIgnoreCase(key[1]);
        }
    }

    private HiddenChannels() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cleartv", Context.MODE_PRIVATE);
    }

    /** Every hidden channel, oldest first. */
    public static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(new Entry(o.optString("s"), o.optString("id"), o.optString("n")));
            }
        } catch (JSONException e) {
            return Collections.emptyList();
        }
        return out;
    }

    /** The hidden channels of one service. */
    public static List<Entry> of(Context c, String service) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all(c)) if (e.service.equals(service)) out.add(e);
        return out;
    }

    /** Whether a tile of {@code service} with channel {@code key} ({id, name}, from {@link Source#channelKey}) is in {@code list}. */
    public static boolean contains(List<Entry> list, String service, String[] key) {
        if (key == null) return false;
        for (Entry e : list) if (e.service.equals(service) && e.matches(key)) return true;
        return false;
    }

    /** Adds a channel ({id, name}); true when it was not on the list yet. */
    public static boolean add(Context c, String service, String[] key) {
        List<Entry> list = all(c);
        for (Entry e : list) if (e.service.equals(service) && e.matches(key)) return false;
        list.add(new Entry(service, key[0], key[1]));
        save(c, list);
        return true;
    }

    /** Takes a channel ({id, name}) off the list. */
    public static void remove(Context c, String service, String[] key) {
        List<Entry> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            if (e.service.equals(service) && e.matches(key)) list.remove(i);
        }
        save(c, list);
    }

    /** Takes one entry of {@link #all} off the list. */
    public static void remove(Context c, Entry gone) {
        remove(c, gone.service, new String[]{gone.id, gone.name});
    }

    private static void save(Context c, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) a.put(new JSONObject().put("s", e.service).put("id", e.id).put("n", e.name));
        } catch (JSONException ignored) {
            // plain strings never fail
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    /** The stored list as text, so a screen can tell whether it changed while it was away. */
    public static String stamp(Context c) {
        return prefs(c).getString(KEY, "[]");
    }
}
