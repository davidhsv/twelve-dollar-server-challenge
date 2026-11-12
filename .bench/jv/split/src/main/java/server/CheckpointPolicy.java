package server;

import server.Sqlite.CheckpointState;

/** When db.c's thread copies the log back into the database, and when writes wait for it.
 *
 * Past `startFrames` log frames, ask for a copy. When it is done, hold the write queue (reads go
 * on) and ask again: that copy takes the frames written meanwhile, and once the whole log is
 * copied the next write starts the log over. Past `highFrames` the queue is held too. A hold lasts
 * at most `holdMicros`; then writes go on and a later round tries again.
 *
 * Measured over five-minute holds with the database on slow devices (device-mapper delay, 3-10 ms
 * writes, 10-30 ms flushes, 20-40 MB/s): with 4,096-frame cycles (16 MB) and holds of at most
 * 50 ms the writes' p99 was 53-111 ms, against 188-360 ms with 16,384 frames and 200 ms, and
 * 246-459 ms with 4,096 frames and 250 ms. A short hold lets the log grow when the disk cannot
 * keep up (a gigabyte or more after five minutes on the slowest device), which costs disk space,
 * not latency. */
final class CheckpointPolicy {
    private CheckpointPolicy() {}

    private static long startFrames = 4096;
    private static long highFrames = 65536;
    private static long holdMicros = 50_000;

    private enum Phase {
        IDLE,
        /** a copy is running; writes go on */
        COPYING,
        /** copying the rest, with writes held so that the log can start over */
        COPYING_TAIL
    }

    private static Phase phase = Phase.IDLE;
    private static boolean holding;
    private static long heldSince;

    static void configure(long start, long high, long hold) {
        startFrames = start;
        highFrames = high;
        holdMicros = hold;
    }

    static boolean holdsWrites() { return holding; }

    static void step(long nowMicros) {
        CheckpointState state = Sqlite.pollCheckpoint();
        switch (phase) {
            case IDLE -> {
                if (Sqlite.logFrames() >= startFrames) {
                    Sqlite.askCheckpoint();
                    phase = Phase.COPYING;
                }
            }
            case COPYING -> {
                if (state == CheckpointState.COPIED_ALL || state == CheckpointState.COPIED_SOME) {
                    // Copied up to where the log was when it started. Now nothing may be written
                    // until the rest is copied, so that the log can start over.
                    hold(nowMicros);
                    Sqlite.askCheckpoint();
                    phase = Phase.COPYING_TAIL;
                } else if (!holding && Sqlite.logFrames() >= highFrames) {
                    hold(nowMicros);
                } else if (holding && nowMicros - heldSince > holdMicros) {
                    release(nowMicros);
                }
            }
            case COPYING_TAIL -> {
                if (state == CheckpointState.COPIED_ALL) {
                    Sqlite.logWillRestart();
                    release(nowMicros);
                    phase = Phase.IDLE;
                    Stats.logRestarts++;
                } else if (state == CheckpointState.COPIED_SOME) {
                    release(nowMicros);
                    phase = Phase.IDLE;
                } else if (holding && nowMicros - heldSince > holdMicros) {
                    release(nowMicros);  // the copy is slow: writes go on, and a later round tries again
                }
            }
        }
    }

    private static void hold(long nowMicros) {
        if (holding) return;
        holding = true;
        heldSince = nowMicros;
        Stats.holds++;
    }

    private static void release(long nowMicros) {
        if (!holding) return;
        holding = false;
        Stats.held(nowMicros - heldSince);
    }

    /** For a stats line in the middle of a hold: the time held so far, counted from now on anew. */
    static void accountOngoingHold(long nowMicros) {
        if (!holding) return;
        Stats.held(nowMicros - heldSince);
        heldSince = nowMicros;
    }
}
