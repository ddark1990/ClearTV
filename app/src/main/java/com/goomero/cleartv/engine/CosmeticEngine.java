package com.goomero.cleartv.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Element hiding, ":style()" rules and scriptlets for one frame, read from a compiled engine blob. Nothing is
 * loaded up front: a snapshot decodes only the rules keyed by the frame's own host names (plus the generic
 * lists), and lowly-generic selectors are decoded per class/id token the page reports.
 */
public final class CosmeticEngine {
    private final ByteBuffer buf;
    private final int base;
    private final MultiMap specific, exceptions, low;
    private final int[] highOffsets, genericExclOffsets, genericScriptletOffsets;
    private volatile String[] highCache;   // decoded on first use, dropped by trim()
    public final int cosmeticCount, scriptletCount, droppedCount, lowKeys, highCount, specificDomains;

    CosmeticEngine(Blob b) {
        buf = b.buf;
        base = b.offset(Blob.S_COS_RECORDS);
        specific = b.multiMap(Blob.S_COS_SPECIFIC);
        exceptions = b.multiMap(Blob.S_COS_EXCEPT);
        low = b.multiMap(Blob.S_COS_LOW);
        highOffsets = b.intList(Blob.S_COS_HIGH);
        genericExclOffsets = b.intList(Blob.S_COS_GENERIC_EXCL);
        genericScriptletOffsets = b.intList(Blob.S_COS_GENERIC_SCRIPTLETS);
        cosmeticCount = b.stat(Blob.ST_COSMETIC);
        scriptletCount = b.stat(Blob.ST_SCRIPTLETS);
        droppedCount = b.stat(Blob.ST_COS_DROPPED);
        lowKeys = b.stat(Blob.ST_LOW_KEYS);
        highCount = b.stat(Blob.ST_HIGH);
        specificDomains = b.stat(Blob.ST_SPECIFIC_DOMAINS);
    }

    public String stats() {
        return "cosmetic=" + cosmeticCount + " (lowGenericKeys=" + lowKeys + " highGeneric=" + highCount
                + " specificDomains=" + specificDomains + ") scriptlets=" + scriptletCount + " dropped=" + droppedCount;
    }

    public void trim() {
        highCache = null;
    }

    /** Selectors and scriptlets for one frame. */
    public static final class Snapshot {
        public final ArrayList<String> hide = new ArrayList<>();
        public final ArrayList<String[]> styles = new ArrayList<>();   // [selector, declarations]
        public final ArrayList<String[]> scriptlets = new ArrayList<>();
        public boolean lowGeneric;
    }

    private static final class Spec {
        String selector, style, key;
        String[] scriptlet;
        long[] exclude;
    }

    private Spec spec(int off) {
        Blob.Cursor c = new Blob.Cursor(buf, base + off);
        Spec s = new Spec();
        int kind = c.u8();
        if (kind == CosmeticCompiler.K_SCRIPTLET) {
            int n = c.varint();
            String[] a = new String[n];
            for (int i = 0; i < n; i++) a[i] = c.string();
            s.scriptlet = a;
            s.key = CosmeticCompiler.scriptletKey(a);
        } else {
            s.selector = c.string();
            if (kind == CosmeticCompiler.K_STYLE) {
                s.style = c.string();
                s.key = s.selector + ":style(" + s.style + ")";
            } else {
                s.key = s.selector;
            }
        }
        s.exclude = c.longArrayAt(base);
        return s;
    }

    private String string(int off) {
        return new Blob.Cursor(buf, base + off).string();
    }

    private void strings(MultiMap m, long key, java.util.Collection<String> out) {
        int idx = m.find(key);
        if (idx < 0) return;
        for (int i = 0, e = m.count(idx); i < e; i++) out.add(string(m.value(idx, i)));
    }

    private HashSet<String> exceptionsFor(HostContext h) {
        HashSet<String> ex = new HashSet<>();
        for (long k : h.suffixHashes) strings(exceptions, k, ex);
        for (long k : h.entityHashes) strings(exceptions, k, ex);
        return ex;
    }

    private void specs(long key, ArrayList<Spec> out) {
        int idx = specific.find(key);
        if (idx < 0) return;
        for (int i = 0, e = specific.count(idx); i < e; i++) out.add(spec(specific.value(idx, i)));
    }

    private String[] high() {
        String[] h = highCache;
        if (h == null) {
            h = new String[highOffsets.length];
            for (int i = 0; i < h.length; i++) h[i] = string(highOffsets[i]);
            highCache = h;
        }
        return h;
    }

    public Snapshot snapshot(HostContext h, int pageFlags, boolean cosmeticOn, boolean scriptletsOn) {
        Snapshot out = new Snapshot();
        HashSet<String> ex = exceptionsFor(h);
        boolean elemhide = (pageFlags & NetFilter.P_ELEMHIDE) != 0;
        boolean generic = cosmeticOn && !elemhide && (pageFlags & NetFilter.P_GENERICHIDE) == 0;
        boolean spec = cosmeticOn && !elemhide && (pageFlags & NetFilter.P_SPECIFICHIDE) == 0;
        boolean noScriptlets = !scriptletsOn || ex.contains("+js()") || (pageFlags & NetFilter.P_DOCUMENT) != 0;
        HashSet<String> seen = new HashSet<>();
        ArrayList<Spec> specs = new ArrayList<>();
        for (long k : h.suffixHashes) specs(k, specs);
        for (long k : h.entityHashes) specs(k, specs);
        for (Spec s : specs) emit(out, s, h, ex, seen, spec, !noScriptlets);
        if (!noScriptlets) for (int off : genericScriptletOffsets) emit(out, spec(off), h, ex, seen, false, true);
        if (generic) {
            for (int off : genericExclOffsets) emit(out, spec(off), h, ex, seen, true, false);
            for (String sel : high()) if (!ex.contains(sel) && seen.add(sel)) out.hide.add(sel);
            out.lowGeneric = true;
        }
        return out;
    }

    private static void emit(Snapshot out, Spec s, HostContext h, HashSet<String> ex, HashSet<String> seen,
                             boolean css, boolean js) {
        if (s.exclude != null && h.matchesAny(s.exclude)) return;
        if (ex.contains(s.key) || !seen.add(s.key)) return;
        if (s.scriptlet != null) {
            if (js) out.scriptlets.add(s.scriptlet);
        } else if (css) {
            if (s.style != null) out.styles.add(new String[]{s.selector, s.style});
            else out.hide.add(s.selector);
        }
    }

    /** Lowly-generic selectors for the ".class" / "#id" tokens found in a page. */
    public ArrayList<String> lowGeneric(HostContext h, String[] tokens) {
        ArrayList<String> out = new ArrayList<>();
        HashSet<String> ex = null;
        ArrayList<String> sels = new ArrayList<>();
        for (String t : tokens) {
            sels.clear();
            strings(low, Hash.of(t), sels);
            if (sels.isEmpty()) continue;
            if (ex == null) ex = exceptionsFor(h);
            for (String s : sels) if (!ex.contains(s)) out.add(s);
        }
        return out;
    }
}
