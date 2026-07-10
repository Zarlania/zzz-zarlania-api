package com.zarlania.api.features.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FeatureCachePropertiesTest {

  @Test
  void acceptsPositiveTtlAndSize() {
    FeatureCacheProperties properties = new FeatureCacheProperties(Duration.ofMinutes(10), 10_000);
    assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(10));
    assertThat(properties.maxSize()).isEqualTo(10_000);
  }

  @Test
  void rejectsNullTtl() {
    assertThatThrownBy(() -> new FeatureCacheProperties(null, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonPositiveTtl() {
    assertThatThrownBy(() -> new FeatureCacheProperties(Duration.ZERO, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonPositiveMaxSize() {
    assertThatThrownBy(() -> new FeatureCacheProperties(Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
