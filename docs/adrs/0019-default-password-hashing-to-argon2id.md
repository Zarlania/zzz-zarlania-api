---
id: 0019
name: Default password hashing to Argon2id
description: 'Amends ADR-0017''s bcrypt-default clause only: the DelegatingPasswordEncoder
  now defaults new password hashes to Argon2id with OWASP parameters via BouncyCastle,
  with no rehash machinery since no bcrypt hashes exist in any environment, and raises
  the password policy ceiling from bcrypt''s 72-byte cap to 128 characters.'
status: accepted
date_proposed: '2026-07-11'
date_accepted: '2026-07-11'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- persistence
- security
---
# ADR-0019: Default password hashing to Argon2id

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0019 |
| Name | Default password hashing to Argon2id |
| Description | Amends ADR-0017's bcrypt-default clause only: the DelegatingPasswordEncoder now defaults new password hashes to Argon2id with OWASP parameters via BouncyCastle, with no rehash machinery since no bcrypt hashes exist in any environment, and raises the password policy ceiling from bcrypt's 72-byte cap to 128 characters. |
| Status | accepted |
| Date proposed | 2026-07-11 |
| Date accepted | 2026-07-11 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | persistence, security |
<!-- adr-meta:end -->

## Context and Problem Statement

ADR-0017 established the `password_credentials` shape and a `DelegatingPasswordEncoder`
defaulting to bcrypt strength 12, explicitly leaving the door open to a stronger default
later without a schema migration, because the delegating encoder's `{id}` prefix makes the
hashing algorithm self-describing per row. Login now exists (see the org-scoped JWT auth
ADR), which is the point at which a password hash is actually verified against an
attacker-reachable input for the first time. That is the moment to adopt the stronger default
this repo has no reason to defer any longer: Argon2id is the current OWASP-recommended
password-hashing algorithm, and no bcrypt hash yet exists anywhere the new default would need
to coexist with. This ADR decides that one clause only — the default algorithm — and the
policy ceiling it forces; it changes nothing else ADR-0017 decided.

## Decision Drivers

- Argon2id is OWASP's current recommended default for new password hashes; bcrypt remains
  acceptable but is no longer the strongest available default.
- No bcrypt hash exists in any environment (the identity domain's password store is
  in-memory H2, per ADR-0010) — there is nothing to migrate and no rehash-on-login machinery
  is needed to support a mixed population of old and new hashes.
- The `{id}`-prefixed `DelegatingPasswordEncoder` ADR-0017 chose already supports exactly
  this: swapping the default algorithm requires no schema change and does not break
  verification of any hash the previous default produced.
- Argon2 does not truncate its input the way bcrypt truncates at 72 bytes, so the password
  policy ceiling can move to something a human is more likely to actually type.
- A new dependency (BouncyCastle) is only justified if it is the standard, framework-endorsed
  path to Argon2id support, not a hand-rolled implementation.

## Considered Options

- Flip the `DelegatingPasswordEncoder` default from bcrypt to Argon2id with OWASP parameters,
  via Spring Security's `Argon2PasswordEncoder` (backed by BouncyCastle's `bcprov-jdk18on`),
  keeping the bcrypt delegate registered (chosen).
- Keep bcrypt strength 12 as the default and revisit later.
- Move to Argon2id but build in rehash-on-verify machinery to opportunistically upgrade
  legacy hashes.

## Decision Outcome

Chosen option: **flip the `DelegatingPasswordEncoder` default to Argon2id with OWASP
parameters, via BouncyCastle's `bcprov-jdk18on`, with the bcrypt delegate kept registered and
no rehash machinery**, because it adopts the stronger current-recommended algorithm at the
moment login makes it load-bearing, costs nothing in migration since no bcrypt hash exists
yet, and needs no new machinery beyond parameter and dependency changes.

This ADR **amends only ADR-0017's bcrypt-default clause**. Everything else ADR-0017 decided
stands unchanged: the identity domain still owns the `password_credentials` table with the
same shape (DB `FOREIGN KEY` to `users`, no cross-domain JPA association, per ADR-0011); the
`{id}`-prefixed `DelegatingPasswordEncoder` mechanism is unchanged; and the `PASSWORD_ACCOUNTS`
toggle still gates credential creation exactly as ADR-0017 described. The only thing this ADR
changes is which algorithm the delegating encoder uses **by default** for newly created
hashes.

The default algorithm id is now `argon2`, using Spring Security's `Argon2PasswordEncoder`
configured with OWASP-recommended parameters: 16-byte salt, 32-byte hash, parallelism 1,
19 MiB memory, and 2 iterations. `Argon2PasswordEncoder` is backed by BouncyCastle
(`bcprov-jdk18on`), added as a new dependency. The `bcrypt` delegate remains registered in the
same `DelegatingPasswordEncoder` map, so a `{bcrypt}`-prefixed hash — were one ever to exist —
would still verify; this is an inherent property of the delegating encoder's design, not a
purpose-built rehash path. No rehash-on-verify machinery is added because none is needed: no
bcrypt hash exists in any environment today, so every hash created from this point on is
`{argon2}`-prefixed from the moment `PASSWORD_ACCOUNTS` first creates a credential under this
ADR.

The password policy ceiling moves from bcrypt's 72-byte truncation cap to 128 characters.
Argon2 does not truncate its input the way bcrypt silently does at 72 bytes, so the boundary
validation that previously had to track bcrypt's byte limit can instead express the ceiling in
characters a user actually types.

### Consequences

- Good: new password hashes use Argon2id, OWASP's current recommended default, at OWASP's
  recommended parameters.
- Good: zero migration cost — no bcrypt hash exists anywhere to migrate or coexist with, so no
  rehash-on-login machinery is built or needed.
- Good: ADR-0017's table shape, domain ownership, and `PASSWORD_ACCOUNTS` gating are
  untouched; this is a narrow, additive amendment to one clause.
- Good: the password policy ceiling rises from 72 bytes (bcrypt's silent truncation point) to
  128 characters, a limit expressed in what a user actually types rather than an
  algorithm's internal byte cap.
- Bad: a new dependency (BouncyCastle `bcprov-jdk18on`) is now on the classpath purely to back
  `Argon2PasswordEncoder`.
- Bad: Argon2id costs more CPU/memory per verification than bcrypt strength 12; this is the
  intended trade-off (stronger resistance to offline cracking) but is a real per-login cost
  that did not exist before.

## Links

- ADR-0011: Keep domains decoupled in code with DB-level integrity (the domain-ownership and
  DB-FK shape this ADR leaves unchanged)
- ADR-0016: Gate every behavior change behind a feature toggle (the `PASSWORD_ACCOUNTS`
  toggle this ADR leaves unchanged)
- ADR-0017: Store password credentials in the identity domain, hashed with bcrypt (amended by
  this ADR — its bcrypt-default clause only; the rest of that ADR stands)
