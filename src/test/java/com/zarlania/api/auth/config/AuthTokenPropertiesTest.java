package com.zarlania.api.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Fail-fast validation of the auth token configuration (the CorsProperties pattern). */
class AuthTokenPropertiesTest {

  private static final String VALID_SECRET =
      "zarlania-test-only-signing-secret-zarlania-test-only-signing-secret";

  private static AuthTokenProperties.Jwt jwt(String secret, Duration ttl) {
    return new AuthTokenProperties.Jwt(secret, ttl);
  }

  @Test
  void acceptsValidConfiguration() {
    assertThatCode(
            () ->
                new AuthTokenProperties(
                    jwt(VALID_SECRET, Duration.ofMinutes(15)), Duration.ofDays(30)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingJwtBlock() {
    assertThatThrownBy(() -> new AuthTokenProperties(null, Duration.ofDays(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.auth.jwt");
  }

  @Test
  void rejectsBlankSecret() {
    assertThatThrownBy(() -> jwt("  ", Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ZARLANIA_AUTH_JWT_SIGNING_SECRET");
  }

  @Test
  void rejectsSecretShorterThan32Bytes() {
    assertThatThrownBy(() -> jwt("too-short-secret", Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void rejectsNonPositiveAccessTokenTtl() {
    assertThatThrownBy(() -> jwt(VALID_SECRET, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("access-token-ttl");
  }

  @Test
  void rejectsNonPositiveRefreshTokenTtl() {
    assertThatThrownBy(
            () ->
                new AuthTokenProperties(
                    jwt(VALID_SECRET, Duration.ofMinutes(15)), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("refresh-token-ttl");
  }
}
