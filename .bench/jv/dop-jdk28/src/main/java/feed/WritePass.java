package feed;

/** When the write queue runs, and running it: 5 ms after the first write of a batch arrived, all
 * queued writes in arrival order, in the pass's transaction. */
final class WritePass {
    private WritePass() {}

    private static long windowMicros = 5000;
    private static boolean running;

    static void configure(long window) { windowMicros = window; }

    static long windowMicros() { return windowMicros; }

    /** While the queue runs, a write that arrives (behind a queued one) runs at once. */
    static boolean isRunning() { return running; }

    static boolean isDue(long nowMicros) {
        return !WriteQueue.isEmpty() && !CheckpointPolicy.holdsWrites() && nowMicros >= WriteQueue.dueAt;
    }

    static void run(long nowMicros) {
        running = true;
        Stats.writePasses++;
        Stats.oldestWriteMicros = Math.max(Stats.oldestWriteMicros, nowMicros - (WriteQueue.dueAt - windowMicros));
        for (int k = 0; k < WriteQueue.size(); k++) {
            Write write = WriteQueue.get(k);
            int id = write.connection();
            if (!Connections.isCurrent(id, write.generation())
                    || !Connections.is(id, Connections.OPEN | Connections.WRITE_QUEUED, Connections.CLOSING)) continue;
            Connections.remove(id, Connections.WRITE_QUEUED);
            Stats.writes++;
            Responses.send(id, WriteRequests.execute(write));
            if (write.close()) {
                Outbox.closeAfter(id);
                continue;
            }
            // What arrived behind the write is served now; a write among it runs at once.
            if (Connections.has(id, Connections.INPUT_KEPT)) ConnectionInput.serveKept(id);
            if (Connections.has(id, Connections.PEER_DONE)) Outbox.closeAfter(id);
        }
        WriteQueue.clear();
        running = false;
    }
}
