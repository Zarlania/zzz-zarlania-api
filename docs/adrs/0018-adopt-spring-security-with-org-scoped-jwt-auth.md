---
id: 0018
name: Adopt Spring Security with org-scoped JWT auth
description: Adopts spring-boot-starter-security and spring-boot-starter-oauth2-resource-server
  for a single stateless, deny-by-default filter chain with an explicit permit-list,
  org-scoped HS256 JWT access tokens under the one-token-one-organization law, and
  rotating opaque refresh tokens with family reuse detection.
status: accepted
date_proposed: '2026-07-11'
date_accepted: '2026-07-11'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- architecture
- security
---
# ADR-0018: Adopt Spring Security with org-scoped JWT auth

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0018 |
| Name | Adopt Spring Security with org-scoped JWT auth |
| Description | Adopts spring-boot-starter-security and spring-boot-starter-oauth2-resource-server for a single stateless, deny-by-default filter chain with an explicit permit-list, org-scoped HS256 JWT access tokens under the one-token-one-organization law, and rotating opaque refresh tokens with family reuse detection. |
| Status | accepted |
| Date proposed | 2026-07-11 |
| Date accepted | 2026-07-11 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | architecture, security |
<!-- adr-meta:end -->

## Context and Problem Statement

Every endpoint in the service is unauthenticated today. ADR-0017 gave accounts a password
credential to verify against, but nothing yet issues a session or token from that
verification, and nothing enforces that a caller present one. ADR-0015 accepted this gap
explicitly for `/api/admin/**`, calling path placement "obscurity, not security" until a real
authentication/authorization mechanism landed. That mechanism is this ADR: a request-level
security layer, a token format and issuance model, and a posture for which endpoints require
a token — chosen so every future endpoint is protected by default rather than by each
author remembering to add a check.

## Decision Drivers

- Every organization's data must stay isolated from every other organization's; the token
  itself, not a lookup the caller controls, must carry that boundary.
- A regression that ships an unprotected endpoint must be the exception a reviewer has to
  spot, not the default a reviewer has to prevent — new endpoints should be protected unless
  explicitly opted out, never protected only once someone remembers to add it.
- The service is a pure bearer-token API with no server-side session; the security model
  should not introduce state (a session store, a cookie) the architecture does not otherwise
  need.
- A stolen refresh token must be detectable and containable, not just individually
  revocable.
- Newly gated behavior must ship behind a feature toggle (ADR-0016), and the rollout must be
  safe to stage: token issuance can exist before enforcement is turned on.
- Keep dependencies lean: prefer what the Spring Security starters already provide (JWT
  encoding/decoding, password hashing) over a third-party JWT library.

## Considered Options

- Adopt `spring-boot-starter-security` and `spring-boot-starter-oauth2-resource-server` for a
  single stateless `SecurityFilterChain`, deny-by-default with an explicit permit-list,
  HS256 JWT access tokens plus opaque rotating refresh tokens, every token scoped to exactly
  one organization (chosen).
- Ship a hand-rolled servlet `Filter` that checks a bearer token without adopting
  `spring-boot-starter-security` at all.
- Session-cookie authentication instead of bearer tokens.
- A single long-lived token per user with no organization scoping, relying on a per-request
  organization-id parameter or header to select context.

## Decision Outcome

Chosen option: **`spring-boot-starter-security` plus
`spring-boot-starter-oauth2-resource-server`, wired as a single stateless
`SecurityFilterChain` that is deny-by-default with an explicit permit-list, issuing org-scoped
HS256 JWT access tokens and opaque rotating refresh tokens**, because it retires ADR-0015's
obscurity caveat with an actual enforcement mechanism, gives every future endpoint a
protected-by-default posture, and needs no third-party JWT library — Nimbus JOSE ships inside
`spring-boot-starter-oauth2-resource-server` and is used directly for both encoding and
decoding.

### Dependencies

Two starters are adopted: `spring-boot-starter-security` (the filter chain and authentication
model; it also transitively provides the `PasswordEncoder` abstraction ADR-0017 already
depended on directly via `spring-security-crypto`, which remains an explicit dependency) and
`spring-boot-starter-oauth2-resource-server` (bearer-token resource-server support, and the
Nimbus JOSE library it bundles for JWT encoding and decoding). No separate JWT library is
added — `JwtEncoder`/`JwtDecoder` beans built on Nimbus classes are the only JWT machinery in
the codebase.

### Posture: stateless, deny-by-default, permit-listed

The API is served by a single `SecurityFilterChain`. Session creation policy is `STATELESS` —
no `HttpSession` is ever created. CSRF protection is disabled: CSRF defends a browser session
riding on a cookie, and this API issues no cookie and holds no session for a cross-site
request to exploit. Every request is `authorizeHttpRequests`-checked; an explicit permit-list
covers the surface that must stay reachable without a token — account signup (`POST
/accounts`), the token-issuing endpoints (`POST /auth/login`, `POST /auth/refresh`, `POST
/auth/logout` — login mints the first token), the public OpenAPI documents and Swagger UI
(ADR-0003), actuator `health`/`info` (ADR-0002), and CORS
preflight (`OPTIONS` on every path, since a browser's preflight carries no bearer token to
check). Every other path — present and future — falls to a toggle-aware authorization rule
that requires a valid authenticated JWT once `AUTH_ENFORCEMENT` is on. A newly added endpoint
therefore starts protected the moment it is routed; the author must add it to the permit-list
to opt it *out*, never add a check to opt it in. This retires ADR-0015's "obscurity, not
security" caveat for `/api/admin/**`: those endpoints now sit behind the same deny-by-default
rule as everything else, gated by `AUTH_ENFORCEMENT` rather than by path secrecy.

Adopting the chain also ships two changes that are always on, not toggle-gated: (a) Spring
Security's default response headers on every response (`X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff`, the `Cache-Control: no-store` family, `X-XSS-Protection:
0`), and (b) a request bearing an `Authorization: Bearer` header that fails to parse or
validate is rejected `401` by the resource-server filter even while `AUTH_ENFORCEMENT` is off —
only the *absence* of a token is permitted on a permit-listed path pre-enforcement, not the
presence of a bad one. Both are accepted as part of the adopted security posture — the
infrastructure of the chain itself — rather than behavior ADR-0016 requires gating.

### The one-token-one-organization law

Every token this service issues — a user's token today, a future service token, a future
impersonation token — is minted for **exactly one organization**, carried in the `org` claim.
Access to a different organization is never obtained by re-scoping or re-interpreting an
existing token; it always requires minting a fresh one. This holds however the need to
"switch organizations" is eventually surfaced to a user (a org-switcher in a UI, an
admin impersonating a user for support, a service token acting on behalf of an organization):
each such action mints its own token scoped to the target organization rather than mutating
or reusing the token already held. A token's `org` claim is fixed for that token's entire
lifetime.

The full claims contract for every access token:

| Claim | Value |
| --- | --- |
| `sub` | the subject id — the user id for a user token |
| `org` | the single organization this token is scoped to |
| `token_use` | `"user"` today; a future `"service"` value for service tokens |
| `jti` | a random per-token id |
| `iss` | `zarlania-api` |
| `iat` / `exp` | issued-at and expiry instants |

### Access tokens

Access tokens are HS256-signed JWTs with a 15-minute TTL. The signing secret is sourced from
the `ZARLANIA_AUTH_JWT_SIGNING_SECRET` environment variable and validated at startup: missing
or blank fails fast, and a secret shorter than 32 bytes fails fast, so the service never signs
or accepts tokens with a weak or absent key. Should a token ever need to be validated by a
service other than the one that issued it, the future path is RS256 with a JWKS endpoint
(asymmetric signing, so a verifier needs only the public key) rather than sharing the HS256
symmetric secret across services.

### Refresh tokens

Refresh tokens are opaque, 256-bit, `SecureRandom`-generated values, returned to the client
exactly once and never logged. Only their SHA-256 hash is persisted; a database compromise
does not expose usable tokens. Each refresh token is scoped to the single organization it was
minted for (mirroring the access token's `org` claim) and lives 30 days.

Rotation is single-use: presenting a refresh token consumes it and mints a successor in the
same rotation family; a consumed or revoked token cannot be presented again. The code carries
two hardening properties beyond that basic rule:

- **Atomic single-use consumption.** Consumption is a single conditional `UPDATE` at the
  database layer (`consumedAt` is set only if the row is not already consumed or revoked),
  not a read-then-write. Under concurrent presentation of the same raw token, exactly one
  caller's update affects a row; every other caller observes zero rows updated and is routed
  down the replay path — closing the race a naive check-then-set would leave open.
- **Durable family-reuse revocation.** Presenting an already-consumed or already-revoked
  token is treated as a stolen-token signal: the entire rotation family is revoked before the
  request is rejected. That revocation is deliberately durable — the rejection is thrown with
  `noRollbackFor` at every transactional boundary it crosses, so the family-wide revocation
  the replay just triggered is never undone by the same transaction's rollback on the way out.

Logout revokes the presented refresh token (idempotent — an unknown or already-revoked token
still succeeds). The paired access token is not individually revocable and is deliberately
allowed to live out its remaining TTL (at most 15 minutes) after logout: this is the accepted
cost of a stateless access token that needs no revocation-list lookup on every request.
Consumed and revoked refresh-token rows are retained, not purged, by this ADR; a retention/purge
policy is deferred to issue #77.

### Toggles and rollout order

Token issuance and enforcement are independently toggled (ADR-0016): `PASSWORD_LOGIN` gates
the entire `/auth/**` surface (off returns 404, indistinguishable from a route that does not
exist), and `AUTH_ENFORCEMENT` gates whether non-permit-listed paths require a valid token at
all (off preserves today's open behavior). The rollout order is login-first: `PASSWORD_LOGIN`
is enabled before `AUTH_ENFORCEMENT`, so callers can obtain tokens before any endpoint starts
requiring one.

### Future additive paths

Nothing above forecloses: RS256/JWKS token verification if a second service ever needs to
validate tokens issued here without sharing the HS256 secret; OAuth as a login method
alongside password login (issue #76); and service tokens (`token_use: "service"`) for
machine-to-machine calls, each minted for exactly one organization under the same law as user
tokens.

### Consequences

- Good: every endpoint, present and future, is protected by default — an author opts a path
  *out* via the permit-list, never opts a path *in* with a hand-added check.
- Good: the one-token-one-organization law makes cross-organization access structurally
  impossible to obtain by mutating a token; it always requires a fresh mint, which is itself
  an auditable, authorizable event.
- Good: no third-party JWT library is added — Nimbus JOSE, bundled with
  `spring-boot-starter-oauth2-resource-server`, is the only JWT machinery.
- Good: refresh-token rotation's atomic consumption and durable family revocation close the
  concurrent-replay race and guarantee a stolen token's blast radius is contained even if the
  rejection path itself is retried or races.
- Good: ADR-0015's obscurity caveat for `/api/admin/**` is retired — those endpoints are now
  actually enforced, not merely undocumented.
- Bad: HS256 requires every verifier to hold the same symmetric secret; this is acceptable
  while only this service validates its own tokens, but does not scale to a second verifying
  service without moving to RS256/JWKS.
- Bad: an access token cannot be revoked mid-flight; logout only revokes the refresh token, so
  a compromised access token remains usable for up to its remaining 15-minute TTL. This is a
  deliberate, bounded trade-off, not an oversight.
- Bad: consumed and revoked refresh-token rows accumulate with no purge yet; deferred to
  issue #77.

## Links

- ADR-0011: Keep domains decoupled in code with DB-level integrity (the `auth` domain's DB-FK,
  no-cross-domain-JPA-association shape for `refresh_tokens`)
- ADR-0015: Admin API surface under /api/admin, excluded from public OpenAPI (this ADR
  retires its "obscurity, not security" caveat for `/api/admin/**`)
- ADR-0016: Gate every behavior change behind a feature toggle (`PASSWORD_LOGIN` and
  `AUTH_ENFORCEMENT`)
- Issue #75: login/auth implementation
- Issue #76: OAuth login
- Issue #77: refresh-token retention/purge policy
- Spec: docs/superpowers/specs/2026-07-10-login-auth-design.md
