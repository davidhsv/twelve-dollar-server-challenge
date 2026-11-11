package feed;

/** What a request is answered with (ErrorReply, the fixed error responses, is one kind). */
sealed interface Reply permits ErrorReply, Reply.Found, Reply.Created, Reply.Feed, Reply.Liked, Reply.Healthy, Reply.Degraded {
    /** 200 {"post": …} */
    record Found(PostRow post) implements Reply {}

    /** 201 {"post": …}, a new post (no likes yet). */
    record Created(PostRow post) implements Reply {}

    /** 200 {"posts": […]}, the page FeedRows holds (its rows are made one at a time as they are
     * written, rather than kept in a list, so that each is scalar-replaced). */
    record Feed(int posts) implements Reply {}

    /** 201 for a new like, 200 for a repeated one. */
    record Liked(long postId, boolean already) implements Reply {}

    record Healthy(long uptimeSeconds) implements Reply {}

    record Degraded(Bytes reason) implements Reply {}
}
