package feed;

/** How many accepts are in flight, on which ring, and when to add a ring.
 *
 * Accepted sockets are direct descriptors in a ring's file table, which RLIMIT_NOFILE caps, so
 * more connections need more rings. A ring takes accepts until fewer of its slots are free than
 * the accepts in flight plus some room (an accept that finds no free slot has already taken the
 * connection off the listen queue); then a ring with room takes over, and a ring is added when
 * none has room. */
final class AcceptPolicy {
    private AcceptPolicy() {}

    /** Accepts kept in flight on the accepting ring, over all listeners. */
    private static int depth;
    /** A ring stops accepting while fewer than this many of its slots are free. */
    private static int margin;
    private static int accepting;
    /** Adding a ring failed: not again before this (µs). */
    private static long retryAt;

    static void configure(int slotsPerRing) {
        depth = Math.clamp(slotsPerRing / 32, Rings.LISTENERS, 512) / Rings.LISTENERS * Rings.LISTENERS;
        margin = depth + 64;
    }

    static boolean addRing() {
        if (Rings.count >= Rings.MAX || Uring.addRing(Connections.slotsPerRing) < 0) return false;
        int ring = Rings.count;
        int total = (ring + 1) * Connections.slotsPerRing;
        Connections.resize(total);
        Outbox.resize(total);
        Completions.resize(total);
        Rings.receiveBuffers[ring] = Uring.receiveBuffers(ring);
        Rings.open[ring] = 0;
        Rings.full[ring] = false;
        for (int listener = 0; listener < Rings.LISTENERS; listener++) Rings.acceptsInFlight[ring * Rings.LISTENERS + listener] = 0;
        Rings.count++;
        if (ring > 0) Main.log("ring " + ring + " added");
        return true;
    }

    static void accepted(Completion.Accepted c) {
        int ring = c.ring();
        Rings.acceptsInFlight[ring * Rings.LISTENERS + c.listener()]--;
        if (c.result() >= 0) {
            int id = Connections.id(ring, c.result());
            int generation = Connections.open(id);
            Uring.receive(ring, c.result(), UserData.of(Operation.RECEIVE, id, generation));
            Rings.open[ring]++;
            if (Rings.open[ring] + margin >= Connections.slotsPerRing) Rings.full[ring] = true;
        } else if (c.result() == -Uring.ENFILE) {
            Rings.full[ring] = true;
        }
    }

    static void connectionClosed(int ring) { Rings.open[ring]--; }

    static void maybeRoomAgain(int ring) {
        if (Rings.full[ring] && Rings.open[ring] + 2L * margin < Connections.slotsPerRing) Rings.full[ring] = false;
    }

    /** Keeps single-shot accepts in flight on the accepting ring, spread over the listeners. */
    static void topUp(long nowMicros) {
        if (Rings.full[accepting]) {
            int next = -1;
            for (int r = 0; r < Rings.count && next < 0; r++) if (!Rings.full[r]) next = r;
            if (next < 0) {
                if (nowMicros < retryAt) return;
                if (!addRing()) {
                    retryAt = nowMicros + 1_000_000;
                    Main.log("cannot add a ring; trying again in 1 s");
                    return;
                }
                next = Rings.count - 1;
            }
            accepting = next;
        }
        int ring = accepting;
        int each = depth / Rings.LISTENERS;
        for (int listener = 0; listener < Rings.LISTENERS; listener++) {
            while (Rings.acceptsInFlight[ring * Rings.LISTENERS + listener] < each) {
                Uring.accept(ring, listener, UserData.ofAccept(ring, listener));
                Rings.acceptsInFlight[ring * Rings.LISTENERS + listener]++;
            }
        }
    }
}
