package com.goomero.cleartv.tv;

import android.app.Activity;
import android.content.Intent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts playback of a tile the same way from every screen (service pages, home rows, the next-episode countdown):
 * resolves the stream on a background thread, then opens ClearTV's player for clear HLS or the in-app web player
 * for DRM titles. The tile, the episodes after it (for auto-play next), the saved position and the intro/credits
 * markers travel with the intent.
 */
public final class Launcher {
    public static final String EXTRA_ITEM = "ctvItem", EXTRA_QUEUE = "ctvQueue", EXTRA_RESUME = "ctvResume",
            EXTRA_MARKERS = "ctvMarkers";
    private static final String TAG = "CTV.Launch";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Launcher() {}

    private static final Runnable CLEAR_PENDING = new Runnable() {
        @Override
        public void run() {
            pending = 0;
        }
    };

    /** When the last play request started (uptime), so a second OK while the stream resolves is ignored. */
    private static long pending;

    public static void play(final Activity a, final Source source, final Item it, final List<Item> queue, final long pressedAt) {
        play(a, source, it, queue, pressedAt, false);
    }

    /** {@code fromStart}: the viewer chose Start over, so any saved position is ignored. */
    public static void play(final Activity a, final Source source, final Item it, final List<Item> queue, final long pressedAt,
                            final boolean fromStart) {
        long now = android.os.SystemClock.uptimeMillis();
        // a double press (or OK held a moment too long) used to open two players on top of each other
        if (now - pending < 4000) {
            Log.i(TAG, "play " + it.id + " ignored: a title is already starting");
            return;
        }
        pending = now;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    StartTrace.begin(source.id(), pressedAt);
                    final Source.Playback p = source.play(it);
                    StartTrace.mark("stream resolved (" + (p.hls != null ? "own player" : "web player") + ")");
                    final long resume = fromStart ? 0 : p.resumeMs > 0 ? p.resumeMs : Library.resumeAt(a, source.id(), it.id);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (a.isFinishing() || a.isDestroyed()) return;
                            Intent i;
                            if (p.hls != null) {
                                i = PlayerActivity.intent(a, source, p);
                                i.putExtra(PlayerActivity.EXTRA_PRESSED, pressedAt);
                            } else if (p.webUrl != null) {
                                i = WebActivity.player(a, p.webUrl, p.webTvUa, webDomains(source), com.goomero.cleartv.Ui.serviceAccent(source.accent()),
                                        source.name(), source.id(), p.title, p.subtitle, pressedAt);
                            } else {
                                return;
                            }
                            i.putExtra(EXTRA_ITEM, Library.encode(it));
                            i.putExtra(EXTRA_QUEUE, encodeQueue(queue));
                            i.putExtra(EXTRA_RESUME, p.live ? 0 : resume);
                            i.putExtra(EXTRA_MARKERS, new long[]{p.introStartMs, p.introEndMs, p.creditsMs});
                            a.startActivity(i);
                            // the player is up; the next request (BACK and play again) starts at once
                            MAIN.postDelayed(CLEAR_PENDING, 1000);
                        }
                    });
                } catch (final IOException e) {
                    pending = 0;
                    Log.w(TAG, "play " + it.id + ": " + e.getMessage());
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(a, e.getMessage() == null ? "Could not start playback." : e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    /**
     * Connects a player's shared overlay to the library and the queue: progress is saved every 15 s and on exit,
     * the next episode is offered with a countdown, intro markers enable Skip intro. Returns the position to resume
     * at (0 for the start).
     */
    public static long attach(final Activity a, final PlayerChrome chrome, Intent in, final String serviceId) {
        final Item item = Library.decode(in.getStringExtra(EXTRA_ITEM));
        final List<Item> queue = decodeQueue(in.getStringExtra(EXTRA_QUEUE));
        long[] m = in.getLongArrayExtra(EXTRA_MARKERS);
        if (m != null && m.length == 3) chrome.setMarkers(m[0], m[1], m[2]);
        if (item != null && serviceId != null && !serviceId.isEmpty()) {
            chrome.setProgressSink(new Runnable() {
                @Override
                public void run() {
                    PlayerChrome.Target t = chrome.target();
                    if (t.isLive()) return;
                    final long pos = t.positionMs(), dur = t.durationMs();
                    IO.execute(new Runnable() {
                        @Override
                        public void run() {
                            Library.record(a, serviceId, item, pos, dur);
                        }
                    });
                }
            });
        }
        if (!queue.isEmpty() && serviceId != null && !serviceId.isEmpty()) {
            final Item next = queue.get(0);
            chrome.setNext(next.title, new Runnable() {
                @Override
                public void run() {
                    Source s;
                    try {
                        s = Source.create(a, serviceId);
                    } catch (IllegalArgumentException e) {
                        a.finish();
                        return;
                    }
                    play(a, s, next, queue.subList(1, queue.size()), android.os.SystemClock.uptimeMillis(), true);
                    // the next player starts on top; this one leaves once it is up
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            a.finish();
                        }
                    }, 1500);
                }
            });
        }
        if (item != null && serviceId != null && !serviceId.isEmpty() && !serviceId.equals("all")) {
            try {
                if (Source.create(a, serviceId).hasSimilar(item)) {
                    chrome.setMoreLike(new Runnable() {
                        @Override
                        public void run() {
                            chrome.saveProgress();
                            ServiceActivity.openSimilar(a, serviceId, item);
                            a.finish();
                        }
                    });
                }
            } catch (IllegalArgumentException ignored) {
                // a service without a native screen (Pluto TV) has no similar titles here
            }
        }
        loadChapters(a, chrome, item, serviceId);
        return in.getLongExtra(EXTRA_RESUME, 0);
    }

    /**
     * The title's chapters, asked of its service in the background once the player is up (playback never waits
     * for them); the chapter bar and Skip sponsor appear when they arrive. Services without chapters answer null.
     */
    private static void loadChapters(final Activity a, final PlayerChrome chrome, final Item item, final String serviceId) {
        if (item == null || item.kind == Item.LIVE || serviceId == null || serviceId.isEmpty()) return;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final ChapterList list;
                try {
                    list = Source.create(a, serviceId).chapters(item);
                } catch (IllegalArgumentException | IOException e) {
                    Log.i(TAG, "chapters " + item.id + ": " + e.getMessage());
                    return;
                }
                Log.i(TAG, "chapters " + item.id + ": " + (list == null ? "none" : list.toString()));
                if (list == null) return;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!a.isFinishing() && !a.isDestroyed()) chrome.setChapters(list);
                    }
                });
            }
        });
    }

    /** "23:14" or "1:02:05". */
    public static String clock(long ms) {
        long t = ms / 1000, h = t / 3600, m = t / 60 % 60, sec = t % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }

    /** The episodes after {@code it} in its row (at most 25), for auto-play next. */
    public static List<Item> after(Item.Shelf shelf, Item it) {
        List<Item> out = new ArrayList<>();
        if (shelf == null) return out;
        int i = shelf.items.indexOf(it);
        for (int k = 0; i < 0 && k < shelf.items.size(); k++) if (shelf.items.get(k).id.equals(it.id)) i = k;
        if (i < 0) return out;
        for (int j = i + 1; j < shelf.items.size() && out.size() < 25; j++) {
            Item n = shelf.items.get(j);
            if (n.kind == Item.VIDEO) out.add(n);
        }
        return out;
    }

    static String encodeQueue(List<Item> q) {
        if (q == null || q.isEmpty()) return null;
        JSONArray a = new JSONArray();
        for (Item it : q) a.put(Library.toJson(it));
        return a.toString();
    }

    public static List<Item> decodeQueue(String s) {
        List<Item> out = new ArrayList<>();
        if (s == null) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(Library.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static String[] webDomains(Source source) {
        String[] d = source.webDomains();
        if (d.length > 0) return d;
        if (source.id().equals("youtube")) return new String[]{"youtube.com", "youtu.be", "google.com", "gstatic.com", "accounts.youtube.com"};
        return new String[]{"tubitv.com", "tubi.tv"};
    }
}
