package com.goomero.cleartv.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A video service behind a native ClearTV screen: its sections, rows, search, playback and account. Every call
 * except the getters runs on a background thread.
 */
public abstract class Source {
    /** Sign-in styles: a code shown on the TV and entered on a phone, or none (the all-services search). */
    public static final int SIGNIN_CODE = 0, SIGNIN_NONE = 1;

    /** A section in the left rail. */
    public static final class Nav {
        public final String id, label;

        public Nav(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** What to show while a code sign-in waits for the phone. */
    /** A sign-in failure after the code was approved: polling stops and the card shows the message. */
    public static class SignInError extends IOException {
        public SignInError(String m) {
            super(m);
        }
    }

    public static final class Code {
        public final String userCode, url, deviceCode;
        public final int intervalSec;
        public final long expiresAt;

        public Code(String userCode, String url, String deviceCode, int intervalSec, long expiresAt) {
            this.userCode = userCode;
            this.url = url;
            this.deviceCode = deviceCode;
            this.intervalSec = Math.max(2, intervalSec);
            this.expiresAt = expiresAt;
        }
    }

    /** How to play an item: an HLS stream in ClearTV's player, or a web page when the stream is DRM-protected. */
    public static final class Playback {
        public String hls;
        public Map<String, String> headers;
        public String userAgent;
        public boolean live;
        /** Web player page, used when there is no clear stream (or as the fallback when the stream fails). */
        public String webUrl;
        public boolean webTvUa;
        public String title = "", subtitle = "";
        public long resumeMs;
        /** Skip-intro and next-episode markers when the service publishes them (ms into the video), else 0. */
        public long introStartMs, introEndMs, creditsMs;
        /** A caption file next to the stream (SRT or WebVTT) and its language name, when the service has one. */
        public String captionsUrl, captionsLabel;
    }

    protected final Context app;
    protected final SharedPreferences prefs;

    protected Source(Context c, String id) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences("svc_" + id, Context.MODE_PRIVATE);
    }

    public static Source create(Context c, String id) {
        switch (id) {
            case "youtube":
                return new YouTubeSource(c);
            case "tubi":
                return new TubiSource(c);
            case "prime":
                return new PrimeSource(c);
            case "all":
                return new AllSource(c);
            default:
                throw new IllegalArgumentException("no native source for " + id);
        }
    }

    public abstract String id();

    public abstract String name();

    /** Brand accent (focus ring, progress, highlights) and a second brand colour for the backdrop wash. */
    public abstract int accent();

    public int accent2() {
        return accent();
    }

    /** Asset key of the logo in assets/icons. */
    public abstract String iconKey();

    public abstract List<Nav> navs();

    public abstract Item.Page load(Nav nav) throws IOException;

    /** Next rows of a page (Item.Page.more), or null. */
    public Item.Page more(String token) throws IOException {
        return null;
    }

    /** The next tiles of a row (Item.Shelf.more): a shelf holding only the new tiles and the next token, or null. */
    public Item.Shelf moreRow(String token) throws IOException {
        return null;
    }

    public abstract Item.Page search(String query) throws IOException;

    /**
     * Whether "Go to channel" makes sense for a tile (no network: the menu opens at once). True when the tile knows
     * its channel, series or show, or the service can look it up in {@link #channelOf}.
     */
    public boolean hasChannel(Item it) {
        return it.channel != null;
    }

    /**
     * The title's chapters for the player's chapter bar and Skip sponsor, or null when it has none or the service
     * does not publish them (background thread; may ask the service). Only YouTube has chapters today.
     */
    public ChapterList chapters(Item it) throws IOException {
        return null;
    }

    /** The channel, creator, series or show page of a tile, or null (background thread; may ask the service). */
    public Item channelOf(Item it) throws IOException {
        return it.channel;
    }

    /**
     * Label of the tile menu action that stops the tile's channel (or show) being recommended to the account, or null
     * when the service, the account or the tile has none (no network: the menu opens at once).
     */
    public String hideLabel(Item it) {
        return null;
    }

    /**
     * True when the service has its own "similar titles" data for this tile ("More like this" in the tile and
     * player menus). Answers from the tile alone, never the network.
     */
    public boolean hasSimilar(Item it) {
        return false;
    }

    /** The service's own similar titles for the tile as one wrapping grid (background thread). */
    public Item.Page similar(Item it) throws IOException {
        throw new IOException(name() + " has no similar titles.");
    }

    /** One grid of similar titles, the source tile and repeats left out; an empty page says so. */
    protected static Item.Page similarPage(Item of, List<Item> found, String name) {
        Item.Page p = new Item.Page();
        List<Item> keep = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(of.id);
        for (Item x : found) if (x != null && seen.add(x.id)) keep.add(x);
        if (keep.isEmpty()) {
            p.empty = name + " lists nothing similar for this title.";
            return p;
        }
        p.shelves.add(new Item.Shelf("More like " + of.title, keep));
        p.grid = 0;
        return p;
    }

    /**
     * Fetches a tile's menu when the service sends it only on request (background thread, when the menu opens);
     * true when {@link #hideLabel} now offers the action.
     */
    public boolean resolveHide(Item it) throws IOException {
        return false;
    }

    /** Sends that action for the account (background thread); returns the service's undo token, or null when it gives none. */
    public String hide(Item it) throws IOException {
        throw new IOException(name() + " can't do that.");
    }

    /** Takes a {@link #hide} back with its undo token (background thread). */
    public void undoHide(String undoToken) throws IOException {
        throw new IOException(name() + " can't undo that.");
    }

    /**
     * The channel a tile belongs to as {id, name} (either may be empty, not both), for ClearTV's own list of hidden
     * channels ({@link HiddenChannels}); null where the service has no channels or the tile shows none.
     */
    public String[] channelKey(Item x) {
        return null;
    }

    /** Label of the "Go to channel" action for this service ("Go to show" where the page is a show). */
    public String channelLabel() {
        return "Go to channel";
    }

    /** Contents of a PAGE, CHANNEL or SERIES tile. */
    public abstract Item.Page open(Item item) throws IOException;

    public abstract Playback play(Item item) throws IOException;

    /** Whether OK on this tile opens the shared title page (detail) rather than playing or browsing. */
    public boolean opensDetail(Item item) {
        return item.kind == Item.SERIES;
    }

    /**
     * The shared title page for a tile: header from the tile (artwork, synopsis, Play), seasons as a selector and
     * episodes as a grid. Services with richer title data (Prime Video) override it.
     */
    public Item.Page openDetail(Item item) throws IOException {
        Item.Page p;
        if (item.kind == Item.VIDEO) {
            p = new Item.Page();
        } else {
            p = open(item);
        }
        Item.Detail d = new Item.Detail();
        d.title = item.title;
        d.meta = item.subtitle;
        d.synopsis = item.description;
        d.image = item.image;
        p.detail = d;
        if (item.kind == Item.VIDEO) {
            d.play = item;
            return p;
        }
        List<Item.Shelf> eps = new java.util.ArrayList<>();
        for (Item.Shelf s : p.shelves) if (!s.items.isEmpty()) eps.add(s);
        if (eps.size() > 1) {
            // one shelf per season (Tubi): the selector switches between them without reloading
            for (Item.Shelf s : eps) p.seasons.add(new Item.Season(s.title, null, s));
            p.season = 0;
            p.shelves.clear();
            p.shelves.add(eps.get(0));
            p.grid = 0;
        } else if (eps.size() == 1) {
            p.grid = 0;
        }
        if (!eps.isEmpty()) {
            Item first = eps.get(0).items.get(0);
            d.play = first;
            d.playLabel = "Play " + shortEpisode(first.title);
        } else if (p.empty.isEmpty()) {
            p.empty = "Nothing to watch on this page.";
        }
        return p;
    }

    /** Adds or removes a title page's title from the account's watchlist; returns the new Detail.WL_ state. */
    public int toggleWatchlist(Item.Detail d) throws IOException {
        throw new IOException(name() + " has no watchlist here.");
    }

    /** "E1" from "E1 · Pilot", else "first episode". */
    static String shortEpisode(String title) {
        int dot = title.indexOf(" · ");
        return dot > 0 && dot <= 5 && title.startsWith("E") ? title.substring(0, dot) : "first episode";
    }

    /** Search suggestions shown before anything is typed, or null. */
    public String searchHint() {
        return "Search " + name();
    }

    // ------------------------------------------------------------------------------------- account

    public abstract int signInKind();

    public Code startCode() throws IOException {
        throw new IOException("no code sign-in");
    }

    /** True once the code was approved and the session is stored; false while still waiting. */
    public boolean pollCode(Code c) throws IOException {
        throw new IOException("no code sign-in");
    }

    /** Domains a service's web player may visit. */
    public String[] webDomains() {
        return new String[0];
    }

    public abstract boolean signedIn();

    /** Renews the stored session ahead of time (background thread at start-up); no-op when signed out. */
    public void refreshSession() throws IOException {}

    /**
     * The account's own history or "continue watching" row when signed in (background thread), for home's
     * Continue watching; empty when the service has none or nobody is signed in.
     */
    public List<Item> history() throws IOException {
        if (!signedIn()) return new java.util.ArrayList<>();
        // the account's own section ("My Stuff"), then home: the row titled continue/keep watching or history
        for (Nav n : navs()) {
            String l = n.label.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("my stuff") || l.contains("history") || l.contains("continue")) {
                List<Item> r = historyRow(load(n));
                if (!r.isEmpty()) return r;
            }
        }
        return historyRow(load(navs().get(0)));
    }

    /** The account's saved list when signed in (background thread): the nav or row named watchlist / my list / watch later. */
    public List<Item> watchlist() throws IOException {
        List<Item> out = new java.util.ArrayList<>();
        if (!signedIn()) return out;
        for (Nav n : navs()) {
            String l = n.label.toLowerCase(java.util.Locale.ROOT) + " " + n.id.toLowerCase(java.util.Locale.ROOT);
            if (!(l.contains("watchlist") || l.contains("my list") || l.contains("watch later") || l.contains("my stuff") || l.contains("library"))) continue;
            Item.Page p = load(n);
            for (Item.Shelf s : p.shelves) {
                String t = s.title.toLowerCase(java.util.Locale.ROOT);
                if (t.contains("watchlist") || t.contains("my list") || t.contains("watch later")) return s.items;
            }
            if (l.contains("watchlist") || l.contains("my list") || l.contains("watch later")) {
                for (Item.Shelf s : p.shelves) for (Item it : s.items) if (it.kind != Item.PAGE && out.size() < 12) out.add(it);
                if (!out.isEmpty()) return out;
            }
        }
        return out;
    }

    static List<Item> historyRow(Item.Page p) {
        for (Item.Shelf s : p.shelves) {
            String t = s.title.toLowerCase(java.util.Locale.ROOT);
            if (!s.items.isEmpty() && (t.contains("continue watching") || t.contains("keep watching")
                    || t.contains("history") || t.contains("resume"))) return s.items;
        }
        return new java.util.ArrayList<>();
    }

    /** "Signed in as ..." detail, or empty. */
    public String accountLabel() {
        return prefs.getString("account", "");
    }

    public abstract void signOut();

    /**
     * What the sign-in QR code opens on the phone: the activation page from the config, with the code filled in
     * where the page accepts it ({code}), else the page shown on the card.
     */
    public String codeQrUrl(Code c) {
        String page = c.url.startsWith("http") ? c.url : "https://" + c.url;
        String t = Config.s(id() + ".activateQr", page);
        return t.replace("{code}", android.net.Uri.encode(c.userCode));
    }

    /** Small picture of the signed-in account (URL), or empty. */
    public String accountAvatar() {
        return prefs.getString("avatar", "");
    }

    // ------------------------------------------------------------------------------------ profiles

    /** A viewer profile of the signed-in account (Prime Video profiles, YouTube channels). */
    public static final class Profile {
        public final String id, name, avatar;
        public final boolean kids, active;
        /** Title of the PIN question when the profile is locked, else empty. */
        public final String pinTitle;
        /** Service data needed to switch to it (JSON). */
        public final String data;

        public Profile(String id, String name, String avatar, boolean kids, boolean active, String pinTitle, String data) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? "Profile" : name;
            this.avatar = avatar == null ? "" : avatar;
            this.kids = kids;
            this.active = active;
            this.pinTitle = pinTitle == null ? "" : pinTitle;
            this.data = data == null ? "" : data;
        }

        public boolean locked() {
            return !pinTitle.isEmpty();
        }
    }

    /** Thrown by {@link #selectProfile} when the profile needs its PIN first. */
    public static final class PinNeeded extends IOException {
        public PinNeeded(String title) {
            super(title);
        }
    }

    /**
     * Whether a hover preview of this tile plays as muted video: true only where play() gives a clear HLS stream
     * ClearTV plays natively (no web player, no DRM). Every other tile gets the artwork preview.
     */
    public boolean previewVideo(Item item) {
        return false;
    }

    /**
     * When previewPlayback has no clear stream: an HTML page whose muted, ad-blocked web player previews the tile
     * (YouTube's embed), or null for the artwork card.
     */
    public String previewWebPage(Item item) {
        return null;
    }

    /** The stream a hover preview plays (no side effects: never records history). Defaults to play(). */
    public Playback previewPlayback(Item item) throws IOException {
        return play(item);
    }

    /** Whether the service has profiles to pick from once signed in. */
    public boolean hasProfiles() {
        return false;
    }

    /** The account's profiles, the active one marked (background thread); empty when signed out. */
    public List<Profile> profiles() throws IOException {
        return new java.util.ArrayList<>();
    }

    /**
     * Makes the profile the one used for rows, watchlist, history and playback (background thread). {@code pin}
     * is null on the first try; a locked profile then throws {@link PinNeeded}.
     */
    public void selectProfile(Profile p, String pin) throws IOException {
        throw new IOException(name() + " has no profiles.");
    }

    /** Name of the profile ClearTV uses, or empty (the account's default). */
    public String profileName() {
        return prefs.getString("profile_name", "");
    }

    public String profileId() {
        return prefs.getString("profile_id", "");
    }

    /** "Ask every time": the picker opens whenever the service screen opens. */
    public boolean askProfile() {
        return prefs.getBoolean("profile_ask", false);
    }

    public void setAskProfile(boolean ask) {
        prefs.edit().putBoolean("profile_ask", ask).apply();
    }

    protected void rememberProfile(Profile p) {
        prefs.edit().putString("profile_id", p.id).putString("profile_name", p.name).putString("profile_avatar", p.avatar).apply();
    }

    protected void forgetProfile() {
        prefs.edit().remove("profile_id").remove("profile_name").remove("profile_avatar").apply();
    }

    /** Instructions shown with the sign-in code. */
    public String codeHelp() {
        return "On your phone or computer, go to";
    }
}
