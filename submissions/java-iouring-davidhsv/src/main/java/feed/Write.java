package feed;

import server.Bytes;
import server.QueuedWrite;

/** A validated write waiting for the next write pass, with everything it needs. Its connection
 * reads no further request until it is answered, so responses keep their order. */
sealed interface Write extends QueuedWrite {

    /** A new post: the token's username (its author) and the trimmed body, copied into the
     * queue's storage. */
    value record NewPost(int connection, int generation, boolean close, long userId, Bytes author, Bytes text) implements Write {}

    value record Like(int connection, int generation, boolean close, long userId, long postId) implements Write {}
}
