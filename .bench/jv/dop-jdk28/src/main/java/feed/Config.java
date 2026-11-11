package feed;

import java.nio.charset.StandardCharsets;

/** The server's settings: SQLITE_PATH, JWT_SECRET, HOST and PORT, and the test knobs (none of
 * which is set on the benchmark box). */
record Config(byte[] databasePath, byte[] secret, byte[] host, long port, long slotsPerRing, long writeWindowMicros,
              long checkpointFrames, long checkpointHighFrames, long holdMicros, long waitMicros, boolean stats) {

    Config {
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("PORT must be 1-65535");
    }

    static Config fromEnvironment(long descriptorLimit) {
        long frames = Environment.integer("FEED_CK_FRAMES", 64, 1_000_000, 4096);
        return new Config(
                Environment.get("SQLITE_PATH", "feed.db"),
                Environment.get("JWT_SECRET", ""),
                Environment.get("HOST", "127.0.0.1"),
                port(Environment.get("PORT", "3000")),
                Environment.integer("FEED_SLOTS", 64, 1_048_576, descriptorLimit),
                Environment.integer("FEED_WRITE_US", 0, 1_000_000, 5000),
                frames,
                Environment.integer("FEED_CK_HIGH", 64, 10_000_000, Math.max(65536, frames * 4)),
                Environment.integer("FEED_GATE_US", 0, 10_000_000, 50_000),
                Environment.integer("FEED_WAIT_US", 50, 100_000, 1000),
                Environment.get("FEED_STATS") != null);
    }

    /** An optionally signed decimal integer, or -1 (rejected by the constructor). */
    private static long port(byte[] text) {
        try {
            return Long.parseLong(new String(text, StandardCharsets.ISO_8859_1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    String hostText() { return new String(host, StandardCharsets.UTF_8); }
}
