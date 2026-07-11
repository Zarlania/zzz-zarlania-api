# Login/Auth Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Password login issuing organization-scoped JWT access tokens + rotating refresh
tokens, enforced by a deny-by-default Spring Security chain (proven on `/api/admin/**`),
with the default password hash switched to Argon2id.

**Architecture:** A new `auth` domain owns the `SecurityFilterChain`, JWT issuance
(HS256, 15-min TTL, claims `sub`/`org`/`token_use`/`jti`/`iss`), and a hashed, rotating,
org-scoped refresh-token store with family reuse detection. Identity keeps owning
credentials (verification seam added); organizations gains a personal-org lookup. All new
behavior is gated by two new feature toggles.

**Tech Stack:** Java 25, Spring Boot 4.1.0 (Spring Security 7 via
`spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`; Nimbus JOSE
bundled — no third-party JWT library), BouncyCastle for Argon2id, H2/Flyway/JPA, Maven
wrapper.

**Spec:** `docs/superpowers/specs/2026-07-10-login-auth-design.md` (approved).
**Issue:** #75. **Branch:** `feat/75-login-auth` (already created). Deferred: OAuth #76,
rate limiting & token purge #77.

## Global Constraints

- Every commit message references issue `#75`; commit after every task.
- `./mvnw test` iterates fast (skips Checkstyle/SpotBugs/JaCoCo); `./mvnw verify` runs the
  full gates and must pass before the PR. Coverage floor ≥ 80% (JaCoCo).
- Never silence gates; a genuine tool false positive gets the repo's documented-exception
  pattern: GitHub issue + scoped entry in `config/spotbugs/spotbugs-exclude.xml` +
  compensating test (see existing entries in that file for the format).
- Checkstyle google_checks: max 1 consecutive capital in names — write `Jwt`, never `JWT`,
  in identifiers. Javadoc every public class and public method (match existing style).
- ADR-0011: no cross-domain JPA associations or entity imports in production code;
  cross-domain calls exchange DTOs only. DB-level FKs provide integrity.
- ADR-0016: new behavior is toggle-gated; toggles default **off** (percentage 0), so every
  existing test and production behavior is unchanged until an operator flips them.
- Never commit secrets. The test signing secret in `pom.xml` is a deliberately fake,
  low-entropy, test-only value and must stay that way.
- Logging: surrogate ids only (never emails, passwords, raw tokens, or JWTs), wrapped in
  `LogSanitizer.forLog(...)` (use `com.zarlania.api.logging.LogSanitizer`).
- Test taxonomy: controllers → e2e via `@SpringBootTest` + `@AutoConfigureMockMvc` +
  `@Transactional` (import `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
  — Boot 4 package); services → integration (+ unit where logic is pure); repositories →
  integration only. Do not create `*TransactionalTest`-suffixed classes unless a test
  must commit (none here should).
- New-file javadoc conventions, Lombok (`@RequiredArgsConstructor`, `@Getter`/`@Setter` on
  entities), and record DTOs must match the existing domain code shown in each task.

---

### Task 1: Feature toggle constants `PASSWORD_LOGIN` and `AUTH_ENFORCEMENT`

**Files:**
- Modify: `src/main/java/com/zarlania/api/features/Feature.java`
- Test: `src/test/java/com/zarlania/api/features/FeatureTest.java` (create if absent; if a
  test for `Feature` already exists under `src/test/java/com/zarlania/api/features/`, add
  the methods there instead)

**Interfaces:**
- Consumes: existing `Feature` enum (constants carry `toggleName()` + `description()`).
- Produces: `Feature.PASSWORD_LOGIN` (toggle name `password-login`) and
  `Feature.AUTH_ENFORCEMENT` (toggle name `auth-enforcement`) — later tasks reference these
  constants and toggle names verbatim. The startup synchronizer auto-creates the DB rows;
  no migration needed.

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The code registry contract for the auth toggles introduced by issue #75. */
class FeatureTest {

  @Test
  void passwordLoginToggleIsRegistered() {
    assertThat(Feature.PASSWORD_LOGIN.toggleName()).isEqualTo("password-login");
    assertThat(Feature.PASSWORD_LOGIN.description()).isNotBlank();
  }

  @Test
  void authEnforcementToggleIsRegistered() {
    assertThat(Feature.AUTH_ENFORCEMENT.toggleName()).isEqualTo("auth-enforcement");
    assertThat(Feature.AUTH_ENFORCEMENT.description()).isNotBlank();
  }

  @Test
  void toggleNamesAreUnique() {
    long distinct =
        Arrays.stream(Feature.values()).map(Feature::toggleName).distinct().count();
    assertThat(distinct).isEqualTo(Feature.values().length);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FeatureTest`
Expected: COMPILE ERROR — `PASSWORD_LOGIN` / `AUTH_ENFORCEMENT` do not exist.

- [ ] **Step 3: Add the two constants**

In `Feature.java`, after the `PASSWORD_ACCOUNTS` constant, add (keep the existing
constant-per-entry javadoc style):

```java
  /** Gates the password-login surface: POST /auth/login, /auth/refresh, /auth/logout. */
  PASSWORD_LOGIN(
      "password-login",
      "Enable the /auth endpoints: password login issuing org-scoped JWT access tokens "
          + "plus rotating refresh tokens, refresh, and logout. Off means 404."),

  /** Gates deny-by-default auth enforcement on every non-permit-listed path. */
  AUTH_ENFORCEMENT(
      "auth-enforcement",
      "Require a valid JWT bearer token on every endpoint outside the public permit-list "
          + "(e.g. /api/admin/**). Off preserves the pre-auth behavior.");
```

(Replace the `;` terminating `PASSWORD_ACCOUNTS` with `,` accordingly.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=FeatureTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/features/Feature.java src/test/java/com/zarlania/api/features/FeatureTest.java
git commit -m "feat: register password-login and auth-enforcement toggles (#75)"
```

---

### Task 2: Argon2id becomes the default password hash; policy cap 72 bytes → 128 chars

**Files:**
- Modify: `pom.xml` (add BouncyCastle dependency)
- Modify: `src/main/java/com/zarlania/api/identity/config/IdentityConfig.java`
- Modify: `src/main/java/com/zarlania/api/identity/service/PasswordPolicy.java`
- Test: existing identity tests asserting `{bcrypt}` and the 72-byte cap (find with
  `grep -rln 'bcrypt\|72' src/test/java/com/zarlania/api/identity`) plus new assertions

**Interfaces:**
- Consumes: existing `PasswordEncoder` bean (`DelegatingPasswordEncoder`), existing
  `PasswordPolicy.validate(String)`.
- Produces: the same `PasswordEncoder` bean now defaulting to Argon2id — `encode(...)`
  returns `{argon2}`-prefixed hashes; bcrypt hashes remain verifiable via the delegating
  map (no rehash machinery — none exists to migrate). `PasswordPolicy` accepts up to 128
  characters (code points), min 8 + character classes unchanged. Task 8's `verify` uses
  this bean.

- [ ] **Step 1: Add the BouncyCastle dependency**

In `pom.xml` `<dependencies>`, directly after the `spring-security-crypto` dependency:

```xml
		<!-- Supplies the Argon2 primitive used by spring-security-crypto's
		     Argon2PasswordEncoder (ADR: Argon2id default, amending ADR-0017). -->
		<dependency>
			<groupId>org.bouncycastle</groupId>
			<artifactId>bcprov-jdk18on</artifactId>
			<version>1.81</version>
		</dependency>
```

(If `1.81` fails to resolve, use the latest stable `bcprov-jdk18on` version.)

- [ ] **Step 2: Write/adjust the failing tests**

Locate the existing encoder test (`grep -rln 'bcrypt' src/test/java/com/zarlania/api/identity`;
expected: an `IdentityConfig` test and/or `PasswordCredentialService` tests). Change every
assertion that expects a `{bcrypt}` prefix to expect `{argon2}`, and add to the
`IdentityConfig` test class:

```java
  @Test
  void encodesWithArgon2idByDefaultAndStillVerifies() {
    PasswordEncoder encoder = new IdentityConfig().passwordEncoder();
    String hash = encoder.encode("Str0ng!Pass");
    assertThat(hash).startsWith("{argon2}");
    assertThat(hash).startsWith("{argon2}$argon2id$"); // id variant, not i/d
    assertThat(encoder.matches("Str0ng!Pass", hash)).isTrue();
    assertThat(encoder.matches("wrong", hash)).isFalse();
  }
```

In the `PasswordPolicy` test class (find it under
`src/test/java/com/zarlania/api/identity/service/`), replace the 72-byte-cap test(s) with:

```java
  @Test
  void accepts128CharacterPassword() {
    // 124 filler chars + the 4 required classes = exactly 128.
    String password = "Aa1!" + "x".repeat(124);
    assertThatCode(() -> passwordPolicy.validate(password)).doesNotThrowAnyException();
  }

  @Test
  void rejects129CharacterPassword() {
    String password = "Aa1!" + "x".repeat(125);
    assertThatThrownBy(() -> passwordPolicy.validate(password))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("password must be at most 128 characters");
  }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -Dtest='PasswordPolicy*,IdentityConfig*,PasswordCredential*'`
Expected: FAIL — `{argon2}` prefix absent; 128-char password rejected with the old
byte-cap message.

- [ ] **Step 4: Implement**

`IdentityConfig.java` — replace the bean body and constants (update the class javadoc to
say Argon2id default with bcrypt still verifiable):

```java
  private static final String ENCODER_ID = "argon2";

  // OWASP-recommended Argon2id parameters: 16-byte salt, 32-byte hash,
  // parallelism 1, 19 MiB memory, 2 iterations.
  private static final int SALT_LENGTH_BYTES = 16;
  private static final int HASH_LENGTH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19_456;
  private static final int ITERATIONS = 2;

  private static final int BCRYPT_STRENGTH = 12;

  /**
   * The password encoder for credential hashing: delegating, defaulting to Argon2id with
   * OWASP parameters. The {@code bcrypt} delegate remains registered so any {@code
   * {bcrypt}}-prefixed hash still verifies (an inherent property of the delegating
   * encoder — no rehash machinery exists because no bcrypt hashes exist).
   *
   * @return the delegating password encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    Map<String, PasswordEncoder> encoders =
        Map.of(
            ENCODER_ID,
                new Argon2PasswordEncoder(
                    SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS),
            "bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH));
    return new DelegatingPasswordEncoder(ENCODER_ID, encoders);
  }
```

Add import `org.springframework.security.crypto.argon2.Argon2PasswordEncoder`.

`PasswordPolicy.java` — replace the `MAX_BYTES` constant and its check:

```java
  private static final int MAX_LENGTH = 128;
```

```java
    if (rawPassword.codePointCount(0, rawPassword.length()) > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "password must be at most " + MAX_LENGTH + " characters");
    }
```

Remove the now-unused `java.nio.charset.StandardCharsets` import and update the class/method
javadoc (72-byte bcrypt cap → 128-character ceiling; Argon2 does not truncate).

- [ ] **Step 5: Run the identity test suite**

Run: `./mvnw test -Dtest='com.zarlania.api.identity.**'`
Expected: PASS (account-creation e2e tests also still pass — they assert prefix-free
behavior through the API).

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/zarlania/api/identity src/test/java/com/zarlania/api/identity
git commit -m "feat: default password hashing to Argon2id, 128-char policy cap (#75)"
```

---

### Task 3: `AuthTokenProperties` + configuration and the test signing secret

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/config/AuthTokenProperties.java`
- Modify: `src/main/resources/application.properties`
- Modify: `pom.xml` (surefire `<systemPropertyVariables>`, around line 140)
- Test: `src/test/java/com/zarlania/api/auth/config/AuthTokenPropertiesTest.java`

**Interfaces:**
- Consumes: nothing (pure record; registration happens in Task 4).
- Produces: `AuthTokenProperties(AuthTokenProperties.Jwt jwt, Duration refreshTokenTtl)`
  with nested `Jwt(String signingSecret, Duration accessTokenTtl)`; accessors
  `properties.jwt().signingSecret()`, `properties.jwt().accessTokenTtl()`,
  `properties.refreshTokenTtl()`. Bound from `zarlania.auth.*`. Tasks 4, 5, 7 consume it.

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Fail-fast validation of the auth token configuration (the CorsProperties pattern). */
class AuthTokenPropertiesTest {

  private static final String VALID_SECRET =
      "zarlania-test-only-signing-secret-zarlania-test-only-signing-secret";

  private static AuthTokenProperties.Jwt jwt(String secret, Duration ttl) {
    return new AuthTokenProperties.Jwt(secret, ttl);
  }

  @Test
  void acceptsValidConfiguration() {
    assertThatCode(
            () ->
                new AuthTokenProperties(
                    jwt(VALID_SECRET, Duration.ofMinutes(15)), Duration.ofDays(30)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingJwtBlock() {
    assertThatThrownBy(() -> new AuthTokenProperties(null, Duration.ofDays(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.auth.jwt");
  }

  @Test
  void rejectsBlankSecret() {
    assertThatThrownBy(() -> jwt("  ", Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ZARLANIA_AUTH_JWT_SIGNING_SECRET");
  }

  @Test
  void rejectsSecretShorterThan32Bytes() {
    assertThatThrownBy(() -> jwt("too-short-secret", Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void rejectsNonPositiveAccessTokenTtl() {
    assertThatThrownBy(() -> jwt(VALID_SECRET, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("access-token-ttl");
  }

  @Test
  void rejectsNonPositiveRefreshTokenTtl() {
    assertThatThrownBy(
            () ->
                new AuthTokenProperties(
                    jwt(VALID_SECRET, Duration.ofMinutes(15)), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("refresh-token-ttl");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthTokenPropertiesTest`
Expected: COMPILE ERROR — `AuthTokenProperties` does not exist.

- [ ] **Step 3: Implement the record**

```java
package com.zarlania.api.auth.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth token configuration, bound from {@code zarlania.auth.*}. Invalid or missing values
 * are rejected at bind time so a misconfiguration fails startup rather than issuing weak
 * or unverifiable tokens (the {@code CorsProperties} pattern).
 *
 * @param jwt the access-token (JWT) settings
 * @param refreshTokenTtl how long a refresh token lives; must be positive
 */
@ConfigurationProperties(prefix = "zarlania.auth")
public record AuthTokenProperties(Jwt jwt, Duration refreshTokenTtl) {

  /**
   * Validates the configured values.
   *
   * @param jwt the access-token settings; required
   * @param refreshTokenTtl the refresh-token time-to-live; must be positive
   * @throws IllegalArgumentException if any value is missing or invalid
   */
  public AuthTokenProperties {
    if (jwt == null) {
      throw new IllegalArgumentException("zarlania.auth.jwt must be configured");
    }
    if (refreshTokenTtl == null || refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
      throw new IllegalArgumentException(
          "zarlania.auth.refresh-token-ttl must be a positive duration");
    }
  }

  /**
   * JWT access-token settings, bound from {@code zarlania.auth.jwt.*}.
   *
   * @param signingSecret the HS256 signing secret; required, at least 32 bytes, sourced
   *     from the {@code ZARLANIA_AUTH_JWT_SIGNING_SECRET} environment variable
   * @param accessTokenTtl how long an access token lives; must be positive
   */
  public record Jwt(String signingSecret, Duration accessTokenTtl) {

    /**
     * Validates the configured values.
     *
     * @param signingSecret the HS256 signing secret
     * @param accessTokenTtl the access-token time-to-live
     * @throws IllegalArgumentException if the secret is missing/short or the ttl is not
     *     positive
     */
    public Jwt {
      if (signingSecret == null || signingSecret.isBlank()) {
        throw new IllegalArgumentException(
            "zarlania.auth.jwt.signing-secret must be set "
                + "(ZARLANIA_AUTH_JWT_SIGNING_SECRET environment variable)");
      }
      if (signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
        throw new IllegalArgumentException(
            "zarlania.auth.jwt.signing-secret must be at least 32 bytes");
      }
      if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
        throw new IllegalArgumentException(
            "zarlania.auth.jwt.access-token-ttl must be a positive duration");
      }
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthTokenPropertiesTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Wire configuration**

Append to `src/main/resources/application.properties`:

```properties

# Auth tokens (issue #75). The JWT signing secret is REQUIRED and environment-sourced —
# startup fails fast without it (see AuthTokenProperties). Never commit a real value;
# generate one with: openssl rand -base64 48
zarlania.auth.jwt.signing-secret=${ZARLANIA_AUTH_JWT_SIGNING_SECRET:}
zarlania.auth.jwt.access-token-ttl=15m
zarlania.auth.refresh-token-ttl=30d
```

In `pom.xml`, inside the existing surefire `<systemPropertyVariables>` (next to the
`spring.datasource.url` pin), add:

```xml
						<!-- Test-only JWT signing secret (NOT a real secret — deliberately fake and
						     low-entropy). Pinned here so every @SpringBootTest context can start,
						     since the property is required and env-sourced in production. -->
						<zarlania.auth.jwt.signing-secret>zarlania-test-only-signing-secret-zarlania-test-only-signing-secret</zarlania.auth.jwt.signing-secret>
```

- [ ] **Step 6: Run the full test suite (nothing should regress; binding is not yet registered)**

Run: `./mvnw test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/zarlania/api/auth src/test/java/com/zarlania/api/auth src/main/resources/application.properties pom.xml
git commit -m "feat: fail-fast auth token configuration properties (#75)"
```

---

### Task 4: Spring Security deny-by-default chain, toggle-aware enforcement, CORS rework

**Files:**
- Modify: `pom.xml` (two starters)
- Create: `src/main/java/com/zarlania/api/auth/config/SecurityConfig.java`
- Create: `src/main/java/com/zarlania/api/auth/config/ToggleAwareAuthorizationManager.java`
- Modify: `src/main/java/com/zarlania/api/config/WebConfig.java` (CorsRegistry →
  `CorsConfigurationSource` bean, + `Authorization` header)
- Test: `src/test/java/com/zarlania/api/auth/config/SecurityFilterChainTest.java`
- Test: `src/test/java/com/zarlania/api/config/CorsConfigTest.java` (add Authorization
  preflight assertion)
- Possibly modify: `config/spotbugs/spotbugs-exclude.xml` (CSRF detector — Step 8)

**Interfaces:**
- Consumes: `AuthTokenProperties` (Task 3), `Feature.AUTH_ENFORCEMENT` (Task 1),
  `FeatureToggleService.isEnabled(Feature)` (existing),
  `FeatureToggleAdminService.setPercentage(String, int)` (existing, for tests),
  `CorsProperties.allowedOrigins()` (existing).
- Produces: beans `SecurityFilterChain`, `JwtEncoder`, `JwtDecoder` (Tasks 5, 9 consume
  the encoder/decoder); permit-list = `POST /accounts`, `/auth/**`, OpenAPI paths,
  actuator health/info, `OPTIONS`; everything else goes through
  `ToggleAwareAuthorizationManager` (`auth-enforcement` off → permit, on → require JWT).

- [ ] **Step 1: Add the dependencies**

In `pom.xml` `<dependencies>`, after `spring-boot-starter-actuator`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<!-- JWT encode/decode via bundled Nimbus JOSE — no third-party JWT library. -->
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
		</dependency>
```

- [ ] **Step 2: Write the failing chain test**

```java
package com.zarlania.api.auth.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.features.service.FeatureToggleAdminService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// e2e proof of the deny-by-default posture: with auth-enforcement OFF everything behaves
// as before this change; with it ON, non-permit-listed paths demand a valid bearer JWT
// while the public permit-list stays open. Token-accepted paths are covered in
// JwtIssuerTest (Task 5) once an issuer exists.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityFilterChainTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;

  private void enforcementOn() {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
  }

  @Test
  void adminEndpointStaysOpenWhileEnforcementOff() throws Exception {
    mockMvc.perform(get("/api/admin/feature-toggles")).andExpect(status().isOk());
  }

  @Test
  void adminEndpointRequires401WithBearerChallengeWhenEnforcementOn() throws Exception {
    enforcementOn();
    mockMvc
        .perform(get("/api/admin/feature-toggles"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
  }

  @Test
  void garbageTokenIsRejectedWhenEnforcementOn() throws Exception {
    enforcementOn();
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void signupStaysOpenWhenEnforcementOn() throws Exception {
    enforcementOn();
    String username = "sec" + UUID.randomUUID().toString().substring(0, 8);
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"" + username + "@example.com\",\"username\":\"" + username + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void openApiAndHealthStayOpenWhenEnforcementOn() throws Exception {
    enforcementOn();
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
  }

  @Test
  void responsesAreStatelessNoSessionCookieEverIssued() throws Exception {
    // Compensating test for the deliberate csrf().disable(): the API is stateless — no
    // session is created, so no cookie exists for a cross-site request to ride.
    enforcementOn();
    mockMvc
        .perform(get("/api/admin/feature-toggles"))
        .andExpect(header().doesNotExist("Set-Cookie"));
    mockMvc.perform(get("/v3/api-docs")).andExpect(header().doesNotExist("Set-Cookie"));
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=SecurityFilterChainTest`
Expected: FAIL — with the starters on the classpath but no chain defined, Boot's
auto-configuration locks everything down (401/403 on all paths, including the
permit-list cases).

- [ ] **Step 4: Implement the authorization manager**

`src/main/java/com/zarlania/api/auth/config/ToggleAwareAuthorizationManager.java`:

```java
package com.zarlania.api.auth.config;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleService;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Authorization rule for every path outside the public permit-list: while the {@code
 * AUTH_ENFORCEMENT} toggle is off the request is permitted (pre-auth behavior, per
 * ADR-0016's gate-everything rule); once on, a valid authenticated JWT is required. When
 * the toggle goes permanent this class is deleted and the chain uses {@code
 * .authenticated()} directly.
 */
@Component
@RequiredArgsConstructor
public class ToggleAwareAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {

  private static final AuthorizationManager<RequestAuthorizationContext> REQUIRE_AUTHENTICATED =
      AuthenticatedAuthorizationManager.authenticated();

  private final FeatureToggleService featureToggleService;

  @Override
  public AuthorizationResult authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
    if (!featureToggleService.isEnabled(Feature.AUTH_ENFORCEMENT)) {
      return new AuthorizationDecision(true);
    }
    return REQUIRE_AUTHENTICATED.authorize(authentication, context);
  }
}
```

Note: Spring Security 7 renamed `check(...)` to `authorize(...)` returning
`AuthorizationResult`. If the interface in the resolved Security version differs in the
supplier's generic bound, match the interface's exact signature — the compiler error will
show it.

- [ ] **Step 5: Implement the security configuration**

`src/main/java/com/zarlania/api/auth/config/SecurityConfig.java`:

```java
package com.zarlania.api.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The stateless, deny-by-default security chain (see the org-scoped JWT auth ADR). An
 * explicit permit-list covers the public surface — signup, the /auth token endpoints, the
 * public OpenAPI docs (ADR-0003), and actuator health/info (ADR-0002); every other path,
 * present or future, is guarded by {@link ToggleAwareAuthorizationManager}, so new
 * endpoints are born protected and are opted <em>out</em> of auth, never bolted on.
 *
 * <p>CSRF protection is disabled deliberately: the API is a pure bearer-token surface
 * with {@code STATELESS} session policy — no session, no cookie, nothing for a cross-site
 * request to ride. {@code SecurityFilterChainTest} proves no Set-Cookie is ever issued.
 */
@Configuration
@EnableConfigurationProperties(AuthTokenProperties.class)
public class SecurityConfig {

  /**
   * Builds the single API security filter chain.
   *
   * @param http the builder
   * @param enforcement the toggle-aware rule for non-permit-listed paths
   * @return the chain
   * @throws Exception if the builder fails
   */
  @Bean
  public SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http, ToggleAwareAuthorizationManager enforcement) throws Exception {
    http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/accounts")
                    .permitAll()
                    .requestMatchers("/auth/**")
                    .permitAll()
                    .requestMatchers(
                        "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                        "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .access(enforcement))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
  }

  /**
   * Decodes and validates incoming HS256 bearer tokens with the shared signing secret.
   *
   * @param properties the auth token configuration
   * @return the decoder
   */
  @Bean
  public JwtDecoder jwtDecoder(AuthTokenProperties properties) {
    return NimbusJwtDecoder.withSecretKey(secretKey(properties))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  /**
   * Signs access tokens with the shared HS256 secret. Consumed by {@code JwtIssuer}.
   *
   * @param properties the auth token configuration
   * @return the encoder
   */
  @Bean
  public JwtEncoder jwtEncoder(AuthTokenProperties properties) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
  }

  private static SecretKeySpec secretKey(AuthTokenProperties properties) {
    return new SecretKeySpec(
        properties.jwt().signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }
}
```

- [ ] **Step 6: Rework CORS to a `CorsConfigurationSource` (single source, + Authorization)**

Replace the body of `src/main/java/com/zarlania/api/config/WebConfig.java` (it stops
implementing `WebMvcConfigurer`; Spring Security's CORS filter now serves the whole app
from this one bean — update the class javadoc accordingly, and note this may resolve the
FindSecBugs NPE from issue #23 since `addCorsMappings` no longer exists):

```java
package com.zarlania.api.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS configuration for browser clients. Allowed origins are an explicit allowlist
 * sourced from {@code zarlania.cors.allowed-origins} (overridable per environment), never
 * a wildcard. Exposed as a {@link CorsConfigurationSource} bean so Spring Security's CORS
 * filter applies it to every request (including preflights that would otherwise hit the
 * auth chain); the previous {@code WebMvcConfigurer#addCorsMappings} approach is retired
 * with the introduction of the security filter chain (issue #75).
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig {

  private final CorsProperties cors;

  WebConfig(CorsProperties cors) {
    this.cors = cors;
  }

  /**
   * The application-wide CORS policy: allowlisted origins, the API's methods, and the
   * headers browser clients send — including {@code Authorization} for bearer tokens.
   *
   * @return the CORS configuration source consumed by the security filter chain
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(cors.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "Authorization"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
```

Add to `src/test/java/com/zarlania/api/config/CorsConfigTest.java` a preflight test for
the Authorization header (adapt the existing preflight test style in that file — read it
first; the assertion that matters is):

```java
  @Test
  void preflightAllowsAuthorizationHeader() throws Exception {
    mockMvc
        .perform(
            options("/api/admin/feature-toggles")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "Authorization"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(
                "Access-Control-Allow-Headers",
                org.hamcrest.Matchers.containsStringIgnoringCase("authorization")));
  }
```

- [ ] **Step 7: Run the chain test, CORS tests, then the whole suite**

Run: `./mvnw test -Dtest='SecurityFilterChainTest,CorsConfigTest'`
Expected: PASS

Run: `./mvnw test`
Expected: PASS — every pre-existing test must be green (enforcement defaults off). If an
existing test fails on a 401/403, the permit-list or the toggle default is wrong — fix the
chain, do not touch the failing test.

- [ ] **Step 8: Run the static-analysis gates; handle the CSRF detector if it fires**

Run: `./mvnw verify -DskipTests`
Expected: PASS, or a FindSecBugs `SPRING_CSRF_PROTECTION_DISABLED` finding on
`SecurityConfig`.

If (and only if) it fires, this is the known false positive for stateless bearer APIs
(the detector cannot see the STATELESS session policy). Apply the repo's
documented-exception pattern:

1. Create the durable-record issue:

```bash
gh issue create --repo Zarlania/zarlania-api \
  --title "SpotBugs exclusion record: SPRING_CSRF_PROTECTION_DISABLED on SecurityConfig" \
  --body "FindSecBugs flags csrf().disable() in SecurityConfig (#75). Deliberate and safe: the chain is SessionCreationPolicy.STATELESS with bearer-token auth only — no session cookie exists for a cross-site request to ride, which is the precondition CSRF protection defends against. Compensating test: SecurityFilterChainTest.responsesAreStatelessNoSessionCookieEverIssued proves no Set-Cookie is ever issued. Scoped to SecurityConfig only."
```

2. Add to `config/spotbugs/spotbugs-exclude.xml` (before `</FindBugsFilter>`), citing the
   issue number returned above:

```xml
  <!--
    FindSecBugs SPRING_CSRF_PROTECTION_DISABLED: csrf().disable() in SecurityConfig is
    deliberate — the chain is SessionCreationPolicy.STATELESS with bearer-token auth only,
    so no session cookie exists for a cross-site request to ride (the precondition for
    CSRF). The detector cannot see the session policy. Compensating test:
    SecurityFilterChainTest.responsesAreStatelessNoSessionCookieEverIssued. Scoped to
    SecurityConfig only — any future chain change gets a fresh review. See issue #<NN>.
  -->
  <Match>
    <Class name="com.zarlania.api.auth.config.SecurityConfig"/>
    <Bug pattern="SPRING_CSRF_PROTECTION_DISABLED"/>
  </Match>
```

Re-run `./mvnw verify -DskipTests` → PASS.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/com/zarlania/api/auth src/main/java/com/zarlania/api/config/WebConfig.java src/test/java/com/zarlania/api/auth src/test/java/com/zarlania/api/config/CorsConfigTest.java config/spotbugs/spotbugs-exclude.xml
git commit -m "feat: deny-by-default security chain behind auth-enforcement toggle (#75)"
```

---

### Task 5: `JwtIssuer` — org-scoped access tokens

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/service/JwtIssuer.java`
- Test: `src/test/java/com/zarlania/api/auth/service/JwtIssuerTest.java`

**Interfaces:**
- Consumes: `JwtEncoder` + `JwtDecoder` beans and `AuthTokenProperties` (Task 4/3).
- Produces: `String issueUserToken(UUID userId, UUID organizationId)` and
  `long accessTokenTtlSeconds()` — Task 9's `AuthService` calls both. Claims contract
  (asserted here, recorded in the ADR): `sub` = userId, `org` = organizationId,
  `token_use` = `"user"`, `jti` unique, `iss` = `zarlania-api`, `iat`/`exp` spanning the
  configured TTL.

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.features.service.FeatureToggleAdminService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// Integration test through the real encoder/decoder beans: the claims contract is the
// hard-to-change part of the auth design (see the org-scoped JWT ADR), so it is asserted
// literally here. Also proves a minted token passes chain enforcement end to end.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtIssuerTest {

  @Autowired private JwtIssuer jwtIssuer;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private MockMvc mockMvc;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;

  @Test
  void mintsOrgScopedUserTokenWithContractClaims() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    Jwt jwt = jwtDecoder.decode(jwtIssuer.issueUserToken(userId, organizationId));

    assertThat(jwt.getSubject()).isEqualTo(userId.toString());
    assertThat(jwt.getClaimAsString("org")).isEqualTo(organizationId.toString());
    assertThat(jwt.getClaimAsString("token_use")).isEqualTo("user");
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("zarlania-api");
    assertThat(jwt.getId()).isNotBlank();
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void everyTokenGetsAFreshJti() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    Jwt first = jwtDecoder.decode(jwtIssuer.issueUserToken(userId, organizationId));
    Jwt second = jwtDecoder.decode(jwtIssuer.issueUserToken(userId, organizationId));
    assertThat(first.getId()).isNotEqualTo(second.getId());
  }

  @Test
  void exposesTheConfiguredTtlInSeconds() {
    assertThat(jwtIssuer.accessTokenTtlSeconds()).isEqualTo(900L);
  }

  @Test
  void mintedTokenPassesChainEnforcement() throws Exception {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    String token = jwtIssuer.issueUserToken(UUID.randomUUID(), UUID.randomUUID());
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void tamperedTokenFailsChainEnforcement() throws Exception {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    String token = jwtIssuer.issueUserToken(UUID.randomUUID(), UUID.randomUUID());
    String tampered = token.substring(0, token.length() - 4) + "AAAA";
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer " + tampered))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenFailsChainEnforcement() throws Exception {
    // Crafted directly with the encoder: exp two hours in the past, comfortably beyond
    // the decoder's default 60-second clock skew.
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    java.time.Instant issuedAt = java.time.Instant.now().minus(Duration.ofHours(2));
    org.springframework.security.oauth2.jwt.JwtClaimsSet claims =
        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
            .issuer("zarlania-api")
            .subject(UUID.randomUUID().toString())
            .claim("org", UUID.randomUUID().toString())
            .claim("token_use", "user")
            .id(UUID.randomUUID().toString())
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
            .build();
    String expired =
        jwtEncoder
            .encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwsHeader.with(
                            org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                        .build(),
                    claims))
            .getTokenValue();
    mockMvc
        .perform(get("/api/admin/feature-toggles").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }
}
```

(Add `@Autowired private org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;`
alongside the other autowired fields, and hoist the fully-qualified names above into
imports when writing the file — they are spelled out here only for unambiguity.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=JwtIssuerTest`
Expected: COMPILE ERROR — `JwtIssuer` does not exist.

- [ ] **Step 3: Implement**

```java
package com.zarlania.api.auth.service;

import com.zarlania.api.auth.config.AuthTokenProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints signed HS256 access tokens. Every token is scoped to exactly one organization via
 * the {@code org} claim — one token, one organization, never a re-scope (the auth ADR's
 * core rule). Parameterized by subject and organization so future issuance paths (service
 * tokens with {@code token_use=service}, OAuth-obtained user tokens) reuse it additively.
 */
@Service
@RequiredArgsConstructor
public class JwtIssuer {

  /** The {@code iss} claim stamped into every token. */
  static final String ISSUER = "zarlania-api";

  /** The {@code token_use} claim value for user-held tokens. */
  static final String TOKEN_USE_USER = "user";

  private final JwtEncoder jwtEncoder;
  private final AuthTokenProperties properties;

  /**
   * Mints an access token for a user, scoped to one organization.
   *
   * @param userId the authenticated user's id (the {@code sub} claim)
   * @param organizationId the single organization this token grants access within (the
   *     {@code org} claim)
   * @return the signed compact JWT
   */
  public String issueUserToken(UUID userId, UUID organizationId) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(userId.toString())
            .claim("org", organizationId.toString())
            .claim("token_use", TOKEN_USE_USER)
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.jwt().accessTokenTtl()))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /**
   * The configured access-token lifetime, for the {@code expiresInSeconds} response field.
   *
   * @return the access-token TTL in whole seconds
   */
  public long accessTokenTtlSeconds() {
    return properties.jwt().accessTokenTtl().toSeconds();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=JwtIssuerTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/auth/service/JwtIssuer.java src/test/java/com/zarlania/api/auth/service/JwtIssuerTest.java
git commit -m "feat: org-scoped JWT issuer with contract claims (#75)"
```

---

### Task 6: `refresh_tokens` migration, entity, repository

**Files:**
- Create: `src/main/resources/db/migration/V7__create_refresh_tokens.sql`
- Create: `src/main/java/com/zarlania/api/auth/entity/RefreshTokenEntity.java`
- Create: `src/main/java/com/zarlania/api/auth/repository/RefreshTokenRepository.java`
- Test: `src/test/java/com/zarlania/api/auth/repository/RefreshTokenRepositoryTest.java`

**Interfaces:**
- Consumes: `users`/`organizations` tables (DB-level FKs only, per ADR-0011).
- Produces: `RefreshTokenEntity` (fields `id`, `userId`, `organizationId`, `tokenHash`,
  `familyId`, `issuedAt`, `expiresAt`, `consumedAt`, `revokedAt`; all-args setters except
  generated `id`) and `RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID>`
  with `Optional<RefreshTokenEntity> findByTokenHash(String tokenHash)` and
  `List<RefreshTokenEntity> findByFamilyId(UUID familyId)`. Task 7 consumes both. This is
  the single storage seam a future Redis/Postgres store replaces.

- [ ] **Step 1: Write the migration** (additive scaffolding — toggle carve-out per ADR-0016)

`V7__create_refresh_tokens.sql`:

```sql
CREATE TABLE refresh_tokens (
    id              UUID                        NOT NULL,
    user_id         UUID                        NOT NULL,
    organization_id UUID                        NOT NULL,
    token_hash      VARCHAR(64)                 NOT NULL,
    family_id       UUID                        NOT NULL,
    issued_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at     TIMESTAMP(6) WITH TIME ZONE,
    revoked_at      TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_refresh_tokens      PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_refresh_tokens_org  FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
```

- [ ] **Step 2: Write the failing repository test**

Model it on the existing repository tests (see
`src/test/java/com/zarlania/api/users/repository/` for the slice pattern used —
`@DataJpaTest` + `TestEntityManager` per `AbstractIntegrationTest`'s javadoc; match
whatever those tests actually use, including any base class):

```java
package com.zarlania.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.auth.entity.RefreshTokenEntity;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.entity.UserEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

// Repository slice test: hash lookup, family listing, and the DB-level invariants
// (unique token_hash, FKs to users/organizations per ADR-0011).
@DataJpaTest
class RefreshTokenRepositoryTest extends AbstractIntegrationTest {

  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private TestEntityManager entityManager;

  private UUID persistUser() {
    UserEntity user = new UserEntity();
    String unique = UUID.randomUUID().toString().substring(0, 8);
    user.setEmail(unique + "@example.com");
    user.setUsername("u" + unique);
    return entityManager.persistAndFlush(user).getId();
  }

  private UUID persistOrganization() {
    OrganizationEntity organization = new OrganizationEntity();
    organization.setName("org" + UUID.randomUUID().toString().substring(0, 8));
    organization.setType(OrganizationType.PERSONAL);
    return entityManager.persistAndFlush(organization).getId();
  }

  private RefreshTokenEntity newToken(UUID userId, UUID organizationId, String hash) {
    RefreshTokenEntity token = new RefreshTokenEntity();
    token.setUserId(userId);
    token.setOrganizationId(organizationId);
    token.setTokenHash(hash);
    token.setFamilyId(UUID.randomUUID());
    token.setIssuedAt(Instant.now());
    token.setExpiresAt(Instant.now().plusSeconds(3600));
    return token;
  }

  @Test
  void findsByTokenHash() {
    UUID userId = persistUser();
    UUID organizationId = persistOrganization();
    refreshTokenRepository.saveAndFlush(newToken(userId, organizationId, "a".repeat(64)));

    assertThat(refreshTokenRepository.findByTokenHash("a".repeat(64))).isPresent();
    assertThat(refreshTokenRepository.findByTokenHash("b".repeat(64))).isEmpty();
  }

  @Test
  void listsAllTokensInAFamily() {
    UUID userId = persistUser();
    UUID organizationId = persistOrganization();
    UUID familyId = UUID.randomUUID();
    RefreshTokenEntity first = newToken(userId, organizationId, "c".repeat(64));
    first.setFamilyId(familyId);
    RefreshTokenEntity second = newToken(userId, organizationId, "d".repeat(64));
    second.setFamilyId(familyId);
    refreshTokenRepository.saveAndFlush(first);
    refreshTokenRepository.saveAndFlush(second);

    assertThat(refreshTokenRepository.findByFamilyId(familyId)).hasSize(2);
  }

  @Test
  void rejectsDuplicateTokenHash() {
    UUID userId = persistUser();
    UUID organizationId = persistOrganization();
    refreshTokenRepository.saveAndFlush(newToken(userId, organizationId, "e".repeat(64)));

    assertThatThrownBy(
            () ->
                refreshTokenRepository.saveAndFlush(
                    newToken(userId, organizationId, "e".repeat(64))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_refresh_tokens_hash");
  }

  @Test
  void rejectsUnknownUserAndOrganization() {
    assertThatThrownBy(
            () ->
                refreshTokenRepository.saveAndFlush(
                    newToken(UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
```

(If the existing repository tests use different setup helpers — e.g. a shared support
class — mirror that instead of the inline `persistUser`/`persistOrganization` shown here.
Importing `UserEntity`/`OrganizationEntity` in a *test* is acceptable; ADR-0011 forbids it
in production code.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=RefreshTokenRepositoryTest`
Expected: COMPILE ERROR — entity/repository do not exist.

- [ ] **Step 4: Implement entity and repository**

`RefreshTokenEntity.java` (deliberately not `Auditable` — its lifecycle timestamps are
domain fields, not audit metadata):

```java
package com.zarlania.api.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stored refresh token: the SHA-256 hash of the raw token (never the raw value), scoped
 * to one user and one organization (DB foreign keys, no JPA associations — ADR-0011).
 * Rotations share a {@code familyId} so reuse of a consumed token can revoke the whole
 * chain. A token is live iff {@code consumedAt} and {@code revokedAt} are both null and
 * {@code expiresAt} is in the future. Consumed/revoked rows are retained for reuse
 * detection (purge deferred to issue #77).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor
public class RefreshTokenEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Setter
  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Setter
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Setter
  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Setter
  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Setter
  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Setter
  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Setter
  @Column(name = "revoked_at")
  private Instant revokedAt;
}
```

`RefreshTokenRepository.java`:

```java
package com.zarlania.api.auth.repository;

import com.zarlania.api.auth.entity.RefreshTokenEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link RefreshTokenEntity}. Internal to the {@code auth} domain;
 * the single storage seam a future Redis/Postgres-backed token store replaces.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

  /**
   * Finds a token row by the SHA-256 hex hash of a presented raw token.
   *
   * @param tokenHash the 64-char hex hash
   * @return the row, if one exists
   */
  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  /**
   * Lists every rotation in a token family — used to revoke the whole chain when a
   * consumed token is replayed.
   *
   * @param familyId the family id shared across rotations
   * @return all rows in the family
   */
  List<RefreshTokenEntity> findByFamilyId(UUID familyId);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=RefreshTokenRepositoryTest`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V7__create_refresh_tokens.sql src/main/java/com/zarlania/api/auth/entity src/main/java/com/zarlania/api/auth/repository src/test/java/com/zarlania/api/auth/repository
git commit -m "feat: refresh_tokens store with family index and DB-level integrity (#75)"
```

---### Task 7: `RefreshTokenService` — mint, rotate, reuse detection, revoke

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/service/RefreshTokenService.java`
- Create: `src/main/java/com/zarlania/api/auth/exception/InvalidRefreshTokenException.java`
- Test: `src/test/java/com/zarlania/api/auth/service/RefreshTokenServiceTest.java`

**Interfaces:**
- Consumes: `RefreshTokenRepository`, `RefreshTokenEntity` (Task 6),
  `AuthTokenProperties.refreshTokenTtl()` (Task 3).
- Produces (Task 9 consumes all of these):
  - `String mint(UUID userId, UUID organizationId)` — returns the raw token (returned to
    the client exactly once; only its hash is stored), new family.
  - `record RefreshRotation(String newRawToken, UUID userId, UUID organizationId)`
    (nested in `RefreshTokenService`).
  - `RefreshRotation rotate(String rawToken)` — single-use rotation; throws
    `InvalidRefreshTokenException` on unknown/expired/revoked tokens, and on a replayed
    (already-consumed) token **after** revoking its whole family.
  - `void revoke(String rawToken)` — idempotent; unknown tokens are a no-op.
  - `InvalidRefreshTokenException` (unchecked) — mapped to 401 in Task 9.

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.auth.entity.RefreshTokenEntity;
import com.zarlania.api.auth.exception.InvalidRefreshTokenException;
import com.zarlania.api.auth.repository.RefreshTokenRepository;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.service.UserService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// Integration tests against the real store: the full rotation lifecycle including the
// stolen-token tripwire (family revocation on replay of a consumed token).
@SpringBootTest
@Transactional
class RefreshTokenServiceTest extends AbstractIntegrationTest {

  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  private UUID userId;
  private UUID organizationId;

  @BeforeEach
  void createOwningRows() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    userId = userService.create(unique + "@example.com", "u" + unique).id();
    organizationId =
        organizationService.createPersonalOrganization(userId, "u" + unique).id();
  }

  @Test
  void mintReturnsRawTokenAndStoresOnlyItsHash() {
    String raw = refreshTokenService.mint(userId, organizationId);

    assertThat(raw).isNotBlank();
    assertThat(refreshTokenRepository.findByTokenHash(raw)).isEmpty(); // raw != stored hash
    assertThat(refreshTokenRepository.findAll())
        .anySatisfy(
            row -> {
              assertThat(row.getTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
              assertThat(row.getUserId()).isEqualTo(userId);
              assertThat(row.getOrganizationId()).isEqualTo(organizationId);
              assertThat(row.getConsumedAt()).isNull();
              assertThat(row.getRevokedAt()).isNull();
            });
  }

  @Test
  void rotateConsumesOldTokenAndIssuesNewOneInSameFamily() {
    String raw = refreshTokenService.mint(userId, organizationId);

    RefreshTokenService.RefreshRotation rotation = refreshTokenService.rotate(raw);

    assertThat(rotation.userId()).isEqualTo(userId);
    assertThat(rotation.organizationId()).isEqualTo(organizationId);
    assertThat(rotation.newRawToken()).isNotEqualTo(raw);
    assertThat(refreshTokenRepository.findAll()).hasSize(2);
    assertThat(
            refreshTokenRepository.findAll().stream()
                .map(RefreshTokenEntity::getFamilyId)
                .distinct())
        .hasSize(1);
  }

  @Test
  void replayingAConsumedTokenRevokesTheWholeFamily() {
    String original = refreshTokenService.mint(userId, organizationId);
    RefreshTokenService.RefreshRotation rotation = refreshTokenService.rotate(original);

    // Replay the consumed token: tripwire.
    assertThatThrownBy(() -> refreshTokenService.rotate(original))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // The still-live successor is now revoked too.
    assertThatThrownBy(() -> refreshTokenService.rotate(rotation.newRawToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rejectsUnknownToken() {
    assertThatThrownBy(() -> refreshTokenService.rotate("never-issued"))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rejectsExpiredToken() {
    String raw = refreshTokenService.mint(userId, organizationId);
    RefreshTokenEntity row = refreshTokenRepository.findAll().getFirst();
    row.setExpiresAt(Instant.now().minusSeconds(1));
    refreshTokenRepository.saveAndFlush(row);

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void revokedTokenCannotRotateAndRevokeIsIdempotent() {
    String raw = refreshTokenService.mint(userId, organizationId);

    refreshTokenService.revoke(raw);
    assertThatCode(() -> refreshTokenService.revoke(raw)).doesNotThrowAnyException();
    assertThatCode(() -> refreshTokenService.revoke("never-issued")).doesNotThrowAnyException();

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=RefreshTokenServiceTest`
Expected: COMPILE ERROR — service/exception do not exist.

- [ ] **Step 3: Implement**

`InvalidRefreshTokenException.java`:

```java
package com.zarlania.api.auth.exception;

/**
 * A presented refresh token is unknown, expired, revoked, or already consumed. Carries no
 * detail about which, so responses cannot be used to probe token state; mapped to 401.
 */
public class InvalidRefreshTokenException extends RuntimeException {

  /** Creates the exception with a fixed, token-free message. */
  public InvalidRefreshTokenException() {
    super("invalid refresh token");
  }
}
```

`RefreshTokenService.java`:

```java
package com.zarlania.api.auth.service;

import com.zarlania.api.auth.config.AuthTokenProperties;
import com.zarlania.api.auth.entity.RefreshTokenEntity;
import com.zarlania.api.auth.exception.InvalidRefreshTokenException;
import com.zarlania.api.auth.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the refresh-token lifecycle: mint (raw returned once, SHA-256 hash stored), rotate
 * (single-use; each refresh consumes the old token and issues a successor in the same
 * family), reuse detection (replaying a consumed token revokes the entire family — the
 * stolen-token tripwire), and revoke (logout; idempotent). Raw tokens are 256-bit
 * {@link SecureRandom} values and are never stored or logged.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private static final int RAW_TOKEN_BYTES = 32;

  private final RefreshTokenRepository refreshTokenRepository;
  private final AuthTokenProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * The outcome of a successful rotation: the successor raw token plus the user and
   * organization the family is scoped to (for minting the paired access token).
   *
   * @param newRawToken the successor refresh token, to return to the client once
   * @param userId the owning user
   * @param organizationId the organization the family is scoped to
   */
  public record RefreshRotation(String newRawToken, UUID userId, UUID organizationId) {}

  /**
   * Mints a refresh token in a new family.
   *
   * @param userId the owning user
   * @param organizationId the single organization the token is scoped to
   * @return the raw token — returned to the client exactly once, never stored
   */
  @Transactional
  public String mint(UUID userId, UUID organizationId) {
    return storeNewToken(userId, organizationId, UUID.randomUUID());
  }

  /**
   * Rotates a refresh token: consumes the presented one and issues a successor in the
   * same family. Replaying an already-consumed or revoked token revokes the whole family
   * before rejecting.
   *
   * @param rawToken the presented raw refresh token
   * @return the rotation result
   * @throws InvalidRefreshTokenException if the token is unknown, expired, revoked, or
   *     already consumed
   */
  @Transactional
  public RefreshRotation rotate(String rawToken) {
    RefreshTokenEntity row =
        refreshTokenRepository
            .findByTokenHash(sha256Hex(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);
    if (row.getConsumedAt() != null || row.getRevokedAt() != null) {
      revokeFamily(row.getFamilyId());
      throw new InvalidRefreshTokenException();
    }
    if (row.getExpiresAt().isBefore(Instant.now())) {
      throw new InvalidRefreshTokenException();
    }
    row.setConsumedAt(Instant.now());
    refreshTokenRepository.save(row);
    String successor = storeNewToken(row.getUserId(), row.getOrganizationId(), row.getFamilyId());
    return new RefreshRotation(successor, row.getUserId(), row.getOrganizationId());
  }

  /**
   * Revokes a refresh token (logout). Idempotent: unknown or already-revoked tokens are a
   * no-op so logout never fails.
   *
   * @param rawToken the presented raw refresh token
   */
  @Transactional
  public void revoke(String rawToken) {
    refreshTokenRepository
        .findByTokenHash(sha256Hex(rawToken))
        .filter(row -> row.getRevokedAt() == null)
        .ifPresent(
            row -> {
              row.setRevokedAt(Instant.now());
              refreshTokenRepository.save(row);
            });
  }

  private String storeNewToken(UUID userId, UUID organizationId, UUID familyId) {
    byte[] bytes = new byte[RAW_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = Instant.now();
    RefreshTokenEntity row = new RefreshTokenEntity();
    row.setUserId(userId);
    row.setOrganizationId(organizationId);
    row.setTokenHash(sha256Hex(rawToken));
    row.setFamilyId(familyId);
    row.setIssuedAt(now);
    row.setExpiresAt(now.plus(properties.refreshTokenTtl()));
    refreshTokenRepository.saveAndFlush(row);
    return rawToken;
  }

  private void revokeFamily(UUID familyId) {
    Instant now = Instant.now();
    refreshTokenRepository
        .findByFamilyId(familyId)
        .forEach(
            member -> {
              if (member.getRevokedAt() == null) {
                member.setRevokedAt(now);
              }
            });
  }

  private static String sha256Hex(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by the JVM spec", ex);
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=RefreshTokenServiceTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/auth/service/RefreshTokenService.java src/main/java/com/zarlania/api/auth/exception src/test/java/com/zarlania/api/auth/service/RefreshTokenServiceTest.java
git commit -m "feat: rotating refresh tokens with family reuse detection (#75)"
```

---

### Task 8: Cross-domain seams — password verification and personal-org lookup

**Files:**
- Modify: `src/main/java/com/zarlania/api/identity/service/PasswordCredentialService.java`
- Modify (if `findByUserId` absent):
  `src/main/java/com/zarlania/api/identity/repository/PasswordCredentialRepository.java`
- Modify: `src/main/java/com/zarlania/api/organizations/repository/MembershipRepository.java`
- Modify: `src/main/java/com/zarlania/api/organizations/service/OrganizationService.java`
- Test: existing `PasswordCredentialService` test class (add methods) and existing
  `OrganizationService` test class (add methods)

**Interfaces:**
- Consumes: `PasswordEncoder` bean (Argon2id default, Task 2), existing repositories.
- Produces (Task 9 consumes):
  - `boolean PasswordCredentialService.verify(UUID userId, String rawPassword)` —
    `userId` may be null (unknown account); a dummy Argon2id verification still runs so
    login timing does not reveal account existence.
  - `Optional<Organization> OrganizationService.findPersonalOrganization(UUID ownerUserId)`.

- [ ] **Step 1: Write the failing tests**

Add to the existing `PasswordCredentialService` test class (find it under
`src/test/java/com/zarlania/api/identity/service/`; reuse its existing setup for creating
a user + credential):

```java
  @Test
  void verifyAcceptsTheCorrectPassword() {
    UUID userId = /* create a user via the class's existing helper */;
    passwordCredentialService.create(userId, "Str0ng!Pass");
    assertThat(passwordCredentialService.verify(userId, "Str0ng!Pass")).isTrue();
  }

  @Test
  void verifyRejectsAWrongPassword() {
    UUID userId = /* create a user via the class's existing helper */;
    passwordCredentialService.create(userId, "Str0ng!Pass");
    assertThat(passwordCredentialService.verify(userId, "Wr0ng!Pass")).isFalse();
  }

  @Test
  void verifyRejectsAUserWithoutACredential() {
    UUID userId = /* create a user via the class's existing helper */;
    assertThat(passwordCredentialService.verify(userId, "Str0ng!Pass")).isFalse();
  }

  @Test
  void verifyRejectsANullUserWithoutThrowing() {
    assertThat(passwordCredentialService.verify(null, "Str0ng!Pass")).isFalse();
  }

  @Test
  void verifyRejectsABlankPassword() {
    UUID userId = /* create a user via the class's existing helper */;
    passwordCredentialService.create(userId, "Str0ng!Pass");
    assertThat(passwordCredentialService.verify(userId, "  ")).isFalse();
  }
```

(Replace the `/* create a user ... */` comments with the test class's actual user-creation
helper — these tests run against a real DB, so the FK to `users` must be satisfied the
same way the class's existing `create` tests satisfy it.)

Add to the existing `OrganizationService` test class (find it under
`src/test/java/com/zarlania/api/organizations/service/`):

```java
  @Test
  void findsThePersonalOrganizationForItsOwner() {
    UUID ownerId = /* create a user via the class's existing helper */;
    Organization created = organizationService.createPersonalOrganization(ownerId, "own-name");

    assertThat(organizationService.findPersonalOrganization(ownerId))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(created.id());
              assertThat(found.type()).isEqualTo(OrganizationType.PERSONAL);
            });
  }

  @Test
  void personalOrganizationLookupIsEmptyForAUserWithoutOne() {
    UUID ownerId = /* create a user via the class's existing helper */;
    assertThat(organizationService.findPersonalOrganization(ownerId)).isEmpty();
  }

  @Test
  void personalOrganizationLookupIgnoresGeneralOrganizations() {
    UUID ownerId = /* create a user via the class's existing helper */;
    organizationService.createGeneralOrganization(ownerId, "gen-" + ownerId);
    assertThat(organizationService.findPersonalOrganization(ownerId)).isEmpty();
  }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest='PasswordCredentialService*,OrganizationService*'`
Expected: COMPILE ERROR — `verify` / `findPersonalOrganization` do not exist.

- [ ] **Step 3: Implement**

`PasswordCredentialRepository` — if not already present, add:

```java
  /**
   * Finds a user's password credential.
   *
   * @param userId the owning user's id
   * @return the credential, if one exists
   */
  Optional<PasswordCredentialEntity> findByUserId(UUID userId);
```

`PasswordCredentialService` — replace `@RequiredArgsConstructor` with an explicit
constructor (a dummy hash is computed once so verification does constant work even when
no credential exists), and add `verify`:

```java
  private final PasswordCredentialRepository passwordCredentialRepository;
  private final PasswordPolicy passwordPolicy;
  private final PasswordEncoder passwordEncoder;

  /**
   * A throwaway hash matched against when no credential exists, so verification performs
   * one real Argon2id comparison on every path — response timing must not reveal whether
   * an account or credential exists (user-enumeration defense).
   */
  private final String absentCredentialHash;

  /**
   * Creates the service and precomputes the dummy hash used for constant-work
   * verification (one Argon2id encode at startup).
   *
   * @param passwordCredentialRepository the credential store
   * @param passwordPolicy the account-creation password policy
   * @param passwordEncoder the delegating encoder (Argon2id default)
   */
  public PasswordCredentialService(
      PasswordCredentialRepository passwordCredentialRepository,
      PasswordPolicy passwordPolicy,
      PasswordEncoder passwordEncoder) {
    this.passwordCredentialRepository = passwordCredentialRepository;
    this.passwordPolicy = passwordPolicy;
    this.passwordEncoder = passwordEncoder;
    this.absentCredentialHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * Verifies a raw password against the user's stored credential — the seam ADR-0017
   * anticipated for authentication.
   *
   * @param userId the claimed user's id; may be null (unknown account), in which case a
   *     dummy verification still runs and the result is {@code false}
   * @param rawPassword the presented password
   * @return whether the password matches the user's stored credential
   */
  @Transactional(readOnly = true)
  public boolean verify(UUID userId, String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      return false;
    }
    String storedHash =
        userId == null
            ? null
            : passwordCredentialRepository
                .findByUserId(userId)
                .map(PasswordCredentialEntity::getPasswordHash)
                .orElse(null);
    if (storedHash == null) {
      passwordEncoder.matches(rawPassword, absentCredentialHash);
      return false;
    }
    return passwordEncoder.matches(rawPassword, storedHash);
  }
```

(Keep the existing `create` method and its javadoc untouched; remove the now-redundant
`@RequiredArgsConstructor` import/annotation.)

`MembershipRepository` — add:

```java
  /**
   * Finds the user's membership with the given role in an organization of the given type
   * — used to resolve a user's personal organization (at most one exists, by invariant).
   *
   * @param userId the user id
   * @param role the membership role to match
   * @param type the organization type to match
   * @return the membership, if one exists
   */
  Optional<MembershipEntity> findFirstByUserIdAndRoleAndOrganizationType(
      UUID userId, MembershipRole role, OrganizationType type);
```

`OrganizationService` — add:

```java
  /**
   * Finds the user's personal organization — the organization every issued token is
   * scoped to at login while personal organizations are each user's only organization.
   *
   * @param ownerUserId the owning user's id
   * @return the personal organization as a DTO, if one exists
   */
  @Transactional(readOnly = true)
  public Optional<Organization> findPersonalOrganization(UUID ownerUserId) {
    return membershipRepository
        .findFirstByUserIdAndRoleAndOrganizationType(
            ownerUserId, MembershipRole.OWNER, OrganizationType.PERSONAL)
        .map(MembershipEntity::getOrganization)
        .map(organizationMapper::toDto);
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest='PasswordCredentialService*,OrganizationService*'`
Expected: PASS (all pre-existing + 8 new)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/identity src/main/java/com/zarlania/api/organizations src/test/java/com/zarlania/api/identity src/test/java/com/zarlania/api/organizations
git commit -m "feat: password verification and personal-org lookup seams (#75)"
```

---

### Task 9: `/auth` endpoints — login, refresh, logout

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/dto/LoginRequest.java`
- Create: `src/main/java/com/zarlania/api/auth/dto/RefreshRequest.java`
- Create: `src/main/java/com/zarlania/api/auth/dto/LogoutRequest.java`
- Create: `src/main/java/com/zarlania/api/auth/dto/TokenResponse.java`
- Create: `src/main/java/com/zarlania/api/auth/exception/InvalidCredentialsException.java`
- Create: `src/main/java/com/zarlania/api/auth/exception/PasswordLoginDisabledException.java`
- Create: `src/main/java/com/zarlania/api/auth/service/AuthService.java`
- Create: `src/main/java/com/zarlania/api/auth/controller/AuthController.java`
- Modify: `src/main/java/com/zarlania/api/web/ApiExceptionHandler.java`
- Test: `src/test/java/com/zarlania/api/auth/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `Feature.PASSWORD_LOGIN` (Task 1), `FeatureToggleService.isEnabled(Feature)`,
  `UserService.findByEmail(String)` (existing), `PasswordCredentialService.verify` and
  `OrganizationService.findPersonalOrganization` (Task 8), `JwtIssuer` (Task 5),
  `RefreshTokenService` (Task 7), `LogSanitizer`.
- Produces: `POST /auth/login` `{email,password}` → 200 `TokenResponse(accessToken,
  expiresInSeconds, refreshToken)`; `POST /auth/refresh` `{refreshToken}` → 200 new pair;
  `POST /auth/logout` `{refreshToken}` → 204. Toggle off → 404. Bad credentials → generic
  401 `"Invalid email or password"`; bad refresh token → 401
  `"Invalid or expired refresh token"`.

- [ ] **Step 1: Write the failing e2e test**

```java
package com.zarlania.api.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zarlania.api.features.service.FeatureToggleAdminService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

// e2e for the /auth surface: the full login → protected-admin-call → refresh →
// reuse-tripwire → logout loop, plus toggle gating and the anti-enumeration 401 contract.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

  private static final String PASSWORD = "Str0ng!Pass";

  @Autowired private MockMvc mockMvc;
  @Autowired private FeatureToggleAdminService featureToggleAdminService;
  @Autowired private ObjectMapper objectMapper;

  private String email;

  @BeforeEach
  void createPasswordAccountAndEnableLogin() throws Exception {
    featureToggleAdminService.setPercentage("password-accounts", 100);
    featureToggleAdminService.setPercentage("password-login", 100);
    String username = "auth" + UUID.randomUUID().toString().substring(0, 8);
    email = username + "@example.com";
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"" + email + "\",\"username\":\"" + username
                        + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isCreated());
  }

  private MvcResult login(String loginEmail, String password) throws Exception {
    return mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"" + loginEmail + "\",\"password\":\"" + password + "\"}"))
        .andReturn();
  }

  private JsonNode loginOk() throws Exception {
    MvcResult result = login(email, PASSWORD);
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void loginReturnsTokenPair() throws Exception {
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.expiresInSeconds").value(900))
        .andExpect(jsonPath("$.refreshToken").isNotEmpty());
  }

  @Test
  void loginFailuresAreIndistinguishable() throws Exception {
    MvcResult wrongPassword = login(email, "Wr0ng!Pass1");
    MvcResult unknownEmail = login("nobody@example.com", PASSWORD);

    org.assertj.core.api.Assertions.assertThat(wrongPassword.getResponse().getStatus())
        .isEqualTo(401);
    org.assertj.core.api.Assertions.assertThat(unknownEmail.getResponse().getStatus())
        .isEqualTo(401);
    org.assertj.core.api.Assertions.assertThat(wrongPassword.getResponse().getContentAsString())
        .isEqualTo(unknownEmail.getResponse().getContentAsString());
  }

  @Test
  void loginTokenOpensEnforcedAdminEndpoint() throws Exception {
    featureToggleAdminService.setPercentage("auth-enforcement", 100);
    String accessToken = loginOk().get("accessToken").asText();

    mockMvc
        .perform(get("/api/admin/feature-toggles"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/api/admin/feature-toggles").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk());
  }

  @Test
  void refreshRotatesTheTokenAndReplayTripsTheFamily() throws Exception {
    String refreshToken = loginOk().get("refreshToken").asText();

    MvcResult rotated =
        mockMvc
            .perform(
                post("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andReturn();
    String successor =
        objectMapper
            .readTree(rotated.getResponse().getContentAsString())
            .get("refreshToken")
            .asText();

    // Replaying the consumed token: 401, and the family (successor included) dies.
    mockMvc
        .perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + successor + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Invalid or expired refresh token"));
  }

  @Test
  void logoutRevokesTheRefreshTokenAndIsIdempotent() throws Exception {
    String refreshToken = loginOk().get("refreshToken").asText();
    String logoutBody = "{\"refreshToken\":\"" + refreshToken + "\"}";

    mockMvc
        .perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(logoutBody))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(logoutBody))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(logoutBody))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authSurfaceIs404WhileToggleOff() throws Exception {
    featureToggleAdminService.setPercentage("password-login", 0);
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"x\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"x\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void blankFieldsAre400() throws Exception {
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\" \",\"password\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists())
        .andExpect(jsonPath("$.errors.password").exists());
  }

  @Test
  void responsesNeverEchoThePassword() throws Exception {
    MvcResult result = login(email, PASSWORD);
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
        .doesNotContain(PASSWORD);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: FAIL — 404s from the permit-listed but nonexistent `/auth/**` endpoints (compile
errors first while the DTOs are missing).

- [ ] **Step 3: Implement DTOs and exceptions**

`LoginRequest.java`:

```java
package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials presented to {@code POST /auth/login}. Email format is deliberately not
 * validated here: an unknown email yields the same generic 401 as a wrong password, so
 * rejecting malformed emails differently would add an enumeration signal for no benefit.
 *
 * @param email the account email
 * @param password the account password
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
```

`RefreshRequest.java`:

```java
package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A refresh-token rotation request for {@code POST /auth/refresh}.
 *
 * @param refreshToken the raw refresh token issued at login or by a previous refresh
 */
public record RefreshRequest(@NotBlank String refreshToken) {}
```

`LogoutRequest.java`:

```java
package com.zarlania.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A logout request for {@code POST /auth/logout}: revokes the presented refresh token.
 *
 * @param refreshToken the raw refresh token to revoke
 */
public record LogoutRequest(@NotBlank String refreshToken) {}
```

`TokenResponse.java`:

```java
package com.zarlania.api.auth.dto;

/**
 * The token pair returned by login and refresh. Both tokens are scoped to a single
 * organization (the auth ADR's one-token-one-organization rule).
 *
 * @param accessToken the signed JWT access token
 * @param expiresInSeconds the access token's lifetime in seconds
 * @param refreshToken the raw refresh token — shown exactly once, only its hash is stored
 */
public record TokenResponse(String accessToken, long expiresInSeconds, String refreshToken) {}
```

`InvalidCredentialsException.java`:

```java
package com.zarlania.api.auth.exception;

/**
 * A login attempt failed — unknown email, missing credential, or wrong password. One
 * exception for all three so responses (and logs) cannot distinguish them
 * (user-enumeration defense); mapped to a generic 401.
 */
public class InvalidCredentialsException extends RuntimeException {

  /** Creates the exception with a fixed, account-free message. */
  public InvalidCredentialsException() {
    super("invalid credentials");
  }
}
```

`PasswordLoginDisabledException.java`:

```java
package com.zarlania.api.auth.exception;

/**
 * The {@code PASSWORD_LOGIN} toggle is off: the {@code /auth} surface does not exist yet
 * for callers. Mapped to 404 so the unreleased surface is indistinguishable from a
 * nonexistent one.
 */
public class PasswordLoginDisabledException extends RuntimeException {

  /** Creates the exception. */
  public PasswordLoginDisabledException() {
    super("password login is disabled");
  }
}
```

- [ ] **Step 4: Implement `AuthService`**

```java
package com.zarlania.api.auth.service;

import com.zarlania.api.auth.dto.TokenResponse;
import com.zarlania.api.auth.exception.InvalidCredentialsException;
import com.zarlania.api.auth.exception.PasswordLoginDisabledException;
import com.zarlania.api.auth.service.RefreshTokenService.RefreshRotation;
import com.zarlania.api.features.Feature;
import com.zarlania.api.features.service.FeatureToggleService;
import com.zarlania.api.identity.service.PasswordCredentialService;
import com.zarlania.api.logging.LogSanitizer;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the token lifecycle: login (verify the identity-owned credential, then
 * mint an access/refresh pair scoped to the user's personal organization), refresh
 * (rotate), and logout (revoke). The public surface of the {@code auth} domain; exchanges
 * only DTOs with the {@code users}, {@code identity}, and {@code organizations} domains
 * (ADR-0011). The whole surface is gated by the {@code PASSWORD_LOGIN} toggle (404 off),
 * evaluated globally — no organization context exists before a token is issued.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

  private final FeatureToggleService featureToggleService;
  private final UserService userService;
  private final PasswordCredentialService passwordCredentialService;
  private final OrganizationService organizationService;
  private final JwtIssuer jwtIssuer;
  private final RefreshTokenService refreshTokenService;

  /**
   * Verifies the email + password and mints a token pair scoped to the user's personal
   * organization. Every failure — unknown email, missing credential, wrong password, or a
   * user with no personal organization — is the same {@link InvalidCredentialsException},
   * and verification does constant work on all paths (see {@code
   * PasswordCredentialService#verify}), so neither response nor timing reveals whether an
   * account exists.
   *
   * @param email the account email
   * @param password the account password
   * @return the minted token pair
   * @throws PasswordLoginDisabledException if the {@code PASSWORD_LOGIN} toggle is off
   * @throws InvalidCredentialsException if the credentials do not verify
   */
  @Transactional
  public TokenResponse login(String email, String password) {
    requirePasswordLoginEnabled();
    UUID userId = userService.findByEmail(email).map(User::id).orElse(null);
    if (!passwordCredentialService.verify(userId, password)) {
      throw new InvalidCredentialsException();
    }
    Organization personalOrganization =
        organizationService
            .findPersonalOrganization(userId)
            .orElseThrow(InvalidCredentialsException::new);
    String accessToken = jwtIssuer.issueUserToken(userId, personalOrganization.id());
    String refreshToken = refreshTokenService.mint(userId, personalOrganization.id());
    log.info(
        "Login: userId={}, organizationId={}",
        LogSanitizer.forLog(userId),
        LogSanitizer.forLog(personalOrganization.id()));
    return new TokenResponse(accessToken, jwtIssuer.accessTokenTtlSeconds(), refreshToken);
  }

  /**
   * Rotates a refresh token and mints a fresh access token for the same user and
   * organization.
   *
   * @param refreshToken the presented raw refresh token
   * @return the successor token pair
   * @throws PasswordLoginDisabledException if the {@code PASSWORD_LOGIN} toggle is off
   * @throws com.zarlania.api.auth.exception.InvalidRefreshTokenException if the token is
   *     unknown, expired, revoked, or replayed (replay also revokes its family)
   */
  @Transactional
  public TokenResponse refresh(String refreshToken) {
    requirePasswordLoginEnabled();
    RefreshRotation rotation = refreshTokenService.rotate(refreshToken);
    String accessToken = jwtIssuer.issueUserToken(rotation.userId(), rotation.organizationId());
    return new TokenResponse(
        accessToken, jwtIssuer.accessTokenTtlSeconds(), rotation.newRawToken());
  }

  /**
   * Revokes the presented refresh token. Idempotent — repeated or unknown tokens still
   * succeed, so logout never fails. The paired access token stays valid up to its
   * remaining TTL (≤ 15 minutes): the deliberate trade-off of stateless access tokens.
   *
   * @param refreshToken the raw refresh token to revoke
   * @throws PasswordLoginDisabledException if the {@code PASSWORD_LOGIN} toggle is off
   */
  @Transactional
  public void logout(String refreshToken) {
    requirePasswordLoginEnabled();
    refreshTokenService.revoke(refreshToken);
  }

  private void requirePasswordLoginEnabled() {
    if (!featureToggleService.isEnabled(Feature.PASSWORD_LOGIN)) {
      throw new PasswordLoginDisabledException();
    }
  }
}
```

- [ ] **Step 5: Implement `AuthController`**

```java
package com.zarlania.api.auth.controller;

import com.zarlania.api.auth.dto.LoginRequest;
import com.zarlania.api.auth.dto.LogoutRequest;
import com.zarlania.api.auth.dto.RefreshRequest;
import com.zarlania.api.auth.dto.TokenResponse;
import com.zarlania.api.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the {@code auth} domain: password login, refresh-token rotation,
 * and logout. Permit-listed in the security chain (these endpoints are how tokens are
 * obtained) and gated by the {@code PASSWORD_LOGIN} toggle (404 while off).
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * Logs in with email + password.
   *
   * @param request the validated credentials
   * @return {@code 200 OK} with the org-scoped token pair
   */
  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request.email(), request.password());
  }

  /**
   * Rotates a refresh token.
   *
   * @param request the validated rotation request
   * @return {@code 200 OK} with the successor token pair
   */
  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authService.refresh(request.refreshToken());
  }

  /**
   * Revokes a refresh token (logout). Idempotent.
   *
   * @param request the validated logout request
   * @return {@code 204 No Content}
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 6: Extend `ApiExceptionHandler`**

Add imports for the three auth exceptions, and these handlers (plus a private
`unauthorized` helper mirroring the existing `conflict`/`notFound` helpers):

```java
  /** Login failed: generic 401; the detail never says whether the account exists. */
  @ExceptionHandler(InvalidCredentialsException.class)
  ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
    return unauthorized("Invalid email or password");
  }

  /** Refresh token unknown/expired/revoked/replayed: generic 401. */
  @ExceptionHandler(InvalidRefreshTokenException.class)
  ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
    return unauthorized("Invalid or expired refresh token");
  }

  /** The password-login toggle is off: the /auth surface does not exist yet (404). */
  @ExceptionHandler(PasswordLoginDisabledException.class)
  ProblemDetail handlePasswordLoginDisabled(PasswordLoginDisabledException ex) {
    return notFound("Resource not found");
  }

  /**
   * Builds a 401 {@link ProblemDetail} from a fixed, safe detail and logs at INFO. Failed
   * logins are expected traffic; the fixed detail keeps credentials and account
   * existence out of responses and logs.
   */
  private static ProblemDetail unauthorized(String detail) {
    log.info("Request rejected (401 Unauthorized): {}", LogSanitizer.forLog(detail));
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
  }
```

- [ ] **Step 7: Run the e2e test, then the whole suite**

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: PASS (9 tests)

Run: `./mvnw test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/zarlania/api/auth src/main/java/com/zarlania/api/web/ApiExceptionHandler.java src/test/java/com/zarlania/api/auth
git commit -m "feat: /auth login, refresh, logout behind password-login toggle (#75)"
```

---

### Task 10: OpenAPI bearer security scheme

**Files:**
- Create: `src/main/java/com/zarlania/api/config/OpenApiSecurityConfig.java`
- Test: `src/test/java/com/zarlania/api/config/OpenApiSecuritySchemeTest.java`

**Interfaces:**
- Consumes: springdoc's annotation-driven components (existing).
- Produces: a `bearerAuth` HTTP bearer scheme in the public OpenAPI document; `/auth/**`
  endpoints visible publicly; `/api/admin/**` still excluded (ADR-0015 —
  `OpenApiVisibilityConfig` is untouched).

- [ ] **Step 1: Write the failing test**

```java
package com.zarlania.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// The public OpenAPI document (the endpoint contract of record, ADR-0003) must document
// the bearer scheme and the /auth surface while still excluding /api/admin/** (ADR-0015).
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSecuritySchemeTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void publicDocumentCarriesBearerSchemeAndAuthEndpointsButNoAdminPaths() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.paths['/auth/login']").exists())
        .andExpect(jsonPath("$.paths['/auth/refresh']").exists())
        .andExpect(jsonPath("$.paths['/auth/logout']").exists())
        .andExpect(jsonPath("$.paths['/api/admin/feature-toggles']").doesNotExist());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OpenApiSecuritySchemeTest`
Expected: FAIL — no `bearerAuth` scheme in the document.

- [ ] **Step 3: Implement**

```java
package com.zarlania.api.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the bearer-token security scheme in the public OpenAPI document (ADR-0003) so
 * protected endpoints can reference it and API consumers know how to authenticate.
 * Endpoints outside the security chain's permit-list require a JWT minted by {@code POST
 * /auth/login}.
 */
@Configuration
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class OpenApiSecurityConfig {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest='OpenApiSecuritySchemeTest,OpenApiVisibilityTest'`
Expected: PASS (the existing visibility test must stay green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/config/OpenApiSecurityConfig.java src/test/java/com/zarlania/api/config/OpenApiSecuritySchemeTest.java
git commit -m "feat: document bearer auth scheme in public OpenAPI (#75)"
```

---

### Task 11: ADRs, reference doc, README

**Files:**
- Create: two ADRs via the `adr-create` skill (ids assigned by the tooling)
- Create: one reference doc via `./scripts/ref new`
- Modify: `README.md`

**Interfaces:**
- Consumes: the implemented behavior of Tasks 1–10 (documents must match the code).
- Produces: the decision records later changes are judged against.

- [ ] **Step 1: ADR — adopt Spring Security with org-scoped JWT auth**

Invoke the `adr-create` skill (it owns numbering, frontmatter, and format; use the
`adr-tags` skill to pick tags — reuse existing ones like `architecture`, `security`,
`api`). Decision content the ADR must record:

- Dependencies adopted: `spring-boot-starter-security`,
  `spring-boot-starter-oauth2-resource-server` (Nimbus JOSE bundled — no third-party JWT
  library).
- Posture: single stateless `SecurityFilterChain`; CSRF disabled (stateless bearer API —
  no session cookie exists); deny-by-default with an explicit permit-list (signup,
  `/auth/**`, public OpenAPI, actuator health/info, CORS preflight); future endpoints are
  born protected. Retires ADR-0015's "obscurity, not security" caveat for `/api/admin/**`.
- **The one-token-one-organization law**: every token (user today; service and
  impersonation tokens in the future) is minted for exactly one organization via the
  `org` claim; cross-org access is always a fresh mint, never a re-scope. Claims
  contract: `sub`, `org`, `token_use` (`"user"`; future `"service"`), `jti`, `iss`
  (`zarlania-api`), `iat`/`exp`.
- Access tokens: HS256 JWT, 15-min TTL, secret env-sourced
  (`ZARLANIA_AUTH_JWT_SIGNING_SECRET`), fail-fast validated, ≥ 32 bytes. RS256/JWKS noted
  as the future path if other services ever validate tokens.
- Refresh tokens: opaque 256-bit, SHA-256-hashed at rest, 30-day TTL, org-scoped,
  single-use rotation with family reuse detection; logout revokes; access tokens outlive
  logout by ≤ their TTL (deliberate); consumed rows retained (purge → issue #77).
- Toggles: `password-login`, `auth-enforcement` (ADR-0016); rollout order login-first.
- Links: spec as a bottom Links entry only (ADR prose states decisions as law on its
  own); issues #75/#76/#77; ADR-0011, ADR-0015, ADR-0016.

- [ ] **Step 2: ADR — Argon2id as the default password hash**

Second `adr-create` invocation. Decision content:

- `DelegatingPasswordEncoder` default flips bcrypt → Argon2id (OWASP parameters: 16-byte
  salt, 32-byte hash, parallelism 1, 19 MiB, 2 iterations) via BouncyCastle
  (`bcprov-jdk18on`).
- **Amends ADR-0017's bcrypt-default clause only**; identity ownership, table shape, and
  the `{id}`-prefix migration mechanism all stand. No rehash machinery: no bcrypt hashes
  exist in any environment (in-memory H2); bcrypt remains verifiable through the
  delegating map as an inherent property.
- Password policy ceiling: 72-byte bcrypt cap → 128 characters (Argon2 does not
  truncate).

- [ ] **Step 3: Reference doc — authentication & token behavior**

```bash
./scripts/ref new --title "Authentication and token behavior" --tags security,api
```

Fill it with **behavior and rules only** (endpoint shapes stay in OpenAPI per
ADR-0003/0013): the one-token-one-organization rule and what it means for future org
switching/impersonation/service tokens (fresh mint, never re-scope); the token lifecycle
(login mints a pair scoped to the personal organization; refresh rotates single-use
tokens; replaying a consumed token revokes the family; logout revokes the refresh token
while the access token lives out ≤ 15 min); toggle gating (`password-login` off → the
`/auth` surface 404s; `auth-enforcement` off → non-permit-listed paths open) and the
login-before-enforcement rollout order; the permit-list philosophy (endpoints are born
protected and opted out, never bolted on).

Then run `./scripts/ref check` and `./scripts/adr check`.
Expected: both PASS.

- [ ] **Step 4: README**

In the README's run/deploy notes (near the existing datasource env-var notes), document:
`ZARLANIA_AUTH_JWT_SIGNING_SECRET` is **required** — the app fails fast at startup
without it; generate with `openssl rand -base64 48`; set it in Render env vars and local
`.env` (git-ignored), never in a committed file.

- [ ] **Step 5: Commit**

```bash
git add docs/adrs docs/reference README.md
git commit -m "docs: auth model + Argon2id ADRs, token behavior reference (#75)"
```

---

### Task 12: Full verification, version bump, PR

**Files:**
- Modify: `pom.xml` (version only, via script)

- [ ] **Step 1: Full gate run**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — Spotless, Checkstyle, SpotBugs/FindSecBugs, tests, JaCoCo ≥ 80%
all green. Fix root causes for any failure (never suppress; see Task 4 Step 8 for the one
sanctioned exclusion).

- [ ] **Step 2: Version bump (feature → minor)**

```bash
./scripts/bump-version bump minor
git add pom.xml
git commit -m "chore: bump version for release (#75)"
```

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin feat/75-login-auth
gh pr create --repo Zarlania/zarlania-api \
  --title "feat: login/auth layer — org-scoped JWTs, deny-by-default chain, Argon2id (#75)" \
  --label release:minor \
  --body "$(cat <<'EOF'
Closes #75. Implements docs/superpowers/specs/2026-07-10-login-auth-design.md.

- POST /auth/login|refresh|logout: password login minting org-scoped HS256 JWTs (sub/org/token_use/jti/iss, 15 min) + rotating hashed refresh tokens (30 d, family reuse detection)
- Deny-by-default stateless SecurityFilterChain behind the auth-enforcement toggle; /auth surface behind password-login (both default OFF — no behavior change until flipped)
- Default password hash bcrypt → Argon2id (OWASP params); policy cap 72 bytes → 128 chars
- Two ADRs (auth model; Argon2id amending ADR-0017) + auth behavior reference doc

## ⚠️ Deploy prerequisite — BEFORE merging

Set `ZARLANIA_AUTH_JWT_SIGNING_SECRET` in the Render service env (generate: `openssl rand -base64 48`). The app fails fast at startup without it.

## Rollout (after merge)

1. `password-login` → 100 (tokens obtainable)
2. `auth-enforcement` → 100 (tokens required outside the permit-list)

Deferred: OAuth #76; rate limiting & refresh-token purge #77.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Verify CI**

Watch the PR checks (`gh pr checks --watch`); the "Release version bump" check must accept
the minor bump. Fix anything red before requesting review/merge.

---

## Verification (whole-feature)

After all tasks, the headline flow works end to end in one e2e pass
(`AuthControllerTest.loginTokenOpensEnforcedAdminEndpoint`): create an account with a
password → login → org-scoped JWT → `/api/admin/feature-toggles` 401 without / 200 with
the token → refresh rotates → replay trips the family → logout revokes. Production is
unaffected at merge (both toggles default off), except that startup now requires the
signing-secret env var — set it in Render **before** merging.
