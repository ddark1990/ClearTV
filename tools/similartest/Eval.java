package com.goomero.cleartv.tv;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "More like this" ranking offline: the descriptor and its searches for a video ("q src"), or the top new channels
 * before (related lists only) and after (plus the searches and the descriptor's fit) for saved lists
 * ("rank src lists more"). The lists are YouTube's answers saved as TSV (see the QA notes); no network here.
 */
public final class Eval {
    static String un(String s) {
        return s.replace("\\n", "\n").replace("\\\\", "\\");
    }

    static Descriptor.Source src(String path, String[] owner) throws IOException {
        Descriptor.Source s = new Descriptor.Source();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            for (String l; (l = r.readLine()) != null; ) {
                String[] f = l.split("\t", -1);
                String v = f.length > 1 ? un(f[1]) : "";
                switch (f[0]) {
                    case "title": s.title = v; break;
                    case "owner": s.owner = v; break;
                    case "ownerId": owner[0] = v; break;
                    case "category": s.category = v; break;
                    case "description": s.description = v; break;
                    case "keyword": s.keywords.add(v); break;
                    case "chapter": s.chapters.add(v); break;
                    case "topic": s.topics.add(v); break;
                    default:
                }
            }
        }
        owner[1] = s.owner;
        return s;
    }

    static final class L {
        String name;
        String w;
        final List<Item> items = new ArrayList<>();
    }

    static List<L> lists(String path, Map<String, String> snippets) throws IOException {
        List<L> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            for (String l; (l = r.readLine()) != null; ) {
                String[] f = l.split("\t", -1);
                if (f[0].equals("list")) {
                    L x = new L();
                    x.name = f[1];
                    x.w = f[2];
                    out.add(x);
                } else if (f[0].equals("item")) {
                    Item it = new Item(Item.VIDEO, f[1], un(f[4]), un(f[3]), "", "", "");
                    if (f[2].startsWith("UC")) it.channel = new Item(Item.CHANNEL, f[2], un(f[3]), "Channel", "", "", "");
                    if (f.length > 5 && !f[5].isEmpty()) snippets.put(f[1], un(f[5]));
                    out.get(out.size() - 1).items.add(it);
                }
            }
        }
        return out;
    }

    static Similar sim(final String srcVideo) {
        return new Similar(new Similar.Key() {
            @Override
            public String of(Item x) {
                if (x.id.equals(srcVideo)) return null;
                String n = x.channel != null ? x.channel.title : x.subtitle;
                return n.isEmpty() ? null : n.toLowerCase(Locale.ROOT).trim();
            }
        });
    }

    static String idOf(Similar.Group g) {
        for (Item x : g.items) if (x.channel != null) return x.channel.id;
        return "";
    }

    static List<String> top(List<Similar.Group> ranked, String[] owner, int n, boolean detail) {
        List<String> out = new ArrayList<>();
        for (Similar.Group g : ranked) {
            String o = owner[1].toLowerCase(Locale.ROOT);
            if (idOf(g).equals(owner[0]) || g.key.equals(o) || g.key.startsWith(o + " and ") || g.key.endsWith(" and " + o)) continue;
            if (out.size() >= n) break;
            out.add(detail ? String.format(Locale.ROOT, "%-28s %5.2f m=%.2f  %s", g.items.get(0).channel != null
                    ? g.items.get(0).channel.title : g.key, g.score, g.match, cut(g.items.get(0).title)) : g.key);
        }
        return out;
    }

    static String cut(String s) {
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    public static void main(String[] a) throws IOException {
        Descriptor.Weights w = new Descriptor.Weights();
        String[] owner = new String[2];
        Descriptor.Source s = src(a[1], owner);
        Descriptor d = new Descriptor(s, w);
        int nq = Integer.parseInt(System.getProperty("searches", "2"));
        if (a[0].equals("q")) {
            Map<String, String> sn = new HashMap<>();
            Similar first = sim(a[1].replaceAll(".*/|\\.src$", ""));
            for (L l : lists(a[2], sn)) first.add(l.items, Double.parseDouble(l.w));
            Descriptor.Scorer sc = d.scorer(first.ranked(), sn, w);
            System.out.println("descriptor: " + d.summary(16));
            for (String q : d.queries(nq, sc)) System.out.println("query\t" + q);
            // seeds: the first video of each other channel in the related column
            Set<String> ch = new LinkedHashSet<>();
            StringBuilder seeds = new StringBuilder();
            for (L l : lists(a[2], sn)) for (Item x : l.items) {
                String k = x.channel != null ? x.channel.id : x.subtitle;
                if (k.equals(owner[0]) || x.subtitle.equalsIgnoreCase(owner[1]) || !ch.add(k) || ch.size() > 4) continue;
                seeds.append(seeds.length() > 0 ? "," : "").append(x.id);
            }
            System.out.println("seeds\t" + seeds);
            return;
        }
        double searchW = Double.parseDouble(System.getProperty("searchW", "0.3"));
        double fitW = Double.parseDouble(System.getProperty("fitW", "2"));
        String video = a[1].replaceAll(".*/|\\.src$", "");
        Map<String, String> snippets = new HashMap<>();
        List<L> ls = lists(a[2], snippets);
        if (a.length > 3) ls.addAll(lists(a[3], snippets));
        Similar before = sim(video), after = sim(video);
        for (L l : ls) {
            if (!l.w.equals("S")) {
                before.add(l.items, Double.parseDouble(l.w));
                after.add(l.items, Double.parseDouble(l.w));
            } else {
                after.add(l.items, searchW);
            }
        }
        List<Similar.Group> all = after.ranked();
        Set<String> related = new java.util.HashSet<>();
        for (Similar.Group g : before.ranked()) related.add(g.key);
        w.searchFit = Double.parseDouble(System.getProperty("searchFit", "0.5"));
        Similar.Fit fit = d.fit(all, snippets, related, w);
        // before keeps its own order (weight 0, no miss penalty) but shows the descriptor match for comparison
        List<String> b = top(before.ranked(fit, 0, 0), owner, 10, true);
        List<String> ft = top(after.ranked(fit, fitW, Double.parseDouble(System.getProperty("miss", "0.6"))), owner, 10, true);
        System.out.println("descriptor: " + d.summary(12));
        System.out.println("-- before");
        for (String x : b) System.out.println("  " + x);
        System.out.println("-- after");
        for (String x : ft) System.out.println("  " + x);
    }
}
