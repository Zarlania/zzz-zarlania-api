package com.zarlania.api.features;

/**
 * The code registry of feature toggles: adding a constant creates the toggle (synced to the DB at
 * startup, default off); removing the constant deletes it and its overrides on the next deploy.
 * Each constant carries its {@link #toggleName() toggle name} — a kebab-case string that is the
 * toggle's name in the DB and the admin API — and a human-readable {@link #description()} that is
 * persisted and surfaced to operators so they (and code reviewers deciding whether an existing
 * toggle already covers a change) can tell what the toggle gates.
 */
public enum Feature {

  /**
   * Permanent no-op toggle for smoke-testing the toggle mechanism end to end in production, and a
   * stable constant for tests. It gates no code path.
   */
  FEATURE_SERVICE_CANARY(
      "feature-service-canary",
      "Permanent no-op toggle for smoke-testing the feature-toggle mechanism in production. "
          + "Gates no real feature."),

  /** Gates accepting and storing a bcrypt password credential when an account is created. */
  PASSWORD_ACCOUNTS(
      "password-accounts",
      "Accept and store a bcrypt password credential when an account is created.");

  private final String toggleName;
  private final String description;

  Feature(String toggleName, String description) {
    this.toggleName = toggleName;
    this.description = description;
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

  /**
   * The toggle's human-readable description, persisted to the DB and returned by the admin API.
   *
   * @return the description of what this toggle gates
   */
  public String description() {
    return description;
  }
}
