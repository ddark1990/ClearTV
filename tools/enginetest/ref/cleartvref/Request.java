package cleartvref;

/** A network request to classify. Build once per request; fields are read by the matchers. */
public final class Request {
    public final String url;
    final String urlLower;
    final int hostStart, hostEnd;
    public final String host;
    final HostContext req;
    final HostContext page;
    final boolean thirdParty;
    final String method;
    final int type;

    public Request(String url, String method, int type, HostContext page, PublicSuffix psl) {
        this.url = url;
        this.urlLower = url.toLowerCase(java.util.Locale.ROOT);
        int s = urlLower.indexOf("://");
        int hs = s < 0 ? 0 : s + 3;
        int he = hs;
        while (he < urlLower.length()) {
            char c = urlLower.charAt(he);
            if (c == '/' || c == '?' || c == '#' || c == ':') break;
            he++;
        }
        int at = urlLower.lastIndexOf('@', he);
        if (at >= hs) hs = at + 1;
        this.hostStart = hs;
        this.hostEnd = he;
        this.host = urlLower.substring(hs, he);
        this.req = new HostContext(host, psl);
        this.page = page;
        this.thirdParty = page != null && !page.registrable.isEmpty() && !page.registrable.equals(req.registrable);
        this.method = method;
        this.type = type;
    }

    public static int typeFromName(String name) {
        switch (name) {
            case "script": return NetFilter.T_SCRIPT;
            case "image": return NetFilter.T_IMAGE;
            case "stylesheet": return NetFilter.T_STYLESHEET;
            case "xhr": return NetFilter.T_XHR;
            case "subdocument": return NetFilter.T_SUBDOCUMENT;
            case "media": return NetFilter.T_MEDIA;
            case "font": return NetFilter.T_FONT;
            case "document": return NetFilter.T_DOCUMENT;
            case "popup": return NetFilter.T_POPUP;
            case "ping": return NetFilter.T_PING;
            case "other": return NetFilter.T_OTHER;
            default: return UNKNOWN;
        }
    }

    /** Type mask used when the WebView gives no reliable resource type. */
    public static final int UNKNOWN = NetFilter.T_SCRIPT | NetFilter.T_XHR | NetFilter.T_OTHER | NetFilter.T_MEDIA
            | NetFilter.T_FONT | NetFilter.T_OBJECT | NetFilter.T_PING | NetFilter.T_WEBSOCKET;
    public static final int DOCUMENT = NetFilter.T_DOCUMENT;
    public static final int SUBDOCUMENT = NetFilter.T_SUBDOCUMENT;
    public static final int POPUP = NetFilter.T_POPUP;
    public static final int IMAGE = NetFilter.T_IMAGE;
    public static final int STYLESHEET = NetFilter.T_STYLESHEET;
    public static final int SCRIPT = NetFilter.T_SCRIPT;
    public static final int MEDIA = NetFilter.T_MEDIA;
    public static final int FONT = NetFilter.T_FONT;
    public static final int XHR = NetFilter.T_XHR;
}
