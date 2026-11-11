package feed;

import feed.JsonReader.Document;
import feed.JsonReader.Member;

/** Judging a POST /posts body. */
final class PostBodies {
    private PostBodies() {}

    static final int MAX_CODE_POINTS = 500;

    private static final byte[] BODY = Ascii.bytes("body");
    // where an escaped body is decoded
    private static final NativeBuffer DECODED = new NativeBuffer(4096);

    static PostBody of(Bytes json) {
        return switch (JsonReader.read(json, BODY, null, null)) {
            case Document.Invalid _ -> PostBody.MalformedJson.MALFORMED_JSON;
            case Document.Object(Member.Text text, _, _) -> judge(JavaScriptText.trim(decoded(text)));
            case Document.Object _, Document.NotAnObject _ -> PostBody.Missing.MISSING;
        };
    }

    private static Bytes decoded(Member.Text text) {
        if (!text.escaped()) return text.raw();
        DECODED.clear();
        JsonReader.decode(text, DECODED);
        return DECODED.contents();
    }

    private static PostBody judge(Bytes trimmed) {
        if (trimmed.length() == 0) return PostBody.Missing.MISSING;
        // more than 500 bytes may still be 500 code points or fewer
        if (trimmed.length() > MAX_CODE_POINTS && JavaScriptText.codePoints(trimmed) > MAX_CODE_POINTS) return PostBody.TooLong.TOO_LONG;
        return new PostBody.Valid(trimmed);
    }
}
