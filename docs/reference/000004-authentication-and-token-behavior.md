---
id: '000004'
title: Authentication and token behavior
description: How org-scoped JWT access tokens and rotating refresh tokens behave across
  login, refresh, replay, and logout, how the password-login and auth-enforcement
  toggles gate that surface, and the permit-list philosophy new endpoints inherit.
tags:
- architecture
- security
created: '2026-07-11'
updated: '2026-07-11'
related:
- ADR-0018
- ADR-0015
- ADR-0016
- com.zarlania.api.auth
---
# Authentication and token behavior

<!-- ref-meta:start -->
| Field | Value |
| --- | --- |
| ID | 000004 |
| Title | Authentication and token behavior |
| Description | How org-scoped JWT access tokens and rotating refresh tokens behave across login, refresh, replay, and logout, how the password-login and auth-enforcement toggles gate that surface, and the permit-list philosophy new endpoints inherit. |
| Tags | architecture, security |
| Created | 2026-07-11 |
| Updated | 2026-07-11 |
| Related | ADR-0018, ADR-0015, ADR-0016, com.zarlania.api.auth |
<!-- ref-meta:end -->

## Overview

This doc explains how tokens behave once a caller has them: what a token is scoped to, how a
token pair moves through login/refresh/logout, how a stolen or replayed refresh token is
detected and contained, and how the two feature toggles gating this surface interact. It
describes behavior, not the decision to build it that way — the design decisions live in the
org-scoped JWT auth ADR-0018 (dependencies, posture, claims contract, token model) and
ADR-0015 (the permit-list convention this doc's gating rules extend); the code lives in
`com.zarlania.api.auth`.

## Scope

Covers: the one-token-one-organization rule and its consequences for any future way of
changing organization context; the token lifecycle from login through refresh, replay, and
logout; how the `PASSWORD_LOGIN` and `AUTH_ENFORCEMENT` toggles gate the surface and the order
they roll out in; and the permit-list philosophy new endpoints inherit. This doc deliberately
does **not** describe endpoint shapes, request/response bodies, or status codes for `/auth/**`
— those are owned by the public OpenAPI document (ADR-0003), viewable at `/v3/api-docs`.

## Rules / constraints

### The one-token-one-organization rule

- Every token is minted for exactly one organization, carried in the token's `org` claim.
  That claim never changes for the life of the token.
- There is no operation that re-scopes an existing token to a different organization. Any
  need to act under a different organization — a user switching the organization they are
  acting as, an admin impersonating a user for support, a service token acting on behalf of
  an organization — is always satisfied by minting a **fresh** token scoped to the target
  organization, never by mutating or reinterpreting a token already held.
- This rule is a constraint on every future token-issuing path, not only the login path that
  exists today: a future "switch organization" action, an impersonation token, and a service
  token (`token_use: "service"`) must each mint their own org-scoped token rather than
  extending the one-token-one-organization rule with an exception.

### Token lifecycle

- **Login** verifies the submitted email and password against the identity-owned credential
  and, on success, mints a token pair scoped to the user's **personal organization** — not
  any organization the caller names, and not any organization the user may belong to other
  than their personal one. The access token is a 15-minute JWT; the refresh token is an
  opaque 30-day value returned once.
- **Refresh** presents the refresh token and rotates it: the presented token is consumed and
  a successor is minted in the same rotation family, together with a fresh access token for
  the same user and organization. Rotation is single-use — a token can be presented for
  refresh exactly once — and consumption is atomic, so a token cannot be redeemed twice even
  under concurrent presentation of the same raw value; the loser of that race is treated as a
  replay.
- **Replay** — presenting a refresh token that has already been consumed or revoked — revokes
  the entire rotation family (every token descended from the same original mint) before
  rejecting the request. This is a durable action: the family-wide revocation is not undone
  even though the request that triggered it is itself rejected. Replay is the system's
  signal that a refresh token may have been stolen and reused by someone other than its
  rightful holder; revoking the whole family, not just the replayed token, contains that
  possibility.
- **Logout** revokes the presented refresh token. It is idempotent — logging out with an
  already-revoked or unknown token still succeeds. The access token issued alongside that
  refresh token is not individually revoked and continues to work until it expires on its
  own — at most 15 minutes after logout. This is deliberate: a stateless access token needs no
  revocation-list lookup on every request, at the cost of a bounded window where a
  already-issued access token remains usable after logout.

### Toggle gating and rollout order

- While `PASSWORD_LOGIN` is off, the entire `/auth` surface (`/auth/login`, `/auth/refresh`,
  `/auth/logout`) responds `404`, indistinguishable from a route that does not exist — no
  distinct "feature disabled" response is returned.
- While `AUTH_ENFORCEMENT` is off, every path outside the permit-list is open — no token is
  required anywhere, matching the service's pre-auth behavior. Once `AUTH_ENFORCEMENT` is on,
  every non-permit-listed path requires a valid, authenticated bearer JWT.
- The two toggles are independent, and the rollout order is **login-first**: `PASSWORD_LOGIN`
  is turned on before `AUTH_ENFORCEMENT`, so callers can obtain tokens before any endpoint
  starts requiring one. Turning `AUTH_ENFORCEMENT` on while `PASSWORD_LOGIN` is still off would
  lock every non-permit-listed endpoint with no way for a caller to obtain a token in the
  first place.
- `AUTH_ENFORCEMENT` must only ever be set to `0` or `100` — never a partial percentage. The
  feature-toggle service's rollout is keyed on a client-supplied trace id, so a partial
  percentage is shoppable by an adversary: a caller can simply retry with different trace ids
  until one lands outside the enforced slice, meaning partial enforcement provides no actual
  protection while looking like a gradual, safer rollout.

### Permit-list philosophy

- Endpoints are **born protected**. The permit-list is an explicit opt-**out** list — account
  signup, the `/auth/**` surface itself, the public OpenAPI documents and Swagger UI,
  actuator `health`/`info`, and CORS preflight (`OPTIONS`) — not an opt-in list.
  A newly added endpoint that is not added to the permit-list requires a valid token the
  moment it is routed, with no code change needed to make that so.
  Protection is never bolted on after the fact; it is the default an author has to explicitly
  carve an exception out of.

## Related

- ADR-0018 — adopt Spring Security with org-scoped JWT auth: dependencies, stateless
  deny-by-default posture, the one-token-one-organization law, the claims contract, and the
  access/refresh token models this doc describes the behavior of.
- ADR-0015 — admin API surface placement; its "obscurity, not security" caveat is retired by
  ADR-0018 and the permit-list rule this doc explains.
- ADR-0016 — feature-toggle-first policy; establishes the toggle mechanics
  (`PASSWORD_LOGIN`, `AUTH_ENFORCEMENT`) this doc's gating rules apply.
- `com.zarlania.api.auth` — the auth domain (JWT issuance, refresh-token service and
  repository, security configuration, controller).
