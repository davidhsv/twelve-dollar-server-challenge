package feed;

/** SQLite's record format (https://sqlite.org/fileformat.html#record_format): a header of varints
 * (its own length, then one serial type per column), then the column values in order. */
final class SqliteRecord {
    private SqliteRecord() {}

    /** One column of a record. */
    sealed interface Field {
        /** Its serial type and its bytes (a view into the record). */
        record Value(long serialType, Bytes bytes) implements Field {}
        /** The record does not have that column, or its header is malformed. */
        enum Corrupt implements Field { CORRUPT }
    }

    // serial types 0-11: NULL, 1-, 2-, 3-, 4-, 6-, 8-byte integers, a float, the integers 0 and 1,
    // two reserved
    private static final int[] FIXED_SIZES = {0, 1, 2, 3, 4, 6, 8, 8, 0, 0, 0, 0};

    static long size(long serialType) {
        return serialType >= 12 ? (serialType - 12) / 2 : serialType >= 0 ? FIXED_SIZES[(int) serialType] : 0;
    }

    /** Text (odd serial types from 13). */
    static boolean isText(long serialType) { return serialType >= 13 && (serialType & 1) == 1; }

    /** An integer (serial types 1-6, 8 and 9). */
    static boolean isInteger(long serialType) { return (serialType >= 1 && serialType <= 6) || serialType == 8 || serialType == 9; }

    /** The value of an integer column (isInteger of its serial type). */
    static long integer(Field.Value value) {
        long t = value.serialType();
        if (t == 8) return 0;
        if (t == 9) return 1;
        long s = value.bytes().address(), n = value.bytes().length();
        long v = Memory.byteAt(s);
        for (long k = 1; k < n; k++) v = (v << 8) | Memory.unsignedByteAt(s + k);
        return v;
    }

    /** Column k of the record. */
    static Field field(Bytes record, int k) {
        // Both header walks below find the column as numbers, and the Value is made here only, so
        // that when this is inlined C2 can replace it (and its Bytes) by those numbers.
        long offset = locate(record, k);
        return offset < 0 ? Field.Corrupt.CORRUPT
                : new Field.Value(locatedType, new Bytes(record.address() + offset, (int) size(locatedType)));
    }

    // The serial type of the column locate() found last (a second return value without an object).
    private static long locatedType;

    /** Where column k starts in the record, its serial type in locatedType; -1 if the record has
     * no such column or its header is malformed. Records of this schema have a one-byte header
     * length and one- or two-byte serial types; any other header is decoded as varints. */
    private static long locate(Bytes record, int k) {
        long r = record.address(), length = record.length();
        if (length < 1) return -1;
        int first = Memory.unsignedByteAt(r);
        if (first >= 0x80) return locateInAnyRecord(r, length, k);
        long headerSize = first;
        if (headerSize > length) return -1;
        long h = 1, offset = headerSize;
        for (int i = 0; ; i++) {
            if (h >= headerSize) return -1;
            long t = Memory.unsignedByteAt(r + h++);
            if (t >= 0x80) {
                if (h >= headerSize) return -1;
                long low = Memory.unsignedByteAt(r + h++);
                if (low >= 0x80) return locateInAnyRecord(r, length, k);
                t = ((t & 0x7f) << 7) | low;
            }
            long size = size(t);
            if (offset + size > length) return -1;
            if (i == k) {
                locatedType = t;
                return offset;
            }
            offset += size;
        }
    }

    private static long locateInAnyRecord(long r, long length, int k) {
        long h = varint(r, r + length);
        long headerSize = varintValue;
        if (h < 0 || headerSize > length) return -1;
        long offset = headerSize;
        for (int i = 0; ; i++) {
            if (h >= r + headerSize) return -1;
            long next = varint(h, r + headerSize);
            if (next < 0) return -1;
            long t = varintValue;
            h = next;
            long size = size(t);
            if (offset + size > length) return -1;
            if (i == k) {
                locatedType = t;
                return offset;
            }
            offset += size;
        }
    }

    // The value of the varint varint() read last (a second return value without an object).
    private static long varintValue;

    /** Reads the varint at i (before end): the position after it, or -1. */
    private static long varint(long i, long end) {
        if (i < end && Memory.unsignedByteAt(i) < 0x80) { varintValue = Memory.unsignedByteAt(i); return i + 1; }
        long v = 0;
        for (int k = 0; k < 9; k++) {
            if (i + k >= end) return -1;
            int c = Memory.unsignedByteAt(i + k);
            if (k == 8) { varintValue = (v << 8) | c; return i + 9; }
            v = (v << 7) | (c & 0x7f);
            if (c < 0x80) { varintValue = v; return i + k + 1; }
        }
        return -1;
    }
}
