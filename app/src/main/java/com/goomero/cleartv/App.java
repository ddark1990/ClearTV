package com.goomero.cleartv;

import android.app.Application;

/** Process-wide singletons: settings and the filter engine (mapped in the background at start). */
public final class App extends Application {
    Prefs prefs;
    FilterManager filters;

    /** For the native service screens (package tv): the shared blocker inputs. */
    public Prefs prefs() {
        return prefs;
    }

    public FilterManager filters() {
        return filters;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // the active theme's colours, type and layout, before the first screen is built
        com.goomero.cleartv.theme.Themes.init(this);
        // the services' endpoints and parser keys: cached signed copy or the bundled one (no network here)
        BlockStats.init(this);
        com.goomero.cleartv.tv.Config.init(this);
        prefs = new Prefs(this);
        filters = new FilterManager(this, prefs);
        filters.loadAsync();
        // daily update check against the public repo's latest release (Settings > Updates turns it off)
        com.goomero.cleartv.update.Updater.checkIfDue(this);
        // the phone page sends keys and text to the screen in front, and turns itself off when ClearTV leaves it
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(android.app.Activity a) { PhoneLink.onStarted(); }
            @Override public void onActivityResumed(android.app.Activity a) { PhoneLink.onResumed(a); }
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) { PhoneLink.onStopped(); }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // UI hidden or memory getting low: drop the engine's decode caches (they refill from the mapped file)
        if (level >= TRIM_MEMORY_RUNNING_LOW) filters.trimMemory();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        filters.trimMemory();
    }
}
