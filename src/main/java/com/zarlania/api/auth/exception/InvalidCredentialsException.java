package com.zarlania.api.auth.exception;

/**
 * A login attempt failed — unknown email, missing credential, or wrong password. One exception for
 * all three so responses (and logs) cannot distinguish them (user-enumeration defense); mapped to a
 * generic 401.
 */
public class InvalidCredentialsException extends RuntimeException {

  /** Creates the exception with a fixed, account-free message. */
  public InvalidCredentialsException() {
    super("invalid credentials");
  }
}
