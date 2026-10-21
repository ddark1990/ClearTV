package com.goomero.cleartv.engine;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Read-only map from 64-bit keys (token or hostname hashes) to lists of int values (record offsets), stored in a
 * blob. Keys are kept in full, so a lookup never returns another key's values. Value order is the insertion order
 * used when the map was compiled, which keeps "first matching filter" semantics unchanged.
 *
 * <p>Layout: int count, int bits, int[2^bits + 1] bucket starts, long[count] keys (sorted by bucket, then key),
 * int[count] entries, int[] lists. An entry {@code >= 0} is the key's only value; a negative entry points (low 31
 * bits) at a list in {@code lists}: its length, then the values. Most keys have one value, so they cost 12 bytes.
 */
final class MultiMap {
    static final MultiMap EMPTY = new MultiMap(null, 0);
    private final ByteBuffer b;
    private final int bits, count, table, keys, entries, lists;

    MultiMap(ByteBuffer b, int off) {
        this.b = b;
        if (b == null) {
            bits = count = table = keys = entries = lists = 0;
            return;
        }
        count = b.getInt(off);
        bits = b.getInt(off + 4);
        table = off + 8;
        keys = table + ((1 << bits) + 1) * 4;
        entries = keys + count * 8;
        lists = entries + count * 4;
    }

    int size() {
        return count;
    }

    /** Index of key, or -1. */
    int find(long key) {
        if (count == 0) return -1;
        int bk = BlobWriter.bucketOf(key, bits);
        int lo = b.getInt(table + bk * 4), hi = b.getInt(table + bk * 4 + 4) - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = b.getLong(keys + mid * 8);
            if (v < key) lo = mid + 1;
            else if (v > key) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    /** Number of values of the key at idx. */
    int count(int idx) {
        int e = b.getInt(entries + idx * 4);
        return e >= 0 ? 1 : b.getInt(lists + (e & 0x7FFFFFFF) * 4);
    }

    /** The j-th value of the key at idx. */
    int value(int idx, int j) {
        int e = b.getInt(entries + idx * 4);
        return e >= 0 ? e : b.getInt(lists + ((e & 0x7FFFFFFF) + 1 + j) * 4);
    }

    /** Build-time accumulator. */
    static final class Builder {
        private final LongObjMap<int[]> map = new LongObjMap<>();   // [0] = size, then values
        private int keyCount, valueCount;

        void add(long key, int value) {
            int[] l = map.get(key);
            if (l == null) {
                l = new int[3];
                map.put(key, l);
                keyCount++;
            } else if (l[0] + 1 == l.length) {
                l = Arrays.copyOf(l, l.length * 2);
                map.put(key, l);
            }
            l[++l[0]] = value;
            valueCount++;
        }

        int keyCount() {
            return keyCount;
        }

        /** Writes the map; every value is shifted by delta (record offsets relocated into the final section). */
        void write(final BlobWriter w, final int delta) {
            final long[] ks = new long[keyCount];
            final int[] pos = {0};
            map.forEachEntry(new LongObjMap.Visitor<int[]>() {
                @Override
                public void visit(long key, int[] value) {
                    ks[pos[0]++] = key;
                }
            });
            final int bits = BlobWriter.bucketBits(keyCount, 2, 16);
            // order by (bucket, key); keys inside one bucket share their top bits, so signed order works
            Long[] boxed = new Long[keyCount];
            for (int i = 0; i < keyCount; i++) boxed[i] = ks[i];
            Arrays.sort(boxed, new java.util.Comparator<Long>() {
                @Override
                public int compare(Long x, Long y) {
                    int bx = BlobWriter.bucketOf(x, bits), by = BlobWriter.bucketOf(y, bits);
                    if (bx != by) return bx < by ? -1 : 1;
                    return Long.compare(x, y);
                }
            });
            w.i32(keyCount);
            w.i32(bits);
            int k = 0;
            for (int bk = 0; bk <= (1 << bits); bk++) {
                while (k < keyCount && BlobWriter.bucketOf(boxed[k], bits) < bk) k++;
                w.i32(k);
            }
            for (Long key : boxed) w.i64(key);
            int p = 0;
            for (Long key : boxed) {
                int[] l = map.get(key);
                if (l[0] == 1) {
                    w.i32(l[1] + delta);
                } else {
                    w.i32(0x80000000 | p);
                    p += 1 + l[0];
                }
            }
            for (Long key : boxed) {
                int[] l = map.get(key);
                if (l[0] == 1) continue;
                w.i32(l[0]);
                for (int i = 1; i <= l[0]; i++) w.i32(l[i] + delta);
            }
        }
    }
}
