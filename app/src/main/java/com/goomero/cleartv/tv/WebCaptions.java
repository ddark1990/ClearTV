package com.goomero.cleartv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ClearTV's own captions for the web players, whose caption UI is hidden with the rest of their page.
 * <ul>
 * <li>YouTube: the embed player's caption tracks; the player renders the chosen track in its (hidden) caption
 * layer and the embed frame reports each line, which is drawn here.</li>
 * <li>Prime Video: the subtitle files listed in the page's playback resources (TTML), fetched and timed
 * against the video here.</li>
 * </ul>
 * The chosen language is remembered per service; the CC chip in the controls, the options panel and the
 * remote's CAPTIONS key change it.
 */
final class WebCaptions {
    private static final String TAG = "CTV.CC";
    interface Host {
        void js(String code);

        /** The video position now, in ms. */
        long positionMs();
    }

    private static final class Track {
        final String id, label, url;

        Track(String id, String label, String url) {
            this.id = id;
            this.label = label;
            this.url = url;
        }
    }

    private final PlayerChrome chrome;
    private final Host host;
    private final boolean youtube;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Track> tracks = new ArrayList<>();
    private int index = -1, last = -1;
    /** Prime Video: the chosen file's cues, sorted by start. */
    private long[] start, end;
    private String[] text;
    private int loadSeq;

    WebCaptions(Context c, String serviceId, boolean youtube, PlayerChrome chrome, Host host) {
        this.chrome = chrome;
        this.host = host;
        this.youtube = youtube;
        prefs = c.getSharedPreferences("svc_" + serviceId, Context.MODE_PRIVATE);
    }

    boolean available() {
        return !tracks.isEmpty();
    }

    /** The page's track list: [{id, label, url?}]. */
    void onTracks(String json) {
        tracks.clear();
        try {
            JSONArray a = new JSONArray(json);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id"), label = o.optString("label", id), url = o.optString("url", "");
                if (id.isEmpty() || !seen.add(id) || (!youtube && url.isEmpty())) continue;
                tracks.add(new Track(id, label.isEmpty() ? id : label, url));
            }
        } catch (JSONException e) {
            Log.w(TAG, "track list unreadable");
        }
        Log.i(TAG, tracks.size() + " caption tracks");
        index = -1;
        if (tracks.isEmpty()) {
            chrome.setCaptions(null, null);
            return;
        }
        String want = prefs.getString("cc_track", "");
        int pick = -1;
        for (int i = 0; i < tracks.size() && !want.isEmpty(); i++) if (tracks.get(i).id.equals(want)) pick = i;
        select(pick, false);
    }

    /** One caption line from the YouTube embed frame ("" between lines). */
    void onLine(String line) {
        if (youtube && index >= 0) chrome.showCaption(line);
    }

    String label() {
        return index >= 0 ? tracks.get(index).label : "";
    }

    void toggle() {
        if (tracks.isEmpty()) return;
        if (index >= 0) {
            select(-1, true);
            return;
        }
        int pick = last >= 0 ? last : 0;
        if (last < 0) {
            String lang = Locale.getDefault().getLanguage();
            for (int i = tracks.size() - 1; i >= 0; i--) if (tracks.get(i).id.startsWith(lang)) pick = i;
        }
        select(pick, true);
    }

    String[] rows() {
        String[] r = new String[tracks.size() + 1];
        r[0] = (index < 0 ? "● " : "") + "Off";
        for (int i = 0; i < tracks.size(); i++) r[i + 1] = (i == index ? "● " : "") + tracks.get(i).label;
        return r;
    }

    /** Row {@code row} of {@link #rows()}: 0 is off. */
    void pick(int row) {
        select(row - 1, true);
    }

    private void select(int i, boolean save) {
        index = i >= 0 && i < tracks.size() ? i : -1;
        if (index >= 0) last = index;
        if (save) prefs.edit().putString("cc_track", index >= 0 ? tracks.get(index).id : "").apply();
        chrome.showCaption(null);
        chrome.setCaptions(label(), new Runnable() {
            @Override
            public void run() {
                toggle();
            }
        });
        main.removeCallbacks(tick);
        start = null;
        if (youtube) {
            host.js("typeof ctvCc==='function'&&ctvCc(" + index + ")");
        } else if (index >= 0) {
            load(tracks.get(index).url);
        }
    }

    private void load(final String url) {
        final int seq = ++loadSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<long[]> times = new ArrayList<>();
                final List<String> lines = new ArrayList<>();
                try {
                    String body = new String(Http.get(url, null), StandardCharsets.UTF_8);
                    if (body.startsWith("WEBVTT")) parseVtt(body, times, lines);
                    else parseTtml(body, times, lines);
                } catch (IOException | XmlPullParserException | RuntimeException e) {
                    Log.w(TAG, "captions not loaded: " + e.getClass().getSimpleName());
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq) return;
                        Log.i(TAG, lines.size() + " cues");
                        int n = lines.size();
                        Integer[] order = new Integer[n];
                        for (int k = 0; k < n; k++) order[k] = k;
                        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                            @Override
                            public int compare(Integer a, Integer b) {
                                return Long.compare(times.get(a)[0], times.get(b)[0]);
                            }
                        });
                        start = new long[n];
                        end = new long[n];
                        text = new String[n];
                        for (int k = 0; k < n; k++) {
                            start[k] = times.get(order[k])[0];
                            end[k] = times.get(order[k])[1];
                            text[k] = lines.get(order[k]);
                        }
                        main.post(tick);
                    }
                });
            }
        }, "captions").start();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (start == null || index < 0) return;
            long pos = host.positionMs();
            StringBuilder sb = new StringBuilder();
            // the last cue starting at or before pos, and any overlapping ones before it
            int lo = 0, hi = start.length - 1, at = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (start[mid] <= pos) {
                    at = mid;
                    lo = mid + 1;
                } else hi = mid - 1;
            }
            for (int k = at; k >= 0 && k > at - 4; k--) {
                if (end[k] > pos) sb.insert(0, sb.length() > 0 ? text[k] + "\n" : text[k]);
            }
            chrome.showCaption(sb);
            main.postDelayed(this, 200);
        }
    };

    void release() {
        loadSeq++;
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------------------------ caption files

    static void parseTtml(String xml, List<long[]> times, List<String> lines) throws XmlPullParserException, IOException {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));
        double tickRate = 10_000_000, frameRate = 30;
        StringBuilder sb = null;
        long b = 0, e = 0;
        int depth = 0;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String name = local(p.getName());
                if (name.equals("tt")) {
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        String an = local(p.getAttributeName(i));
                        if (an.equals("tickRate")) tickRate = parseD(p.getAttributeValue(i), tickRate);
                        if (an.equals("frameRate")) frameRate = parseD(p.getAttributeValue(i), frameRate);
                    }
                } else if (name.equals("p") && sb == null) {
                    b = time(attr(p, "begin"), tickRate, frameRate);
                    String en = attr(p, "end"), du = attr(p, "dur");
                    e = en != null ? time(en, tickRate, frameRate) : du != null ? b + time(du, tickRate, frameRate) : b + 3000;
                    sb = new StringBuilder();
                    depth = p.getDepth();
                } else if (name.equals("br") && sb != null) {
                    sb.append('\n');
                }
            } else if (ev == XmlPullParser.TEXT && sb != null) {
                sb.append(p.getText().replaceAll("\\s+", " "));
            } else if (ev == XmlPullParser.END_TAG && sb != null && local(p.getName()).equals("p") && p.getDepth() == depth) {
                String t = trimLines(sb.toString());
                if (!t.isEmpty() && e > b) {
                    times.add(new long[]{b, e});
                    lines.add(t);
                }
                sb = null;
            }
        }
    }

    static void parseVtt(String vtt, List<long[]> times, List<String> lines) {
        String[] blocks = vtt.replace("\r", "").split("\n\n+");
        for (String block : blocks) {
            String[] l = block.split("\n");
            for (int i = 0; i < l.length; i++) {
                int arrow = l[i].indexOf("-->");
                if (arrow < 0) continue;
                long b = time(l[i].substring(0, arrow).trim(), 0, 0);
                String rest = l[i].substring(arrow + 3).trim();
                int sp = rest.indexOf(' ');
                long e = time(sp > 0 ? rest.substring(0, sp) : rest, 0, 0);
                StringBuilder sb = new StringBuilder();
                for (int k = i + 1; k < l.length; k++) sb.append(sb.length() > 0 ? "\n" : "").append(l[k].replaceAll("<[^<>]*>", ""));
                if (sb.length() > 0 && e > b) {
                    times.add(new long[]{b, e});
                    lines.add(sb.toString().trim());
                }
                break;
            }
        }
    }

    private static String local(String n) {
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) if (local(p.getAttributeName(i)).equals(name)) return p.getAttributeValue(i);
        return null;
    }

    private static double parseD(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return def;
        }
    }

    /** TTML / WebVTT time expressions: 00:01:02.345, 00:01:02:12 (frames), 12345t, 1.5s, 200ms, 1.2h. */
    static long time(String s, double tickRate, double frameRate) {
        if (s == null) return 0;
        s = s.trim();
        try {
            if (s.endsWith("ms")) return (long) Double.parseDouble(s.substring(0, s.length() - 2));
            if (s.endsWith("t")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000 / (tickRate > 0 ? tickRate : 10_000_000));
            if (s.endsWith("s")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
            if (s.endsWith("m")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 60_000);
            if (s.endsWith("h")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 3_600_000);
            String[] p = s.split(":");
            double ms = 0;
            if (p.length == 4) {
                ms = (Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2])) * 1000
                        + Double.parseDouble(p[3]) * 1000 / (frameRate > 0 ? frameRate : 30);
            } else if (p.length == 3) {
                ms = (Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2])) * 1000;
            } else if (p.length == 2) {
                ms = (Integer.parseInt(p[0]) * 60 + Double.parseDouble(p[1])) * 1000;
            } else {
                ms = Double.parseDouble(s) * 1000;
            }
            return (long) ms;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Trims spaces around every line (no backtracking regex). */
    private static String trimLines(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            int a = 0, e = line.length();
            while (a < e && line.charAt(a) == ' ') a++;
            while (e > a && line.charAt(e - 1) == ' ') e--;
            if (b.length() > 0) b.append('\n');
            b.append(line, a, e);
        }
        return b.toString().trim();
    }
}
