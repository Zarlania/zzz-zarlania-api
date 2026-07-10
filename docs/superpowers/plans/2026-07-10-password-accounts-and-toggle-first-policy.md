# Password accounts + feature-toggle-first policy — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an account be created with a password — a bcrypt credential owned by the `identity` domain, stored at signup behind the `PASSWORD_ACCOUNTS` feature toggle — while adding operator-visible descriptions to the toggle registry and recording a repo-wide feature-toggle-first policy.

**Architecture:** The `identity` domain gains its first persistence layer: a `password_credentials` table, a `PasswordCredentialEntity`/`Repository`/`Service`, a `PasswordPolicy`, and a `PasswordEncoder` bean (`DelegatingPasswordEncoder` → bcrypt strength 12). `IdentityService.createAccount` creates the credential in its existing single transaction, but only when `FeatureToggleService.isEnabled(Feature.PASSWORD_ACCOUNTS)`. Cross-domain integrity uses a DB foreign key to `users` with no JPA association (ADR-0011). Separately, the `Feature` enum gains a `description`, persisted to `feature_toggles` and surfaced read-only through the admin API.

**Tech Stack:** Java 25, Spring Boot 4.1.x, Maven (`./mvnw`), Spring Data JPA + H2 + Flyway, `spring-security-crypto` (new), Lombok, JUnit 5 + MockMvc + AssertJ.

## Global Constraints

- **Branch:** work on `feat/password-accounts-toggle-first` (already created; the design spec is committed there).
- **Build gates (ADR-0007):** Spotless + Checkstyle (`google_checks`; `AbbreviationAsWordInName` allows **max 1** consecutive capital), SpotBugs/FindSecBugs, JaCoCo **≥ 80%** coverage. `./mvnw test` runs tests only; **`./mvnw verify`** runs all gates. Never silence a gate — fix the root cause.
- **TDD:** write the failing test first, watch it fail, implement minimally, watch it pass, commit. Commit after each task.
- **Domain decoupling (ADR-0011):** referential integrity via **DB foreign keys**; **no cross-domain JPA associations**; only DTOs (and exceptions) cross domain boundaries. Importing another domain's *service*/*DTO*/*exception* bean is allowed; importing another domain's *entity* is not.
- **Logging:** log surrogate ids only, never email or password; sanitize any logged value with `LogSanitizer.forLog(...)`.
- **Release (ADR-0009):** this is a **minor** feature. Bump `pom.xml` in-PR with `./scripts/bump-version bump minor` (0.6.1 → 0.7.0) and apply the `release:minor` label. Do this in the final task.
- **Password hashing:** `DelegatingPasswordEncoder` with default id `bcrypt`, `BCryptPasswordEncoder(12)`. Stored hashes are `{bcrypt}$2a$12$...`. Plaintext is never stored, logged, or returned.
- **Password policy (only enforced when the toggle is ON):** required/non-blank, **≥ 8 characters**, **≤ 72 bytes** (UTF-8), and at least one uppercase, one lowercase, one digit, and one symbol (symbol = any non-letter-or-digit). Violations throw `IllegalArgumentException` (mapped to **400** by the existing `ApiExceptionHandler`); messages must not echo the password.
- **Migrations:** additive Flyway files, next numbers are **V5** and **V6**. Never edit an existing migration.

---

## Task ordering

Workstream A (Tasks 1–4) — toggle-registry `description` — comes first because the new `PASSWORD_ACCOUNTS` enum constant carries a description and relies on that support existing. Workstream B (Tasks 5–10) builds the password feature. Workstream C (Tasks 11–14) records the policy, ADRs, reference doc, and version bump.

---

## Task 1: Add `description` to the `Feature` enum

**Files:**
- Modify: `src/main/java/com/zarlania/api/features/Feature.java`
- Test: `src/test/java/com/zarlania/api/features/FeatureTest.java` (create)

**Interfaces:**
- Produces: `Feature.description()` returning a non-blank human-readable description for each constant; new constant `Feature.PASSWORD_ACCOUNTS` with `toggleName()` == `"password-accounts"`.

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeatureTest {

  @Test
  void everyFeatureHasNonBlankNameAndDescription() {
    for (Feature feature : Feature.values()) {
      assertThat(feature.toggleName()).as("toggleName of %s", feature).isNotBlank();
      assertThat(feature.description()).as("description of %s", feature).isNotBlank();
    }
  }

  @Test
  void passwordAccountsToggleIsRegistered() {
    assertThat(Feature.PASSWORD_ACCOUNTS.toggleName()).isEqualTo("password-accounts");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=FeatureTest`
Expected: FAIL — `description()` does not exist / `PASSWORD_ACCOUNTS` undefined (compilation error).

- [ ] **Step 3: Implement the enum change**

Replace the body of `Feature.java` with:

```java
package com.zarlania.api.features;

/**
 * The code registry of feature toggles: adding a constant creates the toggle (synced to the DB at
 * startup, default off); removing the constant deletes it and its overrides on the next deploy.
 * Each constant carries its {@link #toggleName() toggle name} — a kebab-case string that is the
 * toggle's name in the DB and the admin API — and a human-readable {@link #description()} that is
 * persisted and surfaced to operators so they (and code reviewers deciding whether an existing
 * toggle already covers a change) can tell what the toggle gates.
 */
public enum Feature {

  /**
   * Permanent no-op toggle for smoke-testing the toggle mechanism end to end in production, and a
   * stable constant for tests. It gates no code path.
   */
  FEATURE_SERVICE_CANARY(
      "feature-service-canary",
      "Permanent no-op toggle for smoke-testing the feature-toggle mechanism in production. "
          + "Gates no real feature."),

  /** Gates accepting and storing a bcrypt password credential when an account is created. */
  PASSWORD_ACCOUNTS(
      "password-accounts",
      "Accept and store a bcrypt password credential when an account is created.");

  private final String toggleName;
  private final String description;

  Feature(String toggleName, String description) {
    this.toggleName = toggleName;
    this.description = description;
  }

  /**
   * The toggle's registered name — a kebab-case string used as the DB {@code name} and the admin
   * API identifier.
   *
   * @return the kebab-case toggle name
   */
  public String toggleName() {
    return toggleName;
  }

  /**
   * The toggle's human-readable description, persisted to the DB and returned by the admin API.
   *
   * @return the description of what this toggle gates
   */
  public String description() {
    return description;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FeatureTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/features/Feature.java src/test/java/com/zarlania/api/features/FeatureTest.java
git commit -m "feat: add description to Feature toggle registry (#72)"
```

---

## Task 2: Persist the toggle description (migration + entity)

**Files:**
- Create: `src/main/resources/db/migration/V5__add_feature_toggle_description.sql`
- Modify: `src/main/java/com/zarlania/api/features/entity/FeatureToggleEntity.java`
- Test: `src/test/java/com/zarlania/api/features/repository/FeatureToggleRepositoryIntegrationTest.java` (add a test method)

**Interfaces:**
- Produces: `FeatureToggleEntity.getDescription()` / `.setDescription(String)`; `feature_toggles.description VARCHAR(500) NOT NULL`.

- [ ] **Step 1: Write the failing test**

Add this method to `FeatureToggleRepositoryIntegrationTest` (match the existing class's setup/imports; it is an integration test against the real schema):

```java
  @Test
  void persistsAndReadsBackDescription() {
    FeatureToggleEntity toggle = new FeatureToggleEntity();
    toggle.setName("desc-toggle-" + java.util.UUID.randomUUID());
    toggle.setPercentage(0);
    toggle.setDescription("Gates the thing.");

    FeatureToggleEntity saved = featureToggleRepository.saveAndFlush(toggle);

    assertThat(featureToggleRepository.findById(saved.getId()))
        .get()
        .extracting(FeatureToggleEntity::getDescription)
        .isEqualTo("Gates the thing.");
  }
```

(If the test class does not already expose `featureToggleRepository` and `assertThat`, add the `@Autowired FeatureToggleRepository featureToggleRepository;` field and the `import static org.assertj.core.api.Assertions.assertThat;` import — mirror the sibling repository tests.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=FeatureToggleRepositoryIntegrationTest`
Expected: FAIL — `setDescription` undefined (compile) / column missing.

- [ ] **Step 3: Create the migration**

`V5__add_feature_toggle_description.sql`:

```sql
-- Feature-toggle descriptions are code-owned (the Feature enum) and synchronized at startup.
-- Existing rows (only ever present in a persistent DB; production H2 is in-memory and rebuilt each
-- boot) get an empty default that the startup synchronizer immediately overwrites from the enum.
ALTER TABLE feature_toggles
    ADD COLUMN description VARCHAR(500) NOT NULL DEFAULT '';
```

- [ ] **Step 4: Add the entity column**

In `FeatureToggleEntity.java`, add after the `percentage` field:

```java
  /**
   * Human-readable description, code-owned (the {@code Feature} enum) and written only by the
   * startup synchronizer. Defaults to empty string — the pre-sync placeholder that mirrors the
   * migration's {@code DEFAULT ''} — so an entity persisted before its description is set (e.g. in
   * a test) never violates the {@code NOT NULL} column.
   */
  @Setter
  @Column(name = "description", nullable = false, length = 500)
  private String description = "";
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FeatureToggleRepositoryIntegrationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V5__add_feature_toggle_description.sql src/main/java/com/zarlania/api/features/entity/FeatureToggleEntity.java src/test/java/com/zarlania/api/features/repository/FeatureToggleRepositoryIntegrationTest.java
git commit -m "feat: persist feature toggle description column (#72)"
```

---

## Task 3: Synchronizer inserts and updates descriptions

**Files:**
- Modify: `src/main/java/com/zarlania/api/features/service/FeatureToggleSynchronizer.java`
- Modify (tests): `src/test/java/com/zarlania/api/features/service/FeatureToggleSynchronizerIntegrationTest.java`

**Interfaces:**
- Consumes: `Feature.toggleName()`, `Feature.description()`; `FeatureToggleEntity.getDescription()/.setDescription(...)`.
- Produces: `FeatureToggleSynchronizer.synchronize(Map<String,String> registered)` where the map is toggleName → description. The old `synchronize(Set<String>)` signature is **removed**.

- [ ] **Step 1: Update the existing tests to the new signature and add description coverage**

In `FeatureToggleSynchronizerIntegrationTest`, change every `synchronize(Set.of(name))` call to `synchronize(Map.of(name, "desc"))` (and `synchronize(Set.of(keep))` → `synchronize(Map.of(keep, "desc"))`), add `import java.util.Map;`, and add:

```java
  @Test
  void insertsDescriptionForNewToggle() {
    String name = "sync-desc-" + java.util.UUID.randomUUID();

    synchronizer().synchronize(Map.of(name, "first description"));

    assertThat(featureToggleRepository.findByName(name))
        .get()
        .extracting(FeatureToggleEntity::getDescription)
        .isEqualTo("first description");
  }

  @Test
  void updatesDescriptionWhenItChanges() {
    String name = "sync-desc-" + java.util.UUID.randomUUID();
    synchronizer().synchronize(Map.of(name, "old description"));

    synchronizer().synchronize(Map.of(name, "new description"));

    assertThat(featureToggleRepository.findByName(name))
        .get()
        .extracting(FeatureToggleEntity::getDescription)
        .isEqualTo("new description");
  }
```

(Reuse the class's existing `synchronizer()` helper / `featureToggleRepository` field. If the class asserts an exact toggle *count* anywhere, that assertion is unaffected — these use unique random names.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=FeatureToggleSynchronizerIntegrationTest`
Expected: FAIL — `synchronize(Map)` does not exist (compile error).

- [ ] **Step 3: Implement the new synchronizer**

Replace the `run` and `synchronize` methods (and imports) so the class reads:

```java
package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the {@link Feature} code registry with the {@code feature_toggles} table during
 * application boot: registers new toggles default-off, updates the stored description of existing
 * toggles when the enum's description changes, and deletes rows whose enum constant no longer
 * exists (the DB {@code ON DELETE CASCADE} removes their organization overrides). Descriptions are
 * code-owned — this is the only writer of the column. Runs as an {@link ApplicationRunner} so a
 * failure aborts startup rather than leaving code and DB drifted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeatureToggleSynchronizer implements ApplicationRunner {

  private final FeatureToggleRepository featureToggleRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    synchronize(
        Arrays.stream(Feature.values())
            .collect(Collectors.toMap(Feature::toggleName, Feature::description)));
  }

  /**
   * Brings the {@code feature_toggles} table in line with the given registered toggles.
   *
   * @param registered map of registered toggle name to its code-owned description
   */
  @Transactional
  public void synchronize(Map<String, String> registered) {
    List<FeatureToggleEntity> existing = featureToggleRepository.findAll();
    Map<String, FeatureToggleEntity> byName =
        existing.stream().collect(Collectors.toMap(FeatureToggleEntity::getName, entity -> entity));

    List<FeatureToggleEntity> toSave = new ArrayList<>();

    for (Map.Entry<String, String> entry : registered.entrySet()) {
      FeatureToggleEntity current = byName.get(entry.getKey());
      if (current == null) {
        FeatureToggleEntity created = new FeatureToggleEntity();
        created.setName(entry.getKey());
        created.setPercentage(0);
        created.setDescription(entry.getValue());
        toSave.add(created);
      } else if (!entry.getValue().equals(current.getDescription())) {
        current.setDescription(entry.getValue());
        toSave.add(current);
      }
    }
    featureToggleRepository.saveAll(toSave);

    List<FeatureToggleEntity> orphaned =
        existing.stream().filter(toggle -> !registered.containsKey(toggle.getName())).toList();
    featureToggleRepository.deleteAll(orphaned);
    featureToggleRepository.flush();

    log.info(
        "Feature toggles synchronized: {} registered, {} inserted-or-updated, {} removed",
        registered.size(),
        toSave.size(),
        orphaned.size());
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=FeatureToggleSynchronizerIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/features/service/FeatureToggleSynchronizer.java src/test/java/com/zarlania/api/features/service/FeatureToggleSynchronizerIntegrationTest.java
git commit -m "feat: sync feature toggle descriptions from the enum at startup (#72)"
```

---

## Task 4: Surface description through the DTO and admin API

**Files:**
- Modify: `src/main/java/com/zarlania/api/features/dto/FeatureToggle.java`
- Modify: `src/main/java/com/zarlania/api/features/service/FeatureToggleMapper.java`
- Modify (test): `src/test/java/com/zarlania/api/features/controller/FeatureToggleAdminControllerTest.java`

**Interfaces:**
- Consumes: `FeatureToggleEntity.getDescription()`.
- Produces: `FeatureToggle` record with a `description` component (2nd position): `FeatureToggle(String name, String description, int percentage, List<FeatureToggleOrganizationOverride> organizationOverrides)`.

- [ ] **Step 1: Write the failing test**

In `FeatureToggleAdminControllerTest`, the `get` test asserts the canary toggle. Add a description assertion to that test (the canary's description comes from the enum). Locate the test that GETs a single toggle and asserts `$.name`/`$.percentage`, and add:

```java
        .andExpect(jsonPath("$.description").isNotEmpty())
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=FeatureToggleAdminControllerTest`
Expected: FAIL — `$.description` missing from the response.

- [ ] **Step 3: Add the DTO component**

Replace `FeatureToggle.java` with:

```java
package com.zarlania.api.features.dto;

import java.util.List;

/**
 * Immutable view of a feature toggle's full state for use across the domain boundary and in admin
 * API responses. This DTO — not the JPA {@code FeatureToggleEntity} — is the type passed throughout
 * the application.
 *
 * @param name the toggle's registered (enum-constant) name
 * @param description the toggle's code-owned, human-readable description
 * @param percentage the global percentage: 0 = off, 100 = on, in between = partial
 * @param organizationOverrides per-organization overrides; each wins unconditionally over the
 *     global percentage for its organization
 */
public record FeatureToggle(
    String name,
    String description,
    int percentage,
    List<FeatureToggleOrganizationOverride> organizationOverrides) {

  /**
   * Stores an immutable copy of the overrides list.
   *
   * @param name the toggle name
   * @param description the toggle description
   * @param percentage the global percentage
   * @param organizationOverrides the per-organization overrides
   */
  public FeatureToggle {
    organizationOverrides = List.copyOf(organizationOverrides);
  }
}
```

- [ ] **Step 4: Update the mapper**

In `FeatureToggleMapper.toDto`, change the return line to:

```java
    return new FeatureToggle(
        entity.getName(), entity.getDescription(), entity.getPercentage(), overrideDtos);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=FeatureToggleAdminControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/zarlania/api/features/dto/FeatureToggle.java src/main/java/com/zarlania/api/features/service/FeatureToggleMapper.java src/test/java/com/zarlania/api/features/controller/FeatureToggleAdminControllerTest.java
git commit -m "feat: expose feature toggle description via admin API (#72)"
```

---

## Task 5: Add `spring-security-crypto` and the `PasswordEncoder` bean

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/zarlania/api/identity/config/IdentityConfig.java`
- Test: `src/test/java/com/zarlania/api/identity/config/IdentityConfigTest.java` (create)

**Interfaces:**
- Produces: a Spring `PasswordEncoder` bean that encodes to `{bcrypt}`-prefixed hashes and verifies them.

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class IdentityConfigTest {

  @Autowired private PasswordEncoder passwordEncoder;

  @Test
  void encodesToBcryptPrefixedHashThatIsNotThePlaintext() {
    String raw = "Str0ng!Pass";
    String encoded = passwordEncoder.encode(raw);

    assertThat(encoded).startsWith("{bcrypt}").doesNotContain(raw);
    assertThat(passwordEncoder.matches(raw, encoded)).isTrue();
    assertThat(passwordEncoder.matches("wrong", encoded)).isFalse();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=IdentityConfigTest`
Expected: FAIL — no `PasswordEncoder` bean / class not found.

- [ ] **Step 3: Add the dependency**

In `pom.xml`, inside `<dependencies>` (near the other `org.springframework.*` entries), add:

```xml
		<dependency>
			<groupId>org.springframework.security</groupId>
			<artifactId>spring-security-crypto</artifactId>
		</dependency>
```

(No `<version>` — it is managed by the Spring Boot BOM. Do **not** add `spring-boot-starter-security`; only the standalone crypto jar is wanted, so no security filter chain is installed.)

- [ ] **Step 4: Create the config**

```java
package com.zarlania.api.identity.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Wiring for the {@code identity} domain. Provides the password encoder used to hash credentials at
 * account creation. A {@link DelegatingPasswordEncoder} prefixes each hash with its algorithm id
 * ({@code {bcrypt}}), so the algorithm is self-describing in the stored value and can be migrated
 * later (e.g. to Argon2) without a schema change — old hashes still verify.
 */
@Configuration
public class IdentityConfig {

  private static final String ENCODER_ID = "bcrypt";
  private static final int BCRYPT_STRENGTH = 12;

  /**
   * The password encoder for credential hashing: delegating, defaulting to bcrypt (strength 12).
   *
   * @return the delegating password encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    Map<String, PasswordEncoder> encoders =
        Map.of(ENCODER_ID, new BCryptPasswordEncoder(BCRYPT_STRENGTH));
    return new DelegatingPasswordEncoder(ENCODER_ID, encoders);
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=IdentityConfigTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/zarlania/api/identity/config/IdentityConfig.java src/test/java/com/zarlania/api/identity/config/IdentityConfigTest.java
git commit -m "feat: add spring-security-crypto bcrypt password encoder to identity (#72)"
```

---

## Task 6: `PasswordPolicy` boundary validation

**Files:**
- Create: `src/main/java/com/zarlania/api/identity/service/PasswordPolicy.java`
- Test: `src/test/java/com/zarlania/api/identity/service/PasswordPolicyTest.java` (create)

**Interfaces:**
- Produces: `PasswordPolicy.validate(String rawPassword)` — returns normally when valid; throws `IllegalArgumentException` (message never contains the password) when null/blank, `< 8` chars, `> 72` bytes, or missing any of uppercase/lowercase/digit/symbol.

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  @Test
  void acceptsAStrongPassword() {
    assertThatCode(() -> policy.validate("Str0ng!Pass")).doesNotThrowAnyException();
  }

  @Test
  void rejectsNull() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(null));
  }

  @Test
  void rejectsBlank() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("   "));
  }

  @Test
  void rejectsTooShort() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("Ab1!x"));
  }

  @Test
  void rejectsOver72Bytes() {
    String longPassword = "Aa1!" + "a".repeat(70); // 74 bytes
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(longPassword));
  }

  @Test
  void rejectsMissingUppercase() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("str0ng!pass"));
  }

  @Test
  void rejectsMissingLowercase() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("STR0NG!PASS"));
  }

  @Test
  void rejectsMissingDigit() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("Strong!Pass"));
  }

  @Test
  void rejectsMissingSymbol() {
    assertThatIllegalArgumentException().isThrownBy(() -> policy.validate("Str0ngPass"));
  }

  @Test
  void errorMessageNeverContainsThePassword() {
    String secret = "sneaky";
    assertThatIllegalArgumentException()
        .isThrownBy(() -> policy.validate(secret))
        .withMessageNotContaining(secret);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=PasswordPolicyTest`
Expected: FAIL — `PasswordPolicy` not found.

- [ ] **Step 3: Implement the policy**

```java
package com.zarlania.api.identity.service;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Validates a raw password against the account-creation policy at the boundary, failing fast. Used
 * only when the {@code PASSWORD_ACCOUNTS} toggle is enabled. Messages describe the rule violated
 * and never echo the supplied password. A violation is an {@link IllegalArgumentException}, which
 * the global {@code ApiExceptionHandler} maps to 400.
 */
@Component
public class PasswordPolicy {

  private static final int MIN_LENGTH = 8;
  private static final int MAX_BYTES = 72;

  /**
   * Validates the given raw password.
   *
   * @param rawPassword the caller-supplied password
   * @throws IllegalArgumentException if the password is null, blank, shorter than 8 characters,
   *     longer than 72 bytes, or missing an uppercase letter, lowercase letter, digit, or symbol
   */
  public void validate(String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new IllegalArgumentException("password is required");
    }
    if (rawPassword.length() < MIN_LENGTH) {
      throw new IllegalArgumentException("password must be at least " + MIN_LENGTH + " characters");
    }
    if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IllegalArgumentException("password must be at most " + MAX_BYTES + " bytes");
    }
    boolean hasUpper = false;
    boolean hasLower = false;
    boolean hasDigit = false;
    boolean hasSymbol = false;
    for (int i = 0; i < rawPassword.length(); i++) {
      char c = rawPassword.charAt(i);
      if (Character.isUpperCase(c)) {
        hasUpper = true;
      } else if (Character.isLowerCase(c)) {
        hasLower = true;
      } else if (Character.isDigit(c)) {
        hasDigit = true;
      } else {
        hasSymbol = true;
      }
    }
    if (!hasUpper || !hasLower || !hasDigit || !hasSymbol) {
      throw new IllegalArgumentException(
          "password must contain an uppercase letter, a lowercase letter, a digit, and a symbol");
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=PasswordPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/identity/service/PasswordPolicy.java src/test/java/com/zarlania/api/identity/service/PasswordPolicyTest.java
git commit -m "feat: add identity password policy validation (#72)"
```

---

## Task 7: Password credential table, entity, and repository

**Files:**
- Create: `src/main/resources/db/migration/V6__create_password_credentials.sql`
- Create: `src/main/java/com/zarlania/api/identity/entity/PasswordCredentialEntity.java`
- Create: `src/main/java/com/zarlania/api/identity/repository/PasswordCredentialRepository.java`
- Test: `src/test/java/com/zarlania/api/identity/repository/PasswordCredentialRepositoryIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (test base); `users(id)` (FK target).
- Produces: `PasswordCredentialEntity` (`getId`, `getUserId`/`setUserId`, `getPasswordHash`/`setPasswordHash`); `PasswordCredentialRepository extends JpaRepository<PasswordCredentialEntity, UUID>` with `Optional<PasswordCredentialEntity> findByUserId(UUID userId)`. Unique constraint `uq_password_credentials_user`.

- [ ] **Step 1: Write the failing test**

Look at `FeatureToggleRepositoryIntegrationTest` for the exact base class / annotations used by repository integration tests in this repo, and mirror it. A user row must exist first (FK), created via the autowired `UserService`.

```java
package com.zarlania.api.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.identity.entity.PasswordCredentialEntity;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class PasswordCredentialRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PasswordCredentialRepository passwordCredentialRepository;
  @Autowired private UserService userService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private User newUser() {
    return userService.create(unique("u") + "@example.com", unique("u"));
  }

  private PasswordCredentialEntity credentialFor(UUID userId) {
    PasswordCredentialEntity credential = new PasswordCredentialEntity();
    credential.setUserId(userId);
    credential.setPasswordHash("{bcrypt}$2a$12$abcdefghijklmnopqrstuv");
    return credential;
  }

  @Test
  void persistsAndFindsByUserId() {
    User user = newUser();
    passwordCredentialRepository.saveAndFlush(credentialFor(user.id()));

    assertThat(passwordCredentialRepository.findByUserId(user.id()))
        .get()
        .extracting(PasswordCredentialEntity::getUserId)
        .isEqualTo(user.id());
  }

  @Test
  void rejectsSecondCredentialForSameUser() {
    User user = newUser();
    passwordCredentialRepository.saveAndFlush(credentialFor(user.id()));

    assertThatThrownBy(() -> passwordCredentialRepository.saveAndFlush(credentialFor(user.id())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=PasswordCredentialRepositoryIntegrationTest`
Expected: FAIL — entity/repository/table absent.

- [ ] **Step 3: Create the migration**

`V6__create_password_credentials.sql`:

```sql
CREATE TABLE password_credentials (
    id            UUID                        NOT NULL,
    user_id       UUID                        NOT NULL,
    password_hash VARCHAR(255)                NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_password_credentials      PRIMARY KEY (id),
    CONSTRAINT uq_password_credentials_user UNIQUE (user_id),
    CONSTRAINT fk_password_credentials_user FOREIGN KEY (user_id) REFERENCES users (id)
);
```

- [ ] **Step 4: Create the entity**

```java
package com.zarlania.api.identity.entity;

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
 * A user's password credential — the first credential type owned by the {@code identity} domain.
 * References the owning user by id only (a DB foreign key, no JPA association) per ADR-0011; one
 * credential per user (unique {@code user_id}). Holds only the encoded hash, never plaintext.
 * Future credential types (e.g. OAuth identities) are sibling tables, not columns here.
 */
@Entity
@Table(name = "password_credentials")
@Getter
@NoArgsConstructor
public class PasswordCredentialEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  @Setter
  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;
}
```

- [ ] **Step 5: Create the repository**

```java
package com.zarlania.api.identity.repository;

import com.zarlania.api.identity.entity.PasswordCredentialEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link PasswordCredentialEntity}. Internal to the {@code identity} domain.
 * {@link #findByUserId(UUID)} is the lookup seam future authentication will use to verify a
 * credential.
 */
public interface PasswordCredentialRepository
    extends JpaRepository<PasswordCredentialEntity, UUID> {

  /**
   * Finds a user's password credential.
   *
   * @param userId the owning user's id
   * @return the credential, if one exists for that user
   */
  Optional<PasswordCredentialEntity> findByUserId(UUID userId);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=PasswordCredentialRepositoryIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V6__create_password_credentials.sql src/main/java/com/zarlania/api/identity/entity/PasswordCredentialEntity.java src/main/java/com/zarlania/api/identity/repository/PasswordCredentialRepository.java src/test/java/com/zarlania/api/identity/repository/PasswordCredentialRepositoryIntegrationTest.java
git commit -m "feat: add password_credentials table, entity, and repository (#72)"
```

---

## Task 8: `PasswordCredentialService`

**Files:**
- Create: `src/main/java/com/zarlania/api/identity/exception/PasswordCredentialAlreadyExistsException.java`
- Create: `src/main/java/com/zarlania/api/identity/service/PasswordCredentialService.java`
- Modify: `src/main/java/com/zarlania/api/web/ApiExceptionHandler.java`
- Test: `src/test/java/com/zarlania/api/identity/service/PasswordCredentialServiceIntegrationTest.java` (create)
- Test: `src/test/java/com/zarlania/api/web/ApiExceptionHandlerTest.java` (add a case)

**Interfaces:**
- Consumes: `PasswordPolicy.validate(...)`, `PasswordEncoder.encode(...)`, `PasswordCredentialRepository`, `ConstraintViolations.matches(...)` (from `com.zarlania.api.persistence`).
- Produces: `PasswordCredentialService.create(UUID userId, String rawPassword)` — validates the password, hashes it, and stores exactly one credential row. Throws `IllegalArgumentException` (from the policy, → 400) on an invalid password before touching the DB; throws `PasswordCredentialAlreadyExistsException` (→ 409) if a credential already exists for the user (the DB-enforced one-per-user invariant, caught and translated).

**Why the catch:** the spec enforces one-credential-per-user *at the DB layer and catches it in code* (repo convention — mirrors how `UserService` translates `uq_users_email`). Account creation always passes a brand-new user id so it cannot trip this today, but the catch is the correct enforcement pattern and the seam future auth (set-password-for-existing-user) will rely on.

- [ ] **Step 1: Create the domain exception**

```java
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
```

- [ ] **Step 2: Write the failing tests**

Service test — `PasswordCredentialServiceIntegrationTest`:

```java
package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.zarlania.api.identity.exception.PasswordCredentialAlreadyExistsException;
import com.zarlania.api.identity.repository.PasswordCredentialRepository;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordCredentialServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PasswordCredentialService passwordCredentialService;
  @Autowired private PasswordCredentialRepository passwordCredentialRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserService userService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private User newUser() {
    return userService.create(unique("u") + "@example.com", unique("u"));
  }

  @Test
  void storesABcryptHashThatVerifiesAndIsNotThePlaintext() {
    User user = newUser();
    String raw = "Str0ng!Pass";

    passwordCredentialService.create(user.id(), raw);

    var stored = passwordCredentialRepository.findByUserId(user.id()).orElseThrow();
    assertThat(stored.getPasswordHash()).startsWith("{bcrypt}").isNotEqualTo(raw);
    assertThat(passwordEncoder.matches(raw, stored.getPasswordHash())).isTrue();
  }

  @Test
  void rejectsAnInvalidPasswordWithoutStoringAnything() {
    User user = newUser();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> passwordCredentialService.create(user.id(), "weak"));

    assertThat(passwordCredentialRepository.findByUserId(user.id())).isEmpty();
  }

  @Test
  void rejectsASecondCredentialForTheSameUser() {
    User user = newUser();
    passwordCredentialService.create(user.id(), "Str0ng!Pass");

    assertThatExceptionOfType(PasswordCredentialAlreadyExistsException.class)
        .isThrownBy(() -> passwordCredentialService.create(user.id(), "An0ther!Pass"));
  }
}
```

Handler test — add to `ApiExceptionHandlerTest`:

```java
  @Test
  void mapsPasswordCredentialConflictToConflict() {
    ProblemDetail problem =
        handler.handlePasswordCredentialConflict(
            com.zarlania.api.identity.exception.PasswordCredentialAlreadyExistsException.forUserId(
                java.util.UUID.randomUUID(), null));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).isEqualTo("A password credential already exists for this user");
  }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=PasswordCredentialServiceIntegrationTest,ApiExceptionHandlerTest`
Expected: FAIL — `PasswordCredentialService` / `handlePasswordCredentialConflict` not found.

- [ ] **Step 4: Implement the service (with catch-and-translate)**

```java
package com.zarlania.api.identity.service;

import com.zarlania.api.identity.entity.PasswordCredentialEntity;
import com.zarlania.api.identity.exception.PasswordCredentialAlreadyExistsException;
import com.zarlania.api.identity.repository.PasswordCredentialRepository;
import com.zarlania.api.persistence.ConstraintViolations;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates password credentials for the {@code identity} domain. Validates the raw password against
 * {@link PasswordPolicy}, hashes it with the configured {@link PasswordEncoder}, and stores the
 * hash — never the plaintext. The one-credential-per-user invariant is enforced by the {@code
 * uq_password_credentials_user} unique constraint and translated to {@link
 * PasswordCredentialAlreadyExistsException} when hit (mirrors {@code UserService}'s handling of the
 * email/username constraints). Verification is the future seam authentication will add here.
 */
@Service
@RequiredArgsConstructor
public class PasswordCredentialService {

  /** Name of the one-per-user unique constraint in {@code V6__create_password_credentials.sql}. */
  private static final String USER_UNIQUE_CONSTRAINT = "uq_password_credentials_user";

  private final PasswordCredentialRepository passwordCredentialRepository;
  private final PasswordPolicy passwordPolicy;
  private final PasswordEncoder passwordEncoder;

  /**
   * Validates, hashes, and stores a password credential for the given user.
   *
   * @param userId the owning user's id
   * @param rawPassword the caller-supplied password
   * @throws IllegalArgumentException if the password fails {@link PasswordPolicy}
   * @throws PasswordCredentialAlreadyExistsException if the user already has a credential
   */
  @Transactional
  public void create(UUID userId, String rawPassword) {
    passwordPolicy.validate(rawPassword);
    PasswordCredentialEntity credential = new PasswordCredentialEntity();
    credential.setUserId(userId);
    credential.setPasswordHash(passwordEncoder.encode(rawPassword));
    try {
      // saveAndFlush forces the INSERT now so a duplicate surfaces here as the unique-constraint
      // violation and is reported as the domain exception rather than a raw persistence error.
      passwordCredentialRepository.saveAndFlush(credential);
    } catch (DataIntegrityViolationException ex) {
      if (ConstraintViolations.matches(ex, USER_UNIQUE_CONSTRAINT)) {
        throw PasswordCredentialAlreadyExistsException.forUserId(userId, ex);
      }
      throw ex;
    }
  }
}
```

- [ ] **Step 5: Add the exception handler**

In `ApiExceptionHandler.java`, add the import
`import com.zarlania.api.identity.exception.PasswordCredentialAlreadyExistsException;`
and a handler method next to `handlePersonalOrgConflict` (reuse the existing private `conflict(...)` helper):

```java
  /** Defensive: cannot occur for a brand-new account, but mapped to 409 rather than 500. */
  @ExceptionHandler(PasswordCredentialAlreadyExistsException.class)
  ProblemDetail handlePasswordCredentialConflict(PasswordCredentialAlreadyExistsException ex) {
    return conflict("A password credential already exists for this user");
  }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=PasswordCredentialServiceIntegrationTest,ApiExceptionHandlerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/zarlania/api/identity/exception/PasswordCredentialAlreadyExistsException.java src/main/java/com/zarlania/api/identity/service/PasswordCredentialService.java src/main/java/com/zarlania/api/web/ApiExceptionHandler.java
git add src/test/java/com/zarlania/api/identity/service/PasswordCredentialServiceIntegrationTest.java src/test/java/com/zarlania/api/web/ApiExceptionHandlerTest.java
git commit -m "feat: add PasswordCredentialService to store hashed credentials (#72)"
```

---

## Task 9: Gate credential creation into account creation

**Files:**
- Modify: `src/main/java/com/zarlania/api/identity/dto/CreateAccountRequest.java`
- Modify: `src/main/java/com/zarlania/api/identity/service/IdentityService.java`
- Modify: `src/main/java/com/zarlania/api/identity/controller/IdentityController.java`
- Modify (tests): `src/test/java/com/zarlania/api/identity/service/IdentityServiceIntegrationTest.java`, `src/test/java/com/zarlania/api/identity/service/IdentityServiceTransactionalTest.java`

**Interfaces:**
- Consumes: `FeatureToggleService.isEnabled(Feature)`, `Feature.PASSWORD_ACCOUNTS`, `PasswordCredentialService.create(UUID, String)`.
- Produces: `IdentityService.createAccount(String email, String username, String password)` (now 3-arg); `CreateAccountRequest` with nullable `password()`.

- [ ] **Step 1: Update the existing service-test call sites (they must compile against the new signature)**

In `IdentityServiceTransactionalTest`, change the call to `identityService.createAccount(victimEmail, collidingName)` → `identityService.createAccount(victimEmail, collidingName, null)`. In `IdentityServiceIntegrationTest`, change every `createAccount(email, username)` call to `createAccount(email, username, null)` (the `PASSWORD_ACCOUNTS` toggle is off by default, so `null` is ignored — existing behavior is preserved).

- [ ] **Step 2: Add the toggle-on integration tests**

Add to `IdentityServiceIntegrationTest` (this class already runs against the real context; mirror its existing autowiring style and `unique(...)` helper). Autowire the admin service to flip the toggle and the credential repository to assert:

```java
  @Autowired private com.zarlania.api.features.service.FeatureToggleAdminService featureToggleAdminService;
  @Autowired private com.zarlania.api.identity.repository.PasswordCredentialRepository passwordCredentialRepository;

  @Test
  void storesPasswordCredentialWhenToggleEnabled() {
    featureToggleAdminService.setPercentage("password-accounts", 100);
    String email = unique("pw") + "@example.com";

    var account = identityService.createAccount(email, unique("pw"), "Str0ng!Pass");

    assertThat(passwordCredentialRepository.findByUserId(account.user().id())).isPresent();
  }

  @Test
  void storesNoCredentialWhenToggleDisabled() {
    // Toggle is off by default; a supplied password is ignored.
    String email = unique("np") + "@example.com";

    var account = identityService.createAccount(email, unique("np"), "Str0ng!Pass");

    assertThat(passwordCredentialRepository.findByUserId(account.user().id())).isEmpty();
  }

  @Test
  void rejectsInvalidPasswordWhenToggleEnabledAndCreatesNoUser() {
    featureToggleAdminService.setPercentage("password-accounts", 100);
    String email = unique("bad") + "@example.com";

    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> identityService.createAccount(email, unique("bad"), "weak"));

    assertThat(userService.findByEmail(email)).isEmpty(); // whole transaction rolled back
  }
```

(If `IdentityServiceIntegrationTest` uses a rollback-per-test transaction, setting the toggle percentage participates in that same transaction and is visible to the `createAccount` call. Confirm against the class's existing pattern; the sibling `FeatureToggleAdminServiceIntegrationTest` shows the established way to enable a toggle in a test.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=IdentityServiceIntegrationTest,IdentityServiceTransactionalTest`
Expected: FAIL — `createAccount` 3-arg signature does not exist.

- [ ] **Step 4: Update `CreateAccountRequest`**

```java
package com.zarlania.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /accounts}, validated at the HTTP boundary. The email/username
 * size limits mirror the {@code users.email} (320) and {@code users.username} (100) columns; these
 * bounds also exist as domain invariants in {@code UserService} (defense in depth at two
 * boundaries — the only accepted duplication, since exposing the {@code users} constants here would
 * breach the domain boundary).
 *
 * <p>{@code password} is optional at the type level and carries no bean-validation annotations: it
 * is only consulted when the {@code PASSWORD_ACCOUNTS} feature toggle is enabled, and is then
 * validated at runtime by {@code PasswordPolicy} (required-ness depends on toggle state, which a
 * static annotation cannot express). When the toggle is off it is ignored entirely. Unknown extra
 * fields in the body are silently ignored (Spring's default {@code FAIL_ON_UNKNOWN_PROPERTIES=
 * false}) so the frontend may send fields the current toggle state does not act on.
 *
 * @param email the new user's email
 * @param username the new user's unique public handle
 * @param password the new user's password, honored only when the password-accounts toggle is on
 */
public record CreateAccountRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 100) String username,
    String password) {}
```

- [ ] **Step 5: Update `IdentityService`**

Add the two new dependencies and gate credential creation. The full class:

```java
package com.zarlania.api.identity.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleService;
import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.logging.LogSanitizer;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates account creation across the {@code users}, {@code organizations}, and (when the
 * {@code PASSWORD_ACCOUNTS} toggle is enabled) {@code identity} credential stores. The public
 * surface of the {@code identity} domain. Injects each collaborator as a Spring bean and exchanges
 * only DTOs (ADR-0011).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdentityService {

  private final UserService userService;
  private final OrganizationService organizationService;
  private final PasswordCredentialService passwordCredentialService;
  private final FeatureToggleService featureToggleService;

  /**
   * Creates an account — a user, their personal organization named after the username, and (when
   * the password-accounts feature is enabled) a password credential — in a single transaction.
   * Because every delegated service joins this transaction, a failure anywhere rolls the whole
   * account back, so no partially-created account remains.
   *
   * <p>The toggle is evaluated globally: there is no organization context at signup, since the
   * personal organization is being created here. When the toggle is off, {@code password} is
   * ignored and the account is created exactly as before.
   *
   * @param email the new user's email
   * @param username the new user's unique public handle
   * @param password the new user's password; honored only when the toggle is enabled, where it is
   *     required and validated by {@code PasswordPolicy}
   * @return the created account (user + personal organization)
   */
  @Transactional
  public Account createAccount(String email, String username, String password) {
    User user = userService.create(email, username);
    Organization personalOrganization =
        organizationService.createPersonalOrganization(user.id(), user.username());
    if (featureToggleService.isEnabled(Feature.PASSWORD_ACCOUNTS)) {
      passwordCredentialService.create(user.id(), password);
    }
    // Log identifiers only — never the email or password (PII/secret). Sanitised via LogSanitizer
    // to keep the CRLF_INJECTION_LOGS detector satisfied.
    log.info(
        "Created account: userId={}, organizationId={}",
        LogSanitizer.forLog(user.id()),
        LogSanitizer.forLog(personalOrganization.id()));
    return new Account(user, personalOrganization);
  }
}
```

- [ ] **Step 6: Update `IdentityController`**

Change the call in `createAccount` to pass the password:

```java
    Account account =
        identityService.createAccount(request.email(), request.username(), request.password());
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=IdentityServiceIntegrationTest,IdentityServiceTransactionalTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/zarlania/api/identity/
git add src/test/java/com/zarlania/api/identity/service/IdentityServiceIntegrationTest.java src/test/java/com/zarlania/api/identity/service/IdentityServiceTransactionalTest.java
git commit -m "feat: create password credential at signup behind PASSWORD_ACCOUNTS toggle (#72)"
```

---

## Task 10: End-to-end contract for `POST /accounts` with a password

**Files:**
- Modify (test): `src/test/java/com/zarlania/api/identity/controller/IdentityControllerTest.java`

**Interfaces:**
- Consumes: the running MockMvc stack; `FeatureToggleAdminService` to enable the toggle in-test.

- [ ] **Step 1: Write the failing tests**

Add to `IdentityControllerTest`. Autowire the admin service, add a `body` overload that includes a password and one that adds an unrecognized field, and cover both toggle states. (Keep the existing `body(email, username)` helper and existing tests — they already exercise the toggle-off passwordless path.)

```java
  @Autowired
  private com.zarlania.api.features.service.FeatureToggleAdminService featureToggleAdminService;

  private static String bodyWithPassword(String email, String username, String password) {
    return "{\"email\":\"" + email + "\",\"username\":\"" + username + "\",\"password\":\""
        + password + "\"}";
  }

  @Test
  void ignoresPasswordAndUnknownFieldsWhenToggleOff() throws Exception {
    String username = unique("off");
    String email = username + "@example.com";
    String body =
        "{\"email\":\"" + email + "\",\"username\":\"" + username
            + "\",\"password\":\"Str0ng!Pass\",\"nickname\":\"ignored\"}";

    mockMvc
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.username").value(username))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.user.password").doesNotExist());
  }

  @Test
  void createsAccountWithPasswordWhenToggleOnAndNeverReturnsIt() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);
    String username = unique("on");
    String email = username + "@example.com";

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithPassword(email, username, "Str0ng!Pass")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.email").value(email))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.user.password").doesNotExist());
  }

  @Test
  void rejectsMissingPasswordWhenToggleOn() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("m") + "@example.com", unique("m"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("password is required"));
  }

  @Test
  void rejectsWeakPasswordWhenToggleOn() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithPassword(unique("w") + "@example.com", unique("w"), "weak")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }
```

- [ ] **Step 2: Run tests to verify they fail, then pass**

Run: `./mvnw -q test -Dtest=IdentityControllerTest`
Expected: the four new tests are GREEN because Tasks 6–9 already implemented the behavior. If `rejectsMissingPasswordWhenToggleOn` fails because the toggle write is not visible within the request in this `@Transactional` MockMvc test, follow the same enabling approach the feature domain's controller/admin tests use (the toggle write and the request run in the same thread and transaction, so it should be visible). Do **not** weaken assertions to pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/zarlania/api/identity/controller/IdentityControllerTest.java
git commit -m "test: e2e contract for password account creation (#72)"
```

- [ ] **Step 4: Full verify (gates + coverage) before moving to docs**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS — Spotless, Checkstyle, SpotBugs/FindSecBugs, and JaCoCo ≥ 80% all pass. Fix any gate failure at its root (formatting via `./mvnw spotless:apply`; coverage by adding a missing meaningful test; never by excludes or `@SuppressWarnings`).

---

## Task 11: ADR + docs for the feature-toggle-first policy

**Files:**
- Create: a new ADR via the `adr-create` skill (it assigns the next id — expected **0016**).
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Author the ADR**

Invoke the `adr-create` skill. Title: **"Gate every behavior change behind a feature toggle."** Tags (all already in `docs/adrs/_tags.md`): `governance`, `process`. Content to capture:

- **Context:** every merge to `master` deploys to production; the `features` domain (ADR-0014) now exists, so new behavior can ship dark and be flipped/ramped/killed without a redeploy.
- **Decision:** any change that introduces or alters user-observable behavior must be gated by a feature toggle. Before adding a `Feature` constant, check whether an existing toggle already covers the change (its `description()` aids this) and reuse it; add a new constant only when none fits.
- **Cleanup obligation:** once a gated feature is permanent, remove its `Feature` constant (the enum-deletion lifecycle in reference doc 000003), so toggles do not accumulate.
- **Carve-outs (no toggle required):** pure refactors / no behavior change, docs & ADRs, build/CI/tooling, the feature-toggle machinery itself, and additive DB migrations that scaffold a gated feature.
- **Consequences:** mechanics are not restated here — they live in ADR-0014 and reference doc 000003; this ADR adds only the mandate and the reuse-first + cleanup workflow. Reference ADR-0014.

- [ ] **Step 2: Validate the ADR**

Run: `./scripts/adr check`
Expected: PASS (metadata table, tag ordering, registry all valid).

- [ ] **Step 3: Update CLAUDE.md**

Under **## Non-negotiables**, add a bullet:

```markdown
- **Every behavior change ships behind a feature toggle.** New or changed user-observable
  behavior must be gated (see ADR-0016). Reuse an existing `Feature` toggle when one covers
  the change — check `Feature` descriptions first — and add a new constant only when none
  fits. Remove a toggle's constant once its feature is permanent. Carve-outs: pure refactors,
  docs/ADRs, build/CI/tooling, the toggle machinery itself, and additive scaffolding
  migrations.
```

- [ ] **Step 4: Update README.md**

In the **## Making a change** ordered list, add an item (renumber as needed):

```markdown
6. Gate new or changed behavior behind a feature toggle (ADR-0016): reuse an existing
   `Feature` if one fits, otherwise add a constant. Non-behavioral changes (refactors,
   docs, tooling) are exempt.
```

- [ ] **Step 5: Commit**

```bash
git add docs/adrs/ CLAUDE.md README.md
git commit -m "docs: record feature-toggle-first policy (ADR-0016) (#72)"
```

---

## Task 12: ADR for password credentials & hashing

**Files:**
- Create: a new ADR via the `adr-create` skill (expected **0017**).

- [ ] **Step 1: Author the ADR**

Invoke the `adr-create` skill. Title: **"Store password credentials in the identity domain, hashed with bcrypt."** Tags (all existing): `architecture`, `persistence`, `security`. Content:

- **Context:** accounts need passwords; authentication (login, JWT, OAuth) is a later repo-wide story. Something must own credential storage now without pulling in a full auth stack.
- **Decision:** the `identity` domain owns credentials. Passwords are stored in a `password_credentials` table (one per user, unique `user_id`, DB FK to `users` per ADR-0011, no JPA association). Hashing uses the standalone `spring-security-crypto` dependency — **not** `spring-boot-starter-security`, so no filter chain/autoconfig is installed — via a `DelegatingPasswordEncoder` defaulting to `bcrypt` strength 12. Hashes are stored `{bcrypt}`-prefixed so the algorithm is self-describing and migratable with no schema change. Creation is gated by the `PASSWORD_ACCOUNTS` toggle (ADR-0016).
- **Future fit:** verification is a later method on `PasswordCredentialService`; OAuth is a sibling credential table; JWT is a future session concern consuming identity's verification. Nothing here is torn out when auth lands.
- **Consequences:** a new dependency (`spring-security-crypto`, version managed by the Boot BOM); passwords never stored/logged/returned in plaintext; bcrypt's 72-byte input cap is enforced by policy. Reference ADR-0011 and ADR-0016.

- [ ] **Step 2: Validate**

Run: `./scripts/adr check`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/adrs/
git commit -m "docs: record password credentials & bcrypt hashing decision (ADR-0017) (#72)"
```

---

## Task 13: Update reference doc 000003 for toggle descriptions

**Files:**
- Modify: `docs/reference/000003-feature-toggle-behavior-and-lifecycle.md`

- [ ] **Step 1: Edit the reference doc**

Use `./scripts/ref show 000003` to view it. In the **Lifecycle** and/or **Rules / constraints** sections, add that each toggle carries a **code-owned description** (from the `Feature` enum) that the startup synchronizer **inserts and updates** on the `feature_toggles` row and that is surfaced **read-only** through the admin API (operators read it; they never edit it — a toggle is born in code). Do not restate endpoint shapes (owned by the OpenAPI doc, ADR-0003). Keep the doc's existing style and the `<!-- ref-meta -->` block intact; bump the `updated` date in the front-matter and meta table to `2026-07-10`.

- [ ] **Step 2: Validate**

Run: `./scripts/ref check`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/reference/000003-feature-toggle-behavior-and-lifecycle.md
git commit -m "docs: document toggle descriptions in reference doc 000003 (#72)"
```

---

## Task 14: Version bump and final verification

**Files:**
- Modify: `pom.xml` (via script)

- [ ] **Step 1: Bump the version (minor)**

Run: `./scripts/bump-version bump minor`
Expected: `pom.xml` `<version>` goes `0.6.1` → `0.7.0`. (Apply the `release:minor` label when the PR is opened.)

- [ ] **Step 2: Final full verify**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS with all gates green and coverage ≥ 80%.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: bump version to 0.7.0 (#72)"
```

---

## Self-review notes (for the executor)

- **`#72`** placeholders in commit messages: replace with the real GitHub issue number (CLAUDE.md requires every change to tie to an issue; the PR title must reference it). If no issue exists yet, create one before opening the PR.
- **Toggle visibility in tests:** several tests flip `password-accounts` to 100 via `FeatureToggleAdminService` and then act in the same transaction/thread. The sibling `FeatureToggleAdminServiceIntegrationTest` is the reference for the established enabling pattern — follow it rather than inventing a new mechanism.
- **Coverage:** if `./mvnw verify` reports < 80% on new code, add a *meaningful* behavioral test (e.g. an additional `PasswordPolicy` branch or an `IdentityService` path), never an exclude or threshold change.
- **Do not edit existing migrations** (V1–V4); only add V5 and V6.
```
