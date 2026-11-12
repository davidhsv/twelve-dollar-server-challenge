package server;

/** One parsed HTTP request: views into the bytes it arrived in (see Bytes), so it lives no longer
 * than they do. */
public value record Request(Method method, Bytes path, Authorization authorization, Bytes body, boolean close) {

    public enum Method { GET, POST, OTHER }

    /** The Authorization header, which an application can tell apart from a missing one. */
    public sealed interface Authorization {
        enum Absent implements Authorization { ABSENT }
        /** Its value, without the blanks around it. */
        value record Header(Bytes value) implements Authorization {}
    }
}
