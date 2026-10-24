package com.goomero.cleartv.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A title's chapters (start times and names), for any service that has them; YouTube is the source today
 * ({@link Source#chapters}). The player chrome draws one bar segment per chapter, names the current one and offers
 * Skip sponsor in chapters whose name says they are a sponsor or ad read.
 */
public final class ChapterList {
    private final long[] starts;
    private final String[] titles;

    private ChapterList(long[] starts, String[] titles) {
        this.starts = starts;
        this.titles = titles;
    }

    /** Builds a list from parallel start (ms) and title lists; null unless there are at least 2 ascending chapters. */
    public static ChapterList of(List<Long> startMs, List<String> names) {
        if (startMs == null || names == null || startMs.size() != names.size() || startMs.size() < 2) return null;
        long[] s = new long[startMs.size()];
        String[] t = new String[s.length];
        for (int i = 0; i < s.length; i++) {
            s[i] = startMs.get(i);
            if (s[i] < 0 || (i > 0 && s[i] <= s[i - 1])) return null;
            String n = names.get(i);
            t[i] = n == null ? "" : n.trim();
        }
        return new ChapterList(s, t);
    }

    public int size() {
        return starts.length;
    }

    public long start(int i) {
        return starts[i];
    }

    /** Where chapter {@code i} ends: the next chapter's start, else the duration (or Long.MAX_VALUE when unknown). */
    public long end(int i, long durationMs) {
        return i + 1 < starts.length ? starts[i + 1] : durationMs > 0 ? durationMs : Long.MAX_VALUE;
    }

    public String title(int i) {
        return titles[i];
    }

    /** The chapter playing at {@code ms} (the first one before its start). */
    public int indexAt(long ms) {
        int lo = 0, hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= ms) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /**
     * A chapter named as a sponsor or ad read: sponsor(ed/ship/ing/s), ad, ads, ad break, advert(isement),
     * promo(tion), paid promotion, "this video is sponsored", "thanks to X for sponsoring". "Ad" counts only as a
     * whole word ("adventure", "load", "Adam" and "ad-free" do not match).
     */
    private static final Pattern SPONSOR = Pattern.compile(
            "\\b(sponsor(s|ed|ship|ships|ing)?|ads?(?![- ]?free\\b)|advert(s|isement|isements|ising)?|promo(s|tion|tions)?)\\b",
            Pattern.CASE_INSENSITIVE);

    public static boolean isSponsor(String title) {
        return title != null && SPONSOR.matcher(title).find();
    }

    public boolean isSponsor(int i) {
        return isSponsor(titles[i]);
    }

    /** A description line that starts a chapter: "0:00 Intro", "1:02:03 - Title", "(4:05) Title", "12:30: Title". */
    private static final Pattern LINE = Pattern.compile(
            "^\\s*[(\\[]?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})[)\\]]?\\s*(?:[-–—:|•·]\\s*)?(\\S.*?)\\s*$");

    /**
     * Chapters written in a description the way services publish them (YouTube's rules: the first at 0:00, at
     * least 3, ascending, each at least 10 s long). Null when the text has no such list.
     */
    public static ChapterList fromText(String text) {
        if (text == null || text.isEmpty()) return null;
        List<Long> s = new ArrayList<>();
        List<String> t = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.find()) continue;
            long ms = parseClock(m.group(1));
            if (s.isEmpty() && ms != 0) continue;
            if (!s.isEmpty() && ms < s.get(s.size() - 1) + 10_000) {
                if (ms <= s.get(s.size() - 1)) break; // a second list (or a timestamp in the text): stop at the first
                continue;
            }
            s.add(ms);
            t.add(m.group(2));
        }
        return s.size() >= 3 ? of(s, t) : null;
    }

    static long parseClock(String c) {
        String[] p = c.split(":");
        long sec = 0;
        for (String x : p) sec = sec * 60 + Long.parseLong(x);
        return sec * 1000;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%d chapters", starts.length);
    }
}
