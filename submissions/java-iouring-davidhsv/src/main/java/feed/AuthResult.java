package feed;

import server.Bytes;

/** What a request's token says. */
sealed interface AuthResult {
    /** A valid token: its user id and username (a view into the verifier's scratch, valid until
     * the next verification). */
    value record Authenticated(long userId, Bytes username) implements AuthResult {
        public Authenticated {
            if (userId <= 0) throw new IllegalArgumentException("user ids are positive");
        }
    }

    /** No Authorization header, or not a Bearer one. */
    enum MissingToken implements AuthResult { MISSING_TOKEN }

    /** Not a well-formed HS256 token with this server's signature and an exp in the future. */
    enum InvalidToken implements AuthResult { INVALID_TOKEN }

    /** A valid token whose sub is not a positive integer string or whose username is not a string. */
    enum InvalidPayload implements AuthResult { INVALID_PAYLOAD }
}
