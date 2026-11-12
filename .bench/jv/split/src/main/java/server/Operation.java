package server;

/** The operations the loop queues, by the code their user_data carries. */
enum Operation {
    ACCEPT(1), RECEIVE(2), SEND(3), SHUTDOWN(4), CLOSE(5);

    final long code;

    Operation(long code) { this.code = code; }
}
