package server;

/** Finding the bytes a JSON string must escape (control characters, the quote, the backslash),
 * a word or a vector at a time. */
public final class JsonEscape {
    private JsonEscape() {}

    static final long ONES = 0x0101010101010101L;
    static final long HIGHS = 0x8080808080808080L;

    // Strings of a vector's width and more are copied and checked a vector at a time (1-3 % less
    // CPU per request than eight bytes at a time, measured on x86-64); -Dserver.scalar=true (a test
    // knob) keeps to eight bytes.
    private static final int VECTOR_MIN = Boolean.getBoolean("server.scalar") ? Integer.MAX_VALUE : VectorEscapeScan.width();

    /** Nonzero when one of the eight bytes of word needs escaping. The lowest flagged byte is
     * always a true one (a borrow only runs upwards). */
    static long special(long word) {
        long quote = word ^ (ONES * 0x22);
        long slash = word ^ (ONES * 0x5c);
        return (((word - ONES * 0x20) & ~word) | ((quote - ONES) & ~quote) | ((slash - ONES) & ~slash)) & HIGHS;
    }

    static boolean needsEscape(int c) { return c < 0x20 || c == '"' || c == '\\'; }

    /** True when no byte of text needs escaping. The last word overlaps the one before it
     * instead of being cut short. */
    static boolean isPlain(Bytes text) {
        long a = text.address(), n = text.length();
        if (n >= 8) {
            long found = 0;
            long end = a + n - 8;
            for (long p = a; p < end; p += 8) found |= special(Memory.wordAt(p));
            return (found | special(Memory.wordAt(end))) == 0;
        }
        for (long p = a; p < a + n; p++) if (needsEscape(Memory.unsignedByteAt(p))) return false;
        return true;
    }

    /** Copies text to `to` and says whether any of its bytes needs escaping, reading each byte
     * once for both. */
    public static boolean copyAndCheck(Bytes text, long to) {
        long from = text.address(), n = text.length();
        if (n >= VECTOR_MIN) return VectorEscapeScan.copyAndCheck(from, to, n);
        if (n >= 8) {
            long found = 0;
            long last = n - 8;
            for (long i = 0; i < last; i += 8) {
                long word = Memory.wordAt(from + i);
                Memory.putWord(to + i, word);
                found |= special(word);
            }
            long word = Memory.wordAt(from + last);
            Memory.putWord(to + last, word);
            return (found | special(word)) != 0;
        }
        boolean found = false;
        for (long i = 0; i < n; i++) {
            int c = Memory.unsignedByteAt(from + i);
            Memory.putByte(to + i, c);
            found |= needsEscape(c);
        }
        return found;
    }
}
