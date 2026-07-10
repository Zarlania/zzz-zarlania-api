package com.zarlania.api.features;

/**
 * The code registry of feature toggles: adding a constant creates the toggle (synced to the DB at
 * startup, default off); removing the constant deletes it and its overrides on the next deploy.
 * Each constant carries its {@link #toggleName() toggle name} — a kebab-case string that is the
 * toggle's name in the DB and the admin API.
 */
public enum Feature {

  /**
   * Permanent no-op toggle for smoke-testing the toggle mechanism end to end in production, and a
   * stable constant for tests. It gates no code path.
   */
  FEATURE_SERVICE_CANARY("feature-service-canary");

  private final String toggleName;

  Feature(String toggleName) {
    this.toggleName = toggleName;
  }

  /**
   * The toggle's registered name — a kebab-case string used as the DB {@code name} and the admin
   * API identifier.
   *
   * @return the kebab-case toggle name
   */
  public String toggleName() {
    return toggleName;
  }
}
