package server;

import java.util.Arrays;

/** What the loop knows about each connection, one row per slot of every ring. Its bytes are in
 * net.c. A connection's id is ring * slotsPerRing + slot. The columns grow when a ring is added,
 * and never while serving. */
public final class Connections {
    private Connections() {}

    // flags
    static final int OPEN = 1;
    /** Close once the output is out. */
    static final int CLOSE_AFTER_OUTPUT = 2;
    /** A write of this connection waits in the write queue. */
    static final int WRITE_QUEUED = 4;
    /** The client finished sending while its write was queued. */
    static final int PEER_DONE = 8;
    /** SHUTDOWN and CLOSE are on their way. */
    static final int CLOSING = 16;
    static final int SENDING = 32;
    /** In this pass's Outbox. */
    static final int TOUCHED = 64;
    /** Answered something in this pass. */
    static final int ANSWERED = 128;
    /** net.c keeps input of this connection. */
    static final int INPUT_KEPT = 256;

    static int slotsPerRing;
    /** Bumped when the slot is reused, so completions for an older connection are dropped. */
    private static char[] generation = new char[0];
    private static char[] flags = new char[0];
    /** The pending output's length when this pass first touched the connection. */
    private static int[] outputMark = new int[0];
    /** Kept input is a request still arriving: its whole length once known, else 0. */
    private static int[] needed = new int[0];

    static int id(int ring, int slot) { return ring * slotsPerRing + slot; }
    static int ring(int id) { return id / slotsPerRing; }
    static int slot(int id) { return id % slotsPerRing; }

    static void resize(int total) {
        generation = Arrays.copyOf(generation, total);
        flags = Arrays.copyOf(flags, total);
        outputMark = Arrays.copyOf(outputMark, total);
        needed = Arrays.copyOf(needed, total);
    }

    /** The slot holds a new connection: its generation. */
    static int open(int id) {
        generation[id]++;
        flags[id] = OPEN;
        outputMark[id] = 0;
        needed[id] = 0;
        return generation[id];
    }

    static void closed(int id) { flags[id] = 0; }

    public static int generation(int id) { return generation[id]; }

    static boolean isCurrent(int id, int gen) { return generation[id] == gen; }

    static boolean has(int id, int flag) { return (flags[id] & flag) != 0; }

    /** True when the connection has all of `set` and none of `unset`. */
    static boolean is(int id, int set, int unset) { return (flags[id] & (set | unset)) == set; }

    static void add(int id, int flag) { flags[id] |= (char) flag; }

    static void remove(int id, int flag) { flags[id] &= (char) ~flag; }

    static int outputMark(int id) { return outputMark[id]; }
    static void markOutput(int id, int length) { outputMark[id] = length; }

    static int needed(int id) { return needed[id]; }
    static void need(int id, int length) { needed[id] = length; }
}
