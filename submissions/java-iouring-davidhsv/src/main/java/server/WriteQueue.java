package server;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;

/** Writes waiting for the next write pass, in arrival order, and the bytes of their texts. All
 * queued writes run together in one transaction and share its COMMIT (group commit, rule 6), and
 * the pages several of them touch reach the log once; each response still waits for that COMMIT. */
public final class WriteQueue {
    private WriteQueue() {}

    private static final List<QueuedWrite> writes = new ArrayList<>(4096);
    // The queued texts. The storage never moves while writes point into it: a text that does not
    // fit goes into memory of its own, freed with the queue.
    private static final int STORAGE = 1 << 20;
    private static final NativeBuffer storage = new NativeBuffer(STORAGE);
    private static Arena overflow;

    /** When the queue runs (µs). */
    static long dueAt;

    static boolean isEmpty() { return writes.isEmpty(); }

    static int size() { return writes.size(); }

    static QueuedWrite get(int k) { return writes.get(k); }

    /** Queues a write; the first one of a batch sets when the batch runs. */
    static void add(QueuedWrite write, long nowMicros, long windowMicros) {
        if (writes.isEmpty()) dueAt = nowMicros + windowMicros;
        writes.add(write);
    }

    /** A copy of bytes in the queue's storage, valid until the queue is cleared. */
    public static Bytes keep(Bytes bytes) {
        long at;
        if (storage.length + bytes.length() <= STORAGE) {
            at = storage.address + storage.length;
            storage.length += bytes.length();
        } else {
            if (overflow == null) overflow = Arena.ofConfined();
            at = overflow.allocate(Math.max(1, bytes.length())).address();
        }
        Memory.copy(bytes.address(), at, bytes.length());
        return new Bytes(at, bytes.length());
    }

    static void clear() {
        writes.clear();
        storage.clear();
        if (overflow != null) {
            overflow.close();
            overflow = null;
        }
    }
}
