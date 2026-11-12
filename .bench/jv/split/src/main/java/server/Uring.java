package server;

import java.lang.invoke.MethodHandle;

import static server.NativeLibrary.Call.BLOCKING;
import static server.NativeLibrary.Call.CRITICAL;
import static server.NativeLibrary.INT;
import static server.NativeLibrary.LONG;
import static server.NativeLibrary.failure;
import static server.NativeLibrary.function;

/** The kernel side, as net.c offers it: io_uring rings, the operations queued on them, their
 * completions, the receive buffers, each connection's native bytes (pending output, the send in
 * flight, input kept for later), and the listening sockets. Mechanism only: what to do and when
 * is decided by the loop.
 *
 * A connection is a slot in a ring's table of direct descriptors. */
final class Uring {
    private Uring() {}

    // struct io_uring_cqe (<linux/io_uring.h>), as net_reap copies them out
    static final int CQE_SIZE = 16;
    static final int CQE_USER_DATA = 0, CQE_RES = 8, CQE_FLAGS = 12;
    static final int IORING_CQE_F_BUFFER = 1;
    static final int IORING_CQE_F_MORE = 2;
    static final int IORING_CQE_BUFFER_SHIFT = 16;
    /** The completions a ring's queue holds (net.c's CQ_ENTRIES): room net_reap may fill. */
    static final int CQ_ENTRIES = 65536;

    // <errno.h>
    static final int ENFILE = 23;
    static final int ENOBUFS = 105;

    /** A receive buffer's size (net.c's RX_SIZE): buffer `bid` of a ring starts at
     * receiveBuffers(ring) + bid * RECEIVE_BUFFER_SIZE. */
    static final int RECEIVE_BUFFER_SIZE = 1024;

    // net.c's chunk: a 32-bit length at offset 0, the bytes from offset 8
    static final int CHUNK_LENGTH = 0, CHUNK_BYTES = 8;

    private static final MethodHandle RING_ADD = function("net_ring_add", BLOCKING, INT, LONG);
    /** A new ring with `slots` direct descriptors: its index, or -1. */
    static int addRing(long slots) {
        try { return (int) RING_ADD.invokeExact(slots); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle RX_BASE = function("net_rx_base", CRITICAL, LONG, INT);
    static long receiveBuffers(int ring) {
        try { return (long) RX_BASE.invokeExact(ring); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle ENTER = function("net_enter", BLOCKING, null, INT, INT, LONG);
    /** Submits what is queued and waits for `wait` completions or `nanos`, whichever is first. */
    static void enter(int ring, int wait, long nanos) {
        try { ENTER.invokeExact(ring, wait, nanos); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SWEEP = function("net_sweep", CRITICAL, null, INT);
    /** Submits and collects without waiting; free when nothing is queued or pending. */
    static void sweep(int ring) {
        try { SWEEP.invokeExact(ring); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle REAP = function("net_reap", CRITICAL, LONG, INT, LONG);
    /** Copies the ring's completions to `out` (room for CQ_ENTRIES) and returns how many. */
    static long reap(int ring, long out) {
        try { return (long) REAP.invokeExact(ring, out); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle ACCEPT = function("net_accept", CRITICAL, null, INT, INT, LONG);
    static void accept(int ring, int listener, long userData) {
        try { ACCEPT.invokeExact(ring, listener, userData); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle RECV = function("net_recv", CRITICAL, null, INT, INT, LONG);
    /** Arms a multishot receive into the ring's provided buffers. */
    static void receive(int ring, int slot, long userData) {
        try { RECV.invokeExact(ring, slot, userData); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SEND = function("net_send", CRITICAL, null, INT, INT, LONG);
    /** Starts sending the connection's pending output. */
    static void send(int ring, int slot, long userData) {
        try { SEND.invokeExact(ring, slot, userData); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SENT = function("net_sent", CRITICAL, INT, INT, INT, INT, LONG);
    /** A send completed with `bytes`: false when the whole output is out, true when the rest was
     * queued again. */
    static boolean sent(int ring, int slot, int bytes, long userData) {
        try { return (int) SENT.invokeExact(ring, slot, bytes, userData) != 0; } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle CLOSE = function("net_close", CRITICAL, null, INT, INT, LONG, LONG);
    /** SHUTDOWN, with a CLOSE hard-linked behind it. */
    static void close(int ring, int slot, long shutdownData, long closeData) {
        try { CLOSE.invokeExact(ring, slot, shutdownData, closeData); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle RX_DROP = function("net_rx_drop", CRITICAL, null, INT, INT);
    /** Gives a receive buffer back to the kernel: its bytes must not be read again. */
    static void releaseBuffer(int ring, int bufferId) {
        try { RX_DROP.invokeExact(ring, bufferId); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle RX_KEEP = function("net_rx_keep", CRITICAL, LONG, INT, INT, INT, INT);
    /** Appends a received message to the connection's kept input and gives its buffer back;
     * returns the kept length. */
    static long keepBuffer(int ring, int slot, int bufferId, int length) {
        try { return (long) RX_KEEP.invokeExact(ring, slot, bufferId, length); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle KEEP = function("net_keep", CRITICAL, null, INT, INT, LONG, LONG);
    static void keep(int ring, int slot, long address, long length) {
        try { KEEP.invokeExact(ring, slot, address, length); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle KEPT_TAKE = function("net_kept_take", CRITICAL, LONG, INT, INT);
    /** Takes the kept input away from the connection: its chunk, to be freed once served, or 0. */
    static long takeKept(int ring, int slot) {
        try { return (long) KEPT_TAKE.invokeExact(ring, slot); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle CHUNK_FREE = function("net_chunk_free", CRITICAL, null, LONG);
    static void freeChunk(long chunk) {
        try { CHUNK_FREE.invokeExact(chunk); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle OUT_RESERVE = function("net_out_reserve", CRITICAL, LONG, INT, INT, LONG);
    /** Appends `length` bytes to the connection's pending output and returns where they go; the
     * caller writes all of them before the output is touched again. */
    static long reserveOutput(int ring, int slot, long length) {
        try { return (long) OUT_RESERVE.invokeExact(ring, slot, length); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle OUT_LEN = function("net_out_len", CRITICAL, LONG, INT, INT);
    static long outputLength(int ring, int slot) {
        try { return (long) OUT_LEN.invokeExact(ring, slot); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle OUT_CUT = function("net_out_cut", CRITICAL, null, INT, INT, LONG);
    static void truncateOutput(int ring, int slot, long length) {
        try { OUT_CUT.invokeExact(ring, slot, length); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle FREE = function("net_free", CRITICAL, null, INT, INT);
    /** The slot is closed: frees everything it holds. */
    static void freeSlot(int ring, int slot) {
        try { FREE.invokeExact(ring, slot); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle DROP_OUTPUT = function("net_drop_output", CRITICAL, null, INT, INT);
    static void dropOutput(int ring, int slot) {
        try { DROP_OUTPUT.invokeExact(ring, slot); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle DROP_KEPT = function("net_drop_kept", CRITICAL, null, INT, INT);
    static void dropKept(int ring, int slot) {
        try { DROP_KEPT.invokeExact(ring, slot); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle LISTEN = function("net_listen", BLOCKING, INT, LONG, LONG, LONG, INT);
    /** `count` SO_REUSEPORT listeners on host:port: 0, or -errno. */
    static int listen(long host, long hostLength, long port, int count) {
        try { return (int) LISTEN.invokeExact(host, hostLength, port, count); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle SLOT_LIMIT = function("net_slot_limit", BLOCKING, LONG);
    /** Raises the soft descriptor limit to the hard one and returns it: the largest ring. */
    static long raiseDescriptorLimit() {
        try { return (long) SLOT_LIMIT.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle LOCK_CODE = function("net_lock_code", BLOCKING, LONG);
    /** Locks the code of libfeed.so and libjvm.so in memory; the bytes locked. */
    static long lockCode() {
        try { return (long) LOCK_CODE.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }

    private static final MethodHandle TIME_NS = function("net_time_ns", CRITICAL, LONG);
    /** The wall clock in nanoseconds since the epoch. */
    static long wallClockNanos() {
        try { return (long) TIME_NS.invokeExact(); } catch (Throwable t) { throw failure(t); }
    }
}
