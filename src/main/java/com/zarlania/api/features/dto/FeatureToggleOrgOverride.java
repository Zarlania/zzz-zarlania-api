package com.zarlania.api.features.dto;

import java.util.UUID;

/**
 * Immutable view of one organization's override of a feature toggle.
 *
 * @param organizationId the organization the override applies to
 * @param percentage the override percentage: 0 = off, 100 = on, in between = partial
 */
public record FeatureToggleOrgOverride(UUID organizationId, int percentage) {}
