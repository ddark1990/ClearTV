package com.goomero.cleartv;

import android.net.Uri;

import java.util.Locale;

/**
 * The video services ClearTV wraps. Each opens full screen at its start page and main-frame navigation stays
 * inside its own domains (plus the sign-in domains it needs). To add a service: append an entry to {@link #ALL},
 * add its icon page to tools/fetch_icons.py and run it (writes assets/icons/&lt;iconKey&gt;.webp and its colour).
 */
final class Services {
    static final class Service {
        /** Stable id, used for per-service preferences ("svc:&lt;id&gt;:..."). */
        final String id;
        final String name;
        final String startUrl;
        final int ua;
        final int nav;
        /** Hosts (and their subdomains) a main frame may load. */
        final String[] domains;
        /** Key of the bundled logo and brand colour in assets/icons. */
        final String iconKey;
        /** Short line under the name on the home tile. */
        final String tagline;
        /** Native screen that replaces the website for this service, or null to show the site. */
        final Class<? extends android.app.Activity> nativeViewer;

        Service(String id, String name, String startUrl, int ua, int nav, String[] domains,
                String iconKey, String tagline, Class<? extends android.app.Activity> nativeViewer) {
            this.id = id;
            this.name = name;
            this.startUrl = startUrl;
            this.ua = ua;
            this.nav = nav;
            this.domains = domains;
            this.iconKey = iconKey;
            this.tagline = tagline;
            this.nativeViewer = nativeViewer;
        }

        boolean allows(String url) {
            if (url == null) return false;
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
            return allowsHost(u.getHost());
        }

        boolean allowsHost(String host) {
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            for (String d : domains) if (h.equals(d) || h.endsWith("." + d)) return true;
            return false;
        }

        /** Registrable domain used as the blocking-exception key (same key Blocker uses for a page). */
        String site() {
            return domains[0];
        }
    }

    private static final String[] GOOGLE_SIGN_IN = {"google.com", "accounts.youtube.com", "gstatic.com"};

    static final Service[] ALL = {
            // native screens (tv/ServiceActivity): ClearTV's UI with each service's accent and logo
            new Service("prime", "Prime Video", "https://www.primevideo.com/", Prefs.UA_DESKTOP, Prefs.NAV_FOCUS,
                    new String[]{"primevideo.com", "amazon.com", "media-amazon.com"},
                    "www.primevideo.com", "Movies & series", com.goomero.cleartv.tv.ServiceActivity.class),
            new Service("youtube", "YouTube", "https://www.youtube.com/tv", Prefs.UA_TV, Prefs.NAV_KEYS,
                    concat(new String[]{"youtube.com", "youtu.be"}, GOOGLE_SIGN_IN),
                    "www.youtube.com", "Home, search, channels", com.goomero.cleartv.tv.ServiceActivity.class),
            // native guide and player (pluto/PlutoActivity); the website is never shown
            new Service("pluto", "Pluto TV", "https://pluto.tv/live-tv", Prefs.UA_DESKTOP, Prefs.NAV_FOCUS,
                    new String[]{"pluto.tv"}, "pluto.tv", "Live TV · native guide",
                    com.goomero.cleartv.pluto.PlutoActivity.class),
            new Service("tubi", "Tubi", "https://tubitv.com/home", Prefs.UA_DESKTOP, Prefs.NAV_FOCUS,
                    new String[]{"tubitv.com", "tubi.tv"}, "tubitv.com", "Free movies & TV",
                    com.goomero.cleartv.tv.ServiceActivity.class),
    };

    private Services() {}

    static Service byId(String id) {
        if (id != null) for (Service s : ALL) if (s.id.equals(id)) return s;
        return null;
    }

    /** The service whose domains contain the URL's host, or null. */
    static Service forUrl(String url) {
        for (Service s : ALL) if (s.allows(url)) return s;
        return null;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
