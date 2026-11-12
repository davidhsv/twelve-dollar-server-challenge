package server;

import java.lang.foreign.Arena;

import server.JsonReader.Document;
import server.JsonReader.Member;
import server.Request.Authorization;

/** HS256 tokens: every token's signature is checked on every request, in constant time. */
public final class JwtVerifier {
    private JwtVerifier() {}

    // Scratch, reused by each verification: the decoded signature, header and payload, the
    // expected MAC, and the header's alg.
    private static final NativeBuffer SIGNATURE = new NativeBuffer(64);
    private static final NativeBuffer HEADER = new NativeBuffer(1024);
    private static final NativeBuffer PAYLOAD = new NativeBuffer(1024);
    private static final NativeBuffer FIELD = new NativeBuffer(256);
    private static final long EXPECTED = Arena.global().allocate(32, 16).address();

    private static final byte[] BEARER = Ascii.bytes("Bearer ");
    private static final byte[] ALG = Ascii.bytes("alg");
    private static final byte[] HS256 = Ascii.bytes("HS256");
    private static final byte[] EXP = Ascii.bytes("exp");

    /** The request's bearer token, checked with the key HmacSha256.key set; a valid one gives the
     * payload's members named `first` and `second` (read in the same pass as its exp). */
    public static Token verify(Authorization authorization, byte[] first, byte[] second) {
        return switch (authorization) {
            case Authorization.Absent _ -> Token.Missing.MISSING;
            case Authorization.Header(Bytes value) when !value.startsWith(BEARER) -> Token.Missing.MISSING;
            case Authorization.Header(Bytes value) -> verifyToken(new Bytes(value.address() + BEARER.length, value.length() - BEARER.length), first, second);
        };
    }

    private static Token verifyToken(Bytes token, byte[] first, byte[] second) {
        long start = token.address(), end = token.end();
        long dot1 = Memory.indexOf(start, end, '.');
        if (dot1 < 0) return Token.Invalid.INVALID;
        long dot2 = Memory.indexOf(dot1 + 1, end, '.');
        if (dot2 < 0 || Memory.indexOf(dot2 + 1, end, '.') >= 0) return Token.Invalid.INVALID;
        if (!signatureMatches(Bytes.between(start, dot2), Bytes.between(dot2 + 1, end))) return Token.Invalid.INVALID;

        HEADER.clear();
        if (!Base64Url.decode(Bytes.between(start, dot1), HEADER)) return Token.Invalid.INVALID;
        if (!(JsonReader.read(HEADER.contents(), ALG, null, null) instanceof Document.Object(Member.Text alg, _, _))) {
            return Token.Invalid.INVALID;
        }
        FIELD.clear();
        JsonReader.decode(alg, FIELD);
        if (!FIELD.contentEquals(HS256)) return Token.Invalid.INVALID;

        PAYLOAD.clear();
        if (!Base64Url.decode(Bytes.between(dot1 + 1, dot2), PAYLOAD)) return Token.Invalid.INVALID;
        return switch (JsonReader.read(PAYLOAD.contents(), EXP, first, second)) {
            case Document.Object(Member.Number exp, _, _) when !(Uring.wallClockNanos() / 1e9 < JsonReader.toDouble(exp)) ->
                    Token.Invalid.INVALID;
            case Document.Object(Member.Number _, Member a, Member b) -> new Token.Valid(a, b);
            case Document.Object _, Document.NotAnObject _, Document.Invalid _ -> Token.Invalid.INVALID;
        };
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
