package com.goomero.cleartv.theme;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.goomero.cleartv.BuildConfig;
import com.goomero.cleartv.update.Net;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The theme store: {@link BuildConfig#STORE_URL} (fixed at build time, the public repo's themes/index.json) lists
 * themes with their file, preview, size and SHA-256. Downloads are https only and size-capped; a theme is installed
 * only when its size and hash match the index, it passes the same strict validator as every import, and its id is
 * the entry's. Themes are data only: nothing in them can run.
 */
public final class ThemeStore {
    private ThemeStore() {}

    public static String source() {
        return BuildConfig.STORE_URL;
    }

    /** Downloads and checks the index. Blocking. */
    public static List<StoreIndex.Entry> index() throws IOException, Theme.Invalid {
        byte[] b = Net.get(Net.url(BuildConfig.STORE_URL), StoreIndex.MAX_BYTES, "application/json");
        return StoreIndex.parse(new String(b, StandardCharsets.UTF_8));
    }

    /** Downloads, checks and installs (or updates) one theme. Blocking. Returns the installed theme. */
    public static Theme install(StoreIndex.Entry e) throws IOException, Theme.Invalid {
        URL u = Net.resolve(Net.url(BuildConfig.STORE_URL), e.file);
        byte[] b = Net.get(u, Theme.MAX_BYTES, "application/json");
        if (b.length != e.size) throw Theme.problem("size", "mismatch", "The file is " + b.length + " bytes; the index says " + e.size + ".");
        if (!Net.sha256(b).equals(e.sha256)) throw Theme.problem("sha256", "mismatch", "The file's SHA-256 does not match the index. Not installed.");
        Theme t = Theme.parse(new String(b, StandardCharsets.UTF_8));
        if (!t.id.equals(e.id)) throw Theme.problem("id", "mismatch", "The file's id \"" + t.id + "\" is not the index's \"" + e.id + "\".");
        Themes.installFromStore(t, e.version);
        return t;
    }

    /** Downloads and decodes a preview image (size and hash checked), scaled down to at most {@code maxW} wide. */
    public static Bitmap preview(StoreIndex.Entry e, int maxW) throws IOException {
        URL u = Net.resolve(Net.url(BuildConfig.STORE_URL), e.preview);
        byte[] b = Net.get(u, StoreIndex.MAX_PREVIEW, "image/*");
        if (b.length != e.previewSize || !Net.sha256(b).equals(e.previewSha256)) throw new Net.Refused("The preview does not match the index.");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 4096 || o.outHeight > 4096) throw new Net.Refused("The preview is not a usable image.");
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= maxW) o.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    /** "Install", "Update" or "" (installed and current) for an entry. */
    public static String action(StoreIndex.Entry e) {
        int have = Themes.storeRev(e.id);
        if (have == 0) return "Install";
        return e.version > have ? "Update" : "";
    }
}
