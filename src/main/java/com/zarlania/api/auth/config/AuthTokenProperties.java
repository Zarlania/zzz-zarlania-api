package com.zarlania.api.auth.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth token configuration, bound from {@code zarlania.auth.*}. Invalid or missing values are
 * rejected at bind time so a misconfiguration fails startup rather than issuing weak or
 * unverifiable tokens (the {@code CorsProperties} pattern).
 *
 * @param jwt the access-token (JWT) settings
 * @param refreshTokenTtl how long a refresh token lives; must be positive
 */
@ConfigurationProperties(prefix = "zarlania.auth")
public record AuthTokenProperties(Jwt jwt, Duration refreshTokenTtl) {

  /**
   * Validates the configured values.
   *
   * @param jwt the access-token settings; required
   * @param refreshTokenTtl the refresh-token time-to-live; must be positive
   * @throws IllegalArgumentException if any value is missing or invalid
   */
  public AuthTokenProperties {
    if (jwt == null) {
      throw new IllegalArgumentException("zarlania.auth.jwt must be configured");
    }
    if (refreshTokenTtl == null || refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
      throw new IllegalArgumentException(
          "zarlania.auth.refresh-token-ttl must be a positive duration");
    }
  }

  /**
   * JWT access-token settings, bound from {@code zarlania.auth.jwt.*}.
   *
   * @param signingSecret the HS256 signing secret; required, at least 32 bytes, sourced from the
   *     {@code ZARLANIA_AUTH_JWT_SIGNING_SECRET} environment variable
   * @param accessTokenTtl how long an access token lives; must be positive
   */
  public record Jwt(String signingSecret, Duration accessTokenTtl) {

    /**
     * Validates the configured values.
     *
     * @param signingSecret the HS256 signing secret
     * @param accessTokenTtl the access-token time-to-live
     * @throws IllegalArgumentException if the secret is missing/short or the ttl is not positive
     */
    public Jwt {
      if (signingSecret == null || signingSecret.isBlank()) {
        throw new IllegalArgumentException(
            "zarlania.auth.jwt.signing-secret must be set "
                + "(ZARLANIA_AUTH_JWT_SIGNING_SECRET environment variable)");
      }
      if (signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
        throw new IllegalArgumentException(
            "zarlania.auth.jwt.signing-secret must be at least 32 bytes");
      }
      if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
        throw new IllegalArgumentException(
            "zarlania.auth.jwt.access-token-ttl must be a positive duration");
      }
    }
  }
}
