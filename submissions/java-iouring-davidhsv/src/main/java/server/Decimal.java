package server;

/** Decimal integers in native memory. */
public final class Decimal {
    private Decimal() {}

    /** Writes value in decimal at address and returns the number of bytes. */
    public static int write(long address, long value) {
        if (value < 0) {
            Memory.putByte(address, '-');
            if (value == Long.MIN_VALUE) {
                Memory.copy(MIN_MAGNITUDE, address + 1);
                return 1 + MIN_MAGNITUDE.length;
            }
            return 1 + write(address + 1, -value);
        }
        int n = width(value);
        long at = address + n;
        do {
            at--;
            Memory.putByte(at, (int) ('0' + value % 10));
            value /= 10;
        } while (value != 0);
        return n;
    }

    private static final byte[] MIN_MAGNITUDE = Ascii.bytes("9223372036854775808");

    /** How many digits a non-negative value has. */
    static int width(long value) {
        int n = 1;
        while (value >= 10) { value /= 10; n++; }
        return n;
    }

    /** A positive decimal integer with no sign, spaces or leading zeros that fits a long (at most
     * 18 digits), or 0 for anything else. */
    public static long parsePositive(Bytes text) {
        long s = text.address(), n = text.length();
        if (n == 0 || n > 18 || Memory.unsignedByteAt(s) == '0') return 0;
        long v = 0;
        for (long p = s; p < s + n; p++) {
            int c = Memory.unsignedByteAt(p);
            if (c < '0' || c > '9') return 0;
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
