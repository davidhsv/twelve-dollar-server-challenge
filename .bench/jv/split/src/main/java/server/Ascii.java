package server;

import java.nio.charset.StandardCharsets;

/** Constant byte strings. */
public final class Ascii {
    private Ascii() {}

    public static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** Up to eight bytes of s from index `from`, as a word in memory order (little-endian), for
     * writing a constant with whole-word stores. */
    public static long word(String s, int from) {
        long v = 0;
        for (int i = 0; i < 8 && from + i < s.length(); i++) v |= (long) (s.charAt(from + i) & 0xff) << (8 * i);
        return v;
    }
}
