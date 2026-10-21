package com.goomero.cleartv.pluto;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Channel logos for the guide: Pluto's white "solid" logo resized by their image server to 140x40, kept on disk
 * (cacheDir/pluto/logos) and in a 3 MB memory cache. Loads run on two background threads; {@code onLoaded} runs on
 * the main thread so the guide can redraw.
 */
final class PlutoLogos {
    private static final String TAG = "CTV.Pluto";
    private final File dir;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final HashSet<String> pending = new HashSet<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    PlutoLogos(File plutoDir, Runnable onLoaded) {
        dir = new File(plutoDir, "logos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.onLoaded = onLoaded;
    }

    /** The logo if it is in memory; otherwise null and a load is queued (main thread only). */
    Bitmap get(final PlutoApi.Channel c) {
        Bitmap b = cache.get(c.id);
        if (b != null || c.logo.isEmpty() || !pending.add(c.id)) return b;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap got = load(c);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        pending.remove(c.id);
                        if (got != null) {
                            cache.put(c.id, got);
                            onLoaded.run();
                        }
                    }
                });
            }
        });
        return null;
    }

    private Bitmap load(PlutoApi.Channel c) {
        // the id comes from Pluto's API: only a plain file name, never a path out of the logo folder
        if (c.id == null || !c.id.matches("[A-Za-z0-9_-]{1,64}") || c.logo == null || !(c.logo.startsWith("https://") || c.logo.startsWith("http://"))) return null;
        File f = new File(dir, c.id + ".png");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        try {
            String url = c.logo + (c.logo.contains("?") ? "&" : "?") + "fm=png&w=140&h=40&fit=fill";
            byte[] data = PlutoApi.get(url, null);
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return b;
        } catch (IOException e) {
            Log.w(TAG, "logo " + c.id + ": " + e.getMessage());
            return null;
        }
    }

    void trim() {
        cache.evictAll();
    }

    void shutdown() {
        pool.shutdownNow();
    }
}
