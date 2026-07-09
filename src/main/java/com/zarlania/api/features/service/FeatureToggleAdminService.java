package com.zarlania.api.features.service;

import com.zarlania.api.features.dto.FeatureToggle;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
import com.zarlania.api.features.exception.FeatureToggleNotFoundException;
import com.zarlania.api.features.repository.FeatureToggleOrgOverrideRepository;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import com.zarlania.api.organizations.exception.OrganizationNotFoundException;
import com.zarlania.api.persistence.ConstraintViolations;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administers feature-toggle state: global percentages and per-organization overrides. Toggles
 * themselves are created and removed only by the code registry (see {@code
 * FeatureToggleSynchronizer}) — this service can never add or delete a toggle. Organization
 * existence is enforced by the DB foreign key and translated to the {@code organizations} domain
 * exception (importing another domain's exception is permitted under ADR-0011).
 */
@Service
@RequiredArgsConstructor
public class FeatureToggleAdminService {

  /** Name of the override→organization FK constraint in {@code V4__...sql}. */
  private static final String ORGANIZATION_FK_CONSTRAINT = "fk_ft_org_overrides_organization";

  private final FeatureToggleRepository toggleRepository;
  private final FeatureToggleOrgOverrideRepository overrideRepository;
  private final FeatureToggleMapper mapper;

  /**
   * Lists every registered toggle with its overrides.
   *
   * @return all toggles
   */
  @Transactional(readOnly = true)
  public List<FeatureToggle> list() {
    return toggleRepository.findAll().stream().map(this::toDto).toList();
  }

  /**
   * Fetches one toggle by name.
   *
   * @param name the toggle's registered name
   * @return the toggle with its overrides
   * @throws FeatureToggleNotFoundException if no toggle has that name
   */
  @Transactional(readOnly = true)
  public FeatureToggle get(String name) {
    return toDto(requireToggle(name));
  }

  /**
   * Sets a toggle's global percentage (0 = off, 100 = on, in between = partial).
   *
   * @param name the toggle's registered name
   * @param percentage the new global percentage
   * @return the updated toggle
   * @throws FeatureToggleNotFoundException if no toggle has that name
   * @throws IllegalArgumentException if the percentage is outside 0–100
   */
  @Transactional
  public FeatureToggle setPercentage(String name, int percentage) {
    requireValidPercentage(percentage);
    FeatureToggleEntity toggle = requireToggle(name);
    toggle.setPercentage(percentage);
    return toDto(toggleRepository.saveAndFlush(toggle));
  }

  /**
   * Creates or replaces an organization's override of a toggle.
   *
   * @param name the toggle's registered name
   * @param organizationId the organization the override applies to
   * @param percentage the override percentage
   * @return the updated toggle
   * @throws FeatureToggleNotFoundException if no toggle has that name
   * @throws OrganizationNotFoundException if no organization has that id
   * @throws IllegalArgumentException if the percentage is outside 0–100 or the id is null
   */
  @Transactional
  public FeatureToggle setOrgOverride(String name, UUID organizationId, int percentage) {
    requireValidPercentage(percentage);
    requireNonNull(organizationId, "organizationId");
    FeatureToggleEntity toggle = requireToggle(name);
    FeatureToggleOrgOverrideEntity override =
        overrideRepository
            .findByToggleIdAndOrganizationId(toggle.getId(), organizationId)
            .orElseGet(
                () -> {
                  FeatureToggleOrgOverrideEntity created = new FeatureToggleOrgOverrideEntity();
                  created.setToggle(toggle);
                  created.setOrganizationId(organizationId);
                  return created;
                });
    override.setPercentage(percentage);
    try {
      // saveAndFlush forces the INSERT now so an unknown organization surfaces here as the FK
      // violation and is reported as the domain exception rather than a raw persistence error.
      overrideRepository.saveAndFlush(override);
    } catch (DataIntegrityViolationException ex) {
      if (ConstraintViolations.matches(ex, ORGANIZATION_FK_CONSTRAINT)) {
        throw OrganizationNotFoundException.forId(organizationId);
      }
      throw ex;
    }
    return toDto(toggle);
  }

  /**
   * Removes an organization's override so the organization falls back to the global percentage.
   * Removing an override that does not exist is a no-op (idempotent delete).
   *
   * @param name the toggle's registered name
   * @param organizationId the organization whose override is removed
   * @return the updated toggle
   * @throws FeatureToggleNotFoundException if no toggle has that name
   * @throws IllegalArgumentException if the id is null
   */
  @Transactional
  public FeatureToggle removeOrgOverride(String name, UUID organizationId) {
    requireNonNull(organizationId, "organizationId");
    FeatureToggleEntity toggle = requireToggle(name);
    overrideRepository
        .findByToggleIdAndOrganizationId(toggle.getId(), organizationId)
        .ifPresent(overrideRepository::delete);
    overrideRepository.flush();
    return toDto(toggle);
  }

  private FeatureToggleEntity requireToggle(String name) {
    return toggleRepository
        .findByName(name)
        .orElseThrow(() -> FeatureToggleNotFoundException.forName(name));
  }

  private FeatureToggle toDto(FeatureToggleEntity toggle) {
    return mapper.toDto(toggle, overrideRepository.findByToggleId(toggle.getId()));
  }

  private static void requireValidPercentage(int percentage) {
    if (percentage < 0 || percentage > 100) {
      throw new IllegalArgumentException("percentage must be between 0 and 100");
    }
  }

  private static void requireNonNull(UUID value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
  }
}
