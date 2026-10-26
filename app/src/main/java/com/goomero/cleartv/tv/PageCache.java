package com.goomero.cleartv.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The first section of each service saved on disk (rows and tiles only, at most 8 rows of 20, a few tens of KB),
 * so a service opens with its rows on screen at once while the live ones load. Kept for 12 hours.
 */
public final class PageCache {
    private static final String TAG = "CTV.Cache";
    private static final long MAX_AGE = 12 * 3600_000L;

    private PageCache() {}

    private static File file(Context c, String service) {
        return new File(c.getCacheDir(), "page_" + service + ".json");
    }

    public static void put(Context c, String service, Item.Page p) {
        if (p == null || p.isEmpty() || p.detail != null) return;
        try {
            JSONArray shelves = new JSONArray();
            for (int i = 0; i < Math.min(8, p.shelves.size()); i++) {
                Item.Shelf s = p.shelves.get(i);
                JSONArray items = new JSONArray();
                for (int j = 0; j < Math.min(20, s.items.size()); j++) items.put(Library.toJson(s.items.get(j)));
                JSONObject o = new JSONObject().put("t", s.title).put("i", items);
                if (s.mixed) o.put("m", true);
                shelves.put(o);
            }
            File f = file(c, service), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(shelves.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (JSONException | IOException e) {
            Log.w(TAG, service + ": " + e.getMessage());
        }
    }

    public static Item.Page get(Context c, String service) {
        File f = file(c, service);
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int got = 0;
            while (got < b.length) {
                int r = in.read(b, got, b.length - got);
                if (r < 0) break;
                got += r;
            }
            JSONArray shelves = new JSONArray(new String(b, 0, got, StandardCharsets.UTF_8));
            Item.Page p = new Item.Page();
            for (int i = 0; i < shelves.length(); i++) {
                JSONObject s = shelves.getJSONObject(i);
                JSONArray items = s.getJSONArray("i");
                List<Item> list = new ArrayList<>();
                for (int j = 0; j < items.length(); j++) list.add(Library.fromJson(items.getJSONObject(j)));
                Item.Shelf sh = new Item.Shelf(s.optString("t"), list);
                sh.mixed = s.optBoolean("m", false);
                p.shelves.add(sh);
            }
            return p;
        } catch (JSONException | IOException e) {
            return null;
        }
    }

    /** Artwork of the first rows, for {@link Images#prefetch}. */
    public static List<String> firstImages(Item.Page p, int max) {
        List<String> out = new ArrayList<>();
        for (Item.Shelf s : p.shelves) {
            for (Item it : s.items) {
                if (out.size() >= max) return out;
                if (!it.image.isEmpty()) out.add(it.image);
            }
        }
        return out;
    }
}
