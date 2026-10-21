package com.goomero.cleartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/**
 * A true picture of a version 2 theme's home for the picker's live preview: the real composed home is built with
 * the theme's tokens, laid out at full screen size off screen and drawn scaled into a small bitmap. The active
 * theme's tokens are restored afterwards.
 */
public final class LayoutSnapshot {
    private LayoutSnapshot() {
    }

    public static Bitmap render(Context c, com.goomero.cleartv.theme.Theme t, int outW) {
        if (t.ui == null || outW <= 0) return null;
        com.goomero.cleartv.theme.Theme prev = Ui.CURRENT;
        Ui.apply(t);
        try {
            HomeView.Callbacks none = new HomeView.Callbacks() {
                public void onOpen(Services.Service s) { }
                public void onServiceMenu(Services.Service s) { }
                public void onSettings() { }
                public void onSearchAll() { }
                public void onOpenEntry(String service, com.goomero.cleartv.tv.Item item) { }
                public void onEntryMenu(String service, com.goomero.cleartv.tv.Item item, boolean favorite) { }
            };
            ComposeHome home;
            ComposeHome.SNAPSHOT = true;
            try {
                home = new ComposeHome(c, none, new TileIcons(c), t.ui);
                home.setRows(HomeBase.lastCont, HomeBase.lastFavs); // the user's real cards, art from the disk cache
            } finally {
                ComposeHome.SNAPSHOT = false;
            }
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int w = Math.max(dm.widthPixels, dm.heightPixels), h = w * 9 / 16;
            home.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            home.layout(0, 0, w, h);
            Bitmap b = Bitmap.createBitmap(outW, outW * 9 / 16, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            float s = outW / (float) w;
            cv.scale(s, s);
            home.draw(cv);
            home.dispose();
            return b;
        } catch (RuntimeException e) {
            android.util.Log.w("CTV.Theme", "preview: " + e);
            return null;
        } finally {
            if (prev != null) Ui.apply(prev);
        }
    }
}
