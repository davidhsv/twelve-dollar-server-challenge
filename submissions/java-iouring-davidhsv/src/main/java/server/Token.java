package server;

import server.JsonReader.Member;

/** What a request's bearer token is, as JwtVerifier checks it. */
public sealed interface Token {
    /** No Authorization header, or not a Bearer one. */
    enum Missing implements Token { MISSING }

    /** Not a well-formed HS256 token with this server's signature and an exp in the future. */
    enum Invalid implements Token { INVALID }

    /** A valid token: the two payload members the application named (Member.Absent if missing),
     * views into the verifier's scratch, valid until the next verification. */
    value record Valid(Member first, Member second) implements Token {}
}
