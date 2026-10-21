package com.goomero.cleartv;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.goomero.cleartv.engine.FilterCompiler;
import com.goomero.cleartv.engine.FilterEngine;
import com.goomero.cleartv.engine.Hash;
import com.goomero.cleartv.engine.LongSet;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Owns the FilterEngine. The engine is served from compiled blobs that are memory-mapped, not parsed: the bundled
 * assets/filters/engine.bin (compiled at build time, stored uncompressed in the APK so it maps in place), plus,
 * after a list update, files/filters/filters.bin (compiled on the device from the downloaded lists) and
 * files/filters/domains-extra.bin (downloaded DNS lists). Opening takes milliseconds and keeps the filter data in
 * the page cache instead of the Java heap. The engine is swapped atomically after an update.
 */
public final class FilterManager {
    private static final String TAG = "CTV.Filters";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final long UPDATE_INTERVAL_MS = 4L * 24 * 3600 * 1000;
    private static final String BUNDLED = "filters/engine.bin";
    private static final String UPDATED = "filters.bin";
    private static final String EXTRA = "domains-extra.bin";

    /** Filter lists: file name, update URL, minimum line count for a download to be accepted. */
    static final String[][] LISTS = {
            {"easylist.txt", "https://easylist.to/easylist/easylist.txt", "20000"},
            {"easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt", "20000"},
            {"ubo-filters.txt", "https://ublockorigin.github.io/uAssets/filters/filters.min.txt", "5000"},
            {"ubo-privacy.txt", "https://ublockorigin.github.io/uAssets/filters/privacy.min.txt", "300"},
            {"ubo-badware.txt", "https://ublockorigin.github.io/uAssets/filters/badware.min.txt", "500"},
            {"ubo-quick-fixes.txt", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.min.txt", "50"},
            {"peter-lowe.txt", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext", "1000"},
            // cookie-consent banners: hidden (EasyList Cookie) or answered (uBO Cookie Notices scriptlets)
            {"easylist-cookie.txt", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", "5000"},
            {"ubo-cookies.txt", "https://ublockorigin.github.io/uAssets/filters/annoyances-cookies.txt", "1000"},
            // "get the app" banners and prompts (Fanboy's Mobile Notifications, part of EasyList's annoyance lists)
            {"app-banners.txt", "https://secure.fanboy.co.nz/fanboy-mobile-notifications.txt", "300"},
    };
    /** ClearTV's own rules (content allowlist), compiled after the lists. Bump the version when the file changes. */
    static final String APP_RULES = "filters/app-rules.txt";
    static final int APP_RULES_VERSION = 1;
    /** DNS-style domain sources added to the bundled domain set on update (wildcard semantics). */
    static final String[][] DOMAIN_SOURCES = {
            {"oisd-big", "https://big.oisd.nl/domainswild", "100000"},
            {"hagezi-pro.plus", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.plus-onlydomains.txt", "100000"},
            {"stevenblack", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", "50000"},
    };

    public interface Listener {
        void onEngineChanged(FilterEngine e);
    }

    private final Context ctx;
    private final Prefs prefs;
    private final File dir, listDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile FilterEngine engine;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean updating;
    private Listener listener;
    private volatile String status = "Loading filters…";

    public FilterManager(Context c, Prefs p) {
        ctx = c.getApplicationContext();
        prefs = p;
        dir = new File(ctx.getFilesDir(), "filters");
        listDir = new File(dir, "lists");
        if (!listDir.isDirectory() && !listDir.mkdirs()) Log.w(TAG, "cannot create " + listDir);
    }

    /**
     * v0.1.0 kept downloaded lists as plain text in files/filters and parsed them at every start. Moves them to the
     * compressed list store and drops its merged domains.bin (the DNS sources come back with the next update).
     * Returns true if any list was moved, so the caller compiles them once.
     */
    private boolean migrateV010() {
        boolean moved = false;
        for (String[] l : LISTS) {
            File old = new File(dir, l[0]);
            if (!old.isFile()) continue;
            File dst = new File(listDir, l[0] + ".gz");
            try (InputStream in = new FileInputStream(old);
                 OutputStream out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(dst), 1 << 16))) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                moved = true;
            } catch (IOException e) {
                Log.w(TAG, "cannot migrate " + old, e);
                deleteQuietly(dst);
            }
            deleteQuietly(old);
        }
        deleteQuietly(new File(dir, "domains.bin"));
        return moved;
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + f);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public String status() {
        return status;
    }

    public FilterEngine engine() {
        return engine;
    }

    /** Engine, waiting up to timeoutMs for the first build to finish (used by the WebView IO threads). */
    public FilterEngine awaitEngine(long timeoutMs) {
        FilterEngine e = engine;
        if (e != null) return e;
        try {
            ready.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return engine;
    }

    public void loadAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean migrated = migrateV010();
                rebuild();
                // lists compiled by an older build lack the current app rules: compile them again once
                boolean stale = new File(dir, UPDATED).isFile() && prefs.appRulesVersion() != APP_RULES_VERSION;
                if (migrated || stale) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    try {
                        if (compileLists(new StringBuilder())) rebuild();
                        Log.i(TAG, "lists recompiled with app rules v" + APP_RULES_VERSION);
                    } catch (IOException | RuntimeException e) {
                        Log.w(TAG, "compiling migrated lists failed", e);
                    }
                }
                // the bundled lists are current when the app is installed: the first automatic update comes one
                // interval after first launch, not at first launch
                long last = Math.max(prefs.lastUpdate(), prefs.firstSeen());
                if (prefs.autoUpdate() && System.currentTimeMillis() - last > UPDATE_INTERVAL_MS) {
                    update(null);
                }
            }
        }, "ctv-filters-load").start();
    }

    /** Maps the engine files and swaps the engine in. Cheap: nothing is parsed. */
    private synchronized void rebuild() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            ByteBuffer bundled = mapAsset(BUNDLED);
            File upd = new File(dir, UPDATED), extra = new File(dir, EXTRA);
            FilterEngine e;
            try {
                e = FilterEngine.open(bundled, upd.isFile() ? FilterEngine.map(upd) : null,
                        extra.isFile() ? FilterEngine.map(extra) : null);
            } catch (IOException | RuntimeException ex) {
                // a damaged or outdated update must not leave the browser unprotected: drop it, use the bundled lists
                if (!upd.isFile() && !extra.isFile()) throw ex;
                Log.w(TAG, "updated filters unusable, using bundled lists", ex);
                deleteQuietly(upd);
                deleteQuietly(extra);
                e = FilterEngine.open(bundled, null, null);
            }
            final FilterEngine fe = e;
            engine = fe;
            status = String.format(Locale.US, "%,d domains, %,d network filters, %,d cosmetic, %,d scriptlets (ready in %d ms)",
                    fe.domainCount(), fe.network.filterCount, fe.cosmetic.cosmeticCount, fe.cosmetic.scriptletCount,
                    SystemClock.elapsedRealtime() - t0);
            Log.i(TAG, fe.stats());
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) listener.onEngineChanged(fe);
                }
            });
        } catch (IOException | RuntimeException ex) {
            Log.e(TAG, "filter engine failed", ex);
            status = "Filters unavailable: " + ex.getMessage();
        } finally {
            ready.countDown();
        }
    }

    /**
     * Maps an asset in place. The asset is stored uncompressed in the APK (build.gradle noCompress), so this maps
     * the APK region directly; if it ever were compressed, it is copied into a direct buffer instead.
     */
    private ByteBuffer mapAsset(String name) throws IOException {
        AssetManager am = ctx.getAssets();
        try {
            AssetFileDescriptor afd = am.openFd(name);
            try (FileInputStream in = afd.createInputStream(); FileChannel ch = in.getChannel()) {
                return ch.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
        } catch (java.io.FileNotFoundException compressed) {
            Log.w(TAG, name + " is compressed in the APK; copying it instead of mapping");
            try (InputStream in = am.open(name)) {
                ByteBuffer b = ByteBuffer.allocateDirect(in.available());
                byte[] chunk = new byte[1 << 16];
                int n;
                while ((n = in.read(chunk)) > 0) b.put(chunk, 0, n);
                b.flip();
                return b;
            }
        }
    }

    /** Called from onTrimMemory: drops the engine's decode caches (they refill on demand). */
    public void trimMemory() {
        FilterEngine e = engine;
        if (e != null) e.trim();
    }

    private static Reader reader(InputStream in) {
        return new InputStreamReader(in, UTF8);
    }

    public boolean isUpdating() {
        return updating;
    }

    public interface UpdateCallback {
        void onDone(String result);
    }

    /** Download all lists and domain sources, compile them, then swap the engine. Runs on the calling thread. */
    public void update(final UpdateCallback cb) {
        if (updating) return;
        updating = true;
        String result;
        // a list update compiles ~200k rules; keep it from competing with video playback and the UI
        int oldPriority = Process.getThreadPriority(Process.myTid());
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
            int ok = 0;
            StringBuilder errs = new StringBuilder();
            for (String[] l : LISTS) {
                try {
                    downloadList(l[0], l[1], Integer.parseInt(l[2]));
                    ok++;
                } catch (IOException e) {
                    errs.append(' ').append(l[0]).append(": ").append(e.getMessage()).append(';');
                }
            }
            boolean compiled = false;
            if (ok > 0) {
                try {
                    compiled = compileLists(errs);
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "list compile failed", e);
                    errs.append(" compile: ").append(e.getMessage()).append(';');
                }
            }
            int dok = 0;
            try {
                dok = updateDomains(errs);
            } catch (IOException e) {
                errs.append(" domains: ").append(e.getMessage()).append(';');
            }
            if (compiled || dok > 0) rebuild();
            result = String.format(Locale.US, "%d/%d filter lists and %d/%d domain sources updated.%s", ok, LISTS.length,
                    dok, DOMAIN_SOURCES.length, errs.length() > 0 ? " Errors:" + errs : "");
            if (compiled || dok > 0) prefs.setLastUpdate(System.currentTimeMillis());
        } finally {
            updating = false;
            Process.setThreadPriority(oldPriority);
        }
        prefs.setLastUpdateResult(result);
        final String r = result;
        if (cb != null) main.post(new Runnable() {
            @Override
            public void run() {
                cb.onDone(r);
            }
        });
    }

    /**
     * Compiles the downloaded lists (kept gzip-compressed in files/filters/lists) into files/filters/filters.bin.
     * The text lists are not shipped in the APK, so every list needs one successful download first.
     */
    private boolean compileLists(StringBuilder errs) throws IOException {
        FilterCompiler c = new FilterCompiler();
        StringBuilder missing = new StringBuilder();
        for (String[] l : LISTS) {
            final File f = new File(listDir, l[0] + ".gz");
            if (!f.isFile()) {
                missing.append(missing.length() > 0 ? ", " : "").append(l[0]);
                continue;
            }
            c.addFilterList(new FilterCompiler.Source() {
                @Override
                public Reader open() throws IOException {
                    return reader(new GZIPInputStream(new FileInputStream(f), 1 << 16));
                }
            });
        }
        if (missing.length() > 0) {
            errs.append(" kept the current filters until these lists download once: ").append(missing).append(';');
            return false;
        }
        final AssetManager am = ctx.getAssets();
        c.addFilterList(new FilterCompiler.Source() {
            @Override
            public Reader open() throws IOException {
                return reader(am.open(APP_RULES));
            }
        });
        try (Reader r = reader(am.open("filters/allowlist.txt"))) {
            c.addAllowlist(r);
        }
        try (Reader r = reader(am.open("filters/allowlist-app.txt"))) {
            c.addAllowlist(r);
        }
        File tmp = new File(dir, UPDATED + ".tmp");
        FilterCompiler.Result res;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            res = c.compile(out);
        }
        Log.i(TAG, "compiled lists: " + res);
        prefs.setAppRulesVersion(APP_RULES_VERSION);
        if (!tmp.renameTo(new File(dir, UPDATED))) throw new IOException("cannot replace " + UPDATED);
        return true;
    }

    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ClearTV/" + BuildConfig.VERSION_NAME + " (filter updater)");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    /** Downloads one list into files/filters/lists/NAME.gz after checking it looks like a complete filter list. */
    private void downloadList(String name, String url, int minLines) throws IOException {
        File tmp = new File(listDir, name + ".gz.tmp");
        HttpURLConnection c = connect(url);
        int lines = 0;
        boolean header = false;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), UTF8), 1 << 16);
             OutputStream out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (lines < 20 && (line.startsWith("[Adblock") || line.startsWith("! Title"))) header = true;
                out.write(line.getBytes(UTF8));
                out.write('\n');
                lines++;
            }
        } finally {
            c.disconnect();
        }
        if (!header || lines < minLines) {
            if (!tmp.delete()) Log.w(TAG, "cannot delete " + tmp);
            throw new IOException("rejected (" + lines + " lines" + (header ? "" : ", no filter-list header") + ")");
        }
        File dst = new File(listDir, name + ".gz");
        if (!tmp.renameTo(dst)) throw new IOException("cannot replace " + dst);
    }

    private static final Pattern HOST = Pattern.compile("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$");

    /**
     * Downloads the DNS-style domain sources into files/filters/domains-extra.bin. Hosts the bundled domain set
     * already covers (the host or a parent domain is in it) are skipped, so the file holds only what is new.
     */
    private int updateDomains(StringBuilder errs) throws IOException {
        FilterEngine bundledOnly = FilterEngine.open(mapAsset(BUNDLED), null, null);
        HashSet<String> allow = new HashSet<>();
        for (String a : new String[]{"filters/allowlist.txt", "filters/allowlist-app.txt"}) {
            try (BufferedReader br = new BufferedReader(reader(ctx.getAssets().open(a)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) allow.add(line.split("[\\s#]")[0].toLowerCase(Locale.ROOT));
                }
            }
        }
        LongSet.Builder all = new LongSet.Builder();
        int ok = 0, total = 0;
        for (String[] src : DOMAIN_SOURCES) {
            LongSet.Builder b = new LongSet.Builder();
            int got = 0;
            try {
                HttpURLConnection c = connect(src[1]);
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), UTF8), 1 << 16)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String d = parseDomainLine(line);
                        if (d == null || allow.contains(d)) continue;
                        got++;
                        if (!coveredBy(bundledOnly, d)) b.add(Hash.of(d));
                    }
                } finally {
                    c.disconnect();
                }
                if (got < Integer.parseInt(src[2])) {
                    // a truncated or changed source is ignored rather than shrinking protection
                    errs.append(' ').append(src[0]).append(": only ").append(got).append(" domains, ignored;");
                    continue;
                }
                all.addAll(b);
                total += got;
                ok++;
            } catch (IOException e) {
                errs.append(' ').append(src[0]).append(": ").append(e.getMessage()).append(';');
            }
        }
        if (ok == 0) return 0;
        File tmp = new File(dir, EXTRA + ".tmp");
        int stored;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            stored = FilterCompiler.writeDomainSet(all, total, out);
        }
        Log.i(TAG, "domain sources: " + total + " hosts, " + stored + " not covered by the bundled set");
        if (!tmp.renameTo(new File(dir, EXTRA))) throw new IOException("cannot replace " + EXTRA);
        return ok;
    }

    /** True if host or one of its parent domains (not the bare TLD) is in the engine's domain set. */
    private static boolean coveredBy(FilterEngine e, String host) {
        int start = 0;
        while (true) {
            int dot = host.indexOf('.', start);
            if (dot < 0) return false; // bare TLD
            if (e.inDomainSet(Hash.of(host, start, host.length()))) return true;
            start = dot + 1;
        }
    }

    /** Accepts "domain", "*.domain", "0.0.0.0 domain", "127.0.0.1 domain", "||domain^"; returns null otherwise. */
    static String parseDomainLine(String line) {
        String l = line.trim();
        if (l.isEmpty() || l.charAt(0) == '#' || l.charAt(0) == '!') return null;
        int hash = l.indexOf('#');
        if (hash > 0) l = l.substring(0, hash).trim();
        if (l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ")) l = l.substring(l.indexOf(' ') + 1).trim();
        if (l.startsWith("*.")) l = l.substring(2);
        if (l.startsWith("||") && l.endsWith("^")) l = l.substring(2, l.length() - 1);
        l = l.toLowerCase(Locale.ROOT);
        if (l.endsWith(".")) l = l.substring(0, l.length() - 1);
        if (l.equals("localhost") || l.equals("0.0.0.0") || l.endsWith(".localdomain")) return null;
        return HOST.matcher(l).matches() ? l : null;
    }

    /** Remove downloaded lists and go back to the bundled snapshot. */
    public void resetToBundled() {
        File[] ls = listDir.listFiles();
        if (ls != null) for (File f : ls) deleteQuietly(f);
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile()) deleteQuietly(f);
        prefs.setLastUpdate(0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                rebuild();
            }
        }, "ctv-filters-reset").start();
    }
}
