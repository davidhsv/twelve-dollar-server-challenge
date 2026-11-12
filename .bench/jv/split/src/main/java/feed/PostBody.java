package feed;

import server.Bytes;

/** A POST /posts body, judged. */
sealed interface PostBody {
    /** The body's text, decoded and trimmed: 1 to 500 code points. A view into the request's
     * bytes or into the decoding scratch, valid until the next body is read. */
    value record Valid(Bytes text) implements PostBody {}
    enum MalformedJson implements PostBody { MALFORMED_JSON }
    /** No "body" string, or one that trims to nothing. */
    enum Missing implements PostBody { MISSING }
    enum TooLong implements PostBody { TOO_LONG }
}
