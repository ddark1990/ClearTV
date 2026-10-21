package com.goomero.cleartv.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What the user watches and keeps, across services, stored on the TV only: playback progress (Continue Watching
 * on home) and favourites pinned to home (shows, films, Pluto TV channels). Small JSON lists in one preferences
 * file; at most 20 progress entries and 40 favourites.
 */
public final class Library {
    public static final int MAX_PROGRESS = 20, MAX_FAVORITES = 40;
    /** Shorter than this is a peek, not progress; past FINISHED of the runtime the title counts as watched. */
    private static final long MIN_MS = 30_000L;
    private static final float FINISHED = 0.95f;

    /** One entry: the service, the tile to reopen, and for progress the position. */
    public static final class Entry {
        public final String service;
        public final Item item;
        public final long positionMs, durationMs, at;

        Entry(String service, Item item, long positionMs, long durationMs, long at) {
            this.service = service;
            this.item = item;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.at = at;
        }

        public float progress() {
            return durationMs > 0 ? Math.min(1f, positionMs / (float) durationMs) : 0f;
        }
    }

    private Library() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("library", Context.MODE_PRIVATE);
    }

    // --------------------------------------------------------------------------------- items as JSON

    public static JSONObject toJson(Item it) {
        JSONObject o = new JSONObject();
        try {
            o.put("k", it.kind).put("i", it.id).put("t", it.title).put("s", it.subtitle).put("img", it.image)
                    .put("b", it.badge).put("x", it.extra);
            if (!it.description.isEmpty()) o.put("d", it.description.length() > 400 ? it.description.substring(0, 400) : it.description);
            if (it.channel != null && it.channel != it) o.put("ch", toJson(it.channel));
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Item fromJson(JSONObject o) {
        if (o == null) return null;
        Item it = new Item(o.optInt("k"), o.optString("i"), o.optString("t"), o.optString("s"), o.optString("img"),
                o.optString("b"), o.optString("x"));
        it.description = o.optString("d", "");
        JSONObject ch = o.optJSONObject("ch");
        if (ch != null) it.channel = fromJson(ch);
        return it;
    }

    public static String encode(Item it) {
        return it == null ? null : toJson(it).toString();
    }

    public static Item decode(String s) {
        if (s == null) return null;
        try {
            return fromJson(new JSONObject(s));
        } catch (JSONException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------------------------- progress

    /** Records where playback of {@code it} stopped; finished titles leave Continue Watching. */
    public static synchronized void record(Context c, String service, Item it, long posMs, long durMs) {
        if (service == null || it == null || it.kind == Item.LIVE || it.kind == Item.CHANNEL) return;
        // a peek (or a player that is already gone) changes nothing
        if (posMs < MIN_MS) return;
        List<Entry> list = read(c, "cw");
        String key = service + "|" + it.id;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            if ((e.service + "|" + e.item.id).equals(key)) list.remove(i);
        }
        boolean finished = durMs > 0 && posMs >= durMs * FINISHED;
        if (!finished) list.add(0, new Entry(service, it, posMs, durMs, System.currentTimeMillis()));
        while (list.size() > MAX_PROGRESS) list.remove(list.size() - 1);
        write(c, "cw", list);
    }

    /** Saved position for a title, or 0. */
    public static synchronized long resumeAt(Context c, String service, String id) {
        for (Entry e : read(c, "cw")) if (e.service.equals(service) && e.item.id.equals(id)) return e.positionMs;
        return 0;
    }

    public static synchronized List<Entry> continueWatching(Context c) {
        return read(c, "cw");
    }

    public static synchronized void forget(Context c, String service, String id) {
        List<Entry> list = read(c, "cw");
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).service.equals(service) && list.get(i).item.id.equals(id)) list.remove(i);
        }
        write(c, "cw", list);
    }

    // ---------------------------------------------------------------------------------- favourites

    public static synchronized List<Entry> favorites(Context c) {
        return read(c, "fav");
    }

    public static synchronized boolean isFavorite(Context c, String service, String id) {
        for (Entry e : read(c, "fav")) if (e.service.equals(service) && e.item.id.equals(id)) return true;
        return false;
    }

    /** Pins or unpins a tile on home; returns true when it is now pinned. */
    public static synchronized boolean toggleFavorite(Context c, String service, Item it) {
        List<Entry> list = read(c, "fav");
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            if (e.service.equals(service) && e.item.id.equals(it.id)) {
                list.remove(i);
                write(c, "fav", list);
                return false;
            }
        }
        list.add(0, new Entry(service, it, 0, 0, System.currentTimeMillis()));
        while (list.size() > MAX_FAVORITES) list.remove(list.size() - 1);
        write(c, "fav", list);
        return true;
    }

    // --------------------------------------------------------------------- the services' own history

    /** Caches a signed-in service's own history row (background refresh) for the home row. */
    public static synchronized void putHistory(Context c, String service, List<Item> items) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(12, items.size()); i++) a.put(toJson(items.get(i)));
        prefs(c).edit().putString("hist_" + service, a.toString()).putLong("hist_at_" + service, System.currentTimeMillis()).apply();
    }

    public static synchronized List<Item> history(Context c, String service) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("hist_" + service, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** The signed-in account's own saved list (watchlist, My List, Watch later), cached for home's Watchlist row. */
    public static synchronized void putWatchlist(Context c, String service, List<Item> items) {
        JSONArray a = new JSONArray();
        java.util.Set<String> hidden = prefs(c).getStringSet("wl_hidden_" + service, new java.util.HashSet<String>());
        for (int i = 0; i < items.size() && a.length() < 12; i++) if (!hidden.contains(items.get(i).id)) a.put(toJson(items.get(i)));
        prefs(c).edit().putString("wl_" + service, a.toString()).apply();
    }

    public static synchronized List<Item> watchlist(Context c, String service) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("wl_" + service, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Hides one title of a service's saved list from home (until the next refresh brings the list again). */
    public static synchronized void hideFromWatchlist(Context c, String service, String id) {
        List<Item> l = watchlist(c, service);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        JSONArray a = new JSONArray();
        for (Item it : l) a.put(toJson(it));
        prefs(c).edit().putString("wl_" + service, a.toString()).apply();
        java.util.Set<String> hidden = new java.util.HashSet<>(prefs(c).getStringSet("wl_hidden_" + service, new java.util.HashSet<String>()));
        hidden.add(id);
        prefs(c).edit().putStringSet("wl_hidden_" + service, hidden).apply();
    }

    public static synchronized boolean inWatchlist(Context c, String service, String id) {
        for (Item it : watchlist(c, service)) if (it.id.equals(id)) return true;
        return false;
    }

    public static long historyAge(Context c, String service) {
        return System.currentTimeMillis() - prefs(c).getLong("hist_at_" + service, 0);
    }

    public static synchronized void clearHistory(Context c, String service) {
        prefs(c).edit().remove("hist_" + service).remove("hist_at_" + service).remove("wl_" + service).apply();
    }

    // -------------------------------------------------------------------------------------- storage

    private static List<Entry> read(Context c, String key) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = fromJson(o.optJSONObject("item"));
                if (it == null) continue;
                out.add(new Entry(o.optString("svc"), it, o.optLong("pos"), o.optLong("dur"), o.optLong("at")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void write(Context c, String key, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) {
                a.put(new JSONObject().put("svc", e.service).put("item", toJson(e.item)).put("pos", e.positionMs)
                        .put("dur", e.durationMs).put("at", e.at));
            }
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(key, a.toString()).apply();
    }
}
