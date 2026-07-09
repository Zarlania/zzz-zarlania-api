package com.zarlania.api.features.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

// The H2 pin and between-test cleanup are inherited from AbstractIntegrationTest.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class FeatureToggleRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private FeatureToggleRepository toggles;
  @Autowired private FeatureToggleOrgOverrideRepository overrides;
  @Autowired private TestEntityManager entityManager;

  private FeatureToggleEntity saveToggle(String name, int percentage) {
    FeatureToggleEntity toggle = new FeatureToggleEntity();
    toggle.setName(name);
    toggle.setPercentage(percentage);
    return toggles.saveAndFlush(toggle);
  }

  private UUID seedOrganization(String name) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO organizations (id, name, type, created_at, updated_at) "
                + "VALUES (:id, :name, 'GENERAL', NOW(), NOW())")
        .setParameter("id", id)
        .setParameter("name", name)
        .executeUpdate();
    return id;
  }

  private FeatureToggleOrgOverrideEntity newOverride(
      FeatureToggleEntity toggle, UUID organizationId, int percentage) {
    FeatureToggleOrgOverrideEntity override = new FeatureToggleOrgOverrideEntity();
    override.setToggle(toggle);
    override.setOrganizationId(organizationId);
    override.setPercentage(percentage);
    return override;
  }

  @Test
  void savingAssignsIdAndAuditTimestamps() {
    FeatureToggleEntity toggle = saveToggle("T_" + UUID.randomUUID(), 0);
    assertThat(toggle.getId()).isNotNull();
    assertThat(toggle.getCreatedAt()).isNotNull();
    assertThat(toggle.getUpdatedAt()).isNotNull();
  }

  @Test
  void findByNameReturnsSavedToggle() {
    String name = "T_" + UUID.randomUUID();
    saveToggle(name, 25);
    assertThat(toggles.findByName(name))
        .hasValueSatisfying(found -> assertThat(found.getPercentage()).isEqualTo(25));
  }

  @Test
  void duplicateNameIsRejectedByUniqueConstraint() {
    String name = "T_" + UUID.randomUUID();
    saveToggle(name, 0);
    assertThatThrownBy(() -> saveToggle(name, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_feature_toggles_name");
  }

  @Test
  void percentageOutsideRangeIsRejectedByCheckConstraint() {
    assertThatThrownBy(() -> saveToggle("T_" + UUID.randomUUID(), 101))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void overrideRoundTripsAndFindsByToggleAndOrganization() {
    FeatureToggleEntity toggle = saveToggle("T_" + UUID.randomUUID(), 0);
    UUID orgId = seedOrganization("org-" + UUID.randomUUID());
    overrides.saveAndFlush(newOverride(toggle, orgId, 10));

    assertThat(overrides.findByToggleIdAndOrganizationId(toggle.getId(), orgId))
        .hasValueSatisfying(found -> assertThat(found.getPercentage()).isEqualTo(10));
    assertThat(overrides.findByToggleId(toggle.getId())).hasSize(1);
  }

  @Test
  void overrideForUnknownOrganizationIsRejectedByForeignKey() {
    FeatureToggleEntity toggle = saveToggle("T_" + UUID.randomUUID(), 0);
    assertThatThrownBy(() -> overrides.saveAndFlush(newOverride(toggle, UUID.randomUUID(), 10)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_ft_org_overrides_organization");
  }

  @Test
  void duplicateOverridePerOrganizationIsRejectedByUniqueConstraint() {
    FeatureToggleEntity toggle = saveToggle("T_" + UUID.randomUUID(), 0);
    UUID orgId = seedOrganization("org-" + UUID.randomUUID());
    overrides.saveAndFlush(newOverride(toggle, orgId, 10));
    assertThatThrownBy(() -> overrides.saveAndFlush(newOverride(toggle, orgId, 20)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_ft_org_overrides_toggle_org");
  }

  @Test
  void deletingToggleCascadesToOverridesAtDbLevel() {
    FeatureToggleEntity toggle = saveToggle("T_" + UUID.randomUUID(), 0);
    UUID orgId = seedOrganization("org-" + UUID.randomUUID());
    overrides.saveAndFlush(newOverride(toggle, orgId, 10));
    entityManager.getEntityManager().clear();

    toggles.deleteById(toggle.getId());
    toggles.flush();

    assertThat(overrides.findByToggleId(toggle.getId())).isEmpty();
  }
}
