package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.config.FeatureCacheProperties;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
import com.zarlania.api.features.repository.FeatureToggleOrgOverrideRepository;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FeatureToggleServiceUnitTest {

  private static final Feature FEATURE = Feature.FEATURE_SERVICE_CANARY;

  @Mock private FeatureToggleRepository toggleRepository;
  @Mock private FeatureToggleOrgOverrideRepository overrideRepository;

  private String traceId;
  private double nextRandom;

  private FeatureToggleService service() {
    TraceDecisionCache cache =
        new CaffeineTraceDecisionCache(new FeatureCacheProperties(Duration.ofMinutes(10), 100));
    return new FeatureToggleService(
        toggleRepository,
        overrideRepository,
        cache,
        () -> Optional.ofNullable(traceId),
        () -> nextRandom);
  }

  private FeatureToggleEntity toggle(int percentage) {
    FeatureToggleEntity entity = new FeatureToggleEntity();
    entity.setName(FEATURE.name());
    entity.setPercentage(percentage);
    ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
    return entity;
  }

  private void stubToggle(int percentage) {
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(percentage)));
  }

  @Test
  void rejectsNullFeature() {
    assertThatThrownBy(() -> service().isEnabled(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroPercentIsOffWithoutDrawingRandomness() {
    stubToggle(0);
    nextRandom = 0.0; // even the luckiest draw must not matter
    assertThat(service().isEnabled(FEATURE)).isFalse();
  }

  @Test
  void hundredPercentIsOn() {
    stubToggle(100);
    nextRandom = 0.999_999; // even the unluckiest draw must not matter
    assertThat(service().isEnabled(FEATURE)).isTrue();
  }

  @Test
  void partialPercentageEnablesWhenDrawIsBelowThreshold() {
    stubToggle(10);
    nextRandom = 0.099;
    assertThat(service().isEnabled(FEATURE)).isTrue();
  }

  @Test
  void partialPercentageDisablesWhenDrawIsAtOrAboveThreshold() {
    stubToggle(10);
    nextRandom = 0.10;
    assertThat(service().isEnabled(FEATURE)).isFalse();
  }

  @Test
  void missingRowFailsSafeToOff() {
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.empty());
    assertThat(service().isEnabled(FEATURE)).isFalse();
  }

  @Test
  void orgOverrideBeatsGlobalUnconditionally() {
    FeatureToggleEntity entity = toggle(100);
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(entity));
    UUID orgId = UUID.randomUUID();
    FeatureToggleOrgOverrideEntity override = new FeatureToggleOrgOverrideEntity();
    override.setToggle(entity);
    override.setOrganizationId(orgId);
    override.setPercentage(0);
    when(overrideRepository.findByToggleIdAndOrganizationId(entity.getId(), orgId))
        .thenReturn(Optional.of(override));

    assertThat(service().isEnabled(FEATURE, orgId)).isFalse();
  }

  @Test
  void orgWithoutOverrideFallsBackToGlobal() {
    FeatureToggleEntity entity = toggle(100);
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(entity));
    UUID orgId = UUID.randomUUID();
    when(overrideRepository.findByToggleIdAndOrganizationId(entity.getId(), orgId))
        .thenReturn(Optional.empty());

    assertThat(service().isEnabled(FEATURE, orgId)).isTrue();
  }

  @Test
  void decisionIsPinnedToTraceEvenWhenStateChanges() {
    traceId = "trace-1";
    stubToggle(100);
    FeatureToggleService service = service();
    assertThat(service.isEnabled(FEATURE)).isTrue();

    // State flips to off, but the pinned decision must hold for the same trace.
    lenient().when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(0)));
    assertThat(service.isEnabled(FEATURE)).isTrue();
  }

  @Test
  void separateTracesReEvaluate() {
    traceId = "trace-1";
    stubToggle(100);
    FeatureToggleService service = service();
    assertThat(service.isEnabled(FEATURE)).isTrue();

    traceId = "trace-2";
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(0)));
    assertThat(service.isEnabled(FEATURE)).isFalse();
  }

  @Test
  void withoutTraceContextEveryCallReEvaluates() {
    traceId = null;
    stubToggle(100);
    FeatureToggleService service = service();
    assertThat(service.isEnabled(FEATURE)).isTrue();

    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(0)));
    assertThat(service.isEnabled(FEATURE)).isFalse();
  }

  @Test
  void globalCheckNeverTouchesOverrideRepository() {
    stubToggle(50);
    nextRandom = 0.9;
    service().isEnabled(FEATURE);
    verify(overrideRepository, never()).findByToggleIdAndOrganizationId(any(), any());
  }
}
