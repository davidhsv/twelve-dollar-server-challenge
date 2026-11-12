package server;

/** A completed operation, as its user_data and result say. */
sealed interface Completion {
    /** An accept on a listener finished: the new connection's slot, or -errno. */
    value record Accepted(int ring, int listener, int result) implements Completion {}

    /** A multishot receive produced a message (result its length and flags its buffer), or ended
     * (result 0 or -errno, or no IORING_CQE_F_MORE in flags). */
    value record Received(int connection, int generation, int result, int flags) implements Completion {
        boolean hasBuffer() { return (flags & Uring.IORING_CQE_F_BUFFER) != 0; }
        int bufferId() { return flags >>> Uring.IORING_CQE_BUFFER_SHIFT; }
        boolean more() { return (flags & Uring.IORING_CQE_F_MORE) != 0; }
    }

    /** A send finished: the bytes it sent, or -errno. */
    value record Sent(int connection, int generation, int result) implements Completion {}

    /** The connection's CLOSE finished: its slot is free. */
    value record Closed(int connection, int generation) implements Completion {}

    enum ShutDown implements Completion { SHUT_DOWN }
}
