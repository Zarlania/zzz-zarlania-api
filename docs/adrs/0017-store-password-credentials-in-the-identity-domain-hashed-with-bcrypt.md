---
id: '0017'
name: Store password credentials in the identity domain, hashed with bcrypt
description: 'The identity domain owns password credentials in a password_credentials table, hashed via a standalone spring-security-crypto DelegatingPasswordEncoder defaulting to bcrypt strength 12, with creation gated by the PASSWORD_ACCOUNTS toggle.'
status: accepted
date_proposed: '2026-07-10'
date_accepted: '2026-07-10'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- architecture
- persistence
- security
---
# ADR-0017: Store password credentials in the identity domain, hashed with bcrypt

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0017 |
| Name | Store password credentials in the identity domain, hashed with bcrypt |
| Description | The identity domain owns password credentials in a password_credentials table, hashed via a standalone spring-security-crypto DelegatingPasswordEncoder defaulting to bcrypt strength 12, with creation gated by the PASSWORD_ACCOUNTS toggle. |
| Status | accepted |
| Date proposed | 2026-07-10 |
| Date accepted | 2026-07-10 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | architecture, persistence, security |
<!-- adr-meta:end -->

## Context and Problem Statement

Accounts need passwords, but authentication as a whole — login, session issuance, JWT, OAuth —
is a later, repo-wide story. Something must own credential storage and hashing now, so account
creation can proceed, without pulling in a full authentication/authorization stack or foreclosing
how login is later implemented. We need a home for the credential, a storage shape that respects
domain boundaries, and a hashing scheme that is safe today and migratable as algorithms change.

## Decision Drivers

- Credential storage must exist now, ahead of the broader login/session/OAuth story, without
  building or wiring an auth stack that isn't needed yet.
- Domain boundaries (ADR-0011) must hold: a credential belongs to a user, but the credential
  table cannot become a cross-domain JPA association.
- Plaintext passwords must never be stored, logged, or returned; only a hash may be persisted.
- The hashing scheme must be changeable later (stronger algorithm, higher cost factor) without a
  schema migration forcing a rewrite of every stored row.
- Newly gated behavior must ship behind a feature toggle per ADR-0016.

## Considered Options

- Identity domain owns a `password_credentials` table (DB FK to `users`, no JPA association),
  hashed via a standalone `spring-security-crypto` `DelegatingPasswordEncoder` defaulting to
  bcrypt strength 12, gated by the `PASSWORD_ACCOUNTS` toggle (chosen).
- Pull in `spring-boot-starter-security` for hashing, accepting its filter chain and
  autoconfiguration even though no request-level authentication is being built yet.
- Store the password hash as a column directly on the `users` entity instead of a separate
  credential table.

## Decision Outcome

Chosen option: **a dedicated `password_credentials` table owned by the identity domain, hashed
with a standalone `spring-security-crypto` `DelegatingPasswordEncoder` defaulting to bcrypt
strength 12, gated by the `PASSWORD_ACCOUNTS` toggle**, because it gives accounts a working,
secure credential store today while keeping every future auth concern — verification, OAuth,
sessions — a clean addition rather than a rewrite.

The `identity` domain owns credentials. Passwords are stored in a `password_credentials` table:
one row per user, a unique `user_id` column, and a database `FOREIGN KEY` to `users` per
ADR-0011 — never a cross-domain JPA association. Hashing uses the standalone
`spring-security-crypto` dependency, not `spring-boot-starter-security`; this pulls in only the
`PasswordEncoder` abstraction with no servlet filter chain or security autoconfiguration
installed. The encoder is a `DelegatingPasswordEncoder` defaulting to `bcrypt` at strength 12.
Stored hashes are `{bcrypt}`-prefixed, so the algorithm identifier travels with the hash and a
future algorithm change (or cost-factor bump) needs no schema change — the delegating encoder
reads the prefix to know how to verify each row. Creation of a password credential is gated by
the `PASSWORD_ACCOUNTS` feature toggle (ADR-0016).

This is deliberately a narrow slice of the eventual auth story, chosen so nothing built here is
torn out when the rest of it lands: verification (checking a submitted password against the
stored hash) is a later method on the credential service; OAuth is a sibling credential table
under the same identity domain; JWT/session issuance is a future concern that consumes identity's
verification result rather than replacing it.

### Consequences

- Good: accounts can be created with a securely hashed password today, without waiting on or
  building the full login/session/OAuth story.
- Good: a new dependency is required — `spring-security-crypto`, with its version managed by the
  Spring Boot BOM rather than pinned by hand — but not `spring-boot-starter-security`, so no
  filter chain or security autoconfiguration is installed for functionality that doesn't exist
  yet.
- Good: plaintext passwords are never stored, logged, or returned; only the `{bcrypt}`-prefixed
  hash is persisted.
- Good: the `{bcrypt}` prefix makes the hashing algorithm self-describing, so a future move to a
  stronger algorithm or cost factor needs no schema migration and can coexist with
  already-hashed rows.
- Good: the credential table stays decoupled from the `users` entity in code (ADR-0011), while
  the database FK still enforces that a credential cannot outlive its user.
- Bad: bcrypt truncates its input at 72 bytes; this is enforced by the password policy at the
  boundary rather than the hashing library, so the policy must be kept in sync with that limit.
- Bad: no login, verification, or session behavior exists yet — this ADR only covers storage and
  hashing; a future ADR will be needed if the eventual auth mechanism (e.g. session vs. stateless
  JWT) departs from the "verification as an identity service method" shape assumed here.

## Links

- ADR-0011: Keep domains decoupled in code with DB-level integrity (the no-cross-domain-JPA-
  association and DB-FK rule this ADR applies to `password_credentials`)
- ADR-0016: Gate every behavior change behind a feature toggle (the `PASSWORD_ACCOUNTS` toggle
  gating credential creation)
