package com.goomero.cleartv.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;

import com.goomero.cleartv.Ui;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Hover preview, the same on every service: when focus rests on a tile for 1.5 s, the preview box (top right)
 * shows the title. Where the service gives a clear stream ClearTV plays natively (Source.previewVideo: Tubi's
 * titles and channels, YouTube videos and live streams via the iOS client's HLS), a muted clip plays through the same ad-free HLS path as the player.
 * Everywhere else (Prime Video, whose player is web/DRM, or a video with no clear stream) the preview shows the title's large artwork
 * with a slow Ken Burns pan, its title, details line and a short description. One preview at a time, capped at
 * 480p with a small buffer; it stops at once when focus moves or a key is pressed. Previews never touch watch
 * history (Library.record is only called by the players).
 */
@OptIn(markerClass = UnstableApi.class)
public final class HoverPreview {
    private static final String TAG = "CTV.Preview";
    private static final long DELAY_MS = 1500, MAX_MS = 30_000;

    private final Context c;
    private final Source source;
    private final ExecutorService io;
    private final FrameLayout box;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private TextureView view;
    private int gen;
    private android.widget.ImageView art;
    private android.widget.LinearLayout caption;
    private Item artItem;
    private final Images artImages;

    public HoverPreview(Context c, Source source, ExecutorService io, FrameLayout box) {
        this.c = c;
        this.source = source;
        this.io = io;
        this.box = box;
        this.artImages = new Images(c, Ui.dp(c, 640), new Runnable() {
            @Override
            public void run() {
                if (artItem != null && art != null && art.getDrawable() == null) {
                    android.graphics.Bitmap b = artImages.get(bigImage(artItem));
                    if (b != null) showBitmap(b);
                }
            }
        });
    }

    public static boolean enabled(Context c) {
        return c.getSharedPreferences("cleartv", Context.MODE_PRIVATE).getBoolean("hover_preview", true);
    }

    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("cleartv", Context.MODE_PRIVATE).edit().putBoolean("hover_preview", on).apply();
    }

    /** Focus landed on a tile: a preview starts if focus stays there. */
    public void focus(final Item it) {
        stop();
        if (it == null || !enabled(c)) return;
        final int g = gen;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g != gen) return;
                if (!source.previewVideo(it)) {
                    startArt(it);
                    return;
                }
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        Source.Playback p0;
                        try {
                            p0 = source.previewPlayback(it);
                        } catch (Exception e) {
                            p0 = null;
                        }
                        final Source.Playback p = p0;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (g != gen) return;
                                // no clear stream after all: the artwork preview instead of nothing
                                if (p == null || p.hls == null) {
                                    String page = source.previewWebPage(it);
                                    if (page != null) startWeb(it, page);
                                    else startArt(it);
                                }
                                else start(p, it.kind == Item.LIVE || p.live);
                            }
                        });
                    }
                });
            }
        }, DELAY_MS);
    }

    /** A larger still for the preview box than the tile's (YouTube tiles use the 320 px "mq" still). */
    private static String bigImage(Item it) {
        String u = it.image == null ? "" : it.image;
        return u.replaceFirst("^(https://i\\.ytimg\\.com/vi/[^/]+/)mqdefault\\.jpg", "$1hqdefault.jpg");
    }

    private void startArt(Item it) {
        artItem = it;
        art = new android.widget.ImageView(c);
        art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0f);
        box.addView(art, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the screen's header already shows the focused title, details and description next to the preview (a caption
        // inside the preview ran over the row of tiles below it), so the card is the moving artwork alone
        caption = new android.widget.LinearLayout(c);
        caption.setVisibility(View.GONE);
        caption.setOrientation(android.widget.LinearLayout.VERTICAL);
        caption.setPadding(Ui.dp(c, 140), 0, Ui.dp(c, 16), Ui.dp(c, 12));
        caption.addView(Ui.lines(Ui.text(c, it.title, 18, Ui.TEXT), 2));
        if (it.subtitle != null && !it.subtitle.isEmpty()) caption.addView(Ui.oneLine(Ui.text(c, it.subtitle, 12, Ui.MUTED)));
        if (it.description != null && !it.description.isEmpty()) caption.addView(Ui.lines(Ui.text(c, it.description, 12, Ui.MUTED), 2));
        caption.setAlpha(0f);
        box.addView(caption, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM));
        box.setVisibility(View.VISIBLE);
        android.graphics.Bitmap b = bigImage(it).isEmpty() ? null : artImages.get(bigImage(it));
        if (b != null) showBitmap(b);
        caption.animate().alpha(1f).setDuration(250).start();
        final int g = gen;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) stop();
            }
        }, MAX_MS);
    }

    private android.webkit.WebView web;

    /** A muted web player (YouTube's embed) in the preview box, ad-blocked like ClearTV's web player. */
    private void startWeb(final Item it, String page) {
        startArt(it); // the artwork card shows until the video's first frame covers it
        final int g = gen;
        com.goomero.cleartv.App app = (com.goomero.cleartv.App) c.getApplicationContext();
        final com.goomero.cleartv.Blocker blocker = new com.goomero.cleartv.Blocker(app.filters(), app.prefs(),
                new com.goomero.cleartv.Blocker.Host() {
                    @Override public void onBlockedCountChanged(int count) {}
                    @Override public void onMediaPlayingChanged(boolean playing) {
                        if (playing && g == gen && web != null) {
                            web.animate().alpha(1f).setDuration(250).start();
                            if (caption != null) caption.animate().alpha(0f).setStartDelay(3000).setDuration(400).start();
                        }
                    }
                });
        blocker.setService(source.id());
        web = new android.webkit.WebView(c);
        web.setFocusable(false);
        web.setBackgroundColor(0xFF000000);
        web.setAlpha(0f);
        android.webkit.WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(s.getUserAgentString());
        String chrome = m.find() ? m.group(1) : "120";
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome + ".0.0.0 Safari/537.36");
        WebActivity.applyUaMetadata(s, true, chrome);
        web.addJavascriptInterface(blocker.new Bridge(), blocker.bridgeName);
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            java.util.Set<String> yt = new java.util.HashSet<>(java.util.Arrays.asList("https://www.youtube.com", "https://www.youtube-nocookie.com"));
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, AdGuardJs.script(blocker.bridgeName), yt);
            // just the picture: the same rule that hides the embed's title bar, buttons and spinners in the player
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, WebActivity.BARE_VIDEO, yt);
        }
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                return blocker.intercept(r);
            }

            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                return r.isForMainFrame(); // the preview never navigates away
            }
        });
        box.addView(web, 2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadDataWithBaseURL("https://com.goomero.cleartv/", page, "text/html", "UTF-8", null);
        // the frame's media events may not reach the bridge: show the player after a few seconds regardless
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen && web != null && web.getAlpha() == 0f) web.animate().alpha(1f).setDuration(250).start();
            }
        }, 4000);
        Log.i(TAG, "web preview " + it.id);
    }

    /** Ken Burns: a slow zoom and drift across the artwork for the length of the preview. */
    private void showBitmap(android.graphics.Bitmap b) {
        art.setImageBitmap(b);
        art.setScaleX(1.04f);
        art.setScaleY(1.04f);
        art.setTranslationX(Ui.dp(c, 10));
        art.animate().alpha(0.6f).setDuration(300).start();
        art.animate().scaleX(1.18f).scaleY(1.18f).translationX(-Ui.dp(c, 16)).translationY(-Ui.dp(c, 6))
                .setDuration(MAX_MS).setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    private void start(Source.Playback p, boolean live) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(p.userAgent != null ? p.userAgent : Http.DESKTOP_UA).setAllowCrossProtocolRedirects(true);
        Map<String, String> h = p.headers;
        if (h != null) http.setDefaultRequestProperties(h);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(4_000, 8_000, 1_000, 2_000).setTargetBufferBytes(3 * 1024 * 1024).build();
        // HLS (Tubi, Pluto, YouTube live) or a progressive MP4 (YouTube's muxed 360p)
        androidx.media3.exoplayer.source.MediaSource.Factory msf = !p.hls.contains("/videoplayback?")
                ? new HlsMediaSource.Factory(http) : new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(http);
        player = new ExoPlayer.Builder(c).setMediaSourceFactory(msf).setLoadControl(load).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(854, 480).setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build());
        player.setVolume(0f);
        view = new TextureView(c);
        view.setAlpha(0f);
        box.addView(view, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setVisibility(View.VISIBLE);
        player.setVideoTextureView(view);
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                if (view != null) view.animate().alpha(0.5f).setDuration(250).start();
            }

            @Override
            public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0) Log.i(TAG, "preview " + v.width + "x" + v.height);
            }

            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_ENDED) stop();
            }
        });
        // a little into the title, past cold opens and logos
        if (live) player.setMediaItem(MediaItem.fromUri(p.hls));
        // YouTube videos are often short: start them at the beginning
        else player.setMediaItem(MediaItem.fromUri(p.hls), "youtube".equals(source.id()) ? 0 : 120_000);
        player.prepare();
        player.play();
        final int g = gen;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) stop();
            }
        }, MAX_MS);
    }

    /** Stops and frees the preview at once (focus moved, a key, the screen paused). */
    public void stop() {
        gen++;
        main.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (view != null) {
            box.removeView(view);
            view = null;
        }
        if (art != null) {
            art.animate().cancel();
            box.removeView(art);
            art = null;
        }
        if (caption != null) {
            box.removeView(caption);
            caption = null;
        }
        artItem = null;
        if (web != null) {
            // free the page's decoder before anything else plays
            web.stopLoading();
            web.loadUrl("about:blank");
            box.removeView(web);
            web.destroy();
            web = null;
        }
        box.setVisibility(View.GONE);
    }

    public boolean isActive() {
        return player != null || art != null || web != null;
    }
}
