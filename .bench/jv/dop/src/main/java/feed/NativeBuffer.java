package feed;

import java.lang.foreign.Arena;

/** A growable byte buffer in native memory, reused as storage only: whoever fills it clears it
 * first, and nothing in it outlives its next use. It starts larger than a request needs and
 * doubles when a write would not fit (its old memory is freed then, so an address into it is good
 * only until the next write). */
final class NativeBuffer {
    /** Where the bytes start. */
    long address;
    /** How many bytes are in use. */
    long length;
    private long capacity;
    private Arena arena;

    NativeBuffer(long capacity) {
        arena = Arena.ofConfined();
        address = arena.allocate(capacity, 16).address();
        this.capacity = capacity;
    }

    void clear() { length = 0; }

    /** Room for n more bytes. */
    void reserve(long n) { if (length + n > capacity) grow(length + n); }

    private void grow(long needed) {
        long c = capacity;
        while (c < needed) c *= 2;
        Arena larger = Arena.ofConfined();
        long moved = larger.allocate(c, 16).address();
        Memory.copy(address, moved, length);
        arena.close();
        arena = larger;
        address = moved;
        capacity = c;
    }

    void append(int b) {
        reserve(1);
        Memory.putByte(address + length, b);
        length++;
    }

    void append(byte[] bytes) {
        reserve(bytes.length);
        Memory.copy(bytes, address + length);
        length += bytes.length;
    }

    void append(long from, long n) {
        reserve(n);
        Memory.copy(from, address + length, n);
        length += n;
    }

    void append(Bytes bytes) { append(bytes.address(), bytes.length()); }

    /** Appends a non-negative decimal integer. */
    void appendDecimal(long value) {
        reserve(20);
        length += Decimal.write(address + length, value);
    }

    /** The bytes in use, as a view valid until the buffer is next written. */
    Bytes contents() { return new Bytes(address, (int) length); }

    /** The view from offset start to the end. */
    Bytes from(long start) { return new Bytes(address + start, (int) (length - start)); }

    boolean contentEquals(byte[] ascii) { return Memory.equals(address, length, ascii); }
}
