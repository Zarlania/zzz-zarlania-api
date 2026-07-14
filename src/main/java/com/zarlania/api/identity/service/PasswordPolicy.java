package com.zarlania.api.identity.service;

import org.springframework.stereotype.Component;

/**
 * Validates a raw password against the account-creation policy at the boundary, failing fast. Used
 * only when the {@code PASSWORD_ACCOUNTS} toggle is enabled. Messages describe the rule violated
 * and never echo the supplied password. A violation is an {@link IllegalArgumentException}, which
 * the global {@code ApiExceptionHandler} maps to 400.
 */
@Component
public class PasswordPolicy {

  private static final int MIN_LENGTH = 8;
  private static final int MAX_LENGTH = 128;

  /**
   * Validates the given raw password.
   *
   * @param rawPassword the caller-supplied password
   * @throws IllegalArgumentException if the password is null, blank, fewer than 8 characters or
   *     more than 128 characters (Unicode code points — Argon2 does not truncate, so this ceiling
   *     is a policy choice rather than an algorithm limitation), or missing an uppercase letter,
   *     lowercase letter, digit, or symbol
   */
  public void validate(String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new IllegalArgumentException("password is required");
    }
    // Count Unicode code points, not UTF-16 code units, so a password made of supplementary
    // characters (e.g. emoji, which are surrogate pairs) is measured by how many characters it
    // actually has rather than counting each astral character twice.
    if (rawPassword.codePointCount(0, rawPassword.length()) < MIN_LENGTH) {
      throw new IllegalArgumentException("password must be at least " + MIN_LENGTH + " characters");
    }
    if (rawPassword.codePointCount(0, rawPassword.length()) > MAX_LENGTH) {
      throw new IllegalArgumentException("password must be at most " + MAX_LENGTH + " characters");
    }
    boolean hasUpper = false;
    boolean hasLower = false;
    boolean hasDigit = false;
    boolean hasSymbol = false;
    for (int i = 0; i < rawPassword.length(); ) {
      int codePoint = rawPassword.codePointAt(i);
      if (Character.isUpperCase(codePoint)) {
        hasUpper = true;
      }
      if (Character.isLowerCase(codePoint)) {
        hasLower = true;
      }
      if (Character.isDigit(codePoint)) {
        hasDigit = true;
      }
      if (!Character.isLetterOrDigit(codePoint)) {
        hasSymbol = true;
      }
      i += Character.charCount(codePoint);
    }
    if (!hasUpper || !hasLower || !hasDigit || !hasSymbol) {
      throw new IllegalArgumentException(
          "password must contain an uppercase letter, a lowercase letter, a digit, and a symbol");
    }
  }
}
