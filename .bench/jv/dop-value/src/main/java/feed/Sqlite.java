package feed;

import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandle;

import static feed.NativeLibrary.Call.BLOCKING;
import static feed.NativeLibrary.Call.CRITICAL;
import static feed.NativeLibrary.INT;
import static feed.NativeLibrary.LONG;
import static feed.NativeLibrary.failure;
import static feed.NativeLibrary.function;

/** SQLite, as db.c offers it: the pass's transaction, B-tree cursors on four trees, the two
 * INSERTs, the reference feed query, and the checkpoint thread. Mechanism only.
 *
 * A record or text this returns points into SQLite's memory and is valid until the next call on
 * the same cursor or statement, or the end of the pass's transaction. */
final class Sqlite {
    private Sqlite() {}

    /** The trees db.c keeps a cursor on during a pass (its enum, in order). */
    enum Tree {
        POSTS, USERS,
        /** posts_created_at_id_idx: (created_at DESC, id DESC, rowid) */
        FEED,
        /** likes_post_id_idx: (post_id, rowid) */
        LIKES
    }

    /** Where a cursor move left the cursor. */
    enum Position { ON_ENTRY, NONE, FAILED;
        private static final Position[] BY_CODE = { NONE, ON_ENTRY };
        static Position of(int code) { return code < 0 ? FAILED : BY_CODE[code]; }
    }

    private static final MethodHandle IO_ADDRESS = function("db_io", BLOCKING, LONG);
    private static long io() {
        try { return (long) IO_ADDRESS.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    // db.c's feed_io_t (feed.h): results that do not fit a return value
    private static final long IO = io();
    private static final long IO_LENGTH = IO, IO_ID = IO + 8, IO_LIKES = IO + 16, IO_TEXT = IO + 24, IO_TEXT_LENGTH = IO + 48;

    private static final MethodHandle ERROR = function("db_error", BLOCKING, LONG);
    /** Why db_open or the health check failed: a NUL-terminated string db.c owns. */
    static Bytes error() {
        try {
            long message = (long) ERROR.invokeExact();
            return new Bytes(message, Memory.cStringLength(message));
        } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle OPEN = function("db_open", BLOCKING, INT, LONG, LONG);
    /** Opens both connections, checks the schema, prepares the statements and starts the
     * checkpoint thread; false with the reason in error(). */
    static boolean open(byte[] path) {
        try (Arena arena = Arena.ofConfined()) {
            long address = arena.allocate(path.length + 1L).address();
            Memory.copy(path, address);
            return (int) OPEN.invokeExact(address, (long) path.length) == 0;
        } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle BEGIN = function("db_begin", CRITICAL, INT);
    /** Joins the pass's transaction, opening it for the first request that needs it. */
    static boolean begin() {
        try { return (int) BEGIN.invokeExact() == 0; } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle COMMIT = function("db_commit", BLOCKING, INT);
    /** Ends the pass's transaction: true once committed (or there was none). */
    static boolean commit() {
        try { return (int) COMMIT.invokeExact() != 0; } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SEEK = function("db_seek", CRITICAL, INT, INT, LONG);
    /** Moves a table's cursor to a rowid. */
    static Position seek(Tree table, long rowid) {
        try { return Position.of((int) SEEK.invokeExact(table.ordinal(), rowid)); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SEEK_GE = function("db_seek_ge", CRITICAL, INT, INT, LONG);
    /** Moves an index's cursor to its first entry whose leading integer column is at least key. */
    static Position seekAtLeast(Tree index, long key) {
        try { return Position.of((int) SEEK_GE.invokeExact(index.ordinal(), key)); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle FIRST = function("db_first", CRITICAL, INT, INT);
    static Position first(Tree tree) {
        try { return Position.of((int) FIRST.invokeExact(tree.ordinal())); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle NEXT = function("db_next", CRITICAL, INT, INT);
    static Position next(Tree tree) {
        try { return Position.of((int) NEXT.invokeExact(tree.ordinal())); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle RECORD = function("db_rec", CRITICAL, LONG, INT);
    /** The record of the entry the cursor is on, on SQLite's page (or assembled in the tree's
     * spill buffer); empty if it cannot be read. Valid until this tree's cursor moves. */
    static Bytes record(Tree tree) {
        try {
            long address = (long) RECORD.invokeExact(tree.ordinal());
            return address == 0 ? Bytes.EMPTY : new Bytes(address, (int) Memory.longAt(IO_LENGTH));
        } catch (Throwable t) { throw failure(t); }
    }

    /** What INSERT INTO posts gave back. */
    sealed interface PostInsert {
        /** The new row's id and created_at (valid until the next post). */
        value record Created(long id, Bytes createdAt) implements PostInsert {}
        enum Failed implements PostInsert { FAILED }
    }

    private static final MethodHandle ADD_POST = function("db_add_post", BLOCKING, INT, LONG, LONG, LONG);
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

    private static final MethodHandle ADD_LIKE = function("db_add_like", BLOCKING, INT, LONG, LONG);
    static LikeInsert insertLike(long userId, long postId) {
        try { return LikeInsert.of((int) ADD_LIKE.invokeExact(userId, postId)); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle PING = function("db_ping", CRITICAL, INT);
    /** SELECT 1; false with SQLite's message in error(). */
    static boolean ping() {
        try { return (int) PING.invokeExact() == 0; } catch (Throwable t) { throw failure(t); }
    }

    /** One step of the reference feed query. */
    sealed interface FeedStep {
        value record Row(PostRow post) implements FeedStep {}
        enum End implements FeedStep { DONE, FAILED }
    }

    private static final MethodHandle FEED_STEP = function("db_feed_step", BLOCKING, INT);
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

    private static final MethodHandle FEED_RESET = function("db_feed_reset", BLOCKING, null);
    static void resetReferenceFeed() {
        try { FEED_RESET.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    /** The checkpoint thread's state, as db_ck_poll reports it (a finished state once). */
    enum CheckpointState { IDLE, COPYING, COPIED_ALL, COPIED_SOME;
        private static final CheckpointState[] VALUES = values();
    }

    private static final MethodHandle CK_ASK = function("db_ck_ask", BLOCKING, null);
    /** Asks the checkpoint thread for one PASSIVE checkpoint. */
    static void askCheckpoint() {
        try { CK_ASK.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle CK_POLL = function("db_ck_poll", CRITICAL, INT);
    static CheckpointState pollCheckpoint() {
        try { return CheckpointState.VALUES[(int) CK_POLL.invokeExact()]; } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle WAL_FRAMES = function("db_wal_frames", CRITICAL, LONG);
    /** Frames in the log after the last commit that wrote. */
    static long logFrames() {
        try { return (long) WAL_FRAMES.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle WAL_RESTARTS = function("db_wal_restarts", CRITICAL, null);
    /** The whole log is copied back: the next write starts it over. */
    static void logWillRestart() {
        try { WAL_RESTARTS.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    /** What the disk cost since the last call (db.c's counters): the loop's log writes and
     * syncs, and the checkpoints on their thread. */
    record DiskStats(long logWrites, long logWriteMaxMicros, long logSyncs, long logSyncMaxMicros,
                     long checkpoints, long checkpointMicros, long checkpointMaxMicros) {}

    private static final long DISK_COUNTERS = Arena.global().allocate(9 * 8L, 8).address();
    private static final MethodHandle STATS_TAKE = function("db_stats_take", BLOCKING, null, LONG);
    static DiskStats takeDiskStats() {
        try { STATS_TAKE.invokeExact(DISK_COUNTERS); } catch (Throwable t) { throw failure(t); }
        long[] c = new long[9];
        for (int i = 0; i < 9; i++) c[i] = Memory.longAt(DISK_COUNTERS + 8L * i);
        return new DiskStats(c[0], c[2], c[3], c[5], c[6], c[7], c[8]);
    }
}
