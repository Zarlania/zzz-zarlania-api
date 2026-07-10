# Password accounts + feature-toggle-first policy — design

- **Date:** 2026-07-10
- **Status:** Approved (pending implementation)
- **Scope:** Add the ability to create an account with a password. The password is a
  **credential owned by the `identity` domain**, stored (hashed) at signup and gated by a
  feature toggle. Enhance the feature-toggle registry so each toggle carries a
  human-readable **description** surfaced to operators. Establish a repo-wide
  **feature-toggle-first policy**: new/changed user-observable behavior ships behind a
  toggle, reusing an existing toggle when one fits.

## Summary

`identity` currently is a pure orchestrator — `IdentityService` composes the `users` and
`organizations` domains and holds no persistence of its own. This change gives `identity`
its **first persistence layer**: a `password_credentials` table and the domain code around
it. At account creation, when the `PASSWORD_ACCOUNTS` toggle is enabled, a bcrypt-hashed
password credential is created in the **same transaction** as the user and their personal
organization. When the toggle is off, account creation behaves exactly as it does today.

The password credential is structured so that when authentication lands later (out of scope
here), it fits without a rewrite: the identity domain becomes the home of credentials, the
`PasswordEncoder` bean and `PasswordCredentialService` are the seams verification will use,
the self-describing `{id}`-prefixed hash allows algorithm migration with no schema change,
and OAuth becomes a sibling table rather than a change to this one.

Alongside the feature, this change makes **feature-toggle-first** a recorded policy (a new
ADR plus CLAUDE.md and README updates) and adds a **`description`** to the `Feature` enum —
persisted and exposed read-only through the admin API — so a developer, an AI, or an
operator can judge whether an existing toggle already covers a change.

## Goals

- Create an account with a password when `PASSWORD_ACCOUNTS` is enabled; store only a
  bcrypt hash, never the plaintext.
- The password is owned by `identity`, not `users`; `users` is unchanged.
- The password hash never crosses the API boundary — `Account` and all identity DTOs stay
  hash-free.
- Account creation stays atomic: user + personal organization + password credential succeed
  or roll back together.
- The structure fits future auth (JWT/OAuth) additively — nothing built here is torn out
  when auth lands.
- The `Feature` enum carries a description, surfaced read-only to operators via the admin
  API.
- Feature-toggle-first is a recorded, enforced policy with clear carve-outs and a cleanup
  obligation.

## Non-goals (deferred)

- **Authentication of any kind** — no login, no password *verification* endpoint, no
  session. This change only *stores* a credential at signup. Verification is the obvious
  next method on `PasswordCredentialService`; it is not built here.
- **JWT and OAuth** — explicitly future. The data model and package layout are shaped so
  they slot in additively (OAuth as a sibling credential table; JWT as a future
  session/token concern that consumes identity's verification).
- **Full Spring Security** — only the standalone `spring-security-crypto` jar is added, for
  `PasswordEncoder`. The full starter would impose security filters and auth autoconfig
  across every endpoint before auth is designed.
- **Argon2 / alternate hash algorithms now** — bcrypt only. The `DelegatingPasswordEncoder`
  `{id}` prefix makes a later migration free, so adopting Argon2 (and its BouncyCastle
  dependency) is deferred until auth work actually wants it.
- **Unlimited-length passphrases** — bcrypt's 72-byte input cap is honored by rejecting
  longer input, not by a pre-hashing scheme.
- **Password change / reset flows** — out of scope; belong to the future auth story.
- **Persisting operator-editable toggle descriptions** — the description is code-owned; the
  synchronizer writes it and operators read it. It is never editable via the admin API.

## Design

### Password data model

New migration `V5__create_password_credentials.sql`:

```
CREATE TABLE password_credentials (
    id            UUID                        NOT NULL,
    user_id       UUID                        NOT NULL,
    password_hash VARCHAR(255)                NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_password_credentials       PRIMARY KEY (id),
    CONSTRAINT uq_password_credentials_user  UNIQUE (user_id),
    CONSTRAINT fk_password_credentials_user  FOREIGN KEY (user_id) REFERENCES users (id)
);
```

- **One password per user** — `user_id` is unique. Enforced at the DB layer and caught in
  code (consistent with the repo's "enforce invariants at the DB layer" convention and how
  `UserService` maps unique-constraint violations to domain exceptions).
- `password_hash` stores the `DelegatingPasswordEncoder` output, e.g. `{bcrypt}$2a$12$...`;
  the algorithm id is embedded in the value, so no separate algorithm column is needed and
  algorithm migration needs no schema change.
- Cross-domain integrity follows ADR-0011: a DB-level FK to `users`, **no** cross-domain JPA
  association, DTO-only boundaries. `VARCHAR(255)` comfortably holds a bcrypt hash (~60
  chars) plus the `{bcrypt}` prefix, with headroom for a future stronger algorithm.

### Identity domain structure

`identity` gains its first persistence layer, following the established per-domain layout
(`entity`, `repository`, `service`, plus `exception` as needed):

- `identity/entity/PasswordCredentialEntity` — JPA entity for the table above; private to
  the domain.
- `identity/repository/PasswordCredentialRepository` — Spring Data repository keyed by
  `user_id` (the seam future verification uses to look a credential up).
- `identity/service/PasswordCredentialService` — creates a credential (hashing via the
  injected `PasswordEncoder`). `verify(...)` is the obvious future method auth will add; it
  is **not** implemented now.
- A `PasswordEncoder` bean: `DelegatingPasswordEncoder` defaulting to **bcrypt strength
  12**, from the standalone `spring-security-crypto` dependency. Lives in the identity
  domain's config (or the shared `config` package) per the established structure.

`IdentityService.createAccount` extends its existing single `@Transactional` method: after
creating the user and personal organization, **when `PASSWORD_ACCOUNTS` is enabled**, it
also creates the password credential. All three participate in one transaction — a failure
anywhere rolls the whole account back, so no half-created account (e.g. a user with no
credential when one was required) can persist. Logging continues to record surrogate ids
only (never the email or password), sanitized via `LogSanitizer`.

Toggle evaluation uses the **global** overload `isEnabled(Feature.PASSWORD_ACCOUNTS)` — there
is no organization context at signup (the personal organization is being created as part of
this very operation).

### API contract & validation

- `CreateAccountRequest` gains a **nullable** `password` field.
- **Lenient input:** unknown/extra fields in the request body are silently ignored, never
  rejected. This is Spring Boot's default (`FAIL_ON_UNKNOWN_PROPERTIES=false`) and is made
  explicit in the spec/tests as a **deliberate contract** so the frontend can send fields
  (including `password`) that the current toggle state may not act on.
- **Toggle OFF:** `password` is ignored entirely — not validated, not stored. The account is
  created passwordless, exactly as today.
- **Toggle ON:** `password` is **required** and validated **at the boundary, failing fast**:
  - present and non-blank,
  - **min 8 characters**,
  - **max 72 bytes** (reject longer input rather than let bcrypt silently truncate it),
  - **at least one uppercase, one lowercase, one digit, and one symbol** (symbol = any
    non-alphanumeric character).
  - A missing or policy-failing password → **400 Bad Request**.
- Because required-ness depends on the toggle state at runtime, password validation is
  **conditional/runtime**, not a static `@NotBlank`/`@Size` on the record. The endpoint
  checks the toggle, then applies the password policy only when ON. The endpoint status
  codes and bodies remain owned by the public OpenAPI document (ADR-0003), not this spec.
- `Account` (and every identity DTO) **never** carries the password or its hash.

### Feature-toggle registry enhancement

- The `Feature` enum gains a **`description`**: a second constructor argument plus a
  `description()` accessor. Every constant gets one — `FEATURE_SERVICE_CANARY` is backfilled,
  and the new constant is `PASSWORD_ACCOUNTS("password-accounts", "Accept and store a bcrypt
  password credential at account creation.")`.
- A migration adds a **`description`** column to `feature_toggles`.
- The startup **synchronizer** now inserts **and updates** the description from the enum: on
  each startup an existing toggle's stored description is reconciled to the enum's current
  value. The description stays **code-owned** — operators read it, they never edit it (a
  toggle is born in code; ADR-0014).
- The description flows out through the existing `FeatureToggle` DTO → admin API, so the
  frontend can show it when an operator views or flips a toggle.

### Feature-toggle-first policy

Recorded as a **new ADR** and reflected in CLAUDE.md and README:

- **Rule:** any change that introduces or alters user-observable behavior must be gated by a
  feature toggle.
- **Reuse first:** before adding a `Feature` constant, check whether an existing toggle
  already covers the change (the new `description()` aids this) and reuse it; add a new
  constant only when none fits.
- **Cleanup obligation:** once a gated feature is permanent, its `Feature` constant is
  removed — the enum-deletion lifecycle from reference doc 000003, now framed as an
  obligation, so toggles do not accumulate forever.
- **Carve-outs** (changes that do *not* require a toggle): pure refactors / no behavior
  change, docs and ADRs, build/CI/tooling, the feature-toggle machinery itself, and additive
  DB migrations that scaffold a gated feature. The rule is "new or changed *behavior* is
  gated," not "every commit gets a toggle."

## Documentation changes

- **New ADR — feature-toggle-first policy:** the mandate, reuse-first workflow, cleanup
  obligation, and carve-outs. References ADR-0014 and reference doc 000003 for the mechanics
  rather than duplicating them.
- **New ADR — password credentials & hashing strategy:** password credentials owned by
  `identity`; the `spring-security-crypto` dependency; `DelegatingPasswordEncoder` defaulting
  to bcrypt (strength 12); the `{id}`-prefixed hash as the algorithm-migration path; the
  cross-domain FK to `users` under ADR-0011; JWT/OAuth noted as future siblings.
- **CLAUDE.md:** a short feature-toggle-first rule under the non-negotiables.
- **README:** a "Making a change" bullet pointing at the policy.
- **Reference doc 000003:** updated for the enum `description` — that it is code-owned,
  synced (insert *and* update), and surfaced read-only to operators via the admin API.

## Testing

Follows the repo's test-layering taxonomy (controllers → e2e, services →
integration + unit, repositories → integration-only), TDD throughout, under the ≥ 80%
coverage gate:

- **Identity controller (e2e):** account creation with the toggle **off** (password field
  ignored, account created passwordless, no hash in response) and **on** (password required;
  success path; each validation failure → 400; lenient extra fields accepted). Assert the
  response body never contains the password or hash in either state.
- **`PasswordCredentialService` (integration + unit):** creates a credential whose stored
  value is a `{bcrypt}` hash of the input and is **not** the plaintext; rejects a second
  credential for the same user (unique `user_id`).
- **`IdentityService` (integration):** when the toggle is on, a failure creating the
  credential rolls back the user and organization (atomicity); when off, no credential is
  created.
- **`PasswordCredentialRepository` (integration):** unique-constraint behavior on `user_id`.
- **Synchronizer:** description is inserted for a new toggle and **updated** when the enum's
  description changes for an existing toggle.
- **`PasswordEncoder` config:** the bean is a delegating encoder defaulting to bcrypt and
  produces `{bcrypt}`-prefixed hashes.

## Open questions

None — design approved.

## Links

- ADR-0011 — domains decoupled in code, DB-level integrity (governs the cross-domain FK).
- ADR-0014 — feature-toggle architecture (registry, percentage state, evaluation).
- ADR-0015 — admin API surface placement.
- ADR-0003 — public springdoc OpenAPI owns endpoint contracts.
- Reference doc 000003 — feature toggle behavior and lifecycle.
