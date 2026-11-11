package feed;

/** What the bytes at the start of a connection's input are. */
sealed interface ParseResult {
    /** A whole request, `consumed` bytes long. */
    record Complete(Request request, long consumed) implements ParseResult {}

    /** More bytes are needed: the whole request's length once the head says it, else 0. */
    record Incomplete(long needed) implements ParseResult {
        public Incomplete {
            if (needed < 0) throw new IllegalArgumentException("negative length");
        }
    }

    /** Not a request this server reads (answered 400, and the connection closes). */
    enum Malformed implements ParseResult { MALFORMED }
}
