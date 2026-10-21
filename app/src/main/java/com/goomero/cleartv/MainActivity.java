package com.goomero.cleartv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.goomero.cleartv.engine.FilterEngine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Home grid of video services, and one full-screen WebView showing the open service. There is no toolbar: MENU
 * opens a small overlay menu (home, reload, the service's settings). Main-frame navigation stays inside the open
 * service's domains. Remote input is focus navigation on the page (default), keys straight to the page (TV
 * interfaces such as YouTube's), or an optional pointer.
 */
public final class MainActivity extends Activity implements Blocker.Host, FilterManager.Listener, Startup.Done {
    private static final String TAG = "CTV.Main";
    /**
     * YouTube's TV interface (youtube.com/tv) for the YouTube service: a Cobalt (YouTube's own TV runtime) agent
     * with this device's Android version and model, which YouTube serves its full living-room interface to
     * (the Movies & TV storefront, Shorts, the same shelves as the TV app).
     */
    private static final String TV_UA = com.goomero.cleartv.tv.Http.TV_UA;

    private Prefs prefs;
    private FilterManager filters;
    private TileIcons tileIcons;
    private Blocker blocker;

    private FrameLayout root, webFrame, fullscreenFrame;
    private Panel panel;
    private WebView web;
    private CursorView cursor;
    private HomeBase home;
    private View progress;

    /** The open service (also while home is showing over it), or null before the first one is opened. */
    private Services.Service current;
    /** Clear the back history once the next page finishes (a different service was opened). */
    private boolean clearHistoryOnLoad;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean mediaPlaying;
    private int navMode = Prefs.NAV_FOCUS;
    private String desktopUa, chromeMajor = "120";
    private boolean docStartScript;
    private String contentScript;
    private long lastBackAt;
    private long centerDownAt = -1;
    private boolean resumed;
    /** Page to reopen when the renderer was killed while the app was in the background ("" = home). */
    private String restoreAfterRendererLoss;
    private final Handler handler = new Handler();
    private final Runnable hideCursor = new Runnable() {
        @Override
        public void run() {
            if (cursor != null) cursor.setVisibility(View.INVISIBLE);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        App app = (App) getApplication();
        prefs = app.prefs;
        filters = app.filters;
        tileIcons = new TileIcons(this);
        blocker = new Blocker(filters, prefs, this);
        filters.setListener(this);
        buildUi();
        // every service has a native screen, so home starts without a WebView (the slowest part of a cold start
        // and ~40 MB); it is created only if a service ever needs the in-page view
        showHome(true);
        // only a fresh start opens the launch link: a recreate (theme change) keeps the original intent and would
        // open that service again on top of home
        if (state == null) handleIntent(getIntent());
        Startup.afterFirstFrame(this, root);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (restoreAfterRendererLoss != null) {
            // the renderer was reclaimed in the background; bring the view back before opening the link
            String url = restoreAfterRendererLoss;
            restoreAfterRendererLoss = null;
            restoreWebView(url);
        }
        handleIntent(intent);
    }

    /** A link to one of the services opens it there; anything else is not a ClearTV page and is refused. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = i.getData();
        if (!Intent.ACTION_VIEW.equals(i.getAction()) || u == null) return;
        String url = u.toString();
        Services.Service s = Services.forUrl(url);
        if (s != null) openService(s, url);
        else toast(R.string.link_not_opened);
    }

    // ------------------------------------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        webFrame = new FrameLayout(this);
        root.addView(webFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cursor = new CursorView(this);
        // page loading indicator: a 3 dp line in the service's brand colour along the top edge
        progress = new View(this);
        progress.setPivotX(0);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3), Gravity.TOP));
        home = HomeBase.create(this, new HomeView.Callbacks() {
            @Override
            public void onOpen(Services.Service s) {
                openService(s, null);
            }

            @Override
            public void onServiceMenu(Services.Service s) {
                showTileMenu(s);
            }

            @Override
            public void onSettings() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }

            @Override
            public void onSearchAll() {
                Intent i = new Intent(MainActivity.this, com.goomero.cleartv.tv.ServiceActivity.class);
                i.putExtra(com.goomero.cleartv.tv.ServiceActivity.EXTRA_SERVICE, "all");
                startActivity(i);
            }

            @Override
            public void onOpenEntry(String service, com.goomero.cleartv.tv.Item item) {
                prefs.setLastService(service);
                com.goomero.cleartv.tv.ServiceActivity.openInService(MainActivity.this, service, item);
            }

            @Override
            public void onEntryMenu(final String service, final com.goomero.cleartv.tv.Item item, final boolean favorite) {
                // a card of a service's own saved list: pin it to ClearTV's list or hide it from home
                final boolean saved = !favorite && com.goomero.cleartv.tv.Library.inWatchlist(MainActivity.this, service, item.id)
                        && com.goomero.cleartv.tv.Library.resumeAt(MainActivity.this, service, item.id) <= 0;
                final java.util.List<String> rows = new java.util.ArrayList<>();
                final java.util.List<Runnable> acts = new java.util.ArrayList<>();
                // the card's channel, creator, series or show page in its service (hidden where there is none)
                String ch = com.goomero.cleartv.tv.GoToChannel.label(MainActivity.this, service, item);
                if (ch != null) {
                    rows.add(ch);
                    acts.add(new Runnable() { public void run() {
                        com.goomero.cleartv.tv.GoToChannel.open(MainActivity.this, service, item, null);
                    } });
                }
                final Runnable refresh = new Runnable() { public void run() { refreshHomeRows(); } };
                if (favorite) {
                    rows.add("Remove from Watchlist");
                    acts.add(new Runnable() { public void run() {
                        com.goomero.cleartv.tv.Library.toggleFavorite(MainActivity.this, service, item);
                        refresh.run();
                    } });
                } else if (saved) {
                    rows.add("Hide from home");
                    acts.add(new Runnable() { public void run() {
                        com.goomero.cleartv.tv.Library.hideFromWatchlist(MainActivity.this, service, item.id);
                        refresh.run();
                    } });
                    rows.add("Pin to ClearTV list");
                    acts.add(new Runnable() { public void run() {
                        if (!com.goomero.cleartv.tv.Library.isFavorite(MainActivity.this, service, item.id))
                            com.goomero.cleartv.tv.Library.toggleFavorite(MainActivity.this, service, item);
                        refresh.run();
                    } });
                } else {
                    rows.add("Remove from Continue watching");
                    acts.add(new Runnable() { public void run() {
                        com.goomero.cleartv.tv.Library.forget(MainActivity.this, service, item.id);
                        refresh.run();
                    } });
                }
                rows.add("Cancel");
                acts.add(null);
                panel.show(item.title, rows.toArray(new String[0]), new Panel.OnPick() {
                    @Override
                    public void onPick(int which) {
                        Runnable r = acts.get(which);
                        if (r != null) r.run();
                    }
                });
            }
        }, tileIcons);
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenFrame = new FrameLayout(this);
        fullscreenFrame.setBackgroundColor(Color.BLACK);
        fullscreenFrame.setVisibility(View.GONE);
        root.addView(fullscreenFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel = new Panel(this, root);
        setContentView(root);
    }

    // -------------------------------------------------------------------------------------- WebView

    private void createWebView() {
        web = new WebView(this);
        // the services are dark sites; a dark base avoids a white flash before their first paint
        web.setBackgroundColor(Ui.BG);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // No second windows: target=_blank links and window.open() load in this view (and pass the same
        // service-domain check in handleUrl); script-opened popups without a user gesture are not opened at all.
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(prefs.textZoom());
        applyDarkPages(s);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) s.setOffscreenPreRaster(false); // no off-screen tiles
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Full priority while on screen; in the background the renderer drops to the app's own priority, so
            // the system can reclaim it first when another app needs memory (onRenderProcessGone restores it).
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
        String def = s.getUserAgentString();
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(def);
        String chrome = m.find() ? m.group(1) : "120";
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome
                + ".0.0.0 Safari/537.36";
        chromeMajor = chrome;
        applyUa(desktopUa);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(blocker.new Bridge(), blocker.bridgeName);
        contentScript = loadContentScript();
        docStartScript = false;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, contentScript, Collections.singleton("*"));
            docStartScript = true;
        }
        Log.i(TAG, "WebView UA=" + def + " documentStartScript=" + docStartScript);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                toast(R.string.downloads_unsupported);
            }
        });
        webFrame.removeAllViews();
        webFrame.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webFrame.addView(cursor, new FrameLayout.LayoutParams(cursor.sizePx(), cursor.sizePx(), Gravity.TOP | Gravity.START));
        applyNavMode(current != null ? prefs.serviceNav(current) : Prefs.NAV_FOCUS);
    }

    private String loadContentScript() {
        try (InputStream in = getAssets().open("js/content.js")) {
            byte[] buf = new byte[in.available() + 1024];
            int n = 0, r;
            while ((r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
                if (n == buf.length) buf = java.util.Arrays.copyOf(buf, n * 2);
            }
            return new String(buf, 0, n, "UTF-8").replace("__CTV_BRIDGE__", blocker.bridgeName)
                    .replace("__CTV_TOKEN__", blocker.token);
        } catch (IOException e) {
            throw new IllegalStateException("content.js missing", e);
        }
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return blocker.intercept(request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(view, request.getUrl().toString(), request.isForMainFrame());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(view, url, true);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!docStartScript) view.evaluateJavascript(contentScript, null);
        }

        @android.annotation.TargetApi(Build.VERSION_CODES.M)
        @Override
        public void onPageCommitVisible(WebView view, String url) {
            // Fallback path only: re-inject once the new document is committed (the onPageStarted call can land
            // on the previous document). content.js guards against running twice per document.
            if (!docStartScript) view.evaluateJavascript(contentScript, null);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!docStartScript) view.evaluateJavascript(contentScript, null);
            if (clearHistoryOnLoad && url != null && url.startsWith("http")) {
                clearHistoryOnLoad = false;
                view.clearHistory(); // BACK from the new service's first page goes home, not to the old service
            }
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
            h.cancel();
            toast(getString(R.string.ssl_error, Blocker.hostOf(error.getUrl())));
        }

        @android.annotation.TargetApi(Build.VERSION_CODES.O)
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The renderer crashed or was killed for memory: replace the WebView instead of crashing the app.
            Log.w(TAG, "renderer gone, crashed=" + detail.didCrash() + " resumed=" + resumed);
            String url = view.getUrl();
            webFrame.removeView(view);
            view.destroy();
            if (view == web) web = null;
            String target = url != null && url.startsWith("http") && !homeVisible() ? url : "";
            if (!resumed) {
                // killed to free memory while in the background: start a new renderer only when the user returns
                restoreAfterRendererLoss = target;
                return true;
            }
            restoreWebView(target);
            toast(R.string.renderer_restarted);
            return true;
        }
    }

    /**
     * Link and redirect handling. Returns true when the WebView must not load the URL itself. Main frames stay
     * inside the open service's domains; subframes and resources are left to the blocker.
     */
    private boolean handleUrl(WebView view, String url, boolean mainFrame) {
        if (url.startsWith("cleartv-proceed:")) {
            String target = Uri.decode(url.substring("cleartv-proceed:".length()));
            if (current != null && !current.allows(target)) return true;
            blocker.allowHostOnce(Blocker.hostOf(target));
            view.loadUrl(target);
            return true;
        }
        String scheme = Uri.parse(url).getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            if (!mainFrame) return false;
            if (current != null && !current.allows(url)) {
                Log.i(TAG, "off-service navigation blocked: " + logUrl(url));
                toast(getString(R.string.off_service_blocked, Blocker.hostOf(url), current.name));
                return true;
            }
            blocker.onMainFrameNavigation(url);
            return false;
        }
        if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) return false;
        // App handoffs (intent:, vnd.youtube:, market:, …) never start another app. When the link names a page of
        // the open service, that page loads here instead; anything else is refused.
        String inService = inServiceUrl(url, scheme);
        if (inService != null) {
            Log.i(TAG, "app link kept in ClearTV: " + logUrl(url) + " -> " + logUrl(inService));
            view.loadUrl(inService);
            return true;
        }
        Log.i(TAG, "app handoff blocked: " + logUrl(url));
        toast(R.string.app_link_blocked);
        return true;
    }

    /** A URL for the log without its query or fragment (sign-in codes, tokens and intent extras live there). */
    static String logUrl(String u) {
        if (u == null) return "";
        int cut = u.length();
        int q = u.indexOf('?'), h = u.indexOf('#');
        if (q >= 0) cut = q;
        if (h >= 0 && h < cut) cut = h;
        return u.substring(0, cut);
    }

    /** The web page an app link points to, if it belongs to the open service; null otherwise. */
    private String inServiceUrl(String url, String scheme) {
        if (current == null) return null;
        String target = null;
        if (scheme.equals("intent")) {
            try {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = i.getStringExtra("browser_fallback_url");
                if (fallback != null && current.allows(fallback)) target = fallback;
                else if (i.getData() != null) target = i.getData().toString();
            } catch (URISyntaxException | RuntimeException e) {
                return null;
            }
        } else if (scheme.equals("vnd.youtube") || scheme.equals("youtube")) {
            // vnd.youtube:VIDEO_ID, vnd.youtube://VIDEO_ID, youtube://www.youtube.com/watch?v=VIDEO_ID
            Uri u = Uri.parse(url);
            String id = u.getQueryParameter("v");
            if (id == null) {
                String rest = url.substring(scheme.length() + 1).replaceFirst("^//", "");
                int cut = rest.indexOf('?');
                id = cut >= 0 ? rest.substring(0, cut) : rest;
                if (id.contains("/")) id = null;
            }
            if (id != null && id.matches("[A-Za-z0-9_-]{6,20}")) target = "https://www.youtube.com/watch?v=" + id;
        }
        if (target == null || !(target.startsWith("https://") || target.startsWith("http://")) || !current.allows(target)) {
            return null;
        }
        return current.id.equals("youtube") ? youtubeTvUrl(target) : target;
    }

    /** youtube.com/watch?v=ID and youtu.be/ID open in the TV interface (youtube.com/tv#/watch?v=ID). */
    static String youtubeTvUrl(String url) {
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost();
        String id = null;
        if (host.endsWith("youtu.be") && u.getPathSegments().size() == 1) id = u.getPathSegments().get(0);
        else if ("/watch".equals(u.getPath())) id = u.getQueryParameter("v");
        return id != null && id.matches("[A-Za-z0-9_-]{6,20}") ? "https://www.youtube.com/tv#/watch?v=" + id : url;
    }

    /**
     * Sets the user agent string and the matching User-Agent Client Hints. Overriding only the string leaves
     * navigator.userAgentData and the Sec-CH-UA-Mobile / -Platform headers saying "Android, mobile", and sites
     * then serve their phone layout to the desktop UA. Needs WebView with USER_AGENT_METADATA (androidx.webkit);
     * older WebViews keep the string only.
     */
    private void applyUa(String ua) {
        WebSettings s = web.getSettings();
        if (ua.equals(s.getUserAgentString()) && ua.equals(appliedUaMeta)) return;
        s.setUserAgentString(ua);
        appliedUaMeta = ua;
        com.goomero.cleartv.tv.WebActivity.applyUaMetadata(s, ua.equals(desktopUa), chromeMajor);
    }

    private String appliedUaMeta;

    /**
     * Pages are shown as the services made them: the app's theme is dark, and on WebView 105+ with targetSdk 33+
     * that alone lets WebView darken pages algorithmically (washed-out colours, unreadable controls). That only
     * happens when the user turns "Darken service pages" on in Settings.
     */
    @SuppressWarnings("deprecation")
    private void applyDarkPages(WebSettings s) {
        boolean dark = prefs.darkPages();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, dark);
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(s, dark ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        }
    }

    private final class Chrome extends WebChromeClient {
        /**
         * "Leave this page?" prompts: a page that has seen a key press can ask before the app navigates away.
         * Here leaving always comes from the user (Home, Reload, another service), so it is confirmed without a
         * dialog.
         */
        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }

        @Override
        public void onProgressChanged(WebView view, int p) {
            if (homeVisible() || p >= 100) {
                progress.setVisibility(View.INVISIBLE);
                return;
            }
            progress.setVisibility(View.VISIBLE);
            progress.setScaleX(Math.max(0.05f, p / 100f));
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            fullscreenFrame.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreenFrame.setVisibility(View.VISIBLE);
            setSystemBarsHidden(true);
            updateKeepScreenOn();
            view.requestFocus();
        }

        @Override
        public void onHideCustomView() {
            exitFullscreenView();
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // DRM (Widevine) for the services; camera/microphone/MIDI are never granted
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                    return;
                }
            }
            request.deny();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    private void exitFullscreenView() {
        if (customView == null) return;
        fullscreenFrame.removeView(customView);
        fullscreenFrame.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        setSystemBarsHidden(false);
        updateKeepScreenOn();
        focusWeb();
    }

    private void setSystemBarsHidden(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void updateKeepScreenOn() {
        if (customView != null || mediaPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ------------------------------------------------------------------------------- Blocker.Host

    @Override
    public void onBlockedCountChanged(int count) {
        // shown in the service menu's title; nothing on screen while watching
    }

    @Override
    public void onMediaPlayingChanged(boolean playing) {
        mediaPlaying = playing;
        updateKeepScreenOn();
    }

    @Override
    public void onEngineChanged(FilterEngine e) {
        updateHomeStatus();
    }

    private void updateHomeStatus() {
        FilterEngine e = filters.engine();
        if (!prefs.blockingOn()) home.setStatus(getString(R.string.home_status_off));
        else if (e == null) home.setStatus(filters.status());
        else home.setStatus(getString(R.string.home_status_on, e.domainCount()));
    }

    // -------------------------------------------------------------------------------- services

    /**
     * Opens a service full screen, at {@code url} or its start page. Coming back to the service already loaded
     * underneath home resumes it where it was. Services with a native screen (Pluto TV) open that instead of the
     * site; nothing is ever handed over to another app.
     */
    private void openService(Services.Service s, String url) {
        if (s.nativeViewer != null) {
            // the WebView is paused by onPause while the native screen is in front
            mediaCommand("pauseAll");
            prefs.setLastService(s.id);
            Intent i = new Intent(this, s.nativeViewer);
            i.putExtra(com.goomero.cleartv.tv.ServiceActivity.EXTRA_SERVICE, s.id);
            if (url != null && !url.equals(s.startUrl)) i.putExtra(com.goomero.cleartv.tv.ServiceActivity.EXTRA_URL, url);
            startActivity(i);
            return;
        }
        if (web == null) {
            try {
                createWebView();
            } catch (RuntimeException e) {
                // WebView missing or being updated by the system
                Log.e(TAG, "WebView unavailable", e);
                toast(getString(R.string.webview_missing));
                return;
            }
        }
        if (current != null && s != current) freshWebView();
        String loaded = web.getUrl();
        boolean resume = url == null && s == current && s.allows(loaded);
        if (s != current) clearHistoryOnLoad = true;
        current = s;
        blocker.setService(s.id);
        prefs.setLastService(s.id);
        progress.setBackgroundColor(brandColor(s));
        applyNavMode(prefs.serviceNav(s));
        hideHome();
        if (!resume) {
            String target = url == null ? s.startUrl : s.id.equals("youtube") ? youtubeTvUrl(url) : url;
            applyUa(s.ua == Prefs.UA_TV ? TV_UA : desktopUa);
            blocker.onMainFrameNavigation(target);
            web.loadUrl(target);
        } else {
            mediaCommand("resumeAll");
        }
        focusWeb();
    }

    /**
     * Each service gets a new WebView. A page left in the old one could hold up the next service's first load
     * (YouTube TV after a key press swallowed the switch to Tubi on the device), and dropping it frees the old
     * service's renderer memory too. Cookies and storage are profile-wide, so sign-ins stay.
     */
    private void freshWebView() {
        exitFullscreenView();
        WebView old = web;
        webFrame.removeView(old);
        old.stopLoading();
        old.destroy();
        createWebView();
    }

    private int brandColor(Services.Service s) {
        int c = tileIcons.colorFor(s.iconKey);
        return c != 0 ? c : Ui.ACCENT;
    }

    @Override
    public void onHomeDataChanged() {
        if (homeVisible()) refreshHomeRows();
    }

    /**
     * Continue watching (ClearTV's own progress first, then the signed-in services' history), favourites, and
     * the quiet health notices on the tiles.
     */
    private void refreshHomeRows() {
        java.util.List<HomeView.Card> cont = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (com.goomero.cleartv.tv.Library.Entry e : com.goomero.cleartv.tv.Library.continueWatching(this)) {
            if (seen.add(e.service + "|" + e.item.id)) cont.add(new HomeView.Card(e.service, serviceName(e.service), e.item, e.progress(), false));
        }
        for (Services.Service s : Services.ALL) {
            int n = 0;
            for (com.goomero.cleartv.tv.Item it : com.goomero.cleartv.tv.Library.history(this, s.id)) {
                if (n++ >= 6 || cont.size() >= 20) break;
                if (seen.add(s.id + "|" + it.id)) cont.add(new HomeView.Card(s.id, s.name, it, 0, false));
            }
        }
        java.util.List<HomeView.Card> favs = new java.util.ArrayList<>();
        for (com.goomero.cleartv.tv.Library.Entry e : com.goomero.cleartv.tv.Library.favorites(this)) {
            favs.add(new HomeView.Card(e.service, serviceName(e.service), e.item, 0, true));
        }
        // the Watchlist row: ClearTV's own list (pinned favourites) first, then each signed-in service's saved list
        java.util.HashSet<String> wl = new java.util.HashSet<>();
        for (HomeView.Card f : favs) wl.add(f.service + "|" + f.item.id);
        for (Services.Service s : Services.ALL) {
            for (com.goomero.cleartv.tv.Item it : com.goomero.cleartv.tv.Library.watchlist(this, s.id)) {
                if (favs.size() >= 40) break;
                if (wl.add(s.id + "|" + it.id)) favs.add(new HomeView.Card(s.id, s.name, it, 0, false));
            }
        }
        HomeBase.lastCont = cont;
        HomeBase.lastFavs = favs;
        home.setRows(cont, favs);
        java.util.HashMap<String, String> notices = new java.util.HashMap<>();
        // short enough for the tile's one line; the full reason and date are in Settings > Services status
        java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", java.util.Locale.getDefault());
        for (com.goomero.cleartv.tv.HealthCheck.Status st : com.goomero.cleartv.tv.HealthCheck.all(this)) {
            if (!st.ok()) notices.put(st.service, "No answer · " + tf.format(new java.util.Date(st.at)));
        }
        home.setNotices(notices);
    }

    private static String serviceName(String id) {
        for (Services.Service s : Services.ALL) if (s.id.equals(id)) return s.name;
        return id;
    }

    private void showHome(boolean focus) {
        updateHomeStatus();
        refreshHomeRows();
        home.setVisibility(View.VISIBLE);
        home.prepare();
        progress.setVisibility(View.INVISIBLE);
        cursor.setVisibility(View.INVISIBLE);
        stopCursorMotion();
        mediaCommand("pauseAll");
        if (focus) home.focusDefault();
    }

    private void hideHome() {
        home.setVisibility(View.GONE);
        home.release();
        tileIcons.trim();
    }

    private boolean homeVisible() {
        return home.getVisibility() == View.VISIBLE;
    }

    private void focusWeb() {
        if (homeVisible()) return;
        web.requestFocus();
        // the pointer appears on the first arrow key, not on its own
    }

    static int nextNav(int m) {
        for (int i = 0; i < Prefs.NAV_ORDER.length; i++) {
            if (Prefs.NAV_ORDER[i] == m) return Prefs.NAV_ORDER[(i + 1) % Prefs.NAV_ORDER.length];
        }
        return Prefs.NAV_FOCUS;
    }

    /** The pointer stays hidden until an arrow key is pressed in cursor mode. */
    private void applyNavMode(int m) {
        if (navMode == Prefs.NAV_FOCUS && m != Prefs.NAV_FOCUS && web != null) {
            web.evaluateJavascript("(function(){var f=window['__ctvNav_" + blocker.token + "'];if(f)f('hide');})()", null);
        }
        navMode = m;
        if (m != Prefs.NAV_CURSOR) {
            stopCursorMotion();
            cursor.setVisibility(View.INVISIBLE);
        }
    }

    private void showCursor() {
        if (cursor.getVisibility() != View.VISIBLE) {
            cursor.moveBy(0, 0); // place it (centred the first time or after the page area changed size)
            cursor.setVisibility(View.VISIBLE);
        }
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, 8000);
    }

    // ---------------------------------------------------------------------------------- menus

    /** MENU while a service is open: home, reload, back, and the service's own settings. */
    private void showServiceMenu() {
        final Services.Service s = current;
        if (s == null) return;
        stopCursorMotion();
        final boolean disabled = prefs.siteBlockingDisabled(s.site());
        final int nav = prefs.serviceNav(s);
        final ArrayList<String> rows = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        rows.add(getString(R.string.home));
        actions.add(new Runnable() { public void run() { showHome(true); } });
        rows.add(getString(R.string.reload));
        actions.add(new Runnable() { public void run() { web.reload(); } });
        if (web.canGoBack()) {
            rows.add(getString(R.string.back));
            actions.add(new Runnable() { public void run() { web.goBack(); } });
        }
        rows.add(getString(R.string.menu_start_over, s.name));
        actions.add(new Runnable() { public void run() { openService(s, s.startUrl); } });
        rows.add(getString(disabled ? R.string.menu_service_blocking_off : R.string.menu_service_blocking_on, s.name));
        actions.add(new Runnable() { public void run() { toggleServiceBlocking(s, true); } });
        rows.add(getString(nav == Prefs.NAV_CURSOR ? R.string.menu_nav_cursor
                : nav == Prefs.NAV_KEYS ? R.string.menu_nav_keys : R.string.menu_nav_focus));
        actions.add(new Runnable() {
            public void run() {
                int next = nextNav(nav);
                prefs.setServiceNav(s, next);
                applyNavMode(next);
                toast(next == Prefs.NAV_CURSOR ? getString(R.string.mode_cursor)
                        : next == Prefs.NAV_KEYS ? getString(R.string.mode_keys, s.name) : getString(R.string.mode_focus));
            }
        });
        rows.add(getString(R.string.settings));
        actions.add(new Runnable() {
            public void run() { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        panel.show(getString(R.string.menu_title, s.name, blocker.blockedCount()), rows.toArray(new String[0]),
                new Panel.OnPick() {
                    @Override
                    public void onPick(int which) {
                        actions.get(which).run();
                    }
                });
    }

    /** MENU (or a long press) on a home card. */
    private void showTileMenu(final Services.Service s) {
        final boolean disabled = prefs.siteBlockingDisabled(s.site());
        final ArrayList<String> rows = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        rows.add(getString(R.string.menu_open, s.name));
        actions.add(new Runnable() { public void run() { openService(s, null); } });
        rows.add(getString(disabled ? R.string.menu_service_blocking_off : R.string.menu_service_blocking_on, s.name));
        actions.add(new Runnable() { public void run() { toggleServiceBlocking(s, false); } });
        panel.show(s.name, rows.toArray(new String[0]), new Panel.OnPick() {
            @Override
            public void onPick(int which) {
                actions.get(which).run();
            }
        });
    }

    private void toggleServiceBlocking(Services.Service s, boolean reload) {
        boolean disabled = prefs.siteBlockingDisabled(s.site());
        prefs.setSiteBlockingDisabled(s.site(), !disabled);
        blocker.refreshSettings();
        toast(getString(disabled ? R.string.site_now_on : R.string.site_now_off, s.name));
        if (reload && s == current) web.reload();
    }

    // -------------------------------------------------------------------------------- input

    private void mediaCommand(String cmd) {
        if (web == null) return;
        web.evaluateJavascript("(function(){var f=window['__ctvMedia_" + blocker.token + "'];if(f)f('" + cmd + "');})()", null);
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isCenter(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        // the remote's voice/search key on home: Search all, listening at once
        if ((code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) && home != null && home.getVisibility() == View.VISIBLE) {
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                Intent i = new Intent(this, com.goomero.cleartv.tv.ServiceActivity.class);
                i.putExtra(com.goomero.cleartv.tv.ServiceActivity.EXTRA_SERVICE, "all");
                i.putExtra(com.goomero.cleartv.tv.ServiceActivity.EXTRA_VOICE, true);
                startActivity(i);
            }
            return true;
        }
        // a card or tile menu on home: BACK closes the menu (it used to fall through and leave ClearTV)
        if (panel != null && panel.isShowing()) {
            if (panel.handleKey(ev)) return true;
            return super.dispatchKeyEvent(ev);
        }
        if (web == null) return super.dispatchKeyEvent(ev);
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = ev.getAction() == KeyEvent.ACTION_UP;

        // fullscreen video: BACK leaves fullscreen, media keys control the video
        if (customView != null) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) {
                    web.evaluateJavascript("document.exitFullscreen&&document.fullscreenElement&&document.exitFullscreen()", null);
                    exitFullscreenView();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (up) showServiceMenu();
                return true;
            }
            if (handleMediaKey(code, down)) return true;
            return super.dispatchKeyEvent(ev);
        }

        if (homeVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) exitWithConfirm();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU && home.findFocus() == null) {
                // cards handle MENU themselves; anywhere else it opens Settings
                if (up) startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return super.dispatchKeyEvent(ev);
        }

        // a service is open
        if (code == KeyEvent.KEYCODE_MENU) {
            if (up) showServiceMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (navMode == Prefs.NAV_KEYS) {
                if (up) backInTvApp();
                return true;
            }
            if (up) goBackOrHome();
            return true;
        }
        if (handleMediaKey(code, down)) return true;
        if (navMode == Prefs.NAV_FOCUS && (isArrow(code) || isCenter(code))) {
            if (down && (ev.getRepeatCount() == 0 || !navPending)) focusNav(code);
            return true;
        }
        if (navMode != Prefs.NAV_CURSOR) {
            return super.dispatchKeyEvent(ev);
        }
        if (isArrow(code)) {
            // key repeats are ignored: the frame loop moves the pointer while the key is held
            if (down && ev.getRepeatCount() == 0) cursorKeyDown(code);
            else if (up) cursorKeyUp(code);
            return true;
        }
        if (isCenter(code)) {
            if (down && ev.getRepeatCount() == 0) {
                showCursor();
                centerDownAt = SystemClock.uptimeMillis();
                touch(MotionEvent.ACTION_DOWN, centerDownAt);
                cursor.setPressedState(true);
            } else if (up && centerDownAt >= 0) {
                touch(MotionEvent.ACTION_UP, centerDownAt);
                centerDownAt = -1;
                cursor.setPressedState(false);
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /**
     * BACK in a TV interface (keys mode): the page's own back (Escape, which YouTube's TV app treats as the
     * remote's back button); on its main screen, BACK returns to the ClearTV home instead of its exit prompt.
     */
    private void backInTvApp() {
        String u = web.getUrl();
        String frag = u == null ? "" : Uri.parse(u).getFragment();
        if (frag == null || frag.isEmpty() || frag.equals("/") || frag.startsWith("/browse")) {
            showHome(true);
            return;
        }
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));
    }

    private boolean handleMediaKey(int code, boolean down) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (navMode == Prefs.NAV_KEYS && customView == null) return false; // the TV app has its own
                if (down) mediaCommand("toggle");
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (navMode == Prefs.NAV_KEYS && customView == null) return false;
                if (down) mediaOrScroll("back", -1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (navMode == Prefs.NAV_KEYS && customView == null) return false;
                if (down) mediaOrScroll("fwd", 1);
                return true;
            default:
                return false;
        }
    }

    /** Seek the page's video if there is one, otherwise scroll by most of a screen. */
    private void mediaOrScroll(final String cmd, final int dir) {
        if (customView != null) {
            mediaCommand(cmd);
            return;
        }
        web.evaluateJavascript("(function(){var f=window['__ctvHasMedia_" + blocker.token + "'];return f?f():false})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if ("true".equals(v)) mediaCommand(cmd);
                        else web.evaluateJavascript("window.scrollBy(0," + dir + "*Math.round(window.innerHeight*0.8))", null);
                    }
                });
    }

    private void goBackOrHome() {
        if (web.canGoBack()) web.goBack();
        else showHome(true);
    }

    private void exitWithConfirm() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackAt < 2500) finish();
        else {
            lastBackAt = now;
            toast(R.string.press_back_again);
        }
    }

    // ---------------------------------------------------------------------------- focus navigation

    private boolean navPending;

    /** Focus mode: content.js moves its highlight ring; OK comes back as the point to tap (see content.js). */
    private void focusNav(final int code) {
        String cmd = isCenter(code) ? "ok" : code == KeyEvent.KEYCODE_DPAD_UP ? "up"
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? "down" : code == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : "right";
        navPending = true;
        web.evaluateJavascript("(function(){var f=window['__ctvNav_" + blocker.token + "'];return f?f('" + cmd + "'):null;})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        navPending = false;
                        onFocusNavResult(code, v == null ? "" : v.replace("\"", ""));
                    }
                });
    }

    private void onFocusNavResult(int code, String r) {
        if (web == null) return;
        if (r.startsWith("tap:") || r.startsWith("input:")) {
            String[] p = r.substring(r.indexOf(':') + 1).split(",");
            try {
                float scale = web.getWidth() / Math.max(1f, Float.parseFloat(p[2]));
                tapAt(Float.parseFloat(p[0]) * scale, Float.parseFloat(p[1]) * scale);
                if (r.startsWith("input:")) showKeyboardForPage();
            } catch (RuntimeException e) {
                Log.w(TAG, "bad nav result " + r);
            }
        } else if (r.equals("null") || r.isEmpty()) {
            // page script not there (still loading, or a page we can't script): plain scrolling
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) web.pageDown(false);
            else if (code == KeyEvent.KEYCODE_DPAD_UP) web.pageUp(false);
        }
    }

    /** A complete finger tap at WebView view coordinates. */
    private void tapAt(float x, float y) {
        long t = SystemClock.uptimeMillis();
        dispatchFinger(MotionEvent.ACTION_DOWN, t, x, y);
        dispatchFinger(MotionEvent.ACTION_UP, t, x, y);
    }

    private void showKeyboardForPage() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && web != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    // ---------------------------------------------------------------------------- pointer motion

    private static final int DIR_L = 1, DIR_R = 2, DIR_U = 4, DIR_D = 8;
    private int heldDirs;           // arrow keys currently held
    private int revealDirs;         // keys whose press only revealed the pointer
    private long holdStartNs, lastFrameNs;
    private boolean frameQueued;
    private float edgeDx, edgeDy;   // scroll owed at the edges, flushed at most every SCROLL_INTERVAL
    private long lastScrollMs, lastJsScrollMs;
    private boolean jsScrollPending;
    private static final long HOLD_DELAY_NS = 160_000_000L; // a tap moves one step; holding moves continuously
    private static final long SCROLL_INTERVAL_MS = 40, JS_SCROLL_INTERVAL_MS = 180;
    private final Choreographer.FrameCallback cursorFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameNs) {
            frameQueued = false;
            cursorFrame(frameNs);
        }
    };

    private static int dirOf(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return DIR_L;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return DIR_R;
            case KeyEvent.KEYCODE_DPAD_UP: return DIR_U;
            default: return DIR_D;
        }
    }

    private float cursorBaseStep() {
        return Ui.dp(this, new float[]{7, 11, 16}[Math.max(0, Math.min(2, prefs.cursorSpeed()))]);
    }

    private void cursorKeyDown(int code) {
        int d = dirOf(code);
        boolean wasHidden = cursor.getVisibility() != View.VISIBLE;
        showCursor();
        if (wasHidden) { // the first press only reveals the pointer
            revealDirs |= d;
            return;
        }
        if (heldDirs == 0) holdStartNs = System.nanoTime();
        heldDirs |= d;
        // immediate single step, so short taps give precise control
        float st = cursorBaseStep();
        stepCursor((d == DIR_R ? st : d == DIR_L ? -st : 0), (d == DIR_D ? st : d == DIR_U ? -st : 0));
        lastFrameNs = 0;
        queueCursorFrame();
    }

    private void cursorKeyUp(int code) {
        int d = dirOf(code);
        revealDirs &= ~d;
        heldDirs &= ~d;
        if (heldDirs == 0) flushEdgeScroll(true);
    }

    private void stopCursorMotion() {
        heldDirs = 0;
        revealDirs = 0;
        edgeDx = edgeDy = 0;
        if (frameQueued) {
            Choreographer.getInstance().removeFrameCallback(cursorFrame);
            frameQueued = false;
        }
    }

    private void queueCursorFrame() {
        if (frameQueued) return;
        frameQueued = true;
        Choreographer.getInstance().postFrameCallback(cursorFrame);
    }

    /** One vsync: move by speed * frame time while keys are held, accelerating the longer they are held. */
    private void cursorFrame(long frameNs) {
        if (heldDirs == 0 || web == null || !web.hasWindowFocus()) {
            heldDirs = 0;
            return;
        }
        long held = frameNs - holdStartNs;
        if (held >= HOLD_DELAY_NS) {
            long dtNs = lastFrameNs == 0 ? 16_666_667L : Math.min(frameNs - lastFrameNs, 50_000_000L);
            float secs = (held - HOLD_DELAY_NS) / 1e9f;
            // ~18 steps/s at first, up to 5x after about 1.6 s
            float speed = cursorBaseStep() * 18f * Math.min(5f, 1f + secs * 2.5f);
            float dist = speed * dtNs / 1e9f;
            float dx = ((heldDirs & DIR_R) != 0 ? dist : 0) - ((heldDirs & DIR_L) != 0 ? dist : 0);
            float dy = ((heldDirs & DIR_D) != 0 ? dist : 0) - ((heldDirs & DIR_U) != 0 ? dist : 0);
            stepCursor(dx, dy);
            lastFrameNs = frameNs;
        }
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, 8000);
        queueCursorFrame();
    }

    private void stepCursor(float dx, float dy) {
        float[] rest = cursor.moveBy(dx, dy);
        if (rest[0] != 0 || rest[1] != 0) {
            edgeDx += rest[0] * 2;
            edgeDy += rest[1] * 2;
            flushEdgeScroll(false);
        }
        if (centerDownAt >= 0) touch(MotionEvent.ACTION_MOVE, centerDownAt); // drag while OK is held
    }

    /**
     * Scrolls by the distance the pointer was pushed past an edge. The page itself scrolls natively with
     * WebView.scrollBy (no JavaScript); only when the page cannot scroll that way (inner scroll boxes) one small
     * script runs, at most every JS_SCROLL_INTERVAL_MS.
     */
    private void flushEdgeScroll(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastScrollMs < SCROLL_INTERVAL_MS) return;
        int dx = Math.round(edgeDx), dy = Math.round(edgeDy);
        edgeDx = edgeDy = 0;
        if ((dx == 0 && dy == 0) || web == null) return;
        lastScrollMs = now;
        boolean canX = dx != 0 && web.canScrollHorizontally(dx), canY = dy != 0 && web.canScrollVertically(dy);
        if (canX || canY) {
            web.scrollBy(canX ? dx : 0, canY ? dy : 0);
            return;
        }
        if (jsScrollPending || now - lastJsScrollMs < JS_SCROLL_INTERVAL_MS) return;
        lastJsScrollMs = now;
        jsScrollPending = true;
        scrollAtCursor(dx, dy);
    }

    /** Scroll the scrollable element under the pointer, or the page. */
    private void scrollAtCursor(final float dxPx, final float dyPx) {
        String js = "(function(X,Y,DX,DY,W){var r=window.innerWidth/W;X*=r;Y*=r;DX*=r;DY*=r;"
                + "var el=document.elementFromPoint(X,Y);"
                + "while(el&&el!==document.body&&el!==document.documentElement){var s=getComputedStyle(el);"
                + "if(DY&&/(auto|scroll)/.test(s.overflowY)&&el.scrollHeight>el.clientHeight){var b=el.scrollTop;el.scrollTop+=DY;if(el.scrollTop!==b)return 1;}"
                + "if(DX&&/(auto|scroll)/.test(s.overflowX)&&el.scrollWidth>el.clientWidth){var c=el.scrollLeft;el.scrollLeft+=DX;if(el.scrollLeft!==c)return 1;}"
                + "el=el.parentElement;}"
                + "var sy=window.scrollY,sx=window.scrollX;window.scrollBy(DX,DY);"
                + "return (window.scrollY!==sy||window.scrollX!==sx)?1:0;})("
                + cursor.cx() + "," + cursor.cy() + "," + dxPx + "," + dyPx + "," + Math.max(1, web.getWidth()) + ")";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                jsScrollPending = false;
            }
        });
    }

    /**
     * Sends a finger touch at the pointer's hot spot. The pointer lives in the WebView's parent at the WebView's
     * origin, so its coordinates are WebView view coordinates; Chromium applies page zoom and scroll itself.
     */
    private void touch(int action, long downTime) {
        if (web == null) return;
        if (cursor.cx() < 0) cursor.moveBy(0, 0); // never tap at an unplaced pointer
        dispatchFinger(action, downTime, cursor.cx() - web.getLeft(), cursor.cy() - web.getTop());
    }

    private void dispatchFinger(int action, long downTime, float x, float y) {
        if (web == null) return;
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = 1f;
        pc.size = 1f;
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc}, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        web.dispatchTouchEvent(e);
        e.recycle();
    }

    // -------------------------------------------------------------------------------- lifecycle

    private void restoreWebView(String url) {
        createWebView();
        Services.Service s = Services.forUrl(url);
        if (s != null) openService(s, url);
        else showHome(true);
    }

    @Override
    protected void onResume() {
        if (web != null) {
            applyDarkPages(web.getSettings());
            web.getSettings().setTextZoom(prefs.textZoom());
        }
        super.onResume();
        resumed = true;
        if (restoreAfterRendererLoss != null) {
            String url = restoreAfterRendererLoss;
            restoreAfterRendererLoss = null;
            restoreWebView(url);
        }
        if (homeVisible()) showHome(home.findFocus() == null);
        if (web == null) return;
        web.onResume();
        web.resumeTimers();
        blocker.refreshSettings();
        web.getSettings().setTextZoom(prefs.textZoom());
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopCursorMotion();
        if (web != null) {
            mediaCommand("pauseAll");
            web.onPause();
            // stops JavaScript timers and layout in the background (process-wide; undone in onResume)
            web.pauseTimers();
            CookieManager.getInstance().flush();
        }
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // TRIM_MEMORY_UI_HIDDEN and higher, or the system running low while we are visible: give back the
        // WebView's in-memory cache (the disk cache stays). The engine caches are released by App.onTrimMemory.
        if (web != null && level >= TRIM_MEMORY_RUNNING_LOW) web.clearCache(false);
        if (level >= TRIM_MEMORY_UI_HIDDEN || !homeVisible()) {
            tileIcons.trim();
            home.release();
        }
    }

    @Override
    protected void onDestroy() {
        filters.setListener(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
