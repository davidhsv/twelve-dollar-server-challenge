package feed;

import server.Bytes;

/** What a request is answered with (ErrorReply, the fixed error responses, is one kind). */
sealed interface Reply permits ErrorReply, Reply.Found, Reply.Created, Reply.Feed, Reply.Liked, Reply.Healthy, Reply.Degraded {
    /** 200 {"post": …} */
    value record Found(PostRow post) implements Reply {}

    /** 201 {"post": …}, a new post (no likes yet). */
    value record Created(PostRow post) implements Reply {}

    /** 200 {"posts": […]}, the page FeedRows holds (its rows are made one at a time as they are
     * written, rather than kept in a list, so that each is scalar-replaced). */
    value record Feed(int posts) implements Reply {}

    /** 201 for a new like, 200 for a repeated one. */
    value record Liked(long postId, boolean already) implements Reply {}

    value record Healthy(long uptimeSeconds) implements Reply {}

    value record Degraded(Bytes reason) implements Reply {}
}
