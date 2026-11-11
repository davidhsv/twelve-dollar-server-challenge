package feed;

/** JSON out: escaped strings and post objects, appended to a native buffer. */
final class JsonWriter {
    private JsonWriter() {}

    private static final byte[] HEX = Ascii.bytes("0123456789abcdef");

    /** Appends text as the inside of a JSON string. */
    static void string(NativeBuffer out, Bytes text) {
        if (JsonEscape.isPlain(text)) { out.append(text); return; }
        out.reserve(text.length() * 6L);
        long w = out.address + out.length;
        for (long p = text.address(); p < text.end(); p++) {
            int c = Memory.unsignedByteAt(p);
            switch (c) {
                case '"' -> { Memory.putByte(w++, '\\'); Memory.putByte(w++, '"'); }
                case '\\' -> { Memory.putByte(w++, '\\'); Memory.putByte(w++, '\\'); }
                case '\n' -> { Memory.putByte(w++, '\\'); Memory.putByte(w++, 'n'); }
                case '\r' -> { Memory.putByte(w++, '\\'); Memory.putByte(w++, 'r'); }
                case '\t' -> { Memory.putByte(w++, '\\'); Memory.putByte(w++, 't'); }
                case '\b' -> { Memory.putByte(w++, '\\'); Memory.putByte(w++, 'b'); }
                case '\f' -> { Memory.putByte(w++, '\\'); Memory.putByte(w++, 'f'); }
                default -> {
                    if (c >= 0x20) {
                        Memory.putByte(w++, c);
                    } else {
                        Memory.putByte(w++, '\\'); Memory.putByte(w++, 'u'); Memory.putByte(w++, '0'); Memory.putByte(w++, '0');
                        Memory.putByte(w++, HEX[c >> 4]);
                        Memory.putByte(w++, HEX[c & 15]);
                    }
                }
            }
        }
        out.length = w - out.address;
    }

    private static final String ID = "{\"id\":", BODY = ",\"body\":\"", CREATED = "\",\"created_at\":\"",
            AUTHOR = "\",\"author\":\"", LIKES = "\",\"like_count\":";
    private static final byte[] CREATED_BYTES = Ascii.bytes(CREATED);
    // The constants as words: written with whole 8-byte stores, the bytes a store writes past a
    // constant are overwritten by what follows it, and post() has reserved room for them.
    private static final long ID_0 = Ascii.word(ID, 0);
    private static final long BODY_0 = Ascii.word(BODY, 0), BODY_1 = Ascii.word(BODY, 8);
    private static final long CREATED_0 = Ascii.word(CREATED, 0), CREATED_1 = Ascii.word(CREATED, 8);
    private static final long AUTHOR_0 = Ascii.word(AUTHOR, 0), AUTHOR_1 = Ascii.word(AUTHOR, 8);
    private static final long LIKES_0 = Ascii.word(LIKES, 0), LIKES_1 = Ascii.word(LIKES, 8);
    private static final int CONSTANTS = ID.length() + BODY.length() + CREATED.length() + AUTHOR.length() + LIKES.length() + 1;

    /** Appends one post object. Its strings are copied unescaped while being checked; only when a
     * check finds a byte to escape is that part written again, escaped. */
    static void post(NativeBuffer out, PostRow post) {
        Bytes body = post.body(), created = post.createdAt(), author = post.author();
        // the constants, two numbers, the three strings unescaped, and a word of slack
        out.reserve(CONSTANTS + 40 + body.length() + created.length() + author.length() + 8);
        long p = out.address + out.length;
        Memory.putWord(p, ID_0);
        p += ID.length();
        p += Decimal.write(p, post.id());
        Memory.putWord(p, BODY_0);
        Memory.putWord(p + 8, BODY_1);
        p += BODY.length();
        long mark = p;
        boolean escape = JsonEscape.copyAndCheck(body, p);
        p += body.length();
        Memory.putWord(p, CREATED_0);
        Memory.putWord(p + 8, CREATED_1);
        p += CREATED.length();
        escape |= JsonEscape.copyAndCheck(created, p);
        p += created.length();
        if (escape) {
            out.length = mark - out.address;
            string(out, body);
            out.append(CREATED_BYTES);
            string(out, created);
            out.reserve(AUTHOR.length() + author.length() + LIKES.length() + 20 + 1 + 8);
            p = out.address + out.length;
        }
        Memory.putWord(p, AUTHOR_0);
        Memory.putWord(p + 8, AUTHOR_1);
        p += AUTHOR.length();
        if (JsonEscape.copyAndCheck(author, p)) {
            out.length = p - out.address;
            string(out, author);
            out.reserve(LIKES.length() + 20 + 1 + 8);
            p = out.address + out.length;
        } else {
            p += author.length();
        }
        Memory.putWord(p, LIKES_0);
        Memory.putWord(p + 8, LIKES_1);
        p += LIKES.length();
        p += Decimal.write(p, post.likes());
        Memory.putByte(p++, '}');
        out.length = p - out.address;
    }
}
