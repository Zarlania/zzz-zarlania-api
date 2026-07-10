package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.features.Feature;
import com.zarlania.api.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Exercises the cache as it is wired in the running context (bean + bound FeatureCacheProperties),
// not a hand-constructed instance. The bean is an application-scoped singleton, so each test uses a
// fresh trace id to stay isolated from the others sharing it.
@SpringBootTest
class CaffeineTraceDecisionCacheIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TraceDecisionCache traceDecisionCache;

  @Test
  void missesWhenEmpty() {
    assertThat(traceDecisionCache.get(traceId(), Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }

  @Test
  void returnsPutDecisionForSameKey() {
    String traceId = traceId();
    traceDecisionCache.put(traceId, Feature.FEATURE_SERVICE_CANARY, null, true);
    assertThat(traceDecisionCache.get(traceId, Feature.FEATURE_SERVICE_CANARY, null))
        .contains(true);
  }

  @Test
  void distinguishesTraceIds() {
    String traceId = traceId();
    traceDecisionCache.put(traceId, Feature.FEATURE_SERVICE_CANARY, null, true);
    assertThat(traceDecisionCache.get(traceId(), Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }

  @Test
  void distinguishesOrganizations() {
    String traceId = traceId();
    UUID organizationA = UUID.randomUUID();
    UUID organizationB = UUID.randomUUID();
    traceDecisionCache.put(traceId, Feature.FEATURE_SERVICE_CANARY, organizationA, true);
    traceDecisionCache.put(traceId, Feature.FEATURE_SERVICE_CANARY, organizationB, false);
    assertThat(traceDecisionCache.get(traceId, Feature.FEATURE_SERVICE_CANARY, organizationA))
        .contains(true);
    assertThat(traceDecisionCache.get(traceId, Feature.FEATURE_SERVICE_CANARY, organizationB))
        .contains(false);
    assertThat(traceDecisionCache.get(traceId, Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }

  private static String traceId() {
    return "trace-" + UUID.randomUUID();
  }
}
