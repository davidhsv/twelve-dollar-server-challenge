package server;

import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandle;

import static server.NativeLibrary.Call.BLOCKING;
import static server.NativeLibrary.Call.CRITICAL;
import static server.NativeLibrary.INT;
import static server.NativeLibrary.LONG;
import static server.NativeLibrary.failure;
import static server.NativeLibrary.function;

/** SQLite, as db.c offers it: the pass's transaction, B-tree cursors on the trees the
 * application registered (by number, in the order its C part registers them: db_tree), SELECT 1,
 * and the checkpoint thread. Mechanism only; the application's own statements are its own
 * functions in the same library.
 *
 * A record or text this returns points into SQLite's memory and is valid until the next call on
 * the same cursor or statement, or the end of the pass's transaction. */
public final class Sqlite {
    private Sqlite() {}

    /** Where a cursor move left the cursor. */
    public enum Position { ON_ENTRY, NONE, FAILED;
        private static final Position[] BY_CODE = { NONE, ON_ENTRY };
        static Position of(int code) { return code < 0 ? FAILED : BY_CODE[code]; }
    }

    private static final MethodHandle IO_ADDRESS = function("db_io", BLOCKING, LONG);
    private static long io() {
        try { return (long) IO_ADDRESS.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    // db.c's db_io_t (server.h): a record's length
    private static final long IO = io();
    private static final long IO_LENGTH = IO;

    private static final MethodHandle ERROR = function("db_error", BLOCKING, LONG);
    /** Why db_open or the health check failed: a NUL-terminated string db.c owns. */
    public static Bytes error() {
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
    public static boolean begin() {
        try { return (int) BEGIN.invokeExact() == 0; } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle COMMIT = function("db_commit", BLOCKING, INT);
    /** Ends the pass's transaction: true once committed (or there was none). */
    static boolean commit() {
        try { return (int) COMMIT.invokeExact() != 0; } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SEEK = function("db_seek", CRITICAL, INT, INT, LONG);
    /** Moves a table's cursor to a rowid. */
    public static Position seek(int table, long rowid) {
        try { return Position.of((int) SEEK.invokeExact(table, rowid)); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SEEK_GE = function("db_seek_ge", CRITICAL, INT, INT, LONG);
    /** Moves an index's cursor to its first entry whose leading integer column is at least key. */
    public static Position seekAtLeast(int index, long key) {
        try { return Position.of((int) SEEK_GE.invokeExact(index, key)); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle FIRST = function("db_first", CRITICAL, INT, INT);
    public static Position first(int tree) {
        try { return Position.of((int) FIRST.invokeExact(tree)); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle NEXT = function("db_next", CRITICAL, INT, INT);
    public static Position next(int tree) {
        try { return Position.of((int) NEXT.invokeExact(tree)); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle RECORD = function("db_rec", CRITICAL, LONG, INT);
    /** The record of the entry the cursor is on, on SQLite's page (or assembled in the tree's
     * spill buffer); empty if it cannot be read. Valid until this tree's cursor moves. */
    public static Bytes record(int tree) {
        try {
            long address = (long) RECORD.invokeExact(tree);
            return address == 0 ? Bytes.EMPTY : new Bytes(address, (int) Memory.longAt(IO_LENGTH));
        } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle PING = function("db_ping", CRITICAL, INT);
    /** SELECT 1; false with SQLite's message in error(). */
    public static boolean ping() {
        try { return (int) PING.invokeExact() == 0; } catch (Throwable t) { throw failure(t); }
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
