package server;

/** The connections this pass gave output (or a close) to, and what happens to them once the
 * pass's COMMIT returns: no response of a pass reaches the kernel before then. */
final class Outbox {
    private Outbox() {}

    private static final ConnectionList touched = new ConnectionList();

    static void resize(int total) { touched.resize(total); }

    /** The connection has output, or a close, for the end of this pass. */
    static void touch(int id) {
        if (Connections.has(id, Connections.TOUCHED)) return;
        Connections.add(id, Connections.TOUCHED);
        Connections.markOutput(id, (int) Uring.outputLength(Connections.ring(id), Connections.slot(id)));
        touched.add(id);
    }

    /** The connection answered a request in this pass. */
    static void answered(int id) {
        touch(id);
        Connections.add(id, Connections.ANSWERED);
    }

    /** Close the connection once its answers are out; input it kept is dropped. */
    static void closeAfter(int id) {
        Connections.add(id, Connections.CLOSE_AFTER_OUTPUT);
        if (Connections.has(id, Connections.INPUT_KEPT)) {
            Uring.dropKept(Connections.ring(id), Connections.slot(id));
            Connections.remove(id, Connections.INPUT_KEPT);
        }
        touch(id);
    }

    /** After the pass's COMMIT: every touched connection's output goes to the kernel, or its close.
     * If the commit failed, no answer of the pass may stand: each becomes a 500 and the connection
     * closes. */
    static void flush(boolean committed) {
        for (int k = 0; k < touched.size(); k++) {
            int id = touched.get(k);
            int ring = Connections.ring(id), slot = Connections.slot(id);
            if (!committed && Connections.has(id, Connections.ANSWERED)) {
                Responses.replaceWithInternalError(id, Connections.outputMark(id));
                Connections.add(id, Connections.CLOSE_AFTER_OUTPUT);
            }
            Connections.remove(id, Connections.TOUCHED | Connections.ANSWERED);
            if (!Connections.has(id, Connections.OPEN) || Connections.has(id, Connections.CLOSING | Connections.SENDING)) continue;
            int gen = Connections.generation(id);
            if (Uring.outputLength(ring, slot) > 0) {
                Connections.add(id, Connections.SENDING);
                Uring.send(ring, slot, UserData.of(Operation.SEND, id, gen));
            } else if (Connections.has(id, Connections.CLOSE_AFTER_OUTPUT)) {
                Connections.add(id, Connections.CLOSING);
                Uring.close(ring, slot, UserData.of(Operation.SHUTDOWN, id, gen), UserData.of(Operation.CLOSE, id, gen));
            }
        }
        touched.clear();
    }
}
