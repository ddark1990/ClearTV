package com.goomero.cleartv.tv;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * "Go to channel" in every hold-OK menu (home cards in every theme, service rows, all-services search, More like
 * this): opens the channel, creator, series or show page of a tile in its own service. Whether the row shows comes
 * only from the service's capability ({@link Source#hasChannel}, {@link Source#channelLabel}), the same rule on
 * every screen:
 *
 * <ul>
 *   <li>YouTube: the uploader's channel page (from the tile, or looked up from the video; a collaboration opens
 *       its first creator).</li>
 *   <li>Tubi and Prime Video have no channels or creators: "Go to show" opens an episode's series or show page.
 *       Movies, live channels and Prime stations have no such page, so the action is hidden there.</li>
 *   <li>Pluto TV has no creator or show pages (a channel card already opens the channel), so nothing shows.</li>
 * </ul>
 * The menu never waits for the network: {@link #label} answers from the tile alone; a lookup, when needed, runs
 * after the pick on a background thread.
 */
public final class GoToChannel {
    private static final String TAG = "CTV.Channel";

    private GoToChannel() {}

    /** The menu row for this tile, or null when its service has no channel, series or show page for it. */
    public static String label(Activity a, String service, Item it) {
        if (service == null || it == null) return null;
        Source s = source(a, service);
        if (s == null || !s.hasChannel(it)) return null;
        return s.channelLabel();
    }

    /** Opens the channel page; {@code here} (may be null) gets it when it is the same service's screen. */
    public static void open(final Activity a, final String service, final Item it, final Opener here) {
        final Source s = source(a, service);
        if (s == null) return;
        final Item known = it.channel;
        if (known != null && !known.title.isEmpty()) {
            go(a, service, known, here);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Item ch = null;
                String why = null;
                try {
                    ch = s.channelOf(it);
                } catch (IOException e) {
                    why = e.getMessage();
                    Log.w(TAG, service + " channel of " + it.id + ": " + why);
                }
                final Item found = ch;
                final String err = why;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (found == null) {
                            Log.i(TAG, service + ": no channel page for " + it.id + (err != null ? " (" + err + ")" : ""));
                            Toast.makeText(a, err != null ? "Couldn't reach " + s.name() + ". Try again."
                                    : "No channel found for " + it.title, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        go(a, service, found, here);
                    }
                });
            }
        }, "ctv-channel").start();
    }

    /** Opens a page tile on the screen that is already showing its service (keeps BACK in that screen). */
    public interface Opener {
        void openHere(Item page);
    }

    private static void go(Activity a, String service, Item ch, Opener here) {
        Log.i(TAG, service + ": go to " + ch.kind + " " + ch.id);
        if (here != null) here.openHere(ch);
        else ServiceActivity.openInService(a, service, ch);
    }

    private static Source source(Activity a, String service) {
        if (a instanceof ServiceActivity) {
            Source s = ((ServiceActivity) a).sourceFor(service);
            if (s != null) return s;
        }
        try {
            return Source.create(a, service);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
