package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrganizationOverrideEntity;
import com.zarlania.api.features.repository.FeatureToggleOrganizationOverrideRepository;
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

  private final FeatureToggleRepository featureToggleRepository;
  private final FeatureToggleOrganizationOverrideRepository
      featureToggleOrganizationOverrideRepository;
  private final TraceDecisionCache traceDecisionCache;
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
    Optional<FeatureToggleEntity> toggle = featureToggleRepository.findByName(feature.toggleName());
    Optional<FeatureToggleOrganizationOverrideEntity> override =
        findOverride(toggle, organizationId);
    // A no-override organization resolves the toggle's global decision, so it must share the global
    // trace pin (organization id null) rather than draw its own coin flip: without this, checking
    // the same toggle for several no-override organizations in one request could flip differently.
    UUID decisionOrganizationId = override.isPresent() ? organizationId : null;

    Optional<String> traceId = currentTraceId.get();
    if (traceId.isPresent()) {
      Optional<Boolean> pinned =
          traceDecisionCache.get(traceId.get(), feature, decisionOrganizationId);
      if (pinned.isPresent()) {
        return pinned.get();
      }
    }
    boolean enabled = evaluate(toggle, override);
    traceId.ifPresent(id -> traceDecisionCache.put(id, feature, decisionOrganizationId, enabled));
    return enabled;
  }

  private Optional<FeatureToggleOrganizationOverrideEntity> findOverride(
      Optional<FeatureToggleEntity> toggle, UUID organizationId) {
    if (toggle.isEmpty() || organizationId == null) {
      return Optional.empty();
    }
    return featureToggleOrganizationOverrideRepository.findByToggleIdAndOrganizationId(
        toggle.get().getId(), organizationId);
  }

  private boolean evaluate(
      Optional<FeatureToggleEntity> toggle,
      Optional<FeatureToggleOrganizationOverrideEntity> override) {
    if (toggle.isEmpty()) {
      return false;
    }
    int percentage =
        override
            .map(FeatureToggleOrganizationOverrideEntity::getPercentage)
            .orElseGet(toggle.get()::getPercentage);
    if (percentage <= 0) {
      return false;
    }
    if (percentage >= 100) {
      return true;
    }
    return randomSource.nextDouble() * 100.0 < percentage;
  }
}
