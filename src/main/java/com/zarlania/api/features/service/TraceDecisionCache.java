package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import java.util.Optional;
import java.util.UUID;

/**
 * Pins feature-toggle decisions to a trace id so every check within one request — or a chained hop
 * carrying the same trace header — sees the same answer. This interface is the seam for a future
 * shared implementation (Render Key Value / Valkey) when the service goes multi-instance; today's
 * implementation is in-process ({@link CaffeineTraceDecisionCache}).
 */
public interface TraceDecisionCache {

  /**
   * Looks up a pinned decision.
   *
   * @param traceId the request's trace id
   * @param feature the toggle being checked
   * @param organizationId the organization the check was scoped to, or null for a global check
   * @return the pinned decision, or empty if none is cached
   */
  Optional<Boolean> get(String traceId, Feature feature, UUID organizationId);

  /**
   * Pins a decision for the trace.
   *
   * @param traceId the request's trace id
   * @param feature the toggle that was checked
   * @param organizationId the organization the check was scoped to, or null for a global check
   * @param enabled the decision to pin
   */
  void put(String traceId, Feature feature, UUID organizationId, boolean enabled);
}
