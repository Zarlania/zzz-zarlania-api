package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.config.FeatureCacheProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaffeineTraceDecisionCacheTest {

  private static CaffeineTraceDecisionCache cache() {
    return new CaffeineTraceDecisionCache(new FeatureCacheProperties(Duration.ofMinutes(10), 100));
  }

  @Test
  void missesWhenEmpty() {
    assertThat(cache().get("t1", Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }

  @Test
  void returnsPutDecisionForSameKey() {
    CaffeineTraceDecisionCache cache = cache();
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, null, true);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, null)).contains(true);
  }

  @Test
  void distinguishesTraceIds() {
    CaffeineTraceDecisionCache cache = cache();
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, null, true);
    assertThat(cache.get("t2", Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }

  @Test
  void distinguishesOrganizations() {
    CaffeineTraceDecisionCache cache = cache();
    UUID orgA = UUID.randomUUID();
    UUID orgB = UUID.randomUUID();
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, orgA, true);
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, orgB, false);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, orgA)).contains(true);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, orgB)).contains(false);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }
}
