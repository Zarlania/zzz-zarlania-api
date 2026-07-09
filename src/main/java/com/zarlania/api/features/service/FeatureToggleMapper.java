package com.zarlania.api.features.service;

import com.zarlania.api.features.dto.FeatureToggle;
import com.zarlania.api.features.dto.FeatureToggleOrgOverride;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
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
   * @return a DTO carrying the name, global percentage, and per-organization overrides
   */
  public FeatureToggle toDto(
      FeatureToggleEntity entity, List<FeatureToggleOrgOverrideEntity> overrides) {
    List<FeatureToggleOrgOverride> overrideDtos =
        overrides.stream()
            .map(
                override ->
                    new FeatureToggleOrgOverride(
                        override.getOrganizationId(), override.getPercentage()))
            .toList();
    return new FeatureToggle(entity.getName(), entity.getPercentage(), overrideDtos);
  }
}
