package feed;

/** An operation's user_data, the 64 bits the kernel hands back with its completion:
 * operation (8 bits) | ring (8) | generation (16) | slot or listener (32). */
final class UserData {
    private UserData() {}

    static long of(Operation op, int connection, int generation) {
        return (op.code << 56) | ((long) Connections.ring(connection) << 48) | ((long) generation << 32)
                | Connections.slot(connection);
    }

    static long ofAccept(int ring, int listener) {
        return (Operation.ACCEPT.code << 56) | ((long) ring << 48) | listener;
    }

    /** The completion at `cqe` (struct io_uring_cqe) of ring `ring`, decoded once. */
    static Completion decode(int ring, long cqe) {
        long data = Memory.longAt(cqe + Uring.CQE_USER_DATA);
        int result = Memory.intAt(cqe + Uring.CQE_RES);
        int flags = Memory.intAt(cqe + Uring.CQE_FLAGS);
        int generation = (int) ((data >>> 32) & 0xffff);
        int low = (int) (data & 0xffffffffL);
        int connection = Connections.id(ring, low);
        return switch ((int) (data >>> 56)) {
            case 1 -> new Completion.Accepted(ring, low, result);
            case 2 -> new Completion.Received(connection, generation, result, flags);
            case 3 -> new Completion.Sent(connection, generation, result);
            case 5 -> new Completion.Closed(connection, generation);
            default -> Completion.ShutDown.SHUT_DOWN;  // the CLOSE linked behind it does the work
        };
    }
}
