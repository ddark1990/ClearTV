package com.goomero.cleartv.tv;

import android.os.SystemClock;
import android.util.Log;

/**
 * Time from Play to the first frame, step by step, in the log ({@code CTV.Start}): the catalog call, the player
 * screen, the requests a web player makes (with blocked ones marked), manifest, license and segment loads.
 * One trace at a time; it ends at the first frame or after 90 s, so it costs nothing while a video plays.
 */
public final class StartTrace {
    private static final String TAG = "CTV.Start";
    private static final long MAX_MS = 90_000;
    private static final int MAX_LINES = 400;
    private static volatile long t0;
    private static volatile String service = "";
    private static volatile boolean on;
    private static int lines;

    private StartTrace() {}

    /** A new start: {@code pressedAt} is the uptime when Play was pressed. */
    public static synchronized void begin(String serviceId, long pressedAt) {
        t0 = pressedAt > 0 ? pressedAt : SystemClock.uptimeMillis();
        service = serviceId == null ? "" : serviceId;
        // off in release builds unless a developer turns it on: adb shell setprop log.tag.CTV.Start DEBUG
        on = com.goomero.cleartv.BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG);
        lines = 0;
        mark("play pressed");
    }

    public static boolean active() {
        if (on && SystemClock.uptimeMillis() - t0 > MAX_MS) on = false;
        return on;
    }

    public static synchronized void mark(String what) {
        if (!active() || lines++ >= MAX_LINES) return;
        Log.i(TAG, service + " +" + (SystemClock.uptimeMillis() - t0) + " ms " + what);
    }

    /** A request a web player made, and whether ClearTV answered it as blocked. */
    public static void request(String url, boolean blocked) {
        if (!active()) return;
        String u = url.length() > 110 ? url.substring(0, 110) : url;
        int q = u.indexOf('?');
        mark((blocked ? "BLOCKED " : "req ") + (q > 0 ? u.substring(0, q) : u));
    }

    /**
     * Logs a native player's loads (playlist, segments, license) with their duration and size until its first
     * frame, which ends the trace.
     */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    public static void watch(androidx.media3.exoplayer.ExoPlayer p) {
        p.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override
            public void onLoadCompleted(EventTime t, androidx.media3.exoplayer.source.LoadEventInfo info,
                                        androidx.media3.exoplayer.source.MediaLoadData d) {
                if (!active()) return;
                String kind = d.dataType == androidx.media3.common.C.DATA_TYPE_MANIFEST ? "playlist"
                        : d.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA ? "segment"
                        : d.dataType == androidx.media3.common.C.DATA_TYPE_DRM ? "license" : "load " + d.dataType;
                String rate = d.trackFormat != null && d.trackFormat.bitrate > 0 ? " @" + d.trackFormat.bitrate / 1000 + " kbps" : "";
                mark(kind + rate + " took " + info.loadDurationMs + " ms, " + info.bytesLoaded / 1024 + " KB ("
                        + (info.uri.getHost() == null ? "" : info.uri.getHost()) + ")");
            }

            @Override
            public void onRenderedFirstFrame(EventTime t, Object output, long renderTimeMs) {
                end("");
            }
        });
    }

    /** The first frame is on screen: logs the total and ends the trace. */
    public static synchronized long end(String what) {
        if (!on) return -1;
        long ms = SystemClock.uptimeMillis() - t0;
        mark("FIRST FRAME " + what);
        on = false;
        return ms;
    }
}
