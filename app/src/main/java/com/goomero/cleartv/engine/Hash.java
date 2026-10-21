package com.goomero.cleartv.engine;

/** 64-bit FNV-1a over UTF-16 code units. */
public final class Hash {
    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private Hash() {}

    public static long of(CharSequence s) {
        return of(s, 0, s.length());
    }

    public static long of(CharSequence s, int start, int end) {
        long h = OFFSET;
        for (int i = start; i < end; i++) {
            h ^= s.charAt(i);
            h *= PRIME;
        }
        return h;
    }
}
