package feed;

import feed.Request.Authorization;
import feed.Request.Method;

/** HTTP/1.1 framing. Only what the API uses is read: the request line, Authorization,
 * Content-Length, Connection and Transfer-Encoding. */
final class HttpParser {
    private HttpParser() {}

    static final int MAX_HEAD = 16384;
    static final int MAX_BODY = 1048576;

    private static final byte[] HTTP_1 = Ascii.bytes("HTTP/1.");
    private static final byte[] HTTP_1_0 = Ascii.bytes("HTTP/1.0");
    private static final byte[] GET = Ascii.bytes("GET");
    private static final byte[] POST = Ascii.bytes("POST");
    private static final byte[] AUTHORIZATION = Ascii.bytes("authorization");
    private static final byte[] CONTENT_LENGTH = Ascii.bytes("content-length");
    private static final byte[] TRANSFER_ENCODING = Ascii.bytes("transfer-encoding");
    private static final byte[] CONNECTION = Ascii.bytes("connection");
    private static final byte[] CLOSE = Ascii.bytes("close");
    private static final byte[] KEEP_ALIVE = Ascii.bytes("keep-alive");

    /** Reads the request at the start of input. Heads over 16 KB, bodies over 1 MB, chunked
     * bodies and malformed request lines are Malformed (out of the API's scope). */
    static ParseResult parse(Bytes input) {
        long b = input.address(), n = input.length(), end = input.end();
        long nl = newline(b, end);
        if (nl < 0) return n > MAX_HEAD ? ParseResult.Malformed.MALFORMED : new ParseResult.Incomplete(0);
        long lineEnd = nl > b && Memory.unsignedByteAt(nl - 1) == '\r' ? nl - 1 : nl;
        long sp1 = Memory.indexOf(b, lineEnd, ' ');
        if (sp1 <= b) return ParseResult.Malformed.MALFORMED;
        long sp2 = Memory.indexOf(sp1 + 1, lineEnd, ' ');
        if (sp2 <= sp1 + 1) return ParseResult.Malformed.MALFORMED;
        Bytes version = Bytes.between(sp2 + 1, lineEnd);
        if (!version.startsWith(HTTP_1)) return ParseResult.Malformed.MALFORMED;
        Bytes methodName = Bytes.between(b, sp1);
        Method method = methodName.is(GET) ? Method.GET : methodName.is(POST) ? Method.POST : Method.OTHER;
        boolean close = version.is(HTTP_1_0);
        long query = Memory.indexOf(sp1 + 1, sp2, '?');
        Bytes path = Bytes.between(sp1 + 1, query >= 0 ? query : sp2);
        Authorization authorization = Authorization.Absent.ABSENT;
        long contentLength = 0;
        long pos = nl + 1;
        for (;;) {
            long lf = newline(pos, end);
            if (lf < 0) return n > MAX_HEAD ? ParseResult.Malformed.MALFORMED : new ParseResult.Incomplete(0);
            long e = lf > pos && Memory.unsignedByteAt(lf - 1) == '\r' ? lf - 1 : lf;
            long next = lf + 1;
            if (e == pos) { pos = next; break; }
            long colon = Memory.indexOf(pos, e, ':');
            if (colon > pos) {
                Bytes name = Bytes.between(pos, colon);
                Bytes value = trim(colon + 1, e);
                if (isName(name, AUTHORIZATION)) {
                    authorization = new Authorization.Header(value);
                } else if (isName(name, CONTENT_LENGTH)) {
                    contentLength = contentLength(value);
                    if (contentLength < 0) return ParseResult.Malformed.MALFORMED;
                } else if (isName(name, TRANSFER_ENCODING)) {
                    return ParseResult.Malformed.MALFORMED;  // chunked bodies are out of scope
                } else if (isName(name, CONNECTION)) {
                    if (isName(value, CLOSE)) close = true;
                    else if (isName(value, KEEP_ALIVE)) close = false;
                }
            }
            pos = next;
            if (pos - b > MAX_HEAD) return ParseResult.Malformed.MALFORMED;
        }
        long length = pos - b + contentLength;
        if (n < length) return new ParseResult.Incomplete(length);
        return new ParseResult.Complete(new Request(method, path, authorization, new Bytes(pos, (int) contentLength), close), length);
    }

    /** Content-Length's value (at most nine digits and 1 MB), or -1. */
    private static long contentLength(Bytes value) {
        if (value.length() == 0 || value.length() > 9) return -1;
        long v = 0;
        for (long i = value.address(); i < value.end(); i++) {
            int c = Memory.unsignedByteAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v > MAX_BODY ? -1 : v;
    }

    /** The first '\n' in [from, to), or -1; eight bytes at a time. */
    static long newline(long from, long to) {
        long p = from;
        while (p + 8 <= to) {
            long x = Memory.wordAt(p) ^ 0x0a0a0a0a0a0a0a0aL;
            long found = (x - JsonEscape.ONES) & ~x & JsonEscape.HIGHS;
            if (found != 0) return p + (Long.numberOfTrailingZeros(found) >>> 3);
            p += 8;
        }
        for (; p < to; p++) if (Memory.unsignedByteAt(p) == '\n') return p;
        return -1;
    }

    /** The bytes from s to e without the spaces and tabs around them. */
    private static Bytes trim(long s, long e) {
        while (s < e && isBlank(Memory.unsignedByteAt(s))) s++;
        while (e > s && isBlank(Memory.unsignedByteAt(e - 1))) e--;
        return Bytes.between(s, e);
    }

    private static boolean isBlank(int c) { return c == ' ' || c == '\t'; }

    /** ASCII case-insensitive comparison with a lower-case name. */
    private static boolean isName(Bytes text, byte[] lower) {
        if (text.length() != lower.length) return false;
        for (int k = 0; k < lower.length; k++) {
            int c = Memory.unsignedByteAt(text.address() + k);
            if (c >= 'A' && c <= 'Z') c += 32;
            if (c != lower[k]) return false;
        }
        return true;
    }
}
