package com.zarlania.api.features.repository;

import com.zarlania.api.features.entity.FeatureToggleOrganizationOverrideEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link FeatureToggleOrganizationOverrideEntity}. Internal to the {@code
 * features} domain.
 */
public interface FeatureToggleOrganizationOverrideRepository
    extends JpaRepository<FeatureToggleOrganizationOverrideEntity, UUID> {

  /**
   * Finds the override a toggle has for one organization.
   *
   * @param toggleId the toggle's id
   * @param organizationId the organization's id
   * @return the override, if one exists
   */
  Optional<FeatureToggleOrganizationOverrideEntity> findByToggleIdAndOrganizationId(
      UUID toggleId, UUID organizationId);

  /**
   * Lists all of a toggle's organization overrides.
   *
   * @param toggleId the toggle's id
   * @return the overrides (empty if none)
   */
  List<FeatureToggleOrganizationOverrideEntity> findByToggleId(UUID toggleId);
}
