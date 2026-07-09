package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.repository.FeatureToggleOrgOverrideRepository;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import com.zarlania.api.web.CurrentTraceId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates feature toggles: the API feature code calls to gate a new code path. The effective
 * percentage is the organization's override when one exists, else the toggle's global percentage; 0
 * is off, 100 is on, and anything between is a coin flip. Decisions are pinned to the current trace
 * id (when one exists), so repeated checks within a request — or a chained hop carrying the trace
 * header — always agree. A toggle with no DB row (not yet synced, or removed) fails safe to off;
 * evaluation never throws for unknown organizations.
 */
@Service
@RequiredArgsConstructor
public class FeatureToggleService {

  private final FeatureToggleRepository toggleRepository;
  private final FeatureToggleOrgOverrideRepository overrideRepository;
  private final TraceDecisionCache decisionCache;
  private final CurrentTraceId currentTraceId;
  private final RandomSource randomSource;

  /**
   * Reports whether a feature is enabled for this request, using the toggle's global state.
   *
   * @param feature the toggle to check
   * @return true if the feature's path should run
   * @throws IllegalArgumentException if {@code feature} is null
   */
  @Transactional(readOnly = true)
  public boolean isEnabled(Feature feature) {
    return isEnabled(feature, null);
  }

  /**
   * Reports whether a feature is enabled for this request in the context of an organization. An
   * organization with an override uses it unconditionally; otherwise the global state applies.
   *
   * @param feature the toggle to check
   * @param organizationId the organization context, or null for a global check
   * @return true if the feature's path should run
   * @throws IllegalArgumentException if {@code feature} is null
   */
  @Transactional(readOnly = true)
  public boolean isEnabled(Feature feature, UUID organizationId) {
    if (feature == null) {
      throw new IllegalArgumentException("feature must not be null");
    }
    Optional<String> traceId = currentTraceId.get();
    if (traceId.isPresent()) {
      Optional<Boolean> pinned = decisionCache.get(traceId.get(), feature, organizationId);
      if (pinned.isPresent()) {
        return pinned.get();
      }
    }
    boolean enabled = evaluate(feature, organizationId);
    traceId.ifPresent(id -> decisionCache.put(id, feature, organizationId, enabled));
    return enabled;
  }

  private boolean evaluate(Feature feature, UUID organizationId) {
    Optional<FeatureToggleEntity> toggle = toggleRepository.findByName(feature.name());
    if (toggle.isEmpty()) {
      return false;
    }
    int percentage = effectivePercentage(toggle.get(), organizationId);
    if (percentage <= 0) {
      return false;
    }
    if (percentage >= 100) {
      return true;
    }
    return randomSource.nextDouble() * 100.0 < percentage;
  }

  private int effectivePercentage(FeatureToggleEntity toggle, UUID organizationId) {
    if (organizationId == null) {
      return toggle.getPercentage();
    }
    return overrideRepository
        .findByToggleIdAndOrganizationId(toggle.getId(), organizationId)
        .map(override -> override.getPercentage())
        .orElse(toggle.getPercentage());
  }
}
