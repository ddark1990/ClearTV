package com.goomero.cleartv.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Artwork for the native screens: downloaded once into cacheDir/art (pruned to 40 MB), decoded at the tile's
 * size in RGB_565 and kept in a 10 MB memory cache. Newest requests load first (LIFO), so scrolling fast fetches
 * what is on screen rather than everything scrolled past.
 */
public final class Images {
    /**
     * The largest variant of an artwork URL worth trying for a full-screen backdrop, best first (the original URL is
     * always last). YouTube: maxresdefault, then sddefault, then hqdefault. Amazon: the size suffix removed. Others:
     * a width query parameter raised to 1920.
     */
    public static java.util.List<String> largest(String url) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (url == null || url.isEmpty()) return out;
        java.util.regex.Matcher yt = java.util.regex.Pattern.compile("^(https://i\\d?\\.ytimg\\.com/vi(?:_webp)?/[^/]+/)[a-z0-9_]+\\.(jpg|webp)(\\?.*)?$").matcher(url);
        if (yt.matches()) {
            out.add(yt.group(1).replace("/vi_webp/", "/vi/") + "maxresdefault.jpg");
            out.add(yt.group(1).replace("/vi_webp/", "/vi/") + "sddefault.jpg");
            out.add(yt.group(1).replace("/vi_webp/", "/vi/") + "hqdefault.jpg");
        } else if (url.contains("media-amazon.com/images/") || url.contains("ssl-images-amazon.com/images/")) {
            String big = url.replaceAll("\\._[^/]*_\\.(jpg|jpeg|png|webp)$", ".$1");
            if (!big.equals(url)) out.add(big);
        } else if (url.matches(".*[?&](w|width)=\\d+.*")) {
            out.add(url.replaceAll("([?&])(w|width)=\\d+", "$1$2=1920").replaceAll("([?&])(h|height)=\\d+&?", "$1").replaceAll("[?&]$", ""));
        }
        if (!out.contains(url)) out.add(url);
        return out;
    }

    private static final String TAG = "CTV.Art";
    private static final long DISK_MAX = 40L << 20;
    private final File dir;
    private final int targetW;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final ExecutorService pool = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);
                }
            });
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    public Images(Context c, int targetWidthPx, Runnable onLoaded) {
        dir = new File(c.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        targetW = targetWidthPx;
        this.onLoaded = onLoaded;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                prune();
            }
        });
    }

    /** The URL could not be loaded (missing, not an image); callers can fall back to another variant. */
    public boolean failed(String url) {
        return failed.contains(url);
    }

    /** Decode from the disk cache on the calling thread when the file is already there (offscreen previews only). */
    public boolean diskSync;

    /** The bitmap when it is in memory; otherwise null and a load is queued (main thread). */
    public Bitmap get(final String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = cache.get(url);
        if (b == null && diskSync && new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length()).exists()) {
            b = load(url);
            if (b != null) cache.put(url, b);
        }
        if (b != null || failed.contains(url) || !pending.add(url)) return b;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap got = load(url);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        pending.remove(url);
                        if (got == null) {
                            failed.add(url);
                            onLoaded.run(); // lets callers try their next variant
                            return;
                        }
                        cache.put(url, got);
                        onLoaded.run();
                    }
                });
            }
        });
        return null;
    }

    /** Downloads artwork into the disk cache ahead of time (background thread); the next screen decodes it locally. */
    public static void prefetch(Context c, String url) {
        if (url == null || url.isEmpty()) return;
        File d = new File(c.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File f = new File(d, Integer.toHexString(url.hashCode()) + "_" + url.length());
        if (f.exists()) return;
        try {
            byte[] data = Http.get(url, null);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(data);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (IOException e) {
            Log.w(TAG, "prefetch " + Http.shortUrl(url) + ": " + e.getMessage());
        }
    }

    private Bitmap load(String url) {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
        if (!f.exists()) {
            try {
                byte[] data = Http.get(url, null);
                File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(data);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            } catch (IOException e) {
                Log.w(TAG, Http.shortUrl(url) + ": " + e.getMessage());
                return null;
            }
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_MAX) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= DISK_MAX * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public void trim() {
        cache.evictAll();
    }

    public void shutdown() {
        pool.shutdownNow();
        cache.evictAll();
    }
}
