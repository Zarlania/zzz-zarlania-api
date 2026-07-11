package com.zarlania.api.auth.dto;

/**
 * The token pair returned by login and refresh. Both tokens are scoped to a single organization
 * (the auth ADR's one-token-one-organization rule).
 *
 * @param accessToken the signed JWT access token
 * @param expiresInSeconds the access token's lifetime in seconds
 * @param refreshToken the raw refresh token — shown exactly once, only its hash is stored
 */
public record TokenResponse(String accessToken, long expiresInSeconds, String refreshToken) {}
