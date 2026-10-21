package com.goomero.cleartv.ui;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder (byte mode, error correction level M, versions 1 to 10, so up to 213 bytes): enough for
 * sign-in links and the phone page address, without a library. Follows ISO/IEC 18004 (the structure of Project
 * Nayuki's reference encoder). {@link #encode} returns the module matrix, true = dark.
 */
public final class Qr {
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_LEVEL_M = 0; // format bits for level M

    private final int size;
    private final boolean[][] mod;
    private final boolean[][] fn;

    private Qr(int ver) {
        size = ver * 4 + 17;
        mod = new boolean[size][size];
        fn = new boolean[size][size];
    }

    /** The QR matrix for {@code text}; throws when it is longer than version 10 holds. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int ver = 1;
        for (; ver <= 10; ver++) {
            int lenBits = ver < 10 ? 8 : 16;
            if (4 + lenBits + data.length * 8 <= dataCodewords(ver) * 8) break;
        }
        if (ver > 10) throw new IllegalArgumentException("text too long for a QR code: " + data.length);
        int lenBits = ver < 10 ? 8 : 16;
        int cap = dataCodewords(ver) * 8;
        Bits bb = new Bits(cap);
        bb.put(4, 4);
        bb.put(data.length, lenBits);
        for (byte b : data) bb.put(b & 0xFF, 8);
        bb.put(0, Math.min(4, cap - bb.n));
        bb.put(0, (8 - bb.n % 8) % 8);
        for (int pad = 0xEC; bb.n < cap; pad ^= 0xEC ^ 0x11) bb.put(pad, 8);
        byte[] codewords = new byte[cap / 8];
        for (int i = 0; i < bb.n; i++) if (bb.get(i)) codewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));

        Qr q = new Qr(ver);
        q.drawFunctionPatterns(ver);
        q.drawCodewords(addEcc(codewords, ver));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            q.applyMask(m);
            q.drawFormat(m);
            long p = q.penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
            q.applyMask(m); // undo (XOR)
        }
        q.applyMask(best);
        q.drawFormat(best);
        return q.mod;
    }

    // ------------------------------------------------------------------------------------- layout

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            setFn(6, i, i % 2 == 0);
            setFn(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] align = alignmentPositions(ver);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFn(align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormat(0); // reserves the format areas; redrawn per mask
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            long bits = (long) ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                setFn(a, c, b);
                setFn(c, a, b);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || xx >= size || yy < 0 || yy >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                setFn(xx, yy, d != 2 && d != 4);
            }
        }
    }

    private void drawFormat(int mask) {
        int data = FORMAT_LEVEL_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFn(8, i, bit(bits, i));
        setFn(8, 7, bit(bits, 6));
        setFn(8, 8, bit(bits, 7));
        setFn(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFn(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(8, size - 15 + i, bit(bits, i));
        setFn(8, size - 8, true);
    }

    private static int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (fn[y][x]) continue;
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv) mod[y][x] = !mod[y][x];
            }
        }
    }

    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                boolean color = false;
                int run = 0;
                int[] hist = new int[7];
                for (int b = 0; b < size; b++) {
                    boolean v = pass == 0 ? mod[a][b] : mod[b][a];
                    if (v == color) {
                        run++;
                        if (run == 5) p += 3;
                        else if (run > 5) p++;
                    } else {
                        finderAdd(run, hist);
                        if (!color) p += finderCount(hist) * 40;
                        color = v;
                        run = 1;
                    }
                }
                p += finderTerminate(color, run, hist) * 40;
            }
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = mod[y][x];
                if (c == mod[y][x + 1] && c == mod[y + 1][x] && c == mod[y + 1][x + 1]) p += 3;
            }
        int dark = 0;
        for (boolean[] row : mod) for (boolean v : row) if (v) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += k * 10L;
        return p;
    }

    private int finderCount(int[] h) {
        int n = h[1];
        boolean core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n;
        return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    }

    private int finderTerminate(boolean color, int run, int[] h) {
        if (color) {
            finderAdd(run, h);
            run = 0;
        }
        run += size;
        finderAdd(run, h);
        return finderCount(h);
    }

    private void finderAdd(int run, int[] h) {
        if (h[0] == 0) run += size;
        System.arraycopy(h, 0, h, 1, h.length - 1);
        h[0] = run;
    }

    // ----------------------------------------------------------------------------------- codewords

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    private static byte[] addEcc(byte[] data, int ver) {
        int blocks = BLOCKS[ver], ecc = ECC_PER_BLOCK[ver];
        int raw = rawModules(ver) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[][] out = new byte[blocks][];
        byte[] div = divisor(ecc);
        for (int i = 0, k = 0; i < blocks; i++) {
            int dlen = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] dat = new byte[dlen];
            System.arraycopy(data, k, dat, 0, dlen);
            k += dlen;
            byte[] r = remainder(dat, div);
            byte[] blk = new byte[shortLen + 1];
            System.arraycopy(dat, 0, blk, 0, dlen);
            System.arraycopy(r, 0, blk, blk.length - ecc, ecc);
            out[i] = blk;
        }
        byte[] res = new byte[raw];
        int n = 0;
        for (int i = 0; i < out[0].length; i++)
            for (int j = 0; j < blocks; j++)
                if (i != shortLen - ecc || j >= shortBlocks) res[n++] = out[j][i];
        return res;
    }

    private static byte[] divisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] remainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private void setFn(int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class Bits {
        final boolean[] b;
        int n;

        Bits(int cap) {
            b = new boolean[cap];
        }

        void put(int val, int len) {
            for (int i = len - 1; i >= 0; i--) b[n++] = ((val >>> i) & 1) != 0;
        }

        boolean get(int i) {
            return b[i];
        }
    }
}
