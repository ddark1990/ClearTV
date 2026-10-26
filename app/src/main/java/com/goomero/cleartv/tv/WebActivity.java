package com.goomero.cleartv.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.goomero.cleartv.App;
import com.goomero.cleartv.Blocker;
import com.goomero.cleartv.Ui;

import java.util.Locale;

/**
 * A service's own web page inside ClearTV, for the two things the native screens cannot do themselves: DRM
 * playback (Prime Video's player, YouTube's embed player); no service's sign-in page is ever shown here. The
 * page stays inside the service's domains, app hand-offs (intent:, market:, app schemes) are refused, ad requests
 * go through ClearTV's blocker, and BACK always returns to the native screen.
 */
public final class WebActivity extends Activity {
    private static final String TAG = "CTV.Web";
    private static final String EXTRA_URL = "url", EXTRA_TV_UA = "tvUa", EXTRA_DOMAINS = "domains",
            EXTRA_ACCENT = "accent", EXTRA_TITLE = "title", EXTRA_SERVICE_ID = "serviceId",
            EXTRA_VIDEO_TITLE = "videoTitle", EXTRA_VIDEO_SUB = "videoSub", EXTRA_PRESSED = "pressed";

    /** Temporary: remote page inspection while measuring (must be false in shipped builds). */
    private static final boolean DEBUG_WEB = false;
    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private String[] domains;
    private Blocker blocker;
    private String contentScript;
    private boolean docStartScript;
    // player mode: the shared overlay and remote, the page's video driven through JavaScript
    private PlayerChrome chrome;
    private View cover;
    private String serviceId = "";
    private int quality;
    private long pressedAt;
    private boolean firstFrame;
    private String playingDims = "";
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile double stT, stD;
    private long resumeMs;
    private volatile boolean stPlaying;
    /** Uptime of the last position report, to time captions between the 1 s polls. */
    private volatile long stAt;
    private WebCaptions captions;
    private int endTicks;

    /**
     * A service's web player. {@code title} is the service name; {@code pressedAt} is the uptime when Play was
     * pressed (for the time-to-first-frame log), or 0.
     */
    public static Intent player(Context c, String url, boolean tvUa, String[] domains, int accent, String title,
                                String serviceId, String videoTitle, String videoSub, long pressedAt) {
        Intent i = new Intent(c, WebActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TV_UA, tvUa);
        i.putExtra(EXTRA_DOMAINS, domains);
        i.putExtra(EXTRA_ACCENT, accent);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_SERVICE_ID, serviceId);
        i.putExtra(EXTRA_VIDEO_TITLE, videoTitle);
        i.putExtra(EXTRA_VIDEO_SUB, videoSub);
        i.putExtra(EXTRA_PRESSED, pressedAt > 0 ? pressedAt : android.os.SystemClock.uptimeMillis());
        return i;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        domains = in.getStringArrayExtra(EXTRA_DOMAINS);
        if (domains == null) domains = new String[0];
        App app = (App) getApplication();
        blocker = new Blocker(app.filters(), app.prefs(), new Blocker.Host() {
            @Override public void onBlockedCountChanged(int count) {}
            @Override public void onMediaPlayingChanged(boolean playing) {}
        });

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.V_BG);
        if (DEBUG_WEB) WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        web.setBackgroundColor(Ui.V_BG);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) s.setOffscreenPreRaster(false);
        String def = s.getUserAgentString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(def);
        String chrome = m.find() ? m.group(1) : "120";
        boolean tv = in.getBooleanExtra(EXTRA_TV_UA, false);
        s.setUserAgentString(tv ? Http.TV_UA
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome + ".0.0.0 Safari/537.36");
        // client hints must agree with the agent string instead of announcing "Android WebView"
        applyUaMetadata(s, !tv, chrome);
        // pages as the services made them (the app's dark theme would otherwise let WebView darken them)
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        // the same content script the main browser runs: cosmetic filters, scriptlets (YouTube ad JSON pruning)
        web.addJavascriptInterface(blocker.new Bridge(), blocker.bridgeName);
        contentScript = loadContentScript();
        docStartScript = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT);
        if (docStartScript) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, contentScript, java.util.Collections.singleton("*"));
            // on-demand ad guard in the players' own frames (YouTube embed, primevideo.com, paramountplus.com)
            java.util.Set<String> players = new java.util.HashSet<>(java.util.Arrays.asList("https://www.youtube.com",
                    "https://www.youtube-nocookie.com", "https://www.primevideo.com", "https://www.paramountplus.com"));
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, AdGuardJs.script(blocker.bridgeName), players);
        }
        serviceId = in.getStringExtra(EXTRA_SERVICE_ID) == null ? "" : in.getStringExtra(EXTRA_SERVICE_ID);
        quality = Quality.get(this, serviceId);
        blocker.setService(serviceId);
        pressedAt = in.getLongExtra(EXTRA_PRESSED, android.os.SystemClock.uptimeMillis());
        StartTrace.mark("web player screen");
        if (docStartScript) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, PLAYING_HOOK, java.util.Collections.singleton("*"));
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, BARE_VIDEO, new java.util.HashSet<>(java.util.Arrays.asList(
                    "https://www.youtube.com", "https://www.youtube-nocookie.com", "https://www.primevideo.com")));
            java.util.Set<String> yt = new java.util.HashSet<>(java.util.Arrays.asList("https://www.youtube.com", "https://www.youtube-nocookie.com"));
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, ytQualityScript(Quality.maxHeight(this, quality), quality == Quality.AUTO), yt);
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, YT_CAPTIONS, yt);
            if (url(in).contains("paramountplus.com")) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, PARAMOUNT_PLAYER,
                        java.util.Collections.singleton("https://www.paramountplus.com"));
            }
            if (url(in).contains("primevideo.com")) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, primeQualityScript(Quality.maxHeight(this, quality)),
                        java.util.Collections.singleton("https://www.primevideo.com"));
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, PRIME_SDK_DIRECT,
                        java.util.Collections.singleton("https://www.primevideo.com"));
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, PRIME_ANCHOR,
                        java.util.Collections.singleton("https://www.primevideo.com"));
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, PRIME_TRACKS,
                        java.util.Collections.singleton("https://www.primevideo.com"));
            }
        }
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int accent = in.getIntExtra(EXTRA_ACCENT, Ui.ACCENT);
        String title = in.getStringExtra(EXTRA_TITLE);
        buildPlayerChrome(accent, title, in.getStringExtra(EXTRA_VIDEO_TITLE), in.getStringExtra(EXTRA_VIDEO_SUB));
        setContentView(root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = in.getStringExtra(EXTRA_URL);
        ytId = youTubeId(url);
        if (ytId != null) {
            // YouTube's own embed player on a page of ours: plain web APIs, works in any WebView, ads filtered
            // like any page. YouTube requires embedders to identify themselves by referrer; ours is the app id.
            // filter as if on youtube.com: annoyance lists block the embed API on third-party pages
            blocker.onMainFrameNavigation("https://www.youtube.com/");
            web.loadDataWithBaseURL(EMBED_BASE, embedPage(ytId, Quality.maxHeight(this, quality)), "text/html", "UTF-8", null);
        } else {
            blocker.onMainFrameNavigation(url);
            if ("paramount".equals(serviceId)) ParamountSource.syncWebSession(this);
            web.loadUrl(url);
        }
        web.requestFocus();
    }

    private static String url(Intent in) {
        String u = in.getStringExtra(EXTRA_URL);
        return u == null ? "" : u;
    }

    // ------------------------------------------------------------------------------------ player mode

    private static final String PLAYING = "ctv-playing ", QUALITY = "ctv-quality ", BUFFER = "ctv-buffer ", ENDED = "ctv-ended",
            TRACKS = "ctv-tracks ", CC = "ctv-cc ";

    /**
     * Prime Video: the subtitle files of the title, read from the page's own playback-resources response
     * (TTML, one per language; forced narratives skipped), for {@link WebCaptions}.
     */
    private static final String PRIME_TRACKS = "(function(){if(window.__ctvT||!window.fetch)return;window.__ctvT=1;var F=window.fetch;"
            + "window.fetch=function(i){var p=F.apply(this,arguments);try{var u=typeof i==='string'?i:(i&&i.url)||'';"
            + "if(u.indexOf('GetVodPlaybackResources')>=0)p.then(function(r){return r.clone().json();}).then(function(j){var out=[];"
            + "(function w(x,d,k){if(!x||typeof x!=='object'||d>6||/forced/i.test(k))return;if(Array.isArray(x)){for(var n=0;n<x.length;n++)w(x[n],d+1,k);return;}"
            + "if(typeof x.url==='string'&&x.languageCode&&(/ttml|dfxp|vtt/i.test(x.format||'')||/\\.(dfxp|ttml|xml|vtt)(\\?|$)/i.test(x.url.split('#')[0]))){var sdh=/sdh/i.test(x.type||x.subtype||'');"
            + "out.push({id:x.languageCode+(sdh?':sdh':''),label:(x.displayName||x.languageCode)+(sdh&&!/\\[CC\\]|SDH/i.test(x.displayName||'')?' [CC]':''),url:x.url});return;}"
            + "for(var q in x)if(Object.prototype.hasOwnProperty.call(x,q))w(x[q],d+1,q);})(j,0,'');"
            + "console.log('" + TRACKS + "'+JSON.stringify(out));"
            + "}).catch(function(){});}catch(e){}return p;};})();";

    /** YouTube's embed frame: the line its (hidden) caption layer shows, reported as it changes. */
    private static final String YT_CAPTIONS = "(function(){if(window.top===window||window.__ctvC||location.pathname.indexOf('/embed/')!==0)return;window.__ctvC=1;var last='';"
            + "setInterval(function(){var w=document.querySelectorAll('.caption-window'),t=[];for(var i=0;i<w.length;i++){"
            + "var l=w[i].querySelectorAll('.caption-visual-line'),s=[];for(var k=0;k<l.length;k++)s.push(l[k].textContent.trim());"
            + "if(!l.length)s.push(w[i].textContent.trim());t.push(s.join('\\n'));}"
            + "var x=t.join('\\n').trim();if(x!==last){last=x;console.log('" + CC + "'+x);}},250);})();";

    /**
     * Runs in every frame of a player page: reports the first frame of a full-window video (the feature, not a
     * page's small background trailer) with its size, so the cover can lift and the start time be logged.
     */
    private static final String PLAYING_HOOK = "(function(){if(window.__ctvHook)return;window.__ctvHook=1;"
            + "function big(v){if(!v||v.tagName!=='VIDEO')return false;var r=v.getBoundingClientRect();return (r.width>=innerWidth*0.85&&r.height>=innerHeight*0.5)||(r.height>=innerHeight*0.85&&r.width>=innerWidth*0.3);}"
            + "document.addEventListener('playing',function(e){var v=e.target;if(!big(v))return;"
            + "console.log('" + PLAYING + "'+v.videoWidth+'x'+v.videoHeight);console.log('" + BUFFER + "0');},true);"
            + "document.addEventListener('waiting',function(e){if(big(e.target))console.log('" + BUFFER + "1');},true);"
            + "document.addEventListener('ended',function(e){if(big(e.target))console.log('" + ENDED + "');},true);})();";

    /**
     * Every service's web player shows only its bare video: page and player chrome (controls, title bars, logos,
     * end screens, suggestions, info cards, pause overlays, spinners, captions, watermarks) is hidden from the first
     * paint, in every frame of the player's site, and kept hidden as the page changes. ClearTV's overlay supplies
     * the controls and the buffering spinner. Prime Video's regulatory notices (the rating badge) stay, pinned to
     * the corner by {@link #PRIME_ANCHOR}.
     */
    static final String BARE_VIDEO = "(function(){if(window.__ctvBare)return;window.__ctvBare=1;"
            + "var css='html,body{background:#000!important}"
            + "body *{visibility:hidden!important;cursor:none!important}"
            + "video{visibility:visible!important;object-fit:contain!important;background:#000!important}"
            + "[class*=top-left-regulatory-overlay],[class*=top-left-regulatory-overlay] *,"
            + "[class*=tobacco-warning-container],[class*=tobacco-warning-container] *{visibility:visible!important}"
            + "[class*=regulatory-overlay-underlay-gradient],[class*=regulatory-overlay-illuminate],[class*=regulatory-overlay-bottom-gradient]{visibility:hidden!important}';"
            // watched without a subtree observer, which would cost Prime's busy page time on every change
            + "var st=null,mo=new MutationObserver(add);function add(){if(st&&st.isConnected&&st.parentNode===(document.head||document.documentElement))return;"
            + "if(!document.documentElement)return;if(!st){st=document.createElement('style');st.textContent=css;}"
            + "var p=document.head||document.documentElement;p.appendChild(st);mo.observe(p,{childList:true});mo.observe(document.documentElement,{childList:true});}"
            + "mo.observe(document,{childList:true});add();document.addEventListener('DOMContentLoaded',add);})();";

    /**
     * Paramount+'s watch page: once its video is playing full size, only the bare video shows (the page and AVIA
     * player chrome are hidden; ClearTV's overlay has the controls). Until then nothing is hidden, so a Resume
     * question or a sign-in page stays visible; a Resume button the player offers before starting is pressed once.
     */
    static final String PARAMOUNT_PLAYER = "(function(){if(window.__ctvPp)return;window.__ctvPp=1;"
            + "var css='html.ctv-bare,html.ctv-bare body{background:#000!important}"
            + "html.ctv-bare body *{visibility:hidden!important;cursor:none!important}"
            + "html.ctv-bare video{visibility:visible!important;object-fit:contain!important;background:#000!important}';"
            + "var st=null,resumed=false;function add(){if(!document.documentElement)return;if(!st){st=document.createElement('style');st.textContent=css;}"
            + "var p=document.head||document.documentElement;if(st.parentNode!==p)p.appendChild(st);}"
            + "setInterval(function(){add();var l=document.querySelectorAll('video'),big=false;"
            + "for(var i=0;i<l.length;i++){var r=l[i].getBoundingClientRect();"
            + "if(r.width>=innerWidth*0.6&&l[i].readyState>=2&&(!l[i].paused||l[i].currentTime>0))big=true;}"
            + "document.documentElement.classList.toggle('ctv-bare',big);"
            + "if(!big&&!resumed){var bs=document.querySelectorAll('button,[role=button]');for(var j=0;j<bs.length;j++){"
            + "var t=(bs[j].innerText||'').trim();if(/^resume( watching)?$/i.test(t)&&bs[j].getBoundingClientRect().width>0){resumed=true;bs[j].click();break;}}}"
            + "},500);document.addEventListener('DOMContentLoaded',add);})();";

    /**
     * YouTube's player inside the embed frame: lets YouTube pick the first segments itself (its quick start),
     * then, 3 s after the picture appears, asks for the best quality at or under the limit (hd1080 on a 1080p
     * TV) and reports what plays. The limit can change later through postMessage from our page.
     */
    private static String ytQualityScript(int maxH, boolean auto) {
        return "(function(){if(window.top===window||window.__ctvQ)return;window.__ctvQ=1;var want=" + maxH + ",go=false,auto=" + auto + ";"
                + "var H={hd2160:2160,hd1440:1440,hd1080:1080,hd720:720,large:480,medium:360,small:240,tiny:144};"
                + "function apply(){var p=document.getElementById('movie_player');if(!p||!p.getAvailableQualityLevels)return;"
                + "if(!go){var v0=document.querySelector('video');if(v0&&v0.videoWidth){var s0=p.getPlaybackQuality()+' '+v0.videoWidth+'x'+v0.videoHeight;if(s0!==window.__ctvLast){window.__ctvLast=s0;console.log('" + QUALITY + "'+s0);}}return;}"
                + "var l=p.getAvailableQualityLevels()||[],q=null;for(var i=0;i<l.length;i++){if(H[l[i]]&&H[l[i]]<=want){q=l[i];break;}}"
                // Auto: YouTube's quick start picks the first rendition, then (go, 3 s after the picture) ClearTV asks for the
                // best rendition this TV shows; the embed's own adaptation alone stayed at 720p on the device
                + "if(q&&auto&&p.getPlaybackQuality()!==q&&!window.__ctvR){window.__ctvR=1;try{p.setPlaybackQualityRange(q,q);}catch(e){}"
                + "try{p.setPlaybackQuality(q);}catch(e){}try{p.seekTo(p.getCurrentTime(),true);}catch(e){}}"
                + "else if(q&&!auto&&p.getPlaybackQuality()!==q){window.__ctvPinned=1;try{p.setPlaybackQualityRange(q,q);}catch(e){}try{p.setPlaybackQuality(q);}catch(e){}"
                + "if(!window.__ctvS){window.__ctvS=1;try{p.seekTo(p.getCurrentTime(),true);}catch(e){}}}"
                + "var v=document.querySelector('video');if(v&&v.videoWidth){var s=p.getPlaybackQuality()+' '+v.videoWidth+'x'+v.videoHeight;"
                + "if(s!==window.__ctvLast){window.__ctvLast=s;console.log('" + QUALITY + "'+s);}}}"
                + "addEventListener('message',function(e){if(e.data&&e.data.ctvMaxH){want=e.data.ctvMaxH;auto=!!e.data.ctvAuto;go=true;window.__ctvLast='';window.__ctvR=0;window.__ctvS=0;apply();}});"
                + "var n=0,t=setInterval(function(){apply();if(++n>60)clearInterval(t);},1000);"
                + "document.addEventListener('playing',function(){if(!go)setTimeout(function(){go=true;apply();},3000);else apply();},true);"
                + "document.addEventListener('resize',apply,true);})();";
    }

    /**
     * Prime Video's web player keeps its quality choice in localStorage; set it before the player reads it
     * (Best for 1080p and Auto, Better for 720p, Good for 480p) and report the playing size.
     */
    private static String primeQualityScript(int maxH) {
        String level = maxH >= 1080 ? "BEST" : maxH >= 720 ? "BETTER" : "GOOD";
        return "(function(){try{var k=Object.keys(localStorage);for(var i=0;i<k.length;i++){var v=localStorage.getItem(k[i])||'';"
                + "if(/videoQuality|VideoQuality|video_quality/.test(k[i])||/\"videoQuality\"/.test(v)){"
                + "if(v.charAt(0)==='{'){var o=JSON.parse(v);o.videoQuality='" + level + "';localStorage.setItem(k[i],JSON.stringify(o));}"
                + "else localStorage.setItem(k[i],'" + level + "');}}}catch(e){}"
                + "document.addEventListener('resize',function(e){var v=e.target;if(v&&v.tagName==='VIDEO'&&v.videoWidth)"
                + "console.log('" + QUALITY + "'+v.videoWidth+'x'+v.videoHeight);},true);})();";
    }


    /**
     * Prime Video's own notices inside its web player (the "Rated PG" maturity badge, the photosensitivity warning,
     * tobacco and product-placement notices) are placed against the picture and the page's 1280 px layout, so they
     * float inside letterboxed films. Pins them all to the top-left corner at the TV-safe margins ClearTV's own
     * overlays use (48 x 27 dp: 5% of the width and height), and hides each one 5 s after it appears; a new notice
     * (different text, or the same one shown again after a pause) gets its own 5 s.
     */
    private static final String PRIME_ANCHOR = "(function(){if(window.__ctvA)return;window.__ctvA=1;"
            + "var N='[class*=top-left-regulatory-overlay],[class*=tobacco-warning-container]';"
            + "var css='[class*=top-left-regulatory-overlay],[class*=tobacco-warning-container]{position:fixed!important;left:5vw!important;top:5vh!important;"
            + "right:auto!important;bottom:auto!important;margin:0!important;padding:0!important;transform:none!important;z-index:2147483646!important}"
            + "[class*=top-left-regulatory-overlay],[class*=tobacco-warning-container]{width:auto!important;height:auto!important;max-width:40vw!important;"
            + "display:flex!important;flex-direction:column!important;align-items:flex-start!important;gap:0.3em!important;"
            + "padding:0.5em 0.9em!important;border-radius:6px!important;background:rgba(6,8,9,0.8)!important;text-align:left!important;box-sizing:border-box!important}"
            // the notice's own lines were absolutely placed against Prime's 1280 px stage: flow them inside the notice
            + "[class*=top-left-regulatory-overlay] *,[class*=tobacco-warning-container] *{position:static!important;left:auto!important;top:auto!important;"
            + "right:auto!important;bottom:auto!important;margin:0!important;transform:none!important;width:auto!important;max-width:100%!important;"
            + "text-align:left!important;line-height:1.3!important;white-space:normal!important}"
            + "[class*=tobacco-warning-container]{top:calc(5vh + 5em)!important}"
            + "html body .ctv-notice-done,html body .ctv-notice-done *{visibility:hidden!important}';"
            + "function add(){var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);}"
            + "if(document.documentElement)add();else document.addEventListener('DOMContentLoaded',add);"
            // a cheap poll instead of a subtree observer on Prime's busy page
            + "var seen=new WeakMap();setInterval(function(){var l=document.querySelectorAll(N),now=Date.now();"
            + "for(var i=0;i<l.length;i++){var e=l[i],r=e.getBoundingClientRect(),t=(e.textContent||'').trim();"
            + "var on=r.width>0&&r.height>0&&t.length>0,st=seen.get(e);"
            + "if(!on){if(st)st.t='';continue;}"
            + "if(!st||st.t!==t){seen.set(e,{t:t,at:now});e.classList.remove('ctv-notice-done');}"
            + "else if(now-st.at>5000)e.classList.add('ctv-notice-done');}},400);})();";

    private static final String SDK_DIRECT = "ctv-sdk-direct ";

    /**
     * Prime Video's player SDK (about 7 MB of script) is downloaded with XHR and run from a blob: URL, which the
     * browser can neither cache nor keep compiled, so every start downloads and compiles it again (most of
     * Prime's start time on a Fire TV). This hands the page the SDK's own URL instead of the blob, so it loads
     * as a normal script: kept in the HTTP cache and, from the second start on, from V8's compiled-code cache.
     */
    private static final String PRIME_SDK_DIRECT = "(function(){if(window.__ctvSdk)return;window.__ctvSdk=1;var last=null;"
            + "var re=/ATVWebPlayerSDK[^\\/]*\\/.*\\.js(\\?|$)/;"
            + "var O=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{if(re.test(String(u)))last=String(u);}catch(e){}return O.apply(this,arguments);};"
            + "if(window.fetch){var F=window.fetch;window.fetch=function(r){try{var u=typeof r==='string'?r:r&&r.url;if(u&&re.test(u))last=u;}catch(e){}return F.apply(this,arguments);};}"
            + "var C=URL.createObjectURL;URL.createObjectURL=function(b){try{if(last&&b&&b.size>1000000&&/javascript|ecmascript|^$/.test(b.type||'')){"
            + "var u=new URL(last,location.href).href;last=null;console.log('" + SDK_DIRECT + "'+u.split('?')[0]);return u;}}catch(e){}return C.apply(this,arguments);};"
            + "var R=URL.revokeObjectURL;URL.revokeObjectURL=function(u){if(typeof u==='string'&&u.indexOf('blob:')!==0)return;return R.apply(this,arguments);};})();";

    /** The page's main video, driven by the remote (YouTube's embed goes through its player API instead). */
    private static final String VIDEO_JS = "(function(){if(window.__ctv)return;window.__ctv={v:function(){var b=null,a=-1;"
            + "var l=document.querySelectorAll('video');for(var i=0;i<l.length;i++){var r=l[i].getBoundingClientRect(),s=r.width*r.height;"
            + "if(s>a){a=s;b=l[i];}}return b;},"
            + "sk:function(){var b=document.querySelector('.atvwebplayersdk-skipelement-button,[class*=skipelement] button');"
            + "if(!b)return '';var r=b.getBoundingClientRect();return r.width>0&&r.height>0?(b.innerText||'Skip').trim():'';},"
            + "st:function(){var v=this.v();if(!v)return '';return JSON.stringify({t:v.currentTime,d:isFinite(v.duration)?v.duration:0,p:!v.paused,s:this.sk()});},"
            + "c:function(cmd,x){var v=this.v();if(!v)return;if(cmd==='play')v.play();else if(cmd==='pause')v.pause();"
            + "else if(cmd==='seek')v.currentTime=Math.max(0,x);"
            // stall recovery: a live video jumps to its live edge, an on-demand one re-seeks where it is (the page's
            // player drops the stuck request and loads that spot again)
            + "else if(cmd==='kick'){var s=v.seekable;if(!isFinite(v.duration)&&s&&s.length){v.currentTime=Math.max(s.start(s.length-1),s.end(s.length-1)-3);}"
            + "else{v.currentTime=v.currentTime+0.05;}var pr=v.play();if(pr&&pr.catch)pr.catch(function(){});}"
            + "else if(cmd==='skip'){var b=document.querySelector('.atvwebplayersdk-skipelement-button,[class*=skipelement] button');if(b)b.click();}}};})();";

    /** Shared stall watchdog: the page's video clock standing still while it should play gets a kick (see VIDEO_JS). */
    private final StallWatchdog watchdog = new StallWatchdog(ui, "web", new StallWatchdog.Target() {
        @Override
        public boolean wantsToPlay() {
            // playing per the page, and not sitting on the last frame of an on-demand title
            return firstFrame && stPlaying && !isFinishing() && !(stD > 0 && stT >= stD - 3);
        }

        @Override
        public long clockMs() {
            return (long) (stT * 1000);
        }

        @Override
        public void recover(int attempt) {
            if (ytId != null) js("typeof P!=='undefined'&&P&&P.seekTo&&(P.seekTo(P.getCurrentTime()+0.05,true),P.playVideo())");
            else js(VIDEO_JS + "__ctv.c('kick',0)");
        }

        @Override
        public void giveUp() {
            android.widget.Toast.makeText(WebActivity.this, "The video stopped loading. Press BACK and play it again.",
                    android.widget.Toast.LENGTH_LONG).show();
        }

        @Override
        public String describe() {
            return "t=" + stT + " d=" + stD + " playing=" + stPlaying;
        }
    });

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private void playerCommand(String cmd, double arg) {
        if (ytId != null) {
            if (cmd.equals("seek")) js("P&&P.seekTo(" + arg + ",true)");
            else js("ctv('" + cmd + "')");
        } else {
            js(VIDEO_JS + "__ctv.c('" + cmd + "'," + arg + ")");
        }
    }

    /** Polls the player's time and state for the overlay (once a second while the activity runs). */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (web == null) return;
            String q = ytId != null ? "(typeof ctvState==='function')?ctvState():''" : VIDEO_JS + "__ctv.st()";
            web.evaluateJavascript(q, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    parseState(v);
                }
            });
            ui.postDelayed(this, 1000);
        }
    };

    private void parseState(String v) {
        if (v == null || v.length() < 4 || v.equals("null")) return;
        try {
            String json = new org.json.JSONTokener(v).nextValue().toString();
            if (json.isEmpty()) return;
            org.json.JSONObject o = new org.json.JSONObject(json);
            boolean was = stPlaying;
            stT = o.optDouble("t", 0);
            stAt = android.os.SystemClock.uptimeMillis();
            stD = o.optDouble("d", 0);
            stPlaying = o.optBoolean("p", false);
            // backstop for the cover: the page's video is playing and its clock is moving, whatever the element's
            // size (a 4:3 or vertical video is pillarboxed and never filled 85% of the width, so the cover stayed
            // up over the playing video with only the sound coming through)
            if (!firstFrame && stPlaying && stT >= 0.5) {
                Log.i(TAG, "cover lifted by the player clock (t=" + stT + ")");
                onFirstFrame(playingDims.isEmpty() ? "?" : playingDims);
            }
            // Prime Video holds the last frame without an "ended" event (its own end screen is hidden)
            if (ytId == null && firstFrame && stD > 0 && stT >= stD - 2.5) {
                if (++endTicks == 4 && chrome != null) chrome.onEnded();
            } else {
                endTicks = 0;
            }
            String skip = o.optString("s", "");
            if (chrome != null) chrome.setExternalSkip(skip.isEmpty() ? null : skip, skip.isEmpty() ? null : skipAction);
            if (was != stPlaying && chrome != null && firstFrame) chrome.onPlayingChanged();
            else if (chrome != null && chrome.isShowing()) chrome.update();
        } catch (org.json.JSONException | ClassCastException e) {
            // not ready yet
        }
    }

    /** The page's own skip button (Prime Video's "Skip Intro" / "Skip Recap"), pressed from the remote. */
    private final Runnable skipAction = new Runnable() {
        @Override
        public void run() {
            playerCommand("skip", 0);
        }
    };

    private void buildPlayerChrome(int accent, String service, String videoTitle, String videoSub) {
        chrome = new PlayerChrome(this, root, accent, new PlayerChrome.Target() {
            @Override public boolean isLive() {
                return firstFrame && stD <= 0;
            }
            @Override public long positionMs() {
                return (long) (stT * 1000);
            }
            @Override public long durationMs() {
                return (long) (stD * 1000);
            }
            @Override public boolean isPlaying() {
                return stPlaying;
            }
            @Override public void setPlaying(boolean play) {
                stPlaying = play;
                playerCommand(play ? "play" : "pause", 0);
                chrome.onPlayingChanged();
            }
            @Override public void seekTo(long ms) {
                stT = ms / 1000.0;
                stAt = android.os.SystemClock.uptimeMillis();
                playerCommand("seek", ms / 1000.0);
            }
            @Override public void options(java.util.List<String> labels, java.util.List<Runnable> actions) {
                if (captions != null && captions.available()) {
                    labels.add("Captions: " + (captions.label().isEmpty() ? "Off" : captions.label()));
                    actions.add(new Runnable() {
                        @Override public void run() {
                            chrome.pick("Captions", captions.rows(), new com.goomero.cleartv.Panel.OnPick() {
                                @Override public void onPick(int which) {
                                    captions.pick(which);
                                }
                            });
                        }
                    });
                }
                labels.add("Quality: " + Quality.shortLabel(quality) + (playingDims.isEmpty() ? "" : " · " + playingDims));
                actions.add(new Runnable() {
                    @Override public void run() {
                        pickQuality();
                    }
                });
            }
            @Override public void exit() {
                finish();
            }
        });
        chrome.setInfo(service, videoTitle == null || videoTitle.isEmpty() ? service : videoTitle, videoSub);
        captions = new WebCaptions(this, serviceId, url(getIntent()).contains("youtube") || youTubeId(url(getIntent())) != null, chrome, new WebCaptions.Host() {
            @Override public void js(String code) {
                WebActivity.this.js(code);
            }
            @Override public long positionMs() {
                return (long) (stT * 1000) + (stPlaying ? android.os.SystemClock.uptimeMillis() - stAt : 0);
            }
        });
        chrome.setQuality(Quality.overlayLine(quality, ""));
        chrome.hide();
        resumeMs = Launcher.attach(this, chrome, getIntent(), serviceId);
        // until the first frame: ClearTV's own cover instead of the service's page loading underneath
        android.widget.LinearLayout c = new android.widget.LinearLayout(this);
        c.setOrientation(android.widget.LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackgroundColor(0xFF000000);
        c.setClickable(true);
        // every line centred and inside the TV-safe margins (the kicker and hint lines used to sit at x=0)
        c.setPadding(Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y), Ui.dp(this, Ui.SAFE_X), Ui.dp(this, Ui.SAFE_Y));
        TextView kick = Ui.oneLine(Ui.kicker(this, service == null ? "" : service, 12, accent));
        kick.setGravity(Gravity.CENTER);
        c.addView(kick, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(this, videoTitle == null ? "" : videoTitle, 26, Ui.V_TEXT);
        t.setTypeface(Ui.display(this));
        t.setGravity(Gravity.CENTER);
        c.addView(Ui.oneLine(t), Ui.below(this, Ui.S1));
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        sp.topMargin = Ui.dp(this, Ui.S3);
        c.addView(spin, sp);
        TextView hintLine = Ui.oneLine(Ui.kicker(this, "Starting playback · BACK to cancel", 11, Ui.V_SOFT));
        hintLine.setGravity(Gravity.CENTER);
        c.addView(hintLine, Ui.below(this, Ui.S2));
        cover = c;
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the page never got to a full-window video: show what it says rather than a spinner forever
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!firstFrame) logStuck(20);
            }
        }, 20_000);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!firstFrame && cover != null) {
                    logStuck(45);
                    removeCover();
                    toast(WebActivity.this, "Playback didn't start. The page may need sign-in or a subscription for this title.");
                }
            }
        }, 45_000);
        ui.postDelayed(poll, 1000);
    }

    private android.widget.ProgressBar buffering;

    /** ClearTV's own buffering spinner over the page's video (the page's spinner is hidden). */
    private void setBuffering(boolean on) {
        if (!firstFrame) return;
        if (buffering == null) {
            buffering = new android.widget.ProgressBar(this);
            buffering.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(getIntent().getIntExtra(EXTRA_ACCENT, Ui.ACCENT)));
            root.addView(buffering, new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER));
        }
        buffering.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /**
     * What the page shows while the cover waits for a video that has not started: the path, whether the page sees a
     * signed-in user (Paramount+), the videos with their state and error, and the page's headings and alerts. Only
     * headings and alerts are read (no buttons or lists, which can hold profile names).
     */
    static final String STUCK_JS = "(function(){try{var v=document.querySelectorAll('video'),vs=[];"
            + "for(var i=0;i<v.length;i++){var r=v[i].getBoundingClientRect();vs.push(Math.round(r.width)+'x'+Math.round(r.height)"
            + "+' rs'+v[i].readyState+(v[i].error?' err'+v[i].error.code:'')+(v[i].paused?' paused':' playing')+' t'+Math.round(v[i].currentTime||0));}"
            + "var R=(window.CBS&&window.CBS.Registry)||{},auth=('user_auth' in R)?String(R.user_auth):'-',h=[];"
            + "var n=document.querySelectorAll('h1,h2,h3,[role=alert],[role=dialog] h1,[role=dialog] h2,[class*=error-message],[class*=errorMessage]');"
            + "for(var j=0;j<n.length&&h.length<8;j++){var e=n[j],b=e.getBoundingClientRect(),t=(e.innerText||'').replace(/\\s+/g,' ').trim();"
            + "if(t&&b.width>0&&b.height>0)h.push(t.slice(0,60));}"
            + "return 'path='+location.pathname+' auth='+auth+' frames='+document.querySelectorAll('iframe').length"
            + "+' videos=['+vs.join('; ')+'] text=['+h.join(' | ')+']';}catch(e){return 'diag error '+e;}})()";

    private void logStuck(final int sec) {
        if (web == null) return;
        web.evaluateJavascript(STUCK_JS, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                String s = v;
                try {
                    Object o = new org.json.JSONTokener(v).nextValue();
                    if (o instanceof String) s = (String) o;
                } catch (Exception ignored) {
                }
                Log.w(TAG, "no picture " + sec + " s after Play (" + serviceId + "): " + (s == null ? "" : s.length() > 700 ? s.substring(0, 700) : s));
            }
        });
    }

    private void removeCover() {
        if (cover == null) return;
        root.removeView(cover);
        cover = null;
    }

    private void onFirstFrame(String dims) {
        if (firstFrame) return;
        firstFrame = true;
        stPlaying = true;
        long ms = android.os.SystemClock.uptimeMillis() - pressedAt;
        Log.i(TAG, "first frame " + ms + " ms after Play (" + serviceId + ", video " + dims + ")");
        StartTrace.end(dims);
        removeCover();
        // Prime Video resumes on its own (t= and the account's position); the embed starts where ClearTV stopped
        if (resumeMs > 0 && chrome != null && !"prime".equals(serviceId) && !"paramount".equals(serviceId)) chrome.target().seekTo(resumeMs);
        if (chrome != null) {
            chrome.show();
            chrome.hidePausedMark();
        }
        if (ytId != null) js("typeof ctvTracks==='function'&&ctvTracks()");
    }

    private void onQuality(String q) {
        playingDims = q;
        Log.i("CTV.Quality", serviceId + " playing " + q + " (choice " + Quality.shortLabel(quality) + ", limit "
                + Quality.maxHeight(this, quality) + "p)");
        if (chrome != null) chrome.setQuality(Quality.overlayLine(quality, q));
    }

    private void pickQuality() {
        chrome.pick("Video quality · " + getIntent().getStringExtra(EXTRA_TITLE), Quality.rows(quality), new com.goomero.cleartv.Panel.OnPick() {
            @Override
            public void onPick(int which) {
                quality = which;
                Quality.set(WebActivity.this, serviceId, which);
                int maxH = Quality.maxHeight(WebActivity.this, which);
                if (ytId != null) {
                    js("(function(){ctvSize(" + maxH + ");var f=document.querySelector('iframe');if(f)f.contentWindow.postMessage({ctvMaxH:" + maxH
                            + ",ctvAuto:" + (which == Quality.AUTO) + "},'*');})()");
                    chrome.setQuality(Quality.overlayLine(quality, playingDims));
                } else {
                    // Prime Video reads its quality setting when the player starts: reload at the current position
                    String u = web.getUrl() == null ? url(getIntent()) : web.getUrl();
                    long t = (long) stT;
                    u = u.replaceAll("([?&])t=\\d+", "$1t=" + t);
                    if (!u.contains("t=")) u += (u.contains("?") ? "&" : "?") + "t=" + t;
                    getIntent().putExtra(EXTRA_URL, u);
                    recreate();
                }
            }
        });
    }

    /** Sets the client hints (Sec-CH-UA*) to match a desktop Chrome or a living-room (TV) agent. */
    public static void applyUaMetadata(WebSettings s, boolean desktop, String chromeMajor) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) return;
        try {
            String full = chromeMajor + ".0.0.0";
            java.util.ArrayList<androidx.webkit.UserAgentMetadata.BrandVersion> brands = new java.util.ArrayList<>();
            brands.add(new androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Not;A=Brand").setMajorVersion("8")
                    .setFullVersion("8.0.0.0").build());
            brands.add(new androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(chromeMajor)
                    .setFullVersion(full).build());
            if (desktop) brands.add(new androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome")
                    .setMajorVersion(chromeMajor).setFullVersion(full).build());
            androidx.webkit.UserAgentMetadata.Builder b = new androidx.webkit.UserAgentMetadata.Builder()
                    .setBrandVersionList(brands)
                    .setFullVersion(full)
                    .setMobile(false)
                    .setModel("");
            if (desktop) b.setPlatform("Windows").setPlatformVersion("10.0.0").setArchitecture("x86").setBitness(64);
            else b.setPlatform("Linux").setPlatformVersion("");
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(s, b.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "user agent metadata not applied", e);
        }
    }

    private static final String EMBED_BASE = "https://com.goomero.cleartv/";
    private static final String YT_ERROR = "ctv-yt-error ";
    private String ytId;

    /** The video id of a youtube.com/watch?v= or youtu.be link, else null. */
    static String youTubeId(String url) {
        if (url == null) return null;
        Uri u = Uri.parse(url);
        String h = u.getHost() == null ? "" : u.getHost();
        String id = null;
        if ((h.equals("www.youtube.com") || h.equals("youtube.com") || h.equals("m.youtube.com")) && "/watch".equals(u.getPath())) {
            id = u.getQueryParameter("v");
        } else if (h.equals("youtu.be") && u.getPath() != null && u.getPath().length() > 1) {
            id = u.getPath().substring(1);
        }
        return id != null && id.matches("[A-Za-z0-9_-]{6,20}") ? id : null;
    }

    private static String embedPage(String id, int frameH) {
        return "<!doctype html><html><head><meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;left:0;top:0;width:100%;height:100%;border:0;transform-origin:0 0}</style>"
                // the player frame starts loading at once; the IFrame API (for the remote) attaches to it when it
                // arrives, instead of the page waiting for the API before it even creates the frame
                + "</head><body><iframe id=\"p\" allow=\"autoplay; encrypted-media\" referrerpolicy=\"strict-origin-when-cross-origin\" src=\"https://www.youtube.com/embed/" + id
                + "?autoplay=1&controls=0&disablekb=1&rel=0&fs=0&playsinline=1&iv_load_policy=3&cc_load_policy=0&modestbranding=1&enablejsapi=1&origin="
                + Uri.encode(EMBED_BASE.substring(0, EMBED_BASE.length() - 1)) + "\"></iframe><script>"
                // YouTube caps the rendition at the frame's size: lay the frame out at the wanted resolution in CSS
                // pixels (at least the screen) and scale it to fit, so a 1080p choice is not capped at 720p
                + "function ctvSize(h){var f=document.getElementById('p'),w=Math.round(h*16/9),W=innerWidth,H=innerHeight;"
                + "if(h<=H){f.style.width='100%';f.style.height='100%';f.style.transform='';return;}"
                + "f.style.width=w+'px';f.style.height=h+'px';f.style.transform='scale('+Math.min(W/w,H/h)+')';}"
                + "ctvSize(" + frameH + ");addEventListener('resize',function(){ctvSize(" + frameH + ");});"
                + "var P=null;function onYouTubeIframeAPIReady(){P=new YT.Player('p',{"
                + "events:{onReady:function(e){e.target.playVideo();},onError:function(e){console.log('" + YT_ERROR + "'+e.data);}}});}"
                + "function ctv(c){if(!P||!P.getPlayerState)return;if(c==='toggle'){P.getPlayerState()===1?P.pauseVideo():P.playVideo();}"
                + "else if(c==='play'){P.playVideo();}else if(c==='pause'){P.pauseVideo();}"
                + "else{P.seekTo(Math.max(0,P.getCurrentTime()+c),true);}}"
                + "function ctvState(){if(!P||!P.getPlayerState)return '';var s=P.getPlayerState();"
                + "return JSON.stringify({t:P.getCurrentTime()||0,d:P.getDuration()||0,p:s===1||s===3});}"
                // captions: the track list once the player has it (the captions module loads off), then the chosen one
                + "var TL=[],CCON=false;function ctvTracks(){if(!P||!P.loadModule)return;P.loadModule('captions');var n=0;(function t(){"
                + "var l=(P.getOption&&P.getOption('captions','tracklist'))||[];if(!l.length&&++n<12){setTimeout(t,500);return;}TL=l;"
                + "if(!CCON)P.setOption('captions','track',{});"
                + "console.log('" + TRACKS + "'+JSON.stringify(l.map(function(x){return {id:x.languageCode+(x.kind==='asr'?':asr':''),"
                + "label:(x.displayName||x.languageName||x.languageCode)};})));})();}"
                + "function ctvCc(i){if(!P||!P.setOption)return;CCON=i>=0&&!!TL[i];if(CCON){P.loadModule('captions');P.setOption('captions','track',TL[i]);}"
                + "else P.setOption('captions','track',{});}"
                + "</script><script src=\"https://www.youtube.com/iframe_api\"></script></body></html>";
    }

    private void ytError(String code) {
        Log.w(TAG, "YouTube embed error " + code + " for " + ytId);
        // 101/150: the uploader allows playback only on youtube.com and in YouTube's apps
        String why = code.equals("101") || code.equals("150") || code.startsWith("152")
                ? "The uploader only allows this video on YouTube itself."
                : code.equals("100") ? "This video is private or was removed." : "YouTube couldn't play this video (error " + code + ").";
        android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_LONG).show();
    }

    private String loadContentScript() {
        try (java.io.InputStream in = getAssets().open("js/content.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toString("UTF-8").replace("__CTV_BRIDGE__", blocker.bridgeName).replace("__CTV_TOKEN__", blocker.token);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("content.js missing", e);
        }
    }

    private boolean allowed(String url) {
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        for (String d : domains) if (h.equals(d) || h.endsWith("." + d)) return true;
        return false;
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse r = blocker.intercept(request);
            if (StartTrace.active()) StartTrace.request(request.getUrl().toString(), r != null);
            return r;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handle(request.getUrl().toString(), request.isForMainFrame());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handle(url, true);
        }

        private boolean handle(String url, boolean mainFrame) {
            if (!mainFrame) return false;
            if (allowed(url)) {
                blocker.onMainFrameNavigation(url);
                return false;
            }
            // intent:, market:, amzn:, aiv:, other sites: stay here; never hand over to another app
            Log.i(TAG, "blocked navigation/hand-off: " + Http.shortUrl(url));
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            StartTrace.mark("page started " + Http.shortUrl(url));
            if (!docStartScript) view.evaluateJavascript(contentScript, null);
                    }

        @Override
        public void onPageFinished(WebView view, String url) {
            StartTrace.mark("page finished " + Http.shortUrl(url));
            if (!docStartScript) view.evaluateJavascript(contentScript, null);
                    }
    }

    private final class Chrome extends WebChromeClient {
        /** Protected media (Widevine) for the service's own pages only; every other permission is refused. */
        @Override
        public void onPermissionRequest(android.webkit.PermissionRequest request) {
            String origin = request.getOrigin() == null ? "" : request.getOrigin().toString();
            for (String r : request.getResources()) {
                if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r) && allowed(origin)) {
                    request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                    return;
                }
            }
            request.deny();
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            String msg = m.message() == null ? "" : m.message();
            if (msg.startsWith(PLAYING)) {
                onFirstFrame(msg.substring(PLAYING.length()).trim());
            } else if (msg.startsWith(BUFFER)) {
                setBuffering(msg.endsWith("1"));
            } else if (msg.startsWith(TRACKS)) {
                if (captions != null) captions.onTracks(msg.substring(TRACKS.length()));
            } else if (msg.startsWith(CC)) {
                if (captions != null) captions.onLine(msg.substring(CC.length()));
            } else if (msg.equals(ENDED)) {
                endTicks = 99;
                if (chrome != null) chrome.onEnded();
            } else if (msg.startsWith(QUALITY)) {
                onQuality(msg.substring(QUALITY.length()).trim());
            } else if (msg.startsWith(SDK_DIRECT)) {
                StartTrace.mark("player SDK loaded as a cached script: " + msg.substring(SDK_DIRECT.length()));
            } else if (ytId != null && msg.startsWith(YT_ERROR)) {
                ytError(msg.substring(YT_ERROR.length()).trim());
            } else if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                Log.w(TAG, "page error: " + msg + " @" + Http.shortUrl(m.sourceId()) + ":" + m.lineNumber());
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.requestFocus();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
            web.requestFocus();
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, android.webkit.JsResult result) {
            result.confirm();
            return true;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        // player: the remote drives the video through JavaScript and never reaches the page's own focus
        if (!firstFrame && code != KeyEvent.KEYCODE_BACK) return true;
        if (chrome.dispatch(ev)) return true;
        if (chrome.optionsShowing()) return super.dispatchKeyEvent(ev);
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        watchdog.stop();
        CookieManager.getInstance().flush();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        watchdog.start();
        // MainActivity pauses WebView timers when it goes to the background, and that pause is process-wide:
        // without this, pages here never get past their first parser-blocking script
        web.resumeTimers();
    }

    @Override
    protected void onDestroy() {
        watchdog.stop();
        ui.removeCallbacksAndMessages(null);
        if (chrome != null) chrome.release();
        if (captions != null) captions.release();
        if (web != null) {
            root.removeView(web);
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }

    /** Clears a service's web session (cookies and site storage) from the WebView profile. */
    /** Adds session cookies (Set-Cookie lines) for the web players, on the main thread. */
    public static void setCookies(final String url, final java.util.List<String> lines) {
        if (lines.isEmpty()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                CookieManager cm = CookieManager.getInstance();
                for (String l : lines) cm.setCookie(url, l);
                cm.flush();
            }
        });
    }

    public static void clearWebSession(String[] urls) {
        CookieManager cm = CookieManager.getInstance();
        for (String url : urls) {
            String all = cm.getCookie(url);
            if (all == null) continue;
            String host = Uri.parse(url).getHost();
            String base = host;
            int dot = host.indexOf('.');
            if (host.startsWith("www.") && dot > 0) base = host.substring(dot + 1);
            for (String part : all.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String name = part.substring(0, eq).trim();
                String expired = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
                cm.setCookie(url, expired);
                cm.setCookie(url, expired + "; Domain=." + base);
                cm.setCookie(url, expired + "; Domain=" + host);
            }
            android.webkit.WebStorage.getInstance().deleteOrigin(Uri.parse(url).getScheme() + "://" + host);
        }
        cm.flush();
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
