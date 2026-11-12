package server;

/** A run of bytes in native memory: a view, not a copy. It is valid only as long as the memory it
 * points into (a receive buffer until it goes back to the kernel, a record until its cursor
 * moves, a scratch buffer until its next use), which the code that makes one documents. */
public value record Bytes(long address, int length) {
    static final Bytes EMPTY = new Bytes(0, 0);

    public Bytes {
        if (length < 0) throw new IllegalArgumentException("negative length");
    }

    /** The bytes from start (inclusive) to end (exclusive). */
    public static Bytes between(long start, long end) { return new Bytes(start, (int) (end - start)); }

    public long end() { return address + length; }

    public boolean is(byte[] ascii) { return Memory.equals(address, length, ascii); }

    public boolean startsWith(byte[] ascii) { return Memory.startsWith(address, length, ascii); }
}
