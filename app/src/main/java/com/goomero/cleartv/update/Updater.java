package com.goomero.cleartv.update;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.goomero.cleartv.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app updates from the GitHub Releases of the public repo ({@link BuildConfig#UPDATE_URL}, fixed at build time).
 *
 * <p>A release qualifies when its tag (v0.1.19 or 0.1.19) is newer than this build, it has exactly one
 * {@code .apk} asset, and a {@code <apk name>.sha256} asset (or {@code SHA256SUMS}) gives the APK's SHA-256. The APK
 * is downloaded over https into the app's cache and installed only when all of these hold: its size equals the
 * release's, its SHA-256 equals the published one, its package is this app's, its version code is higher, and its
 * signing certificates equal the installed app's. Anything else is refused with a reason and the file is deleted.
 * The package installer always asks the user before installing.
 */
public final class Updater {
    private static final String TAG = "CTV.Update", PREFS = "updates";
    public static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_JSON = 256 * 1024, MAX_SUM = 8 * 1024;
    private static final long MAX_APK = 100L * 1024 * 1024;
    private static final Pattern HEX = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    private Updater() {}

    /** A newer release found by {@link #check}. */
    public static final class Release {
        public String version, apkName, apkUrl, shaUrl, page;
        public long size;
    }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean autoCheck(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    public static void setAutoCheck(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
    }

    /** The newest version seen by the last check ("" when this build is the newest). */
    public static String available(Context c) {
        String v = prefs(c).getString("latest", "");
        return newer(v, BuildConfig.VERSION_NAME) ? v : "";
    }

    public static long lastCheck(Context c) {
        return prefs(c).getLong("last", 0);
    }

    /** Where updates come from, for Settings. */
    public static String source() {
        return BuildConfig.UPDATE_URL;
    }

    // ------------------------------------------------------------------------------------------- versions

    /** Parses "v1.2.3" / "1.2.3" (anything after a '-' or '+' is ignored); null when it is not a version. */
    static int[] parse(String v) {
        if (v == null) return null;
        Matcher m = Pattern.compile("^[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:[-+].*)?$").matcher(v.trim());
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** True when {@code a} is a higher version than {@code b}. */
    public static boolean newer(String a, String b) {
        int[] x = parse(a), y = parse(b);
        if (x == null || y == null) return false;
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] > y[i];
        return false;
    }

    // ------------------------------------------------------------------------------------------- check

    /** Asks the release source for the latest release. Blocking. Returns null when this build is current. */
    public static Release check(Context c) throws IOException {
        URL src = Net.url(BuildConfig.UPDATE_URL);
        byte[] b = Net.get(src, MAX_JSON, "application/vnd.github+json, application/json");
        Release r;
        try {
            r = parseRelease(new String(b, StandardCharsets.UTF_8), src);
        } catch (JSONException e) {
            throw new Net.Refused("The release information is not valid JSON.");
        }
        prefs(c).edit().putLong("last", System.currentTimeMillis()).putString("latest", r == null ? "" : r.version).apply();
        return r;
    }

    static Release parseRelease(String json, URL src) throws JSONException, Net.Refused {
        JSONObject o = new JSONObject(json);
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null;
        String tag = o.optString("tag_name", "");
        if (parse(tag) == null) throw new Net.Refused("The latest release tag \"" + tag + "\" is not a version.");
        if (!newer(tag, BuildConfig.VERSION_NAME)) return null;
        JSONArray assets = o.optJSONArray("assets");
        if (assets == null) throw new Net.Refused("The release has no files.");
        Release r = new Release();
        r.version = tag.replaceFirst("^[vV]", "");
        r.page = o.optString("html_url", "");
        String sums = null;
        int apks = 0;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name", ""), link = a.optString("browser_download_url", "");
            if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                apks++;
                r.apkName = name;
                r.apkUrl = link;
                r.size = a.optLong("size", -1);
            } else if (name.equals("SHA256SUMS") && sums == null) {
                sums = link;
            }
        }
        if (apks != 1) throw new Net.Refused(apks == 0 ? "The release has no APK file." : "The release has more than one APK file.");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").equals(r.apkName + ".sha256")) r.shaUrl = a.optString("browser_download_url");
        }
        if (r.shaUrl == null) r.shaUrl = sums;
        if (r.shaUrl == null) throw new Net.Refused("The release has no " + r.apkName + ".sha256 or SHA256SUMS file, so the APK cannot be checked.");
        if (r.size <= 0 || r.size > MAX_APK) throw new Net.Refused("The APK size in the release is not usable.");
        Net.url(r.apkUrl);
        Net.url(r.shaUrl);
        return r;
    }

    /** Daily check from app start (background thread); a toast says when a newer version is out. */
    public static void checkIfDue(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (!autoCheck(c) || System.currentTimeMillis() - lastCheck(c) < DAY_MS) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    final Release r = check(c);
                    if (r == null) return;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(c, "ClearTV " + r.version + " is available: Settings > Updates", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (IOException e) {
                    Log.i(TAG, "update check: " + e.getMessage());
                }
            }
        }, "ctv-update").start();
    }

    // ------------------------------------------------------------------------------------------- download

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Downloads and verifies the release APK. Blocking. Throws {@link Net.Refused} with the reason when it fails a check. */
    public static File download(Context c, Release r, Net.Progress p) throws IOException {
        byte[] sumFile = Net.get(Net.url(r.shaUrl), MAX_SUM, "text/plain, application/octet-stream");
        String want = expectedSha(new String(sumFile, StandardCharsets.UTF_8), r.apkName);
        if (want == null) throw new Net.Refused("The checksum file does not list " + r.apkName + ".");
        File f = new File(dir(c), "update.apk");
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        String got;
        try (OutputStream out = new FileOutputStream(f)) {
            got = Net.save(Net.url(r.apkUrl), out, r.size, p);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw e;
        }
        try {
            if (!got.equalsIgnoreCase(want)) throw new Net.Refused("The APK's SHA-256 does not match the release's checksum. Not installed.");
            verifyPackage(c, f);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw e;
        }
        return f;
    }

    /** The SHA-256 for {@code name}: a lone hash, or the "hash  name" line of a SHA256SUMS file. */
    static String expectedSha(String text, String name) {
        String only = null;
        int lines = 0;
        for (String line : text.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            lines++;
            Matcher m = HEX.matcher(line);
            if (!m.find()) continue;
            String rest = line.substring(m.end()).trim().replaceFirst("^\\*", "");
            if (rest.equals(name)) return m.group(1).toLowerCase(Locale.ROOT);
            if (rest.isEmpty()) only = m.group(1).toLowerCase(Locale.ROOT);
        }
        return lines == 1 ? only : null;
    }

    @SuppressLint("PackageManagerGetSignatures")
    @SuppressWarnings("deprecation")
    static void verifyPackage(Context c, File apk) throws Net.Refused {
        PackageManager pm = c.getPackageManager();
        PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (got == null) throw new Net.Refused("The download is not a readable APK. Not installed.");
        if (!c.getPackageName().equals(got.packageName)) throw new Net.Refused("The APK is a different app (" + got.packageName + "). Not installed.");
        PackageInfo mine;
        try {
            mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Net.Refused("Cannot read this app's own signature.");
        }
        if (code(got) <= code(mine)) throw new Net.Refused("The APK is not newer than this build. Not installed.");
        Set<String> a = certs(got.signatures), b = certs(mine.signatures);
        if (a.isEmpty() || !a.equals(b)) throw new Net.Refused("The APK is signed with a different key than this app. Not installed.");
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static Set<String> certs(Signature[] s) {
        Set<String> out = new HashSet<>();
        if (s != null) for (Signature x : s) out.add(Net.sha256(x.toByteArray()));
        return out;
    }

    /** SHA-256 of this app's signing certificate (for Settings and docs). */
    @SuppressLint("PackageManagerGetSignatures")
    @SuppressWarnings("deprecation")
    public static String ownCert(Context c) {
        try {
            Signature[] s = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            String[] l = certs(s).toArray(new String[0]);
            Arrays.sort(l);
            return l.length == 0 ? "" : l[0];
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------------------------------- install

    /** Whether ClearTV may open the package installer (Android 8+: "Install unknown apps" for ClearTV). */
    public static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    /** The system screen where the user allows ClearTV to install apps, or null when the device has none. */
    public static Intent permissionScreen(Context c) {
        if (Build.VERSION.SDK_INT < 26) return null;
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
        return i.resolveActivity(c.getPackageManager()) != null ? i : null;
    }

    /** The package installer for a verified APK (it asks the user before installing). */
    @SuppressWarnings("deprecation")
    public static Intent installIntent(Context c, File apk) {
        Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
