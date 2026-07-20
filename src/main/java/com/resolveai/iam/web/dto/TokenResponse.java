package com.resolveai.iam.web.dto;

/**
 * The login and refresh response.
 *
 * @param accessToken  short-lived JWT; the only credential any other endpoint accepts
 * @param refreshToken opaque, single-use, rotated on every exchange. Not a JWT: it carries
 *                     no claims, it is looked up server-side, and it can therefore actually
 *                     be revoked - which is the whole reason the pair exists
 * @param expiresIn    seconds until the access token expires
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user) {

    public static TokenResponse of(String accessToken, String refreshToken, long expiresInSeconds,
                                   UserResponse user) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, user);
    }
}
