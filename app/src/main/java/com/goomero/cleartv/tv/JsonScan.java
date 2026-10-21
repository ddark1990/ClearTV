package com.goomero.cleartv.tv;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Set;

/**
 * Streaming extraction from large JSON answers (YouTube search is ~1.6 MB, a Prime Video page ~4.5 MB, Tubi's home
 * ~0.8 MB) without holding the whole document: the reader walks the stream and only builds small JSONObjects for
 * the parts a screen needs. Keys in {@code skip} (tracking, menus, ad slots, players) are never materialised.
 */
public final class JsonScan {
    public interface Sink {
        /**
         * A captured value. {@code key} is the key it was found under; for {@code childrenOf} captures it is the
         * parent key and {@code name} the child's own key (or its index as text for arrays).
         */
        void onValue(String key, String name, JSONObject value);
    }

    private final Set<String> capture, childrenOf, skip;
    private final Sink sink;

    private JsonScan(Set<String> capture, Set<String> childrenOf, Set<String> skip, Sink sink) {
        this.capture = capture;
        this.childrenOf = childrenOf;
        this.skip = skip;
        this.sink = sink;
    }

    /**
     * Reads one JSON value from {@code r}. Objects under a key in {@code capture} are delivered whole; every
     * object member or array element directly under a key in {@code childrenOf} is delivered one by one.
     */
    public static void scan(JsonReader r, Set<String> capture, Set<String> childrenOf, Set<String> skip, Sink sink)
            throws IOException {
        r.setLenient(true);
        new JsonScan(capture, childrenOf, skip, sink).walk(r, "");
    }

    private void walk(JsonReader r, String key) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.BEGIN_OBJECT) {
            boolean children = childrenOf.contains(key);
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if (children) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) sink.onValue(key, k, readObject(r));
                    else r.skipValue();
                } else if (skip.contains(k)) {
                    r.skipValue();
                } else if (capture.contains(k) && r.peek() == JsonToken.BEGIN_OBJECT) {
                    sink.onValue(k, k, readObject(r));
                } else {
                    walk(r, k);
                }
            }
            r.endObject();
        } else if (t == JsonToken.BEGIN_ARRAY) {
            boolean children = childrenOf.contains(key);
            r.beginArray();
            int i = 0;
            while (r.hasNext()) {
                if (children && r.peek() == JsonToken.BEGIN_OBJECT) sink.onValue(key, String.valueOf(i), readObject(r));
                else walk(r, key);
                i++;
            }
            r.endArray();
        } else {
            r.skipValue();
        }
    }

    private JSONObject readObject(JsonReader r) throws IOException {
        JSONObject o = new JSONObject();
        r.beginObject();
        try {
            while (r.hasNext()) {
                String k = r.nextName();
                if (skip.contains(k)) r.skipValue();
                else o.put(k, readAny(r));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        r.endObject();
        return o;
    }

    private Object readAny(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BEGIN_OBJECT:
                return readObject(r);
            case BEGIN_ARRAY: {
                JSONArray a = new JSONArray();
                r.beginArray();
                while (r.hasNext()) a.put(readAny(r));
                r.endArray();
                return a;
            }
            case STRING:
                return r.nextString();
            case NUMBER: {
                String s = r.nextString();
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(s);
                }
            }
            case BOOLEAN:
                return r.nextBoolean();
            default:
                r.skipValue();
                return JSONObject.NULL;
        }
    }

    /** Text of a YouTube text object: simpleText, runs[].text or content. */
    public static String text(JSONObject o) {
        if (o == null) return "";
        String s = o.optString("simpleText", null);
        if (s != null) return s;
        s = o.optString("content", null);
        if (s != null) return s;
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) b.append(run.optString("text"));
        }
        return b.toString();
    }

    /** Follows a path of object keys; null when any step is missing. */
    public static JSONObject path(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o == null) return null;
            o = o.optJSONObject(k);
        }
        return o;
    }
}
