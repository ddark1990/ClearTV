package com.goomero.cleartv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import java.util.Locale;

/**
 * Video quality for every ClearTV player, remembered per service. AUTO plays the best rendition the stream and the
 * TV allow (capped at the display's resolution) and only steps down when the connection cannot keep up; the fixed
 * choices lock to the best rendition at or below that height. Media3 players get it through {@link #build} and
 * {@link #apply}; the web players (YouTube embed, Prime Video) read {@link #maxHeight} and pass it to the page.
 */
@OptIn(markerClass = UnstableApi.class)
public final class Quality {
    private static final String TAG = "CTV.Quality";
    public static final int AUTO = 0, P1080 = 1, P720 = 2, P480 = 3;
    public static final String[] LABELS = {"Auto (best for this TV)", "1080p", "720p", "480p"};
    private static final int[] HEIGHTS = {0, 1080, 720, 480};
    /**
     * The bandwidth guess before anything is measured: the first segment comes from a mid rendition (small, so
     * the picture starts sooner) and the selection steps up as soon as real throughput is known.
     */
    private static final long START_BITRATE = 2_500_000L;

    private Quality() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("quality", Context.MODE_PRIVATE);
    }

    public static int get(Context c, String service) {
        int q = sp(c).getInt(service, AUTO);
        return q < AUTO || q > P480 ? AUTO : q;
    }

    public static void set(Context c, String service, int q) {
        sp(c).edit().putInt(service, q).apply();
    }

    /** Short label for the overlay ("Auto", "720p"). */
    public static String shortLabel(int q) {
        return q == AUTO ? "Auto" : LABELS[q];
    }

    /** Picker rows with a mark on the current choice. */
    public static String[] rows(int current) {
        String[] r = new String[LABELS.length];
        for (int i = 0; i < r.length; i++) r[i] = (i == current ? "✓  " : "     ") + LABELS[i];
        return r;
    }

    /** The display's physical size in pixels ({width, height}), at least 1280x720. */
    public static int[] display(Context c) {
        int w = 1920, h = 1080;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display d = wm.getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode m = d.getMode();
                w = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                h = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
            } else {
                android.graphics.Point p = new android.graphics.Point();
                d.getRealSize(p);
                w = Math.max(p.x, p.y);
                h = Math.min(p.x, p.y);
            }
        }
        return new int[]{Math.max(w, 1280), Math.max(h, 720)};
    }

    /** Height limit in pixels for a choice: the display's height for AUTO. */
    public static int maxHeight(Context c, int q) {
        int dh = display(c)[1];
        return q == AUTO ? dh : Math.min(dh, HEIGHTS[q]);
    }

    /**
     * An ExoPlayer that starts on a mid rendition, steps up once 3 s are buffered (Media3's default waits for
     * 10 s), and keeps to the chosen quality.
     */
    public static ExoPlayer build(Context c, MediaSource.Factory media, LoadControl load, int q) {
        DefaultBandwidthMeter meter = new DefaultBandwidthMeter.Builder(c).setInitialBitrateEstimate(START_BITRATE).build();
        DefaultTrackSelector selector = new DefaultTrackSelector(c, new AdaptiveTrackSelection.Factory(
                2_000, 25_000, 8_000, 0.85f));
        // Tubi encodes its top rendition in another H.264 profile (High 4.0 next to Main 3.0). Media3 keeps
        // renditions whose decoder support differs out of one adaptive set, so Auto stayed on 240p/360p and never
        // reached 480p. All renditions share the same hardware decoder here, so let them adapt together.
        selector.setParameters(selector.buildUponParameters()
                .setAllowVideoMixedDecoderSupportAdaptiveness(true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true));
        lastMeter = meter;
        ExoPlayer p = new ExoPlayer.Builder(c, new DefaultRenderersFactory(c), media)
                .setTrackSelector(selector)
                .setBandwidthMeter(meter)
                .setLoadControl(load)
                .build();
        apply(c, p, q);
        return p;
    }

    /**
     * Applies a choice to a running player. A manual height pins the rendition to that height (min and max, so
     * the selector cannot stay on a lower one), Auto clears the pin and adapts up to the display. The buffer still
     * holds up to 30 s of the old rendition, so a re-seek to the current position flushes it: the new rendition
     * shows within a second or two instead of after the buffer drains (why "1080p did nothing" before 0.1.6).
     */
    public static void apply(Context c, ExoPlayer p, int q) {
        apply(c, p, q, false);
    }

    public static void apply(Context c, ExoPlayer p, int q, boolean flush) {
        int[] d = display(c);
        int maxH = maxHeight(c, q);
        int maxW = q == AUTO ? d[0] : Math.round(maxH * 16f / 9f);
        TrackSelectionParameters.Builder b = p.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMaxVideoSize(maxW, maxH)
                .setViewportSize(d[0], d[1], false)
                .setForceHighestSupportedBitrate(q != AUTO);
        // a manual pick keeps renditions within ~10% of its height (streams label 1080p as 1072 or 1088 lines)
        // streams whose variants carry no RESOLUTION (Pluto's) pass any size limit: a bitrate cap per choice
        // keeps a manual pick meaningful there too
        if (q == AUTO) b.setMinVideoSize(0, 0).setMaxVideoBitrate(Integer.MAX_VALUE);
        else b.setMinVideoSize(0, Math.round(maxH * 0.9f)).setMaxVideoBitrate(maxH >= 1080 ? Integer.MAX_VALUE : maxH >= 720 ? 4_500_000 : 2_000_000);
        p.setTrackSelectionParameters(b.build());
        if (flush && p.getPlaybackState() == androidx.media3.common.Player.STATE_READY) {
            if (p.isCurrentMediaItemLive()) p.seekToDefaultPosition();
            else p.seekTo(p.getCurrentPosition());
        }
        Log.i(TAG, "apply " + shortLabel(q) + " max " + maxW + "x" + maxH + (flush ? " (flushed)" : ""));
    }

    /** The playing video rendition, or null. */
    public static Format selectedVideo(ExoPlayer p) {
        if (p == null) return null;
        // the rendition being decoded now; with an adaptive set every member counts as "selected" in Tracks
        Format now = p.getVideoFormat();
        if (now != null) return now;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) if (g.isTrackSelected(i)) return g.getTrackFormat(i);
        }
        return null;
    }

    /** Highest video rendition the stream offers, or null. */
    public static Format bestVideo(ExoPlayer p) {
        Format best = null;
        if (p == null) return null;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                if (best == null || f.height > best.height || (f.height == best.height && f.bitrate > best.bitrate)) best = f;
            }
        }
        return best;
    }

    /** Every video rendition with its codec and whether this TV can play it (and adapt to it), for the log. */
    public static String ladder(ExoPlayer p) {
        StringBuilder sb = new StringBuilder();
        if (p == null) return "";
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            sb.append(g.isAdaptiveSupported() ? "adaptive" : "fixed");
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                sb.append(String.format(Locale.ROOT, " | %dp %.1fM %s %s%s", f.height, f.bitrate / 1e6, f.codecs,
                        g.isTrackSupported(i) ? "ok" : "unsupported", g.isTrackSelected(i) ? " *" : ""));
            }
        }
        return sb.toString();
    }

    /** "1920×1080 · 5.1 Mbps". */
    public static String describe(Format f) {
        if (f == null || f.width <= 0) return "";
        String s = f.width + "×" + f.height;
        if (f.bitrate > 0) s += String.format(Locale.ROOT, " · %.1f Mbps", f.bitrate / 1e6);
        return s;
    }

    /** Overlay line: "Quality Auto · 1920×1080 · 5.1 Mbps". */
    public static String overlayLine(int q, String playing) {
        return "Quality " + shortLabel(q) + (playing == null || playing.isEmpty() ? "" : " · " + playing);
    }

    /** The bandwidth meter of the newest Media3 player, for the log line only. */
    private static DefaultBandwidthMeter lastMeter;

    public static void log(String service, Format selected, Format best) {
        DefaultBandwidthMeter m = lastMeter;
        Log.i(TAG, service + " playing " + describe(selected) + " (best offered " + describe(best) + ")"
                + (m == null ? "" : String.format(Locale.ROOT, ", estimate %.1f Mbps", m.getBitrateEstimate() / 1e6)));
    }
}
