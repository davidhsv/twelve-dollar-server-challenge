package feed;

import server.Bytes;
import server.Request;

/** Answering one request, by its route. */
final class Requests {
    private Requests() {}

    /** True when the request is a write that now waits in the write queue. */
    static boolean handle(int connection, Request request) {
        return switch (Router.route(request)) {
            case Route.Feed _ -> answer(connection, ReadPlans.feed());
            case Route.Post(long id) -> answer(connection, ReadPlans.post(id));
            case Route.Health _ -> answer(connection, ReadPlans.health());
            case Route.CreatePost(Request r) -> WriteRequests.createPost(connection, r);
            case Route.LikePost(Request r, Bytes postId) -> WriteRequests.likePost(connection, r, postId);
            case Route.InvalidPostId _ -> answer(connection, ErrorReply.INVALID_POST_ID);
            case Route.NotFound _ -> answer(connection, ErrorReply.NOT_FOUND);
        };
    }

    private static boolean answer(int connection, Reply reply) {
        Replies.send(connection, reply);
        return false;
    }
}
