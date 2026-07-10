package com.zarlania.api.features.repository;

import com.zarlania.api.features.entity.FeatureToggleEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link FeatureToggleEntity}. Internal to the {@code features} domain. */
public interface FeatureToggleRepository extends JpaRepository<FeatureToggleEntity, UUID> {

  /**
   * Finds a toggle by its registered (enum-constant) name.
   *
   * @param name the toggle name
   * @return the toggle, if a row with that name exists
   */
  Optional<FeatureToggleEntity> findByName(String name);
}
