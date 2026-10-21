package com.goomero.cleartv.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Live channel favorites and recently watched channels, per service, stored only on this TV. Shared by every
 * service with live channels (Pluto TV's guide, Prime Video's Live TV page). Entries are items as JSON
 * (Library's format), newest first.
 */
public final class LiveLists {
    public static final int MAX_FAV = 50, MAX_RECENT = 12;

    private LiveLists() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("live_lists", Context.MODE_PRIVATE);
    }

    private static List<Item> read(Context c, String key) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                Item it = Library.fromJson(a.optJSONObject(i));
                if (it != null) out.add(it);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void write(Context c, String key, List<Item> l) {
        JSONArray a = new JSONArray();
        for (Item it : l) a.put(Library.toJson(it));
        sp(c).edit().putString(key, a.toString()).apply();
    }

    public static synchronized List<Item> favorites(Context c, String service) {
        return read(c, "fav_" + service);
    }

    public static synchronized List<Item> recent(Context c, String service) {
        return read(c, "rec_" + service);
    }

    public static synchronized boolean isFavorite(Context c, String service, String id) {
        for (Item it : favorites(c, service)) if (it.id.equals(id)) return true;
        return false;
    }

    /** Adds or removes a favorite; returns true when it is now a favorite. */
    public static synchronized boolean toggleFavorite(Context c, String service, Item ch) {
        List<Item> l = favorites(c, service);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(ch.id)) {
                l.remove(i);
                write(c, "fav_" + service, l);
                return false;
            }
        }
        l.add(0, ch);
        while (l.size() > MAX_FAV) l.remove(l.size() - 1);
        write(c, "fav_" + service, l);
        return true;
    }

    /** A channel was tuned on purpose (not a preview): it moves to the front of Recently watched. */
    public static synchronized void watched(Context c, String service, Item ch) {
        List<Item> l = recent(c, service);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(ch.id)) l.remove(i);
        l.add(0, ch);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        write(c, "rec_" + service, l);
    }

}
