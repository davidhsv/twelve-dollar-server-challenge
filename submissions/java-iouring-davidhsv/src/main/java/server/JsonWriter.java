package server;

/** JSON out: escaped strings, appended to a native buffer. */
public final class JsonWriter {
    private JsonWriter() {}

    private static final byte[] HEX = Ascii.bytes("0123456789abcdef");

    /** Appends text as the inside of a JSON string. */
    public static void string(NativeBuffer out, Bytes text) {
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
}
