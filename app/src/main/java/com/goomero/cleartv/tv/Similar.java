package com.goomero.cleartv.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "More like this" ranking: tiles gathered from several of a service's own similar lists (the title's own, then the
 * lists of a few of those titles) are grouped by source (a YouTube channel, a show or film) and the groups ranked by
 * how often and how high they appear. A group that turns up in several lists is the strongest similarity signal.
 */
final class Similar {
    /** One group (a channel or a title) with its tiles in the order they were found. */
    static final class Group {
        final String key;
        String id = "", name = "", image = "";
        final List<Item> items = new ArrayList<>();
        double score;
        int lists, first = Integer.MAX_VALUE;

        Group(String key) {
            this.key = key;
        }
    }

    /** Maps a tile to its group key (lower case), or null to leave the tile out. */
    interface Key {
        String of(Item x);
    }

    private final Key key;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Set<String> ids = new HashSet<>();
    private int order;

    Similar(Key key) {
        this.key = key;
    }

    /**
     * Adds one similar list with weight {@code w}: a group's first tile in the list scores w (1.5 w in the list's
     * first eight), each further tile in the same list 0.15 w. Tiles already seen are not added twice.
     */
    synchronized void add(List<Item> list, double w) {
        if (list == null) return;
        Set<String> inList = new HashSet<>();
        int pos = 0;
        for (Item x : list) {
            if (x == null) continue;
            String k = key.of(x);
            if (k == null || k.isEmpty()) continue;
            Group g = groups.get(k);
            if (g == null) {
                g = new Group(k);
                groups.put(k, g);
            }
            if (g.first == Integer.MAX_VALUE) g.first = order;
            order++;
            if (inList.add(k)) {
                g.score += w * (pos < 8 ? 1.5 : 1.0);
                g.lists++;
            } else {
                g.score += w * 0.15;
            }
            if (ids.add(x.id)) g.items.add(x);
            pos++;
        }
    }

    /** The group key of a tile (null when it has none). */
    String keyOf(Item x) {
        return key.of(x);
    }

    synchronized Group group(String k) {
        return groups.get(k);
    }

    /** Groups by score (ties: the one found first), copied so the caller may read them while more lists arrive. */
    synchronized List<Group> ranked() {
        List<Group> out = new ArrayList<>(groups.values());
        Collections.sort(out, new Comparator<Group>() {
            @Override
            public int compare(Group a, Group b) {
                int c = Double.compare(b.score, a.score);
                return c != 0 ? c : Integer.compare(a.first, b.first);
            }
        });
        List<Group> copy = new ArrayList<>(out.size());
        for (Group g : out) {
            Group c = new Group(g.key);
            c.id = g.id;
            c.name = g.name;
            c.image = g.image;
            c.items.addAll(g.items);
            c.score = g.score;
            c.lists = g.lists;
            c.first = g.first;
            copy.add(c);
        }
        return copy;
    }

    private static final Pattern SEASON = Pattern.compile(
            "(?i)\\s*([:\\-–(,]\\s*)?(the\\s+)?(complete\\s+)?(season|series|staffel|volume|vol\\.|part)\\s*\\d+.*$");
    private static final Pattern YEAR = Pattern.compile("\\s*\\((19|20)\\d\\d\\)\\s*$");

    /**
     * A title without its season, part or year ("Show: Season 2", "Show (2019)" -> "show"), so one show's seasons,
     * versions and its live channel count as one title.
     */
    static String titleKey(String title) {
        if (title == null) return "";
        String t = YEAR.matcher(title.trim()).replaceAll("");
        t = SEASON.matcher(t).replaceAll("");
        t = t.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^\\p{L}\\p{N}' ]+", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? title.toLowerCase(Locale.ROOT).trim() : t;
    }
}
