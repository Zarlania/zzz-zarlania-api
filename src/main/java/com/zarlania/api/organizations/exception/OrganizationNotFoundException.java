package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when an operation targets an organization id that does not exist. */
@Getter
public class OrganizationNotFoundException extends RuntimeException {

  /** The id that did not resolve to an organization. */
  private final UUID organizationId;

  private OrganizationNotFoundException(UUID organizationId, Throwable cause) {
    super("No organization exists with the given id", cause);
    this.organizationId = organizationId;
  }

  /**
   * Creates the exception for a missing organization.
   *
   * @param organizationId the id that did not resolve
   * @return an exception describing the miss
   */
  public static OrganizationNotFoundException forId(UUID organizationId) {
    return new OrganizationNotFoundException(organizationId, null);
  }

  /**
   * Creates the exception for a missing organization, preserving the originating failure (for
   * example a foreign-key {@code DataIntegrityViolationException} translated at a domain boundary).
   *
   * @param organizationId the id that did not resolve
   * @param cause the underlying failure that revealed the missing organization
   * @return an exception describing the miss, chained to its cause
   */
  public static OrganizationNotFoundException forId(UUID organizationId, Throwable cause) {
    return new OrganizationNotFoundException(organizationId, cause);
  }
}
