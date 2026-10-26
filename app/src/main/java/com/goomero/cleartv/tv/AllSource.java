package com.goomero.cleartv.tv;

import android.content.Context;
import android.util.Log;

import com.goomero.cleartv.Ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Search across every service in the shared screen: the query goes to Prime Video, YouTube, Tubi (their search)
 * and Pluto TV (channel names) at once, and the results come back as one row per service. A tile opens in its own
 * service's screen.
 */
final class AllSource extends Source {
    private static final String TAG = "CTV.All";
    private static final String[][] SERVICES = {{"prime", "Prime Video"}, {"youtube", "YouTube"}, {"tubi", "Tubi"}, {"paramount", "Paramount+"}, {"pluto", "Pluto TV"}};

    AllSource(Context c) {
        super(c, "all");
    }

    @Override public String id() { return "all"; }

    @Override public String name() { return "All services"; }

    @Override public int accent() { return Ui.ACCENT; }

    @Override public String iconKey() { return ""; }

    @Override
    public List<Nav> navs() {
        List<Nav> l = new ArrayList<>();
        l.add(new Nav("search", "Search all"));
        return l;
    }

    @Override
    public Item.Page load(Nav nav) {
        Item.Page p = new Item.Page();
        p.empty = "Search Prime Video, YouTube, Tubi and Pluto TV at once.";
        return p;
    }

    @Override
    public String searchHint() {
        return "Search all services";
    }

    @Override
    public Item.Page search(final String query) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(SERVICES.length);
        List<Future<List<Item>>> jobs = new ArrayList<>();
        try {
            for (final String[] s : SERVICES) {
                jobs.add(pool.submit(new java.util.concurrent.Callable<List<Item>>() {
                    @Override
                    public List<Item> call() throws Exception {
                        if (s[0].equals("pluto")) return com.goomero.cleartv.pluto.PlutoApi.searchChannels(app, query);
                        Item.Page r = Source.create(app, s[0]).search(query);
                        List<Item> out = new ArrayList<>();
                        for (Item.Shelf sh : r.shelves) {
                            for (Item it : sh.items) {
                                it.service = s[0];
                                out.add(it);
                                if (out.size() >= 20) return out;
                            }
                        }
                        return out;
                    }
                }));
            }
            Item.Page page = new Item.Page();
            int failed = 0;
            for (int i = 0; i < SERVICES.length; i++) {
                try {
                    List<Item> items = jobs.get(i).get(20, TimeUnit.SECONDS);
                    if (!items.isEmpty()) page.shelves.add(new Item.Shelf(SERVICES[i][1], items));
                } catch (Exception e) {
                    failed++;
                    Log.w(TAG, SERVICES[i][0] + " search: " + e.getMessage());
                }
            }
            if (page.isEmpty()) page.empty = failed == SERVICES.length ? "No service answered. Check the connection."
                    : "Nothing found for \u201c" + query + "\u201d.";
            return page;
        } finally {
            pool.shutdownNow();
        }
    }

    @Override
    public Item.Page open(Item item) throws IOException {
        return Source.create(app, item.service).open(item);
    }

    @Override
    public Playback play(Item item) throws IOException {
        return Source.create(app, item.service).play(item);
    }

    @Override
    public ChapterList chapters(Item item) throws IOException {
        if (item.service == null || item.service.isEmpty() || item.service.equals(id())) return null;
        try {
            return Source.create(app, item.service).chapters(item);
        } catch (IllegalArgumentException e) {
            return null; // Pluto TV has no native source (and no chapters)
        }
    }

    @Override public int signInKind() { return SIGNIN_NONE; }

    @Override public boolean signedIn() { return false; }

    @Override public void signOut() {}
}
