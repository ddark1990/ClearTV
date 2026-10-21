package com.goomero.cleartv.pluto;

import android.app.Activity;
import android.content.Context;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsManifest;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;

import com.goomero.cleartv.Panel;
import com.goomero.cleartv.R;
import com.goomero.cleartv.Ui;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Pluto TV: live channels played as HLS with ExoPlayer (clear-key AES-128, no DRM, ads as Pluto stitches
 * them into the stream) and a self-drawn guide. Guide mode shows the tuned channel in a preview at the top right,
 * the focused program's details at the top left and the grid below; OK on another channel tunes it, OK on the
 * playing one goes full screen. Full screen: up/down change channel, OK returns to the guide, BACK too.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlutoActivity extends Activity implements GuideView.Listener {
    private static final String TAG = "CTV.Pluto";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";
    private static final int GUIDE_MINUTES = 240;
    /**
     * Guide preview size by layout engine: classic 352x198 inset top right; apple a 440x248 backdrop flush in the
     * top-right corner fading into the channel info; rail a larger 400x225 inset banner.
     */
    private final boolean flush = Ui.ENGINE.equals("apple");
    private final int PREVIEW_W = flush ? 440 : Ui.ENGINE.equals("rail") ? 400 : 352,
            PREVIEW_H = flush ? 248 : Ui.ENGINE.equals("rail") ? 225 : 198;
    private View scrimL, scrimB;
    private static final long INFO_MS = 4000, TIMELINE_CHECK_MS = 10 * 60_000L;

    private PlutoApi api;
    private PlutoLogos logos;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private AspectBox videoBox;
    private SurfaceView surface;
    private TextView status, captions;
    private LinearLayout guideLayer, chipRow, infoBar;
    private TextView infoChannel, infoTitle, infoMeta, infoDesc;
    private TextView barChannel, barTitle, barMeta;
    private View barProgress;
    private GuideView guide;
    private Panel panel;
    private DateFormat timeFmt;

    private ExoPlayer player;
    private List<PlutoApi.Channel> channels = Collections.emptyList();
    private final HashMap<String, PlutoApi.Channel> byId = new HashMap<>();
    private List<PlutoApi.Category> categories = Collections.emptyList();
    private List<PlutoApi.Channel> rows = Collections.emptyList();
    private String categoryId = "";
    private PlutoApi.Channel playing;
    private boolean guideMode = true;
    private boolean retried;
    private com.goomero.cleartv.tv.StallWatchdog watchdog;
    /** Behind-live-window recoveries in the last minute (more than a few means something else is wrong). */
    private int blwCount;
    private long blwSince;
    private long timelineStart;
    private boolean started;

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            infoBar.setVisibility(View.GONE);
            placeCaptions();
        }
    };
    private final Runnable timelineCheck = new Runnable() {
        @Override
        public void run() {
            // keep at least 2 h of guide ahead of now
            if (System.currentTimeMillis() > timelineStart + (GUIDE_MINUTES - 120) * 60_000L) loadTimelines();
            main.postDelayed(this, TIMELINE_CHECK_MS);
        }
    };

    @Override
    protected void onNewIntent(android.content.Intent in) {
        super.onNewIntent(in);
        String pinned = in.getStringExtra(EXTRA_CHANNEL);
        PlutoApi.Channel c = pinned == null ? null : byId.get(pinned);
        if (c != null) {
            tune(c);
            guideMode = false;
            applyMode();
        }
    }

    /** Channel id to tune at start (a favourite pinned on home). */
    public static final String EXTRA_CHANNEL = "channel";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new PlutoApi(this);
        // a channel pinned on home opens tuned to it
        String pinned = getIntent().getStringExtra(EXTRA_CHANNEL);
        if (pinned != null) {
            api.setLastChannel(pinned);
            guideMode = false;
        }
        timeFmt = android.text.format.DateFormat.getTimeFormat(this);
        buildUi();
        logos = new PlutoLogos(api.dir(), new Runnable() {
            @Override
            public void run() {
                guide.invalidate();
            }
        });
        guide.setLogos(logos);
        loadData();
    }

    // ------------------------------------------------------------------------------------------- UI

    private void buildUi() {
        Context c = this;
        root = new FrameLayout(c);
        root.setBackgroundColor(Ui.BG);

        videoBox = new AspectBox(c);
        videoBox.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(c);
        videoBox.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        status = Ui.kicker(c, getString(R.string.pluto_loading), 12, Ui.V_MUTED);
        status.setGravity(Gravity.CENTER);
        int sp = Ui.dp(c, Ui.S2);
        status.setPadding(sp, sp, sp, sp);
        status.setLineSpacing(0, Ui.LINE_H);
        root.addView(videoBox);
        if (flush) {
            // apple: the corner preview melts into the background on its left and bottom edges
            scrimL = new View(c);
            scrimL.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.BG, 0x00000000}));
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(c, 140), Ui.dp(c, PREVIEW_H), Gravity.TOP | Gravity.END);
            sl.rightMargin = Ui.dp(c, PREVIEW_W - 140);
            root.addView(scrimL, sl);
            scrimB = new View(c);
            scrimB.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{Ui.BG, 0x00000000}));
            FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(Ui.dp(c, PREVIEW_W), Ui.dp(c, 70), Gravity.TOP | Gravity.END);
            sb.topMargin = Ui.dp(c, PREVIEW_H - 70);
            root.addView(scrimB, sb);
        }
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        captions = Ui.text(c, "", 20, 0xFFFFFFFF);
        captions.setGravity(Gravity.CENTER);
        captions.setBackgroundColor(0xB3000000);
        captions.setPadding(Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S_HALF));
        captions.setLineSpacing(0, Ui.LINE_H);
        captions.setVisibility(View.GONE);
        root.addView(captions, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // guide layer: transparent where the preview sits, so the video shows through
        guideLayer = new LinearLayout(c);
        guideLayer.setOrientation(LinearLayout.VERTICAL);
        guideLayer.setPadding(Ui.dp(c, Ui.SAFE_X), Ui.dp(c, Ui.SAFE_Y), Ui.dp(c, Ui.SAFE_X), Ui.dp(c, Ui.SAFE_Y - Ui.S1));
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(0, 0, Ui.dp(c, PREVIEW_W + Ui.S3 - (flush ? Ui.SAFE_X : 0)), 0);
        // the remote keys sit on the kicker line, right-aligned within the info column (never over the chips)
        LinearLayout kickerRow = new LinearLayout(c);
        kickerRow.setOrientation(LinearLayout.HORIZONTAL);
        kickerRow.setGravity(Gravity.CENTER_VERTICAL);
        kickerRow.addView(Ui.oneLine(Ui.kicker(c, "Pluto TV · Live", 11, Ui.SOFT)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView keysHint = Ui.oneLine(Ui.kicker(c, "HOLD OK · MENU   ▶▶ · FAVORITE", 11, Ui.SOFT));
        LinearLayout.LayoutParams khl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        khl.leftMargin = Ui.dp(c, Ui.S2);
        kickerRow.addView(keysHint, khl);
        info.addView(kickerRow);
        infoChannel = Ui.oneLine(Ui.kicker(c, "", 13, Ui.MUTED));
        info.addView(infoChannel, Ui.below(c, Ui.S1));
        infoTitle = Ui.text(c, "", 24, Ui.TEXT);
        infoTitle.setTypeface(Ui.display(c));
        infoTitle.setMaxLines(2);
        infoTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(infoTitle, Ui.below(c, Ui.S_HALF));
        infoMeta = Ui.oneLine(Ui.kicker(c, "", 11, Ui.SOFT));
        info.addView(infoMeta, Ui.below(c, Ui.S_HALF));
        infoDesc = Ui.lines(Ui.text(c, "", 14, Ui.MUTED), 3);
        // the description takes what the header leaves (a two-line title leaves room for two lines)
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        dlp.topMargin = Ui.dp(c, Ui.S1);
        info.addView(infoDesc, dlp);
        guideLayer.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, PREVIEW_H - (flush ? Ui.SAFE_Y : 0))));

        HorizontalScrollView chips = new HorizontalScrollView(c);
        chips.setHorizontalScrollBarEnabled(false);
        chips.setClipToPadding(false);
        chips.setPadding(Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S_HALF));
        chipRow = new LinearLayout(c);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chipRow);
        // chips that run past the right edge fade out there instead of ending in a hard cut
        chips.setHorizontalFadingEdgeEnabled(true);
        chips.setFadingEdgeLength(Ui.dp(c, Ui.S6));
        LinearLayout chipBar = new LinearLayout(c);
        chipBar.setOrientation(LinearLayout.HORIZONTAL);
        chipBar.setGravity(Gravity.CENTER_VERTICAL);
        chipBar.addView(chips, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(c, Ui.S1);
        guideLayer.addView(chipBar, clp);

        guide = new GuideView(c);
        guide.setId(View.generateViewId());
        guide.setListener(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        glp.topMargin = Ui.dp(c, Ui.S1);
        guideLayer.addView(guide, glp);
        root.addView(guideLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // full-screen info bar
        infoBar = new LinearLayout(c);
        infoBar.setOrientation(LinearLayout.VERTICAL);
        infoBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF0060809, 0x00060809}));
        infoBar.setPadding(Ui.dp(c, Ui.SAFE_X), Ui.dp(c, Ui.S6 + Ui.S2), Ui.dp(c, Ui.SAFE_X), Ui.dp(c, Ui.SAFE_Y + Ui.S1));
        barChannel = Ui.oneLine(Ui.kicker(c, "", 13, Ui.V_MUTED));
        infoBar.addView(barChannel);
        barTitle = Ui.text(c, "", 24, Ui.V_TEXT);
        barTitle.setTypeface(Ui.display(c));
        infoBar.addView(Ui.oneLine(barTitle), Ui.below(c, Ui.S_HALF));
        FrameLayout track = new FrameLayout(c);
        track.setBackground(Ui.rounded(Ui.V_LINE, Ui.dp(c, 2)));
        barProgress = new View(c);
        barProgress.setBackground(Ui.rounded(Ui.ACCENT, Ui.dp(c, 2)));
        barProgress.setPivotX(0);
        track.addView(barProgress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4));
        tlp.topMargin = Ui.dp(c, Ui.S2);
        tlp.bottomMargin = Ui.dp(c, Ui.S1);
        infoBar.addView(track, tlp);
        barMeta = Ui.kicker(c, "", 11, Ui.V_SOFT);
        infoBar.addView(Ui.oneLine(barMeta));
        infoBar.setVisibility(View.GONE);
        root.addView(infoBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        panel = new Panel(c, root);
        setContentView(root);
        applyMode();
    }

    /** Guide mode: preview at the top right and the guide; full screen: the video and the info bar. */
    private void applyMode() {
        FrameLayout.LayoutParams lp;
        if (!guideMode) commitHover();
        if (guideMode) {
            lp = new FrameLayout.LayoutParams(Ui.dp(this, PREVIEW_W), Ui.dp(this, PREVIEW_H), Gravity.TOP | Gravity.END);
            if (!flush) lp.setMargins(0, Ui.dp(this, Ui.SAFE_Y), Ui.dp(this, Ui.SAFE_X), 0);
            guideLayer.setVisibility(View.VISIBLE);
            infoBar.setVisibility(View.GONE);
            captions.setTextSize(13);
        } else {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            guideLayer.setVisibility(View.GONE);
            captions.setTextSize(22);
        }
        videoBox.setLayoutParams(lp);
        if (scrimL != null) {
            scrimL.setVisibility(guideMode ? View.VISIBLE : View.GONE);
            scrimB.setVisibility(guideMode ? View.VISIBLE : View.GONE);
        }
        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) status.getLayoutParams();
        FrameLayout.LayoutParams cap = (FrameLayout.LayoutParams) captions.getLayoutParams();
        if (guideMode) {
            slp.gravity = Gravity.TOP | Gravity.END;
            slp.width = Ui.dp(this, PREVIEW_W);
            int top = flush ? 0 : Ui.SAFE_Y, right = flush ? 0 : Ui.SAFE_X;
            slp.setMargins(0, Ui.dp(this, top + PREVIEW_H / 2 - 20), Ui.dp(this, right), 0);
            cap.gravity = Gravity.TOP | Gravity.END;
            cap.setMargins(0, Ui.dp(this, top + PREVIEW_H - 40), Ui.dp(this, right + 8), 0);
        } else {
            slp.gravity = Gravity.CENTER;
            slp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            slp.setMargins(0, 0, 0, 0);
            cap.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            cap.setMargins(Ui.dp(this, Ui.SAFE_X), 0, Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y + 12));
        }
        status.setLayoutParams(slp);
        captions.setLayoutParams(cap);
        if (guideMode) guide.requestFocus();
        else showInfo();
    }

    private void buildChips() {
        chipRow.removeAllViews();
        addChip("", getString(R.string.pluto_all_channels));
        addChip(FAV, "★ Favorites");
        addChip(RECENT, "Recently watched");
        HashMap<String, Integer> counts = new HashMap<>();
        for (PlutoApi.Channel ch : channels) for (String id : ch.categories) counts.put(id, counts.containsKey(id) ? counts.get(id) + 1 : 1);
        for (PlutoApi.Category cat : categories) if (counts.containsKey(cat.id)) addChip(cat.id, cat.name);
        updateChipFocusLinks();
    }

    private void addChip(final String id, String name) {
        TextView t = Ui.kicker(this, name, 12, Ui.MUTED);
        t.setId(View.generateViewId());
        t.setTag(id);
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(this, Ui.S2), 0, Ui.dp(this, Ui.S2), 0);
        t.setBackground(Ui.pillBackground(this));
        t.setNextFocusDownId(guide.getId());
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectCategory(id);
                guide.requestFocus();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 32));
        lp.rightMargin = Ui.dp(this, 8);
        chipRow.addView(t, lp);
    }

    private void updateChipFocusLinks() {
        for (int i = 0; i < chipRow.getChildCount(); i++) {
            TextView t = (TextView) chipRow.getChildAt(i);
            boolean sel = categoryId.equals(t.getTag());
            t.setTextColor(sel ? Ui.TEXT : Ui.SOFT);
            if (sel) guide.setNextFocusUpId(t.getId());
        }
    }

    static final String FAV = "ctv:fav", RECENT = "ctv:recent";

    private static com.goomero.cleartv.tv.Item liveItem(PlutoApi.Channel ch) {
        return new com.goomero.cleartv.tv.Item(com.goomero.cleartv.tv.Item.LIVE, ch.id, ch.name, "", ch.logo == null ? "" : ch.logo, "LIVE", "");
    }

    /** Channels of a stored list (favorites or recent) that are still in the lineup, in the list's order. */
    private ArrayList<PlutoApi.Channel> stored(java.util.List<com.goomero.cleartv.tv.Item> items, java.util.Set<String> skip) {
        HashMap<String, PlutoApi.Channel> byId = new HashMap<>();
        for (PlutoApi.Channel ch : channels) byId.put(ch.id, ch);
        ArrayList<PlutoApi.Channel> out = new ArrayList<>();
        for (com.goomero.cleartv.tv.Item it : items) {
            PlutoApi.Channel ch = byId.get(it.id);
            if (ch != null && skip.add(ch.id)) out.add(ch);
        }
        return out;
    }

    private void selectCategory(String id) {
        categoryId = id;
        ArrayList<PlutoApi.Channel> l = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (id.isEmpty() || id.equals(FAV)) l.addAll(stored(com.goomero.cleartv.tv.LiveLists.favorites(this, "pluto"), seen));
        if (id.isEmpty() || id.equals(RECENT)) l.addAll(stored(com.goomero.cleartv.tv.LiveLists.recent(this, "pluto"), seen));
        for (PlutoApi.Channel ch : channels) {
            if (id.equals(FAV) || id.equals(RECENT)) break;
            if (id.isEmpty()) {
                if (!seen.contains(ch.id)) l.add(ch);
            } else for (String c : ch.categories) if (c.equals(id)) {
                l.add(ch);
                break;
            }
        }
        rows = l;
        PlutoApi.Channel f = guide.focusedChannel();
        guide.setRows(l, playing != null ? playing.id : f != null ? f.id : "");
        updateChipFocusLinks();
    }

    // ------------------------------------------------------------------------------------- data

    /** Cached guide first (instant), then a fresh session, channel list and guide window in the background. */
    private void loadData() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<PlutoApi.Channel> cached = api.cachedChannels();
                final List<PlutoApi.Category> cats = api.cachedCategories();
                final Map<String, List<PlutoApi.Program>> tl = api.cachedTimelines();
                if (cached != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            applyChannels(cached, cats);
                            applyTimelines(tl);
                            if (playing == null) tuneInitial();
                        }
                    });
                }
                try {
                    api.session(false);
                    if (cached == null || cats == null || !api.channelsFresh()) {
                        final List<PlutoApi.Channel> fresh = api.fetchChannels();
                        final List<PlutoApi.Category> freshCats = api.fetchCategories();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                applyChannels(fresh, freshCats);
                                if (playing == null) tuneInitial();
                            }
                        });
                    }
                } catch (IOException e) {
                    Log.w(TAG, "channel list: " + e.getMessage());
                    if (cached == null) showStatus(getString(R.string.pluto_offline));
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loadTimelines();
                        main.removeCallbacks(timelineCheck);
                        main.postDelayed(timelineCheck, TIMELINE_CHECK_MS);
                    }
                });
            }
        });
    }

    private void loadTimelines() {
        final ArrayList<String> ids = new ArrayList<>();
        // the rows on screen first, so the visible part of the guide fills in with the first batch
        for (PlutoApi.Channel c : rows) ids.add(c.id);
        for (PlutoApi.Channel c : channels) if (!ids.contains(c.id)) ids.add(c.id);
        final long start = System.currentTimeMillis() / 1800_000L * 1800_000L;
        timelineStart = start;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.fetchTimelines(ids, start, GUIDE_MINUTES, new PlutoApi.TimelineSink() {
                        @Override
                        public void onBatch(final Map<String, List<PlutoApi.Program>> programs) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    applyTimelines(programs);
                                }
                            });
                        }
                    });
                } catch (IOException e) {
                    Log.w(TAG, "guide: " + e.getMessage());
                }
            }
        });
    }

    private void applyChannels(List<PlutoApi.Channel> list, List<PlutoApi.Category> cats) {
        for (PlutoApi.Channel c : list) {
            PlutoApi.Channel old = byId.get(c.id);
            if (old != null) c.programs = old.programs;
        }
        byId.clear();
        for (PlutoApi.Channel c : list) byId.put(c.id, c);
        channels = list;
        if (cats != null) categories = cats;
        if (playing != null && byId.containsKey(playing.id)) playing = byId.get(playing.id);
        buildChips();
        selectCategory(categoryId);
        if (guideMode && !guide.hasFocus() && !panel.isShowing()) guide.requestFocus();
    }

    private void applyTimelines(Map<String, List<PlutoApi.Program>> map) {
        for (Map.Entry<String, List<PlutoApi.Program>> e : map.entrySet()) {
            PlutoApi.Channel c = byId.get(e.getKey());
            if (c != null) c.programs = e.getValue();
        }
        guide.dataChanged();
        if (!guideMode && infoBar.getVisibility() == View.VISIBLE) showInfo();
    }

    private void tuneInitial() {
        if (channels.isEmpty() || !started) return;
        PlutoApi.Channel c = byId.get(api.lastChannel());
        if (c == null) c = channels.get(0);
        tune(c);
        // the guide opens on the channel that is playing, so OK on it goes straight to full screen
        guide.setRows(rows, c.id);
    }

    // ---------------------------------------------------------------------------------- playback

    private void createPlayer() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", "https://pluto.tv");
        headers.put("Referer", "https://pluto.tv/");
        // short timeouts with instant retries, and a start LIVE_OFFSET_S behind the newest segment, so a late
        // playlist refresh or a slow segment is absorbed by media already loaded (tv/FastLoad)
        HlsMediaSource.Factory hls = com.goomero.cleartv.tv.FastLoad.hls(UA, headers, LIVE_OFFSET_S);
        // about 10-12 MB of media at most (the default 50 s buffer would be ~20 MB at Pluto's top bitrate)
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 25_000, 1_500, 3_000)
                .setTargetBufferBytes(12 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        quality = com.goomero.cleartv.tv.Quality.get(this, "pluto");
        if (quiet == null) quiet = new com.goomero.cleartv.tv.FastLoad.QuietBuffering(main, "pluto", new Runnable() {
            @Override
            public void run() {
                showStatus(getString(R.string.pluto_tuning));
            }
        });
        player = com.goomero.cleartv.tv.Quality.build(this, hls, load, quality);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguage("en")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !api.captions())
                .build());
        player.setVideoSurfaceView(surface);
        com.goomero.cleartv.tv.StartTrace.watch(player);
        com.goomero.cleartv.tv.StallWatchdog.logLoadTrouble(player, "pluto");
        quiet.attach(player);
        if (watchdog == null) watchdog = new com.goomero.cleartv.tv.StallWatchdog(main, "pluto",
                new com.goomero.cleartv.tv.StallWatchdog.Target() {
                    @Override
                    public boolean wantsToPlay() {
                        return playing != null && com.goomero.cleartv.tv.StallWatchdog.exoWantsToPlay(player);
                    }

                    @Override
                    public long clockMs() {
                        return com.goomero.cleartv.tv.StallWatchdog.exoClockMs(player);
                    }

                    @Override
                    public void recover(int attempt) {
                        // what a channel change does: the channel's playlist loaded again, at the live edge; from the
                        // third attempt on with a new Pluto session too
                        reloadLive(attempt >= 3);
                    }

                    @Override
                    public void giveUp() {
                        showStatus(getString(R.string.pluto_unavailable));
                    }

                    @Override
                    public String describe() {
                        return com.goomero.cleartv.tv.StallWatchdog.exoDescribe(player);
                    }
                });
        watchdog.reset();
        watchdog.start();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                // a short rebuffer while a channel plays shows nothing; a tune or a longer one shows "Tuning"
                boolean now = quiet.onState(state);
                if (state == Player.STATE_READY) {
                    retried = false;
                    hideStatus();
                } else if (state == Player.STATE_BUFFERING && now) {
                    showStatus(getString(R.string.pluto_tuning));
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "playback error " + error.getErrorCodeName() + ": " + error.getMessage());
                // fell out of the live window (a long stall or pause): back to the live edge of the same playlist,
                // no new session and no "unavailable" (a few times a minute at most, then the normal recovery)
                if (com.goomero.cleartv.tv.StallWatchdog.isBehindLiveWindow(error) && player != null) {
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
                        player.play();
                        return;
                    }
                }
                recover();
            }

            @Override
            public void onTimelineChanged(Timeline timeline, int reason) {
                Object m = player.getCurrentManifest();
                if (m instanceof HlsManifest && looksTakenDown(((HlsManifest) m).mediaPlaylist)) {
                    Log.w(TAG, "takedown slate or empty playlist");
                    player.stop();
                    recover();
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) videoBox.setAspect(v.width * v.pixelWidthHeightRatio / v.height);
                logQuality();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                logQuality();
            }

            @Override
            public void onCues(CueGroup cues) {
                StringBuilder sb = new StringBuilder();
                for (Cue cue : cues.cues) {
                    if (cue.text == null) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(cue.text);
                }
                captions.setText(sb);
                captions.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private int quality;
    private String loggedFormat = "";
    private com.goomero.cleartv.tv.FastLoad.QuietBuffering quiet;
    /**
     * Where a tune starts: this far behind the newest segment. Pluto's live playlists hold 25-30 s (5 s segments);
     * Media3's default (3 target durations) is 15 s, which left 10-15 s loaded ahead. 20 s leaves 15-20 s ahead,
     * and still 5-10 s between playback and the oldest segment, so a stall has to last that long before the
     * jump-back-to-live (behind the live window) handling is needed.
     */
    static final int LIVE_OFFSET_S = 20;

    private String playingQuality() {
        return com.goomero.cleartv.tv.Quality.describe(com.goomero.cleartv.tv.Quality.selectedVideo(player));
    }

    private void logQuality() {
        String d = playingQuality();
        if (d.isEmpty() || d.equals(loggedFormat)) return;
        loggedFormat = d;
        com.goomero.cleartv.tv.Quality.log("pluto", com.goomero.cleartv.tv.Quality.selectedVideo(player),
                com.goomero.cleartv.tv.Quality.bestVideo(player));
        Log.i("CTV.Quality", "pluto ladder " + com.goomero.cleartv.tv.Quality.ladder(player));
        if (infoBar.getVisibility() == View.VISIBLE) showInfo();
    }

    private void pickQuality() {
        panel.show("Video quality · Pluto TV", com.goomero.cleartv.tv.Quality.rows(quality), new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                quality = which;
                com.goomero.cleartv.tv.Quality.set(PlutoActivity.this, "pluto", which);
                if (player != null) com.goomero.cleartv.tv.Quality.apply(PlutoActivity.this, player, which, true);
            }
        });
    }

    /** Pluto's 2026 failure mode: playlists with no segments, or a "takedown slate" in place of the channel. */
    private static boolean looksTakenDown(HlsMediaPlaylist p) {
        if (p == null) return false;
        if (p.segments.isEmpty()) return p.hasEndTag;
        for (HlsMediaPlaylist.Segment s : p.segments) {
            if (s.url.contains("takedownslate")
                    || (s.fullSegmentEncryptionKeyUri != null && s.fullSegmentEncryptionKeyUri.contains("takedownslate"))) {
                return true;
            }
        }
        return false;
    }

    /** First failure: new session and tune again. Second: tell the user. */
    private void recover() {
        if (playing == null) return;
        if (retried) {
            showStatus(getString(R.string.pluto_unavailable));
            return;
        }
        retried = true;
        final PlutoApi.Channel c = playing;
        showStatus(getString(R.string.pluto_tuning));
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.session(true);
                } catch (IOException e) {
                    showStatus(getString(R.string.pluto_offline));
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isPlaying(c)) tune(playing, hoverTuned);
                    }
                });
            }
        });
    }

    private void tune(final PlutoApi.Channel c) {
        tune(c, false);
    }

    /**
     * Stall recovery: the playing channel's playlist loaded again from scratch at the live edge, exactly what a
     * channel change does, without the info bar or the watch history. {@code freshSession} also boots a new session.
     */
    private void reloadLive(final boolean freshSession) {
        if (player == null || playing == null) return;
        final PlutoApi.Channel c = playing;
        if (quiet != null) quiet.reset();
        showStatus(getString(R.string.pluto_tuning));
        io.execute(new Runnable() {
            @Override
            public void run() {
                final PlutoApi.Session s;
                try {
                    s = api.session(freshSession);
                } catch (IOException e) {
                    showStatus(getString(R.string.pluto_offline));
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (player == null || !isPlaying(c)) return;
                        player.setMediaItem(MediaItem.fromUri(PlutoApi.streamUrl(s, c.id)));
                        player.prepare();
                        player.play();
                    }
                });
            }
        });
    }

    /**
     * Same channel as the one playing, by id: a fresh channel list (applyChannels) swaps in new Channel objects,
     * so an identity check would drop a tune that was in flight and leave the player on "Tuning" forever.
     */
    private boolean isPlaying(PlutoApi.Channel c) {
        return c != null && playing != null && c.id.equals(playing.id);
    }

    // hover preview: resting 1.5 s on a guide row plays that channel muted in the preview box; it only counts as
    // watched (Recently watched, last channel) and gets its sound once it is picked or shown full screen
    private boolean hoverTuned;
    private final Runnable hoverRun = new Runnable() {
        @Override
        public void run() {
            if (hoverTarget != null && guideMode && !isPlaying(hoverTarget)) tune(hoverTarget, true);
        }
    };
    private PlutoApi.Channel hoverTarget;

    private void commitHover() {
        main.removeCallbacks(hoverRun);
        if (!hoverTuned || playing == null) return;
        hoverTuned = false;
        if (player != null) player.setVolume(1f);
        api.setLastChannel(playing.id);
        com.goomero.cleartv.tv.LiveLists.watched(this, "pluto", liveItem(playing));
    }

    private void tune(final PlutoApi.Channel c, boolean hover) {
        if (player == null) return;
        com.goomero.cleartv.tv.StartTrace.begin("pluto", android.os.SystemClock.uptimeMillis());
        if (!isPlaying(c)) retried = false;
        if (watchdog != null) watchdog.reset();
        if (quiet != null) quiet.reset();
        playing = c;
        hoverTuned = hover;
        player.setVolume(hover ? 0f : 1f);
        if (!hover) {
            api.setLastChannel(c.id);
            com.goomero.cleartv.tv.LiveLists.watched(this, "pluto", liveItem(c));
        }
        guide.setPlaying(c.id);
        captions.setVisibility(View.GONE);
        showStatus(getString(R.string.pluto_tuning));
        if (!guideMode) showInfo();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final PlutoApi.Session s;
                try {
                    s = api.session(false);
                } catch (IOException e) {
                    showStatus(getString(R.string.pluto_offline));
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (player == null || !isPlaying(c)) return;
                        com.goomero.cleartv.tv.StartTrace.mark("session ready, preparing " + c.name);
                        player.setMediaItem(MediaItem.fromUri(PlutoApi.streamUrl(s, c.id)));
                        player.prepare();
                        player.play();
                    }
                });
            }
        });
    }

    private void zap(int delta) {
        if (rows.isEmpty()) return;
        int i = playing != null ? rows.indexOf(playing) : -1;
        int n = ((i < 0 ? 0 : i + delta) % rows.size() + rows.size()) % rows.size();
        tune(rows.get(n));
        guide.setRows(rows, rows.get(n).id);
    }

    private void showStatus(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                status.setText(s.toUpperCase(Locale.ROOT));
                status.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    // ----------------------------------------------------------------------------------- info

    @Override
    public void onGuideFocus(PlutoApi.Channel c, PlutoApi.Program p) {
        main.removeCallbacks(hoverRun);
        hoverTarget = c;
        if (guideMode && !isPlaying(c) && com.goomero.cleartv.tv.HoverPreview.enabled(this)) main.postDelayed(hoverRun, 1500);
        infoChannel.setText((c.number + " · " + c.name).toUpperCase(Locale.ROOT));
        if (p == null) {
            infoTitle.setText(c.name);
            infoMeta.setText("");
            infoDesc.setText("");
            return;
        }
        infoTitle.setText(p.episode.isEmpty() ? p.title : p.title + ": " + p.episode);
        infoMeta.setText(meta(p).toUpperCase(Locale.ROOT));
        infoDesc.setText(p.description);
        fitInfoDesc();
    }

    /** Whole lines only: two description lines under a two-line title, three under a one-line title. */
    private void fitInfoDesc() {
        infoTitle.post(new Runnable() {
            @Override
            public void run() {
                int want = infoTitle.getLineCount() > 1 ? 2 : 3;
                if (infoDesc.getMaxLines() != want) infoDesc.setMaxLines(want);
            }
        });
    }

    @Override
    public void onGuideActivate(PlutoApi.Channel c) {
        if (playing != null && c.id.equals(playing.id)) {
            commitHover();
            guideMode = false;
            applyMode();
        } else {
            tune(c);
        }
    }

    @Override
    public void onGuideMenu(PlutoApi.Channel c) {
        channelMenu(c);
    }

    /**
     * OK held on a channel (guide row or full screen): Go to channel (full screen on it), watch in the preview or
     * back to the guide, Favorites, Home watchlist.
     */
    private void channelMenu(final PlutoApi.Channel c) {
        if (c == null) return;
        final boolean watching = !guideMode && playing != null && c.id.equals(playing.id);
        final boolean fav = com.goomero.cleartv.tv.LiveLists.isFavorite(this, "pluto", c.id);
        final boolean pinned = com.goomero.cleartv.tv.Library.isFavorite(this, "pluto", c.id);
        final java.util.List<String> rows = new java.util.ArrayList<>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<>();
        if (!watching) {
            // the channel full screen, like OK on the channel that is already playing
            rows.add("Go to channel");
            acts.add(new Runnable() { public void run() { goToChannel(c); } });
        }
        rows.add(watching ? "Back to the guide" : "Watch now");
        acts.add(new Runnable() { public void run() {
            if (watching) {
                guideMode = true;
                applyMode();
            } else onGuideActivate(c);
        } });
        rows.add(fav ? "Remove from Favorites" : "★ Add to Favorites");
        acts.add(new Runnable() { public void run() {
            boolean now = com.goomero.cleartv.tv.LiveLists.toggleFavorite(PlutoActivity.this, "pluto", liveItem(c));
            android.widget.Toast.makeText(PlutoActivity.this, (now ? "★ Added to Favorites: " : "Removed from Favorites: ") + c.name,
                    android.widget.Toast.LENGTH_SHORT).show();
            if (guideMode) selectCategory(categoryId);
        } });
        rows.add(pinned ? "Remove from Home watchlist" : "Pin to Home watchlist");
        acts.add(new Runnable() { public void run() {
            boolean now = com.goomero.cleartv.tv.Library.toggleFavorite(PlutoActivity.this, "pluto",
                    new com.goomero.cleartv.tv.Item(com.goomero.cleartv.tv.Item.CHANNEL, c.id, c.name,
                            "Pluto TV · Ch " + c.number, c.logo, "", ""));
            android.widget.Toast.makeText(PlutoActivity.this, now ? "Pinned to Home" : "Removed from Home",
                    android.widget.Toast.LENGTH_SHORT).show();
        } });
        rows.add("Cancel");
        acts.add(null);
        panel.show(c.number + " · " + c.name, rows.toArray(new String[0]), new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                Runnable r = acts.get(which);
                if (r != null) r.run();
            }
        });
    }

    /** Go to channel: tune it (unless it already plays) and show it full screen. */
    private void goToChannel(PlutoApi.Channel c) {
        if (!isPlaying(c)) tune(c);
        else commitHover();
        guideMode = false;
        applyMode();
    }

    private boolean fullOkDown, fullOkLong;

    private String meta(PlutoApi.Program p) {
        StringBuilder sb = new StringBuilder();
        sb.append(timeFmt.format(new Date(p.start))).append(" – ").append(timeFmt.format(new Date(p.stop)));
        if (!p.rating.isEmpty()) sb.append(" · ").append(p.rating);
        if (!p.genre.isEmpty()) sb.append(" · ").append(p.genre);
        return sb.toString();
    }

    private void showInfo() {
        if (playing == null || guideMode) return;
        long now = System.currentTimeMillis();
        PlutoApi.Program p = playing.at(now);
        barChannel.setText((playing.number + " · " + playing.name).toUpperCase(Locale.ROOT));
        barTitle.setText(p == null ? playing.name : p.episode.isEmpty() ? p.title : p.title + ": " + p.episode);
        StringBuilder m = new StringBuilder();
        if (p != null) {
            m.append(meta(p));
            PlutoApi.Program next = playing.after(p);
            if (next != null) m.append("   ·   ").append(getString(R.string.pluto_next, next.title));
            barProgress.setScaleX(Math.max(0f, Math.min(1f, (now - p.start) / (float) (p.stop - p.start))));
        } else {
            barProgress.setScaleX(0f);
        }
        String pq = playingQuality();
        m.append("   ·   ").append(com.goomero.cleartv.tv.Quality.shortLabel(quality)).append(pq.isEmpty() ? "" : " " + pq);
        barMeta.setText(m.toString().toUpperCase(Locale.ROOT));
        infoBar.setVisibility(View.VISIBLE);
        placeCaptions();
        infoBar.post(new Runnable() {
            @Override
            public void run() {
                placeCaptions(); // again once the bar has its measured height
            }
        });
        main.removeCallbacks(hideInfo);
        main.postDelayed(hideInfo, INFO_MS);
    }

    /** Full screen: captions sit above the info bar while it shows (in its fade), at the safe bottom otherwise. */
    private void placeCaptions() {
        if (guideMode) return;
        FrameLayout.LayoutParams cap = (FrameLayout.LayoutParams) captions.getLayoutParams();
        int bottom = Ui.dp(this, Ui.SAFE_Y + 12);
        if (infoBar.getVisibility() == View.VISIBLE) {
            int h = infoBar.getHeight() > 0 ? infoBar.getHeight() : Ui.dp(this, 170);
            bottom = Math.max(bottom, h - Ui.dp(this, Ui.S6));
        }
        if (cap.bottomMargin != bottom) {
            cap.bottomMargin = bottom;
            captions.setLayoutParams(cap);
        }
    }

    // ------------------------------------------------------------------------------------ menu

    private void showMenu() {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        labels.add(getString(guideMode ? R.string.pluto_full_screen : R.string.pluto_guide));
        actions.add(new Runnable() {
            public void run() {
                if (playing == null) return;
                guideMode = !guideMode;
                applyMode();
            }
        });
        final boolean cc = api.captions();
        labels.add(getString(cc ? R.string.pluto_captions_on : R.string.pluto_captions_off));
        actions.add(new Runnable() {
            public void run() {
                api.setCaptions(!cc);
                if (player != null) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, cc).build());
                }
                if (cc) captions.setVisibility(View.GONE);
            }
        });
        final List<Tracks.Group> audio = audioGroups();
        int tracks = 0;
        for (Tracks.Group g : audio) tracks += g.length;
        if (tracks > 1) {
            labels.add(getString(R.string.pluto_audio, currentAudioLabel(audio)));
            actions.add(new Runnable() {
                public void run() {
                    nextAudio(audio);
                }
            });
        }
        labels.add("Quality: " + com.goomero.cleartv.tv.Quality.shortLabel(quality)
                + (playingQuality().isEmpty() ? "" : " · " + playingQuality()));
        actions.add(new Runnable() {
            public void run() {
                main.post(new Runnable() {
                    public void run() {
                        pickQuality();
                    }
                });
            }
        });
        final PlutoApi.Channel pinTarget = guideMode && guide.focusedChannel() != null ? guide.focusedChannel() : playing;
        if (pinTarget != null) {
            final boolean pinned = com.goomero.cleartv.tv.Library.isFavorite(this, "pluto", pinTarget.id);
            labels.add((pinned ? "Remove from Home watchlist: " : "Add to Home watchlist: ") + pinTarget.name);
            actions.add(new Runnable() {
                public void run() {
                    boolean now = com.goomero.cleartv.tv.Library.toggleFavorite(PlutoActivity.this, "pluto",
                            new com.goomero.cleartv.tv.Item(com.goomero.cleartv.tv.Item.CHANNEL, pinTarget.id, pinTarget.name,
                                    "Pluto TV · Ch " + pinTarget.number, pinTarget.logo, "", ""));
                    android.widget.Toast.makeText(PlutoActivity.this, now ? "Pinned to Home" : "Removed from Home",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        labels.add(getString(R.string.pluto_reload_guide));
        actions.add(new Runnable() {
            public void run() {
                loadTimelines();
            }
        });
        final boolean signed = com.goomero.cleartv.tv.Accounts.signedIn(this, "pluto");
        labels.add(signed ? "Sign out of Pluto TV" : "Sign in to Pluto TV");
        actions.add(new Runnable() {
            public void run() {
                if (signed) {
                    com.goomero.cleartv.tv.Accounts.signOut(PlutoActivity.this, "pluto");
                    android.widget.Toast.makeText(PlutoActivity.this, "Signed out of Pluto TV", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    com.goomero.cleartv.tv.Accounts.signIn(PlutoActivity.this, "pluto");
                }
            }
        });
        labels.add(getString(R.string.pluto_home));
        actions.add(new Runnable() {
            public void run() {
                finish();
            }
        });
        String title = playing != null ? playing.number + " · " + playing.name : getString(R.string.pluto_name);
        panel.show(title, labels.toArray(new String[0]), new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                actions.get(which).run();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == com.goomero.cleartv.tv.Accounts.REQ_PLUTO) {
            boolean ok = com.goomero.cleartv.tv.Accounts.onPlutoResult(this, data);
            android.widget.Toast.makeText(this, ok ? "Signed in to Pluto TV" : "Not signed in to Pluto TV",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private List<Tracks.Group> audioGroups() {
        ArrayList<Tracks.Group> out = new ArrayList<>();
        if (player == null) return out;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO && g.isSupported()) out.add(g);
        }
        return out;
    }

    private static String label(Format f) {
        if (f.label != null && !f.label.isEmpty()) return f.label;
        if (f.language != null) return new Locale(f.language).getDisplayLanguage();
        return "Default";
    }

    private String currentAudioLabel(List<Tracks.Group> groups) {
        for (Tracks.Group g : groups) for (int i = 0; i < g.length; i++) if (g.isTrackSelected(i)) return label(g.getTrackFormat(i));
        return "Default";
    }

    private void nextAudio(List<Tracks.Group> groups) {
        ArrayList<int[]> all = new ArrayList<>();
        int cur = -1;
        for (int gi = 0; gi < groups.size(); gi++) {
            Tracks.Group g = groups.get(gi);
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (g.isTrackSelected(i)) cur = all.size();
                all.add(new int[]{gi, i});
            }
        }
        if (all.size() < 2 || player == null) return;
        int[] next = all.get((cur + 1) % all.size());
        Tracks.Group g = groups.get(next[0]);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), next[1])).build());
    }

    // ------------------------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean up = ev.getAction() == KeyEvent.ACTION_UP;
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (panel.isShowing()) {
            if (panel.handleKey(ev)) return true;
            return super.dispatchKeyEvent(ev);
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (up) showMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down && player != null) {
                if (code == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                else if (player.isPlaying()) player.pause();
                else player.play();
                if (!guideMode) showInfo();
            }
            return true;
        }
        if (guideMode) {
            // the remote's >> key on a guide row: add or remove the channel from Favorites (hint in the chip row)
            if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                PlutoApi.Channel f = guide.focusedChannel();
                if (down && ev.getRepeatCount() == 0 && f != null) {
                    boolean now = com.goomero.cleartv.tv.LiveLists.toggleFavorite(this, "pluto", liveItem(f));
                    android.widget.Toast.makeText(this, (now ? "★ Added to Favorites: " : "Removed from Favorites: ") + f.name,
                            android.widget.Toast.LENGTH_SHORT).show();
                    selectCategory(categoryId);
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) finish();
                return true;
            }
            return super.dispatchKeyEvent(ev);
        }
        // full screen: the same remote as every ClearTV player (PlayerChrome); live TV has nothing to seek, so
        // left/right and the channel keys change channel
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (up) {
                    if (infoBar.getVisibility() == View.VISIBLE) {
                        main.removeCallbacks(hideInfo);
                        infoBar.setVisibility(View.GONE);
                    } else {
                        guideMode = true;
                        applyMode();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (up) {
                    showInfo();
                    showMenu();
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (down && ev.getRepeatCount() == 0) zap(-1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (down && ev.getRepeatCount() == 0) zap(1);
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
                // the remote's CC key, as in every ClearTV player
                if (up) {
                    boolean on = !api.captions();
                    api.setCaptions(on);
                    if (player != null) {
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build());
                    }
                    if (!on) captions.setVisibility(View.GONE);
                    android.widget.Toast.makeText(this, on ? "Captions on" : "Captions off", android.widget.Toast.LENGTH_SHORT).show();
                    showInfo();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // a tap pauses/plays on release; held it opens the channel's actions (Favorites). Only a press that
                // started here counts, so the release of the OK that opened full screen from the guide is ignored
                if (down && ev.getRepeatCount() == 0) {
                    fullOkDown = true;
                    fullOkLong = false;
                } else if (down && fullOkDown && !fullOkLong && com.goomero.cleartv.tv.ShelfView.isHeld(ev)) {
                    fullOkLong = true;
                    showInfo();
                    channelMenu(playing);
                } else if (up) {
                    boolean tap = fullOkDown && !fullOkLong;
                    fullOkDown = false;
                    fullOkLong = false;
                    if (tap && player != null) {
                        if (player.isPlaying()) player.pause();
                        else player.play();
                        showInfo();
                    }
                }
                return true;
            default:
                if (down) showInfo();
                return super.dispatchKeyEvent(ev);
        }
    }

    // ------------------------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        createPlayer();
        if (playing != null) tune(playing);
        else tuneInitial();
    }

    @Override
    protected void onStop() {
        started = false;
        if (watchdog != null) watchdog.stop();
        if (quiet != null) quiet.release();
        // give the decoder and buffers back while in the background; the channel is tuned again on return
        if (player != null) {
            player.release();
            player = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_UI_HIDDEN) logos.trim();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        logos.shutdown();
        super.onDestroy();
    }

    /** Keeps its child at the video's aspect ratio, centred (letterbox or pillarbox as needed). */
    private static final class AspectBox extends FrameLayout {
        private float aspect = 16f / 9f;

        AspectBox(Context c) {
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
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }
}
