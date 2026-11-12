package feed;

import server.Ascii;
import server.JsonReader;
import server.JsonReader.Member;
import server.JwtVerifier;
import server.NativeBuffer;
import server.Request.Authorization;
import server.Token;

/** Who a write comes from: the token's sub (a positive integer string, the user id) and
 * username. */
final class Authentication {
    private Authentication() {}

    private static final byte[] SUB = Ascii.bytes("sub");
    private static final byte[] USERNAME = Ascii.bytes("username");
    // Scratch: the claim being decoded, and then the username of the token last checked.
    private static final NativeBuffer FIELD = new NativeBuffer(256);

    static AuthResult of(Authorization authorization) {
        return switch (JwtVerifier.verify(authorization, SUB, USERNAME)) {
            case Token.Missing _ -> AuthResult.MissingToken.MISSING_TOKEN;
            case Token.Invalid _ -> AuthResult.InvalidToken.INVALID_TOKEN;
            case Token.Valid(Member.Text sub, Member.Text username) -> claims(sub, username);
            case Token.Valid _ -> AuthResult.InvalidPayload.INVALID_PAYLOAD;
        };
    }

    private static AuthResult claims(Member.Text sub, Member.Text username) {
        FIELD.clear();
        JsonReader.decode(sub, FIELD);
        long userId = server.Decimal.parsePositive(FIELD.contents());
        if (userId <= 0) return AuthResult.InvalidPayload.INVALID_PAYLOAD;
        FIELD.clear();
        JsonReader.decode(username, FIELD);
        return new AuthResult.Authenticated(userId, FIELD.contents());
    }
}
