package feed;

import java.lang.foreign.Arena;

import feed.AuthResult.Authenticated;
import feed.JsonReader.Document;
import feed.JsonReader.Member;
import feed.Request.Authorization;

/** HS256 tokens: every token's signature is checked on every request, in constant time. */
final class JwtVerifier {
    private JwtVerifier() {}

    // Scratch, reused by each verification: the decoded signature, header and payload, the
    // expected MAC, and the username of the token last verified.
    private static final NativeBuffer SIGNATURE = new NativeBuffer(64);
    private static final NativeBuffer HEADER = new NativeBuffer(1024);
    private static final NativeBuffer PAYLOAD = new NativeBuffer(1024);
    private static final NativeBuffer FIELD = new NativeBuffer(256);
    private static final long EXPECTED = Arena.global().allocate(32, 16).address();

    private static final byte[] BEARER = Ascii.bytes("Bearer ");
    private static final byte[] ALG = Ascii.bytes("alg");
    private static final byte[] HS256 = Ascii.bytes("HS256");
    private static final byte[] EXP = Ascii.bytes("exp");
    private static final byte[] SUB = Ascii.bytes("sub");
    private static final byte[] USERNAME = Ascii.bytes("username");

    static AuthResult verify(Authorization authorization) {
        return switch (authorization) {
            case Authorization.Absent _ -> AuthResult.MissingToken.MISSING_TOKEN;
            case Authorization.Header(Bytes value) when !value.startsWith(BEARER) -> AuthResult.MissingToken.MISSING_TOKEN;
            case Authorization.Header(Bytes value) -> verifyToken(new Bytes(value.address() + BEARER.length, value.length() - BEARER.length));
        };
    }

    private static AuthResult verifyToken(Bytes token) {
        long start = token.address(), end = token.end();
        long dot1 = Memory.indexOf(start, end, '.');
        if (dot1 < 0) return AuthResult.InvalidToken.INVALID_TOKEN;
        long dot2 = Memory.indexOf(dot1 + 1, end, '.');
        if (dot2 < 0 || Memory.indexOf(dot2 + 1, end, '.') >= 0) return AuthResult.InvalidToken.INVALID_TOKEN;
        if (!signatureMatches(Bytes.between(start, dot2), Bytes.between(dot2 + 1, end))) return AuthResult.InvalidToken.INVALID_TOKEN;

        HEADER.clear();
        if (!Base64Url.decode(Bytes.between(start, dot1), HEADER)) return AuthResult.InvalidToken.INVALID_TOKEN;
        if (!(JsonReader.read(HEADER.contents(), ALG, null, null) instanceof Document.Object(Member.Text alg, _, _))) {
            return AuthResult.InvalidToken.INVALID_TOKEN;
        }
        FIELD.clear();
        JsonReader.decode(alg, FIELD);
        if (!FIELD.contentEquals(HS256)) return AuthResult.InvalidToken.INVALID_TOKEN;

        PAYLOAD.clear();
        if (!Base64Url.decode(Bytes.between(dot1 + 1, dot2), PAYLOAD)) return AuthResult.InvalidToken.INVALID_TOKEN;
        return switch (JsonReader.read(PAYLOAD.contents(), EXP, SUB, USERNAME)) {
            case Document.Object(Member.Number exp, _, _) when !(Uring.wallClockNanos() / 1e9 < JsonReader.toDouble(exp)) ->
                    AuthResult.InvalidToken.INVALID_TOKEN;
            case Document.Object(Member.Number _, Member.Text sub, Member.Text username) -> claims(sub, username);
            case Document.Object(Member.Number _, _, _) -> AuthResult.InvalidPayload.INVALID_PAYLOAD;
            case Document.Object _, Document.NotAnObject _, Document.Invalid _ -> AuthResult.InvalidToken.INVALID_TOKEN;
        };
    }

    private static AuthResult claims(Member.Text sub, Member.Text username) {
        FIELD.clear();
        JsonReader.decode(sub, FIELD);
        long userId = Decimal.parsePositive(FIELD.contents());
        if (userId <= 0) return AuthResult.InvalidPayload.INVALID_PAYLOAD;
        FIELD.clear();
        JsonReader.decode(username, FIELD);
        return new Authenticated(userId, FIELD.contents());
    }

    /** True when the signature is the canonical base64url of the MAC of the signed part, compared
     * over every byte whatever the first difference. */
    private static boolean signatureMatches(Bytes signed, Bytes signature) {
        SIGNATURE.clear();
        if (!Base64Url.decode(signature, SIGNATURE) || SIGNATURE.length != 32) return false;
        HmacSha256.mac(signed, EXPECTED);
        int difference = 0;
        for (int i = 0; i < 32; i++) difference |= Memory.unsignedByteAt(EXPECTED + i) ^ Memory.unsignedByteAt(SIGNATURE.address + i);
        return difference == 0;
    }
}
