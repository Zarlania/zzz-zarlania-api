package com.zarlania.api.features.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound payload for setting a toggle's (or an override's) percentage, validated at the HTTP
 * boundary. The 0–100 range also exists as a domain invariant in {@code FeatureToggleAdminService}
 * and as a DB CHECK constraint (defense in depth).
 *
 * @param percentage the rollout percentage: 0 = off, 100 = on, in between = partial
 */
public record SetPercentageRequest(@NotNull @Min(0) @Max(100) Integer percentage) {}
