package com.zarlania.api.auth.repository;

import com.zarlania.api.auth.entity.RefreshTokenEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link RefreshTokenEntity}. Internal to the {@code auth} domain; the
 * single storage seam a future Redis/Postgres-backed token store replaces.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

  /**
   * Finds a token row by the SHA-256 hex hash of a presented raw token.
   *
   * @param tokenHash the 64-char hex hash
   * @return the row, if one exists
   */
  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  /**
   * Lists every rotation in a token family — used to revoke the whole chain when a consumed token
   * is replayed.
   *
   * @param familyId the family id shared across rotations
   * @return all rows in the family
   */
  List<RefreshTokenEntity> findByFamilyId(UUID familyId);

  /**
   * Atomically consumes a live token row: sets {@code consumedAt} only if the row is not already
   * consumed or revoked. The single-use guarantee under concurrency — exactly one caller wins;
   * losers observe 0 rows updated and must take the replay path.
   *
   * @param id the token row id
   * @param now the consumption timestamp
   * @return 1 if this call consumed the row, 0 if it was already consumed/revoked
   */
  @Modifying
  @Query(
      "update RefreshTokenEntity t set t.consumedAt = :now "
          + "where t.id = :id and t.consumedAt is null and t.revokedAt is null")
  int consumeIfLive(@Param("id") UUID id, @Param("now") Instant now);
}
