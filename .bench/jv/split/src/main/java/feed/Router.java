package feed;

import server.Ascii;
import server.Bytes;
import server.Decimal;
import server.Memory;
import server.Request;

/** The API's routes. */
final class Router {
    private Router() {}

    private static final byte[] FEED = Ascii.bytes("/feed");
    private static final byte[] HEALTH = Ascii.bytes("/health");
    private static final byte[] POSTS = Ascii.bytes("/posts");
    private static final byte[] POSTS_PREFIX = Ascii.bytes("/posts/");
    private static final byte[] LIKE_SUFFIX = Ascii.bytes("/like");

    static Route route(Request request) {
        Bytes path = request.path();
        return switch (request.method()) {
            case GET -> {
                if (path.is(FEED)) yield Route.Feed.FEED;
                if (path.startsWith(POSTS_PREFIX)) {
                    Bytes id = Bytes.between(path.address() + POSTS_PREFIX.length, path.end());
                    if (Memory.indexOf(id.address(), id.end(), '/') < 0) {
                        long postId = Decimal.parsePositive(id);
                        yield postId > 0 ? new Route.Post(postId) : Route.InvalidPostId.INVALID_POST_ID;
                    }
                }
                yield path.is(HEALTH) ? Route.Health.HEALTH : Route.NotFound.NOT_FOUND;
            }
            case POST -> {
                if (path.is(POSTS)) yield new Route.CreatePost(request);
                if (path.length() >= POSTS_PREFIX.length + LIKE_SUFFIX.length && path.startsWith(POSTS_PREFIX)
                        && Bytes.between(path.end() - LIKE_SUFFIX.length, path.end()).is(LIKE_SUFFIX)) {
                    Bytes id = Bytes.between(path.address() + POSTS_PREFIX.length, path.end() - LIKE_SUFFIX.length);
                    if (Memory.indexOf(id.address(), id.end(), '/') < 0) yield new Route.LikePost(request, id);
                }
                yield Route.NotFound.NOT_FOUND;
            }
            case OTHER -> Route.NotFound.NOT_FOUND;
        };
    }
}
