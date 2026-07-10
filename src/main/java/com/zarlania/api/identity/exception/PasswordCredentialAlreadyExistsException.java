package com.zarlania.api.identity.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when creating a password credential for a user who already has one. */
@Getter
public class PasswordCredentialAlreadyExistsException extends RuntimeException {

  /** The conflicting user id, kept as structured data and never embedded in the message. */
  private final UUID userId;

  private PasswordCredentialAlreadyExistsException(UUID userId, Throwable cause) {
    super("A password credential already exists for the given user", cause);
    this.userId = userId;
  }

  /**
   * Creates the exception for a user who already has a password credential, chaining the
   * persistence failure as the cause so its stack trace and DB context are preserved.
   *
   * @param userId the user already holding a credential
   * @param cause the underlying integrity violation
   * @return an exception describing the conflict
   */
  public static PasswordCredentialAlreadyExistsException forUserId(UUID userId, Throwable cause) {
    return new PasswordCredentialAlreadyExistsException(userId, cause);
  }
}
