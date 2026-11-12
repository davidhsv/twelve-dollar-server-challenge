package server;

import java.nio.charset.StandardCharsets;

/** Starts the server for an application: the database, the listeners and the first ring, then
 * the loop, which never returns. */
public final class Server {
    private Server() {}

    static Application application;
    static byte[] badRequest, internalError;

    public static void log(String line) { System.err.println(line); }

    /** Raises the process's soft RLIMIT_NOFILE to its hard limit; the new limit. */
    public static long raiseDescriptorLimit() { return Uring.raiseDescriptorLimit(); }

    /** Serves `app` with `config`; exits the process if the database or the listeners fail. */
    public static void run(ServerConfig config, Application app) {
        application = app;
        badRequest = app.badRequest();
        internalError = app.internalError();
        WritePass.configure(config.writeWindowMicros());
        CheckpointPolicy.configure(config.checkpointFrames(), config.checkpointHighFrames(), config.holdMicros());
        EventLoop.configure(config.waitMicros());
        Stats.enabled = config.stats();

        if (!Sqlite.open(config.databasePath())) {
            log("database: " + new String(Memory.toArray(Sqlite.error().address(), Sqlite.error().length()),
                    StandardCharsets.UTF_8));
            System.exit(1);
        }
        app.started();

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
