package com.goomero.cleartv.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Freshness checks: age and audience, shelf reorder, and a small recent channel beating a repeat mega channel. */
public final class FreshTest {
    private static int failed;

    static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static Item video(String id, String channel, String subtitle) {
        Item x = new Item(Item.VIDEO, id, id, subtitle, "", "", "");
        x.channel = new Item(Item.CHANNEL, "UC" + channel.replace(' ', '_'), channel, "Channel", "", "", "");
        return x;
    }

    public static void main(String[] a) {
        check(Fresh.ageDays("6 hours ago") > 0 && Fresh.ageDays("6 hours ago") < 1, "hours");
        check(Fresh.ageDays("Channel · 1.7K views · 5 days ago") == 5, "days in a second line");
        check(Fresh.ageDays("2 weeks ago") == 14, "weeks");
        check(Fresh.ageDays("1 month ago") == 30, "months");
        check(Fresh.ageDays("Streamed 6 days ago") == 6, "streamed");
        check(Fresh.ageDays("no time here") < 0, "unknown age");
        check(Fresh.viewCount("1.7K views") == 1700, "thousands");
        check(Fresh.viewCount("465K views") == 465000, "hundreds of thousands");
        check(Fresh.viewCount("15 views") == 15, "plain views");
        check(Fresh.viewCount("2.4M views") == 2_400_000, "millions");
        check(Fresh.viewCount("just now") < 0, "unknown views");

        List<Item> row = new ArrayList<>(Arrays.asList(
                video("a", "A", ""), video("b", "B", ""), video("c", "C", ""), video("d", "D", "")));
        Set<String> seen = new HashSet<>(Arrays.asList("a", "c"));
        check(Fresh.demote(row, seen, 0) == 2, "demote count");
        check(row.get(0).id.equals("b") && row.get(1).id.equals("d") && row.get(2).id.equals("a")
                && row.get(3).id.equals("c"), "unseen lead: " + ids(row));

        List<Item> again = new ArrayList<>(Arrays.asList(video("a", "A", ""), video("b", "B", ""), video("c", "C", "")));
        check(Fresh.demote(again, new HashSet<>(Arrays.asList("a", "b", "c")), 1) == 1, "rotate distance");
        check(again.get(0).id.equals("b") && again.get(2).id.equals("a"), "whole row rotates: " + ids(again));

        List<Item> watched = Arrays.asList(video("w1", "W", ""), video("w2", "W2", ""), video("w3", "W3", ""));
        List<Item> s0 = Fresh.pickSeeds(watched, null, 0, 2);
        List<Item> s1 = Fresh.pickSeeds(watched, null, 1, 2);
        check(s0.get(0).id.equals("w1") && s0.get(1).id.equals("w2"), "newest watch stays: " + ids(s0));
        check(s1.get(0).id.equals("w1") && s1.get(1).id.equals("w3"), "second seed rotates: " + ids(s1));
        List<Item> page = Arrays.asList(video("p1", "P", ""), video("p2", "P2", ""), video("p3", "P3", ""));
        List<Item> a0 = Fresh.pickSeeds(null, page, 0, 2);
        List<Item> a1 = Fresh.pickSeeds(null, page, 1, 2);
        check(a0.get(0).id.equals("p1") && a1.get(0).id.equals("p2") && !a0.get(0).id.equals(a1.get(0).id),
                "page seeds move: " + ids(a0) + " / " + ids(a1));

        Similar sim = new Similar(new Similar.Key() {
            @Override
            public String of(Item x) {
                return x.channel.title.toLowerCase();
            }
        });
        List<Item> big = new ArrayList<>();
        big.add(video("big1", "Mega Channel", "Mega Channel · 2.4M views · 2 years ago"));
        List<Item> small = new ArrayList<>();
        small.add(video("sm1", "Tiny Doc", "Tiny Doc · 800 views · 3 days ago"));
        sim.add(big, 2);
        sim.add(small, 0.4);
        Fresh.Tune tune = new Fresh.Tune();
        tune.haveMatch = true;
        List<Similar.Group> groups = ranked(sim, tune, null);
        check(groups.get(0).key.equals("tiny doc"), "recent small channel leads: " + groups.get(0).key + " " + groups.get(0).score);
        Set<String> shown = new HashSet<>();
        shown.add("tiny doc");
        groups = ranked(sim, tune, shown);
        check(groups.get(0).key.equals("mega channel"), "last visit's lead steps back: " + groups.get(0).key);

        if (failed > 0) {
            System.out.println(failed + " FAILED");
            System.exit(1);
        }
        System.out.println("fresh ok");
    }

    /** A fresh ranking each time: {@link Fresh#tune} adds to the score it is given. */
    private static List<Similar.Group> ranked(Similar sim, Fresh.Tune tune, Set<String> shown) {
        List<Similar.Group> groups = sim.ranked();
        for (Similar.Group g : groups) g.match = g.key.equals("tiny doc") ? 0.8 : 0.4;
        Fresh.tune(groups, shown, tune);
        return groups;
    }

    private static String ids(List<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item it : items) {
            if (b.length() > 0) b.append(',');
            b.append(it.id);
        }
        return b.toString();
    }
}
