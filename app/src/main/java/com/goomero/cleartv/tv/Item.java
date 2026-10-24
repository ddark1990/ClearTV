package com.goomero.cleartv.tv;

import java.util.ArrayList;
import java.util.List;

/** One tile on a native service screen: a video, a live channel, or something that opens another page. */
public final class Item {
    public static final int VIDEO = 0, LIVE = 1, PAGE = 2, CHANNEL = 3, SERIES = 4;

    public final int kind;
    /** Service id of the thing (video id, browse id, content id, detail path). */
    public final String id;
    public final String title;
    /** Second line under the tile: channel, year, genre. */
    public final String subtitle;
    public final String image;
    /** Small label on the artwork (duration, LIVE, "Free"), or empty. */
    public final String badge;
    /** Extra service data (YouTube browse params, Prime detail path). */
    public final String extra;
    public String description = "";
    /** The service a tile belongs to when it is shown outside that service (all-services search), else null. */
    public String service;
    /**
     * The page "Go to channel" opens, when the service told us while building the tile: a YouTube channel, a Tubi
     * series, a Prime Video show. Null when unknown (the service may still look it up, see Source.channelOf).
     */
    public Item channel;
    /** Share already watched (0..1) on a Continue watching tile, drawn as a bar along the artwork; 0 for none. */
    public float progress;
    /** Title and subtitle cut to the tile width (drawW), cached by ShelfView. */
    CharSequence drawTitle, drawSub;
    float drawW;

    public Item(int kind, String id, String title, String subtitle, String image, String badge, String extra) {
        this.kind = kind;
        this.id = id;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.image = image == null ? "" : image;
        this.badge = badge == null ? "" : badge;
        this.extra = extra == null ? "" : extra;
    }

    /** A titled row of tiles. */
    public static final class Shelf {
        public final String title;
        public final List<Item> items;
        /** Token for the row's next tiles when the service pages the row (Source.moreRow), else null. */
        public String more;

        public Shelf(String title, List<Item> items) {
            this.title = title == null ? "" : title;
            this.items = items;
        }
    }

    /**
     * The top of a title page (a show, film or playlist), drawn by the shared detail screen the same way for every
     * service: artwork, synopsis, Play/Resume, trailer, watchlist.
     */
    public static final class Detail {
        public String title = "", meta = "", synopsis = "", image = "";
        /** What Play starts (a VIDEO item for the service's play()), its button label, and the trailer. */
        public Item play, trailer;
        public String playLabel = "Play";
        /** Shown instead of Play when the title cannot be played here (for example "Rent or buy"). */
        public String note = "";
        /** Watchlist button: NONE, ADD or REMOVE; SIGN_IN when the service wants a signed-in account first. */
        public static final int WL_NONE = 0, WL_ADD = 1, WL_REMOVE = 2, WL_SIGN_IN = 3;
        public int watchlist = WL_NONE;
        public String watchlistUrl = "";
    }

    /** One entry of the season selector: either a page to load (link) or episodes already loaded. */
    public static final class Season {
        public final String label;
        public final Item link;
        public final Shelf episodes;

        public Season(String label, Item link, Shelf episodes) {
            this.label = label == null ? "" : label;
            this.link = link;
            this.episodes = episodes;
        }
    }

    /** A screen's worth of rows plus, when the service pages them, the token for the next rows. */
    public static final class Page {
        public final List<Shelf> shelves = new ArrayList<>();
        public String more;
        /** The rows that {@link #more} brings continue this page's grid (a long feed) instead of adding rows. */
        public boolean moreExtendsGrid;
        /** Shown when the page has no rows (for example "Sign in to see your subscriptions"). */
        public String empty = "";
        /** Title page header, or null for a browse page. */
        public Detail detail;
        /** Season selector entries and the selected one. */
        public final List<Season> seasons = new ArrayList<>();
        public int season;
        /** Index of the shelf drawn as a wrapping grid (episodes), or -1. */
        public int grid = -1;

        public boolean isEmpty() {
            for (Shelf s : shelves) if (!s.items.isEmpty()) return false;
            return true;
        }
    }
}
