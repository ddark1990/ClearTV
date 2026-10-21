package com.goomero.cleartv.engine;

import java.nio.charset.Charset;
import java.util.HashMap;

/** uBlock Origin-compatible neutered redirect resources served instead of a blocked request. */
public final class Redirects {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Resource {
        public final String mime;
        public final byte[] body;

        Resource(String mime, byte[] body) {
            this.mime = mime;
            this.body = body;
        }
    }

    private static final HashMap<String, String> ALIASES = new HashMap<>();
    private static final HashMap<String, Resource> RES = new HashMap<>();

    static {
        String[][] al = {
                {"noop.js", "noopjs"}, {"noop.txt", "nooptext"}, {"noop.html", "noopframe"}, {"noop.css", "noopcss"},
                {"1x1-transparent.gif", "1x1.gif"}, {"2x2-transparent.png", "2x2.png"},
                {"3x2-transparent.png", "3x2.png"}, {"32x32-transparent.png", "32x32.png"},
                {"noop-0.1s.mp3", "noopmp3-0.1s"}, {"noop-0.5s.mp3", "noopmp3-0.1s"}, {"noop-1s.mp4", "noopmp4-1s"},
                {"noop-vast2.xml", "noopvast-2.0"}, {"noop-vast3.xml", "noopvast-3.0"},
                {"noop-vast4.xml", "noopvast-4.0"}, {"noop-vmap1.0.xml", "noopvmap-1.0"}, {"noop-vmap1.xml", "noopvmap-1.0"},
                {"ga.js", "google-analytics_ga.js"}, {"analytics.js", "google-analytics_analytics.js"},
                {"googletagmanager_gtm.js", "googletagmanager_gtm.js"}, {"gtm.js", "googletagmanager_gtm.js"},
                {"adsbygoogle.js", "googlesyndication_adsbygoogle.js"}, {"amazon-apstag.js", "amazon_apstag.js"},
                {"noop.json", "noopjson"}, {"noopjson", "noopjson"},
        };
        for (String[] a : al) ALIASES.put(a[0], a[1]);
        put("noopjs", "application/javascript", "(function(){})();");
        put("nooptext", "text/plain", "");
        put("empty", "text/plain", "");
        put("noopcss", "text/css", "");
        put("noopframe", "text/html", "<!DOCTYPE html><html><head></head><body></body></html>");
        put("noopjson", "application/json", "{}");
        put("noopvast-2.0", "application/xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"2.0\"></VAST>");
        put("noopvast-3.0", "application/xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"></VAST>");
        put("noopvast-4.0", "application/xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"4.0\"></VAST>");
        put("noopvmap-1.0", "application/xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\"></vmap:VMAP>");
        byte[] gif = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x21, (byte) 0xF9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B};
        RES.put("1x1.gif", new Resource("image/gif", gif));
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44,
                0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15,
                (byte) 0xC4, (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0xDA, 0x63, 0x60,
                0x00, 0x02, 0x00, 0x00, 0x05, 0x00, 0x01, (byte) 0xE9, (byte) 0xFA, (byte) 0xDC, (byte) 0xD8, 0x00, 0x00,
                0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
        Resource p = new Resource("image/png", png);
        RES.put("2x2.png", p);
        RES.put("3x2.png", p);
        RES.put("32x32.png", p);
        put("google-analytics_ga.js", "application/javascript",
                "(function(){var noop=function(){};var tracker={_trackPageview:noop,_setAccount:noop,_trackEvent:noop};"
                + "var gat={_getTracker:function(){return tracker},_getTrackerByName:function(){return tracker},"
                + "_createTracker:function(){return tracker},_anonymizeIp:noop};window._gat=gat;"
                + "var gaq=window._gaq||[];if(Array.isArray(gaq)){window._gaq={push:function(){var a=arguments;"
                + "for(var i=0;i<a.length;i++){var c=a[i];if(typeof c==='function'){try{c()}catch(e){}}}return 0}};"
                + "gaq.forEach(function(c){window._gaq.push(c)})}})();");
        put("google-analytics_analytics.js", "application/javascript",
                "(function(){var noop=function(){};var t={get:noop,set:noop,send:noop};var ga=function(){"
                + "var a=arguments;var len=a.length;if(len===0)return;var o=a[len-1];if(o instanceof Object&&"
                + "typeof o.hitCallback==='function'){try{o.hitCallback()}catch(e){}}};ga.create=function(){return t};"
                + "ga.getByName=function(){return null};ga.getAll=function(){return[t]};ga.remove=noop;ga.loaded=true;"
                + "var n=window.GoogleAnalyticsObject||'ga';var q=window[n];window[n]=ga;"
                + "if(q&&Array.isArray(q.q)){q.q.forEach(function(x){try{ga.apply(null,x)}catch(e){}})}})();");
        put("googletagmanager_gtm.js", "application/javascript",
                "(function(){var noop=function(){};window.ga=window.ga||noop;var dl=window.dataLayer;"
                + "if(dl instanceof Object){if(dl.hide instanceof Object&&typeof dl.hide.end==='function'){"
                + "try{dl.hide.end()}catch(e){}}if(typeof dl.push==='function'){var cb=function(o){"
                + "if(o instanceof Object&&typeof o.eventCallback==='function'){setTimeout(o.eventCallback,1)}};"
                + "dl.push=new Proxy(dl.push,{apply:function(t,a,args){cb(args[0]);return t.apply(a,args)}});"
                + "if(Array.isArray(dl)){dl.slice().forEach(cb)}}}})();");
        put("googlesyndication_adsbygoogle.js", "application/javascript",
                "(function(){window.adsbygoogle={loaded:true,push:function(){}};"
                + "var p=document.querySelectorAll('.adsbygoogle');for(var i=0;i<p.length;i++){"
                + "p[i].style.setProperty('display','none','important')}})();");
        put("amazon_apstag.js", "application/javascript",
                "(function(){var w=window;var noop=function(){};var apstag={_Q:[],fetchBids:function(a,b){"
                + "if(typeof b==='function'){b([])}},init:noop,setDisplayBids:noop,targetingKeys:noop};"
                + "w.apstag=apstag})();");
        put("doubleclick_instream_ad_status.js", "application/javascript", "window.google_ad_status=1;");
        put("scorecardresearch_beacon.js", "application/javascript",
                "(function(){window.COMSCORE={purge:function(){window._comscore=[]},beacon:function(){}};})();");
    }

    private static void put(String name, String mime, String body) {
        RES.put(name, new Resource(mime, body.getBytes(UTF8)));
    }

    private Redirects() {}

    /** Canonical resource name for a $redirect= value ("noop.js:10" -> "noopjs"). */
    static String normalize(String v) {
        String n = v.trim();
        int colon = n.lastIndexOf(':');
        if (colon > 0) n = n.substring(0, colon);
        if (n.isEmpty()) return null;
        String a = ALIASES.get(n);
        return a != null ? a : n;
    }

    /** Resource body, or null when the name is unknown or a media stub (then the request is simply blocked). */
    public static Resource get(String name) {
        return name == null ? null : RES.get(name);
    }
}
