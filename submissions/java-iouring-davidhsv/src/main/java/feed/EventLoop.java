package feed;

import java.lang.foreign.Arena;

/** The loop: one thread, every connection, one SQLite transaction per pass.
 *
 * A pass: every other ring submits and collects without waiting, and ring 0 goes last and waits
 * for 256 completions or the wait time, so a busy pass shares its transaction across many
 * requests. Every completion is handled (requests are served as they arrive, reads at once,
 * writes queued); the write queue runs if it is due; COMMIT; and only then does the pass's
 * output go to the kernel. Then receives that ended are armed again, accepts are topped up, and
 * the checkpoint policy runs. */
final class EventLoop {
    private EventLoop() {}

    private static final int WAIT_COMPLETIONS = 256;
    private static long waitNanos = 1_000_000;
    private static final long COMPLETIONS = Arena.global().allocate((long) Uring.CQ_ENTRIES * Uring.CQE_SIZE, 64).address();
    private static long nowMicros;

    static void configure(long waitMicros) { waitNanos = waitMicros * 1000; }

    /** The pass's clock (µs). */
    static long nowMicros() { return nowMicros; }

    static void run() {
        for (;;) pass();
    }

    private static void pass() {
        for (int ring = Rings.count - 1; ring > 0; ring--) Uring.sweep(ring);
        Uring.enter(0, WAIT_COMPLETIONS, waitNanos);
        nowMicros = System.nanoTime() / 1000;
        for (int ring = 0; ring < Rings.count; ring++) drain(ring);
        long served = Stats.enabled ? System.nanoTime() / 1000 : 0;
        if (WritePass.isDue(nowMicros)) WritePass.run(nowMicros);
        long wrote = Stats.enabled ? System.nanoTime() / 1000 : 0;
        boolean committed = Sqlite.commit();
        if (Stats.enabled) {
            long done = System.nanoTime() / 1000;
            Stats.servingMicros = Math.max(Stats.servingMicros, served - nowMicros);
            Stats.writingMicros = Math.max(Stats.writingMicros, wrote - served);
            Stats.committingMicros = Math.max(Stats.committingMicros, done - wrote);
        }
        Outbox.flush(committed);
        Completions.rearmReceives();
        AcceptPolicy.topUp(nowMicros);
        CheckpointPolicy.step(nowMicros);
        if (Stats.enabled) Stats.passEnded(nowMicros);
    }

    private static void drain(int ring) {
        long n = Uring.reap(ring, COMPLETIONS);
        Stats.completions += n;
        for (long k = 0; k < n; k++) Completions.handle(UserData.decode(ring, COMPLETIONS + k * Uring.CQE_SIZE));
    }
}
