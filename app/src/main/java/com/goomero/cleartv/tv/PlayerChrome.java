package com.goomero.cleartv.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.goomero.cleartv.Panel;
import com.goomero.cleartv.Ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * The one player overlay and remote behaviour of every ClearTV player (Media3 for Tubi, YouTube live and Pluto
 * on-demand; YouTube's embed and Prime Video's web player): what a stock TV app does.
 * <ul>
 * <li>OK and play/pause toggle playback; play and pause keys do just that.</li>
 * <li>Left/right seek 10 s; held, they scrub (the target shows in the overlay and is applied on release), faster
 * the longer they are held. Rewind/fast-forward seek 30 s.</li>
 * <li>Up/down (and MENU) open the options: quality, captions, whatever the player adds.</li>
 * <li>Any key shows the overlay; it hides after 4 s without input. BACK closes the options, then the overlay,
 * then leaves.</li>
 * <li>A title with chapters ({@link #setChapters}, any service) gets a segmented bar, the current chapter's name
 * (the target's while scrubbing) and Skip sponsor in chapters named as a sponsor or ad read; with Settings >
 * Auto-skip sponsor chapters on, those are skipped by themselves, once each.</li>
 * </ul>
 * The player supplies a {@link Target}; the web players implement it with JavaScript so the page's own focus
 * never sees the remote.
 */
public final class PlayerChrome {
    public interface Target {
        boolean isLive();

        long positionMs();

        /** Duration, or <= 0 when unknown. */
        long durationMs();

        boolean isPlaying();

        void setPlaying(boolean play);

        void seekTo(long ms);

        /** Option rows for the options panel and what each does (quality first). */
        void options(List<String> labels, List<Runnable> actions);

        void exit();
    }

    private static final long HIDE_MS = 4000;
    private static final String TAG = "CTV.Chapters";
    /** Skip sponsor lands this much before the next chapter's start. */
    private static final long SEEK_EARLY_MS = 500;
    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Target target;
    private final Panel panel;
    private final LinearLayout overlay;
    private final TextView kick, title, sub, time, quality, scrubLabel;
    private final View progress, scrubMark;
    private final FrameLayout track;
    private final TextView pausedMark;
    /** Captions: ClearTV's own caption line and the CC chip in the controls (hidden while no tracks). */
    private final TextView captionView, ccChip;
    private Runnable ccToggle;
    private long scrubTo = -1;
    private int accent;
    /** Skip intro: the service's intro markers, or a skip button the web player shows (label + action). */
    private long introStart, introEnd, credits;
    private String extSkipLabel;
    private Runnable extSkip;
    private final TextView skipChip;
    /** What OK on the skip chip does: the page's button, the intro markers or the sponsor chapter. */
    private static final int SKIP_EXT = 1, SKIP_INTRO = 2, SKIP_SPONSOR = 3;
    private int skipKind;
    /** Chapters (null: none, the plain bar); the sponsor chapter the chip is for; chapters never auto-skipped again. */
    private ChapterList chapters;
    private int sponsorAt = -1;
    private final Set<Integer> noAutoSkip = new HashSet<>();
    /** The chapter just skipped and when (uptime): the web players' clock can report the old position for a tick. */
    private int skippedChapter = -1;
    private long skippedAt;
    private final ChapterBar chapterBar;
    private final TextView chapterLine, skipNote;
    private final Runnable hideNote = new Runnable() {
        @Override
        public void run() {
            skipNote.setVisibility(View.GONE);
        }
    };
    /** Auto-play next: the next title and how to start it; the countdown card. */
    private String nextTitle;
    private Runnable nextGo;
    private boolean nextCancelled;
    private int countdown = -1;
    private final LinearLayout nextCard;
    private final TextView nextLine, nextCount;
    /** The card's two remote targets: play now (0) and the Autoplay next switch (1); OK acts on the focused one. */
    private final TextView playChip, autoChip;
    private int nextFocus;
    /** A direction key whose press moved the card's focus: its release is swallowed too. */
    private int nextKeyHeld = -1;
    private Runnable progressSink;
    private long lastProgress;
    private static final int COUNTDOWN_S = 10;
    private static final long PROGRESS_MS = 15_000;
    /** Runs every second while the player is open: skip chip, next-episode countdown, saved progress. */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            watchTick();
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            if (!panel.isShowing() && scrubTo < 0 && target.isPlaying()) overlay.setVisibility(View.GONE);
            else main.postDelayed(this, HIDE_MS);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            if (overlay.getVisibility() == View.VISIBLE) main.postDelayed(this, 1000);
        }
    };

    public PlayerChrome(Context c, FrameLayout root, int accent, Target target) {
        this.c = c;
        this.accent = accent;
        this.target = target;
        pausedMark = Ui.kicker(c, "Paused", 14, Ui.V_TEXT);
        pausedMark.setPadding(Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1));
        GradientDrawable pm = Ui.rounded(0xCC060809, Ui.dp(c, Ui.PILL + 6));
        pm.setStroke(Ui.dp(c, 2), accent);
        pausedMark.setBackground(pm);
        pausedMark.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        plp.setMargins(0, Ui.dp(c, Ui.SAFE_Y), Ui.dp(c, Ui.SAFE_X), 0);
        root.addView(pausedMark, plp);

        overlay = new LinearLayout(c);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF0060809, 0x00060809}));
        overlay.setPadding(Ui.dp(c, Ui.SAFE_X), Ui.dp(c, Ui.S6 + Ui.S3), Ui.dp(c, Ui.SAFE_X), Ui.dp(c, Ui.SAFE_Y + Ui.S1));
        kick = Ui.oneLine(Ui.kicker(c, "", 12, accent));
        overlay.addView(kick);
        title = Ui.text(c, "", 24, Ui.V_TEXT);
        title.setTypeface(Ui.display(c));
        overlay.addView(Ui.oneLine(title), Ui.below(c, Ui.S_HALF));
        sub = Ui.oneLine(Ui.text(c, "", 14, Ui.V_MUTED));
        overlay.addView(sub, Ui.below(c, Ui.S_HALF));
        // the current chapter (the scrub target's while scrubbing); only for titles with chapters
        chapterLine = Ui.oneLine(Ui.text(c, "", 15, Ui.V_TEXT));
        chapterLine.setVisibility(View.GONE);
        overlay.addView(chapterLine, Ui.below(c, Ui.S1));
        track = new FrameLayout(c);
        track.setBackground(Ui.rounded(Ui.V_LINE, Ui.dp(c, 2)));
        progress = new View(c);
        progress.setBackground(Ui.rounded(accent, Ui.dp(c, 2)));
        progress.setPivotX(0);
        progress.setScaleX(0f);
        track.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // with chapters the bar is drawn as segments instead (the plain track and fill are hidden)
        chapterBar = new ChapterBar(c, accent);
        chapterBar.setVisibility(View.GONE);
        track.addView(chapterBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrubMark = new View(c);
        scrubMark.setBackground(Ui.rounded(Ui.V_TEXT, Ui.dp(c, 2)));
        scrubMark.setVisibility(View.GONE);
        track.addView(scrubMark, new FrameLayout.LayoutParams(Ui.dp(c, 4), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4));
        tlp.topMargin = Ui.dp(c, Ui.S2);
        tlp.bottomMargin = Ui.dp(c, Ui.S1);
        overlay.addView(track, tlp);
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        time = Ui.oneLine(Ui.kicker(c, "", 11, Ui.V_SOFT));
        foot.addView(time);
        scrubLabel = Ui.oneLine(Ui.kicker(c, "", 11, accent));
        scrubLabel.setPadding(Ui.dp(c, Ui.S2), 0, 0, 0);
        foot.addView(scrubLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        quality = Ui.oneLine(Ui.kicker(c, "", 11, Ui.V_SOFT));
        foot.addView(quality);
        ccChip = Ui.oneLine(Ui.kicker(c, "CC", 11, Ui.V_SOFT));
        ccChip.setPadding(Ui.dp(c, Ui.S1), Ui.dp(c, 1), Ui.dp(c, Ui.S1), Ui.dp(c, 1));
        ccChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(c, Ui.S2);
        foot.addView(ccChip, clp);
        overlay.addView(foot);
        TextView hint = Ui.oneLine(Ui.kicker(c, "OK play/pause   ·   ◀ ▶ seek, hold to scrub   ·   ▲ ▼ options   ·   BACK", 10, Ui.V_SOFT));
        overlay.addView(hint, Ui.below(c, Ui.S1));
        captionView = Ui.text(c, "", 22, 0xFFFFFFFF);
        captionView.setGravity(Gravity.CENTER);
        captionView.setBackgroundColor(0xB3000000);
        captionView.setPadding(Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S_HALF), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S_HALF));
        captionView.setLineSpacing(0, Ui.LINE_H);
        captionView.setVisibility(View.GONE);
        FrameLayout.LayoutParams cap = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cap.bottomMargin = Ui.dp(c, Ui.SAFE_Y + Ui.S2);
        cap.leftMargin = cap.rightMargin = Ui.dp(c, Ui.SAFE_X);
        root.addView(captionView, cap);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        skipChip = Ui.kicker(c, "Skip intro  ▸", 14, Ui.V_TEXT);
        skipChip.setPadding(Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S1 + 2), Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S1 + 2));
        GradientDrawable sk = Ui.rounded(0xE6060809, Ui.dp(c, Ui.PILL + 6));
        sk.setStroke(Ui.dp(c, 2), accent);
        skipChip.setBackground(sk);
        skipChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        slp.setMargins(0, 0, Ui.dp(c, 16), Ui.dp(c, Ui.SAFE_Y + 120));
        root.addView(skipChip, slp);
        // "Skipped sponsor": a short note in the chip's spot and style after an automatic skip (OK does nothing)
        skipNote = Ui.kicker(c, "Skipped sponsor", 14, Ui.V_TEXT);
        skipNote.setPadding(Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S1 + 2), Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S1 + 2));
        GradientDrawable sn = Ui.rounded(0xE6060809, Ui.dp(c, Ui.PILL + 6));
        sn.setStroke(Ui.dp(c, 2), accent);
        skipNote.setBackground(sn);
        skipNote.setVisibility(View.GONE);
        FrameLayout.LayoutParams nolp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        nolp.setMargins(0, 0, Ui.dp(c, 16), Ui.dp(c, Ui.SAFE_Y + 120));
        root.addView(skipNote, nolp);

        nextCard = new LinearLayout(c);
        nextCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable nb = Ui.rounded(0xF0101317, Ui.dp(c, Ui.RADIUS));
        nb.setStroke(Ui.dp(c, 2), accent);
        nextCard.setBackground(nb);
        nextCard.setPadding(Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S3), Ui.dp(c, Ui.S2));
        nextCard.addView(Ui.oneLine(Ui.kicker(c, "Up next", 11, accent)));
        nextLine = Ui.oneLine(Ui.text(c, "", 18, Ui.V_TEXT));
        nextCard.addView(nextLine, Ui.below(c, Ui.S_HALF));
        LinearLayout nextRow = new LinearLayout(c);
        nextRow.setOrientation(LinearLayout.HORIZONTAL);
        playChip = Ui.oneLine(Ui.kicker(c, "Play now", 12, Ui.V_TEXT));
        autoChip = Ui.oneLine(Ui.kicker(c, "", 12, Ui.V_TEXT));
        // focus between the two is kept here (dispatch), not by Android, so a web player's page never loses it
        for (TextView t : new TextView[]{playChip, autoChip}) {
            t.setPadding(Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.S1));
        }
        nextRow.addView(playChip);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = Ui.dp(c, Ui.S1);
        nextRow.addView(autoChip, alp);
        nextCard.addView(nextRow, Ui.below(c, Ui.S1));
        nextCount = Ui.oneLine(Ui.kicker(c, "", 11, Ui.V_MUTED));
        nextCard.addView(nextCount, Ui.below(c, Ui.S1));
        nextCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(Ui.dp(c, 520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        nlp.setMargins(0, 0, Ui.dp(c, 16), Ui.dp(c, Ui.SAFE_Y + 120));
        root.addView(nextCard, nlp);
        panel = new Panel(c, root);
        main.postDelayed(watch, 1000);
    }

    /** Intro and end-credit markers from the service (ms; 0 when unknown). */
    public void setMarkers(long introStartMs, long introEndMs, long creditsMs) {
        introStart = introStartMs;
        introEnd = introEndMs;
        credits = creditsMs;
    }

    /** A skip button the page's own player shows right now (Prime Video's "Skip Intro"), or null. */
    public void setExternalSkip(String label, Runnable action) {
        extSkipLabel = label;
        extSkip = action;
    }

    /**
     * The title's chapters (any service; YouTube today), or null for none: the bar shows one segment per chapter,
     * the controls name the current chapter, and sponsor chapters get Skip sponsor (or are skipped, see
     * {@link #autoSkipSponsor}). Without chapters the controls look as they always did.
     */
    public void setChapters(ChapterList list) {
        chapters = list;
        sponsorAt = -1;
        noAutoSkip.clear();
        chapterBar.setChapters(list);
        if (isShowing()) update();
        else showChapters(false);
    }

    /** Settings switch "Auto-skip sponsor chapters" (off by default): off, a sponsor chapter shows Skip sponsor. */
    public static boolean autoSkipSponsor(android.content.Context c) {
        return c.getSharedPreferences("cleartv", android.content.Context.MODE_PRIVATE).getBoolean("auto_skip_sponsor", false);
    }

    public static void setAutoSkipSponsor(android.content.Context c, boolean on) {
        c.getSharedPreferences("cleartv", android.content.Context.MODE_PRIVATE).edit().putBoolean("auto_skip_sponsor", on).apply();
    }

    /** "More like this" in the options (the title's similar titles in its service); null when the service has none. */
    public void setMoreLike(Runnable go) {
        moreLike = go;
    }

    private Runnable moreLike;

    /** The next episode and how to start it; null when there is none. */
    public void setNext(String title, Runnable go) {
        nextTitle = title;
        nextGo = go;
    }

    /** Called every 15 s of playback and when leaving: the player saves the position. */
    public void setProgressSink(Runnable r) {
        progressSink = r;
    }

    public void saveProgress() {
        if (progressSink != null) progressSink.run();
    }

    /** Settings switch "Autoplay next" (off by default): without it the Up next card waits for OK. */
    public static boolean autoplayNext(android.content.Context c) {
        return c.getSharedPreferences("cleartv", android.content.Context.MODE_PRIVATE).getBoolean("autoplay_next", false);
    }

    public static void setAutoplayNext(android.content.Context c, boolean on) {
        c.getSharedPreferences("cleartv", android.content.Context.MODE_PRIVATE).edit().putBoolean("autoplay_next", on).apply();
    }

    private boolean ended;

    /**
     * Playback reached the end. With Autoplay next on, the next title starts; with it off (the default) the
     * Up next card stays and waits (OK play now, BACK cancel, BACK again leaves). Nothing next: leave.
     */
    public void onEnded() {
        saveProgress();
        ended = true;
        if (nextGo != null && !nextCancelled && autoplayNext(c)) {
            playNext();
        } else if (nextGo != null && !nextCancelled) {
            showNextCard(false);
        } else {
            target.exit();
        }
    }

    private void playNext() {
        if (nextGo == null) return;
        countdown = -1;
        nextCard.setVisibility(View.GONE);
        saveProgress();
        Runnable go = nextGo;
        nextGo = null;
        go.run();
    }

    private void showNextCard(boolean counting) {
        if (!countdownVisible()) nextFocus = 0; // a fresh card starts on play now
        nextLine.setText(nextTitle);
        String keys = nextFocus == 0 ? "OK play now · ↓ autoplay · BACK cancel" : "OK switch · ↑ play now · BACK cancel";
        nextCount.setText(((counting ? "Starts in " + countdown + " s · " : "") + keys).toUpperCase(Locale.ROOT));
        autoChip.setText(autoplayNext(c) ? "AUTOPLAY: ON" : "AUTOPLAY: OFF");
        styleChip(playChip, nextFocus == 0);
        styleChip(autoChip, nextFocus == 1);
        nextCard.setVisibility(View.VISIBLE);
    }

    /** The card's chips look like the skip chip (accent stroke); the focused one is filled with the accent. */
    private void styleChip(TextView t, boolean focused) {
        GradientDrawable g = Ui.rounded(focused ? accent : 0xE6060809, Ui.dp(c, Ui.PILL + 6));
        g.setStroke(Ui.dp(c, 2), accent);
        t.setBackground(g);
        // dark text on a light accent (Tubi's yellow, Pluto's), as on the CC chip
        boolean light = (((accent >> 16 & 0xFF) * 299 + (accent >> 8 & 0xFF) * 587 + (accent & 0xFF) * 114) / 1000) > 150;
        t.setTextColor(focused && light ? 0xFF060809 : Ui.V_TEXT);
    }

    /**
     * OK on the card's Autoplay switch: the same saved setting as Settings > Autoplay next. On, the 10 s countdown
     * starts at once (also while the card waits at the end) and then plays next; off, the countdown stops and the
     * card waits for OK.
     */
    private void toggleAutoplay() {
        boolean on = !autoplayNext(c);
        setAutoplayNext(c, on);
        countdown = on ? COUNTDOWN_S : -1;
        showNextCard(on);
    }

    private boolean skipVisible() {
        return skipChip.getVisibility() == View.VISIBLE;
    }

    private boolean countdownVisible() {
        return nextCard.getVisibility() == View.VISIBLE;
    }

    private void watchTick() {
        long pos = target.positionMs(), dur = target.durationMs();
        boolean playing = target.isPlaying();
        // skip intro: the page's own button, else inside the service's intro markers
        String label = null;
        skipKind = 0;
        if (extSkip != null && extSkipLabel != null) {
            label = extSkipLabel;
            skipKind = SKIP_EXT;
        } else if (introEnd > introStart && pos >= introStart && pos < introEnd - 1000) {
            label = "Skip intro";
            skipKind = SKIP_INTRO;
        } else {
            // a sponsor chapter: skipped by itself once with the setting on (while playing, not mid-scrub), else the chip
            int sp = sponsorChapter(pos, dur);
            if (sp == skippedChapter && android.os.SystemClock.uptimeMillis() - skippedAt < 2500) sp = -1;
            sponsorAt = sp;
            if (sp >= 0 && playing && scrubTo < 0 && !noAutoSkip.contains(sp) && autoSkipSponsor(c)) {
                skipSponsor(sp, true);
            } else if (sp >= 0 && !countdownVisible()) {
                label = "Skip sponsor";
                skipKind = SKIP_SPONSOR;
            }
        }
        if (label != null && !panel.isShowing()) {
            skipChip.setText((label + "  ▸ OK").toUpperCase(Locale.ROOT));
            skipChip.setVisibility(View.VISIBLE);
        } else {
            skipChip.setVisibility(View.GONE);
        }
        // the next-episode countdown: from the end credits, else the last 10 s
        if (ended && nextGo != null && !nextCancelled) {
            // waiting on the card after the end; Autoplay switched on there counts down, then plays next
            if (countdown >= 0 && autoplayNext(c)) {
                countdown--;
                if (countdown <= 0) {
                    playNext();
                    return;
                }
                showNextCard(true);
            }
        } else if (nextGo != null && !nextCancelled && !target.isLive() && dur > 0) {
            long startAt = credits > 0 && credits < dur ? credits : dur - COUNTDOWN_S * 1000L;
            boolean auto = autoplayNext(c);
            if (pos >= startAt && playing && auto) {
                if (countdown < 0) countdown = COUNTDOWN_S;
                else countdown--;
                if (countdown <= 0) {
                    playNext();
                    return;
                }
                showNextCard(true);
            } else if (pos >= startAt && playing) {
                countdown = -1;
                showNextCard(false);
            } else if (pos < startAt) {
                countdown = -1;
                nextCard.setVisibility(View.GONE);
            }
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (playing && progressSink != null && now - lastProgress >= PROGRESS_MS) {
            lastProgress = now;
            progressSink.run();
        }
    }

    /** The sponsor chapter playing at {@code pos} (not in its last second), or -1. */
    private int sponsorChapter(long pos, long dur) {
        if (chapters == null || dur <= 0 || target.isLive()) return -1;
        int i = chapters.indexAt(pos);
        if (!chapters.isSponsor(i) || pos >= chapters.end(i, dur) - 1000) return -1;
        return i;
    }

    /**
     * Past sponsor chapter {@code i}: to the next chapter's start, or the end for the last chapter. A chapter is
     * skipped automatically only once; after that (or after seeking back into it) it shows the chip.
     */
    private void skipSponsor(int i, boolean auto) {
        long dur = target.durationMs();
        noAutoSkip.add(i);
        skippedChapter = i;
        skippedAt = android.os.SystemClock.uptimeMillis();
        // an absolute time, never a step from the position: the next chapter's start (0.5 s early, for players that
        // land on the next keyframe, but never before this chapter's start), or the end for the last chapter
        final long from = target.positionMs();
        long to = i + 1 < chapters.size() ? Math.max(chapters.start(i) + 1, chapters.start(i + 1) - SEEK_EARLY_MS)
                : Math.max(0, dur - 1000);
        target.seekTo(to);
        Log.i(TAG, (auto ? "auto-skipped" : "skipped") + " sponsor chapter " + (i + 1) + "/" + chapters.size() + " from "
                + fmtMs(from) + " to " + fmtMs(to) + " (next chapter starts " + (i + 1 < chapters.size() ? fmtMs(chapters.start(i + 1)) : "-") + ")");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "after the sponsor skip the player is at " + fmtMs(target.positionMs()));
            }
        }, 2000);
        skipChip.setVisibility(View.GONE);
        sponsorAt = -1;
        if (auto) {
            skipNote.setVisibility(View.VISIBLE);
            main.removeCallbacks(hideNote);
            main.postDelayed(hideNote, 3000);
        }
        if (isShowing()) update();
    }

    /** A seek the viewer asked for: one that goes back into a sponsor chapter is never skipped automatically. */
    private void userSeek(long to) {
        if (chapters != null) {
            int i = chapters.indexAt(to);
            if (to < target.positionMs() && chapters.isSponsor(i)) noAutoSkip.add(i);
        }
        target.seekTo(to);
    }

    private void doSkip() {
        if (skipKind == SKIP_SPONSOR && sponsorAt >= 0 && chapters != null) {
            skipSponsor(sponsorAt, false);
            return;
        }
        if (extSkip != null && extSkipLabel != null) {
            extSkip.run();
            extSkipLabel = null;
        } else if (introEnd > 0) {
            target.seekTo(introEnd);
        }
        skipChip.setVisibility(View.GONE);
    }

    public void setInfo(String service, String titleText, String subText) {
        kick.setText(((service == null ? "" : service) + (target.isLive() ? " · Live" : "")).toUpperCase(Locale.ROOT));
        title.setText(titleText == null ? "" : titleText);
        sub.setText(subText == null ? "" : subText);
        sub.setVisibility(subText == null || subText.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** "QUALITY AUTO · 1920×1080 · 5.1 MBPS". */
    /**
     * The CC chip in the controls: {@code null} while the title has no caption tracks, else the chosen track's
     * name, or "" when captions are off. {@code toggle} runs on the remote's CAPTIONS key.
     */
    public void setCaptions(String label, Runnable toggle) {
        ccToggle = toggle;
        if (label == null) {
            ccChip.setVisibility(View.GONE);
            showCaption(null);
            return;
        }
        boolean on = !label.isEmpty();
        ccChip.setText(on ? ("CC · " + label).toUpperCase(Locale.ROOT) : "CC OFF");
        // on a light accent (Tubi's yellow, Pluto's) white text was unreadable: dark text there
        ccChip.setTextColor(on ? (((accent >> 16 & 0xFF) * 299 + (accent >> 8 & 0xFF) * 587 + (accent & 0xFF) * 114) / 1000 > 150 ? 0xFF060809 : Ui.V_TEXT) : Ui.V_SOFT);
        GradientDrawable g = Ui.rounded(on ? accent : 0x00000000, Ui.dp(c, 3));
        if (!on) g.setStroke(Ui.dp(c, 1), Ui.V_SOFT);
        ccChip.setBackground(g);
        ccChip.setVisibility(View.VISIBLE);
        if (!on) showCaption(null);
    }

    /** One caption cue on screen (null or empty clears it), above the controls while they show. */
    public void showCaption(CharSequence text) {
        if (text == null || text.length() == 0) {
            captionView.setVisibility(View.GONE);
            return;
        }
        if (!text.toString().contentEquals(captionView.getText())) captionView.setText(text);
        placeCaption();
        captionView.setVisibility(View.VISIBLE);
    }

    /** Captions sit just above the controls while they show, at the bottom safe margin otherwise. */
    private void placeCaption() {
        int lift = overlay.getVisibility() == View.VISIBLE ? overlay.getHeight() - overlay.getPaddingTop() - Ui.dp(c, Ui.SAFE_Y) : 0;
        captionView.setTranslationY(-Math.max(0, lift));
    }

    public void setQuality(String text) {
        quality.setText(text == null ? "" : text.toUpperCase(Locale.ROOT));
    }

    public Target target() {
        return target;
    }

    public boolean isShowing() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    public boolean optionsShowing() {
        return panel.isShowing();
    }

    public void show() {
        overlay.setVisibility(View.VISIBLE);
        update();
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_MS);
        main.removeCallbacks(tick);
        main.post(tick);
        overlay.post(new Runnable() {
            @Override
            public void run() {
                placeCaption();
            }
        });
    }

    public void hide() {
        overlay.setVisibility(View.GONE);
        main.removeCallbacks(hide);
        placeCaption();
    }

    /** Playing state changed: the paused badge, and the overlay stays up while paused. */
    public void onPlayingChanged() {
        boolean playing = target.isPlaying();
        pausedMark.setVisibility(playing ? View.GONE : View.VISIBLE);
        show();
    }

    public void hidePausedMark() {
        pausedMark.setVisibility(View.GONE);
    }

    public void update() {
        long pos = target.positionMs(), dur = target.durationMs();
        if (target.isLive() || dur <= 0) {
            showChapters(false);
            time.setText("LIVE");
            progress.setScaleX(1f);
            scrubMark.setVisibility(View.GONE);
            scrubLabel.setText("");
            return;
        }
        progress.setScaleX(Math.max(0f, Math.min(1f, pos / (float) dur)));
        if (chapters != null) {
            showChapters(true);
            chapterBar.setPosition(pos / (float) dur, dur);
            int at = chapters.indexAt(scrubTo >= 0 ? scrubTo : pos);
            String line = "Chapter " + (at + 1) + " of " + chapters.size() + "  ·  ";
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder((scrubTo >= 0 ? (scrubTo >= pos ? "▶▶ " : "◀◀ ") : "") + line);
            sb.setSpan(new android.text.style.ForegroundColorSpan(accent), 0, sb.length(), 0);
            int from = sb.length();
            sb.append(chapters.title(at));
            // while scrubbing the target chapter's name is in the accent too
            if (scrubTo >= 0) sb.setSpan(new android.text.style.ForegroundColorSpan(accent), from, sb.length(), 0);
            if (!sb.toString().contentEquals(chapterLine.getText())) chapterLine.setText(sb);
        }
        time.setText(fmt(pos) + " / " + fmt(dur));
        if (scrubTo >= 0 && track.getWidth() > 0) {
            scrubMark.setVisibility(View.VISIBLE);
            scrubMark.setTranslationX(track.getWidth() * Math.max(0f, Math.min(1f, scrubTo / (float) dur)) - scrubMark.getWidth() / 2f);
            long d = scrubTo - pos;
            scrubLabel.setText((d >= 0 ? "▶▶ " : "◀◀ ") + fmt(scrubTo) + "  (" + (d >= 0 ? "+" : "−") + fmt(Math.abs(d)) + ")");
        } else {
            scrubMark.setVisibility(View.GONE);
            scrubLabel.setText("");
        }
    }

    /** Segmented bar and chapter line on (a title with chapters and a duration), or the plain bar. */
    private void showChapters(boolean on) {
        on = on && chapters != null;
        int v = on ? View.VISIBLE : View.GONE;
        if (chapterBar.getVisibility() == v) return;
        chapterBar.setVisibility(v);
        chapterLine.setVisibility(v);
        progress.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        track.setBackground(on ? null : Ui.rounded(Ui.V_LINE, Ui.dp(c, 2)));
    }

    /**
     * The progress bar of a title with chapters: one rounded segment per chapter with a small gap between them,
     * filled with the accent up to the position. Drawn in one pass, no views per chapter.
     */
    private static final class ChapterBar extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float gap, radius;
        private ChapterList list;
        private long dur;
        private float frac;

        ChapterBar(Context c, int accent) {
            super(c);
            line.setColor(Ui.V_LINE);
            fill.setColor(accent);
            gap = Ui.dp(c, 4);
            radius = Ui.dp(c, 2);
        }

        void setChapters(ChapterList l) {
            list = l;
            invalidate();
        }

        void setPosition(float f, long durationMs) {
            f = Math.max(0f, Math.min(1f, f));
            if (f == frac && durationMs == dur) return;
            frac = f;
            dur = durationMs;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (list == null || dur <= 0) return;
            float w = getWidth(), h = getHeight(), px = w * frac;
            int n = list.size();
            for (int i = 0; i < n; i++) {
                if (list.start(i) >= dur) break;
                float x0 = w * list.start(i) / dur + (i > 0 ? gap / 2 : 0);
                float x1 = w * Math.min(dur, list.end(i, dur)) / dur - (i < n - 1 ? gap / 2 : 0);
                if (x1 - x0 < 1) continue;
                r.set(x0, 0, x1, h);
                cv.drawRoundRect(r, radius, radius, line);
                if (px > x0) {
                    r.set(x0, 0, Math.min(px, x1), h);
                    cv.drawRoundRect(r, radius, radius, fill);
                }
            }
        }
    }

    /** "2:07.000" for the log. */
    private static String fmtMs(long ms) {
        return fmt(ms) + String.format(Locale.ROOT, ".%03d", Math.max(0, ms) % 1000);
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }

    public void options() {
        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        target.options(labels, actions);
        if (moreLike != null) {
            labels.add("More like this");
            actions.add(moreLike);
        }
        if (labels.isEmpty()) return;
        show();
        panel.show("Options", labels.toArray(new String[0]), new Panel.OnPick() {
            @Override
            public void onPick(final int which) {
                // the panel is closed by now: an action may open the next panel (the quality list)
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        actions.get(which).run();
                        show();
                    }
                });
            }
        });
    }

    /** A second-level list (the quality choices) in the same panel. */
    public void pick(String heading, String[] rows, Panel.OnPick pick) {
        show();
        panel.show(heading, rows, pick);
    }

    private boolean seekable() {
        return !target.isLive() && target.durationMs() > 0;
    }

    /** Seek step while a direction key is held: 10 s, then 30 s, then 60 s. */
    private static long step(int repeat) {
        return repeat < 10 ? 10_000 : repeat < 30 ? 30_000 : 60_000;
    }

    /** Every key of the player window; returns true when handled. */
    public boolean dispatch(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN, up = ev.getAction() == KeyEvent.ACTION_UP;
        if (panel.isShowing()) {
            main.removeCallbacks(hide);
            main.postDelayed(hide, HIDE_MS);
            if (panel.handleKey(ev)) return true;
            return false; // focus moves among the panel's rows
        }
        if (countdownVisible()) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (down && ev.getRepeatCount() == 0) {
                    if (nextFocus == 1) toggleAutoplay();
                    else playNext();
                }
                return true;
            }
            // down/right: to the Autoplay switch; up/left: back to play now (from play now they keep their usual meaning)
            boolean toAuto = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean toPlay = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT;
            if (toAuto || (toPlay && (nextFocus == 1 || nextKeyHeld == code))) {
                if (down) {
                    nextFocus = toAuto ? 1 : 0;
                    nextKeyHeld = code;
                    showNextCard(countdown >= 0 && autoplayNext(c));
                } else if (up) {
                    nextKeyHeld = -1;
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) {
                    nextCancelled = true;
                    nextCard.setVisibility(View.GONE);
                    if (ended) show(); // stopped at the end: the controls stay, BACK again leaves
                }
                return true;
            }
        }
        if (skipVisible() && !isShowing() && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (down && ev.getRepeatCount() == 0) doSkip();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (up) {
                    if (scrubTo >= 0) {
                        scrubTo = -1;
                        update();
                    } else if (isShowing()) {
                        hide();
                    } else {
                        saveProgress();
                        target.exit();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (down && ev.getRepeatCount() == 0) {
                    if (scrubTo >= 0) {
                        commitScrub();
                    } else {
                        target.setPlaying(!target.isPlaying());
                    }
                    show();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) target.setPlaying(true);
                show();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (down) target.setPlaying(false);
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (!seekable()) {
                    if (down) show();
                    return true;
                }
                if (down) {
                    if (ev.getRepeatCount() == 0) {
                        userSeek(clamp(target.positionMs() + dir * 10_000L));
                    } else {
                        // held: scrub a target, applied on release so the stream seeks once
                        if (scrubTo < 0) scrubTo = target.positionMs();
                        scrubTo = clamp(scrubTo + dir * step(ev.getRepeatCount()));
                    }
                    show();
                } else if (up && scrubTo >= 0) {
                    commitScrub();
                }
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD: {
                boolean fwd = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
                        || code == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD;
                if (down && seekable()) userSeek(clamp(target.positionMs() + (fwd ? 1 : -1) * (ev.getRepeatCount() > 6 ? 60_000L : 30_000L)));
                if (down) show();
                return true;
            }
            case KeyEvent.KEYCODE_CAPTIONS:
                if (up && ccToggle != null) {
                    ccToggle.run();
                    show();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                if (up) options();
                return true;
            default:
                if (down) show();
                return false;
        }
    }

    private void commitScrub() {
        long to = scrubTo;
        scrubTo = -1;
        if (to >= 0) userSeek(to);
        update();
    }

    private long clamp(long ms) {
        long d = target.durationMs();
        return Math.max(0, d > 0 ? Math.min(d - 1000, ms) : ms);
    }

    public void release() {
        saveProgress();
        main.removeCallbacksAndMessages(null);
    }
}
