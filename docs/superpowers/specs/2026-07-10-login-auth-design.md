# Login/auth layer: org-scoped JWTs, Spring Security deny-by-default, Argon2id — design

- **Date:** 2026-07-10
- **Status:** Approved (pending implementation)
- **Issue:** #75 (deferred work: #76 OAuth, #77 rate limiting & token purge)
- **Scope:** Add the authentication layer on top of the password credentials landed in
  ADR-0017: password **login** issuing **organization-scoped JWT access tokens** plus
  rotating opaque **refresh tokens**, **logout**, and enforcement via a **deny-by-default
  Spring Security filter chain** — proven by requiring a valid token on the
  `/api/admin/**` surface. Switch the default password hash from bcrypt to **Argon2id**.
  No roles, no OAuth, no service tokens — but the token contract is shaped so those land
  additively later.

## Summary

This change makes the stored password credential *do* something: `POST /auth/login`
verifies an email + password and mints a token pair — a short-lived **JWT access token**
signed with HS256, and a long-lived, single-use, rotating **refresh token** stored hashed
in a new `auth` domain. A stateless `SecurityFilterChain` (from the full
`spring-boot-starter-security`, adopted here by ADR) is **deny-by-default**: an explicit
permit-list covers today's public surface, and every other path — including
`/api/admin/**` and every future endpoint — requires a valid JWT. Endpoints are born
protected; they are opted *out* of auth, never bolted onto it later.

The **core token-contract rule**, recorded as law in the new auth ADR: **every token is
minted for exactly one organization.** The `org` claim scopes the token; a token never
grants cross-organization reach. Today login mints the pair against the user's personal
organization (their only one). Future org switching, admin impersonation, and service
tokens all follow the same rule — a fresh mint per organization, never a re-scope — and
require no change to the token format: switching orgs is a new mint, impersonation is a
new mint, a service needing three orgs holds three tokens.

Because the per-request validation path is pure JWT signature + expiry checking (no
storage read), and the only stateful piece — the refresh-token store — sits behind a
repository interface, this change is also the groundwork for the future real-DB/Redis
session: swapping the store's backing is a swap, not a redesign.

## Goals

- Log in with **email + password** and receive an org-scoped access/refresh token pair.
- **Logout** revokes the refresh token server-side; refresh **rotates** single-use tokens
  and detects reuse (stolen-token tripwire revokes the token family).
- `/api/admin/**` (and any non-permit-listed path) returns **401 without a valid JWT** —
  proving auth end to end — while today's public surface keeps working untouched.
- **Deny-by-default**: future endpoints require no per-endpoint auth wiring.
- Claims contract accommodates future service tokens (`token_use`), roles (additive
  claims), and org switching/impersonation (fresh mint per org) with no format migration.
- New password hashes use **Argon2id**; nothing weakens for existing data (there is none).
- All new behavior is feature-toggled per ADR-0016.

## Non-goals (deferred)

- **OAuth** — issue #76. Lands later as a second issuance path for the same token pair.
- **Roles / permissions / membership checks** — no roles exist; enforcement here is
  "valid token", never "which token". No `403`s are introduced. The `org` claim is minted
  and carried but **not yet compared** against resource ownership — no org-owned resources
  are exposed yet.
- **Service tokens** — accommodated only by the `token_use` claim (always `"user"` here)
  and by keeping the issuer parameterized by subject + claims. No issuance path is built.
- **Org switching / impersonation / general org creation** — future; each will mint fresh
  tokens under the one-org-per-token rule.
- **Login rate limiting / brute-force protection** — issue #77.
- **Refresh-token purge** — consumed/expired rows are kept (reuse detection wants recent
  history; in-memory H2 self-purges on restart). A purge job belongs to the real-DB
  session — issue #77.
- **The real DB/Redis switch itself** — this change only shapes the seams for it.
- **bcrypt migration/rehash machinery** — no bcrypt hashes exist in any environment
  (in-memory H2), so no verify-then-upgrade path is built. `DelegatingPasswordEncoder`'s
  `{id}` prefix remains the future algorithm-migration mechanism.
- **Password change / reset, email verification** — future auth stories.

## Design

### Dependencies & security posture

New dependencies (recorded in the new auth ADR):

- `spring-boot-starter-security` — the filter chain and auth machinery.
- `spring-boot-starter-oauth2-resource-server` — JWT decode/validation via Nimbus JOSE
  (already bundled; **no third-party JWT library**). `spring-security-oauth2-jose`
  provides `NimbusJwtEncoder` for signing.
- `bcprov` (BouncyCastle provider) — supplies the Argon2 primitive used by
  `spring-security-crypto`'s `Argon2PasswordEncoder`.

One stateless `SecurityFilterChain`, owned by the `auth` domain's config:

- `SessionCreationPolicy.STATELESS`; CSRF disabled (pure bearer-token API — no cookies,
  no sessions); Spring Security's CORS support wired to the existing `CorsProperties`
  allowlist. `Authorization` is added to the CORS allowed request headers.
- **Permit-list** (everything else requires a valid JWT):
  - `POST /accounts` (signup),
  - `/auth/**` (login/refresh/logout — how tokens are obtained),
  - `/v3/api-docs*/**` and swagger-ui (public OpenAPI, ADR-0003),
  - actuator health + info (ADR-0002),
  - CORS preflight (`OPTIONS`).
- **Everything else** — `/api/admin/**` today, every future endpoint — is guarded by a
  toggle-aware `AuthorizationManager` (see Feature toggles): `AUTH_ENFORCEMENT` **on** →
  authenticated JWT required (`401` otherwise, with `WWW-Authenticate: Bearer`);
  **off** → request proceeds exactly as today. Once the toggle goes permanent, the
  manager collapses to plain `.authenticated()`.

`/api/admin/**` needs no special-casing anywhere: ADR-0015's "obscurity, not security"
caveat is retired by the general rule.

### Token model & claims contract

**Access token** — signed JWT, **HS256**, TTL **15 minutes**. Claims:

| Claim       | Value                                                            |
| ----------- | ---------------------------------------------------------------- |
| `sub`       | user id (UUID)                                                   |
| `org`       | organization id the token is scoped to (UUID)                    |
| `token_use` | `"user"` (future service tokens: `"service"`, different subject) |
| `jti`       | unique token id (UUID)                                           |
| `iss`       | `zarlania-api`                                                   |
| `iat`/`exp` | issued-at / expiry                                               |

**One token, one organization** (the law recorded in the auth ADR): a token is valid only
for actions within its `org`. Cross-org access — user switching, admin impersonation,
multi-org services — is always a fresh mint, never a re-scope or a multi-org claim.

**Signing key**: a symmetric secret from configuration —
`zarlania.auth.jwt.signing-secret`, sourced from a Render environment variable (never
committed; ADR non-negotiable). Validated **fail-fast at startup** via a
`@ConfigurationProperties` record (the `CorsProperties` pattern): required, ≥ 32 bytes.
Rotating the secret invalidates outstanding access tokens for ≤ 15 minutes — acceptable.
Asymmetric signing (RS256 + JWKS) is a future concern for when other services validate
tokens; HS256 is right for a single deployable.

**Refresh token** — opaque, 256-bit `SecureRandom`, returned raw exactly once and stored
**SHA-256-hashed**. TTL **30 days**. Org-scoped like the access token it pairs with.
**Single-use with rotation**: `POST /auth/refresh` consumes the presented token and issues
a fresh pair (same user, same org). **Reuse detection**: rotations share a `family_id`;
presenting an already-consumed or revoked token revokes the entire family — the standard
stolen-token tripwire. Consumed rows are kept (see Non-goals for purge).

**Logout** revokes the presented refresh token (idempotent). The outstanding access token
is not tracked server-side and stays valid up to its remaining ≤ 15 minutes — the
standard, deliberate trade-off of stateless access tokens; the short TTL bounds it.

TTLs live in configuration (`zarlania.auth.jwt.access-token-ttl`,
`zarlania.auth.refresh-token-ttl`) with the above defaults, validated fail-fast.

### Endpoints & domain layout

New **`auth` domain** (`com.zarlania.api.auth` → `controller`, `service`, `repository`,
`entity`, `dto`, `config`, `exception` as needed). It owns token issuance and validation
config, the refresh-token store, the `SecurityFilterChain`, and the endpoints:

- `POST /auth/login` `{email, password}` → `200` `{accessToken, expiresInSeconds,
  refreshToken}`. Any failure — unknown email, no password credential, wrong password — is
  the same generic `401` (no user enumeration). To blunt timing-based enumeration, a
  missing user/credential still performs one dummy Argon2id verification so success and
  failure paths do comparable work.
- `POST /auth/refresh` `{refreshToken}` → `200` with a new pair; consumed/revoked/expired
  or unknown token → `401` (reuse of a consumed token also trips family revocation).
- `POST /auth/logout` `{refreshToken}` → `204`, idempotent.

(Endpoint request/response shapes and status codes are owned by the public OpenAPI
document per ADR-0003 — the above sketches intent, it is not the contract of record.)

Cross-domain boundaries stay DTO-only per ADR-0011:

- **users**: resolve the account by email (new read method on `UserService`).
- **identity**: verify the password — the `verify(userId, rawPassword)` method that
  ADR-0017's spec anticipated on `PasswordCredentialService`.
- **organizations**: resolve the user's personal organization for token scoping (existing
  lookup or a new read method as needed).

Login mints against the personal organization because it is the user's only organization
today; when multi-org membership lands, login's default scope becomes a product decision
for that change (the token contract does not move).

The `auth` → `identity` dependency is one-way; `identity` (account creation) does not
depend on `auth`. The `PasswordEncoder` bean stays in `IdentityConfig` — identity owns
credentials and their hashing (ADR-0017); auth only asks identity to verify.

### Password hashing: Argon2id default

- The `DelegatingPasswordEncoder` default flips from bcrypt to **Argon2id** with OWASP's
  current parameters: memory **19 MiB**, iterations **2**, parallelism **1**, 16-byte
  salt, 32-byte hash. New hashes are `{argon2}`-prefixed; `VARCHAR(255)` has ample room.
- bcrypt remains verifiable through the delegating encoder as an inherent property, but no
  rehash/upgrade machinery is built — there are no bcrypt hashes anywhere (in-memory H2).
- `PasswordPolicy` drops the bcrypt-specific **72-byte cap** in favor of a **max of 128
  characters** (Argon2 has no truncation behavior; the ceiling only bounds abuse),
  keeping the existing min-8 + character-class rules. This is a behavior
  change only visible behind `PASSWORD_ACCOUNTS` (already toggled).
- Recorded as a new ADR that **amends ADR-0017's bcrypt-default clause**; everything else
  in ADR-0017 (identity ownership, table shape, `{id}`-prefix migration story) stands.

### Feature toggles (ADR-0016)

Two new `Feature` constants, both evaluated **globally** (no org context exists
pre-authentication):

- **`PASSWORD_LOGIN`** (`password-login`) — gates the three `/auth` endpoints. Off →
  `404` (the surface does not exist yet), on → endpoints live. Evaluated per request in
  the auth controller/service, consistent with how `PASSWORD_ACCOUNTS` gates signup.
- **`AUTH_ENFORCEMENT`** (`auth-enforcement`) — gates the deny-by-default rule via the
  toggle-aware `AuthorizationManager` described above. Off → non-permit-listed paths
  behave exactly as today (admin endpoints open, unknown paths `404`); on → valid JWT
  required. Percentage rollout applies per-trace like any toggle; the expected operator
  path is 0 → 100.

The rollout ordering is operational, not code: enable `password-login` first (tokens
become obtainable), then `auth-enforcement` (tokens become required).

`PASSWORD_ACCOUNTS` is untouched by this change.

### Data model

New migration `V7__create_refresh_tokens.sql` (additive scaffolding — toggle carve-out):

```
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
    CONSTRAINT pk_refresh_tokens        PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash   UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user   FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_refresh_tokens_org    FOREIGN KEY (organization_id)
                                        REFERENCES organizations (id)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
```

- `token_hash` is the hex SHA-256 of the raw token; uniqueness enforced at the DB layer
  per the repo convention. The raw token is never stored or logged.
- `consumed_at` marks normal rotation; `revoked_at` marks logout or family revocation.
  A token is live iff both are null and `expires_at` is in the future.
- Cross-domain FKs follow ADR-0011: DB-level integrity, no cross-domain JPA associations.

### Errors & edge behavior

- Login failure, invalid/expired/missing/consumed tokens → `401` (generic detail;
  `WWW-Authenticate: Bearer` on resource-server rejections). No `403`s (no roles).
- Toggled-off `/auth` endpoints → `404`.
- Existing `ApiExceptionHandler` conventions apply; logging records surrogate ids only —
  never emails, passwords, raw tokens, or JWTs — sanitized via `LogSanitizer`.
- A valid JWT whose user or org was wiped by an H2 restart still passes signature
  validation (stateless by design); it grants nothing org-scoped yet, expires within
  15 minutes, and any future per-resource authorization naturally rejects it. Accepted
  and documented; not defended against.

### OpenAPI

The `/auth` endpoints are public API and appear in the OpenAPI document. A bearer
`SecurityScheme` is added so protected endpoints are marked; `/api/admin/**` remains
excluded (ADR-0015).

### Real-DB/Redis preparation (explicit)

- Per-request auth validation reads **no storage** (JWT signature + expiry only).
- All refresh-token state flows through `RefreshTokenRepository` behind the auth service —
  the single seam a Redis/Postgres-backed store replaces later.
- Flyway migration is written in the H2-PostgreSQL-compatible dialect like its
  predecessors; secrets/TTLs are env-sourced configuration.

## Documentation changes

- **New ADR — adopt Spring Security with org-scoped JWT auth** : the two starters as
  dependencies; stateless deny-by-default posture with permit-list; the token model
  (HS256 access JWT + rotating hashed refresh tokens with family reuse detection); the
  **one-token-one-organization law** and the claims contract (`sub`, `org`, `token_use`,
  `jti`, `iss`); env-sourced signing secret; future paths (OAuth #76, service tokens,
  RS256/JWKS) noted as additive.
- **New ADR — Argon2id as the default password hash**: amends ADR-0017's bcrypt-default
  clause (the rest of ADR-0017 stands); OWASP parameters; BouncyCastle dependency; the
  `{id}`-prefix as the ongoing migration mechanism.
- **New reference doc — authentication & token behavior**: living rules — org scoping,
  token lifecycle (issue/refresh/rotate/reuse-trip/logout), toggle gating and rollout
  ordering, the access-token-outlives-logout trade-off. Behavior and rules only — endpoint
  shapes stay in OpenAPI (ADR-0003/0013).
- **Reference doc 000003** (feature toggles): no structural change; new toggles follow
  existing lifecycle.
- **README**: the required signing-secret environment variable (binding to
  `zarlania.auth.jwt.signing-secret`) for local run and Render deploy, alongside the
  existing datasource notes.

## Testing

Per the repo taxonomy (controllers → e2e, services → integration + unit, repositories →
integration-only), TDD throughout, ≥ 80% gate:

- **Auth endpoints (e2e):** full loop — login → `200` with well-formed org-scoped JWT
  (claims asserted) → admin endpoint `200` with token / `401` without (enforcement on) →
  refresh rotates (old token now `401`, reuse trips family revocation) → logout → refresh
  `401`. Bad credentials → generic `401` identical for unknown email vs wrong password.
  Toggles off: `/auth` → `404`; admin endpoints open (today's behavior preserved).
- **Security chain (e2e):** permit-listed paths (signup, docs, health/info, preflight)
  stay open with enforcement on; non-permit-listed paths `401` without / `200` with a
  valid token; expired and tampered JWTs → `401`.
- **Token issuance service (unit + integration):** claims content, TTLs, org scoping;
  refresh-token hashing (raw token never persisted); rotation and family-revocation state
  transitions.
- **Password verification (integration):** identity `verify` accepts the right password,
  rejects wrong ones; new hashes are `{argon2}`-prefixed with OWASP parameters.
- **RefreshTokenRepository (integration):** `token_hash` uniqueness; family queries;
  FK integrity.
- **Config fail-fast (unit):** missing/short signing secret and invalid TTLs fail startup.

## Open questions

None — design approved.

## Links

- ADR-0017 — password credentials in the identity domain (amended by this change's
  Argon2id ADR).
- ADR-0016 — feature-toggle-first policy (gates both new behaviors).
- ADR-0015 — admin API surface (its obscurity caveat is retired by real auth).
- ADR-0011 — domains decoupled in code, DB-level integrity (governs the new FKs).
- ADR-0003 / ADR-0013 — OpenAPI owns endpoint contracts; reference docs own behavior.
- Issues: #75 (this change), #76 (OAuth), #77 (rate limiting, token purge).
