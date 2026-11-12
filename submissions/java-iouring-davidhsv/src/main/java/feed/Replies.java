package feed;

import server.Ascii;
import server.Bytes;
import server.JsonWriter;
import server.NativeBuffer;
import server.Responses;
import server.Status;

/** Writing the API's replies into a connection's output (Responses does the HTTP). */
final class Replies {
    private Replies() {}

    // The body being written, before its head (Content-Length comes first): scratch for one reply.
    private static final NativeBuffer BODY = new NativeBuffer(65536);

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
            case ErrorReply error -> Responses.whole(connection, error.response);
            case Reply.Found(PostRow post) -> Responses.json(connection, Status.OK, onePost(post));
            case Reply.Created(PostRow post) -> Responses.json(connection, Status.CREATED, onePost(post));
            case Reply.Feed(int posts) -> Responses.json(connection, Status.OK, feed(posts));
            case Reply.Liked(long postId, boolean already) -> Responses.json(connection, already ? Status.OK : Status.CREATED, liked(postId, already));
            case Reply.Healthy(long uptime) -> Responses.json(connection, Status.OK, healthy(uptime));
            case Reply.Degraded(Bytes reason) -> Responses.json(connection, Status.SERVICE_UNAVAILABLE, degraded(reason));
        }
    }

    private static NativeBuffer onePost(PostRow post) {
        BODY.clear();
        BODY.append(POST);
        PostJson.write(BODY, post);
        BODY.append('}');
        return BODY;
    }

    private static NativeBuffer feed(int posts) {
        BODY.clear();
        BODY.append(POSTS);
        for (int k = 0; k < posts; k++) {
            if (k > 0) BODY.append(',');
            PostJson.write(BODY, FeedRows.row(k));
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
}
