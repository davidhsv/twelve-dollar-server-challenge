package server;

/** Text the way the reference implementation's JavaScript sees it: String.prototype.trim and a
 * length in code points (the unit of the schema's CHECK (length(body) …)). Input is valid
 * UTF-8. */
public final class JavaScriptText {
    private JavaScriptText() {}

    /** WhiteSpace and LineTerminator, the code points trim() removes. */
    static boolean isSpace(int cp) {
        if (cp <= 0x20) return cp == 0x20 || (cp >= 9 && cp <= 13);
        if (cp < 0xa0) return false;
        return cp == 0xa0 || cp == 0x1680 || (cp >= 0x2000 && cp <= 0x200a) || cp == 0x2028 || cp == 0x2029
                || cp == 0x202f || cp == 0x205f || cp == 0x3000 || cp == 0xfeff;
    }

    /** The code point of the UTF-8 sequence at i (its length follows from its first byte). */
    static int codePointAt(long i) {
        int c = Memory.unsignedByteAt(i);
        if (c < 0x80) return c;
        if (c < 0xe0) return ((c & 0x1f) << 6) | (Memory.unsignedByteAt(i + 1) & 0x3f);
        if (c < 0xf0) return ((c & 0x0f) << 12) | ((Memory.unsignedByteAt(i + 1) & 0x3f) << 6) | (Memory.unsignedByteAt(i + 2) & 0x3f);
        return ((c & 0x07) << 18) | ((Memory.unsignedByteAt(i + 1) & 0x3f) << 12) | ((Memory.unsignedByteAt(i + 2) & 0x3f) << 6)
                | (Memory.unsignedByteAt(i + 3) & 0x3f);
    }

    private static int sequenceLength(int firstByte) {
        return firstByte < 0x80 ? 1 : firstByte < 0xe0 ? 2 : firstByte < 0xf0 ? 3 : 4;
    }

    public static Bytes trim(Bytes text) {
        long i = text.address(), j = text.end();
        while (i < j && isSpace(codePointAt(i))) i += sequenceLength(Memory.unsignedByteAt(i));
        while (j > i) {
            long k = j - 1;
            while (k > i && (Memory.unsignedByteAt(k) & 0xc0) == 0x80) k--;
            if (!isSpace(codePointAt(k))) break;
            j = k;
        }
        return Bytes.between(i, j);
    }

    public static long codePoints(Bytes text) {
        long n = 0;
        for (long p = text.address(); p < text.end(); p++) if ((Memory.unsignedByteAt(p) & 0xc0) != 0x80) n++;
        return n;
    }
}
