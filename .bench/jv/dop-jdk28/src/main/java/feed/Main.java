package feed;

/** Starts the server: configuration, the database, the listeners and the first ring, then the
 * loop. */
public final class Main {
    private Main() {}

    static void log(String line) { System.err.println(line); }

    public static void main(String[] args) {
        long descriptorLimit = Uring.raiseDescriptorLimit();
        Config config;
        try {
            config = Config.fromEnvironment(descriptorLimit);
        } catch (IllegalArgumentException e) {
            log(e.getMessage());
            System.exit(1);
            return;
        }
        WritePass.configure(config.writeWindowMicros());
        CheckpointPolicy.configure(config.checkpointFrames(), config.checkpointHighFrames(), config.holdMicros());
        EventLoop.configure(config.waitMicros());
        Stats.enabled = config.stats();

        if (!Sqlite.open(config.databasePath())) {
            log("database: " + new String(Memory.toArray(Sqlite.error().address(), Sqlite.error().length()),
                    java.nio.charset.StandardCharsets.UTF_8));
            System.exit(1);
        }
        HmacSha256.key(config.secret());
        ReadPlans.started();

        Connections.slotsPerRing = (int) config.slotsPerRing();
        AcceptPolicy.configure(Connections.slotsPerRing);
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            long host = arena.allocate(config.host().length + 1L).address();
            Memory.copy(config.host(), host);
            int rc = Uring.listen(host, config.host().length, config.port(), Rings.LISTENERS);
            if (rc != 0) {
                log("listen " + config.hostText() + ":" + config.port() + " failed (errno " + -rc + ")");
                System.exit(1);
            }
        }
        if (!AcceptPolicy.addRing()) {
            log("io_uring setup failed (needs Linux 6.1+ and io_uring allowed)");
            System.exit(1);
        }
        long locked = Uring.lockCode();
        log("listening on " + config.hostText() + ":" + config.port() + "; io_uring, " + Connections.slotsPerRing
                + " direct descriptors per ring, " + locked / 1024 + " KB of code locked");
        EventLoop.run();
    }
}
