package com.goomero.cleartv.engine;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Read-only set of 64-bit hostname hashes stored in a blob. The top {@code bits} bits of a hash pick a bucket
 * through a prefix table and the low 32 bits are stored sorted inside the bucket, so each entry costs 4 bytes and
 * a lookup is one table read plus a binary search over about 8-16 ints (usually one page). The effective key is
 * 32 + bits bits wide (48 for the big domain set): a false match needs a collision on all of them, about one in
 * 3·10^8 lookups for 700k entries.
 *
 * <p>Layout (big-endian): int count, int bits, int[2^bits + 1] bucket starts, int[count] low halves.
 */
final class HostSet {
    static final HostSet EMPTY = new HostSet(null, 0);
    private final ByteBuffer b;
    private final int bits, count, table, values;

    HostSet(ByteBuffer b, int off) {
        this.b = b;
        if (b == null) {
            bits = count = table = values = 0;
            return;
        }
        count = b.getInt(off);
        bits = b.getInt(off + 4);
        table = off + 8;
        values = table + ((1 << bits) + 1) * 4;
    }

    int size() {
        return count;
    }

    boolean contains(long h) {
        if (count == 0) return false;
        int bk = BlobWriter.bucketOf(h, bits);
        int lo = b.getInt(table + bk * 4), hi = b.getInt(table + bk * 4 + 4) - 1;
        int key = (int) h;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = b.getInt(values + mid * 4);
            if (v < key) lo = mid + 1;
            else if (v > key) hi = mid - 1;
            else return true;
        }
        return false;
    }

    /** Writes the set; hashes may be unsorted and contain duplicates. Returns the number of entries written. */
    static int write(BlobWriter w, long[] hashes, int n) {
        int bits = BlobWriter.bucketBits(n, 3, 16);
        long[] keys = new long[n];
        // sort key: bucket in the high half, low 32 bits (signed order) in the low half
        for (int i = 0; i < n; i++) {
            long h = hashes[i];
            keys[i] = ((long) BlobWriter.bucketOf(h, bits) << 32) | ((long) ((int) h) & 0xFFFFFFFFL) ^ 0x80000000L;
        }
        Arrays.sort(keys);
        int m = 0;
        for (int i = 0; i < n; i++) if (m == 0 || keys[m - 1] != keys[i]) keys[m++] = keys[i];
        w.i32(m);
        w.i32(bits);
        int buckets = 1 << bits;
        int k = 0;
        for (int bk = 0; bk <= buckets; bk++) {
            while (k < m && (int) (keys[k] >>> 32) < bk) k++;
            w.i32(k);
        }
        for (int i = 0; i < m; i++) w.i32((int) (keys[i] ^ 0x80000000L));
        return m;
    }
}
