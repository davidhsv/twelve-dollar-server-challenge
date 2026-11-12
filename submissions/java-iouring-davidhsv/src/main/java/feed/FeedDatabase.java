package feed;

import java.lang.invoke.MethodHandle;

import server.Bytes;
import server.Memory;

import static server.NativeLibrary.Call.BLOCKING;
import static server.NativeLibrary.INT;
import static server.NativeLibrary.LONG;
import static server.NativeLibrary.failure;
import static server.NativeLibrary.function;

/** The feed's part of the database, as feed_db.c offers it: the four trees it registers (read
 * with Sqlite's cursors), the two INSERTs and the reference feed query.
 *
 * A text this returns points into SQLite's memory and is valid until the next call on the same
 * statement, or the end of the pass's transaction. */
final class FeedDatabase {
    private FeedDatabase() {}

    /** The trees, by the numbers feed_db.c registers them under (Sqlite's tree arguments). */
    static final int POSTS = 0, USERS = 1;
    /** posts_created_at_id_idx: (created_at DESC, id DESC, rowid) */
    static final int FEED = 2;
    /** likes_post_id_idx: (post_id, rowid) */
    static final int LIKES = 3;

    private static final MethodHandle IO_ADDRESS = function("feed_io", BLOCKING, LONG);
    private static long io() {
        try { return (long) IO_ADDRESS.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    // feed_db.c's feed_io_t (feed.h): results that do not fit a return value
    private static final long IO = io();
    private static final long IO_ID = IO, IO_LIKES = IO + 8, IO_TEXT = IO + 16, IO_TEXT_LENGTH = IO + 40;

    /** What INSERT INTO posts gave back. */
    sealed interface PostInsert {
        /** The new row's id and created_at (valid until the next post). */
        value record Created(long id, Bytes createdAt) implements PostInsert {}
        enum Failed implements PostInsert { FAILED }
    }

    private static final MethodHandle ADD_POST = function("feed_add_post", BLOCKING, INT, LONG, LONG, LONG);
    static PostInsert insertPost(long userId, Bytes body) {
        try {
            if ((int) ADD_POST.invokeExact(userId, body.address(), (long) body.length()) != 0) return PostInsert.Failed.FAILED;
            return new PostInsert.Created(Memory.longAt(IO_ID),
                    new Bytes(Memory.longAt(IO_TEXT), (int) Memory.longAt(IO_TEXT_LENGTH)));
        } catch (Throwable t) { throw failure(t); }
    }

    /** What INSERT INTO likes … ON CONFLICT DO NOTHING did. */
    enum LikeInsert { REPEATED, ADDED, FAILED;
        static LikeInsert of(int code) { return code < 0 ? FAILED : code == 0 ? REPEATED : ADDED; }
    }

    private static final MethodHandle ADD_LIKE = function("feed_add_like", BLOCKING, INT, LONG, LONG);
    static LikeInsert insertLike(long userId, long postId) {
        try { return LikeInsert.of((int) ADD_LIKE.invokeExact(userId, postId)); } catch (Throwable t) { throw failure(t); }
    }

    /** One step of the reference feed query. */
    sealed interface FeedStep {
        value record Row(PostRow post) implements FeedStep {}
        enum End implements FeedStep { DONE, FAILED }
    }

    private static final MethodHandle FEED_STEP = function("feed_step", BLOCKING, INT);
    /** The reference query's next row; its texts are valid until the next step. */
    static FeedStep stepReferenceFeed() {
        try {
            int code = (int) FEED_STEP.invokeExact();
            if (code <= 0) return code == 0 ? FeedStep.End.DONE : FeedStep.End.FAILED;
            return new FeedStep.Row(new PostRow(Memory.longAt(IO_ID), text(0), text(1), text(2), Memory.longAt(IO_LIKES)));
        } catch (Throwable t) { throw failure(t); }
    }

    private static Bytes text(int k) {
        return new Bytes(Memory.longAt(IO_TEXT + 8L * k), (int) Memory.longAt(IO_TEXT_LENGTH + 8L * k));
    }

    private static final MethodHandle FEED_RESET = function("feed_reset", BLOCKING, null);
    static void resetReferenceFeed() {
        try { FEED_RESET.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }
}
