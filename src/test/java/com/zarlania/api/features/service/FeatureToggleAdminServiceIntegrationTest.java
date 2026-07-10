package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.features.dto.FeatureToggle;
import com.zarlania.api.features.dto.FeatureToggleOrganizationOverride;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.exception.FeatureToggleNotFoundException;
import com.zarlania.api.features.repository.FeatureToggleOrganizationOverrideRepository;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import com.zarlania.api.organizations.exception.OrganizationNotFoundException;
import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

// The H2 pin and between-test cleanup are inherited from AbstractIntegrationTest.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class FeatureToggleAdminServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private FeatureToggleRepository featureToggleRepository;

  @Autowired
  private FeatureToggleOrganizationOverrideRepository featureToggleOrganizationOverrideRepository;

  @Autowired private TestEntityManager entityManager;

  @Test
  void getReturnsToggleWithOverrides() {
    String name = saveToggle(30);
    UUID organizationId = seedOrganization();
    service().setOrganizationOverride(name, organizationId, 10);

    FeatureToggle toggle = service().get(name);
    assertThat(toggle.name()).isEqualTo(name);
    assertThat(toggle.percentage()).isEqualTo(30);
    assertThat(toggle.organizationOverrides())
        .containsExactly(new FeatureToggleOrganizationOverride(organizationId, 10));
  }

  @Test
  void getUnknownNameThrowsNotFound() {
    assertThatThrownBy(() -> service().get("NOPE"))
        .isInstanceOf(FeatureToggleNotFoundException.class);
  }

  @Test
  void listContainsSavedToggles() {
    String name = saveToggle(0);
    assertThat(service().list()).anySatisfy(t -> assertThat(t.name()).isEqualTo(name));
  }

  @Test
  void setPercentageUpdatesGlobalState() {
    String name = saveToggle(0);
    FeatureToggle updated = service().setPercentage(name, 100);
    assertThat(updated.percentage()).isEqualTo(100);
    assertThat(featureToggleRepository.findByName(name).orElseThrow().getPercentage())
        .isEqualTo(100);
  }

  @Test
  void setPercentageRejectsOutOfRangeValues() {
    String name = saveToggle(0);
    assertThatThrownBy(() -> service().setPercentage(name, 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service().setPercentage(name, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setOrganizationOverrideCreatesThenReplaces() {
    String name = saveToggle(0);
    UUID organizationId = seedOrganization();

    service().setOrganizationOverride(name, organizationId, 10);
    FeatureToggle updated = service().setOrganizationOverride(name, organizationId, 90);

    assertThat(updated.organizationOverrides())
        .containsExactly(new FeatureToggleOrganizationOverride(organizationId, 90));
  }

  @Test
  void setOrganizationOverrideForUnknownOrganizationThrowsOrganizationNotFound() {
    String name = saveToggle(0);
    assertThatThrownBy(() -> service().setOrganizationOverride(name, UUID.randomUUID(), 10))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  void removeOrganizationOverrideFallsBackToGlobal() {
    String name = saveToggle(70);
    UUID organizationId = seedOrganization();
    service().setOrganizationOverride(name, organizationId, 10);

    FeatureToggle updated = service().removeOrganizationOverride(name, organizationId);
    assertThat(updated.organizationOverrides()).isEmpty();
  }

  @Test
  void removeMissingOverrideIsIdempotent() {
    String name = saveToggle(70);
    FeatureToggle updated = service().removeOrganizationOverride(name, UUID.randomUUID());
    assertThat(updated.organizationOverrides()).isEmpty();
  }

  private FeatureToggleAdminService service() {
    return new FeatureToggleAdminService(
        featureToggleRepository,
        featureToggleOrganizationOverrideRepository,
        new FeatureToggleMapper());
  }

  private String saveToggle(int percentage) {
    String name = "ADM_" + UUID.randomUUID().toString().replace("-", "");
    FeatureToggleEntity toggle = new FeatureToggleEntity();
    toggle.setName(name);
    toggle.setPercentage(percentage);
    featureToggleRepository.saveAndFlush(toggle);
    return name;
  }

  private UUID seedOrganization() {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO organizations (id, name, type, created_at, updated_at) "
                + "VALUES (:id, :name, 'GENERAL', NOW(), NOW())")
        .setParameter("id", id)
        .setParameter("name", "org-" + id)
        .executeUpdate();
    return id;
  }
}
