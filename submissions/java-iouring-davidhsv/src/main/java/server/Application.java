package server;

/** What the server asks of the application it serves. The server owns the connections, the
 * HTTP framing, the pass's SQLite transaction and the write queue; the application decides what
 * each request means and writes the answers (Responses), reading through Sqlite's cursors.
 *
 * Everything runs on the loop's thread. */
public interface Application {

    /** The database is open; the listeners start next. */
    void started();

    /** Answers a request at once (Responses.json or Responses.whole, inside the pass's
     * transaction: Sqlite.begin), or queues a write with WritePass.queue. True when the connection
     * now waits for that write: it reads no further request until the write is answered. */
    boolean handle(int connection, Request request);

    /** Runs a queued write inside the pass's transaction and answers it. */
    void execute(QueuedWrite write);

    /** The whole response (Responses.fixed) to bytes that are not a request this server reads;
     * the connection closes after it. */
    byte[] badRequest();

    /** The whole response that replaces a pass's answers when its COMMIT fails; the connection
     * closes after it. */
    byte[] internalError();
}
