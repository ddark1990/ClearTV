package cleartvref;

/** Open-addressing map from long keys to objects (no boxing on lookup). */
final class LongObjMap<V> {
    private long[] keys;
    private Object[] vals;
    private boolean[] used;
    private int size;

    LongObjMap() {
        this(64);
    }

    LongObjMap(int cap) {
        int c = 16;
        while (c < cap * 2) c <<= 1;
        keys = new long[c];
        vals = new Object[c];
        used = new boolean[c];
    }

    private static int mix(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        return (int) k;
    }

    @SuppressWarnings("unchecked")
    V get(long k) {
        int mask = keys.length - 1;
        int i = mix(k) & mask;
        while (used[i]) {
            if (keys[i] == k) return (V) vals[i];
            i = (i + 1) & mask;
        }
        return null;
    }

    void put(long k, V v) {
        if ((size + 1) * 2 > keys.length) grow();
        int mask = keys.length - 1;
        int i = mix(k) & mask;
        while (used[i]) {
            if (keys[i] == k) { vals[i] = v; return; }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = k;
        vals[i] = v;
        size++;
    }

    int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        long[] ok = keys;
        Object[] ov = vals;
        boolean[] ou = used;
        keys = new long[ok.length * 2];
        vals = new Object[ok.length * 2];
        used = new boolean[ok.length * 2];
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], (V) ov[i]);
    }

    interface Visitor<V> {
        void visit(long key, V value);
    }

    @SuppressWarnings("unchecked")
    void forEachEntry(Visitor<V> v) {
        for (int i = 0; i < keys.length; i++) if (used[i]) v.visit(keys[i], (V) vals[i]);
    }
}
