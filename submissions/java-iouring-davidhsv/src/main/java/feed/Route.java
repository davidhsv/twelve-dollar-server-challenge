package feed;

/** What a request asks for. */
sealed interface Route {
    enum Feed implements Route { FEED }

    record Post(long id) implements Route {
        public Post {
            if (id <= 0) throw new IllegalArgumentException("post ids are positive");
        }
    }

    enum Health implements Route { HEALTH }

    record CreatePost(Request request) implements Route {}

    /** A like of the post whose id is `postId`'s text: the token is checked before the id. */
    record LikePost(Request request, Bytes postId) implements Route {}

    /** GET /posts/ followed by something that is not a positive integer. */
    enum InvalidPostId implements Route { INVALID_POST_ID }

    enum NotFound implements Route { NOT_FOUND }
}
