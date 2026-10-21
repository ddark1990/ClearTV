package com.goomero.cleartv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;

import com.goomero.cleartv.tv.HealthCheck;
import com.goomero.cleartv.tv.Images;
import com.goomero.cleartv.tv.Item;
import com.goomero.cleartv.tv.Library;
import com.goomero.cleartv.tv.PageCache;
import com.goomero.cleartv.tv.Source;

import java.util.List;

/**
 * Work that waits until home is on screen, on one background thread at low priority: silent session renewal, the last-used service's first rows and artwork (so it opens instantly), the services'
 * own history rows for Continue watching, and the 12-hourly health probes. Nothing here holds memory afterwards.
 */
final class Startup {
    private static final String TAG = "CTV.Startup";
    private static final long DELAY_MS = 2500;
    private static final long HISTORY_MS = 3600_000L;
    private static boolean ran;

    interface Done {
        void onHomeDataChanged();
    }

    private Startup() {}

    static void afterFirstFrame(final Activity a, View root) {
        if (ran) return;
        ran = true;
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        work(a);
                    }
                }, "startup");
                t.start();
            }
        }, DELAY_MS);
    }

    private static void work(final Activity a) {
        final android.content.Context c = a.getApplicationContext();
        long t0 = android.os.SystemClock.uptimeMillis();
        String last = ((App) c).prefs.lastService();
        boolean changed = false;
        for (String id : new String[]{"youtube", "tubi", "prime"}) {
            Source s = Source.create(c, id);
            try {
                s.refreshSession();
            } catch (Exception e) {
                Log.w(TAG, id + " session: " + e.getMessage());
            }
            try {
                if (id.equals(last)) {
                    // the service opened last time: its first rows and their artwork, ready on disk
                    Item.Page p = s.load(s.navs().get(0));
                    PageCache.put(c, id, p);
                    for (String url : PageCache.firstImages(p, 10)) Images.prefetch(c, url);
                }
                if (s.signedIn() && Library.historyAge(c, id) > HISTORY_MS) {
                    List<Item> h = s.history();
                    Library.putHistory(c, id, h);
                    try {
                        Library.putWatchlist(c, id, s.watchlist());
                    } catch (Exception e) {
                        Log.w(TAG, id + " watchlist: " + e.getMessage());
                    }
                    changed = true;
                } else if (!s.signedIn()) {
                    Library.clearHistory(c, id);
                }
            } catch (Exception e) {
                Log.w(TAG, id + ": " + e.getMessage());
            }
        }
        if (HealthCheck.runIfDue(c, false)) changed = true;
        Log.i(TAG, "background start-up work " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
        if (changed && a instanceof Done) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (!a.isFinishing()) ((Done) a).onHomeDataChanged();
                }
            });
        }
    }
}
