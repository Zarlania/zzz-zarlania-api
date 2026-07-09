---
id: '0015'
name: Admin API surface under /api/admin, excluded from public OpenAPI
description: Places administrative endpoints under /api/admin/** and strips them from
  the public OpenAPI document, as obscurity rather than security.
status: accepted
date_proposed: '2026-07-09'
date_accepted: '2026-07-09'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- documentation
- security
---
# ADR-0015: Admin API surface under /api/admin, excluded from public OpenAPI

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0015 |
| Name | Admin API surface under /api/admin, excluded from public OpenAPI |
| Description | Places administrative endpoints under /api/admin/** and strips them from the public OpenAPI document, as obscurity rather than security. |
| Status | accepted |
| Date proposed | 2026-07-09 |
| Date accepted | 2026-07-09 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | documentation, security |
<!-- adr-meta:end -->

## Context and Problem Statement

The service now needs administrative endpoints (starting with feature-toggle management)
that operate on production state and are not meant for public consumption or the public
Swagger UI. ADR-0003 committed to serving `/v3/api-docs` as the public, machine-readable API
contract; we need a placement and documentation strategy for admin endpoints that does not
leak them into that public contract, while the repo has no auth mechanism yet to actually
lock them down.

## Decision Drivers

- Admin endpoints must not appear in the public OpenAPI document or public Swagger UI
  consumed by ADR-0003's audience.
- The repo has no authentication/authorization story yet — any decision here must not imply
  a security guarantee it cannot back up.
- springdoc's grouping mechanism is a documentation-visibility tool, not an access-control
  tool, and must not be treated as one.
- Admin tooling still benefits from machine-readable docs during development, without those
  docs being reachable by default in every environment.

## Considered Options

- A `/api/admin/**` path prefix, stripped from the public OpenAPI document via an
  `OpenApiCustomizer`, with a separate documentation group gated behind a property (chosen).
- A separate springdoc group for admin endpoints, left registered and visible in the default
  Swagger UI group selector.
- No path convention; rely on documentation alone to tell admin and public endpoints apart.

## Decision Outcome

Chosen option: **a dedicated `/api/admin/**` path prefix, stripped from the root public
OpenAPI document, with machine-readable admin (and public) group documents available only
when explicitly enabled**.

All administrative endpoints live under `/api/admin/**`. The root `/v3/api-docs` document —
ADR-0003's public contract — strips any operation under that prefix via an
`OpenApiCustomizer`, so the public document and the public Swagger UI never list admin
operations.

Machine-readable per-group OpenAPI documents (an admin group and a public group) exist only
when the `zarlania.docs.expose-admin=true` property is set; by default the property is unset
and neither group document is served. This property gate exists because springdoc has no way
to register a group and simultaneously hide it from the Swagger UI's group selector — the
only way to keep the admin group out of that selector by default is to not register the
group at all.

This is defense-in-depth **obscurity, not security**. The `/api/admin/**` endpoints remain
unauthenticated and callable by anyone who knows or discovers the path, exactly like every
other endpoint in the service today, regardless of whether `zarlania.docs.expose-admin` is
set. Nothing here restricts *access* to the endpoints — only their *discoverability* through
generated documentation. When the repo-wide authentication and authorization story lands, the
admin documentation group (and the endpoints themselves) must be gated by role at that point;
until then, path obscurity and undocumented status are the only barriers.

This decision refines ADR-0003 rather than contradicting it: ADR-0003's public
`/v3/api-docs` contract still describes every public endpoint accurately and completely; this
ADR narrows what counts as "public" by carving out `/api/admin/**` as a segment that
contract does not cover.

### Consequences

- Good: the public OpenAPI contract from ADR-0003 stays accurate and uncluttered — public
  consumers never see admin operations.
- Good: `/api/admin/**` gives a single, greppable convention for every future admin endpoint,
  rather than deciding path placement per-feature.
- Good: machine-readable admin docs are available on demand (via the property) for local
  development and internal tooling, without being served by default.
- Bad: until the repo has an authentication/authorization mechanism, `/api/admin/**`
  endpoints are reachable by anyone who finds the path — this ADR provides no actual access
  control, and must not be read as one.
- Bad: the property gate is an all-or-nothing switch for *documentation* visibility; it is
  not, and must not be treated as, a runtime feature toggle for the endpoints themselves.

## Links

- ADR-0003: Serve API docs via public springdoc OpenAPI (this ADR refines what counts as
  "public" under that contract)
- Spec: docs/superpowers/specs/2026-07-08-feature-service-design.md
