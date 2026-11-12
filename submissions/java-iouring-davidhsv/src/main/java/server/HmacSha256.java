package server;

import java.lang.foreign.Arena;

/** HMAC-SHA-256 with one key. What is computed once is the key schedule (the SHA-256 states after
 * the key's two padded blocks), not any result: every MAC is computed afresh. The working state is
 * preallocated and reused, as a hash's state is. */
public final class HmacSha256 {
    private HmacSha256() {}

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    };
    private static final int[] INIT = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };

    private static final int[] inner = new int[8];  // the key schedule
    private static final int[] outer = new int[8];
    private static final int[] h = new int[8];      // the hash being computed
    private static final int[] w = new int[64];     // its message schedule
    // Native scratch: the last one or two blocks of a hash, and the inner digest.
    private static final long LAST, DIGEST;
    static {
        long at = Arena.global().allocate(192, 16).address();
        LAST = at;
        DIGEST = at + 128;
    }

    /** One SHA-256 compression of the 64 bytes at a. */
    private static void block(int[] s, long a) {
        for (int i = 0; i < 16; i++) w[i] = Memory.bigEndianIntAt(a + i * 4L);
        for (int i = 16; i < 64; i++) {
            int x = w[i - 15], y = w[i - 2];
            int s0 = Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3);
            int s1 = Integer.rotateRight(y, 17) ^ Integer.rotateRight(y, 19) ^ (y >>> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a0 = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], hh = s[7];
        for (int i = 0; i < 64; i++) {
            int t1 = hh + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25))
                    + ((e & f) ^ (~e & g)) + K[i] + w[i];
            int t2 = (Integer.rotateRight(a0, 2) ^ Integer.rotateRight(a0, 13) ^ Integer.rotateRight(a0, 22))
                    + ((a0 & b) ^ (a0 & c) ^ (b & c));
            hh = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a0;
            a0 = t1 + t2;
        }
        s[0] += a0;
        s[1] += b;
        s[2] += c;
        s[3] += d;
        s[4] += e;
        s[5] += f;
        s[6] += g;
        s[7] += hh;
    }

    /** Finishes a hash whose state s has taken `before` bytes already, with the n bytes at msg as
     * the rest; the digest goes to the 32 bytes at out. */
    private static void finish(int[] s, long msg, long n, long before, long out) {
        long at = 0;
        while (at + 64 <= n) { block(s, msg + at); at += 64; }
        for (int k = 0; k < 128; k += 8) Memory.putWord(LAST + k, 0L);
        int rest = (int) (n - at);
        Memory.copy(msg + at, LAST, rest);
        Memory.putByte(LAST + rest, 0x80);
        int total = rest < 56 ? 64 : 128;
        long bits = (before + n) * 8;
        for (int i = 0; i < 8; i++) Memory.putByte(LAST + total - 1 - i, (int) (bits >>> (i * 8)));
        block(s, LAST);
        if (total == 128) block(s, LAST + 64);
        for (int i = 0; i < 8; i++) Memory.putBigEndianInt(out + i * 4L, s[i]);
    }

    /** Sets the key. */
    public static void key(byte[] secret) {
        long kb = Arena.global().allocate(64, 16).address();
        for (int i = 0; i < 64; i++) Memory.putByte(kb + i, 0);
        if (secret.length > 64) {
            long m = Arena.global().allocate(secret.length, 16).address();
            Memory.copy(secret, m);
            System.arraycopy(INIT, 0, h, 0, 8);
            finish(h, m, secret.length, 0, kb);
        } else {
            Memory.copy(secret, kb);
        }
        long pad = Arena.global().allocate(64, 16).address();
        for (int i = 0; i < 64; i++) Memory.putByte(pad + i, Memory.unsignedByteAt(kb + i) ^ 0x36);
        System.arraycopy(INIT, 0, inner, 0, 8);
        block(inner, pad);
        for (int i = 0; i < 64; i++) Memory.putByte(pad + i, Memory.unsignedByteAt(kb + i) ^ 0x5c);
        System.arraycopy(INIT, 0, outer, 0, 8);
        block(outer, pad);
    }

    /** The MAC of message, written to the 32 bytes at out. */
    static void mac(Bytes message, long out) {
        System.arraycopy(inner, 0, h, 0, 8);
        finish(h, message.address(), message.length(), 64, DIGEST);
        System.arraycopy(outer, 0, h, 0, 8);
        finish(h, DIGEST, 32, 64, out);
    }
}
