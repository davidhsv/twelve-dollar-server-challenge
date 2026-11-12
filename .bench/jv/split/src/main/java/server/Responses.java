package server;

/** Writing responses into connections' output: each leaves after the pass's COMMIT. */
public final class Responses {
    private Responses() {}

    private static final byte[] BLANK_LINE = Ascii.bytes("\r\n\r\n");

    /** Status line and headers, then the body; the connection is marked as having answered in
     * this pass (its answers become the application's 500 if the pass's COMMIT fails). */
    public static void json(int connection, Status status, NativeBuffer body) {
        Outbox.answered(connection);
        long n = body.length;
        int digits = Decimal.width(n);
        long at = Uring.reserveOutput(Connections.ring(connection), Connections.slot(connection),
                status.head.length + digits + BLANK_LINE.length + n);
        Memory.copy(status.head, at);
        at += status.head.length;
        Decimal.write(at, n);
        at += digits;
        Memory.copy(BLANK_LINE, at);
        Memory.copy(body.address, at + BLANK_LINE.length, n);
    }

    /** A response known in advance, head and body (see fixed). */
    public static void whole(int connection, byte[] response) {
        Outbox.answered(connection);
        Memory.copy(response, Uring.reserveOutput(Connections.ring(connection), Connections.slot(connection), response.length));
    }

    /** The whole response with this status and body: head, Content-Length, blank line, body. */
    public static byte[] fixed(Status status, byte[] body) {
        byte[] head = status.head, length = Ascii.bytes(body.length + "\r\n\r\n");
        byte[] response = new byte[head.length + length.length + body.length];
        System.arraycopy(head, 0, response, 0, head.length);
        System.arraycopy(length, 0, response, head.length, length.length);
        System.arraycopy(body, 0, response, head.length + length.length, body.length);
        return response;
    }

    /** Replaces everything the connection answered in this pass, from `mark` on, with the
     * application's 500. */
    static void replaceWithInternalError(int connection, long mark) {
        int ring = Connections.ring(connection), slot = Connections.slot(connection);
        Uring.truncateOutput(ring, slot, mark);
        byte[] response = Server.internalError;
        Memory.copy(response, Uring.reserveOutput(ring, slot, response.length));
    }
}
