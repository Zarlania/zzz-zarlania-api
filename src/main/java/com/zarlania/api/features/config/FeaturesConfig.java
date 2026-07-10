package com.zarlania.api.features.config;

import com.zarlania.api.features.service.RandomSource;
import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the {@code features} domain: cache bounds and the production randomness source. */
@Configuration
@EnableConfigurationProperties(FeatureCacheProperties.class)
public class FeaturesConfig {

  /**
   * Production randomness for partial-rollout coin flips. {@link SecureRandom} rather than a seeded
   * PRNG: the rate is far too low for its cost to matter, and it keeps the FindSecBugs
   * predictable-randomness detector satisfied without an exclusion.
   *
   * @return the randomness source used by feature evaluation
   */
  @Bean
  public RandomSource featureRandomSource() {
    SecureRandom random = new SecureRandom();
    return random::nextDouble;
  }
}
