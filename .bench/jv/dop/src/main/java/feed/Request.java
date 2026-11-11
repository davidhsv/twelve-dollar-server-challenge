package feed;

/** One parsed HTTP request: views into the bytes it arrived in (see Bytes), so it lives no longer
 * than they do. */
record Request(Method method, Bytes path, Authorization authorization, Bytes body, boolean close) {

    enum Method { GET, POST, OTHER }

    /** The Authorization header, which the API tells apart from a missing one. */
    sealed interface Authorization {
        enum Absent implements Authorization { ABSENT }
        /** Its value, without the blanks around it. */
        record Header(Bytes value) implements Authorization {}
    }
}
