package feed;

import feed.AuthResult.Authenticated;
import feed.FeedDatabase.PostInsert;
import server.Bytes;
import server.Connections;
import server.Decimal;
import server.Request;
import server.Sqlite;
import server.WritePass;
import server.WriteQueue;

/** POST /posts and POST /posts/:id/like. A write is checked when it arrives (the token, the id,
 * the body) and a failure is answered at once; a write that passes waits in the WriteQueue, or
 * runs at once when a write pass is running. */
final class WriteRequests {
    private WriteRequests() {}

    /** True when the write now waits in the queue. */
    static boolean createPost(int connection, Request request) {
        return switch (Authentication.of(request.authorization())) {
            case Authenticated(long userId, Bytes username) -> switch (PostBodies.of(request.body())) {
                case PostBody.Valid(Bytes text) -> submit(new Write.NewPost(connection, Connections.generation(connection),
                        request.close(), userId, username, text));
                case PostBody.MalformedJson _ -> answer(connection, ErrorReply.MALFORMED_JSON);
                case PostBody.Missing _ -> answer(connection, ErrorReply.BODY_REQUIRED);
                case PostBody.TooLong _ -> answer(connection, ErrorReply.BODY_TOO_LONG);
            };
            case AuthResult.MissingToken _ -> answer(connection, ErrorReply.MISSING_TOKEN);
            case AuthResult.InvalidToken _ -> answer(connection, ErrorReply.INVALID_TOKEN);
            case AuthResult.InvalidPayload _ -> answer(connection, ErrorReply.INVALID_PAYLOAD);
        };
    }

    static boolean likePost(int connection, Request request, Bytes postIdText) {
        return switch (Authentication.of(request.authorization())) {
            case Authenticated(long userId, Bytes _) -> {
                long postId = Decimal.parsePositive(postIdText);
                yield postId <= 0 ? answer(connection, ErrorReply.INVALID_POST_ID)
                        : submit(new Write.Like(connection, Connections.generation(connection), request.close(), userId, postId));
            }
            case AuthResult.MissingToken _ -> answer(connection, ErrorReply.MISSING_TOKEN);
            case AuthResult.InvalidToken _ -> answer(connection, ErrorReply.INVALID_TOKEN);
            case AuthResult.InvalidPayload _ -> answer(connection, ErrorReply.INVALID_PAYLOAD);
        };
    }

    private static boolean answer(int connection, Reply reply) {
        Replies.send(connection, reply);
        return false;
    }

    private static boolean submit(Write write) {
        if (WritePass.isRunning()) return answer(write.connection(), execute(write));
        // the texts point into the request and the verifier's scratch: the queue keeps copies
        Write queued = switch (write) {
            case Write.NewPost(int c, int g, boolean close, long user, Bytes author, Bytes text) ->
                    new Write.NewPost(c, g, close, user, WriteQueue.keep(author), WriteQueue.keep(text));
            case Write.Like like -> like;
        };
        WritePass.queue(queued);
        return true;
    }

    /** A queued write's turn in the write pass: runs it and answers it. */
    static void answer(Write write) {
        Replies.send(write.connection(), execute(write));
    }

    /** Runs a write inside the pass's transaction. */
    private static Reply execute(Write write) {
        if (!Sqlite.begin()) return ErrorReply.INTERNAL;
        return switch (write) {
            case Write.NewPost(int _, int _, boolean _, long userId, Bytes author, Bytes text) -> switch (FeedDatabase.insertPost(userId, text)) {
                case PostInsert.Created(long id, Bytes createdAt) -> new Reply.Created(new PostRow(id, text, createdAt, author, 0));
                case PostInsert.Failed _ -> ErrorReply.INTERNAL;
            };
            case Write.Like(int _, int _, boolean _, long userId, long postId) -> switch (Sqlite.seek(FeedDatabase.POSTS, postId)) {
                case NONE -> ErrorReply.POST_NOT_FOUND;
                case FAILED -> ErrorReply.INTERNAL;
                case ON_ENTRY -> switch (FeedDatabase.insertLike(userId, postId)) {
                    case ADDED -> new Reply.Liked(postId, false);
                    case REPEATED -> new Reply.Liked(postId, true);
                    case FAILED -> ErrorReply.INTERNAL;
                };
            };
        };
    }
}
