package com.goomero.cleartv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The phone page: a tiny web server on the home network so a phone that scans the QR code gets a remote (D-pad,
 * OK, BACK, play/pause, seek, options) and a keyboard for any text box on the TV (search, passwords on sign-in
 * pages). Lean by design: one thread, started only when a QR code for it is on screen, and secured by
 * <ul>
 *   <li>a random 128-bit token in every path (wrong tokens get 404; ten of them stop the server),</li>
 *   <li>LAN only: bound to the Wi-Fi/Ethernet address, private-range callers only,</li>
 *   <li>off when idle: 10 minutes without a request, or 30 s after ClearTV leaves the screen.</li>
 * </ul>
 * Keys go through the foreground activity's own key handling, exactly like the remote.
 */
public final class PhoneLink {
    /** A screen that takes typed text itself (a web page's focused field). */
    public interface TextTarget {
        void onPhoneText(String text, boolean enter);
    }

    private static final String TAG = "CTV.Phone";
    private static final long IDLE_MS = 10 * 60_000L, BACKGROUND_MS = 30_000L;
    private static final int MAX_BAD = 10, MAX_BODY = 4096, MAX_TEXT = 1024;
    /** Whole-request budget: a client that trickles bytes cannot hold the single server thread longer than this. */
    private static final long REQUEST_MS = 6000;
    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        KEYS.put("up", KeyEvent.KEYCODE_DPAD_UP);
        KEYS.put("down", KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("left", KeyEvent.KEYCODE_DPAD_LEFT);
        KEYS.put("right", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("ok", KeyEvent.KEYCODE_DPAD_CENTER);
        KEYS.put("back", KeyEvent.KEYCODE_BACK);
        KEYS.put("menu", KeyEvent.KEYCODE_MENU);
        KEYS.put("play", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        KEYS.put("rew", KeyEvent.KEYCODE_MEDIA_REWIND);
        KEYS.put("ff", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        KEYS.put("del", KeyEvent.KEYCODE_DEL);
    }

    private static PhoneLink instance;
    private static WeakReference<Activity> top = new WeakReference<>(null);
    private static int started;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final String token;
    private volatile ServerSocket server;
    private String url, hostPort;
    private volatile long lastUse;
    private int bad;

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.uptimeMillis() - lastUse >= IDLE_MS) stop("idle");
            else main.postDelayed(this, 60_000L);
        }
    };
    private final Runnable backgroundStop = new Runnable() {
        @Override
        public void run() {
            if (started == 0) stop("ClearTV left the screen");
        }
    };

    private PhoneLink() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        token = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------------------- lifecycle hooks

    /** Called by {@link App} for every activity: keys and text go to the one in front. */
    static void onResumed(Activity a) {
        top = new WeakReference<>(a);
    }

    static void onStarted() {
        started++;
    }

    static void onStopped() {
        started = Math.max(0, started - 1);
        PhoneLink p = instance;
        if (started == 0 && p != null) {
            p.main.removeCallbacks(p.backgroundStop);
            p.main.postDelayed(p.backgroundStop, BACKGROUND_MS);
        }
    }

    /**
     * Starts the server if needed and returns the address for the QR code ({@code http://ip:port/token/}),
     * or null without a home network. Call from the main thread.
     */
    public static synchronized String start() {
        if (instance != null && instance.server != null && !instance.server.isClosed()) {
            instance.lastUse = SystemClock.uptimeMillis();
            return instance.url;
        }
        InetAddress ip = lanAddress();
        if (ip == null) return null;
        PhoneLink p = new PhoneLink();
        try {
            p.server = new ServerSocket();
            p.server.bind(new InetSocketAddress(ip, 0), 4);
        } catch (IOException e) {
            Log.w(TAG, "cannot listen: " + e.getMessage());
            return null;
        }
        p.hostPort = ip.getHostAddress() + ":" + p.server.getLocalPort();
        p.url = "http://" + p.hostPort + "/" + p.token + "/";
        p.lastUse = SystemClock.uptimeMillis();
        instance = p;
        final PhoneLink self = p;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                self.loop();
            }
        }, "phone-link");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.setDaemon(true);
        t.start();
        p.main.postDelayed(p.idleCheck, 60_000L);
        Log.i(TAG, "listening on " + ip.getHostAddress() + ":" + p.server.getLocalPort());
        return p.url;
    }

    public static synchronized boolean running() {
        return instance != null && instance.server != null && !instance.server.isClosed();
    }

    public static synchronized void stopNow() {
        if (instance != null) instance.stop("turned off");
    }

    private void stop(String why) {
        main.removeCallbacksAndMessages(null);
        synchronized (PhoneLink.class) {
            try {
                if (server != null) server.close();
            } catch (IOException ignored) {
            }
            server = null;
            if (instance == this) instance = null;
        }
        Log.i(TAG, "stopped: " + why);
    }

    private static InetAddress lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "no network: " + e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------------------------- server

    private void loop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try (Socket c = s.accept()) {
                c.setSoTimeout(2000);
                // Java hands IPv4-mapped IPv6 callers over as Inet4Address; the server binds an IPv4 address only
                if (!privateCaller(c.getInetAddress())) continue;
                handle(c, SystemClock.uptimeMillis() + REQUEST_MS);
            } catch (IOException e) {
                if (server == null) return;
            } catch (RuntimeException e) {
                // a malformed request must never end the server thread (or, uncaught, the app)
                Log.w(TAG, "bad request: " + e.getClass().getSimpleName());
            }
        }
    }

    /** Private (RFC 1918), link-local or loopback IPv4 callers only; anything else is not on the home network. */
    static boolean privateCaller(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        return a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress();
    }

    /** Constant-time token check of the first path segment ({@code /TOKEN/rest}). */
    private boolean tokenOk(String path) {
        if (path.length() < 2 || path.charAt(0) != '/') return false;
        int end = path.indexOf('/', 1);
        if (end < 0) return false;
        return MessageDigest.isEqual(path.substring(1, end).getBytes(StandardCharsets.ISO_8859_1),
                token.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void handle(Socket c, long deadline) throws IOException {
        InputStream in = c.getInputStream();
        String head = readHead(in, deadline);
        if (head == null) return;
        String[] first = head.substring(0, Math.max(0, head.indexOf('\r'))).split(" ");
        if (first.length != 3 || !first[2].startsWith("HTTP/1.")) return;
        String method = first[0], path = first[1];
        int len = 0;
        String host = null, origin = null, fetchSite = null, contentType = null;
        boolean badHeaders = false, sawLen = false;
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            switch (name) {
                case "content-length":
                    int v;
                    try {
                        v = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        v = -1;
                    }
                    if (v < 0 || (sawLen && v != len)) badHeaders = true;
                    len = Math.max(0, v);
                    sawLen = true;
                    break;
                case "transfer-encoding":
                    badHeaders = true; // no chunked bodies here
                    break;
                case "host":
                    if (host != null) badHeaders = true;
                    host = value;
                    break;
                case "origin":
                    origin = value;
                    break;
                case "content-type":
                    contentType = value;
                    break;
                case "sec-fetch-site":
                    fetchSite = value;
                    break;
                default:
                    break;
            }
        }
        OutputStream out = c.getOutputStream();
        if (!tokenOk(path)) {
            // favicon requests and probes are not counted; anything else with a wrong token is
            if (!path.equals("/favicon.ico") && ++bad >= MAX_BAD) {
                send(out, 404, "text/plain", "gone");
                stop("too many wrong tokens");
                return;
            }
            if (path.contains("/api/")) sendApi(out, com.goomero.cleartv.theme.ThemeApi.error(404, "not_found", "Unknown token: scan the QR code on the TV again.", null));
            else send(out, 404, "text/plain", "not found");
            return;
        }
        // DNS rebinding / cross-site requests from another page in the phone's browser: the request must name
        // this server itself, and scripted requests must come from this page.
        if (badHeaders || host == null || !host.equalsIgnoreCase(hostPort)
                || (origin != null && !origin.equalsIgnoreCase("http://" + hostPort))
                || (fetchSite != null && !(fetchSite.equals("same-origin") || fetchSite.equals("none")))) {
            if (path.contains("/api/")) sendApi(out, com.goomero.cleartv.theme.ThemeApi.error(400, "bad_request", "Host, Origin or Sec-Fetch-Site does not match this TV.", null));
            else send(out, 400, "text/plain", "bad request");
            return;
        }
        lastUse = SystemClock.uptimeMillis();
        String rest = path.substring(token.length() + 2);
        int q = rest.indexOf('?');
        String route = q >= 0 ? rest.substring(0, q) : rest;
        if (method.equals("GET") && route.isEmpty()) {
            send(out, 200, "text/html; charset=utf-8", PAGE);
            return;
        }
        if (method.equals("GET") && route.equals("theme")) {
            send(out, 200, "text/html; charset=utf-8", ThemePage.HTML);
            return;
        }
        // the theme control API (docs/AGENTS-API.md): JSON in, JSON out, same token and checks as the remote
        if (com.goomero.cleartv.theme.ThemeApi.handles(route)) {
            if (badHeaders || len > com.goomero.cleartv.theme.ThemeApi.MAX_BODY) {
                sendApi(out, com.goomero.cleartv.theme.ThemeApi.error(413, "too_large", "The body is larger than "
                        + com.goomero.cleartv.theme.ThemeApi.MAX_BODY + " bytes.", null));
                return;
            }
            String text = "";
            if (len > 0) {
                byte[] body = new byte[len];
                int got = 0;
                while (got < len) {
                    if (SystemClock.uptimeMillis() > deadline) return;
                    int r = in.read(body, got, len - got);
                    if (r < 0) break;
                    got += r;
                }
                text = new String(body, 0, got, StandardCharsets.UTF_8);
            }
            sendApi(out, com.goomero.cleartv.theme.Themes.api().handle(method, route, contentType, text));
            return;
        }
        if (!method.equals("POST") || len > MAX_BODY) {
            send(out, 400, "text/plain", "bad request");
            return;
        }
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) {
            if (SystemClock.uptimeMillis() > deadline) return;
            int r = in.read(body, got, len - got);
            if (r < 0) break;
            got += r;
        }
        Map<String, String> form = form(new String(body, 0, got, StandardCharsets.UTF_8));
        if (rest.equals("key")) {
            Integer code = KEYS.get(form.get("k"));
            if (code == null) {
                send(out, 400, "text/plain", "unknown key");
                return;
            }
            key(code, "1".equals(form.get("hold")));
            send(out, 200, "application/json", "{\"ok\":true}");
        } else if (rest.equals("text")) {
            text(clean(form.get("t")), "1".equals(form.get("enter")));
            send(out, 200, "application/json", "{\"ok\":true}");
        } else {
            send(out, 404, "text/plain", "not found");
        }
    }

    /** Typed text as one plain line of at most {@link #MAX_TEXT} characters: no control characters or newlines. */
    static String clean(String t) {
        if (t == null) return "";
        StringBuilder b = new StringBuilder(Math.min(t.length(), MAX_TEXT));
        for (int i = 0; i < t.length() && b.length() < MAX_TEXT; i++) {
            char ch = t.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') b.append(' ');
            else if (!Character.isISOControl(ch) && ch != '\u2028' && ch != '\u2029') b.append(ch);
        }
        return b.toString();
    }

    private static String readHead(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 8192) {
            if (SystemClock.uptimeMillis() > deadline) return null;
            int ch = in.read();
            if (ch < 0) return null;
            b.write(ch);
            state = (ch == '\r' && (state == 0 || state == 2)) ? state + 1 : (ch == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("ISO-8859-1");
        }
        return null;
    }

    private static Map<String, String> form(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
            }
        }
        return m;
    }

    private static void sendApi(OutputStream out, com.goomero.cleartv.theme.ThemeApi.Response r) throws IOException {
        send(out, r.status, r.type + "; charset=utf-8", r.body,
                r.download ? "Content-Disposition: attachment; filename=\"cleartv-theme.json\"\r\n" : "");
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        send(out, code, type, body, "");
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return " OK";
            case 404: return " Not Found";
            case 405: return " Method Not Allowed";
            case 409: return " Conflict";
            case 413: return " Payload Too Large";
            case 415: return " Unsupported Media Type";
            case 422: return " Unprocessable Entity";
            case 429: return " Too Many Requests";
            default: return " Bad Request";
        }
    }

    private static void send(OutputStream out, int code, String type, String body, String extra) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + reason(code) + "\r\n" + extra
                + "Content-Type: " + type + "\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';"
                + " connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    // ------------------------------------------------------------------------------- into the app

    /**
     * Injects the key into ClearTV's own window as if the remote sent it (focus moves, players react). Android
     * lets an app inject into its own windows without any permission; this runs off the main thread.
     */
    private void key(final int code, final boolean hold) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    key(code, hold);
                }
            }).start();
            return;
        }
        android.app.Instrumentation in = new android.app.Instrumentation();
        long now = SystemClock.uptimeMillis();
        try {
            in.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            // a long press on the phone repeats like a held remote key: scrubbing, fast scrolling
            if (hold) {
                for (int i = 1; i <= 12; i++) {
                    SystemClock.sleep(50);
                    in.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, i));
                }
            }
            in.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        } catch (RuntimeException e) {
            // another app is in front (ClearTV only injects into its own windows)
            Log.w(TAG, "key not delivered: " + e.getMessage());
        }
    }

    private void text(final String t, final boolean enter) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Activity a = top.get();
                if (a == null || a.isFinishing()) return;
                if (a instanceof TextTarget) {
                    ((TextTarget) a).onPhoneText(t, enter);
                    return;
                }
                View f = a.getCurrentFocus();
                if (f instanceof EditText) {
                    EditText e = (EditText) f;
                    e.setText(t);
                    e.setSelection(e.length());
                    if (enter) e.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
                } else if (enter) {
                    key(KeyEvent.KEYCODE_DPAD_CENTER, false);
                }
            }
        });
    }

    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1,user-scalable=no'><title>ClearTV remote</title>"
            + "<style>body{margin:0;font:16px system-ui,sans-serif;background:#0d0f12;color:#e8ecf0;-webkit-user-select:none;user-select:none}"
            + "main{max-width:420px;margin:0 auto;padding:16px}h1{font-size:13px;letter-spacing:.2em;color:#8a96a3;margin:4px 0 16px}"
            + ".pad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}button{font:inherit;color:inherit;background:#1c2026;"
            + "border:1px solid #2c323a;border-radius:14px;padding:18px 0;touch-action:manipulation}button:active{background:#2f6fed}"
            + ".ok{border-radius:50%;background:#262b33}.row{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:12px}"
            + "textarea{width:100%;box-sizing:border-box;margin-top:18px;min-height:64px;font:inherit;color:inherit;background:#15181d;"
            + "border:1px solid #2c323a;border-radius:12px;padding:12px}.send{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:10px}"
            + "#s{font-size:12px;color:#8a96a3;margin-top:12px;min-height:16px}</style></head><body><main><h1>CLEARTV REMOTE</h1>"
            + "<div class=pad><span></span><button data-k=up>&#9650;</button><span></span><button data-k=left>&#9664;</button>"
            + "<button class=ok data-k=ok>OK</button><button data-k=right>&#9654;</button><button data-k=back>Back</button>"
            + "<button data-k=down>&#9660;</button><button data-k=menu>Menu</button></div>"
            + "<div class=row><button data-k=rew>&#9194;</button><button data-k=play>&#9199;</button><button data-k=ff>&#9193;</button>"
            + "<button data-k=del>&#9003;</button></div>"
            + "<textarea id=t placeholder='Type here, then Send to the text box on the TV' autocomplete=off autocapitalize=off></textarea>"
            + "<div class=send><button id=a>Send</button><button id=e>Send + Enter</button></div><div id=s></div><p><a href=theme style='color:#8a96a3'>Themes: import, preview, export</a></p></main><script>"
            + "const s=document.getElementById('s');function post(p,b){return fetch(p,{method:'POST',headers:{'Content-Type':"
            + "'application/x-www-form-urlencoded'},body:new URLSearchParams(b)}).then(r=>{s.textContent=r.ok?'':'TV said '+r.status})"
            + ".catch(()=>{s.textContent='The TV page is off. Scan the QR code on the TV again.'})}"
            + "document.querySelectorAll('[data-k]').forEach(b=>{let t=0,held=0;b.addEventListener('pointerdown',()=>{held=0;"
            + "t=setTimeout(()=>{held=1;post('key',{k:b.dataset.k,hold:1})},450)});b.addEventListener('pointerup',()=>{clearTimeout(t);"
            + "if(!held)post('key',{k:b.dataset.k})});b.addEventListener('pointerleave',()=>clearTimeout(t))});"
            + "const ta=document.getElementById('t');function send(e){post('text',{t:ta.value,enter:e?1:0}).then(()=>{if(e)ta.value=''})}"
            + "document.getElementById('a').onclick=()=>send(0);document.getElementById('e').onclick=()=>send(1);"
            + "</script></body></html>";
}
