package com.goomero.cleartv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quick probes of each service's key endpoints (browse, search, title page, stream manifest, sign-in code), run
 * by ClearTV itself in the background at most every 12 hours. A failure is shown, quietly, on the service's home
 * tile and in Settings > Services status, with the time of the check. The fix for a changed endpoint ships in the
 * next app version (the settings are built into the app).
 */
public final class HealthCheck {
    private static final String TAG = "CTV.Health";
    private static final long EVERY_MS = 12 * 3600_000L;
    public static final String[] SERVICES = {"prime", "youtube", "pluto", "tubi"};

    /** One service's last result. */
    public static final class Status {
        public final String service, failure;
        public final long at;

        Status(String service, String failure, long at) {
            this.service = service;
            this.failure = failure;
            this.at = at;
        }

        public boolean ok() {
            return failure == null || failure.isEmpty();
        }
    }

    private HealthCheck() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("health", Context.MODE_PRIVATE);
    }

    public static Status status(Context c, String service) {
        SharedPreferences p = prefs(c);
        return new Status(service, p.getString("fail_" + service, null), p.getLong("at", 0));
    }

    public static long lastRun(Context c) {
        return prefs(c).getLong("at", 0);
    }

    public static boolean due(Context c) {
        return System.currentTimeMillis() - lastRun(c) >= EVERY_MS;
    }

    /** Runs the probes when due (background thread). Returns true when results changed. */
    public static boolean runIfDue(Context c, boolean force) {
        if (!force && !due(c)) return false;
        Map<String, String> fails = new HashMap<>();
        for (String s : SERVICES) {
            String f = probe(c, s);
            if (f != null) fails.put(s, f);
        }
        SharedPreferences.Editor e = prefs(c).edit();
        boolean changed = false;
        for (String s : SERVICES) {
            String before = prefs(c).getString("fail_" + s, null);
            String now = fails.get(s);
            if (now == null ? before != null : !now.equals(before)) changed = true;
            if (now == null) e.remove("fail_" + s);
            else e.putString("fail_" + s, now);
        }
        e.putLong("at", System.currentTimeMillis()).apply();
        Log.i(TAG, fails.isEmpty() ? "all services answered" : "still failing: " + fails);
        return changed;
    }

    /** The first failing step of a service ("search: 404"), or null. */
    static String probe(Context c, String service) {
        if (service.equals("pluto")) return com.goomero.cleartv.pluto.PlutoApi.probe(c);
        Source s;
        try {
            s = Source.create(c, service);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String step = "browse";
        try {
            Item.Page home = s.load(s.navs().get(0));
            if (home.isEmpty()) return "browse: no rows";
            List<Item> videos = videos(home, 4);
            if (service.equals("prime")) {
                step = "detail";
                Item d = new Item(Item.PAGE, "", "", "", "", "", Config.s("prime.probe.detail", "/detail/0J1DW15U5F8I6MR5750BWMO6Y1"));
                Item.Page p = s.openDetail(d);
                if (p.detail == null || p.detail.title.isEmpty()) return "detail: no title data";
                step = "search";
                if (s.search("comedy").isEmpty()) return "search: no results";
                return null; // Prime plays in Amazon's player; sign-in is Amazon's page
            }
            step = "search";
            Item.Page found = s.search(Config.s(service + ".probe.search", "news"));
            if (found.isEmpty()) return "search: no results";
            step = "stream";
            if (service.equals("youtube")) {
                videos.clear();
                videos.add(new Item(Item.VIDEO, Config.s("youtube.probe.video", "jNQXAC9e7gk"), "", "", "", "", ""));
            } else if (videos.size() < 4) {
                videos.addAll(videos(found, 4 - videos.size()));
            }
            if (videos.isEmpty()) return "stream: no video to try";
            // some titles only come with DRM or are briefly unavailable; the service works when any of a few plays
            String streamFail = null;
            for (Item v : videos) {
                streamFail = streamOf(s, v);
                if (streamFail == null) break;
            }
            if (streamFail != null) return "stream: " + streamFail;
            if (s.signInKind() == Source.SIGNIN_CODE && !s.signedIn()) {
                step = "sign-in code";
                Source.Code code = s.startCode();
                if (code.userCode.isEmpty()) return "sign-in code: empty";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return step + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static List<Item> videos(Item.Page p, int max) {
        List<Item> out = new ArrayList<>();
        for (Item.Shelf sh : p.shelves) for (Item it : sh.items) {
            if (it.kind == Item.VIDEO && out.size() < max) out.add(it);
        }
        return out;
    }

    /** Null when the title's stream answers (an HLS playlist, or a web player page), else why not. */
    private static String streamOf(Source s, Item v) {
        try {
            Source.Playback pb = s.play(v);
            if (pb.hls != null) {
                Map<String, String> h = pb.headers == null ? new HashMap<String, String>() : new HashMap<>(pb.headers);
                if (pb.userAgent != null) h.put("User-Agent", pb.userAgent);
                String m = new String(Http.get(pb.hls, h), StandardCharsets.UTF_8);
                return m.startsWith("#EXTM3U") ? null : "not a playlist";
            }
            return pb.webUrl == null ? "nothing to play" : null;
        } catch (IOException | RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** Failures for the tiles and Settings: service id to a short line. */
    public static List<Status> all(Context c) {
        List<Status> out = new ArrayList<>();
        for (String s : SERVICES) out.add(status(c, s));
        return out;
    }
}
