package com.zarlania.api.features.controller;

import com.zarlania.api.features.dto.FeatureToggle;
import com.zarlania.api.features.dto.SetPercentageRequest;
import com.zarlania.api.features.service.FeatureToggleAdminService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin HTTP surface for feature-toggle state. Lives under {@code /api/admin/**}, which is excluded
 * from the public OpenAPI document (see {@code OpenApiVisibilityConfig}) and — like every endpoint
 * today — is not yet authenticated; real auth is a future repo-wide story. Toggles are
 * created/removed only via the {@code Feature} enum, so there are no POST/DELETE toggle routes.
 */
@RestController
@RequestMapping("/api/admin/feature-toggles")
@RequiredArgsConstructor
public class FeatureToggleAdminController {

  private final FeatureToggleAdminService adminService;

  /**
   * Lists every registered toggle with its overrides.
   *
   * @return all toggles
   */
  @GetMapping
  public List<FeatureToggle> list() {
    return adminService.list();
  }

  /**
   * Fetches one toggle.
   *
   * @param name the toggle's registered name
   * @return the toggle with its overrides
   */
  @GetMapping("/{name}")
  public FeatureToggle get(@PathVariable String name) {
    return adminService.get(name);
  }

  /**
   * Sets a toggle's global percentage: 0 = off, 100 = on, in between = partial rollout.
   *
   * @param name the toggle's registered name
   * @param request the validated percentage payload
   * @return the updated toggle
   */
  @PutMapping("/{name}")
  public FeatureToggle setPercentage(
      @PathVariable String name, @Valid @RequestBody SetPercentageRequest request) {
    return adminService.setPercentage(name, request.percentage());
  }

  /**
   * Creates or replaces an organization's override of a toggle.
   *
   * @param name the toggle's registered name
   * @param organizationId the organization the override applies to
   * @param request the validated percentage payload
   * @return the updated toggle
   */
  @PutMapping("/{name}/organizations/{organizationId}")
  public FeatureToggle setOrgOverride(
      @PathVariable String name,
      @PathVariable UUID organizationId,
      @Valid @RequestBody SetPercentageRequest request) {
    return adminService.setOrgOverride(name, organizationId, request.percentage());
  }

  /**
   * Removes an organization's override; the organization falls back to the global percentage.
   * Idempotent: removing an absent override succeeds.
   *
   * @param name the toggle's registered name
   * @param organizationId the organization whose override is removed
   */
  @DeleteMapping("/{name}/organizations/{organizationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeOrgOverride(@PathVariable String name, @PathVariable UUID organizationId) {
    adminService.removeOrgOverride(name, organizationId);
  }
}
