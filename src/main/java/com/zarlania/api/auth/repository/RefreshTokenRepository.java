package com.zarlania.api.auth.repository;

import com.zarlania.api.auth.entity.RefreshTokenEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
