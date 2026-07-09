package com.zarlania.api.features.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zarlania.api.features.Feature;
import com.zarlania.api.features.config.FeatureCacheProperties;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * In-process {@link TraceDecisionCache} bounded by TTL and maximum size. Entries are a few hundred
 * bytes, so at the default bounds the cache costs a few MB; the size bound (not just the TTL)
 * matters because trace ids are client-controllable.
 */
@Component
public class CaffeineTraceDecisionCache implements TraceDecisionCache {

  private record Key(String traceId, Feature feature, UUID organizationId) {}

  private final Cache<Key, Boolean> cache;

  /**
   * Creates the cache with the configured bounds.
   *
   * @param properties the validated TTL and size bounds
   */
  public CaffeineTraceDecisionCache(FeatureCacheProperties properties) {
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.ttl())
            .maximumSize(properties.maxSize())
            .build();
  }

  @Override
  public Optional<Boolean> get(String traceId, Feature feature, UUID organizationId) {
    return Optional.ofNullable(cache.getIfPresent(new Key(traceId, feature, organizationId)));
  }

  @Override
  public void put(String traceId, Feature feature, UUID organizationId, boolean enabled) {
    cache.put(new Key(traceId, feature, organizationId), enabled);
  }
}
