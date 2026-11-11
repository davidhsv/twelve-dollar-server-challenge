package feed;

/** The API's error responses, each with its status and {"error": message}, whole and known in
 * advance. */
enum ErrorReply implements Reply {
    BAD_REQUEST(Status.BAD_REQUEST, "bad request"),
    INVALID_POST_ID(Status.BAD_REQUEST, "invalid post id"),
    MALFORMED_JSON(Status.BAD_REQUEST, "malformed JSON body"),
    BODY_REQUIRED(Status.BAD_REQUEST, "body is required"),
    BODY_TOO_LONG(Status.BAD_REQUEST, "body must be at most 500 characters"),
    MISSING_TOKEN(Status.UNAUTHORIZED, "missing bearer token"),
    INVALID_TOKEN(Status.UNAUTHORIZED, "invalid or expired token"),
    INVALID_PAYLOAD(Status.UNAUTHORIZED, "invalid token payload"),
    NOT_FOUND(Status.NOT_FOUND, "not found"),
    POST_NOT_FOUND(Status.NOT_FOUND, "post not found"),
    INTERNAL(Status.INTERNAL_SERVER_ERROR, "internal server error");

    /** The whole response: head, Content-Length, blank line, body. */
    final byte[] response;

    ErrorReply(Status status, String message) {
        byte[] body = Ascii.bytes("{\"error\":\"" + message + "\"}");
        byte[] head = status.head, length = Ascii.bytes(body.length + "\r\n\r\n");
        response = new byte[head.length + length.length + body.length];
        System.arraycopy(head, 0, response, 0, head.length);
        System.arraycopy(length, 0, response, head.length, length.length);
        System.arraycopy(body, 0, response, head.length + length.length, body.length);
    }
}
