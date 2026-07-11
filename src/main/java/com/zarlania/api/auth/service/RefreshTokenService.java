package com.zarlania.api.auth.service;

import com.zarlania.api.auth.config.AuthTokenProperties;
import com.zarlania.api.auth.entity.RefreshTokenEntity;
import com.zarlania.api.auth.exception.InvalidRefreshTokenException;
import com.zarlania.api.auth.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the refresh-token lifecycle: mint (raw returned once, SHA-256 hash stored), rotate
 * (single-use; each refresh consumes the old token and issues a successor in the same family),
 * reuse detection (replaying a consumed token revokes the entire family — the stolen-token
 * tripwire), and revoke (logout; idempotent). Raw tokens are 256-bit {@link SecureRandom} values
 * and are never stored or logged.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private static final int RAW_TOKEN_BYTES = 32;

  private final RefreshTokenRepository refreshTokenRepository;
  private final AuthTokenProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * The outcome of a successful rotation: the successor raw token plus the user and organization
   * the family is scoped to (for minting the paired access token).
   *
   * @param newRawToken the successor refresh token, to return to the client once
   * @param userId the owning user
   * @param organizationId the organization the family is scoped to
   */
  public record RefreshRotation(String newRawToken, UUID userId, UUID organizationId) {}

  /**
   * Mints a refresh token in a new family.
   *
   * @param userId the owning user
   * @param organizationId the single organization the token is scoped to
   * @return the raw token — returned to the client exactly once, never stored
   */
  @Transactional
  public String mint(UUID userId, UUID organizationId) {
    return storeNewToken(userId, organizationId, UUID.randomUUID());
  }

  /**
   * Rotates a refresh token: consumes the presented one and issues a successor in the same family.
   * Consumption is atomic at the database layer, so of concurrent presentations of the same token
   * exactly one wins; every other caller — including a replay of an already-consumed or revoked
   * token — revokes the whole family before rejecting.
   *
   * @param rawToken the presented raw refresh token
   * @return the rotation result
   * @throws InvalidRefreshTokenException if the token is unknown, expired, revoked, or already
   *     consumed
   */
  @Transactional
  public RefreshRotation rotate(String rawToken) {
    RefreshTokenEntity row =
        refreshTokenRepository
            .findByTokenHash(sha256Hex(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);
    if (row.getConsumedAt() != null || row.getRevokedAt() != null) {
      revokeFamily(row.getFamilyId());
      throw new InvalidRefreshTokenException();
    }
    if (row.getExpiresAt().isBefore(Instant.now())) {
      throw new InvalidRefreshTokenException();
    }
    if (refreshTokenRepository.consumeIfLive(row.getId(), Instant.now()) == 0) {
      // Lost a race to a concurrent presentation of the same token: treat as replay.
      revokeFamily(row.getFamilyId());
      throw new InvalidRefreshTokenException();
    }
    String successor = storeNewToken(row.getUserId(), row.getOrganizationId(), row.getFamilyId());
    return new RefreshRotation(successor, row.getUserId(), row.getOrganizationId());
  }

  /**
   * Revokes a refresh token (logout). Idempotent: unknown or already-revoked tokens are a no-op so
   * logout never fails.
   *
   * @param rawToken the presented raw refresh token
   */
  @Transactional
  public void revoke(String rawToken) {
    refreshTokenRepository
        .findByTokenHash(sha256Hex(rawToken))
        .filter(row -> row.getRevokedAt() == null)
        .ifPresent(
            row -> {
              row.setRevokedAt(Instant.now());
              refreshTokenRepository.save(row);
            });
  }

  private String storeNewToken(UUID userId, UUID organizationId, UUID familyId) {
    byte[] bytes = new byte[RAW_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = Instant.now();
    RefreshTokenEntity row = new RefreshTokenEntity();
    row.setUserId(userId);
    row.setOrganizationId(organizationId);
    row.setTokenHash(sha256Hex(rawToken));
    row.setFamilyId(familyId);
    row.setIssuedAt(now);
    row.setExpiresAt(now.plus(properties.refreshTokenTtl()));
    refreshTokenRepository.saveAndFlush(row);
    return rawToken;
  }

  private void revokeFamily(UUID familyId) {
    Instant now = Instant.now();
    refreshTokenRepository
        .findByFamilyId(familyId)
        .forEach(
            member -> {
              if (member.getRevokedAt() == null) {
                member.setRevokedAt(now);
              }
            });
  }

  private static String sha256Hex(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by the JVM spec", ex);
    }
  }
}
