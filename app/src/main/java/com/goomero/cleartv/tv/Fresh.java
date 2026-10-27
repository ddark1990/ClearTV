package com.goomero.cleartv.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Freshness for YouTube rows. A shelf is reordered so videos already shown lead only after the ones that have not
 * been, and a channel's score gains a bonus for a recent upload and a modest view count (and loses a little when
 * the same channel led the last visit). No topic list is kept: the caller decides which videos are candidates.
 */
final class Fresh {
    private static final Pattern AGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s+(minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEWS = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*([KMB])?\\s+views?\\b", Pattern.CASE_INSENSITIVE);

    /** Knobs for {@link #tune}. Defaults match the bundled config. */
    static final class Tune {
        double boost = 1;
        /** Taken off a channel that led the previous visit, so the next one is not the same row. */
        double penalty = 0.8;
        /** No freshness bonus below this descriptor match, when a descriptor was used. */
        double minMatch = 0.22;
        /** True when {@code Group.match} was filled by a descriptor. */
        boolean haveMatch;
        double recentDays = 14, monthDays = 45;
        long modestViews = 20_000, midViews = 150_000, megaViews = 2_000_000;
    }

    private Fresh() {}

    /**
     * Days since upload, from a tile's second line ("3 days ago", "2 weeks ago", "6 hours ago"), or -1 when it
     * doesn't say. The newest mention wins.
     */
    static double ageDays(String subtitle) {
        if (subtitle == null || subtitle.isEmpty()) return -1;
        Matcher m = AGE.matcher(subtitle);
        double best = -1;
        while (m.find()) {
            double n;
            try {
                n = Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            double days;
            if (unit.startsWith("minute")) days = n / (24 * 60);
            else if (unit.startsWith("hour")) days = n / 24;
            else if (unit.startsWith("day")) days = n;
            else if (unit.startsWith("week")) days = n * 7;
            else if (unit.startsWith("month")) days = n * 30;
            else days = n * 365;
            if (best < 0 || days < best) best = days;
        }
        return best;
    }

    /** View count from a second line ("1.7K views", "15 views"), or -1 when it doesn't say. */
    static long viewCount(String subtitle) {
        if (subtitle == null || subtitle.isEmpty()) return -1;
        Matcher m = VIEWS.matcher(subtitle);
        if (!m.find()) return -1;
        double n;
        try {
            n = Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
        String u = m.group(2);
        if (u != null && !u.isEmpty()) {
            char c = u.charAt(0);
            if (c == 'K' || c == 'k') n *= 1_000;
            else if (c == 'M' || c == 'm') n *= 1_000_000;
            else n *= 1_000_000_000L;
        }
        return n < 0 ? -1 : (long) n;
    }

    /**
     * Reorders {@code items} in place: videos whose id is in {@code seen} move to the end, and each part keeps its
     * order. When every video was already seen, the row rotates by {@code rotate} places so the same video does not
     * lead every visit. Returns how many videos moved back, or the rotate distance when the whole row was seen.
     */
    static int demote(List<Item> items, Set<String> seen, int rotate) {
        if (items == null || items.size() < 2) return 0;
        List<Item> fresh = new ArrayList<>(), old = new ArrayList<>();
        for (Item it : items) {
            if (it != null && seen != null && seen.contains(it.id)) old.add(it);
            else fresh.add(it);
        }
        if (fresh.isEmpty()) {
            if (old.size() < 2) return 0;
            int r = Math.floorMod(rotate, old.size());
            if (r == 0) return 0;
            Collections.rotate(old, -r);
            items.clear();
            items.addAll(old);
            return r;
        }
        if (old.isEmpty()) return 0;
        items.clear();
        items.addAll(fresh);
        items.addAll(old);
        return old.size();
    }

    /**
     * Which videos to expand into a fresh search. The newest watch is always included; a second one rotates through
     * the rest so two visits do not ask for the same pair. With no watches, seeds come from the page, starting at
     * {@code visit}.
     */
    static List<Item> pickSeeds(List<Item> personal, List<Item> pageVideos, int visit, int n) {
        List<Item> out = new ArrayList<>();
        if (n <= 0) return out;
        if (personal != null && !personal.isEmpty()) {
            out.add(personal.get(0));
            if (n > 1 && personal.size() > 1) {
                int i = 1 + Math.floorMod(visit, personal.size() - 1);
                Item extra = personal.get(i);
                if (extra != null && !extra.id.equals(personal.get(0).id)) out.add(extra);
            }
            return out;
        }
        if (pageVideos == null || pageVideos.isEmpty()) return out;
        Set<String> ids = new HashSet<>();
        for (int k = 0; k < pageVideos.size() && out.size() < n; k++) {
            Item it = pageVideos.get(Math.floorMod(visit + k, pageVideos.size()));
            if (it != null && it.id != null && ids.add(it.id)) out.add(it);
        }
        return out;
    }

    /**
     * How much to add for one video's age and audience. Recent and modest count up; a very large audience counts
     * down. Unknown age or views add nothing.
     */
    static double signal(double ageDays, long views, Tune t) {
        if (t == null) return 0;
        double s = 0;
        if (ageDays >= 0) {
            if (ageDays <= t.recentDays) s += 1.3;
            else if (ageDays <= t.monthDays) s += 0.55;
        }
        if (views >= 0) {
            if (views < t.modestViews) s += 1.1;
            else if (views < t.midViews) s += 0.4;
            else if (views >= t.megaViews) s -= 0.6;
        }
        return s;
    }

    /**
     * Adds the freshness bonus (when the channel is on-topic, or when no descriptor ran) and subtracts
     * {@link Tune#penalty} for channels in {@code shown}, then sorts again. Best-matching video's second line is
     * the one that counts.
     */
    static void tune(List<Similar.Group> groups, Set<String> shown, Tune t) {
        if (groups == null || groups.isEmpty() || t == null) return;
        for (Similar.Group g : groups) {
            double age = -1;
            long views = -1;
            int n = Math.min(4, g.items.size());
            for (int i = 0; i < n; i++) {
                String sub = g.items.get(i).subtitle;
                if (age < 0) age = ageDays(sub);
                if (views < 0) views = viewCount(sub);
            }
            if (!t.haveMatch || g.match >= t.minMatch) g.score += t.boost * signal(age, views, t);
            if (shown != null && shown.contains(g.key)) g.score -= t.penalty;
        }
        Collections.sort(groups, new Comparator<Similar.Group>() {
            @Override
            public int compare(Similar.Group a, Similar.Group b) {
                int c = Double.compare(b.score, a.score);
                return c != 0 ? c : Integer.compare(a.first, b.first);
            }
        });
    }
}
