package com.zarlania.api.features.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds for the trace-decision cache. Bound from {@code zarlania.features.cache.*}; invalid values
 * are rejected at bind time so a misconfiguration fails startup (same pattern as {@code
 * CorsProperties}).
 *
 * @param ttl how long a pinned decision lives after being written; must be positive
 * @param maxSize maximum number of cached decisions (the key is client-controllable, so the cache
 *     must be size-bounded, not just TTL-bounded); must be positive
 */
@ConfigurationProperties(prefix = "zarlania.features.cache")
public record FeatureCacheProperties(Duration ttl, long maxSize) {

  /**
   * Validates the configured bounds.
   *
   * @param ttl the decision time-to-live
   * @param maxSize the maximum entry count
   * @throws IllegalArgumentException if the ttl is null/non-positive or maxSize is non-positive
   */
  public FeatureCacheProperties {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("zarlania.features.cache.ttl must be a positive duration");
    }
    if (maxSize <= 0) {
      throw new IllegalArgumentException("zarlania.features.cache.max-size must be positive");
    }
  }
}
