package com.goomero.cleartv.pluto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Pluto TV's public web API, as the pluto.tv site itself uses it: an anonymous session from boot.pluto.tv (no
 * Widevine capability, so every channel is offered as clear-key AES-128 HLS), the channel list, categories and
 * guide timelines. Responses are cached under cacheDir/pluto in slim form, so the guide shows instantly on the next
 * start and refreshes in the background. All network calls block: call them off the main thread.
 */
public final class PlutoApi {
    private static final String TAG = "CTV.Pluto";
    private static String CHANNELS, BOOT, APP_NAME, APP_VERSION, DEVICE_VERSION;

    static {
        // endpoints and the web client identity come from the bundled config
        com.goomero.cleartv.tv.Config.onChange(new Runnable() {
            @Override
            public void run() {
                CHANNELS = com.goomero.cleartv.tv.Config.s("pluto.channels", "https://service-channels.clusters.pluto.tv/v2/guide/");
                BOOT = com.goomero.cleartv.tv.Config.s("pluto.boot", "https://boot.pluto.tv/v4/start");
                APP_NAME = com.goomero.cleartv.tv.Config.s("pluto.appName", "web");
                APP_VERSION = com.goomero.cleartv.tv.Config.s("pluto.appVersion", "9.6.0");
                DEVICE_VERSION = com.goomero.cleartv.tv.Config.s("pluto.deviceVersion", "120.0.0");
            }
        });
    }
    private static final long CHANNELS_TTL_MS = 12 * 3600_000L;
    private static final int TIMELINE_BATCH = 100;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";

    static final class Session {
        final String token, stitcher, params;
        final long refreshAt;

        Session(String token, String stitcher, String params, long refreshAt) {
            this.token = token;
            this.stitcher = stitcher;
            this.params = params;
            this.refreshAt = refreshAt;
        }
    }

    static final class Program {
        final long start, stop;
        final String title, episode, description, rating, genre;

        Program(long start, long stop, String title, String episode, String description, String rating, String genre) {
            this.start = start;
            this.stop = stop;
            this.title = title;
            this.episode = episode;
            this.description = description;
            this.rating = rating;
            this.genre = genre;
        }
    }

    static final class Channel {
        final String id, name, logo;
        final int number;
        final String[] categories;
        /** Guide entries in start order; replaced as a whole on the main thread. */
        volatile List<Program> programs = Collections.emptyList();

        Channel(String id, int number, String name, String logo, String[] categories) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.logo = logo;
            this.categories = categories;
        }

        Program at(long t) {
            for (Program p : programs) if (p.start <= t && t < p.stop) return p;
            return null;
        }

        Program after(Program p) {
            List<Program> l = programs;
            int i = l.indexOf(p);
            return i >= 0 && i + 1 < l.size() ? l.get(i + 1) : null;
        }
    }

    static final class Category {
        final String id, name;

        Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final SharedPreferences sp;
    private final File dir;
    private Session session;

    PlutoApi(Context c) {
        sp = c.getSharedPreferences("pluto", Context.MODE_PRIVATE);
        dir = new File(c.getCacheDir(), "pluto");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    File dir() {
        return dir;
    }

    String lastChannel() {
        return sp.getString("lastChannel", "");
    }

    void setLastChannel(String id) {
        sp.edit().putString("lastChannel", id).apply();
    }

    boolean captions() {
        return sp.getBoolean("captions", false);
    }

    void setCaptions(boolean on) {
        sp.edit().putBoolean("captions", on).apply();
    }

    /** A per-install random id, as the site generates per browser. */
    private String clientId() {
        String id = sp.getString("clientID", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString("clientID", id).apply();
        }
        return id;
    }

    // ---------------------------------------------------------------------------------- session

    /** The current session, booting a new one when there is none, it is due for refresh, or {@code force}. */
    synchronized Session session(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && session != null && now < session.refreshAt) return session;
        if (!force && session == null) {
            String saved = sp.getString("session", null);
            if (saved != null) {
                try {
                    JSONObject o = new JSONObject(saved);
                    Session s = new Session(o.getString("t"), o.getString("s"), o.getString("p"), o.getLong("r"));
                    if (now < s.refreshAt) return session = s;
                } catch (JSONException e) {
                    Log.w(TAG, "saved session unreadable", e);
                }
            }
        }
        String url = BOOT + "?appName=" + APP_NAME + "&appVersion=" + APP_VERSION + "&deviceVersion=" + DEVICE_VERSION
                + "&deviceModel=web&deviceMake=chrome&deviceType=web&clientID=" + enc(clientId())
                + "&clientModelNumber=1.0.0&serverSideAds=false";
        try {
            JSONObject o = new JSONObject(new String(get(url, null), StandardCharsets.UTF_8));
            String stitcher = o.getJSONObject("servers").getString("stitcher");
            long refreshSec = o.optLong("refreshInSec", 28800);
            // refresh a little early, and never later than 8 h (the token itself lasts 24 h)
            long refreshAt = now + Math.min(refreshSec, 28800) * 900L;
            Session s = new Session(o.getString("sessionToken"), stitcher, o.getString("stitcherParams"), refreshAt);
            sp.edit().putString("session", new JSONObject().put("t", s.token).put("s", s.stitcher).put("p", s.params)
                    .put("r", s.refreshAt).toString()).apply();
            return session = s;
        } catch (JSONException e) {
            throw new IOException("boot response: " + e.getMessage(), e);
        }
    }

    /** Channels whose name contains {@code q} (cached list, else fetched), as tiles for the all-services search. */
    public static java.util.List<com.goomero.cleartv.tv.Item> searchChannels(Context c, String q) throws IOException {
        PlutoApi api = new PlutoApi(c);
        List<Channel> ch = api.cachedChannels();
        if (ch == null || ch.isEmpty()) ch = api.fetchChannels();
        String needle = q.toLowerCase(java.util.Locale.ROOT);
        java.util.List<com.goomero.cleartv.tv.Item> out = new java.util.ArrayList<>();
        for (Channel x : ch) {
            if (!x.name.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            com.goomero.cleartv.tv.Item it = new com.goomero.cleartv.tv.Item(com.goomero.cleartv.tv.Item.CHANNEL, x.id,
                    x.name, "Live · Ch " + x.number, x.logo, "LIVE", "");
            it.service = "pluto";
            out.add(it);
            if (out.size() >= 20) break;
        }
        return out;
    }

    /**
     * Health probe (background thread): the channel list (browse), a fresh session and the first channel's stream
     * manifest. Returns the failed step, or null when everything answered.
     */
    public static String probe(Context c) {
        PlutoApi api = new PlutoApi(c);
        List<Channel> ch;
        try {
            ch = api.fetchChannels();
            if (ch.isEmpty()) return "browse: no channels";
        } catch (IOException e) {
            return "browse: " + e.getMessage();
        }
        try {
            String m = new String(get(streamUrl(api.session(true), ch.get(0).id), null), StandardCharsets.UTF_8);
            if (!m.startsWith("#EXTM3U")) return "stream: not a playlist";
        } catch (IOException e) {
            return "stream: " + e.getMessage();
        }
        return null;
    }

    /** Live HLS master playlist of a channel (the /v2 stitcher path with the session token is required). */
    static String streamUrl(Session s, String channelId) {
        return s.stitcher + "/v2/stitch/hls/channel/" + channelId + "/master.m3u8?" + s.params + "&jwt=" + s.token
                + "&masterJWTPassthrough=true";
    }

    // --------------------------------------------------------------------------------- channels

    /** Channels from the cache file, or null when there is none. */
    List<Channel> cachedChannels() {
        File f = new File(dir, "channels.json");
        if (!f.exists()) return null;
        try {
            JSONArray a = new JSONArray(readFile(f));
            ArrayList<Channel> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                JSONArray c = a.getJSONArray(i);
                JSONArray cats = c.getJSONArray(4);
                String[] ids = new String[cats.length()];
                for (int k = 0; k < ids.length; k++) ids[k] = cats.getString(k);
                out.add(new Channel(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), ids));
            }
            return out;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "channel cache unreadable", e);
            return null;
        }
    }

    boolean channelsFresh() {
        File f = new File(dir, "channels.json");
        return f.exists() && System.currentTimeMillis() - f.lastModified() < CHANNELS_TTL_MS;
    }

    /** Downloads the channel list (about 90 KB gzipped), streams out the few fields we use and caches them. */
    List<Channel> fetchChannels() throws IOException {
        byte[] body = authed(CHANNELS + "channels?channelIds=&offset=0&limit=1000&sort=number%3Aasc");
        ArrayList<Channel> out = new ArrayList<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(new java.io.ByteArrayInputStream(body), StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!r.nextName().equals("data")) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext()) {
                    Channel c = readChannel(r);
                    if (c != null) out.add(c);
                }
                r.endArray();
            }
            r.endObject();
        } catch (IllegalStateException e) {
            throw new IOException("channels response: " + e.getMessage(), e);
        }
        if (out.isEmpty()) throw new IOException("Pluto returned no channels");
        JSONArray a = new JSONArray();
        for (Channel c : out) {
            JSONArray cats = new JSONArray();
            for (String id : c.categories) cats.put(id);
            a.put(new JSONArray().put(c.id).put(c.number).put(c.name).put(c.logo).put(cats));
        }
        writeFile(new File(dir, "channels.json"), a.toString());
        return out;
    }

    private static Channel readChannel(JsonReader r) throws IOException {
        String id = null, name = "", logo = "";
        int number = 0;
        boolean stitched = true;
        ArrayList<String> cats = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "id": id = r.nextString(); break;
                case "name": name = r.nextString(); break;
                case "number": number = r.nextInt(); break;
                case "isStitched": stitched = r.nextBoolean(); break;
                case "categoryIDs":
                    r.beginArray();
                    while (r.hasNext()) cats.add(r.nextString());
                    r.endArray();
                    break;
                case "images":
                    r.beginArray();
                    while (r.hasNext()) {
                        String type = null, url = null;
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if (k.equals("type") && r.peek() == JsonToken.STRING) type = r.nextString();
                            else if (k.equals("url") && r.peek() == JsonToken.STRING) url = r.nextString();
                            else r.skipValue();
                        }
                        r.endObject();
                        if ("solidLogoPNG".equals(type) && url != null) logo = url;
                    }
                    r.endArray();
                    break;
                default: r.skipValue();
            }
        }
        r.endObject();
        return id == null || !stitched ? null : new Channel(id, number, name, logo, cats.toArray(new String[0]));
    }

    List<Category> cachedCategories() {
        File f = new File(dir, "categories.json");
        if (!f.exists()) return null;
        try {
            JSONArray a = new JSONArray(readFile(f));
            ArrayList<Category> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(new Category(a.getJSONArray(i).getString(0), a.getJSONArray(i).getString(1)));
            return out;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    List<Category> fetchCategories() throws IOException {
        try {
            JSONArray data = new JSONObject(new String(authed(CHANNELS + "categories"), StandardCharsets.UTF_8)).getJSONArray("data");
            ArrayList<Category> out = new ArrayList<>();
            JSONArray slim = new JSONArray();
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.getJSONObject(i);
                out.add(new Category(o.getString("id"), o.getString("name")));
                slim.put(new JSONArray().put(o.getString("id")).put(o.getString("name")));
            }
            writeFile(new File(dir, "categories.json"), slim.toString());
            return out;
        } catch (JSONException e) {
            throw new IOException("categories response: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------------- timelines

    /** Guide window saved by the last fetch (channel id to programs), or an empty map. */
    Map<String, List<Program>> cachedTimelines() {
        HashMap<String, List<Program>> out = new HashMap<>();
        File f = new File(dir, "timelines.json");
        if (!f.exists()) return out;
        long now = System.currentTimeMillis();
        try {
            JSONObject o = new JSONObject(readFile(f));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONArray a = o.getJSONArray(id);
                ArrayList<Program> l = new ArrayList<>(a.length());
                for (int i = 0; i < a.length(); i++) {
                    JSONArray p = a.getJSONArray(i);
                    if (p.getLong(1) <= now) continue;
                    l.add(new Program(p.getLong(0), p.getLong(1), p.getString(2), p.getString(3), p.getString(4),
                            p.getString(5), p.getString(6)));
                }
                out.put(id, l);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "timeline cache unreadable", e);
        }
        return out;
    }

    /**
     * Fetches the guide for {@code minutes} from {@code startMs} in batches of 100 channels; each finished batch is
     * handed to {@code sink} (on this thread) so the guide fills in progressively. The whole window is saved.
     */
    void fetchTimelines(List<String> ids, long startMs, int minutes, TimelineSink sink) throws IOException {
        HashMap<String, List<Program>> all = new HashMap<>();
        SimpleDateFormat iso = isoFormat();
        String start = iso.format(new Date(startMs));
        for (int i = 0; i < ids.size(); i += TIMELINE_BATCH) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + TIMELINE_BATCH));
            StringBuilder q = new StringBuilder();
            for (String id : batch) q.append(q.length() == 0 ? "" : ",").append(id);
            byte[] body = authed(CHANNELS + "timelines?start=" + enc(start) + "&channelIds=" + enc(q.toString())
                    + "&duration=" + minutes);
            Map<String, List<Program>> got = parseTimelines(body, iso);
            all.putAll(got);
            sink.onBatch(got);
        }
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, List<Program>> e : all.entrySet()) {
                JSONArray a = new JSONArray();
                for (Program p : e.getValue()) {
                    a.put(new JSONArray().put(p.start).put(p.stop).put(p.title).put(p.episode).put(p.description)
                            .put(p.rating).put(p.genre));
                }
                o.put(e.getKey(), a);
            }
            writeFile(new File(dir, "timelines.json"), o.toString());
        } catch (JSONException e) {
            Log.w(TAG, "timeline cache not written", e);
        }
    }

    interface TimelineSink {
        void onBatch(Map<String, List<Program>> programs);
    }

    private static Map<String, List<Program>> parseTimelines(byte[] body, SimpleDateFormat iso) throws IOException {
        HashMap<String, List<Program>> out = new HashMap<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(new java.io.ByteArrayInputStream(body), StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!r.nextName().equals("data")) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext()) {
                    String id = null;
                    ArrayList<Program> list = new ArrayList<>();
                    r.beginObject();
                    while (r.hasNext()) {
                        String k = r.nextName();
                        if (k.equals("channelId")) id = r.nextString();
                        else if (k.equals("timelines")) {
                            r.beginArray();
                            while (r.hasNext()) {
                                Program p = readProgram(r, iso);
                                if (p != null) list.add(p);
                            }
                            r.endArray();
                        } else r.skipValue();
                    }
                    r.endObject();
                    if (id != null) out.put(id, list);
                }
                r.endArray();
            }
            r.endObject();
        } catch (IllegalStateException e) {
            throw new IOException("timelines response: " + e.getMessage(), e);
        }
        return out;
    }

    private static Program readProgram(JsonReader r, SimpleDateFormat iso) throws IOException {
        String start = null, stop = null, title = "", episode = "", desc = "", rating = "", genre = "";
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (k) {
                case "start": start = r.nextString(); break;
                case "stop": stop = r.nextString(); break;
                case "title": title = r.nextString(); break;
                case "episode":
                    r.beginObject();
                    while (r.hasNext()) {
                        String e = r.nextName();
                        if (r.peek() != JsonToken.STRING) {
                            r.skipValue();
                            continue;
                        }
                        switch (e) {
                            case "name": episode = r.nextString(); break;
                            case "description": desc = r.nextString(); break;
                            case "rating": rating = r.nextString(); break;
                            case "genre": genre = r.nextString(); break;
                            default: r.skipValue();
                        }
                    }
                    r.endObject();
                    break;
                default: r.skipValue();
            }
        }
        r.endObject();
        if (start == null || stop == null) return null;
        try {
            return new Program(iso.parse(start).getTime(), iso.parse(stop).getTime(), title,
                    episode.equals(title) ? "" : episode, desc, rating, genre);
        } catch (ParseException e) {
            return null;
        }
    }

    private static SimpleDateFormat isoFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    // -------------------------------------------------------------------------------------- HTTP

    /** GET with the session token; a 401/403 boots a new session and retries once. */
    private byte[] authed(String url) throws IOException {
        try {
            return get(url, session(false).token);
        } catch (HttpError e) {
            if (e.code != 401 && e.code != 403) throw e;
            return get(url, session(true).token);
        }
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String url) {
            super("HTTP " + code + " for " + url.replaceAll("\\?.*", ""));
            this.code = code;
        }
    }

    static byte[] get(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("Origin", "https://pluto.tv");
        c.setRequestProperty("Referer", "https://pluto.tv/");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code, url);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            try (InputStream s = in) {
                return readAll(s);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static String readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    /** Writes via a temporary file and rename, so a crash never leaves a half-written cache. */
    private static void writeFile(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "cache write failed: " + f.getName(), e);
            return;
        }
        if (!tmp.renameTo(f)) Log.w(TAG, "cache rename failed: " + f.getName());
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
