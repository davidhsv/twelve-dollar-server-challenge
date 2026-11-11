package feed;

/** JSON in: a validating reader over native memory. A document is checked whole (any nesting,
 * with an explicit stack, so no input can recurse), and the members of a top-level object that
 * the caller names are reported. Inside, positions are absolute addresses and -1 means "not valid
 * JSON". */
final class JsonReader {
    private JsonReader() {}

    // Set by string(): whether the string it accepted has escapes (the parser's one piece of
    // state besides its stack; a second return value without an object for it).
    private static boolean lastEscaped;
    private static final NativeBuffer keybuf = new NativeBuffer(256);
    private static final int MAX_DEPTH = 512;
    private static final byte[] open = new byte[MAX_DEPTH]; // 1 an object, 2 an array

    // Bytes that end a run of plain string content: the quote, the backslash, control characters
    // and everything outside ASCII.
    private static final boolean[] STOPS = new boolean[256];
    static {
        for (int i = 0; i < 32; i++) STOPS[i] = true;
        for (int i = 128; i < 256; i++) STOPS[i] = true;
        STOPS['"'] = true;
        STOPS['\\'] = true;
    }

    static long ws(long i, long end) {
        while (i < end) {
            int c = Memory.unsignedByteAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
            i++;
        }
        return i;
    }

    static int hex(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** The length of the well-formed UTF-8 sequence at i (a lead byte >= 0x80), or -1. */
    static int utf8Len(long i, long end) {
        int c = Memory.unsignedByteAt(i);
        if (c < 0xc2) return -1;
        if (c < 0xe0) {
            if (i + 1 >= end || (Memory.unsignedByteAt(i + 1) & 0xc0) != 0x80) return -1;
            return 2;
        }
        if (c < 0xf0) {
            if (i + 2 >= end || (Memory.unsignedByteAt(i + 1) & 0xc0) != 0x80 || (Memory.unsignedByteAt(i + 2) & 0xc0) != 0x80) return -1;
            if (c == 0xe0 && Memory.unsignedByteAt(i + 1) < 0xa0) return -1;   // overlong
            if (c == 0xed && Memory.unsignedByteAt(i + 1) >= 0xa0) return -1;  // a surrogate
            return 3;
        }
        if (c > 0xf4 || i + 3 >= end) return -1;
        if ((Memory.unsignedByteAt(i + 1) & 0xc0) != 0x80 || (Memory.unsignedByteAt(i + 2) & 0xc0) != 0x80 || (Memory.unsignedByteAt(i + 3) & 0xc0) != 0x80) return -1;
        if (c == 0xf0 && Memory.unsignedByteAt(i + 1) < 0x90) return -1;       // overlong
        if (c == 0xf4 && Memory.unsignedByteAt(i + 1) >= 0x90) return -1;      // past U+10FFFF
        return 4;
    }

    /** The first byte at or after i (before end) that STOPS a run of plain content, or -1. Eight
     * bytes at a time: the lowest byte a word flags is always a true one. */
    private static long stop(long i, long end) {
        while (i + 8 <= end) {
            long x = Memory.wordAt(i);
            long m = JsonEscape.special(x) | (x & JsonEscape.HIGHS);
            if (m != 0) return i + (Long.numberOfTrailingZeros(m) >>> 3);
            i += 8;
        }
        while (i < end) {
            if (STOPS[Memory.unsignedByteAt(i)]) return i;
            i++;
        }
        return -1;
    }

    /** The string whose opening quote is at `at`: the position after its closing quote (-1 if it
     * is not a valid string); whether it has escapes is left in lastEscaped. */
    static long string(long at, long end) {
        long i = at + 1;
        boolean escaped = false;
        for (;;) {
            i = stop(i, end);
            if (i < 0) return -1;
            int c = Memory.unsignedByteAt(i);
            if (c == '"') { lastEscaped = escaped; return i + 1; }
            if (c < 0x20) return -1;
            if (c == '\\') {
                escaped = true;
                if (i + 1 >= end) return -1;
                int d = Memory.unsignedByteAt(i + 1);
                if (d == 'u') {
                    if (i + 5 >= end) return -1;
                    for (int h = 2; h < 6; h++) if (hex(Memory.unsignedByteAt(i + h)) < 0) return -1;
                    i += 6;
                } else if (d == '"' || d == '\\' || d == '/' || d == 'b' || d == 'f' || d == 'n' || d == 'r' || d == 't') {
                    i += 2;
                } else {
                    return -1;
                }
            } else {
                int n = utf8Len(i, end);
                if (n < 0) return -1;
                i += n;
            }
        }
    }

    private static boolean digit(long i, long end) {
        if (i >= end) return false;
        int c = Memory.unsignedByteAt(i);
        return c >= '0' && c <= '9';
    }

    /** The number at `at`: the position after it, or -1. */
    static long number(long at, long end) {
        long i = at;
        if (i < end && Memory.unsignedByteAt(i) == '-') i++;
        if (i >= end) return -1;
        int c = Memory.unsignedByteAt(i);
        if (c == '0') i++;
        else if (c >= '1' && c <= '9') { while (digit(i, end)) i++; }
        else return -1;
        if (i < end && Memory.unsignedByteAt(i) == '.') {
            i++;
            if (!digit(i, end)) return -1;
            while (digit(i, end)) i++;
        }
        if (i < end && (Memory.unsignedByteAt(i) == 'e' || Memory.unsignedByteAt(i) == 'E')) {
            i++;
            if (i < end && (Memory.unsignedByteAt(i) == '+' || Memory.unsignedByteAt(i) == '-')) i++;
            if (!digit(i, end)) return -1;
            while (digit(i, end)) i++;
        }
        return i;
    }

    /** A member name at `at`, then a colon: the position after the colon, or -1. */
    private static long name(long at, long end) {
        if (at >= end || Memory.unsignedByteAt(at) != '"') return -1;
        long e = string(at, end);
        if (e < 0) return -1;
        long i = ws(e, end);
        if (i >= end || Memory.unsignedByteAt(i) != ':') return -1;
        return i + 1;
    }

    private static boolean word(long i, long end, String w) {
        if (end - i < w.length()) return false;
        for (int k = 0; k < w.length(); k++) if (Memory.unsignedByteAt(i + k) != w.charAt(k)) return false;
        return true;
    }

    /** The value at `at` (after blanks): the position after it, or -1 if it is not valid JSON. */
    static long value(long at, long end) {
        int depth = 0;
        boolean want = true;
        long i = at;
        for (;;) {
            i = ws(i, end);
            if (i >= end) return -1;
            int c = Memory.unsignedByteAt(i);
            if (want) {
                if (c == '{' || c == '[') {
                    if (depth == MAX_DEPTH) return -1;
                    open[depth] = (byte) (c == '{' ? 1 : 2);
                    depth++;
                    i = ws(i + 1, end);
                    if (i >= end) return -1;
                    if (Memory.unsignedByteAt(i) == (c == '{' ? '}' : ']')) {
                        i++;
                        depth--;
                        want = false;
                    } else if (c == '{') {
                        i = name(i, end);
                        if (i < 0) return -1;
                    }
                } else {
                    if (c == '"') i = string(i, end);
                    else if (c == '-' || (c >= '0' && c <= '9')) i = number(i, end);
                    else if (word(i, end, "true") || word(i, end, "null")) i += 4;
                    else if (word(i, end, "false")) i += 5;
                    else return -1;
                    if (i < 0) return -1;
                    want = false;
                }
            } else {
                int top = open[depth - 1];
                if (c == ',') {
                    i++;
                    if (top == 1) {
                        i = name(ws(i, end), end);
                        if (i < 0) return -1;
                    }
                    want = true;
                } else if ((top == 1 && c == '}') || (top == 2 && c == ']')) {
                    i++;
                    depth--;
                } else {
                    return -1;
                }
            }
            if (!want && depth == 0) return i;
        }
    }

    private static boolean nameIs(long s, long e, boolean escaped, byte[] key) {
        if (!escaped) return Memory.equals(s, e - s, key);
        keybuf.clear();
        unescape(s, e, keybuf);
        return keybuf.contentEquals(key);
    }

    /** A member of the top-level object that the caller asked for. */
    sealed interface Member {
        /** The object has no member of that name. */
        enum Absent implements Member { ABSENT }
        /** A string: its inside, still escaped when `escaped`. */
        record Text(Bytes raw, boolean escaped) implements Member {}
        /** A number: its text. */
        record Number(Bytes text) implements Member {}
        /** Anything else (an object, an array, true, false, null). */
        enum Other implements Member { OTHER }
    }

    /** What a whole document is. */
    sealed interface Document {
        /** Not valid JSON. */
        enum Invalid implements Document { INVALID }
        /** Valid JSON, but not an object. */
        enum NotAnObject implements Document { NOT_AN_OBJECT }
        /** An object, with the members asked for (the last of duplicate names wins, as in
         * JSON.parse; member names are compared decoded). */
        record Object(Member first, Member second, Member third) implements Document {}
    }

    /** Checks the whole document and reports the members of its top-level object named k0, k1
     * and k2 (null names are not looked for). */
    static Document read(Bytes document, byte[] k0, byte[] k1, byte[] k2) {
        long b = document.address(), end = document.end();
        Member m0 = Member.Absent.ABSENT, m1 = Member.Absent.ABSENT, m2 = Member.Absent.ABSENT;
        long i = ws(b, end);
        if (i >= end) return Document.Invalid.INVALID;
        if (Memory.unsignedByteAt(i) != '{') {
            i = value(i, end);
            if (i < 0 || ws(i, end) != end) return Document.Invalid.INVALID;
            return Document.NotAnObject.NOT_AN_OBJECT;
        }
        i = ws(i + 1, end);
        if (i < end && Memory.unsignedByteAt(i) == '}') {
            return ws(i + 1, end) == end ? new Document.Object(m0, m1, m2) : Document.Invalid.INVALID;
        }
        for (;;) {
            if (i >= end || Memory.unsignedByteAt(i) != '"') return Document.Invalid.INVALID;
            long ne = string(i, end);
            if (ne < 0) return Document.Invalid.INVALID;
            boolean nameEscaped = lastEscaped;
            long n0 = i + 1;
            long n1 = ne - 1;
            i = ws(ne, end);
            if (i >= end || Memory.unsignedByteAt(i) != ':') return Document.Invalid.INVALID;
            long v0 = ws(i + 1, end);
            if (v0 >= end) return Document.Invalid.INVALID;
            Member m;
            int c = Memory.unsignedByteAt(v0);
            if (c == '"') {
                long se = string(v0, end);
                if (se < 0) return Document.Invalid.INVALID;
                m = new Member.Text(Bytes.between(v0 + 1, se - 1), lastEscaped);
                i = se;
            } else {
                i = value(v0, end);
                if (i < 0) return Document.Invalid.INVALID;
                m = c == '-' || (c >= '0' && c <= '9') ? new Member.Number(Bytes.between(v0, i)) : Member.Other.OTHER;
            }
            if (k0 != null && nameIs(n0, n1, nameEscaped, k0)) m0 = m;
            else if (k1 != null && nameIs(n0, n1, nameEscaped, k1)) m1 = m;
            else if (k2 != null && nameIs(n0, n1, nameEscaped, k2)) m2 = m;
            i = ws(i, end);
            if (i >= end) return Document.Invalid.INVALID;
            if (Memory.unsignedByteAt(i) == '}') {
                return ws(i + 1, end) == end ? new Document.Object(m0, m1, m2) : Document.Invalid.INVALID;
            }
            if (Memory.unsignedByteAt(i) != ',') return Document.Invalid.INVALID;
            i = ws(i + 1, end);
        }
    }

    private static int hex4(long i) {
        return (hex(Memory.unsignedByteAt(i)) << 12) | (hex(Memory.unsignedByteAt(i + 1)) << 8) | (hex(Memory.unsignedByteAt(i + 2)) << 4) | hex(Memory.unsignedByteAt(i + 3));
    }

    /** Decodes the inside of a string that string() accepted, appending to out. A surrogate
     * escape without its pair becomes U+FFFD, as it does when JavaScript writes the string out as
     * UTF-8. */
    static void unescape(long s, long e, NativeBuffer out) {
        long i = s;
        while (i < e) {
            int c = Memory.unsignedByteAt(i);
            if (c != '\\') { out.append(c); i++; continue; }
            int d = Memory.unsignedByteAt(i + 1);
            i += 2;
            if (d == 'u') {
                int cp = hex4(i);
                i += 4;
                if (cp >= 0xd800 && cp < 0xdc00 && i + 5 < e && Memory.unsignedByteAt(i) == '\\' && Memory.unsignedByteAt(i + 1) == 'u') {
                    int lo = hex4(i + 2);
                    if (lo >= 0xdc00 && lo < 0xe000) {
                        cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
                        i += 6;
                    }
                }
                if (cp >= 0xd800 && cp < 0xe000) cp = 0xfffd;
                utf8(out, cp);
            } else {
                out.append(switch (d) { case 'b' -> 8; case 'f' -> 12; case 'n' -> 10; case 'r' -> 13; case 't' -> 9; default -> d; });
            }
        }
    }

    static void utf8(NativeBuffer out, int c) {
        if (c < 0 || c > 0x10ffff) c = 0xfffd;
        if (c < 0x80) {
            out.append(c);
        } else if (c < 0x800) {
            out.append(0xc0 | (c >> 6));
            out.append(0x80 | (c & 0x3f));
        } else if (c < 0x10000) {
            out.append(0xe0 | (c >> 12));
            out.append(0x80 | ((c >> 6) & 0x3f));
            out.append(0x80 | (c & 0x3f));
        } else {
            out.append(0xf0 | (c >> 18));
            out.append(0x80 | ((c >> 12) & 0x3f));
            out.append(0x80 | ((c >> 6) & 0x3f));
            out.append(0x80 | (c & 0x3f));
        }
    }

    /** Appends the decoded text of a string member. */
    static void decode(Member.Text text, NativeBuffer out) {
        if (text.escaped()) unescape(text.raw().address(), text.raw().end(), out);
        else out.append(text.raw());
    }

    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17,
        1e18, 1e19, 1e20, 1e21, 1e22,
    };

    /** A number member as the nearest double (NaN if it has no digits). An integer, or a short
     * decimal fraction, the shapes a token's exp takes, is exact in one rounding; anything else
     * goes to Double.parseDouble, which also rounds correctly. */
    static double toDouble(Member.Number number) {
        long s = number.text().address(), e = number.text().end();
        long i = s;
        boolean negative = false;
        if (i < e && Memory.unsignedByteAt(i) == '-') { negative = true; i++; }
        long start = i, mantissa = 0;
        while (i < e && isDigit(Memory.unsignedByteAt(i))) { mantissa = mantissa * 10 + (Memory.unsignedByteAt(i) - '0'); i++; }
        long digits = i - start, fraction = 0;
        if (i < e && Memory.unsignedByteAt(i) == '.') {
            i++;
            long f0 = i;
            while (i < e && isDigit(Memory.unsignedByteAt(i))) { mantissa = mantissa * 10 + (Memory.unsignedByteAt(i) - '0'); i++; }
            fraction = i - f0;
            digits += fraction;
        }
        if (digits == 0) return Double.NaN;
        if (digits <= 19 && i == e && mantissa >>> 63 == 0 && (fraction == 0 || mantissa <= 1L << 53)) {
            double v = (double) mantissa;
            if (fraction > 0) v /= POW10[(int) fraction];
            return negative ? -v : v;
        }
        try {
            return Double.parseDouble(new String(Memory.toArray(s, (int) (e - s)), java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NumberFormatException x) {
            return Double.NaN;
        }
    }

    private static boolean isDigit(int c) { return c >= '0' && c <= '9'; }
}
