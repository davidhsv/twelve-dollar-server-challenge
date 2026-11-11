package feed;

/** The io_uring rings and what the accept policy knows about each. */
final class Rings {
    private Rings() {}

    static final int MAX = 16;
    /** SO_REUSEPORT listeners, each with its own accept queue. */
    static final int LISTENERS = 8;

    static int count;
    /** Connections open on the ring. */
    static final long[] open = new long[MAX];
    /** The ring takes no more accepts until enough of its connections close. */
    static final boolean[] full = new boolean[MAX];
    /** Accepts in flight, per ring and listener (ring * LISTENERS + listener). */
    static final int[] acceptsInFlight = new int[MAX * LISTENERS];
    /** Where each ring's receive buffers start. */
    static final long[] receiveBuffers = new long[MAX];

    /** The receive buffer a completion names, valid until it goes back to the kernel. */
    static Bytes receiveBuffer(int ring, int bufferId, int length) {
        return new Bytes(receiveBuffers[ring] + (long) bufferId * Uring.RECEIVE_BUFFER_SIZE, length);
    }
}
