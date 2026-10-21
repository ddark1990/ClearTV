package com.goomero.cleartv.tv;

import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import java.io.IOException;
import java.util.Locale;

/**
 * One stall watchdog for every ClearTV player (Pluto TV live, the native HLS player for Tubi and the other services,
 * and the web players for YouTube and Prime Video).
 *
 * <p>A stream can stop delivering data without the player ever failing: Media3 retries a segment or playlist load
 * that keeps timing out or trickling, and the renderers just wait (the picture freezes, the audio track starves,
 * logcat shows only AudioFlinger "BUFFER TIMEOUT"). Changing the channel fixes it because that loads the playlist
 * again from scratch. This class notices the playhead standing still while the viewer wants it to move and asks the
 * player to do that reload itself: after {@link #WAIT_MS}[0] (9 s) stuck, then with growing waits, at most
 * {@link #MAX_ATTEMPTS} times in a row; 60 s of normal playback after a recovery resets the count. A manual tune
 * resets it too. Pausing, ending or an error screen never counts as a stall.
 *
 * <p>It is a 2 s main-thread tick that reads two numbers; it allocates nothing while playback is healthy.
 */
@OptIn(markerClass = UnstableApi.class)
public final class StallWatchdog {
    private static final String TAG = "CTV.Stall";

    /** What the watchdog needs from a player. Every method runs on the main thread. */
    public interface Target {
        /** True while the viewer expects the picture to move: playing or buffering, not paused, ended or failed. */
        boolean wantsToPlay();

        /** A playhead in ms that moves forward while playing (any origin; it may jump on a seek). */
        long clockMs();

        /** The playhead has not moved for a while: reload. {@code attempt} counts 1.. {@link #MAX_ATTEMPTS}. */
        void recover(int attempt);

        /** Every attempt failed in a row; tell the viewer. The watchdog then waits for {@link #reset()}. */
        void giveUp();

        /** One-line player state for the log (buffer, loading, rendition). */
        String describe();
    }

    static final long TICK_MS = 2_000;
    /** How long the playhead must stand still before attempt n (1-based: index n-1). */
    static final long[] WAIT_MS = {9_000, 12_000, 20_000, 30_000, 45_000};
    static final int MAX_ATTEMPTS = WAIT_MS.length;
    /** Playback that runs this long after a recovery counts as healthy again (the attempt count restarts). */
    static final long HEALTHY_MS = 60_000;
    /** Smallest playhead change that counts as movement (the clock can wobble by a few ms while frozen). */
    private static final long MOVE_MS = 250;

    private final Handler main;
    private final String name;
    private final Target target;
    private boolean running, gaveUp;
    private long lastClock = Long.MIN_VALUE, lastMove, lastRecover;
    private int attempts;

    public StallWatchdog(Handler main, String name, Target target) {
        this.main = main;
        this.name = name;
        this.target = target;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            check();
            main.postDelayed(this, TICK_MS);
        }
    };

    public void start() {
        if (running) return;
        running = true;
        lastMove = SystemClock.uptimeMillis();
        lastClock = Long.MIN_VALUE;
        main.postDelayed(tick, TICK_MS);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    /** A new channel or title, picked by the viewer: forget earlier attempts and start the stall clock again. */
    public void reset() {
        attempts = 0;
        gaveUp = false;
        lastClock = Long.MIN_VALUE;
        lastMove = SystemClock.uptimeMillis();
    }

    private void check() {
        long now = SystemClock.uptimeMillis();
        if (!target.wantsToPlay()) {
            lastClock = Long.MIN_VALUE;
            lastMove = now;
            return;
        }
        long c = target.clockMs();
        if (lastClock == Long.MIN_VALUE || Math.abs(c - lastClock) >= MOVE_MS) {
            lastClock = c;
            lastMove = now;
            if (attempts > 0 && now - lastRecover >= HEALTHY_MS) {
                Log.i(TAG, name + ": healthy again after " + attempts + " recovery(ies)");
                attempts = 0;
                gaveUp = false;
            }
            return;
        }
        if (gaveUp) return;
        long stuck = now - lastMove;
        if (stuck < WAIT_MS[Math.min(attempts, WAIT_MS.length - 1)]) return;
        if (attempts >= MAX_ATTEMPTS) {
            gaveUp = true;
            Log.w(TAG, name + ": still stalled after " + attempts + " reloads, giving up [" + target.describe() + "]");
            target.giveUp();
            return;
        }
        attempts++;
        lastRecover = now;
        lastMove = now;
        lastClock = Long.MIN_VALUE;
        Log.w(TAG, name + ": stalled " + stuck / 1000 + " s, reload " + attempts + "/" + MAX_ATTEMPTS
                + " [" + target.describe() + "]");
        target.recover(attempts);
    }

    // ------------------------------------------------------------------------------- ExoPlayer helpers

    /** Wants to play: play pressed, buffering or ready, no error. */
    public static boolean exoWantsToPlay(Player p) {
        if (p == null || !p.getPlayWhenReady() || p.getPlayerError() != null) return false;
        int s = p.getPlaybackState();
        return s == Player.STATE_BUFFERING || s == Player.STATE_READY;
    }

    private static final Timeline.Window WINDOW = new Timeline.Window();

    /**
     * The playhead in the period (window offset + position). A live HLS window slides forward as the playlist
     * refreshes, so the plain window position of a frozen live stream keeps changing; the period position does not.
     */
    public static long exoClockMs(Player p) {
        if (p == null) return 0;
        Timeline t = p.getCurrentTimeline();
        long base = 0;
        int i = p.getCurrentMediaItemIndex();
        if (!t.isEmpty() && i < t.getWindowCount()) base = t.getWindow(i, WINDOW).positionInFirstPeriodUs / 1000;
        return base + p.getCurrentPosition();
    }

    public static String exoDescribe(ExoPlayer p) {
        if (p == null) return "no player";
        String[] st = {"?", "idle", "buffering", "ready", "ended"};
        androidx.media3.common.Format v = p.getVideoFormat();
        return String.format(Locale.ROOT, "%s, buffered %d ms, loading %b, live %b, video %s",
                st[Math.max(0, Math.min(4, p.getPlaybackState()))], p.getTotalBufferedDuration(), p.isLoading(),
                p.isCurrentMediaItemLive(), v == null ? "-" : (v.width + "x" + v.height + "@" + v.bitrate));
    }

    /** The live stream fell out of the playlist window (paused too long, or a long stall). */
    public static boolean isBehindLiveWindow(PlaybackException e) {
        return e != null && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW;
    }

    /**
     * Logs the load errors Media3 retries without telling the app (a segment or playlist that times out, 404s or
     * fails to parse) and audio underruns, a few a minute at most, so the next freeze leaves evidence in logcat.
     * URLs are reduced to host and file name (no query, no signed path).
     */
    public static void logLoadTrouble(ExoPlayer p, final String name) {
        p.addAnalyticsListener(new AnalyticsListener() {
            private long lastErr, lastUnderrun;

            @Override
            public void onLoadError(EventTime t, LoadEventInfo info, MediaLoadData data, IOException error, boolean wasCanceled) {
                long now = SystemClock.uptimeMillis();
                if (now - lastErr < 5_000) return;
                lastErr = now;
                Log.w(TAG, name + ": load error (retried) " + shortUri(info.uri) + " type " + data.dataType + ": "
                        + error.getClass().getSimpleName() + " " + error.getMessage());
            }

            @Override
            public void onAudioUnderrun(EventTime t, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
                long now = SystemClock.uptimeMillis();
                if (now - lastUnderrun < 10_000) return;
                lastUnderrun = now;
                Log.w(TAG, name + ": audio underrun, " + elapsedSinceLastFeedMs + " ms since last feed");
            }
        });
    }

    static String shortUri(Uri u) {
        if (u == null) return "?";
        String last = u.getLastPathSegment();
        return u.getHost() + "/…/" + (last == null ? "" : last);
    }

    /** Re-prepares an ExoPlayer at the live edge (live) or where it was (on demand): the generic recovery. */
    public static void exoReload(ExoPlayer p) {
        if (p == null) return;
        boolean live = p.isCurrentMediaItemLive();
        long pos = p.getCurrentPosition();
        p.stop();
        if (live) p.seekToDefaultPosition();
        else p.seekTo(pos);
        p.prepare();
        p.setPlayWhenReady(true);
    }
}
