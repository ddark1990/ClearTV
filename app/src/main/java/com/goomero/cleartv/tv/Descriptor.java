package com.goomero.cleartv.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a video says it is about, read from the metadata its creator writes for search: tags, hashtags, the game or
 * topic it is linked to, the title, chapter titles, the description (without links, sponsor reads, socials and
 * calls to action) and the category. Each word and two-word phrase gets a weight from where it appears (tags,
 * hashtags and the linked game or topic count most) and how often. "More like this" uses it to search for channels
 * the related lists miss and to rank candidate channels by how well their videos match it; how generic a term is
 * comes from the candidates themselves (a term on most candidate channels says little), so no topic word list is kept.
 */
final class Descriptor {
    /** Field weights and limits (bundled config "youtube.similar.descriptor"). */
    static final class Weights {
        double tag = 3, hashtag = 3, topic = 3.5, title = 2, chapter = 1, description = 0.6, category = 0.4;
        /** Extra weight of a two-word phrase over its words. */
        double phrase = 1.25;
        /** The lowest weight a term keeps however many candidate channels use it. */
        double idfFloor = 0.25;
        /** The share of the fit a channel found only by a search keeps (the related lists' word counts more). */
        double searchFit = 0.5;
        /** Terms kept (the strongest). */
        int terms = 24;
    }

    /** The metadata of one video, as YouTube sends it. */
    static final class Source {
        String title = "", description = "", category = "", owner = "";
        final List<String> keywords = new ArrayList<>(), chapters = new ArrayList<>(), topics = new ArrayList<>();
    }

    /** Terms (normalised words and "word word" phrases) by weight, strongest first. */
    final LinkedHashMap<String, Double> terms = new LinkedHashMap<>();
    /** Linked games, music and topics, as written. */
    final List<String> topics = new ArrayList<>();
    private final List<String> tags = new ArrayList<>();
    private final Map<String, String> surface = new HashMap<>();
    private final Set<String> ownerTokens = new HashSet<>();
    private final Set<String> titleTokens = new HashSet<>();

    // ------------------------------------------------------------------------------------------- words

    /** Common words of the languages YouTube is most used in: never terms. */
    private static final Set<String> STOP = new HashSet<>(Arrays.asList((
            // English
            "a an and are as at be been being but by can could did do does doing done for from get got had has have "
            + "having he her here hers him his how i if in into is it its itself just let me more most my no nor not "
            + "now of off on once only or other our out over own same she should so some such than that the their "
            + "them then there these they this those through to too under until up upon very was we were what when "
            + "where which while who whom why will with would you your yours yourself also about after again against "
            + "all am any because before below between both down during each few further ever every got many much "
            + "must never one ones us vs via way well what whats whatever yet im ive youre dont doesnt didnt cant wont "
            + "isnt thats theres lets ll re ve "
            // the words every video and description uses (not topics)
            + "video videos channel official full new watch watching episode ep part pt hd 4k 1080p 60fps today "
            + "subscribe like likes comment comments share follow please thanks thank thx check click link links "
            + "below description playlist shorts short "
            // Spanish, Portuguese, French, German, Italian
            + "el la los las un una unos unas de del al y o en con por para que se su sus es lo como mas pero muy "
            + "e em com por para os as um uma da do das dos no na nos nas ao seu sua sao "
            + "le les des du et ou au aux ce cette ces est sont pour par sur dans avec pas plus qui une "
            + "der die das den dem des ein eine einen einem und oder ist sind mit von zu im auf fur nicht auch "
            + "il lo gli di da per che non con su piu ").split(" ")));

    /** Description lines that are boilerplate: links, handles, sponsor reads, socials, gear, calls to action. */
    private static final Pattern BOILER = Pattern.compile("(?i)(https?://|www\\.|\\.(com|net|org|gg|ly|io|tv)\\b|@\\w"
            + "|\\b(subscribe|patreon|merch|sponsor(ed)?|discount|promo|coupon|affiliate|instagram|twitter|tiktok|facebook"
            + "|twitch|discord|newsletter|membership|business|inquir(y|ies)|e-?mail|gear|camera|microphone|lens|donate"
            + "|support|paypal|ko-fi|shop|store|music by|licensed|epidemic|artlist|copyright|disclaimer|follow|join)\\b)");
    private static final Pattern STAMP = Pattern.compile("^\\s*[\\[(]?(?:\\d{1,2}:)?\\d{1,2}:\\d{2}[\\])]?\\s*[-–—:|.]?\\s*(.*)$");
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,})");
    private static final Pattern CAMEL = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})");
    /** Where a title or tag breaks into separate phrases. */
    private static final Pattern BREAK = Pattern.compile("[|:;,.!?()\\[\\]{}<>/\\\\\"“”«»–—•·~+=*_#]+|\\s-\\s");
    /** What separates words. */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}'’]+");

    /** A word as a term: lower case, no possessive or apostrophes, plural to singular. */
    static String norm(String w) {
        String s = w.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (s.endsWith("'s")) s = s.substring(0, s.length() - 2);
        s = s.replace("'", "");
        int n = s.length();
        if (n > 4 && s.endsWith("ies")) return s.substring(0, n - 3) + "y";
        if (n > 4 && (s.endsWith("ches") || s.endsWith("shes") || s.endsWith("sses") || s.endsWith("xes")))
            return s.substring(0, n - 2);
        if (n > 3 && s.endsWith("s") && !s.endsWith("ss") && !s.endsWith("us") && !s.endsWith("is")) return s.substring(0, n - 1);
        return s;
    }

    /** Whether a normalised word can be a term (no common word, number or single letter). */
    private static boolean keep(String t) {
        if (t.isEmpty() || STOP.contains(t)) return false;
        boolean digits = true;
        for (int i = 0; i < t.length() && digits; i++) digits = t.charAt(i) >= '0' && t.charAt(i) <= '9';
        if (digits) return false;
        return t.length() >= 2 || Character.isIdeographic(t.codePointAt(0));
    }

    /** The phrases of a text, each as its kept words in order. */
    static List<List<String>> phrases(String text) {
        List<List<String>> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String part : BREAK.split(text)) {
            List<String> words = new ArrayList<>();
            for (String w : NON_WORD.split(part)) {
                String t = norm(w);
                if (keep(t)) words.add(t);
            }
            if (!words.isEmpty()) out.add(words);
        }
        return out;
    }

    /** A text's terms with counts: its words and each two adjacent words. */
    static Map<String, Integer> termsOf(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (List<String> p : phrases(text)) {
            for (int i = 0; i < p.size(); i++) {
                inc(out, p.get(i));
                if (i + 1 < p.size() && !p.get(i).equals(p.get(i + 1))) inc(out, p.get(i) + " " + p.get(i + 1));
            }
        }
        return out;
    }

    private static void inc(Map<String, Integer> m, String k) {
        Integer c = m.get(k);
        m.put(k, c == null ? 1 : c + 1);
    }

    /** The description without boilerplate lines; its timestamp lines go to {@code chapters}, hashtags to {@code tags}. */
    static String clean(String description, List<String> chapters, List<String> hashtags) {
        StringBuilder out = new StringBuilder();
        if (description == null) return "";
        for (String line : description.split("\\r?\\n")) {
            Matcher h = HASHTAG.matcher(line);
            while (h.find()) hashtags.add(h.group(1));
            Matcher st = STAMP.matcher(line);
            if (st.matches()) {
                String c = st.group(1).trim();
                if (!c.isEmpty() && chapters != null) chapters.add(c);
                continue;
            }
            if (BOILER.matcher(line).find()) continue;
            String l = HASHTAG.matcher(line).replaceAll(" ").trim();
            if (l.isEmpty() || l.matches("[\\p{Punct}\\s]+")) continue;
            out.append(l).append('\n');
            if (out.length() > 1500) break;
        }
        return out.toString();
    }

    /** A hashtag's words ("#AmericanMcGeesAlice" -> "American Mcgees Alice"). */
    static String unhash(String tag) {
        return CAMEL.matcher(tag.replace('_', ' ')).replaceAll(" ");
    }

    // ------------------------------------------------------------------------------------------ build

    Descriptor(Source s, Weights w) {
        for (List<String> p : phrases(s.owner)) {
            ownerTokens.addAll(p);
            StringBuilder joined = new StringBuilder();
            for (String t : p) joined.append(t);
            ownerTokens.add(joined.toString());
        }
        String compact = s.owner.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        if (!compact.isEmpty()) ownerTokens.add(compact);
        for (List<String> p : phrases(s.title)) titleTokens.addAll(p);
        Map<String, Double> score = new HashMap<>();
        List<String> hashtags = new ArrayList<>();
        List<String> chapters = new ArrayList<>(s.chapters);
        String desc = clean(s.description, s.chapters.isEmpty() ? chapters : null, hashtags);
        Matcher h = HASHTAG.matcher(s.title);
        while (h.find()) hashtags.add(h.group(1));
        List<String> kw = new ArrayList<>();
        for (String k : s.keywords) {
            if (k.startsWith("#")) hashtags.add(k.substring(1));
            else kw.add(k);
        }
        List<String> hs = new ArrayList<>();
        for (String t : new LinkedHashSet<>(hashtags)) hs.add(unhash(t));
        field(score, kw, w.tag, w, true);
        field(score, hs, w.hashtag, w, true);
        field(score, s.topics, w.topic, w, true);
        field(score, Collections.singletonList(HASHTAG.matcher(s.title).replaceAll(" ")), w.title, w, false);
        field(score, chapters, w.chapter, w, true);
        field(score, Collections.singletonList(desc), w.description, w, false);
        field(score, Collections.singletonList(s.category), w.category, w, true);
        for (String t : s.topics) if (!t.trim().isEmpty()) topics.add(t.trim());
        for (String k : kw) if (!k.trim().isEmpty()) tags.add(k.trim());
        for (String k : hs) tags.add(k);
        rememberSurface(s.title);
        for (String k : kw) rememberSurface(k);
        for (String k : s.topics) rememberSurface(k);
        List<Map.Entry<String, Double>> l = new ArrayList<>(score.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                int c = Double.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        for (Map.Entry<String, Double> e : l) {
            if (terms.size() >= w.terms) break;
            terms.put(e.getKey(), e.getValue());
        }
    }

    /**
     * Adds one field: each term once per entry (a tag, a chapter) or by count (a title, a description), so a term in
     * several tags or said several times counts more, with diminishing returns.
     */
    private void field(Map<String, Double> score, List<String> texts, double fw, Weights w, boolean perEntry) {
        if (fw <= 0) return;
        Map<String, Integer> count = new HashMap<>();
        for (String text : texts) {
            Map<String, Integer> t = termsOf(text);
            for (Map.Entry<String, Integer> e : t.entrySet()) {
                Integer c = count.get(e.getKey());
                count.put(e.getKey(), (c == null ? 0 : c) + (perEntry ? 1 : e.getValue()));
            }
        }
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            String t = e.getKey();
            if (isOwner(t)) continue;
            double v = fw * (1 + 0.25 * Math.min(4, e.getValue() - 1)) * (t.indexOf(' ') > 0 ? w.phrase : 1);
            Double old = score.get(t);
            score.put(t, old == null ? v : old + v);
        }
    }

    /** Whether a term is the source channel's own name or part of it. */
    private boolean isOwner(String t) {
        if (ownerTokens.isEmpty()) return false;
        for (String p : t.split(" ")) if (!ownerTokens.contains(p)) return false;
        return true;
    }

    private void rememberSurface(String text) {
        for (String w : NON_WORD.split(text)) {
            String t = norm(w);
            if (keep(t) && !surface.containsKey(t)) surface.put(t, w.replace("’", "'"));
        }
    }

    boolean isEmpty() {
        return terms.isEmpty();
    }

    // ---------------------------------------------------------------------------------------- recall

    /**
     * Up to {@code n} searches for channels the related lists miss: the linked games and topics, the strongest
     * multi-word tag, then the strongest words, each search different from the ones before it. With {@code sc} (the
     * candidates found so far) a term counts less the more candidate channels already use it.
     */
    List<String> queries(int n, Scorer sc) {
        List<String> out = new ArrayList<>();
        List<Set<String>> used = new ArrayList<>();
        for (String t : topics) {
            if (!isOwnerText(t)) offer(out, used, t, n);
        }
        // tags of two to five words, by the weight of their words (as the creator weighs them: a central term that
        // every related video shares is still the best search)
        List<String> phr = new ArrayList<>();
        final Map<String, Double> phrScore = new HashMap<>();
        for (String tag : tags) {
            List<List<String>> ps = phrases(tag);
            if (ps.size() != 1 || ps.get(0).size() < 2 || ps.get(0).size() > 5 || isOwnerText(tag)) continue;
            double v = 0;
            for (String t : ps.get(0)) v += strength(t, null);
            if (!phrScore.containsKey(tag)) phr.add(tag);
            phrScore.put(tag, v / Math.sqrt(ps.get(0).size()));
        }
        Collections.sort(phr, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Double.compare(phrScore.get(b), phrScore.get(a));
            }
        });
        for (String p : phr) offer(out, used, p, n);
        // the strongest words: added to the first search when it already says the rest, else on their own
        List<String> words = new ArrayList<>(terms.keySet());
        final Scorer s2 = sc;
        Collections.sort(words, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Double.compare(strength(b, s2), strength(a, s2));
            }
        });
        StringBuilder q = new StringBuilder();
        int k = 0;
        Set<String> inFirst = used.isEmpty() ? new HashSet<String>() : used.get(0);
        for (String t : words) {
            if (t.indexOf(' ') > 0 || ownerTokens.contains(t)) continue;
            if (q.length() > 0) q.append(' ');
            q.append(surface.containsKey(t) ? surface.get(t) : t);
            if (++k >= 3) break;
        }
        if (k > 0) offer(out, used, q.toString(), n);
        if (out.size() < n && !out.isEmpty()) {
            for (String t : words) {
                if (t.indexOf(' ') > 0 || inFirst.contains(t) || ownerTokens.contains(t)) continue;
                offer(out, used, out.get(0) + " " + (surface.containsKey(t) ? surface.get(t) : t), n, true);
                break;
            }
        }
        return out;
    }

    /** A term's weight, less the more candidate channels use it (when there are candidates). */
    private double strength(String t, Scorer sc) {
        Double v = terms.get(t);
        if (v == null) return 0;
        return sc == null ? v : v * sc.idf(t);
    }

    private boolean isOwnerText(String text) {
        for (List<String> p : phrases(text)) for (String t : p) if (!ownerTokens.contains(t)) return false;
        return true;
    }

    /**
     * Adds a search unless it repeats an earlier one or the title: a search of three or more words mostly from the
     * title finds copies of this video, not other channels.
     */
    private void offer(List<String> out, List<Set<String>> used, String q, int n) {
        offer(out, used, q, n, false);
    }

    /** As above; {@code widen}: a search may add words to an earlier one. */
    private void offer(List<String> out, List<Set<String>> used, String q, int n, boolean widen) {
        if (out.size() >= n) return;
        Set<String> toks = new HashSet<>();
        for (List<String> p : phrases(q)) toks.addAll(p);
        if (toks.isEmpty()) return;
        if (toks.size() >= 3) {
            int inTitle = 0;
            for (String t : toks) if (titleTokens.contains(t)) inTitle++;
            if (inTitle * 4 >= toks.size() * 3) return;
        }
        for (Set<String> u : used) if (u.containsAll(toks) || (!widen && toks.containsAll(u))) return;
        used.add(toks);
        out.add(q.trim().replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s&&[^)\"']]+$", ""));
    }

    // --------------------------------------------------------------------------------------- ranking

    /**
     * Matches against one set of candidates: a term's weight shrinks with the share of candidate channels using it
     * (IDF over the candidates, never below {@link Weights#idfFloor}).
     */
    final class Scorer {
        private final Map<String, Double> idf = new HashMap<>();
        private double norm;
        /** Each candidate text's terms, worked out once (the ranking matches the same texts again). */
        private final Map<String, Set<String>> seenTerms = new HashMap<>();

        /** @param channels each candidate channel's texts (titles, snippets, its name) */
        Scorer(List<List<String>> channels, Weights w) {
            Map<String, Integer> df = new HashMap<>();
            for (List<String> texts : channels) {
                Set<String> seen = new HashSet<>();
                for (String x : texts) seen.addAll(termSet(x));
                for (String t : seen) if (terms.containsKey(t)) inc(df, t);
            }
            int n = Math.max(1, channels.size());
            List<Double> top = new ArrayList<>();
            for (Map.Entry<String, Double> e : terms.entrySet()) {
                Integer d = df.get(e.getKey());
                double v = Math.max(w.idfFloor, Math.log((n + 1.0) / ((d == null ? 0 : d) + 1.0)) / Math.log(n + 1.0));
                idf.put(e.getKey(), v);
                if (d != null) top.add(e.getValue() * v);
            }
            Collections.sort(top, Collections.reverseOrder());
            for (int i = 0; i < top.size() && i < 3; i++) norm += top.get(i);
        }

        double idf(String t) {
            Double v = idf.get(t);
            return v == null ? 1 : v;
        }

        private Set<String> termSet(String text) {
            Set<String> t = seenTerms.get(text);
            if (t == null) {
                t = termsOf(text).keySet();
                seenTerms.put(text, t);
            }
            return t;
        }

        /** How well one text matches, 0 (nothing) to 1 (the strongest matching terms). */
        double match(String text) {
            if (norm <= 0) return 0;
            double s = 0;
            for (String t : termSet(text)) {
                Double v = terms.get(t);
                if (v != null) s += v * idf.get(t);
            }
            return Math.min(1, s / norm);
        }
    }

    /**
     * The fit of candidate channels: each video's title (with its search snippet, when it came from a search) and the
     * channel's name matched against this descriptor, IDF over {@code groups}. A channel's fit is mostly its best
     * video's, partly the average of its first few; its tiles are reordered best match first. A channel that is not in
     * {@code related} (found only by a search) keeps {@link Weights#searchFit} of its fit.
     */
    Scorer scorer(List<Similar.Group> groups, Map<String, String> snippets, Weights w) {
        List<List<String>> texts = new ArrayList<>();
        for (Similar.Group g : groups) texts.add(textsOf(g, snippets));
        return new Scorer(texts, w);
    }

    Similar.Fit fit(List<Similar.Group> groups, final Map<String, String> snippets, final Set<String> related, final Weights w) {
        final Scorer sc = scorer(groups, snippets, w);
        return new Similar.Fit() {
            @Override
            public double of(Similar.Group g) {
                if (g.items.isEmpty()) return 0;
                final Map<String, Double> m = new HashMap<>();
                double best = 0, sum = 0;
                int n = 0;
                double name = sc.match(g.key);
                for (Item x : g.items) {
                    String sn = snippets == null ? null : snippets.get(x.id);
                    double v = Math.max(name, sc.match(sn == null ? x.title : x.title + "\n" + sn));
                    m.put(x.id, v);
                    best = Math.max(best, v);
                    if (n < 5) {
                        sum += v;
                        n++;
                    }
                }
                List<Item> sorted = new ArrayList<>(g.items);
                Collections.sort(sorted, new Comparator<Item>() {
                    @Override
                    public int compare(Item a, Item b) {
                        return Double.compare(m.get(b.id), m.get(a.id));
                    }
                });
                g.items.clear();
                g.items.addAll(sorted);
                double f = Math.min(1, 0.7 * best + 0.3 * sum / n);
                return related == null || related.contains(g.key) ? f : f * w.searchFit;
            }
        };
    }

    private static List<String> textsOf(Similar.Group g, Map<String, String> snippets) {
        List<String> t = new ArrayList<>();
        t.add(g.key);
        for (Item x : g.items) {
            String sn = snippets == null ? null : snippets.get(x.id);
            t.add(sn == null ? x.title : x.title + "\n" + sn);
        }
        return t;
    }

    /** The strongest terms, for the log. */
    String summary(int n) {
        StringBuilder b = new StringBuilder();
        int k = 0;
        for (Map.Entry<String, Double> e : terms.entrySet()) {
            if (k++ >= n) break;
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey()).append(' ').append(String.format(Locale.ROOT, "%.1f", e.getValue()));
        }
        return b.toString();
    }
}
