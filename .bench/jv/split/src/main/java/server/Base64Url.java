package server;

import java.util.Arrays;

/** Unpadded base64url, as JWTs use it. */
final class Base64Url {
    private Base64Url() {}

    private static final byte[] VALUE = new byte[256];  // a character's six bits, 0xff if not in the alphabet
    static {
        Arrays.fill(VALUE, (byte) 0xff);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i = 0; i < 64; i++) VALUE[alphabet.charAt(i)] = (byte) i;
    }

    /** Decodes text, appending to out; false if it is not base64url. Only the canonical
     * spelling is accepted (the unused bits of the last character zero), so a signature has one
     * spelling that verifies. */
    static boolean decode(Bytes text, NativeBuffer out) {
        long s = text.address(), n = text.length();
        long tail = n % 4;
        if (tail == 1) return false;
        long whole = n - tail;
        out.reserve(n / 4 * 3 + 3);
        long o = out.address + out.length;
        long i = 0;
        while (i < whole) {
            int a = value(s + i), b = value(s + i + 1), c = value(s + i + 2), d = value(s + i + 3);
            if (((a | b | c | d) & 0xc0) != 0) return false;
            int v = (a << 18) | (b << 12) | (c << 6) | d;
            Memory.putByte(o, v >> 16);
            Memory.putByte(o + 1, v >> 8);
            Memory.putByte(o + 2, v);
            o += 3;
            i += 4;
        }
        if (tail != 0) {
            int a = value(s + i), b = value(s + i + 1);
            int c = tail == 3 ? value(s + i + 2) : 0;
            if (((a | b | c) & 0xc0) != 0) return false;
            int v = (a << 18) | (b << 12) | (c << 6);
            if ((tail == 2 ? v & 0xffff : v & 0xff) != 0) return false;
            Memory.putByte(o++, v >> 16);
            if (tail == 3) Memory.putByte(o++, v >> 8);
        }
        out.length = o - out.address;
        return true;
    }

    // signed: 0xff (not in the alphabet) becomes -1, which the callers' 0xc0 test catches
    private static int value(long address) { return VALUE[Memory.unsignedByteAt(address)]; }
}
