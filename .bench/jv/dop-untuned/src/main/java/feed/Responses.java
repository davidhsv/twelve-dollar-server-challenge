package feed;

import java.util.List;

/** Writing replies into connections' output: each leaves after the pass's COMMIT. */
final class Responses {
    private Responses() {}

    // The body being written, before its head (Content-Length comes first): scratch for one reply.
    private static final NativeBuffer BODY = new NativeBuffer(65536);

    private static final byte[] BLANK_LINE = Ascii.bytes("\r\n\r\n");
    private static final byte[] POST = Ascii.bytes("{\"post\":");
    private static final byte[] POSTS = Ascii.bytes("{\"posts\":[");
    private static final byte[] POSTS_END = Ascii.bytes("]}");
    private static final byte[] LIKED = Ascii.bytes("{\"liked\":true,\"already_liked\":false,\"post_id\":");
    private static final byte[] LIKED_AGAIN = Ascii.bytes("{\"liked\":true,\"already_liked\":true,\"post_id\":");
    private static final byte[] HEALTHY = Ascii.bytes("{\"status\":\"ok\",\"db\":\"ok\",\"uptime_s\":");
    private static final byte[] DEGRADED = Ascii.bytes("{\"status\":\"degraded\",\"db\":\"unreachable\",\"error\":\"");
    private static final byte[] QUOTE_END = Ascii.bytes("\"}");

    /** Appends the reply to the connection's output. */
    static void send(int connection, Reply reply) {
        switch (reply) {
            case ErrorReply error -> whole(connection, error.response);
            case Reply.Found(PostRow post) -> json(connection, Status.OK, onePost(post));
            case Reply.Created(PostRow post) -> json(connection, Status.CREATED, onePost(post));
            case Reply.Feed(List<PostRow> posts) -> json(connection, Status.OK, feed(posts));
            case Reply.Liked(long postId, boolean already) -> json(connection, already ? Status.OK : Status.CREATED, liked(postId, already));
            case Reply.Healthy(long uptime) -> json(connection, Status.OK, healthy(uptime));
            case Reply.Degraded(Bytes reason) -> json(connection, Status.SERVICE_UNAVAILABLE, degraded(reason));
        }
    }

    private static NativeBuffer onePost(PostRow post) {
        BODY.clear();
        BODY.append(POST);
        JsonWriter.post(BODY, post);
        BODY.append('}');
        return BODY;
    }

    private static NativeBuffer feed(List<PostRow> posts) {
        BODY.clear();
        BODY.append(POSTS);
        for (int k = 0; k < posts.size(); k++) {
            if (k > 0) BODY.append(',');
            JsonWriter.post(BODY, posts.get(k));
        }
        BODY.append(POSTS_END);
        return BODY;
    }

    private static NativeBuffer liked(long postId, boolean already) {
        BODY.clear();
        BODY.append(already ? LIKED_AGAIN : LIKED);
        BODY.appendDecimal(postId);
        BODY.append('}');
        return BODY;
    }

    private static NativeBuffer healthy(long uptime) {
        BODY.clear();
        BODY.append(HEALTHY);
        BODY.appendDecimal(uptime);
        BODY.append('}');
        return BODY;
    }

    private static NativeBuffer degraded(Bytes reason) {
        BODY.clear();
        BODY.append(DEGRADED);
        JsonWriter.string(BODY, reason);
        BODY.append(QUOTE_END);
        return BODY;
    }

    /** Status line and headers, then the body; the connection is marked as having answered in
     * this pass (its answers become a 500 if the pass's COMMIT fails). */
    private static void json(int connection, Status status, NativeBuffer body) {
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

    private static void whole(int connection, byte[] response) {
        Outbox.answered(connection);
        Memory.copy(response, Uring.reserveOutput(Connections.ring(connection), Connections.slot(connection), response.length));
    }

    /** Replaces everything the connection answered in this pass, from `mark` on, with a 500. */
    static void replaceWithInternalError(int connection, long mark) {
        int ring = Connections.ring(connection), slot = Connections.slot(connection);
        Uring.truncateOutput(ring, slot, mark);
        byte[] response = ErrorReply.INTERNAL.response;
        Memory.copy(response, Uring.reserveOutput(ring, slot, response.length));
    }
}
