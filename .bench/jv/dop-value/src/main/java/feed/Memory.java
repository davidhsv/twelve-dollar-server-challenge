package feed;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** Native memory, read and written at absolute addresses.
 *
 * Everything the server reads in place lives outside the Java heap: received bytes in the
 * kernel's buffers, SQLite's records on its pages, responses in the connections' output. One
 * segment spans all of it, so an access is a bounds check against a constant and a plain load or
 * store, and no segment object is made per access. An address is only as good as what owns the
 * memory: each caller says how long its addresses live. */
final class Memory {
    private Memory() {}

    private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);
    private static final ValueLayout.OfLong UNALIGNED_LONG = ValueLayout.JAVA_LONG_UNALIGNED;
    private static final ValueLayout.OfInt BIG_ENDIAN_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    static {
        // Words are assembled and compared in memory order assuming little-endian (x86-64, arm64).
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) throw new IllegalStateException("big-endian machine");
    }

    /** The segment over all of native memory, for APIs that take a segment and an offset. */
    static MemorySegment all() { return ALL; }

    static byte byteAt(long address) { return ALL.get(ValueLayout.JAVA_BYTE, address); }

    static int unsignedByteAt(long address) { return ALL.get(ValueLayout.JAVA_BYTE, address) & 0xff; }

    static void putByte(long address, int value) { ALL.set(ValueLayout.JAVA_BYTE, address, (byte) value); }

    static int intAt(long address) { return ALL.get(ValueLayout.JAVA_INT, address); }

    static long longAt(long address) { return ALL.get(ValueLayout.JAVA_LONG, address); }

    /** Eight bytes at any address, in memory order. */
    static long wordAt(long address) { return ALL.get(UNALIGNED_LONG, address); }

    static void putWord(long address, long value) { ALL.set(UNALIGNED_LONG, address, value); }

    static int bigEndianIntAt(long address) { return ALL.get(BIG_ENDIAN_INT, address); }

    static void putBigEndianInt(long address, int value) { ALL.set(BIG_ENDIAN_INT, address, value); }

    static void copy(long from, long to, long length) { MemorySegment.copy(ALL, from, ALL, to, length); }

    static void copy(byte[] from, long to) {
        MemorySegment.copy(from, 0, ALL, ValueLayout.JAVA_BYTE, to, from.length);
    }

    /** True when the length bytes at address are exactly those of bytes. */
    static boolean equals(long address, long length, byte[] bytes) {
        if (length != bytes.length) return false;
        for (int k = 0; k < bytes.length; k++) if (byteAt(address + k) != bytes[k]) return false;
        return true;
    }

    static boolean startsWith(long address, long length, byte[] prefix) {
        if (length < prefix.length) return false;
        for (int k = 0; k < prefix.length; k++) if (byteAt(address + k) != prefix[k]) return false;
        return true;
    }

    /** The first address in [from, to) holding the byte value, or -1. */
    static long indexOf(long from, long to, int value) {
        for (long p = from; p < to; p++) if (unsignedByteAt(p) == value) return p;
        return -1;
    }

    /** The length of the NUL-terminated string at address. */
    static int cStringLength(long address) {
        int n = 0;
        while (byteAt(address + n) != 0) n++;
        return n;
    }

    static byte[] toArray(long address, int length) {
        byte[] out = new byte[length];
        MemorySegment.copy(ALL, ValueLayout.JAVA_BYTE, address, out, 0, length);
        return out;
    }
}
