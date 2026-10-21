package com.goomero.cleartv.engine;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Read-only view of a compiled engine file (see {@link FilterCompiler} for the layout). The buffer is normally a
 * MappedByteBuffer over the APK asset or a file in the app's files dir, so the data lives in the page cache
 * rather than on the Java heap: pages are loaded on first touch and the kernel can drop them under memory
 * pressure. All reads are absolute, so one Blob can be shared by any number of threads.
 */
final class Blob {
    static final int MAGIC = 0x43545645; // "CTVE"
    static final int VERSION = 2;

    // section ids
    static final int S_STATS = 0, S_PSL_RULES = 1, S_PSL_WILD = 2, S_PSL_EXC = 3, S_DOMAINS = 4, S_LIST_HOSTS = 5,
            S_ALLOW_EXACT = 6, S_ALLOW_SUFFIX = 7, S_NET_RECORDS = 8, S_NET_BLOCK = 9, S_NET_DOC_BLOCK = 10,
            S_NET_EXCEPT = 11, S_NET_IMPORTANT = 12, S_NET_REDIRECT = 13, S_NET_PAGE = 14, S_COS_RECORDS = 15,
            S_COS_SPECIFIC = 16, S_COS_EXCEPT = 17, S_COS_LOW = 18, S_COS_HIGH = 19, S_COS_GENERIC_EXCL = 20,
            S_COS_GENERIC_SCRIPTLETS = 21;
    static final int SECTIONS = 22;

    // S_STATS slots
    static final int ST_DOMAINS_SOURCE = 0, ST_LIST_HOSTS_SOURCE = 1, ST_NET_FILTERS = 2, ST_NET_DROPPED = 3,
            ST_COSMETIC = 4, ST_SCRIPTLETS = 5, ST_COS_DROPPED = 6, ST_LOW_KEYS = 7, ST_HIGH = 8,
            ST_SPECIFIC_DOMAINS = 9, ST_DOMAINS_STORED = 10, ST_LIST_HOSTS_STORED = 11;
    static final int STATS = 12;

    final ByteBuffer buf;
    private final int[] off = new int[SECTIONS];
    private final int[] len = new int[SECTIONS];

    Blob(ByteBuffer b) throws IOException {
        buf = b;
        if (b.capacity() < 16 + SECTIONS * 8) throw new IOException("engine file too small");
        if (b.getInt(0) != MAGIC) throw new IOException("not an engine file");
        int v = b.getInt(4);
        if (v != VERSION) throw new IOException("engine file version " + v + ", expected " + VERSION);
        int total = b.getInt(8);
        if (total != b.capacity()) throw new IOException("engine file truncated (" + b.capacity() + " of " + total + " bytes)");
        int count = b.getInt(12);
        if (count != SECTIONS) throw new IOException("engine file has " + count + " sections");
        for (int i = 0; i < SECTIONS; i++) {
            off[i] = b.getInt(16 + i * 8);
            len[i] = b.getInt(20 + i * 8);
            if (off[i] < 0 || len[i] < 0 || (long) off[i] + len[i] > total) throw new IOException("bad section " + i);
        }
    }

    boolean has(int section) {
        return len[section] > 0;
    }

    int offset(int section) {
        return off[section];
    }

    int stat(int slot) {
        return has(S_STATS) && slot * 4 < len[S_STATS] ? buf.getInt(off[S_STATS] + slot * 4) : 0;
    }

    HostSet hostSet(int section) {
        return has(section) ? new HostSet(buf, off[section]) : HostSet.EMPTY;
    }

    MultiMap multiMap(int section) {
        return has(section) ? new MultiMap(buf, off[section]) : MultiMap.EMPTY;
    }

    /** IntList section: int count, count ints. */
    int[] intList(int section) {
        if (!has(section)) return new int[0];
        int o = off[section];
        int n = buf.getInt(o);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = buf.getInt(o + 4 + i * 4);
        return a;
    }

    /** Sequential decoder over a records section. Not thread-safe; create one per use. */
    static final class Cursor {
        private final ByteBuffer b;
        int pos;

        Cursor(ByteBuffer b, int pos) {
            this.b = b;
            this.pos = pos;
        }

        int u8() {
            return b.get(pos++) & 0xFF;
        }

        int varint() {
            int v = 0, shift = 0;
            while (true) {
                int c = b.get(pos++) & 0xFF;
                v |= (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return v;
                shift += 7;
            }
        }

        long i64() {
            long v = b.getLong(pos);
            pos += 8;
            return v;
        }

        String string() {
            int n = varint();
            if (n == 0) return "";
            boolean ascii = true;
            for (int i = 0; i < n; i++) if (b.get(pos + i) < 0) { ascii = false; break; }
            String s;
            if (ascii) {
                char[] c = new char[n];
                for (int i = 0; i < n; i++) c[i] = (char) b.get(pos + i);
                s = new String(c);
            } else {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) a[i] = b.get(pos + i);
                s = new String(a, BlobWriter.UTF8);
            }
            pos += n;
            return s;
        }

        void skipString() {
            int n = varint();
            pos += n;
        }

        /** Long array stored elsewhere in the section: varint offset relative to base, then count + longs. */
        long[] longArrayAt(int base) {
            int at = varint();
            if (at == 0) return null;
            int p = base + at;
            int n = b.getInt(p);
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = b.getLong(p + 4 + i * 8);
            return a;
        }
    }
}
