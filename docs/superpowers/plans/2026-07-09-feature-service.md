# Feature Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `features` domain providing code-registered feature toggles (enum → DB, default off) flipped at runtime via admin APIs, with percentage/partial rollout, per-organization overrides, trace-pinned evaluation, and the admin surface hidden from public OpenAPI docs.

**Architecture:** New `com.zarlania.api.features` domain (entity/repository/service/dto/controller/exception sub-packages) synced from a `Feature` enum at startup by an `ApplicationRunner`. Evaluation reads the DB per first check and pins the decision in a bounded Caffeine cache keyed by `(traceId, feature, orgId)`; a `TraceIdFilter` in `web` supplies the trace id. Admin endpoints live under `/api/admin/feature-toggles`; a springdoc customizer strips `/api/admin/**` from the public doc.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Maven (`./mvnw`), Spring Data JPA + H2 + Flyway, springdoc 3.0.3, Lombok, Caffeine 3.2.4 (Boot-BOM-managed), JUnit 5 + Mockito + AssertJ + MockMvc.

**Spec:** `docs/superpowers/specs/2026-07-08-feature-service-design.md` — read it before starting.

## Global Constraints

- Branch: `feat/66-feature-service` (already exists). Every commit message references `#66`.
- Build/verify with the wrapper: `./mvnw`. Before every commit run `./mvnw spotless:apply` (formatting gate). The full gate suite (`Checkstyle`, `SpotBugs/FindSecBugs`, JaCoCo ≥ 80 %) runs via `./mvnw verify` — never silence a gate (no `@SuppressWarnings`, no excludes, no skipped tests).
- **Spring Boot 4 test annotation packages differ from Boot 3** — use exactly the imports shown in the code blocks (e.g. `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`). Do not "correct" them to `org.springframework.boot.test.autoconfigure.*`.
- Javadoc every public type/method including record `@param` tags (Checkstyle enforces; match the style of `Organization`/`CorsProperties`).
- ADR-0011: `features` main code never imports `organizations`/`users` entities or repositories. Importing another domain's *exception* is permitted (see `ApiExceptionHandler` javadoc). Test code may cross domains (see `IdentityControllerTest`).
- All tables get `created_at`/`updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL` via the `Auditable` base + named constraints (house rule).
- DTO carries the canonical name (`FeatureToggle`); entity is `FeatureToggleEntity`.
- Test naming: classes ending in `TransactionalTest` run in a separate serial suite — none of this plan's tests use that suffix.
- Test layering: controllers = e2e (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional`), services = unit (Mockito) + integration (`@DataJpaTest` slice), repositories = integration only.

---

### Task 1: Flyway V4 migration, feature-toggle entities, repositories

**Files:**
- Create: `src/main/resources/db/migration/V4__create_feature_toggles.sql`
- Create: `src/main/java/com/zarlania/api/features/entity/FeatureToggleEntity.java`
- Create: `src/main/java/com/zarlania/api/features/entity/FeatureToggleOrgOverrideEntity.java`
- Create: `src/main/java/com/zarlania/api/features/repository/FeatureToggleRepository.java`
- Create: `src/main/java/com/zarlania/api/features/repository/FeatureToggleOrgOverrideRepository.java`
- Test: `src/test/java/com/zarlania/api/features/repository/FeatureToggleRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `com.zarlania.api.persistence.Auditable`, `com.zarlania.api.persistence.JpaConfig` (existing).
- Produces: `FeatureToggleEntity` (getters `getId():UUID`, `getName():String`, `getPercentage():int`; setters `setName(String)`, `setPercentage(int)`), `FeatureToggleOrgOverrideEntity` (getters `getId()`, `getToggle():FeatureToggleEntity`, `getOrganizationId():UUID`, `getPercentage():int`; setters for toggle/organizationId/percentage), `FeatureToggleRepository.findByName(String):Optional<FeatureToggleEntity>`, `FeatureToggleOrgOverrideRepository.findByToggleIdAndOrganizationId(UUID,UUID):Optional<FeatureToggleOrgOverrideEntity>` and `findByToggleId(UUID):List<FeatureToggleOrgOverrideEntity>`. Constraint names `uq_feature_toggles_name`, `fk_ft_org_overrides_organization`, `uq_ft_org_overrides_toggle_org`.

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/V4__create_feature_toggles.sql`:

```sql
CREATE TABLE feature_toggles (
    id         UUID                        NOT NULL,
    name       VARCHAR(100)                NOT NULL,
    percentage INT                         NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_feature_toggles            PRIMARY KEY (id),
    CONSTRAINT uq_feature_toggles_name       UNIQUE (name),
    CONSTRAINT ck_feature_toggles_percentage CHECK (percentage BETWEEN 0 AND 100)
);

CREATE TABLE feature_toggle_org_overrides (
    id              UUID                        NOT NULL,
    toggle_id       UUID                        NOT NULL,
    organization_id UUID                        NOT NULL,
    percentage      INT                         NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_feature_toggle_org_overrides   PRIMARY KEY (id),
    CONSTRAINT fk_ft_org_overrides_toggle        FOREIGN KEY (toggle_id) REFERENCES feature_toggles (id) ON DELETE CASCADE,
    CONSTRAINT fk_ft_org_overrides_organization  FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_ft_org_overrides_toggle_org    UNIQUE (toggle_id, organization_id),
    CONSTRAINT ck_ft_org_overrides_percentage    CHECK (percentage BETWEEN 0 AND 100)
);

-- Supports listing a toggle's overrides without a full table scan as overrides grow.
CREATE INDEX idx_ft_org_overrides_toggle ON feature_toggle_org_overrides (toggle_id);
```

- [ ] **Step 2: Write the entities**

`src/main/java/com/zarlania/api/features/entity/FeatureToggleEntity.java`:

```java
package com.zarlania.api.features.entity;

import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A feature toggle's stored state. The toggle itself is registered in code (the {@code Feature}
 * enum); this row holds only its runtime percentage. Internal to the {@code features} domain;
 * crosses boundaries via the {@link com.zarlania.api.features.dto.FeatureToggle} DTO.
 */
@Entity
@Table(name = "feature_toggles")
@Getter
@NoArgsConstructor
public class FeatureToggleEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "name", nullable = false, length = 100)
  private String name;

  /** Rollout percentage: 0 = off, 100 = on, in between = partial (per-request coin flip). */
  @Setter
  @Column(name = "percentage", nullable = false)
  private int percentage;
}
```

`src/main/java/com/zarlania/api/features/entity/FeatureToggleOrgOverrideEntity.java`:

```java
package com.zarlania.api.features.entity;

import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A per-organization override of a toggle's percentage; when present it wins unconditionally over
 * the toggle's global percentage. References the organization by opaque id only — never the
 * {@code organizations} entity (ADR-0011); referential integrity is enforced by the DB foreign key.
 */
@Entity
@Table(name = "feature_toggle_org_overrides")
@Getter
@NoArgsConstructor
public class FeatureToggleOrgOverrideEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "toggle_id", nullable = false)
  private FeatureToggleEntity toggle;

  @Setter
  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  /** Override percentage: 0 = off, 100 = on, in between = partial (per-request coin flip). */
  @Setter
  @Column(name = "percentage", nullable = false)
  private int percentage;
}
```

- [ ] **Step 3: Write the repositories**

`src/main/java/com/zarlania/api/features/repository/FeatureToggleRepository.java`:

```java
package com.zarlania.api.features.repository;

import com.zarlania.api.features.entity.FeatureToggleEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link FeatureToggleEntity}. Internal to the {@code features} domain. */
public interface FeatureToggleRepository extends JpaRepository<FeatureToggleEntity, UUID> {

  /**
   * Finds a toggle by its registered (enum-constant) name.
   *
   * @param name the toggle name
   * @return the toggle, if a row with that name exists
   */
  Optional<FeatureToggleEntity> findByName(String name);
}
```

`src/main/java/com/zarlania/api/features/repository/FeatureToggleOrgOverrideRepository.java`:

```java
package com.zarlania.api.features.repository;

import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link FeatureToggleOrgOverrideEntity}. Internal to the {@code features}
 * domain.
 */
public interface FeatureToggleOrgOverrideRepository
    extends JpaRepository<FeatureToggleOrgOverrideEntity, UUID> {

  /**
   * Finds the override a toggle has for one organization.
   *
   * @param toggleId the toggle's id
   * @param organizationId the organization's id
   * @return the override, if one exists
   */
  Optional<FeatureToggleOrgOverrideEntity> findByToggleIdAndOrganizationId(
      UUID toggleId, UUID organizationId);

  /**
   * Lists all of a toggle's organization overrides.
   *
   * @param toggleId the toggle's id
   * @return the overrides (empty if none)
   */
  List<FeatureToggleOrgOverrideEntity> findByToggleId(UUID toggleId);
}
```

- [ ] **Step 4: Write the failing integration test**

`src/test/java/com/zarlania/api/features/repository/FeatureToggleRepositoryIntegrationTest.java`:

```java
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
    assertThatThrownBy(
            () -> overrides.saveAndFlush(newOverride(toggle, UUID.randomUUID(), 10)))
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
```

- [ ] **Step 5: Run the test to verify current state**

Run: `./mvnw test -Dtest=FeatureToggleRepositoryIntegrationTest`
Expected: PASS (schema + entities + repositories were written together in this task; failure means the migration and mappings disagree — fix until green).

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add src/main/resources/db/migration/V4__create_feature_toggles.sql src/main/java/com/zarlania/api/features src/test/java/com/zarlania/api/features
git commit -m "feat: feature-toggle schema, entities, repositories (#66)"
```

---

### Task 2: Extract shared `ConstraintViolations` helper (DRY refactor)

`OrganizationService` has a private `isConstraintViolation(...)`; Task 6 needs the identical logic. Extract it once.

**Files:**
- Create: `src/main/java/com/zarlania/api/persistence/ConstraintViolations.java`
- Modify: `src/main/java/com/zarlania/api/organizations/service/OrganizationService.java` (delete the private `isConstraintViolation` method at the bottom; replace its two call sites)
- Test: `src/test/java/com/zarlania/api/persistence/ConstraintViolationsTest.java`

**Interfaces:**
- Produces: `ConstraintViolations.matches(DataIntegrityViolationException ex, String constraintName): boolean` (static).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/zarlania/api/persistence/ConstraintViolationsTest.java`:

```java
package com.zarlania.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintViolationsTest {

  @Test
  void matchesWhenAnyCauseNamesTheConstraint() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper", new RuntimeException("violates UQ_FEATURE_TOGGLES_NAME"));
    assertThat(ConstraintViolations.matches(ex, "uq_feature_toggles_name")).isTrue();
  }

  @Test
  void doesNotMatchAnUnrelatedConstraint() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper", new RuntimeException("violates uq_something_else"));
    assertThat(ConstraintViolations.matches(ex, "uq_feature_toggles_name")).isFalse();
  }

  @Test
  void isNullSafeOnMessages() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException("wrapper", new RuntimeException((String) null));
    assertThat(ConstraintViolations.matches(ex, "uq_feature_toggles_name")).isFalse();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ConstraintViolationsTest`
Expected: COMPILATION FAILURE — `ConstraintViolations` does not exist.

- [ ] **Step 3: Write the helper**

`src/main/java/com/zarlania/api/persistence/ConstraintViolations.java`:

```java
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
```

- [ ] **Step 4: Refactor `OrganizationService` to use it**

In `src/main/java/com/zarlania/api/organizations/service/OrganizationService.java`:
1. Add import `com.zarlania.api.persistence.ConstraintViolations;`.
2. Replace `if (isConstraintViolation(ex, NAME_UNIQUE_CONSTRAINT)) {` with `if (ConstraintViolations.matches(ex, NAME_UNIQUE_CONSTRAINT)) {` and `if (isConstraintViolation(ex, MEMBERSHIP_UNIQUE_CONSTRAINT)) {` with `if (ConstraintViolations.matches(ex, MEMBERSHIP_UNIQUE_CONSTRAINT)) {`.
3. Delete the entire private static `isConstraintViolation` method (and its javadoc).

- [ ] **Step 5: Run the new test plus the organization suites**

Run: `./mvnw test -Dtest='ConstraintViolationsTest,OrganizationServiceUnitTest,OrganizationServiceIntegrationTest'`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/zarlania/api/persistence/ConstraintViolations.java src/main/java/com/zarlania/api/organizations/service/OrganizationService.java src/test/java/com/zarlania/api/persistence/ConstraintViolationsTest.java
git commit -m "refactor: extract shared ConstraintViolations helper (#66)"
```

---

### Task 3: `Feature` enum and startup synchronizer

**Files:**
- Create: `src/main/java/com/zarlania/api/features/Feature.java`
- Create: `src/main/java/com/zarlania/api/features/service/FeatureToggleSynchronizer.java`
- Test: `src/test/java/com/zarlania/api/features/service/FeatureToggleSynchronizerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1's entities/repositories.
- Produces: `enum Feature { FEATURE_SERVICE_CANARY }`; `FeatureToggleSynchronizer implements ApplicationRunner` with `void synchronize(Set<String> registeredNames)` (public, called by `run` with the enum's names; tests call it directly with fabricated names).

- [ ] **Step 1: Write the enum**

`src/main/java/com/zarlania/api/features/Feature.java`:

```java
package com.zarlania.api.features;

/**
 * The code registry of feature toggles: adding a constant creates the toggle (synced to the DB at
 * startup, default off); removing the constant deletes it and its overrides on the next deploy.
 * The constant name is the toggle's name in the admin API.
 */
public enum Feature {

  /**
   * Permanent no-op toggle for smoke-testing the toggle mechanism end to end in production, and a
   * stable constant for tests. It gates no code path.
   */
  FEATURE_SERVICE_CANARY
}
```

- [ ] **Step 2: Write the failing integration test**

`src/test/java/com/zarlania/api/features/service/FeatureToggleSynchronizerIntegrationTest.java`:

```java
package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
import com.zarlania.api.features.repository.FeatureToggleOrgOverrideRepository;
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

  @Autowired private FeatureToggleRepository toggles;
  @Autowired private FeatureToggleOrgOverrideRepository overrides;
  @Autowired private TestEntityManager entityManager;

  private FeatureToggleSynchronizer synchronizer() {
    return new FeatureToggleSynchronizer(toggles);
  }

  private FeatureToggleEntity saveToggle(String name, int percentage) {
    FeatureToggleEntity toggle = new FeatureToggleEntity();
    toggle.setName(name);
    toggle.setPercentage(percentage);
    return toggles.saveAndFlush(toggle);
  }

  @Test
  void insertsMissingtogglesDefaultOff() {
    String name = "SYNC_NEW_" + UUID.randomUUID().toString().replace("-", "");
    synchronizer().synchronize(Set.of(name));
    assertThat(toggles.findByName(name))
        .hasValueSatisfying(created -> assertThat(created.getPercentage()).isZero());
  }

  @Test
  void keepsExistingPercentageForKnownToggles() {
    String name = "SYNC_KEEP_" + UUID.randomUUID().toString().replace("-", "");
    saveToggle(name, 42);
    synchronizer().synchronize(Set.of(name));
    assertThat(toggles.findByName(name))
        .hasValueSatisfying(kept -> assertThat(kept.getPercentage()).isEqualTo(42));
  }

  @Test
  void deletesOrphanedTogglesAndTheirOverrides() {
    String keep = "SYNC_KEEP_" + UUID.randomUUID().toString().replace("-", "");
    String orphan = "SYNC_ORPHAN_" + UUID.randomUUID().toString().replace("-", "");
    saveToggle(keep, 0);
    FeatureToggleEntity orphanToggle = saveToggle(orphan, 50);

    UUID orgId = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO organizations (id, name, type, created_at, updated_at) "
                + "VALUES (:id, :name, 'GENERAL', NOW(), NOW())")
        .setParameter("id", orgId)
        .setParameter("name", "org-" + orgId)
        .executeUpdate();
    FeatureToggleOrgOverrideEntity override = new FeatureToggleOrgOverrideEntity();
    override.setToggle(orphanToggle);
    override.setOrganizationId(orgId);
    override.setPercentage(10);
    overrides.saveAndFlush(override);
    entityManager.getEntityManager().clear();

    synchronizer().synchronize(Set.of(keep));

    assertThat(toggles.findByName(orphan)).isEmpty();
    assertThat(toggles.findByName(keep)).isPresent();
    assertThat(overrides.findByToggleId(orphanToggle.getId())).isEmpty();
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=FeatureToggleSynchronizerIntegrationTest`
Expected: COMPILATION FAILURE — `FeatureToggleSynchronizer` does not exist.

- [ ] **Step 4: Write the synchronizer**

`src/main/java/com/zarlania/api/features/service/FeatureToggleSynchronizer.java`:

```java
package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the {@link Feature} code registry with the {@code feature_toggles} table during
 * application boot: registers new toggles default-off and deletes rows whose enum constant no
 * longer exists (the DB {@code ON DELETE CASCADE} removes their organization overrides). Runs as
 * an {@link ApplicationRunner} so a failure aborts startup rather than leaving code and DB
 * drifted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeatureToggleSynchronizer implements ApplicationRunner {

  private final FeatureToggleRepository toggleRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    synchronize(Arrays.stream(Feature.values()).map(Enum::name).collect(Collectors.toSet()));
  }

  /**
   * Brings the {@code feature_toggles} table in line with the given registered toggle names.
   *
   * @param registeredNames the toggle names that exist in code
   */
  @Transactional
  public void synchronize(Set<String> registeredNames) {
    List<FeatureToggleEntity> existing = toggleRepository.findAll();
    Set<String> existingNames =
        existing.stream().map(FeatureToggleEntity::getName).collect(Collectors.toSet());

    List<FeatureToggleEntity> created =
        registeredNames.stream()
            .filter(name -> !existingNames.contains(name))
            .map(
                name -> {
                  FeatureToggleEntity toggle = new FeatureToggleEntity();
                  toggle.setName(name);
                  toggle.setPercentage(0);
                  return toggle;
                })
            .toList();
    toggleRepository.saveAll(created);

    List<FeatureToggleEntity> orphaned =
        existing.stream().filter(toggle -> !registeredNames.contains(toggle.getName())).toList();
    toggleRepository.deleteAll(orphaned);
    toggleRepository.flush();

    log.info(
        "Feature toggles synchronized: {} registered, {} created (off), {} removed",
        registeredNames.size(),
        created.size(),
        orphaned.size());
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=FeatureToggleSynchronizerIntegrationTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/zarlania/api/features/Feature.java src/main/java/com/zarlania/api/features/service/FeatureToggleSynchronizer.java src/test/java/com/zarlania/api/features/service/FeatureToggleSynchronizerIntegrationTest.java
git commit -m "feat: Feature enum registry and startup DB synchronizer (#66)"
```

---

### Task 4: Trace id filter and `CurrentTraceId`

**Files:**
- Create: `src/main/java/com/zarlania/api/web/TraceIdFilter.java`
- Create: `src/main/java/com/zarlania/api/web/CurrentTraceId.java`
- Create: `src/main/java/com/zarlania/api/web/RequestAttributeCurrentTraceId.java`
- Modify: `src/main/resources/application.properties` (add the traceId log pattern)
- Test: `src/test/java/com/zarlania/api/web/TraceIdFilterTest.java`

**Interfaces:**
- Produces: `TraceIdFilter.TRACE_ID_ATTRIBUTE` (String request-attribute key), `TraceIdFilter.TRACE_ID_HEADER = "X-Trace-Id"`; `interface CurrentTraceId { Optional<String> get(); }` — the `features` domain consumes `CurrentTraceId` in Task 6.

- [ ] **Step 1: Write the failing e2e test**

`src/test/java/com/zarlania/api/web/TraceIdFilterTest.java`:

```java
package com.zarlania.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// e2e through the real filter chain (@AutoConfigureMockMvc registers servlet filters).
@SpringBootTest
@AutoConfigureMockMvc
class TraceIdFilterTest {

  private static final String VALID_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Autowired private MockMvc mockMvc;

  @Test
  void usesTraceIdFromTraceparentHeader() throws Exception {
    mockMvc
        .perform(get("/actuator/health").header("traceparent", VALID_TRACEPARENT))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"));
  }

  @Test
  void fallsBackToXTraceIdHeader() throws Exception {
    mockMvc
        .perform(get("/actuator/health").header("X-Trace-Id", "my-trace-123"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "my-trace-123"));
  }

  @Test
  void generatesTraceIdWhenNoHeaderPresent() throws Exception {
    String echoed =
        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertThat(echoed).isNotBlank();
  }

  @Test
  void ignoresMalformedTraceparentAndGeneratesInstead() throws Exception {
    String echoed =
        mockMvc
            .perform(get("/actuator/health").header("traceparent", "not-a-traceparent"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertThat(echoed).isNotBlank().isNotEqualTo("not-a-traceparent");
  }

  @Test
  void rejectsUnsafeXTraceIdAndGeneratesInstead() throws Exception {
    String unsafe = "abc\r\ninjected";
    String echoed =
        mockMvc
            .perform(get("/actuator/health").header("X-Trace-Id", unsafe))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertThat(echoed).isNotBlank().isNotEqualTo(unsafe);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=TraceIdFilterTest`
Expected: FAIL — no `X-Trace-Id` response header (filter does not exist yet).

- [ ] **Step 3: Write the filter and the trace-id accessor**

`src/main/java/com/zarlania/api/web/TraceIdFilter.java`:

```java
package com.zarlania.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a trace id: taken from the W3C {@code traceparent} header when present,
 * else from {@code X-Trace-Id}, else freshly generated. The id is stored as a request attribute
 * (see {@link RequestAttributeCurrentTraceId}), put in the logging MDC under {@code traceId}, and
 * echoed on the response as {@code X-Trace-Id} so callers (and chained hops) can propagate it.
 * Inbound values are validated against a strict charset, which also makes them log-safe.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  /** Request-attribute key under which the resolved trace id is stored. */
  public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".TRACE_ID";

  /** Inbound fallback and outbound echo header for the trace id. */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  private static final String TRACEPARENT_HEADER = "traceparent";
  private static final String MDC_KEY = "traceId";

  private static final Pattern TRACEPARENT =
      Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
  private static final Pattern SIMPLE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = resolveTraceId(request);
    request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
    response.setHeader(TRACE_ID_HEADER, traceId);
    MDC.put(MDC_KEY, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static String resolveTraceId(HttpServletRequest request) {
    String traceparent = request.getHeader(TRACEPARENT_HEADER);
    if (traceparent != null) {
      Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
      if (matcher.matches()) {
        return matcher.group(1);
      }
    }
    String simple = request.getHeader(TRACE_ID_HEADER);
    if (simple != null && SIMPLE_TRACE_ID.matcher(simple).matches()) {
      return simple;
    }
    return UUID.randomUUID().toString();
  }
}
```

`src/main/java/com/zarlania/api/web/CurrentTraceId.java`:

```java
package com.zarlania.api.web;

import java.util.Optional;

/**
 * Supplies the current request's trace id, if the caller is executing within a traced request.
 * An interface so domain services can be unit-tested with a fixed trace id.
 */
public interface CurrentTraceId {

  /**
   * Returns the current request's trace id.
   *
   * @return the trace id, or empty when not executing within an HTTP request
   */
  Optional<String> get();
}
```

`src/main/java/com/zarlania/api/web/RequestAttributeCurrentTraceId.java`:

```java
package com.zarlania.api.web;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reads the trace id that {@link TraceIdFilter} stored on the current request. Returns empty when
 * no request is bound to the calling thread (startup, background work).
 */
@Component
public class RequestAttributeCurrentTraceId implements CurrentTraceId {

  @Override
  public Optional<String> get() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return Optional.empty();
    }
    Object value =
        attributes.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    return value instanceof String traceId ? Optional.of(traceId) : Optional.empty();
  }
}
```

In `src/main/resources/application.properties`, append:

```properties
# Surface the request trace id (set by TraceIdFilter into the MDC) in every log line.
logging.pattern.level=%5p [traceId:%X{traceId:-}]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=TraceIdFilterTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/zarlania/api/web src/main/resources/application.properties src/test/java/com/zarlania/api/web/TraceIdFilterTest.java
git commit -m "feat: trace-id filter with traceparent/X-Trace-Id support (#66)"
```

---

### Task 5: Caffeine trace-decision cache with validated properties

**Files:**
- Modify: `pom.xml` (add Caffeine dependency — version managed by the Boot BOM at 3.2.4)
- Create: `src/main/java/com/zarlania/api/features/config/FeatureCacheProperties.java`
- Create: `src/main/java/com/zarlania/api/features/config/FeaturesConfig.java`
- Create: `src/main/java/com/zarlania/api/features/service/TraceDecisionCache.java`
- Create: `src/main/java/com/zarlania/api/features/service/RandomSource.java`
- Create: `src/main/java/com/zarlania/api/features/service/CaffeineTraceDecisionCache.java`
- Modify: `src/main/resources/application.properties` (cache defaults)
- Test: `src/test/java/com/zarlania/api/features/config/FeatureCachePropertiesTest.java`
- Test: `src/test/java/com/zarlania/api/features/service/CaffeineTraceDecisionCacheTest.java`

**Interfaces:**
- Produces: `interface TraceDecisionCache { Optional<Boolean> get(String traceId, Feature feature, UUID organizationId); void put(String traceId, Feature feature, UUID organizationId, boolean enabled); }` (`organizationId` may be null = global check); `@FunctionalInterface RandomSource { double nextDouble(); }`; Spring beans for both (Caffeine impl; `SecureRandom`-backed `RandomSource`).

- [ ] **Step 1: Add the dependency**

In `pom.xml`, after the `com.h2database:h2` dependency block, add:

```xml
		<dependency>
			<groupId>com.github.ben-manes.caffeine</groupId>
			<artifactId>caffeine</artifactId>
		</dependency>
```

(No `<version>`: `spring-boot-dependencies` 4.1.0 manages Caffeine 3.2.4.)

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/zarlania/api/features/config/FeatureCachePropertiesTest.java`:

```java
package com.zarlania.api.features.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FeatureCachePropertiesTest {

  @Test
  void acceptsPositiveTtlAndSize() {
    FeatureCacheProperties properties =
        new FeatureCacheProperties(Duration.ofMinutes(10), 10_000);
    assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(10));
    assertThat(properties.maxSize()).isEqualTo(10_000);
  }

  @Test
  void rejectsNullTtl() {
    assertThatThrownBy(() -> new FeatureCacheProperties(null, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonPositiveTtl() {
    assertThatThrownBy(() -> new FeatureCacheProperties(Duration.ZERO, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonPositiveMaxSize() {
    assertThatThrownBy(() -> new FeatureCacheProperties(Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

`src/test/java/com/zarlania/api/features/service/CaffeineTraceDecisionCacheTest.java`:

```java
package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.config.FeatureCacheProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaffeineTraceDecisionCacheTest {

  private static CaffeineTraceDecisionCache cache() {
    return new CaffeineTraceDecisionCache(
        new FeatureCacheProperties(Duration.ofMinutes(10), 100));
  }

  @Test
  void missesWhenEmpty() {
    assertThat(cache().get("t1", Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }

  @Test
  void returnsPutDecisionForSameKey() {
    CaffeineTraceDecisionCache cache = cache();
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, null, true);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, null)).contains(true);
  }

  @Test
  void distinguishesTraceIds() {
    CaffeineTraceDecisionCache cache = cache();
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, null, true);
    assertThat(cache.get("t2", Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }

  @Test
  void distinguishesOrganizations() {
    CaffeineTraceDecisionCache cache = cache();
    UUID orgA = UUID.randomUUID();
    UUID orgB = UUID.randomUUID();
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, orgA, true);
    cache.put("t1", Feature.FEATURE_SERVICE_CANARY, orgB, false);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, orgA)).contains(true);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, orgB)).contains(false);
    assertThat(cache.get("t1", Feature.FEATURE_SERVICE_CANARY, null)).isEmpty();
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -Dtest='FeatureCachePropertiesTest,CaffeineTraceDecisionCacheTest'`
Expected: COMPILATION FAILURE — classes do not exist.

- [ ] **Step 4: Write properties, config, interfaces, implementation**

`src/main/java/com/zarlania/api/features/config/FeatureCacheProperties.java`:

```java
package com.zarlania.api.features.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds for the trace-decision cache. Bound from {@code zarlania.features.cache.*}; invalid
 * values are rejected at bind time so a misconfiguration fails startup (same pattern as
 * {@code CorsProperties}).
 *
 * @param ttl how long a pinned decision lives after being written; must be positive
 * @param maxSize maximum number of cached decisions (the key is client-controllable, so the cache
 *     must be size-bounded, not just TTL-bounded); must be positive
 */
@ConfigurationProperties(prefix = "zarlania.features.cache")
public record FeatureCacheProperties(Duration ttl, long maxSize) {

  /**
   * Validates the configured bounds.
   *
   * @param ttl the decision time-to-live
   * @param maxSize the maximum entry count
   * @throws IllegalArgumentException if the ttl is null/non-positive or maxSize is non-positive
   */
  public FeatureCacheProperties {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException(
          "zarlania.features.cache.ttl must be a positive duration");
    }
    if (maxSize <= 0) {
      throw new IllegalArgumentException("zarlania.features.cache.max-size must be positive");
    }
  }
}
```

`src/main/java/com/zarlania/api/features/service/TraceDecisionCache.java`:

```java
package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import java.util.Optional;
import java.util.UUID;

/**
 * Pins feature-toggle decisions to a trace id so every check within one request — or a chained
 * hop carrying the same trace header — sees the same answer. This interface is the seam for a
 * future shared implementation (Render Key Value / Valkey) when the service goes multi-instance;
 * today's implementation is in-process ({@link CaffeineTraceDecisionCache}).
 */
public interface TraceDecisionCache {

  /**
   * Looks up a pinned decision.
   *
   * @param traceId the request's trace id
   * @param feature the toggle being checked
   * @param organizationId the organization the check was scoped to, or null for a global check
   * @return the pinned decision, or empty if none is cached
   */
  Optional<Boolean> get(String traceId, Feature feature, UUID organizationId);

  /**
   * Pins a decision for the trace.
   *
   * @param traceId the request's trace id
   * @param feature the toggle that was checked
   * @param organizationId the organization the check was scoped to, or null for a global check
   * @param enabled the decision to pin
   */
  void put(String traceId, Feature feature, UUID organizationId, boolean enabled);
}
```

`src/main/java/com/zarlania/api/features/service/RandomSource.java`:

```java
package com.zarlania.api.features.service;

/**
 * Source of randomness for partial-rollout coin flips. An interface so tests can inject
 * deterministic values; production wires {@link java.security.SecureRandom} (see
 * {@code FeaturesConfig}).
 */
@FunctionalInterface
public interface RandomSource {

  /**
   * Returns the next random double.
   *
   * @return a uniformly distributed value in {@code [0.0, 1.0)}
   */
  double nextDouble();
}
```

`src/main/java/com/zarlania/api/features/service/CaffeineTraceDecisionCache.java`:

```java
package com.zarlania.api.features.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zarlania.api.features.Feature;
import com.zarlania.api.features.config.FeatureCacheProperties;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * In-process {@link TraceDecisionCache} bounded by TTL and maximum size. Entries are a few hundred
 * bytes, so at the default bounds the cache costs a few MB; the size bound (not just the TTL)
 * matters because trace ids are client-controllable.
 */
@Component
public class CaffeineTraceDecisionCache implements TraceDecisionCache {

  private record Key(String traceId, Feature feature, UUID organizationId) {}

  private final Cache<Key, Boolean> cache;

  /**
   * Creates the cache with the configured bounds.
   *
   * @param properties the validated TTL and size bounds
   */
  public CaffeineTraceDecisionCache(FeatureCacheProperties properties) {
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.ttl())
            .maximumSize(properties.maxSize())
            .build();
  }

  @Override
  public Optional<Boolean> get(String traceId, Feature feature, UUID organizationId) {
    return Optional.ofNullable(cache.getIfPresent(new Key(traceId, feature, organizationId)));
  }

  @Override
  public void put(String traceId, Feature feature, UUID organizationId, boolean enabled) {
    cache.put(new Key(traceId, feature, organizationId), enabled);
  }
}
```

`src/main/java/com/zarlania/api/features/config/FeaturesConfig.java`:

```java
package com.zarlania.api.features.config;

import com.zarlania.api.features.service.RandomSource;
import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the {@code features} domain: cache bounds and the production randomness source. */
@Configuration
@EnableConfigurationProperties(FeatureCacheProperties.class)
public class FeaturesConfig {

  /**
   * Production randomness for partial-rollout coin flips. {@link SecureRandom} rather than a
   * seeded PRNG: the rate is far too low for its cost to matter, and it keeps the FindSecBugs
   * predictable-randomness detector satisfied without an exclusion.
   *
   * @return the randomness source used by feature evaluation
   */
  @Bean
  public RandomSource featureRandomSource() {
    SecureRandom random = new SecureRandom();
    return random::nextDouble;
  }
}
```

In `src/main/resources/application.properties`, append:

```properties
# Trace-decision cache: how long a toggle decision stays pinned to a trace id, and the cache's
# hard size bound (trace ids are client-supplied, so the cache must not grow unbounded).
zarlania.features.cache.ttl=10m
zarlania.features.cache.max-size=10000
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest='FeatureCachePropertiesTest,CaffeineTraceDecisionCacheTest'`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add pom.xml src/main/java/com/zarlania/api/features src/main/resources/application.properties src/test/java/com/zarlania/api/features
git commit -m "feat: bounded Caffeine trace-decision cache behind TraceDecisionCache seam (#66)"
```

---

### Task 6: `FeatureToggleService` evaluation

**Files:**
- Create: `src/main/java/com/zarlania/api/features/service/FeatureToggleService.java`
- Test: `src/test/java/com/zarlania/api/features/service/FeatureToggleServiceUnitTest.java`

**Interfaces:**
- Consumes: Tasks 1, 4, 5 — repositories, `CurrentTraceId`, `TraceDecisionCache`, `RandomSource`.
- Produces: `FeatureToggleService.isEnabled(Feature):boolean` and `isEnabled(Feature, UUID organizationId):boolean` — **this is the API feature code calls** to gate a path.

- [ ] **Step 1: Write the failing unit tests**

`src/test/java/com/zarlania/api/features/service/FeatureToggleServiceUnitTest.java`:

```java
package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.config.FeatureCacheProperties;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
import com.zarlania.api.features.repository.FeatureToggleOrgOverrideRepository;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FeatureToggleServiceUnitTest {

  private static final Feature FEATURE = Feature.FEATURE_SERVICE_CANARY;

  @Mock private FeatureToggleRepository toggleRepository;
  @Mock private FeatureToggleOrgOverrideRepository overrideRepository;

  private String traceId;
  private double nextRandom;

  private FeatureToggleService service() {
    TraceDecisionCache cache =
        new CaffeineTraceDecisionCache(new FeatureCacheProperties(Duration.ofMinutes(10), 100));
    return new FeatureToggleService(
        toggleRepository,
        overrideRepository,
        cache,
        () -> Optional.ofNullable(traceId),
        () -> nextRandom);
  }

  private FeatureToggleEntity toggle(int percentage) {
    FeatureToggleEntity entity = new FeatureToggleEntity();
    entity.setName(FEATURE.name());
    entity.setPercentage(percentage);
    ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
    return entity;
  }

  private void stubToggle(int percentage) {
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(percentage)));
  }

  @Test
  void rejectsNullFeature() {
    assertThatThrownBy(() -> service().isEnabled(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroPercentIsOffWithoutDrawingRandomness() {
    stubToggle(0);
    nextRandom = 0.0; // even the luckiest draw must not matter
    assertThat(service().isEnabled(FEATURE)).isFalse();
  }

  @Test
  void hundredPercentIsOn() {
    stubToggle(100);
    nextRandom = 0.999_999; // even the unluckiest draw must not matter
    assertThat(service().isEnabled(FEATURE)).isTrue();
  }

  @Test
  void partialPercentageEnablesWhenDrawIsBelowThreshold() {
    stubToggle(10);
    nextRandom = 0.099;
    assertThat(service().isEnabled(FEATURE)).isTrue();
  }

  @Test
  void partialPercentageDisablesWhenDrawIsAtOrAboveThreshold() {
    stubToggle(10);
    nextRandom = 0.10;
    assertThat(service().isEnabled(FEATURE)).isFalse();
  }

  @Test
  void missingRowFailsSafeToOff() {
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.empty());
    assertThat(service().isEnabled(FEATURE)).isFalse();
  }

  @Test
  void orgOverrideBeatsGlobalUnconditionally() {
    FeatureToggleEntity entity = toggle(100);
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(entity));
    UUID orgId = UUID.randomUUID();
    FeatureToggleOrgOverrideEntity override = new FeatureToggleOrgOverrideEntity();
    override.setToggle(entity);
    override.setOrganizationId(orgId);
    override.setPercentage(0);
    when(overrideRepository.findByToggleIdAndOrganizationId(entity.getId(), orgId))
        .thenReturn(Optional.of(override));

    assertThat(service().isEnabled(FEATURE, orgId)).isFalse();
  }

  @Test
  void orgWithoutOverrideFallsBackToGlobal() {
    FeatureToggleEntity entity = toggle(100);
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(entity));
    UUID orgId = UUID.randomUUID();
    when(overrideRepository.findByToggleIdAndOrganizationId(entity.getId(), orgId))
        .thenReturn(Optional.empty());

    assertThat(service().isEnabled(FEATURE, orgId)).isTrue();
  }

  @Test
  void decisionIsPinnedToTraceEvenWhenStateChanges() {
    traceId = "trace-1";
    stubToggle(100);
    FeatureToggleService service = service();
    assertThat(service.isEnabled(FEATURE)).isTrue();

    // State flips to off, but the pinned decision must hold for the same trace.
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(0)));
    assertThat(service.isEnabled(FEATURE)).isTrue();
  }

  @Test
  void separateTracesReEvaluate() {
    traceId = "trace-1";
    stubToggle(100);
    FeatureToggleService service = service();
    assertThat(service.isEnabled(FEATURE)).isTrue();

    traceId = "trace-2";
    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(0)));
    assertThat(service.isEnabled(FEATURE)).isFalse();
  }

  @Test
  void withoutTraceContextEveryCallReEvaluates() {
    traceId = null;
    stubToggle(100);
    FeatureToggleService service = service();
    assertThat(service.isEnabled(FEATURE)).isTrue();

    when(toggleRepository.findByName(FEATURE.name())).thenReturn(Optional.of(toggle(0)));
    assertThat(service.isEnabled(FEATURE)).isFalse();
  }

  @Test
  void globalCheckNeverTouchesOverrideRepository() {
    stubToggle(50);
    nextRandom = 0.9;
    service().isEnabled(FEATURE);
    verify(overrideRepository, never()).findByToggleIdAndOrganizationId(any(), any());
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=FeatureToggleServiceUnitTest`
Expected: COMPILATION FAILURE — `FeatureToggleService` does not exist.

- [ ] **Step 3: Write the service**

`src/main/java/com/zarlania/api/features/service/FeatureToggleService.java`:

```java
package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.repository.FeatureToggleOrgOverrideRepository;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import com.zarlania.api.web.CurrentTraceId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates feature toggles: the API feature code calls to gate a new code path. The effective
 * percentage is the organization's override when one exists, else the toggle's global percentage;
 * 0 is off, 100 is on, and anything between is a coin flip. Decisions are pinned to the current
 * trace id (when one exists), so repeated checks within a request — or a chained hop carrying the
 * trace header — always agree. A toggle with no DB row (not yet synced, or removed) fails safe to
 * off; evaluation never throws for unknown organizations.
 */
@Service
@RequiredArgsConstructor
public class FeatureToggleService {

  private final FeatureToggleRepository toggleRepository;
  private final FeatureToggleOrgOverrideRepository overrideRepository;
  private final TraceDecisionCache decisionCache;
  private final CurrentTraceId currentTraceId;
  private final RandomSource randomSource;

  /**
   * Reports whether a feature is enabled for this request, using the toggle's global state.
   *
   * @param feature the toggle to check
   * @return true if the feature's path should run
   * @throws IllegalArgumentException if {@code feature} is null
   */
  @Transactional(readOnly = true)
  public boolean isEnabled(Feature feature) {
    return isEnabled(feature, null);
  }

  /**
   * Reports whether a feature is enabled for this request in the context of an organization. An
   * organization with an override uses it unconditionally; otherwise the global state applies.
   *
   * @param feature the toggle to check
   * @param organizationId the organization context, or null for a global check
   * @return true if the feature's path should run
   * @throws IllegalArgumentException if {@code feature} is null
   */
  @Transactional(readOnly = true)
  public boolean isEnabled(Feature feature, UUID organizationId) {
    if (feature == null) {
      throw new IllegalArgumentException("feature must not be null");
    }
    Optional<String> traceId = currentTraceId.get();
    if (traceId.isPresent()) {
      Optional<Boolean> pinned = decisionCache.get(traceId.get(), feature, organizationId);
      if (pinned.isPresent()) {
        return pinned.get();
      }
    }
    boolean enabled = evaluate(feature, organizationId);
    traceId.ifPresent(id -> decisionCache.put(id, feature, organizationId, enabled));
    return enabled;
  }

  private boolean evaluate(Feature feature, UUID organizationId) {
    Optional<FeatureToggleEntity> toggle = toggleRepository.findByName(feature.name());
    if (toggle.isEmpty()) {
      return false;
    }
    int percentage = effectivePercentage(toggle.get(), organizationId);
    if (percentage <= 0) {
      return false;
    }
    if (percentage >= 100) {
      return true;
    }
    return randomSource.nextDouble() * 100.0 < percentage;
  }

  private int effectivePercentage(FeatureToggleEntity toggle, UUID organizationId) {
    if (organizationId == null) {
      return toggle.getPercentage();
    }
    return overrideRepository
        .findByToggleIdAndOrganizationId(toggle.getId(), organizationId)
        .map(override -> override.getPercentage())
        .orElse(toggle.getPercentage());
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=FeatureToggleServiceUnitTest`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/zarlania/api/features/service/FeatureToggleService.java src/test/java/com/zarlania/api/features/service/FeatureToggleServiceUnitTest.java
git commit -m "feat: trace-pinned feature-toggle evaluation service (#66)"
```

---

### Task 7: DTOs, mapper, admin service, exception handling

**Files:**
- Create: `src/main/java/com/zarlania/api/features/dto/FeatureToggle.java`
- Create: `src/main/java/com/zarlania/api/features/dto/FeatureToggleOrgOverride.java`
- Create: `src/main/java/com/zarlania/api/features/exception/FeatureToggleNotFoundException.java`
- Create: `src/main/java/com/zarlania/api/features/service/FeatureToggleMapper.java`
- Create: `src/main/java/com/zarlania/api/features/service/FeatureToggleAdminService.java`
- Modify: `src/main/java/com/zarlania/api/web/ApiExceptionHandler.java` (two 404 handlers + `notFound` helper)
- Test: `src/test/java/com/zarlania/api/features/service/FeatureToggleAdminServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1–2; `OrganizationNotFoundException.forId(UUID)` from `organizations.exception` (exception import is permitted; entity/repository imports are not).
- Produces: `record FeatureToggle(String name, int percentage, List<FeatureToggleOrgOverride> organizationOverrides)`; `record FeatureToggleOrgOverride(UUID organizationId, int percentage)`; `FeatureToggleAdminService` with `list():List<FeatureToggle>`, `get(String name):FeatureToggle`, `setPercentage(String name, int percentage):FeatureToggle`, `setOrgOverride(String name, UUID organizationId, int percentage):FeatureToggle`, `removeOrgOverride(String name, UUID organizationId):FeatureToggle`; `FeatureToggleNotFoundException.forName(String)`.

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/zarlania/api/features/service/FeatureToggleAdminServiceIntegrationTest.java`:

```java
package com.zarlania.api.features.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.features.dto.FeatureToggle;
import com.zarlania.api.features.dto.FeatureToggleOrgOverride;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.exception.FeatureToggleNotFoundException;
import com.zarlania.api.features.repository.FeatureToggleOrgOverrideRepository;
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

  @Autowired private FeatureToggleRepository toggles;
  @Autowired private FeatureToggleOrgOverrideRepository overrides;
  @Autowired private TestEntityManager entityManager;

  private FeatureToggleAdminService service() {
    return new FeatureToggleAdminService(toggles, overrides, new FeatureToggleMapper());
  }

  private String saveToggle(int percentage) {
    String name = "ADM_" + UUID.randomUUID().toString().replace("-", "");
    FeatureToggleEntity toggle = new FeatureToggleEntity();
    toggle.setName(name);
    toggle.setPercentage(percentage);
    toggles.saveAndFlush(toggle);
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

  @Test
  void getReturnsToggleWithOverrides() {
    String name = saveToggle(30);
    UUID orgId = seedOrganization();
    service().setOrgOverride(name, orgId, 10);

    FeatureToggle toggle = service().get(name);
    assertThat(toggle.name()).isEqualTo(name);
    assertThat(toggle.percentage()).isEqualTo(30);
    assertThat(toggle.organizationOverrides())
        .containsExactly(new FeatureToggleOrgOverride(orgId, 10));
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
    assertThat(toggles.findByName(name).orElseThrow().getPercentage()).isEqualTo(100);
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
  void setOrgOverrideCreatesThenReplaces() {
    String name = saveToggle(0);
    UUID orgId = seedOrganization();

    service().setOrgOverride(name, orgId, 10);
    FeatureToggle updated = service().setOrgOverride(name, orgId, 90);

    assertThat(updated.organizationOverrides())
        .containsExactly(new FeatureToggleOrgOverride(orgId, 90));
  }

  @Test
  void setOrgOverrideForUnknownOrganizationThrowsOrganizationNotFound() {
    String name = saveToggle(0);
    assertThatThrownBy(() -> service().setOrgOverride(name, UUID.randomUUID(), 10))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  void removeOrgOverrideFallsBackToGlobal() {
    String name = saveToggle(70);
    UUID orgId = seedOrganization();
    service().setOrgOverride(name, orgId, 10);

    FeatureToggle updated = service().removeOrgOverride(name, orgId);
    assertThat(updated.organizationOverrides()).isEmpty();
  }

  @Test
  void removeMissingOverrideIsIdempotent() {
    String name = saveToggle(70);
    FeatureToggle updated = service().removeOrgOverride(name, UUID.randomUUID());
    assertThat(updated.organizationOverrides()).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FeatureToggleAdminServiceIntegrationTest`
Expected: COMPILATION FAILURE — DTOs/service/exception do not exist.

- [ ] **Step 3: Write DTOs, exception, mapper, admin service**

`src/main/java/com/zarlania/api/features/dto/FeatureToggleOrgOverride.java`:

```java
package com.zarlania.api.features.dto;

import java.util.UUID;

/**
 * Immutable view of one organization's override of a feature toggle.
 *
 * @param organizationId the organization the override applies to
 * @param percentage the override percentage: 0 = off, 100 = on, in between = partial
 */
public record FeatureToggleOrgOverride(UUID organizationId, int percentage) {}
```

`src/main/java/com/zarlania/api/features/dto/FeatureToggle.java`:

```java
package com.zarlania.api.features.dto;

import java.util.List;

/**
 * Immutable view of a feature toggle's full state for use across the domain boundary and in admin
 * API responses. This DTO — not the JPA {@code FeatureToggleEntity} — is the type passed
 * throughout the application.
 *
 * @param name the toggle's registered (enum-constant) name
 * @param percentage the global percentage: 0 = off, 100 = on, in between = partial
 * @param organizationOverrides per-organization overrides; each wins unconditionally over the
 *     global percentage for its organization
 */
public record FeatureToggle(
    String name, int percentage, List<FeatureToggleOrgOverride> organizationOverrides) {

  /**
   * Stores an immutable copy of the overrides list.
   *
   * @param name the toggle name
   * @param percentage the global percentage
   * @param organizationOverrides the per-organization overrides
   */
  public FeatureToggle {
    organizationOverrides = List.copyOf(organizationOverrides);
  }
}
```

`src/main/java/com/zarlania/api/features/exception/FeatureToggleNotFoundException.java`:

```java
package com.zarlania.api.features.exception;

import lombok.Getter;

/** Thrown when an admin operation targets a feature-toggle name that is not registered. */
@Getter
public class FeatureToggleNotFoundException extends RuntimeException {

  /** The name that did not resolve to a feature toggle. */
  private final String name;

  private FeatureToggleNotFoundException(String name) {
    super("No feature toggle exists with the given name");
    this.name = name;
  }

  /**
   * Creates the exception for a missing toggle.
   *
   * @param name the name that did not resolve
   * @return an exception describing the miss
   */
  public static FeatureToggleNotFoundException forName(String name) {
    return new FeatureToggleNotFoundException(name);
  }
}
```

`src/main/java/com/zarlania/api/features/service/FeatureToggleMapper.java`:

```java
package com.zarlania.api.features.service;

import com.zarlania.api.features.dto.FeatureToggle;
import com.zarlania.api.features.dto.FeatureToggleOrgOverride;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.entity.FeatureToggleOrgOverrideEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps {@code features} entities to their DTOs for crossing the domain boundary. */
@Component
public class FeatureToggleMapper {

  /**
   * Maps a toggle and its overrides to the boundary DTO.
   *
   * @param entity the toggle entity
   * @param overrides the toggle's organization overrides
   * @return a DTO carrying the name, global percentage, and per-organization overrides
   */
  public FeatureToggle toDto(
      FeatureToggleEntity entity, List<FeatureToggleOrgOverrideEntity> overrides) {
    List<FeatureToggleOrgOverride> overrideDtos =
        overrides.stream()
            .map(
                override ->
                    new FeatureToggleOrgOverride(
                        override.getOrganizationId(), override.getPercentage()))
            .toList();
    return new FeatureToggle(entity.getName(), entity.getPercentage(), overrideDtos);
  }
}
```

`src/main/java/com/zarlania/api/features/service/FeatureToggleAdminService.java`:

```java
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
```

- [ ] **Step 4: Add the 404 handlers**

In `src/main/java/com/zarlania/api/web/ApiExceptionHandler.java`:

1. Add imports:

```java
import com.zarlania.api.features.exception.FeatureToggleNotFoundException;
import com.zarlania.api.organizations.exception.OrganizationNotFoundException;
```

2. Add after the last `@ExceptionHandler` method (before the private `conflict` helper):

```java
  /** Unknown feature-toggle name in an admin operation: 404. */
  @ExceptionHandler(FeatureToggleNotFoundException.class)
  ProblemDetail handleFeatureToggleNotFound(FeatureToggleNotFoundException ex) {
    return notFound("No feature toggle exists with the given name");
  }

  /** Unknown organization id: 404. */
  @ExceptionHandler(OrganizationNotFoundException.class)
  ProblemDetail handleOrganizationNotFound(OrganizationNotFoundException ex) {
    return notFound("No organization exists with the given id");
  }
```

3. Add next to the private `conflict` helper:

```java
  /**
   * Builds a 404 {@link ProblemDetail} from a fixed, safe detail and logs the miss at INFO. The
   * detail is a fixed string, so no client-supplied value is echoed or logged.
   */
  private static ProblemDetail notFound(String detail) {
    log.info("Request rejected (404 Not Found): {}", LogSanitizer.forLog(detail));
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest='FeatureToggleAdminServiceIntegrationTest,ApiExceptionHandlerTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/zarlania/api/features src/main/java/com/zarlania/api/web/ApiExceptionHandler.java src/test/java/com/zarlania/api/features
git commit -m "feat: feature-toggle admin service, DTOs, 404 handling (#66)"
```

---

### Task 8: Admin controller

**Files:**
- Create: `src/main/java/com/zarlania/api/features/dto/SetPercentageRequest.java`
- Create: `src/main/java/com/zarlania/api/features/controller/FeatureToggleAdminController.java`
- Test: `src/test/java/com/zarlania/api/features/controller/FeatureToggleAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 7's `FeatureToggleAdminService` and DTOs.
- Produces: HTTP surface `GET|PUT /api/admin/feature-toggles[/{name}[/organizations/{orgId}]]` — consumed by Task 9's doc-visibility tests.

- [ ] **Step 1: Write the failing e2e test**

`src/test/java/com/zarlania/api/features/controller/FeatureToggleAdminControllerTest.java`:

```java
package com.zarlania.api.features.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.features.Feature;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// Controller test through the full stack via MockMvc; rolls back after each method. The canary
// toggle row exists because FeatureToggleSynchronizer ran (and committed) at context startup.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeatureToggleAdminControllerTest {

  private static final String CANARY = Feature.FEATURE_SERVICE_CANARY.name();
  private static final String BASE = "/api/admin/feature-toggles";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String body(int percentage) {
    return "{\"percentage\":" + percentage + "}";
  }

  private UUID seedOrganization() {
    User creator = userService.create(unique("e") + "@example.com", unique("u"));
    Organization org =
        organizationService.createGeneralOrganization(creator.id(), unique("org"));
    return org.id();
  }

  @Test
  void listContainsTheCanaryToggle() throws Exception {
    mockMvc
        .perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name == '" + CANARY + "')]").exists());
  }

  @Test
  void getReturnsToggleShape() throws Exception {
    mockMvc
        .perform(get(BASE + "/" + CANARY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(CANARY))
        .andExpect(jsonPath("$.percentage").isNumber())
        .andExpect(jsonPath("$.organizationOverrides").isArray());
  }

  @Test
  void getUnknownToggleReturns404ProblemJson() throws Exception {
    mockMvc
        .perform(get(BASE + "/NOT_A_TOGGLE"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("No feature toggle exists with the given name"));
  }

  @Test
  void putUpdatesGlobalPercentage() throws Exception {
    mockMvc
        .perform(put(BASE + "/" + CANARY).contentType(MediaType.APPLICATION_JSON).content(body(100)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.percentage").value(100));
  }

  @Test
  void putRejectsOutOfRangePercentage() throws Exception {
    mockMvc
        .perform(put(BASE + "/" + CANARY).contentType(MediaType.APPLICATION_JSON).content(body(101)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.percentage").exists());
  }

  @Test
  void putRejectsMissingPercentage() throws Exception {
    mockMvc
        .perform(put(BASE + "/" + CANARY).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.percentage").exists());
  }

  @Test
  void orgOverrideRoundTrip() throws Exception {
    UUID orgId = seedOrganization();

    mockMvc
        .perform(
            put(BASE + "/" + CANARY + "/organizations/" + orgId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(10)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationOverrides[0].organizationId").value(orgId.toString()))
        .andExpect(jsonPath("$.organizationOverrides[0].percentage").value(10));

    mockMvc
        .perform(delete(BASE + "/" + CANARY + "/organizations/" + orgId))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get(BASE + "/" + CANARY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationOverrides").isEmpty());
  }

  @Test
  void orgOverrideForUnknownOrganizationReturns404() throws Exception {
    mockMvc
        .perform(
            put(BASE + "/" + CANARY + "/organizations/" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(10)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("No organization exists with the given id"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FeatureToggleAdminControllerTest`
Expected: FAIL — 404s for every request (no controller mapped).

- [ ] **Step 3: Write request DTO and controller**

`src/main/java/com/zarlania/api/features/dto/SetPercentageRequest.java`:

```java
package com.zarlania.api.features.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound payload for setting a toggle's (or an override's) percentage, validated at the HTTP
 * boundary. The 0–100 range also exists as a domain invariant in {@code
 * FeatureToggleAdminService} and as a DB CHECK constraint (defense in depth).
 *
 * @param percentage the rollout percentage: 0 = off, 100 = on, in between = partial
 */
public record SetPercentageRequest(@NotNull @Min(0) @Max(100) Integer percentage) {}
```

`src/main/java/com/zarlania/api/features/controller/FeatureToggleAdminController.java`:

```java
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
 * Admin HTTP surface for feature-toggle state. Lives under {@code /api/admin/**}, which is
 * excluded from the public OpenAPI document (see {@code OpenApiVisibilityConfig}) and — like every
 * endpoint today — is not yet authenticated; real auth is a future repo-wide story. Toggles are
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=FeatureToggleAdminControllerTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/zarlania/api/features src/test/java/com/zarlania/api/features
git commit -m "feat: feature-toggle admin API under /api/admin (#66)"
```

---

### Task 9: OpenAPI visibility — strip admin from the public doc, property-gated admin doc

**Files:**
- Create: `src/main/java/com/zarlania/api/config/OpenApiVisibilityConfig.java`
- Modify: `src/main/resources/application.properties` (add `zarlania.docs.expose-admin=false`)
- Test: `src/test/java/com/zarlania/api/config/OpenApiVisibilityTest.java`

**Interfaces:**
- Consumes: Task 8's `/api/admin/**` endpoints.
- Produces: public `/v3/api-docs` without admin paths (URL unchanged, per ADR-0003); `/v3/api-docs/admin` + `/v3/api-docs/public` only when `zarlania.docs.expose-admin=true`.

**Empirically verified in this repo (2026-07-09, springdoc 3.0.3):** a plain (non-global) `OpenApiCustomizer` bean filters only the root document and does not touch group documents; defining any `GroupedOpenApi` keeps the root `/v3/api-docs` serving; springdoc auto-lists every group in `/v3/api-docs/swagger-config` with no way to hide one — hence the property gate.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/zarlania/api/config/OpenApiVisibilityTest.java`:

```java
package com.zarlania.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the admin API surface is absent from public docs and gated behind a property. */
class OpenApiVisibilityTest {

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  class DefaultVisibility {

    @Autowired private MockMvc mockMvc;

    @Test
    void publicDocKeepsItsUrlAndOmitsAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths['/accounts']").exists())
          .andExpect(content().string(Matchers.not(Matchers.containsString("/api/admin/"))));
    }

    @Test
    void adminGroupDocIsAbsentByDefault() throws Exception {
      mockMvc.perform(get("/v3/api-docs/admin")).andExpect(status().isNotFound());
    }
  }

  @Nested
  @SpringBootTest(properties = "zarlania.docs.expose-admin=true")
  @AutoConfigureMockMvc
  class ExposedForDevelopment {

    @Autowired private MockMvc mockMvc;

    @Test
    void adminGroupDocServesAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs/admin"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths['/api/admin/feature-toggles']").exists());
    }

    @Test
    void publicGroupDocOmitsAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs/public"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths['/accounts']").exists())
          .andExpect(content().string(Matchers.not(Matchers.containsString("/api/admin/"))));
    }

    @Test
    void rootDocStillOmitsAdminPaths() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(content().string(Matchers.not(Matchers.containsString("/api/admin/"))));
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OpenApiVisibilityTest`
Expected: FAIL — `publicDocKeepsItsUrlAndOmitsAdminPaths` finds `/api/admin/` in the root doc; `adminGroupDocIsAbsentByDefault` passes; the `ExposedForDevelopment` tests get 404s.

- [ ] **Step 3: Write the config**

`src/main/java/com/zarlania/api/config/OpenApiVisibilityConfig.java`:

```java
package com.zarlania.api.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the admin API surface ({@code /api/admin/**}) out of the public OpenAPI document while the
 * app has no authentication: the root {@code /v3/api-docs} (ADR-0003's public contract, read by
 * Swagger UI) is filtered by a customizer, and machine-readable admin/public group documents exist
 * only when {@code zarlania.docs.expose-admin=true} (a development aid). Springdoc lists every
 * registered group in the Swagger UI selector with no way to hide one, which is why the groups are
 * property-gated rather than always-on. Hiding docs is defense-in-depth, not security: the
 * endpoints themselves remain callable until real auth lands.
 */
@Configuration
public class OpenApiVisibilityConfig {

  /** Path prefix of the admin API surface, excluded from public docs. */
  static final String ADMIN_PATH_PREFIX = "/api/admin/";

  /**
   * Strips admin paths from the root (public) document. A plain — not global — customizer applies
   * only to the root document, leaving the property-gated group documents untouched.
   *
   * @return the customizer that removes {@code /api/admin/**} paths
   */
  @Bean
  public OpenApiCustomizer publicDocAdminPathFilter() {
    return openApi -> {
      if (openApi.getPaths() != null) {
        openApi.getPaths().keySet().removeIf(path -> path.startsWith(ADMIN_PATH_PREFIX)
            || path.equals(ADMIN_PATH_PREFIX.substring(0, ADMIN_PATH_PREFIX.length() - 1)));
      }
    };
  }

  /**
   * Development-only admin group document at {@code /v3/api-docs/admin}.
   *
   * @return the admin group definition
   */
  @Bean
  @ConditionalOnProperty(name = "zarlania.docs.expose-admin", havingValue = "true")
  public GroupedOpenApi adminOpenApi() {
    return GroupedOpenApi.builder().group("admin").pathsToMatch("/api/admin/**").build();
  }

  /**
   * Development-only public group document at {@code /v3/api-docs/public}, so the Swagger UI
   * selector offers both surfaces when admin docs are exposed.
   *
   * @return the public group definition
   */
  @Bean
  @ConditionalOnProperty(name = "zarlania.docs.expose-admin", havingValue = "true")
  public GroupedOpenApi publicOpenApi() {
    return GroupedOpenApi.builder().group("public").pathsToExclude("/api/admin/**").build();
  }
}
```

In `src/main/resources/application.properties`, append:

```properties
# Admin API docs are unpublished by default (the /api/admin/** surface is stripped from the public
# OpenAPI document). Set true locally to browse admin group docs in Swagger UI; never in production
# until real authentication exists.
zarlania.docs.expose-admin=false
```

Note: every real admin endpoint starts with `/api/admin/`, so the `startsWith` check does the work; the `equals` clause only defends against a hypothetical exact `/api/admin` mapping.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=OpenApiVisibilityTest`
Expected: PASS (5 tests). Also run `./mvnw test -Dtest=OpenApiTest` — the pre-existing docs test must still pass (root URL unchanged).

- [ ] **Step 5: Commit**

```bash
./mvnw spotless:apply
git add src/main/java/com/zarlania/api/config/OpenApiVisibilityConfig.java src/main/resources/application.properties src/test/java/com/zarlania/api/config/OpenApiVisibilityTest.java
git commit -m "feat: hide /api/admin surface from public OpenAPI docs (#66)"
```

---

### Task 10: ADRs and reference doc

**Files:**
- Create via CLI: two ADRs under `docs/adrs/`, one reference doc under `docs/reference/`.

- [ ] **Step 1: Check existing tags, then create the feature-toggle ADR**

Run `./scripts/adr tags` and reuse existing tags where they fit (e.g. `persistence`, `security`, `documentation`); add new ones (e.g. `features`) via the registry only if nothing fits — follow `./scripts/adr add-tag` if needed.

```bash
./scripts/adr new --name "Feature toggles: code registry, percentage state, trace-pinned evaluation" --tags features,persistence
```

Then edit the generated file's body (keep the generated frontmatter/meta table). Body sections (state decisions as law — do not reference the spec in prose; it may appear only as a bottom `## Links` entry):

- **Context and Problem Statement:** every merge deploys to production; regressions need a kill switch that does not require a redeploy, plus gradual (percentage) and per-organization rollout.
- **Decision Outcome:**
  - Toggles are registered in code via the `Feature` enum; a startup `ApplicationRunner` syncs the enum to the `feature_toggles` table — new constants inserted default-off (percentage 0), removed constants deleted (overrides cascade). The admin API can only change state, never create/delete toggles.
  - State is a single percentage 0–100 (0 = off, 100 = on, between = partial as a per-request coin flip), globally and per organization; an organization override wins unconditionally.
  - Decisions are pinned per trace id (W3C `traceparent`, `X-Trace-Id` fallback, generated otherwise) in a TTL- and size-bounded in-process Caffeine cache behind the `TraceDecisionCache` interface. **Render Key Value (managed Valkey) is the designated successor implementation when the service goes multi-instance.** Caffeine (Boot-BOM-managed) is adopted as a dependency.
  - Evaluation fails safe: a toggle without a DB row is off; unknown organizations fall back to global state.
  - Percentage semantics are per-request (a fresh coin flip per trace), not sticky bucketing: these toggles are deploy safety valves, not an experimentation platform.
- **Consequences:** include the current caveat that the prod DB is in-memory H2 (ADR-0010), so toggle state resets to default-off on every restart — fail-safe for a kill switch, but percentages/overrides must be re-applied after restarts until Postgres.
- **Links:** the spec path `docs/superpowers/specs/2026-07-08-feature-service-design.md` as a `Spec:` entry.

- [ ] **Step 2: Create the admin-surface ADR**

```bash
./scripts/adr new --name "Admin API surface under /api/admin, excluded from public OpenAPI" --tags security,documentation
```

Body: administrative endpoints live under `/api/admin/**`; the root `/v3/api-docs` (ADR-0003's public contract) strips them via an `OpenApiCustomizer`; machine-readable admin/public group docs exist only behind `zarlania.docs.expose-admin=true` (springdoc cannot hide a registered group from the Swagger UI selector). State explicitly: this is defense-in-depth obscurity, not security — the endpoints remain unauthenticated and callable until the repo-wide auth story lands, at which point the admin docs group gets gated by role. This refines, not contradicts, ADR-0003.

- [ ] **Step 3: Create the reference doc**

```bash
./scripts/ref new --title "Feature toggle behavior and lifecycle" --tags features,operations
```

(Reuse existing ref tags per `./scripts/ref tags` where they fit.) Content — behavior and rules only, **no endpoint shapes/status codes** (OpenAPI owns those):

- Lifecycle: add a `Feature` enum constant → deploy → toggle exists default-off → flip via the admin API → when the gated code is permanent, delete the constant → next deploy removes the row and its overrides.
- Evaluation rules: org override beats global unconditionally; 0/100 are deterministic; partial is a per-request coin flip; decisions are pinned per trace id (TTL-bounded), so a request and its chained hops always agree; callers re-using a trace id keep their decision until the TTL lapses; no trace context (startup/jobs) means fresh evaluation per call.
- Fail-safe rules: unknown/unsynced toggle = off; unknown org = global; restart resets state to default-off while prod runs in-memory H2.
- How to gate code: inject `FeatureToggleService`, call `isEnabled(Feature.X)` or `isEnabled(Feature.X, orgId)` once per decision point.
- The canary: `FEATURE_SERVICE_CANARY` is permanent, gates nothing, and exists to smoke-test the mechanism in production.

- [ ] **Step 4: Validate and commit**

Run: `./scripts/adr check && ./scripts/ref check`
Expected: both pass.

```bash
git add docs/adrs docs/reference
git commit -m "docs: feature-toggle ADR, admin-surface ADR, reference doc (#66)"
```

---

### Task 11: Version bump, full verification, PR

- [ ] **Step 1: Bump the version (feature ⇒ minor)**

```bash
./scripts/bump-version bump minor
git add pom.xml
git commit -m "chore: bump version for feature-toggle release (#66)"
```

- [ ] **Step 2: Full verification**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — all tests, Spotless, Checkstyle, SpotBugs/FindSecBugs, and the ≥ 80 % JaCoCo gate pass. Fix any finding at the root cause (never exclude/suppress). If JaCoCo flags an uncovered class, add the missing unit test rather than lowering anything.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin feat/66-feature-service
gh pr create --repo Zarlania/zarlania-api \
  --title "feat: feature-toggle service with per-org percentage rollout (#66)" \
  --label release:minor \
  --body "Closes #66.

## What
- \`features\` domain: code-registered toggles (\`Feature\` enum → DB sync at startup, default off)
- Percentage state (0 = off, 100 = on, between = per-request partial rollout) + per-org overrides
- Trace-pinned evaluation: TraceIdFilter (traceparent/X-Trace-Id) + bounded Caffeine decision cache behind the TraceDecisionCache seam (Render Key Value is the recorded multi-instance successor)
- Admin API under /api/admin/feature-toggles, stripped from the public OpenAPI doc; admin doc group behind zarlania.docs.expose-admin (default off)
- ADRs: feature-toggle architecture; /api/admin doc-visibility convention. Reference doc: toggle behavior/lifecycle.

Spec: docs/superpowers/specs/2026-07-08-feature-service-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

Expected: PR opens against `master` with the `release:minor` label; the "Release version bump" CI check passes because the pom minor bump matches the label.

---

## Self-Review Notes (already applied)

- Spec coverage: enum registry (T3), default-off sync + orphan cleanup (T3), percentage model + DB constraints (T1), org overrides + DB-level integrity (T1/T7), trace filter (T4), bounded TTL cache behind seam (T5), per-request coin flip + pinning + fail-safe (T6), admin API incl. 404/400 handling (T7/T8), OpenAPI hiding incl. property gate (T9), ADRs + reference doc (T10), minor release (T11).
- The `logging.pattern.level` addition (T4) is the minimal way to make the MDC trace id visible in logs; if log lines look wrong in local runs, verify the property landed exactly as written.
- Type consistency: `FeatureToggleAdminService` methods return `FeatureToggle` everywhere (controller relies on it); `CurrentTraceId` is an interface (unit tests inject lambdas); `RandomSource.nextDouble()` is consumed as `randomSource.nextDouble() * 100.0 < percentage`.
