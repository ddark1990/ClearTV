package com.goomero.cleartv.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Descriptor and descriptor-ranking checks (JVM, no network). */
public final class DescriptorTest {
    private static int failed;

    static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static Item video(String id, String channel, String title) {
        Item x = new Item(Item.VIDEO, id, title, channel + " · 1K views", "", "", "");
        x.channel = new Item(Item.CHANNEL, "UC" + channel.replace(' ', '_'), channel, "Channel", "", "", "");
        return x;
    }

    public static void main(String[] a) {
        // words: case, plurals, possessives
        check(Descriptor.norm("Games").equals("game"), "plural");
        check(Descriptor.norm("Stories").equals("story"), "ies plural");
        check(Descriptor.norm("McGee's").equals("mcgee"), "possessive");
        check(Descriptor.norm("Boss").equals("boss") && Descriptor.norm("bus").equals("bus"), "not plural");
        check(Descriptor.unhash("AmericanMcGeesAlice").equals("American Mc Gees Alice")
                || Descriptor.unhash("AmericanMcGeesAlice").equals("American McGees Alice"), "hashtag words: " + Descriptor.unhash("AmericanMcGeesAlice"));
        check(Descriptor.termsOf("The Lord of the Rings").containsKey("lord ring"), "phrase across stopwords");

        // the description: links, socials, sponsor reads and calls to action go; timestamps become chapters
        List<String> ch = new ArrayList<>(), tags = new ArrayList<>();
        String clean = Descriptor.clean("A deep dive into the lighthouse keepers of Maine.\n"
                + "0:00 Intro\n1:23:45 The Storm\nSubscribe for more!\nInstagram: https://instagram.com/x\n"
                + "This video is sponsored by Brand, use code X\n#lighthouses #MaineCoast", ch, tags);
        check(clean.contains("lighthouse keepers"), "description kept");
        check(!clean.toLowerCase().contains("subscribe") && !clean.contains("instagram") && !clean.contains("sponsored"), "boilerplate gone: " + clean);
        check(ch.equals(Arrays.asList("Intro", "The Storm")), "chapters from timestamps: " + ch);
        check(tags.contains("lighthouses") && tags.contains("MaineCoast"), "hashtags: " + tags);

        // the descriptor: tags and topics weigh most, the channel's own name is not a term
        Descriptor.Source s = new Descriptor.Source();
        s.title = "The making of Alice";
        s.owner = "Source Code";
        s.category = "Gaming";
        s.description = "We explored the making of American McGee's Alice.\nFollow us on twitter";
        s.keywords.addAll(Arrays.asList("American McGee", "alice", "documentary", "Source Code", "video games"));
        s.topics.add("American McGee's Alice");
        Descriptor d = new Descriptor(s, new Descriptor.Weights());
        List<String> top = new ArrayList<>(d.terms.keySet()).subList(0, 3);
        check(top.contains("alice") && top.contains("american mcgee"), "strongest terms: " + d.summary(6));
        check(!d.terms.containsKey("source") && !d.terms.containsKey("code") && !d.terms.containsKey("source code"), "owner name dropped");
        check(!d.terms.containsKey("twitter"), "socials dropped");
        List<String> q = d.queries(2, null);
        check(!q.isEmpty() && q.get(0).equals("American McGee's Alice"), "first search is the linked game: " + q);
        check(q.size() == 2 && !q.get(1).equalsIgnoreCase(q.get(0)), "second search differs: " + q);

        // a search mostly restating the title is not made
        Descriptor.Source t = new Descriptor.Source();
        t.title = "The Cosmic Horror Game That Pretends To Be Cozy";
        t.keywords.add("cosmic horror game pretends cozy");
        t.keywords.add("dredge");
        for (String x : new Descriptor(t, new Descriptor.Weights()).queries(3, null))
            check(!x.equals("cosmic horror game pretends cozy"), "title copy searched");

        // ranking: an on-point small channel beats a big general one the lists put first
        Similar sim = new Similar(new Similar.Key() {
            @Override
            public String of(Item x) {
                return x.channel.title.toLowerCase();
            }
        });
        List<Item> list = new ArrayList<>();
        list.add(video("v1", "Big Variety", "Wine snobs are the worst"));
        list.add(video("v2", "Retro Docs", "The making of American McGee's Alice"));
        list.add(video("v3", "Big Variety", "I tried every fast food burger"));
        list.add(video("v4", "Cooking", "Perfect pancakes"));
        sim.add(list, 1.0);
        sim.add(Arrays.asList(video("v5", "Big Variety", "Ranking sodas"), video("v6", "Cooking", "Pasta")), 0.6);
        List<Similar.Group> before = sim.ranked();
        check(before.get(0).key.equals("big variety"), "lists alone: big first");
        HashSet<String> related = new HashSet<>();
        for (Similar.Group g : before) related.add(g.key);
        List<Similar.Group> after = sim.ranked(d.fit(before, Collections.<String, String>emptyMap(), related, new Descriptor.Weights()), 2, 0.6);
        check(after.get(0).key.equals("retro docs"), "with the descriptor: on-point first, got " + after.get(0).key);
        // no descriptor match anywhere: the lists' order stands
        Descriptor.Source none = new Descriptor.Source();
        none.title = "Quantum chromodynamics lecture";
        List<Similar.Group> same = sim.ranked(new Descriptor(none, new Descriptor.Weights()).fit(before, null, related, new Descriptor.Weights()), 2, 0.6);
        for (int i = 0; i < before.size(); i++) check(same.get(i).key.equals(before.get(i).key), "order kept without matches");
        // a channel found only by searching keeps half its fit
        sim.add(Arrays.asList(video("v7", "Alice Fan", "American McGee's Alice full game")), 0.3);
        List<Similar.Group> all = sim.ranked();
        List<Similar.Group> mixed = sim.ranked(d.fit(all, null, related, new Descriptor.Weights()), 2, 0.6);
        double fan = 0, docs = 0;
        for (Similar.Group g : mixed) {
            if (g.key.equals("alice fan")) fan = g.match;
            if (g.key.equals("retro docs")) docs = g.match;
        }
        check(fan > 0 && fan <= 0.5 + 1e-9 && docs > fan, "search-only fit halved: fan " + fan + ", docs " + docs);

        if (failed > 0) {
            System.out.println(failed + " descriptor checks failed");
            System.exit(1);
        }
        System.out.println("descriptor tests ok");
    }
}
