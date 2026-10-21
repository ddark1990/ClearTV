package cleartvref;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Immutable sorted set of 64-bit hashes; lookups are binary searches. */
public final class LongSet {
    public static final LongSet EMPTY = new LongSet(new long[0]);
    private final long[] values;

    private LongSet(long[] sortedUnique) {
        this.values = sortedUnique;
    }

    public int size() {
        return values.length;
    }

    public boolean contains(long v) {
        return values.length != 0 && Arrays.binarySearch(values, v) >= 0;
    }

    public long[] raw() {
        return values;
    }

    public static LongSet fromUnsorted(long[] data, int count) {
        long[] a = Arrays.copyOf(data, count);
        Arrays.sort(a);
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (n == 0 || a[n - 1] != a[i]) a[n++] = a[i];
        }
        return new LongSet(n == a.length ? a : Arrays.copyOf(a, n));
    }

    public static LongSet union(LongSet x, LongSet y) {
        long[] a = x.values, b = y.values;
        long[] out = new long[a.length + b.length];
        int i = 0, j = 0, n = 0;
        while (i < a.length || j < b.length) {
            long v;
            if (j >= b.length || (i < a.length && a[i] < b[j])) v = a[i++];
            else if (i >= a.length || b[j] < a[i]) v = b[j++];
            else { v = a[i++]; j++; }
            out[n++] = v;
        }
        return new LongSet(Arrays.copyOf(out, n));
    }

    /** Binary format: magic "CTVD", int version 1, int count, count big-endian longs (sorted). */
    public static LongSet read(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != 0x43545644) throw new IOException("bad domain set magic");
        int ver = in.readInt();
        if (ver != 1) throw new IOException("bad domain set version " + ver);
        int n = in.readInt();
        if (n < 0 || n > 20_000_000) throw new IOException("bad domain set size " + n);
        long[] a = new long[n];
        byte[] buf = new byte[8 * 4096];
        int idx = 0;
        while (idx < n) {
            int chunk = Math.min(4096, n - idx);
            in.readFully(buf, 0, chunk * 8);
            for (int k = 0; k < chunk; k++) {
                int o = k * 8;
                a[idx++] = ((long) (buf[o] & 0xff) << 56) | ((long) (buf[o + 1] & 0xff) << 48)
                        | ((long) (buf[o + 2] & 0xff) << 40) | ((long) (buf[o + 3] & 0xff) << 32)
                        | ((long) (buf[o + 4] & 0xff) << 24) | ((long) (buf[o + 5] & 0xff) << 16)
                        | ((long) (buf[o + 6] & 0xff) << 8) | ((long) (buf[o + 7] & 0xff));
            }
        }
        for (int i = 1; i < n; i++) {
            if (a[i - 1] >= a[i]) throw new IOException("domain set not sorted at " + i);
        }
        return new LongSet(a);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(0x43545644);
        out.writeInt(1);
        out.writeInt(values.length);
        for (long v : values) out.writeLong(v);
    }

    /** Growable buffer used while building. */
    public static final class Builder {
        private long[] buf = new long[1024];
        private int n;

        public void add(long v) {
            if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
            buf[n++] = v;
        }

        public int count() {
            return n;
        }

        public LongSet build() {
            return fromUnsorted(buf, n);
        }
    }
}
