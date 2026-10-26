package com.goomero.cleartv.tv;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.drm.ExoMediaDrm;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback;
import androidx.media3.exoplayer.drm.MediaDrmCallback;
import androidx.media3.exoplayer.drm.MediaDrmCallbackException;
import androidx.media3.exoplayer.hls.HlsDataSourceFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Network hiccups a viewer should never see, shared by the native players (Pluto TV, and Tubi and the others in
 * {@code PlayerActivity}):
 * <ul>
 *   <li>Short timeouts, so a request that hangs is given up and sent again in about 2.5 s instead of 15 s:
 *   playlists (small, refreshed every few seconds) connect 2.5 s / read 2.5 s; segments and keys connect 3 s and
 *   may sit idle 5 s between reads (a slow but moving download is not cut off; the timeout is per read). Each
 *   retry of the same load waits longer (playlists 5 s then 10 s, segments 8 s then 12 s), so a server that is
 *   slow rather than stuck still answers.</li>
 *   <li>{@link Policy}: a network error (timeout, reset, refused, DNS blip, HTTP 5xx) is retried at once, then
 *   after 0.25 / 0.5 / 0.75 / 1 s, up to 8 times for playlists and 6 for segments (Media3: 3, after 0 / 1 / 2 s).
 *   Other errors keep Media3's handling (404s switch rendition, parser errors are not retried).</li>
 *   <li>Optionally a deeper live start ({@code liveOffsetS} behind the playlist's end, through an
 *   {@code EXT-X-START} the player reads as if the server sent it), for a bigger cushion of media already loaded
 *   when a playlist refresh is late. Playback speed is never adjusted.</li>
 * </ul>
 * Connections: Android's {@code HttpURLConnection} (behind {@link DefaultHttpDataSource}) keeps finished
 * connections alive in its process-wide pool and reuses them per host, so the many small playlist and segment
 * requests do not each pay a new TCP and TLS handshake.
 */
@OptIn(markerClass = UnstableApi.class)
public final class FastLoad {
    /** Connect / read timeouts for the first try, then for each retry (a slow but healthy server gets through). */
    static final int[][] PLAYLIST_MS = {{2_500, 2_500}, {4_000, 5_000}, {8_000, 10_000}};
    static final int[][] MEDIA_MS = {{3_000, 5_000}, {5_000, 8_000}, {8_000, 12_000}};

    private FastLoad() {}

    /**
     * An HLS source factory with the short timeouts, the fast-retry policy and, when {@code liveOffsetS > 0}, the
     * deeper live start.
     */
    public static HlsMediaSource.Factory hls(String ua, @Nullable Map<String, String> headers, int liveOffsetS) {
        final DataSource.Factory[] playlists = tiers(ua, headers, PLAYLIST_MS);
        final DataSource.Factory[] media = tiers(ua, headers, MEDIA_MS);
        HlsMediaSource.Factory f = new HlsMediaSource.Factory(new HlsDataSourceFactory() {
            @Override
            public DataSource createDataSource(int dataType) {
                return new Escalating(dataType == C.DATA_TYPE_MANIFEST ? playlists : media);
            }
        });
        f.setLoadErrorHandlingPolicy(new Policy());
        if (liveOffsetS > 0) f.setPlaylistParserFactory(new LiveStart(liveOffsetS));
        return f;
    }

    /**
     * A DASH source factory with the same short timeouts and fast retries. With a {@code licenseUrl} the stream is
     * Widevine-protected: licenses are requested from that URL with {@code licenseHeaders} (for example the
     * service's bearer token). The license answer's outcome is logged (HTTP status only, never the token).
     */
    public static DashMediaSource.Factory dash(String ua, @Nullable Map<String, String> headers,
                                               @Nullable String licenseUrl, @Nullable Map<String, String> licenseHeaders,
                                               final String tag) {
        final DataSource.Factory[] manifests = tiers(ua, headers, PLAYLIST_MS);
        final DataSource.Factory[] media = tiers(ua, headers, MEDIA_MS);
        DashMediaSource.Factory f = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(new DataSource.Factory() {
                    @Override
                    public DataSource createDataSource() {
                        return new Escalating(media);
                    }
                }),
                new DataSource.Factory() {
                    @Override
                    public DataSource createDataSource() {
                        return new Escalating(manifests);
                    }
                });
        f.setLoadErrorHandlingPolicy(new Policy());
        if (licenseUrl != null && !licenseUrl.isEmpty()) {
            final HttpMediaDrmCallback http = new HttpMediaDrmCallback(licenseUrl, http(ua, null, 8_000, 10_000));
            if (licenseHeaders != null) {
                for (Map.Entry<String, String> e : licenseHeaders.entrySet()) http.setKeyRequestProperty(e.getKey(), e.getValue());
            }
            MediaDrmCallback logged = new MediaDrmCallback() {
                @Override
                public byte[] executeProvisionRequest(java.util.UUID uuid, ExoMediaDrm.ProvisionRequest request) throws MediaDrmCallbackException {
                    return http.executeProvisionRequest(uuid, request);
                }

                @Override
                public byte[] executeKeyRequest(java.util.UUID uuid, ExoMediaDrm.KeyRequest request) throws MediaDrmCallbackException {
                    try {
                        byte[] b = http.executeKeyRequest(uuid, request);
                        Log.i("CTV.Drm", tag + " license: HTTP 200, " + b.length + " bytes");
                        return b;
                    } catch (MediaDrmCallbackException e) {
                        Throwable c = e.getCause();
                        String why = c instanceof HttpDataSource.InvalidResponseCodeException
                                ? "HTTP " + ((HttpDataSource.InvalidResponseCodeException) c).responseCode
                                : String.valueOf(c);
                        Log.w("CTV.Drm", tag + " license failed: " + why);
                        throw e;
                    }
                }
            };
            final DefaultDrmSessionManager drm = new DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(logged);
            f.setDrmSessionManagerProvider(new DrmSessionManagerProvider() {
                @Override
                public DrmSessionManager get(androidx.media3.common.MediaItem item) {
                    return drm;
                }
            });
        }
        return f;
    }

    private static DataSource.Factory[] tiers(String ua, @Nullable Map<String, String> headers, int[][] ms) {
        DataSource.Factory[] t = new DataSource.Factory[ms.length];
        for (int i = 0; i < ms.length; i++) t[i] = http(ua, headers, ms[i][0], ms[i][1]);
        return t;
    }

    /**
     * Uses the short timeouts first and longer ones after each failure on this source (Media3 retries a load on the
     * same source), back to the short ones once a load completes.
     */
    static final class Escalating implements DataSource {
        private final DataSource[] tiers;
        private DataSource current;
        private int failures;

        Escalating(DataSource.Factory[] f) {
            tiers = new DataSource[f.length];
            for (int i = 0; i < f.length; i++) tiers[i] = f[i].createDataSource();
            current = tiers[0];
        }

        @Override
        public void addTransferListener(androidx.media3.datasource.TransferListener l) {
            for (DataSource d : tiers) d.addTransferListener(l);
        }

        @Override
        public long open(androidx.media3.datasource.DataSpec spec) throws IOException {
            current = tiers[Math.min(failures, tiers.length - 1)];
            try {
                return current.open(spec);
            } catch (IOException e) {
                failures++;
                throw e;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int n = current.read(buffer, offset, length);
                if (n == C.RESULT_END_OF_INPUT) failures = 0;
                return n;
            } catch (IOException e) {
                failures++;
                throw e;
            }
        }

        @Nullable
        @Override
        public Uri getUri() {
            return current.getUri();
        }

        @Override
        public Map<String, java.util.List<String>> getResponseHeaders() {
            return current.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            current.close();
        }
    }

    private static DefaultHttpDataSource.Factory http(String ua, @Nullable Map<String, String> headers, int connectMs, int readMs) {
        DefaultHttpDataSource.Factory h = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(connectMs)
                .setReadTimeoutMs(readMs);
        if (headers != null) h.setDefaultRequestProperties(headers);
        return h;
    }

    /** Fast retries for network hiccups; everything else as Media3 does it. */
    public static final class Policy extends DefaultLoadErrorHandlingPolicy {
        @Override
        public long getRetryDelayMsFor(LoadErrorInfo info) {
            long d = super.getRetryDelayMsFor(info);
            if (d == C.TIME_UNSET || !isHiccup(info.exception)) return d;
            return info.errorCount <= 1 ? 0 : Math.min(250L * (info.errorCount - 1), 1_000L);
        }

        @Override
        public int getMinimumLoadableRetryCount(int dataType) {
            return dataType == C.DATA_TYPE_MANIFEST ? 8 : 6;
        }
    }

    /** A timeout, a dropped or refused connection, a DNS blip, or a server error: worth asking again right away. */
    static boolean isHiccup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode >= 500;
            }
            if (t instanceof InterruptedIOException || t instanceof SocketException
                    || t instanceof UnknownHostException || t instanceof javax.net.ssl.SSLException) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * Reads each live media playlist with an {@code EXT-X-START:TIME-OFFSET=-N} in it (unless it has one already),
     * so a tune, a reload and a jump back to live all start N seconds behind the newest segment.
     */
    static final class LiveStart implements HlsPlaylistParserFactory {
        private final HlsPlaylistParserFactory base = new DefaultHlsPlaylistParserFactory();
        private final String tag;

        LiveStart(int offsetS) {
            tag = "#EXT-X-START:TIME-OFFSET=-" + offsetS + "\n";
        }

        @Override
        public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
            return wrap(base.createPlaylistParser());
        }

        @Override
        public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(HlsMultivariantPlaylist multivariant,
                                                                         @Nullable HlsMediaPlaylist previous) {
            return wrap(base.createPlaylistParser(multivariant, previous));
        }

        private ParsingLoadable.Parser<HlsPlaylist> wrap(final ParsingLoadable.Parser<HlsPlaylist> p) {
            return new ParsingLoadable.Parser<HlsPlaylist>() {
                @Override
                public HlsPlaylist parse(Uri uri, InputStream in) throws IOException {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
                    String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    int nl = s.indexOf('\n');
                    if (nl > 0 && s.contains("#EXTINF") && !s.contains("#EXT-X-ENDLIST") && !s.contains("#EXT-X-START")) {
                        s = s.substring(0, nl + 1) + tag + s.substring(nl + 1);
                    }
                    return p.parse(uri, new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
                }
            };
        }
    }

    // ------------------------------------------------------------------------------------------- live log

    private static final Timeline.Window W = new Timeline.Window();

    /**
     * One line for the log ({@code CTV.Live}): how far behind the newest segment playback is and how much media
     * is loaded ahead of it. Main thread.
     */
    public static void logLive(ExoPlayer p, String who) {
        if (p == null || !p.isCurrentMediaItemLive()) return;
        Timeline t = p.getCurrentTimeline();
        if (t.isEmpty()) return;
        t.getWindow(p.getCurrentMediaItemIndex(), W);
        long dur = W.getDurationMs();
        if (dur == C.TIME_UNSET) return;
        long pos = p.getCurrentPosition();
        Log.i("CTV.Live", String.format(Locale.ROOT, "%s behind newest %.1f s, buffered %.1f s, window %.1f s, %s",
                who, (dur - pos) / 1000.0, (p.getBufferedPosition() - pos) / 1000.0, dur / 1000.0,
                p.getPlaybackState() == Player.STATE_READY ? "ready" : p.getPlaybackState() == Player.STATE_BUFFERING ? "buffering" : "idle"));
    }

    // ------------------------------------------------------------------------------------- quiet buffering

    /**
     * The loading hint for a player that is already playing: a rebuffer shorter than {@link #HINT_AFTER_MS} shows
     * nothing (the picture just holds for a moment), a longer one shows the hint. Every rebuffer is logged with its
     * length and whether the hint showed ({@code CTV.Live}), plus a live-position line every 30 s.
     * A start (tune, reload, new video) is not quiet: {@link #onState} returns true and the caller shows its hint.
     */
    public static final class QuietBuffering {
        public static final long HINT_AFTER_MS = 2_500;
        private final android.os.Handler main;
        private final String who;
        private final Runnable show;
        private ExoPlayer player;
        private boolean started;
        private long since;
        private boolean shown;
        private final Runnable showRun = new Runnable() {
            @Override
            public void run() {
                shown = true;
                show.run();
            }
        };
        private final Runnable logRun = new Runnable() {
            @Override
            public void run() {
                logLive(player, who);
                main.postDelayed(this, 30_000);
            }
        };

        public QuietBuffering(android.os.Handler main, String who, Runnable show) {
            this.main = main;
            this.who = who;
            this.show = show;
        }

        public void attach(ExoPlayer p) {
            player = p;
            main.removeCallbacks(logRun);
            main.postDelayed(logRun, 30_000);
        }

        public void release() {
            main.removeCallbacks(logRun);
            main.removeCallbacks(showRun);
            player = null;
        }

        /** A new start (tune, reload, seek by the viewer): the next buffering shows the hint at once. */
        public void reset() {
            started = false;
            since = 0;
            main.removeCallbacks(showRun);
        }

        /** @return true when the caller should show its loading hint now */
        public boolean onState(int state) {
            long now = android.os.SystemClock.uptimeMillis();
            if (state == Player.STATE_BUFFERING) {
                if (!started) return true;
                if (since == 0) {
                    since = now;
                    shown = false;
                    main.postDelayed(showRun, HINT_AFTER_MS);
                }
                return false;
            }
            main.removeCallbacks(showRun);
            if (state == Player.STATE_READY) {
                if (since > 0) {
                    Log.i("CTV.Live", who + " rebuffer " + (now - since) + " ms, hint " + (shown ? "shown" : "hidden"));
                    logLive(player, who);
                }
                started = true;
            }
            since = 0;
            return false;
        }
    }
}
