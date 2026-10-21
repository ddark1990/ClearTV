package com.goomero.cleartv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** All persisted settings: blocking options, per-service remote mode and per-site blocking exceptions. */
public final class Prefs {
    /** Remote modes: pointer, keys straight to the page (TV sites), or focus moving between links (default). */
    public static final int NAV_CURSOR = 0, NAV_KEYS = 1, NAV_FOCUS = 2;
    public static final int[] NAV_ORDER = {NAV_FOCUS, NAV_CURSOR, NAV_KEYS};
    public static final int UA_DESKTOP = 0, UA_TV = 2;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("cleartv", Context.MODE_PRIVATE);
        migrateServices011();
    }

    public boolean blockingOn() { return sp.getBoolean("blocking", true); }
    public void setBlockingOn(boolean v) { sp.edit().putBoolean("blocking", v).apply(); }
    public boolean strongOn() { return sp.getBoolean("strong", true); }
    public void setStrongOn(boolean v) { sp.edit().putBoolean("strong", v).apply(); }
    public boolean cosmeticOn() { return sp.getBoolean("cosmetic", true); }
    public void setCosmeticOn(boolean v) { sp.edit().putBoolean("cosmetic", v).apply(); }
    public boolean scriptletsOn() { return sp.getBoolean("scriptlets", true); }
    public void setScriptletsOn(boolean v) { sp.edit().putBoolean("scriptlets", v).apply(); }
    public boolean autoUpdate() { return sp.getBoolean("autoUpdate", true); }
    public void setAutoUpdate(boolean v) { sp.edit().putBoolean("autoUpdate", v).apply(); }
    public long lastUpdate() { return sp.getLong("lastUpdate", 0); }
    /** Time of the first launch of this install (recorded on first call). */
    public long firstSeen() {
        long t = sp.getLong("firstSeen", 0);
        if (t == 0) {
            t = System.currentTimeMillis();
            sp.edit().putLong("firstSeen", t).apply();
        }
        return t;
    }
    public void setLastUpdate(long t) { sp.edit().putLong("lastUpdate", t).apply(); }
    /** Version of assets/filters/app-rules.txt the downloaded lists were last compiled with. */
    public int appRulesVersion() { return sp.getInt("appRulesVersion", 0); }
    public void setAppRulesVersion(int v) { sp.edit().putInt("appRulesVersion", v).apply(); }
    public String lastUpdateResult() { return sp.getString("lastUpdateResult", ""); }
    public void setLastUpdateResult(String s) { sp.edit().putString("lastUpdateResult", s).apply(); }
    public int cursorSpeed() { return sp.getInt("cursorSpeed", 1); } // 0 slow, 1 normal, 2 fast
    public void setCursorSpeed(int v) { sp.edit().putInt("cursorSpeed", v).apply(); }
    /** WebView algorithmic darkening of websites; off by default (it hurts contrast on many sites). */
    public boolean darkPages() { return sp.getBoolean("darkPages", false); }
    public void setDarkPages(boolean v) { sp.edit().putBoolean("darkPages", v).apply(); }
    public int textZoom() { return sp.getInt("textZoom", 100); }
    public void setTextZoom(int v) { sp.edit().putInt("textZoom", v).apply(); }

    // ---- per-site (keyed by registrable domain, e.g. "youtube.com")
    public Set<String> disabledSites() {
        return new HashSet<>(sp.getStringSet("disabledSites", new HashSet<String>()));
    }

    public boolean siteBlockingDisabled(String site) {
        return site != null && !site.isEmpty() && disabledSites().contains(site.toLowerCase(Locale.ROOT));
    }

    public void setSiteBlockingDisabled(String site, boolean disabled) {
        Set<String> s = disabledSites();
        if (disabled) s.add(site.toLowerCase(Locale.ROOT)); else s.remove(site.toLowerCase(Locale.ROOT));
        sp.edit().putStringSet("disabledSites", s).apply();
    }

    /** Remote mode for a service: the user's choice from its menu, else the service's own default. */
    public int serviceNav(Services.Service svc) { return sp.getInt("svc:" + svc.id + ":nav", svc.nav); }
    public void setServiceNav(Services.Service svc, int v) { sp.edit().putInt("svc:" + svc.id + ":nav", v).apply(); }

    /** Service last opened, reopened after the renderer was lost in the background. */
    public String lastService() { return sp.getString("lastService", ""); }
    public void setLastService(String id) { sp.edit().putString("lastService", id).apply(); }

    /**
     * 0.1.1: ClearTV became a launcher for a fixed set of video services. Keys from the 0.1.0 browser (bookmarks,
     * search engine, default remote mode and user agent, per-site agent and remote overrides) no longer mean
     * anything and are removed; per-site blocking exceptions stay.
     */
    void migrateServices011() {
        if (sp.getBoolean("migratedServices011", false)) return;
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.equals("bookmarks") || k.equals("search") || k.equals("nav") || k.equals("ua")
                    || k.startsWith("nav:") || k.startsWith("ua:") || k.startsWith("watchOnly:")
                    || k.equals("migratedNav011")) e.remove(k);
        }
        e.putBoolean("migratedServices011", true).apply();
    }
}
