package com.goomero.cleartv.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

import com.goomero.cleartv.Ui;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ClearTV's player for the native service screens: one HLS stream (or a Widevine DASH stream) full screen in
 * Media3 at the best quality the stream and TV allow ({@link Quality}), with the shared overlay and remote
 * behaviour of {@link PlayerChrome}
 * (options: quality, captions). If the stream fails and the service has a web player for the title, that
 * opens instead (inside ClearTV).
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    private static final String TAG = "CTV.Player";
    public static final String EXTRA_HLS = "hls", EXTRA_UA = "ua", EXTRA_HEADERS = "headers", EXTRA_TITLE = "title",
            EXTRA_SUBTITLE = "subtitle", EXTRA_LIVE = "live", EXTRA_ACCENT = "accent", EXTRA_SERVICE = "service",
            EXTRA_WEB = "web", EXTRA_WEB_TV = "webTv", EXTRA_DOMAINS = "domains", EXTRA_SERVICE_ID = "serviceId",
            EXTRA_PRESSED = "pressed", EXTRA_CAPTIONS = "captions", EXTRA_CAPTIONS_LABEL = "captionsLabel",
            EXTRA_DASH = "dash", EXTRA_DRM_URL = "drmUrl", EXTRA_DRM_HEADERS = "drmHeaders";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private SurfaceView surface;
    private Box box;
    private TextView status;
    private PlayerChrome chrome;
    private boolean live, triedWeb, captionsOn;
    private StallWatchdog watchdog;
    private int blwCount;
    private long blwSince;
    private int accent;
    private String serviceId = "";
    private int q;
    private String loggedFormat = "";
    private long resumeMs;
    /** Captions from a caption file next to the stream (Tubi): cues sorted by start, drawn by the chrome. */
    private long[] cueStart, cueEnd;
    private String[] cueText;
    private String captionsLabel = "";
    private int lastCue = -1;
    private final Runnable cueTick = new Runnable() {
        @Override
        public void run() {
            showFileCue();
            main.postDelayed(this, 250);
        }
    };

    public static Intent intent(Context c, Source s, Source.Playback p) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra(EXTRA_HLS, p.hls);
        i.putExtra(EXTRA_DASH, p.dash);
        if (p.drmLicenseUrl != null) i.putExtra(EXTRA_DRM_URL, p.drmLicenseUrl);
        if (p.drmHeaders != null) i.putExtra(EXTRA_DRM_HEADERS, new HashMap<>(p.drmHeaders));
        i.putExtra(EXTRA_UA, p.userAgent);
        if (p.headers != null) i.putExtra(EXTRA_HEADERS, new HashMap<>(p.headers));
        i.putExtra(EXTRA_TITLE, p.title);
        i.putExtra(EXTRA_SUBTITLE, p.subtitle);
        i.putExtra(EXTRA_LIVE, p.live);
        i.putExtra(EXTRA_ACCENT, Ui.serviceAccent(s.accent()));
        i.putExtra(EXTRA_SERVICE, s.name());
        i.putExtra(EXTRA_SERVICE_ID, s.id());
        i.putExtra(EXTRA_WEB, p.webUrl);
        i.putExtra(EXTRA_WEB_TV, p.webTvUa);
        i.putExtra(EXTRA_DOMAINS, s.webDomains());
        if (p.captionsUrl != null) {
            i.putExtra(EXTRA_CAPTIONS, p.captionsUrl);
            i.putExtra(EXTRA_CAPTIONS_LABEL, p.captionsLabel);
        }
        return i;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        accent = in.getIntExtra(EXTRA_ACCENT, Ui.ACCENT);
        live = in.getBooleanExtra(EXTRA_LIVE, false);
        serviceId = in.getStringExtra(EXTRA_SERVICE_ID) == null ? "" : in.getStringExtra(EXTRA_SERVICE_ID);
        q = Quality.get(this, serviceId);
        StartTrace.mark("player screen");
        buildUi(in.getStringExtra(EXTRA_SERVICE), in.getStringExtra(EXTRA_TITLE), in.getStringExtra(EXTRA_SUBTITLE));
        createPlayer(in);
    }

    private void buildUi(String service, String title, String subtitle) {
        Context c = this;
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(0xFF000000);
        box = new Box(c);
        surface = new SurfaceView(c);
        box.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = Ui.kicker(c, "Loading…", 13, Ui.V_MUTED);
        status.setPadding(Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1));
        status.setBackground(Ui.rounded(0xCC060809, Ui.dp(c, Ui.RADIUS)));
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        chrome = new PlayerChrome(c, root, accent, new PlayerChrome.Target() {
            @Override public boolean isLive() {
                return live || (player != null && player.isCurrentMediaItemLive());
            }
            @Override public long positionMs() {
                return player == null ? 0 : player.getCurrentPosition();
            }
            @Override public long durationMs() {
                long d = player == null ? C.TIME_UNSET : player.getDuration();
                return d == C.TIME_UNSET ? 0 : d;
            }
            @Override public boolean isPlaying() {
                return player != null && player.getPlayWhenReady();
            }
            @Override public void setPlaying(boolean play) {
                if (player != null) player.setPlayWhenReady(play);
            }
            @Override public void seekTo(long ms) {
                if (player != null) player.seekTo(ms);
            }
            @Override public void options(java.util.List<String> labels, java.util.List<Runnable> actions) {
                labels.add("Quality: " + Quality.shortLabel(q) + (loggedFormat.isEmpty() ? "" : " · " + loggedFormat));
                actions.add(new Runnable() {
                    @Override public void run() {
                        pickQuality();
                    }
                });
                if (hasCaptions()) {
                    labels.add(captionsOn ? "Captions: On · " + captionsName() : "Captions: Off");
                    actions.add(new Runnable() {
                        @Override public void run() {
                            setCaptions(!captionsOn);
                        }
                    });
                }
            }
            @Override public void exit() {
                finish();
            }
        });
        chrome.setInfo(service, title, subtitle);
        chrome.setQuality(Quality.overlayLine(q, ""));
        // progress for Continue watching, the next episode with its countdown, Skip intro from the markers
        resumeMs = Launcher.attach(this, chrome, getIntent(), serviceId);
        setContentView(root);
    }

    private boolean hasTextTracks() {
        if (player == null) return false;
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_TEXT && g.length > 0) return true;
        }
        return false;
    }

    /** A caption file (loaded) or a text track in the stream. */
    private boolean hasCaptions() {
        return cueStart != null || hasTextTracks();
    }

    private String captionsName() {
        return captionsLabel == null || captionsLabel.isEmpty() ? "English" : captionsLabel;
    }

    /** Turns captions on or off (remembered per service) and updates the CC chip in the controls. */
    private void setCaptions(boolean on) {
        captionsOn = on;
        getSharedPreferences("svc_" + serviceId, MODE_PRIVATE).edit().putBoolean("cc_on", on).apply();
        // the stream's own text track only plays when there is no caption file
        if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on || cueStart != null).build());
        }
        lastCue = -1;
        if (!on) chrome.showCaption(null);
        updateCcChip();
    }

    private void updateCcChip() {
        if (!hasCaptions()) {
            chrome.setCaptions(null, null);
            return;
        }
        chrome.setCaptions(captionsOn ? captionsName() : "", new Runnable() {
            @Override
            public void run() {
                setCaptions(!captionsOn);
            }
        });
    }

    /** Fetches and parses the caption file (SRT or WebVTT) in the background. */
    private void loadCaptionFile(final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = new String(Http.get(url, null), java.nio.charset.StandardCharsets.UTF_8);
                    // SRT writes 00:01:02,345; WebVTT 00:01:02.345
                    body = body.replaceAll("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{1,3})", "$1.$2");
                    java.util.List<long[]> times = new java.util.ArrayList<>();
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    WebCaptions.parseVtt(body, times, lines);
                    final int n = times.size();
                    if (n == 0) return;
                    Integer[] order = new Integer[n];
                    for (int i = 0; i < n; i++) order[i] = i;
                    final java.util.List<long[]> t = times;
                    java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                        @Override
                        public int compare(Integer a, Integer b) {
                            return Long.compare(t.get(a)[0], t.get(b)[0]);
                        }
                    });
                    final long[] st = new long[n], en = new long[n];
                    final String[] tx = new String[n];
                    for (int i = 0; i < n; i++) {
                        st[i] = times.get(order[i])[0];
                        en[i] = times.get(order[i])[1];
                        tx[i] = lines.get(order[i]);
                    }
                    Log.i(TAG, serviceId + " captions: " + n + " cues (" + captionsName() + ")");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            cueStart = st;
                            cueEnd = en;
                            cueText = tx;
                            setCaptions(captionsOn);
                            main.removeCallbacks(cueTick);
                            main.post(cueTick);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "captions file: " + e.getMessage());
                }
            }
        }, "ctv-captions").start();
    }

    /** The caption-file cue at the playing position, or none. */
    private void showFileCue() {
        if (cueStart == null || player == null) return;
        if (!captionsOn) {
            lastCue = -1;
            return;
        }
        long pos = player.getCurrentPosition();
        int lo = 0, hi = cueStart.length - 1, at = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cueStart[mid] <= pos) {
                at = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        int show = at >= 0 && pos < cueEnd[at] ? at : -1;
        if (show == lastCue) return;
        lastCue = show;
        chrome.showCaption(show >= 0 ? cueText[show] : null);
    }

    private void createPlayer(Intent in) {
        String url = in.getStringExtra(EXTRA_HLS);
        if (url == null) {
            fail();
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) in.getSerializableExtra(EXTRA_HEADERS);
        String ua = in.getStringExtra(EXTRA_UA);
        // about 12 MB of media at most: plenty for smooth HD, a fraction of the default buffer's memory
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(12_000, 30_000, 1_500, 3_000)
                .setTargetBufferBytes(12 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        // short timeouts with instant retries (tv/FastLoad); live streams keep their own start position here
        @SuppressWarnings("unchecked")
        Map<String, String> drmHeaders = (Map<String, String>) in.getSerializableExtra(EXTRA_DRM_HEADERS);
        String agent = ua != null ? ua : Http.DESKTOP_UA;
        // a DASH manifest (Paramount+ episodes and films, Widevine) or an HLS playlist (everything else)
        androidx.media3.exoplayer.source.MediaSource.Factory media = in.getBooleanExtra(EXTRA_DASH, false)
                ? FastLoad.dash(agent, headers, in.getStringExtra(EXTRA_DRM_URL), drmHeaders, serviceId)
                : FastLoad.hls(agent, headers, 0);
        player = Quality.build(this, media, load, q);
        quiet = new FastLoad.QuietBuffering(main, serviceId, new Runnable() {
            @Override
            public void run() {
                showStatus("Loading…");
            }
        });
        quiet.attach(player);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguage("en")
                .setPreferredAudioLanguage("en")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build());
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                // a short rebuffer during playback shows nothing; the start, a seek or a longer one shows "Loading"
                boolean now = quiet.onState(s);
                if (s == Player.STATE_READY) status.setVisibility(View.GONE);
                else if (s == Player.STATE_BUFFERING && now) showStatus("Loading…");
                else if (s == Player.STATE_ENDED) chrome.onEnded();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                chrome.onPlayingChanged();
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo from, Player.PositionInfo to, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) quiet.reset();
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "playback error " + e.getErrorCodeName() + ": " + e.getMessage());
                // a live stream that fell out of its window: back to the live edge (a few times a minute at most)
                if (StallWatchdog.isBehindLiveWindow(e) && player != null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - blwSince > 60_000) {
                        blwSince = now;
                        blwCount = 0;
                    }
                    if (++blwCount <= 3) {
                        Log.i(TAG, "behind the live window: back to the live edge");
                        quiet.reset();
                        player.seekToDefaultPosition();
                        player.prepare();
                        player.setPlayWhenReady(true);
                        return;
                    }
                }
                fail();
            }

            @Override
            public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) box.setAspect(v.width * v.pixelWidthHeightRatio / v.height);
                updateQuality();
            }

            @Override
            public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                updateQuality();
                updateCcChip();
            }

            @Override
            public void onCues(CueGroup cues) {
                if (cueStart != null || !captionsOn) return; // the caption file draws its own lines
                StringBuilder sb = new StringBuilder();
                for (Cue cue : cues.cues) if (cue.text != null) sb.append(sb.length() > 0 ? "\n" : "").append(cue.text);
                chrome.showCaption(sb);
            }
        });
        captionsOn = getSharedPreferences("svc_" + serviceId, MODE_PRIVATE).getBoolean("cc_on", false);
        if (captionsOn) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        captionsLabel = in.getStringExtra(EXTRA_CAPTIONS_LABEL);
        String capUrl = in.getStringExtra(EXTRA_CAPTIONS);
        if (capUrl != null) loadCaptionFile(capUrl);
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            private long lastLog;

            @Override
            public void onBandwidthEstimate(EventTime t, int loadMs, long bytes, long bitrate) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastLog < 10_000) return;
                lastLog = now;
                Log.i("CTV.Quality", String.format(Locale.ROOT, "%s estimate %.1f Mbps, playing %s", serviceId,
                        bitrate / 1e6, Quality.describe(Quality.selectedVideo(player))));
            }
        });
        StartTrace.watch(player);
        StallWatchdog.logLoadTrouble(player, serviceId);
        watchdog = new StallWatchdog(main, serviceId, new StallWatchdog.Target() {
            @Override
            public boolean wantsToPlay() {
                return StallWatchdog.exoWantsToPlay(player);
            }

            @Override
            public long clockMs() {
                return StallWatchdog.exoClockMs(player);
            }

            @Override
            public void recover(int attempt) {
                quiet.reset();
                showStatus("Loading…");
                StallWatchdog.exoReload(player);
            }

            @Override
            public void giveUp() {
                fail();
            }

            @Override
            public String describe() {
                return StallWatchdog.exoDescribe(player);
            }
        });
        watchdog.start();
        player.setMediaItem(MediaItem.fromUri(url));
        if (resumeMs > 0 && !live) player.seekTo(resumeMs);
        player.prepare();
        player.setPlayWhenReady(true);
        chrome.show();
        chrome.hidePausedMark();
    }

    /** The stream failed: the service's web player when it has one, otherwise say so. */
    private void fail() {
        String web = getIntent().getStringExtra(EXTRA_WEB);
        if (web != null && !triedWeb) {
            triedWeb = true;
            Intent i = WebActivity.player(this, web, getIntent().getBooleanExtra(EXTRA_WEB_TV, false),
                    getIntent().getStringArrayExtra(EXTRA_DOMAINS), accent, getIntent().getStringExtra(EXTRA_SERVICE),
                    serviceId, getIntent().getStringExtra(EXTRA_TITLE), getIntent().getStringExtra(EXTRA_SUBTITLE), 0);
            // the web player keeps the library item, the queue, the resume point and the markers
            for (String k : new String[]{Launcher.EXTRA_ITEM, Launcher.EXTRA_QUEUE}) {
                if (getIntent().hasExtra(k)) i.putExtra(k, getIntent().getStringExtra(k));
            }
            i.putExtra(Launcher.EXTRA_RESUME, getIntent().getLongExtra(Launcher.EXTRA_RESUME, 0));
            if (getIntent().hasExtra(Launcher.EXTRA_MARKERS)) i.putExtra(Launcher.EXTRA_MARKERS, getIntent().getLongArrayExtra(Launcher.EXTRA_MARKERS));
            startActivity(i);
            finish();
            return;
        }
        showStatus("This video can't play right now.");
    }

    /** Overlay quality line and a log line whenever the playing rendition changes. */
    private void updateQuality() {
        androidx.media3.common.Format f = Quality.selectedVideo(player);
        String d = Quality.describe(f);
        chrome.setQuality(Quality.overlayLine(q, d));
        if (!d.isEmpty() && !d.equals(loggedFormat)) {
            loggedFormat = d;
            Quality.log(serviceId, f, Quality.bestVideo(player));
            Log.i("CTV.Quality", serviceId + " ladder " + Quality.ladder(player));
        }
    }

    private void pickQuality() {
        chrome.pick("Video quality · " + getIntent().getStringExtra(EXTRA_SERVICE), Quality.rows(q), new com.goomero.cleartv.Panel.OnPick() {
            @Override
            public void onPick(int which) {
                q = which;
                Quality.set(PlayerActivity.this, serviceId, q);
                if (player != null) Quality.apply(PlayerActivity.this, player, q, true);
                updateQuality();
            }
        });
    }

    private FastLoad.QuietBuffering quiet;

    private void showStatus(String s) {
        status.setText(s.toUpperCase(Locale.ROOT));
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (player == null && ev.getKeyCode() != KeyEvent.KEYCODE_BACK) return true;
        if (chrome.dispatch(ev)) return true;
        return super.dispatchKeyEvent(ev);
    }

    @Override
    protected void onStop() {
        super.onStop();
        chrome.saveProgress();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        if (watchdog != null) watchdog.stop();
        if (quiet != null) quiet.release();
        main.removeCallbacksAndMessages(null);
        chrome.release();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /** Keeps the video at its aspect ratio, centred. */
    private static final class Box extends FrameLayout {
        private float aspect = 16f / 9f;

        Box(Context c) {
            super(c);
        }

        void setAspect(float a) {
            if (Math.abs(a - aspect) < 0.01f) return;
            aspect = a;
            requestLayout();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            super.onMeasure(wSpec, hSpec);
            int w = getMeasuredWidth(), h = getMeasuredHeight();
            if (w == 0 || h == 0 || getChildCount() == 0) return;
            int cw = w, ch = Math.round(w / aspect);
            if (ch > h) {
                ch = h;
                cw = Math.round(h * aspect);
            }
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }
}
