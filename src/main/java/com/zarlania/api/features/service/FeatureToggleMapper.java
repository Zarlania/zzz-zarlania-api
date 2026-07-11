package com.zarlania.api.features.service;

import com.zarlania.api.features.dto.FeatureToggle;
import com.zarlania.api.features.dto.FeatureToggleOrganizationOverride;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrganizationOverrideEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps {@code features} entities to their DTOs for crossing the domain boundary. */
@Component
public class FeatureToggleMapper {

  /**
   * Maps a toggle and its overrides to the boundary DTO.
   *
   * @param entity the toggle entity
   * @param overrides the toggle's organization overrides
   * @return a DTO carrying the name, description, global percentage, and per-organization overrides
   */
  public FeatureToggle toDto(
      FeatureToggleEntity entity, List<FeatureToggleOrganizationOverrideEntity> overrides) {
    List<FeatureToggleOrganizationOverride> overrideDtos =
        overrides.stream()
            .map(
                override ->
                    new FeatureToggleOrganizationOverride(
                        override.getOrganizationId(), override.getPercentage()))
            .toList();
    return new FeatureToggle(
        entity.getName(), entity.getDescription(), entity.getPercentage(), overrideDtos);
  }
}
