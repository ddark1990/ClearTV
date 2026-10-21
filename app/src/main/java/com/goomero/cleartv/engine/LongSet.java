package com.goomero.cleartv.engine;

import java.util.Arrays;

/** Growable list of 64-bit hashes used while compiling (sets are written to blobs as {@link HostSet}s). */
public final class LongSet {
    private LongSet() {}

    public static final class Builder {
        private long[] buf = new long[1024];
        private int n;

        public void add(long v) {
            if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
            buf[n++] = v;
        }

        public void addAll(Builder o) {
            for (int i = 0; i < o.n; i++) add(o.buf[i]);
        }

        public int count() {
            return n;
        }

        long[] raw() {
            return buf;
        }

        /** Sorted, duplicate-free copy. */
        long[] sortedUnique() {
            long[] a = Arrays.copyOf(buf, n);
            Arrays.sort(a);
            int m = 0;
            for (int i = 0; i < a.length; i++) if (m == 0 || a[m - 1] != a[i]) a[m++] = a[i];
            return m == a.length ? a : Arrays.copyOf(a, m);
        }
    }
}
