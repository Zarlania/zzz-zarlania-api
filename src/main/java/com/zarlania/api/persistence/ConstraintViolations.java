package com.zarlania.api.persistence;

import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Identifies which database constraint a {@link DataIntegrityViolationException} violated by
 * matching the constraint name in the cause chain's messages. The constraint name appears in both
 * H2 and PostgreSQL messages, so matching it avoids catching unrelated integrity failures and
 * avoids depending on a JPA-provider-specific typed exception.
 */
public final class ConstraintViolations {

  private ConstraintViolations() {}

  /**
   * Reports whether the violation's cause chain names the given constraint (case-insensitive).
   *
   * @param ex the integrity violation to inspect
   * @param constraintName the lower-case constraint name as declared in the Flyway migration
   * @return true if any cause message contains the constraint name
   */
  public static boolean matches(DataIntegrityViolationException ex, String constraintName) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains(constraintName)) {
        return true;
      }
    }
    return false;
  }
}
