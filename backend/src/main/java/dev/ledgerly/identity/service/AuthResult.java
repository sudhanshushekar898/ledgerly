package dev.ledgerly.identity.service;

/** Outcome of register, login and refresh: a new access token, a new raw refresh token, and the user. */
public record AuthResult(String accessToken, long expiresInSeconds, String refreshToken, long refreshMaxAgeSeconds,
                         UserSummary user) {
}
