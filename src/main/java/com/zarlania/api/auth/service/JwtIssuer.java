package com.zarlania.api.auth.service;

import com.zarlania.api.auth.config.AuthTokenProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints signed HS256 access tokens. Every token is scoped to exactly one organization via the
 * {@code org} claim — one token, one organization, never a re-scope (the auth ADR's core rule).
 * Parameterized by subject and organization so future issuance paths (service tokens with {@code
 * token_use=service}, OAuth-obtained user tokens) reuse it additively.
 */
@Service
@RequiredArgsConstructor
public class JwtIssuer {

  /** The {@code iss} claim stamped into every token. */
  static final String ISSUER = "zarlania-api";

  /** The {@code token_use} claim value for user-held tokens. */
  static final String TOKEN_USE_USER = "user";

  private final JwtEncoder jwtEncoder;
  private final AuthTokenProperties properties;

  /**
   * Mints an access token for a user, scoped to one organization.
   *
   * @param userId the authenticated user's id (the {@code sub} claim)
   * @param organizationId the single organization this token grants access within (the {@code org}
   *     claim)
   * @return the signed compact JWT
   */
  public String issueUserToken(UUID userId, UUID organizationId) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(userId.toString())
            .claim("org", organizationId.toString())
            .claim("token_use", TOKEN_USE_USER)
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.jwt().accessTokenTtl()))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /**
   * The configured access-token lifetime, for the {@code expiresInSeconds} response field.
   *
   * @return the access-token TTL in whole seconds
   */
  public long accessTokenTtlSeconds() {
    return properties.jwt().accessTokenTtl().toSeconds();
  }
}
