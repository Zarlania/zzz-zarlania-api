package com.zarlania.api.features.exception;

import lombok.Getter;

/** Thrown when an admin operation targets a feature-toggle name that is not registered. */
@Getter
public class FeatureToggleNotFoundException extends RuntimeException {

  /** The name that did not resolve to a feature toggle. */
  private final String name;

  private FeatureToggleNotFoundException(String name) {
    super("No feature toggle exists with the given name");
    this.name = name;
  }

  /**
   * Creates the exception for a missing toggle.
   *
   * @param name the name that did not resolve
   * @return an exception describing the miss
   */
  public static FeatureToggleNotFoundException forName(String name) {
    return new FeatureToggleNotFoundException(name);
  }
}
