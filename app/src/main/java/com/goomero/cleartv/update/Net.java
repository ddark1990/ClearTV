package com.goomero.cleartv.update;

import com.goomero.cleartv.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Size-capped downloads for the updater and the theme store. Only https is accepted, redirects must stay on https,
 * and nothing larger than the caller's cap is read. The one exception is a test build made with
 * {@code -Pcleartv.allowLocalHttp=true}: it also accepts http to 127.0.0.1 (a mock server reached through
 * {@code adb reverse}). Release builds never set it.
 */
public final class Net {
    private Net() {}

    /** A download that was refused (wrong scheme, too large, bad answer). The message is shown to the user. */
    public static final class Refused extends IOException {
        public Refused(String m) {
            super(m);
        }
    }

    static boolean allowed(URL u) {
        if ("https".equalsIgnoreCase(u.getProtocol()) && !u.getHost().isEmpty()) return true;
        return BuildConfig.ALLOW_LOCAL_HTTP && "http".equalsIgnoreCase(u.getProtocol()) && "127.0.0.1".equals(u.getHost());
    }

    public static URL url(String s) throws Refused {
        try {
            URL u = new URL(s);
            if (!allowed(u)) throw new Refused("Only https:// addresses are accepted.");
            return u;
        } catch (java.net.MalformedURLException e) {
            throw new Refused("Not a web address.");
        }
    }

    /** Resolves a relative path (letters, digits, . _ - and /, no "..") against a base address. */
    public static URL resolve(URL base, String rel) throws Refused {
        if (rel == null || !rel.matches("[A-Za-z0-9._/-]{1,200}") || rel.startsWith("/") || rel.contains("..") || rel.contains("//"))
            throw new Refused("Bad file path in the index: " + rel);
        try {
            return url(new URL(base, rel).toString());
        } catch (java.net.MalformedURLException e) {
            throw new Refused("Bad file path in the index: " + rel);
        }
    }

    private static HttpURLConnection open(URL u, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true); // HttpURLConnection never follows https -> http
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "ClearTV/" + BuildConfig.VERSION_NAME);
        int code = c.getResponseCode();
        if (!allowed(c.getURL())) {
            c.disconnect();
            throw new Refused("The address redirected away from https.");
        }
        if (code != 200) {
            c.disconnect();
            throw new Refused("The server answered " + code + ".");
        }
        return c;
    }

    /** Reads at most {@code max} bytes; a longer answer is refused. */
    public static byte[] get(URL u, int max, String accept) throws IOException {
        HttpURLConnection c = open(u, accept);
        try {
            if (c.getContentLength() > max) throw new Refused("The file is larger than " + max / 1024 + " KB.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(c.getInputStream(), out, max, null, -1, null);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    public interface Progress {
        void on(long done, long total);
    }

    /** Streams to {@code out}, refusing anything but exactly {@code size} bytes; returns the SHA-256 (hex). */
    public static String save(URL u, OutputStream out, long size, Progress p) throws IOException {
        HttpURLConnection c = open(u, "application/octet-stream");
        try {
            long len = c.getContentLength();
            if (len >= 0 && len != size) throw new Refused("The download is " + len + " bytes; the release says " + size + ".");
            MessageDigest md = sha256();
            long n = copy(c.getInputStream(), out, size, md, size, p);
            if (n != size) throw new Refused("The download is " + n + " bytes; the release says " + size + ".");
            return hex(md.digest());
        } finally {
            c.disconnect();
        }
    }

    private static long copy(InputStream in, OutputStream out, long max, MessageDigest md, long total, Progress p) throws IOException {
        byte[] buf = new byte[16384];
        long n = 0;
        int r;
        try {
            while ((r = in.read(buf)) > 0) {
                n += r;
                if (n > max) throw new Refused("The file is larger than expected.");
                out.write(buf, 0, r);
                if (md != null) md.update(buf, 0, r);
                if (p != null) p.on(n, total);
            }
        } finally {
            in.close();
        }
        return n;
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] b) {
        return hex(sha256().digest(b));
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
}
