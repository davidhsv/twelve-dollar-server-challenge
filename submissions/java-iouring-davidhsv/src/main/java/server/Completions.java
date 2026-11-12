package server;

import server.Completion.Accepted;
import server.Completion.Closed;
import server.Completion.Received;
import server.Completion.Sent;

/** What each completion does to the connections. */
final class Completions {
    private Completions() {}

    // Connections whose multishot receive ended: armed again at the end of the pass, when the
    // buffers the pass used are back with the kernel.
    private static final ConnectionList toRearm = new ConnectionList();

    static void resize(int total) { toRearm.resize(total); }

    static void handle(Completion completion) {
        switch (completion) {
            case Accepted accepted -> AcceptPolicy.accepted(accepted);
            case Received received -> received(received);
            case Sent sent -> sent(sent);
            case Closed closed -> closed(closed);
            case Completion.ShutDown _ -> {}
        }
    }

    private static void received(Received c) {
        int id = c.connection(), ring = Connections.ring(id);
        if (!Connections.isCurrent(id, c.generation()) || !Connections.is(id, Connections.OPEN, Connections.CLOSING)) {
            if (c.hasBuffer()) Uring.releaseBuffer(ring, c.bufferId());
            return;
        }
        if (c.result() > 0 && c.hasBuffer()) {
            if (Connections.has(id, Connections.CLOSE_AFTER_OUTPUT)) {
                Uring.releaseBuffer(ring, c.bufferId());  // its answers are done; what follows is dropped
            } else if (Connections.has(id, Connections.WRITE_QUEUED)) {
                Uring.keepBuffer(ring, Connections.slot(id), c.bufferId(), c.result());  // behind a queued write: wait with it
                Connections.add(id, Connections.INPUT_KEPT);
            } else if (Connections.has(id, Connections.INPUT_KEPT)) {
                // More of a request that is still arriving; looked at again once it can be whole.
                long have = Uring.keepBuffer(ring, Connections.slot(id), c.bufferId(), c.result());
                if (have >= Connections.needed(id)) ConnectionInput.serveKept(id);
            } else {
                // Served where the kernel put it; the buffer goes back once nothing reads it.
                ConnectionInput.serve(id, Rings.receiveBuffer(ring, c.bufferId(), c.result()));
                Uring.releaseBuffer(ring, c.bufferId());
            }
            if (!c.more()) toRearm.add(id);
        } else if (c.result() > 0 || c.result() == -Uring.ENOBUFS) {
            // The receive ended for lack of a buffer.
            Stats.receivesOutOfBuffers++;
            toRearm.add(id);
        } else {
            // End of stream or an error: answer what was asked, then close.
            if (c.hasBuffer()) Uring.releaseBuffer(ring, c.bufferId());
            if (Connections.has(id, Connections.WRITE_QUEUED)) Connections.add(id, Connections.PEER_DONE);
            else Outbox.closeAfter(id);
        }
    }

    private static void sent(Sent c) {
        int id = c.connection();
        if (!Connections.isCurrent(id, c.generation()) || !Connections.has(id, Connections.SENDING)) return;
        int ring = Connections.ring(id), slot = Connections.slot(id);
        if (c.result() < 0) {
            Connections.remove(id, Connections.SENDING);
            Uring.dropOutput(ring, slot);
            Outbox.closeAfter(id);
            return;
        }
        if (Uring.sent(ring, slot, c.result(), UserData.of(Operation.SEND, id, c.generation()))) return;
        Connections.remove(id, Connections.SENDING);
        // Output that queued up behind this send goes out with the pass's other responses.
        if (Uring.outputLength(ring, slot) > 0 || Connections.has(id, Connections.CLOSE_AFTER_OUTPUT)) Outbox.touch(id);
    }

    private static void closed(Closed c) {
        int id = c.connection(), ring = Connections.ring(id);
        if (Connections.isCurrent(id, c.generation()) && Connections.has(id, Connections.OPEN)) {
            Uring.freeSlot(ring, Connections.slot(id));
            Connections.closed(id);
            AcceptPolicy.connectionClosed(ring);
        }
        AcceptPolicy.maybeRoomAgain(ring);
    }

    /** Arms again the receives that ended in this pass. */
    static void rearmReceives() {
        Stats.rearms += toRearm.size();
        for (int k = 0; k < toRearm.size(); k++) {
            int id = toRearm.get(k);
            if (Connections.is(id, Connections.OPEN, Connections.CLOSING)) {
                Uring.receive(Connections.ring(id), Connections.slot(id), UserData.of(Operation.RECEIVE, id, Connections.generation(id)));
            }
        }
        toRearm.clear();
    }
}
