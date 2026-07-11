package com.zarlania.api.auth.exception;

/**
 * A presented refresh token is unknown, expired, revoked, or already consumed. Carries no detail
 * about which, so responses cannot be used to probe token state; mapped to 401.
 */
public class InvalidRefreshTokenException extends RuntimeException {

  /** Creates the exception with a fixed, token-free message. */
  public InvalidRefreshTokenException() {
    super("invalid refresh token");
  }
}
