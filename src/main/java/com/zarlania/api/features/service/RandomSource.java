package com.zarlania.api.features.service;

/**
 * Source of randomness for partial-rollout coin flips. An interface so tests can inject
 * deterministic values; production wires {@link java.security.SecureRandom} (see {@code
 * FeaturesConfig}).
 */
@FunctionalInterface
public interface RandomSource {

  /**
   * Returns the next random double.
   *
   * @return a uniformly distributed value in {@code [0.0, 1.0)}
   */
  double nextDouble();
}
