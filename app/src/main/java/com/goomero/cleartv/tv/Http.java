package com.goomero.cleartv.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Small blocking HTTP client for the native service screens (called on background threads only). */
public final class Http {
    public static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    /**
     * A Cobalt (YouTube on TV) agent with this device's Android version and model; youtube.com/tv serves its
     * living-room interface to it.
     */
    public static final String TV_UA = "Mozilla/5.0 (Linux armeabi-v7a; Android " + android.os.Build.VERSION.RELEASE
            + ") Cobalt/25.lts.30.1034943-gold (unlike Gecko) v8/8.8.278.17-jit gles Starboard/15, "
            + android.os.Build.MANUFACTURER.replaceAll("[^A-Za-z0-9]", "") + "_ATV_"
            + android.os.Build.HARDWARE.replaceAll("[^A-Za-z0-9]", "") + "/"
            + android.os.Build.ID.replaceAll("[^A-Za-z0-9.]", "") + " ("
            + android.os.Build.MANUFACTURER.replaceAll("[(),;]", "") + ", "
            + android.os.Build.MODEL.replaceAll("[(),;]", "") + ", Wireless) cleartv/25.lts.30";

    /** Non-2xx answer; {@link #body} holds the start of the error body for logging. */
    public static final class Error extends IOException {
        public final int code;
        public final String body;

        Error(int code, String body, String url) {
            super("HTTP " + code + " " + shortUrl(url) + (body.isEmpty() ? "" : ": " + body));
            this.code = code;
            this.body = body;
        }
    }

    /** An open response: the (un-gzipped) body stream and the final URL after redirects. */
    public static final class Response implements AutoCloseable {
        public final InputStream body;
        public final String url;
        public final int code;
        private final HttpURLConnection conn;

        Response(HttpURLConnection c, InputStream in) {
            conn = c;
            body = in;
            url = c.getURL().toString();
            int rc;
            try {
                rc = c.getResponseCode();
            } catch (IOException e) {
                rc = 0;
            }
            code = rc;
        }

        /** The response's Set-Cookie headers, as sent. */
        /** A response header (e.g. Location), or "". */
        public String header(String name) {
            String v = conn.getHeaderField(name);
            return v == null ? "" : v;
        }

        public java.util.List<String> setCookies() {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) out.addAll(e.getValue());
            }
            return out;
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
            }
            conn.disconnect();
        }
    }

    private Http() {}

    static String shortUrl(String u) {
        int q = u.indexOf('?');
        return q > 0 ? u.substring(0, q) : u;
    }

    /**
     * Opens a request. {@code body} null means GET; otherwise POST with that body and {@code contentType}.
     * {@code followRedirects} false returns 3xx responses as they are (used to detect sign-in redirects).
     */
    public static Response open(String url, Map<String, String> headers, byte[] body, String contentType,
                                boolean followRedirects) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(followRedirects);
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
        }
        int code = c.getResponseCode();
        boolean redirect = code >= 300 && code < 400 && !followRedirects;
        if ((code < 200 || code >= 300) && !redirect) {
            InputStream es = c.getErrorStream();
            String err = "";
            if (es != null) {
                try {
                    if ("gzip".equalsIgnoreCase(c.getContentEncoding())) es = new GZIPInputStream(es);
                    byte[] b = readAll(es, 400);
                    err = new String(b, StandardCharsets.UTF_8).replace('\n', ' ');
                } catch (IOException ignored) {
                }
            }
            c.disconnect();
            throw new Error(code, err, url);
        }
        InputStream in = redirect ? new java.io.ByteArrayInputStream(new byte[0]) : c.getInputStream();
        if (!redirect && "gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in, 16384);
        return new Response(c, in);
    }

    public static byte[] get(String url, Map<String, String> headers) throws IOException {
        try (Response r = open(url, headers, null, null, true)) {
            return readAll(r.body, Integer.MAX_VALUE);
        }
    }

    public static String postJson(String url, Map<String, String> headers, String json) throws IOException {
        try (Response r = open(url, headers, json.getBytes(StandardCharsets.UTF_8), "application/json", true)) {
            return new String(readAll(r.body, Integer.MAX_VALUE), StandardCharsets.UTF_8);
        }
    }

    public static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[16384];
        int n;
        while (o.size() < max && (n = in.read(buf)) > 0) o.write(buf, 0, Math.min(n, max - o.size()));
        return o.toByteArray();
    }
}
