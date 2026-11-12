package feed;

import server.Application;
import server.HmacSha256;
import server.QueuedWrite;
import server.Request;

/** The feed API (GET /feed, GET /posts/:id, POST /posts, POST /posts/:id/like, GET /health) as
 * the server's application. */
final class FeedApplication implements Application {
    private final byte[] secret;

    /** secret: JWT_SECRET, the tokens' HS256 key. */
    FeedApplication(byte[] secret) { this.secret = secret; }

    @Override public void started() {
        HmacSha256.key(secret);
        ReadPlans.started();
    }

    @Override public boolean handle(int connection, Request request) { return Requests.handle(connection, request); }

    @Override public void execute(QueuedWrite write) { WriteRequests.answer((Write) write); }

    @Override public byte[] badRequest() { return ErrorReply.BAD_REQUEST.response; }

    @Override public byte[] internalError() { return ErrorReply.INTERNAL.response; }
}
