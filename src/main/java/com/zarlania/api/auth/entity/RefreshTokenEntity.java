package com.zarlania.api.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stored refresh token: the SHA-256 hash of the raw token (never the raw value), scoped to one
 * user and one organization (DB foreign keys, no JPA associations — ADR-0011). Rotations share a
 * {@code familyId} so reuse of a consumed token can revoke the whole chain. A token is live iff
 * {@code consumedAt} and {@code revokedAt} are both null and {@code expiresAt} is in the future.
 * Consumed/revoked rows are retained for reuse detection (purge deferred to issue #77).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor
public class RefreshTokenEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Setter
  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Setter
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Setter
  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Setter
  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Setter
  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Setter
  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Setter
  @Column(name = "revoked_at")
  private Instant revokedAt;
}
