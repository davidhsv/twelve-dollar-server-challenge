package server;

/** Counters for FEED_STATS (a test knob): a line a second about the pass, the queue, the
 * checkpoints and the disk. The counters are plain fields bumped on the hot path; the line is
 * built only when stats are on. */
final class Stats {
    private Stats() {}

    static boolean enabled;

    static long requests, passes, completions, writes, writePasses, receivesOutOfBuffers, rearms, holds, logRestarts;
    static long longestPassMicros, servingMicros, writingMicros, committingMicros, oldestWriteMicros;
    static long heldMicros, longestHoldMicros;
    private static long since;

    static void held(long micros) {
        heldMicros += micros;
        longestHoldMicros = Math.max(longestHoldMicros, micros);
    }

    /** What one second looked like. */
    record Second(long requests, long passes, long completions, long writes, long writePasses, long longestPassMicros,
                  long servingMicros, long writingMicros, long committingMicros, long oldestWriteMillis,
                  long receivesOutOfBuffers, long rearms, long open, int rings, long logFrames, long holds, long heldMillis,
                  long longestHoldMillis, long logRestarts, int queued, Sqlite.DiskStats disk) {

        String line() {
            return "stats: " + requests + " req/s, " + passes + " passes, " + completions + " cqes, " + writes + " writes in "
                    + writePasses + " write passes, max pass " + longestPassMicros + " us (serve " + servingMicros + ", writes "
                    + writingMicros + ", commit " + committingMicros + "), oldest write " + oldestWriteMillis + " ms, nobuf "
                    + receivesOutOfBuffers + ", rearms " + rearms + ", open " + open + ", rings " + rings + ", wal " + logFrames
                    + ", gates " + holds + " held " + heldMillis + " ms max " + longestHoldMillis + " ms, restarts " + logRestarts
                    + ", queued " + queued + "; loop log writes " + disk.logWrites() + " max " + disk.logWriteMaxMicros()
                    + " us, syncs " + disk.logSyncs() + " max " + disk.logSyncMaxMicros() + " us; checkpoints "
                    + disk.checkpoints() + " " + disk.checkpointMicros() / 1000 + " ms max " + disk.checkpointMaxMicros() / 1000 + " ms";
        }
    }

    /** Called at the end of every pass when stats are on. */
    static void passEnded(long passStartMicros) {
        passes++;
        long now = System.nanoTime() / 1000;
        longestPassMicros = Math.max(longestPassMicros, now - passStartMicros);
        if (since == 0) since = passStartMicros;
        if (passStartMicros - since < 1_000_000) return;
        CheckpointPolicy.accountOngoingHold(passStartMicros);
        long open = 0;
        for (int r = 0; r < Rings.count; r++) open += Rings.open[r];
        Server.log(new Second(requests, passes, completions, writes, writePasses, longestPassMicros, servingMicros, writingMicros,
                committingMicros, oldestWriteMicros / 1000, receivesOutOfBuffers, rearms, open, Rings.count, Sqlite.logFrames(),
                holds, heldMicros / 1000, longestHoldMicros / 1000, logRestarts, WriteQueue.size(), Sqlite.takeDiskStats()).line());
        since = passStartMicros;
        requests = passes = completions = writes = writePasses = receivesOutOfBuffers = rearms = holds = logRestarts = 0;
        longestPassMicros = servingMicros = writingMicros = committingMicros = oldestWriteMicros = heldMicros = longestHoldMicros = 0;
    }
}
