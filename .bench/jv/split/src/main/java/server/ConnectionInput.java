package server;

/** A connection's received bytes, turned into requests and answers. */
final class ConnectionInput {
    private ConnectionInput() {}

    /** Parses and answers every complete request in input. A write that waits for the next write
     * pass stops it: what follows that request stays with the connection until the write is
     * answered, so responses keep their order. A request still arriving is kept for later. */
    static void serve(int connection, Bytes input) {
        long at = input.address(), end = input.end();
        while (at < end) {
            switch (HttpParser.parse(Bytes.between(at, end))) {
                case ParseResult.Incomplete(long needed) -> {
                    keep(connection, Bytes.between(at, end), needed);
                    return;
                }
                case ParseResult.Malformed _ -> {
                    Responses.whole(connection, Server.badRequest);
                    Outbox.closeAfter(connection);
                    return;
                }
                case ParseResult.Complete(Request request, long consumed) -> {
                    Stats.requests++;
                    boolean queued = Server.application.handle(connection, request);
                    at += consumed;
                    if (queued) {
                        if (at < end) keep(connection, Bytes.between(at, end), 0);
                        return;
                    }
                    if (request.close()) {
                        Outbox.closeAfter(connection);
                        return;
                    }
                }
            }
        }
    }

    private static void keep(int connection, Bytes rest, long needed) {
        Uring.keep(Connections.ring(connection), Connections.slot(connection), rest.address(), rest.length());
        Connections.add(connection, Connections.INPUT_KEPT);
        Connections.need(connection, (int) needed);
    }

    /** Serves the input net.c kept for the connection, in its chunk; the chunk is freed after. */
    static void serveKept(int connection) {
        Connections.remove(connection, Connections.INPUT_KEPT);
        long chunk = Uring.takeKept(Connections.ring(connection), Connections.slot(connection));
        if (chunk == 0) return;
        serve(connection, new Bytes(chunk + Uring.CHUNK_BYTES, Memory.intAt(chunk + Uring.CHUNK_LENGTH)));
        Uring.freeChunk(chunk);
    }
}
