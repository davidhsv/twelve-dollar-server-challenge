package server;

/** The response statuses the server writes, with their heads up to the Content-Length value. */
public enum Status {
    OK("200 OK"),
    CREATED("201 Created"),
    BAD_REQUEST("400 Bad Request"),
    UNAUTHORIZED("401 Unauthorized"),
    NOT_FOUND("404 Not Found"),
    INTERNAL_SERVER_ERROR("500 Internal Server Error"),
    SERVICE_UNAVAILABLE("503 Service Unavailable");

    /** "HTTP/1.1 <status>\r\nContent-Type: application/json\r\nContent-Length: " */
    final byte[] head;

    Status(String line) {
        head = Ascii.bytes("HTTP/1.1 " + line + "\r\nContent-Type: application/json\r\nContent-Length: ");
    }
}
