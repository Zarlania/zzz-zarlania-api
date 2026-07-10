package com.zarlania.api.features.dto;

import java.util.List;

/**
 * Immutable view of a feature toggle's full state for use across the domain boundary and in admin
 * API responses. This DTO — not the JPA {@code FeatureToggleEntity} — is the type passed throughout
 * the application.
 *
 * @param name the toggle's registered (enum-constant) name
 * @param percentage the global percentage: 0 = off, 100 = on, in between = partial
 * @param organizationOverrides per-organization overrides; each wins unconditionally over the
 *     global percentage for its organization
 */
public record FeatureToggle(
    String name, int percentage, List<FeatureToggleOrganizationOverride> organizationOverrides) {

  /**
   * Stores an immutable copy of the overrides list.
   *
   * @param name the toggle name
   * @param percentage the global percentage
   * @param organizationOverrides the per-organization overrides
   */
  public FeatureToggle {
    organizationOverrides = List.copyOf(organizationOverrides);
  }
}
