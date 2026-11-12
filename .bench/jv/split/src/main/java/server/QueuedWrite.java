package server;

/** A write waiting in the WriteQueue for the next write pass. Its connection reads no further
 * request until it is answered, so responses keep their order. */
public interface QueuedWrite {
    int connection();

    /** The connection's generation when the write arrived (Connections.generation): a write of a
     * connection closed since is dropped. */
    int generation();

    /** The request asked to close the connection after its response. */
    boolean close();
}
