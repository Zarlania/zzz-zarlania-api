package com.zarlania.api.identity.service;

import java.nio.charset.StandardCharsets;
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
  private static final int MAX_BYTES = 72;

  /**
   * Validates the given raw password.
   *
   * @param rawPassword the caller-supplied password
   * @throws IllegalArgumentException if the password is null, blank, shorter than 8 characters,
   *     longer than 72 bytes, or missing an uppercase letter, lowercase letter, digit, or symbol
   */
  public void validate(String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new IllegalArgumentException("password is required");
    }
    if (rawPassword.length() < MIN_LENGTH) {
      throw new IllegalArgumentException("password must be at least " + MIN_LENGTH + " characters");
    }
    if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IllegalArgumentException("password must be at most " + MAX_BYTES + " bytes");
    }
    boolean hasUpper = false;
    boolean hasLower = false;
    boolean hasDigit = false;
    boolean hasSymbol = false;
    for (int i = 0; i < rawPassword.length(); i++) {
      char c = rawPassword.charAt(i);
      if (Character.isUpperCase(c)) {
        hasUpper = true;
      } else if (Character.isLowerCase(c)) {
        hasLower = true;
      } else if (Character.isDigit(c)) {
        hasDigit = true;
      } else {
        hasSymbol = true;
      }
    }
    if (!hasUpper || !hasLower || !hasDigit || !hasSymbol) {
      throw new IllegalArgumentException(
          "password must contain an uppercase letter, a lowercase letter, a digit, and a symbol");
    }
  }
}
