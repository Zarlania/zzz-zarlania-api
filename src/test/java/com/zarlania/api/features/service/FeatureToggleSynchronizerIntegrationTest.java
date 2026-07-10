package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrganizationOverrideEntity;
import com.zarlania.api.features.repository.FeatureToggleOrganizationOverrideRepository;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import java.util.Set;
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
class FeatureToggleSynchronizerIntegrationTest extends AbstractIntegrationTest {

  @Autowired private FeatureToggleRepository featureToggleRepository;

  @Autowired
  private FeatureToggleOrganizationOverrideRepository featureToggleOrganizationOverrideRepository;

  @Autowired private TestEntityManager entityManager;

  @Test
  void insertsMissingTogglesDefaultOff() {
    String name = "SYNC_NEW_" + UUID.randomUUID().toString().replace("-", "");
    synchronizer().synchronize(Set.of(name));
    assertThat(featureToggleRepository.findByName(name))
        .hasValueSatisfying(created -> assertThat(created.getPercentage()).isZero());
  }

  @Test
  void keepsExistingPercentageForKnownToggles() {
    String name = "SYNC_KEEP_" + UUID.randomUUID().toString().replace("-", "");
    saveToggle(name, 42);
    synchronizer().synchronize(Set.of(name));
    assertThat(featureToggleRepository.findByName(name))
        .hasValueSatisfying(kept -> assertThat(kept.getPercentage()).isEqualTo(42));
  }

  @Test
  void deletesOrphanedTogglesAndTheirOverrides() {
    String keep = "SYNC_KEEP_" + UUID.randomUUID().toString().replace("-", "");
    String orphan = "SYNC_ORPHAN_" + UUID.randomUUID().toString().replace("-", "");
    saveToggle(keep, 0);
    FeatureToggleEntity orphanToggle = saveToggle(orphan, 50);

    UUID organizationId = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO organizations (id, name, type, created_at, updated_at) "
                + "VALUES (:id, :name, 'GENERAL', NOW(), NOW())")
        .setParameter("id", organizationId)
        .setParameter("name", "org-" + organizationId)
        .executeUpdate();
    FeatureToggleOrganizationOverrideEntity override =
        new FeatureToggleOrganizationOverrideEntity();
    override.setToggle(orphanToggle);
    override.setOrganizationId(organizationId);
    override.setPercentage(10);
    featureToggleOrganizationOverrideRepository.saveAndFlush(override);
    entityManager.getEntityManager().clear();

    synchronizer().synchronize(Set.of(keep));

    assertThat(featureToggleRepository.findByName(orphan)).isEmpty();
    assertThat(featureToggleRepository.findByName(keep)).isPresent();
    assertThat(featureToggleOrganizationOverrideRepository.findByToggleId(orphanToggle.getId()))
        .isEmpty();
  }

  private FeatureToggleSynchronizer synchronizer() {
    return new FeatureToggleSynchronizer(featureToggleRepository);
  }

  private FeatureToggleEntity saveToggle(String name, int percentage) {
    FeatureToggleEntity toggle = new FeatureToggleEntity();
    toggle.setName(name);
    toggle.setPercentage(percentage);
    return featureToggleRepository.saveAndFlush(toggle);
  }
}
