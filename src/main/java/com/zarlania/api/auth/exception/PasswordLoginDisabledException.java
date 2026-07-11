package com.zarlania.api.auth.exception;

/**
 * The {@code PASSWORD_LOGIN} toggle is off: the {@code /auth} surface does not exist yet for
 * callers. Mapped to 404 so the unreleased surface is indistinguishable from a nonexistent one.
 */
public class PasswordLoginDisabledException extends RuntimeException {

  /** Creates the exception. */
  public PasswordLoginDisabledException() {
    super("password login is disabled");
  }
}
