package com.zarlania.api.identity.repository;

import com.zarlania.api.identity.entity.PasswordCredentialEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link PasswordCredentialEntity}. Internal to the {@code identity} domain.
 * {@link #findByUserId(UUID)} is the lookup seam future authentication will use to verify a
 * credential.
 */
public interface PasswordCredentialRepository
    extends JpaRepository<PasswordCredentialEntity, UUID> {

  /**
   * Finds a user's password credential.
   *
   * @param userId the owning user's id
   * @return the credential, if one exists for that user
   */
  Optional<PasswordCredentialEntity> findByUserId(UUID userId);
}
