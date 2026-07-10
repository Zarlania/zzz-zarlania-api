---
id: '0014'
name: 'Feature toggles: code registry, percentage state, trace-pinned evaluation'
description: Registers feature toggles as an enum-backed code registry with percentage
  state, trace-pinned evaluation, and a pluggable decision cache.
status: accepted
date_proposed: '2026-07-09'
date_accepted: '2026-07-09'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- architecture
- persistence
---
# ADR-0014: Feature toggles: code registry, percentage state, trace-pinned evaluation

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0014 |
| Name | Feature toggles: code registry, percentage state, trace-pinned evaluation |
| Description | Registers feature toggles as an enum-backed code registry with percentage state, trace-pinned evaluation, and a pluggable decision cache. |
| Status | accepted |
| Date proposed | 2026-07-09 |
| Date accepted | 2026-07-09 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | architecture, persistence |
<!-- adr-meta:end -->

## Context and Problem Statement

Every merge to `master` deploys to production. A regression discovered after deploy needs a
kill switch that does not require a redeploy to pull. Rollouts also need to be gradual
(percentage-based) and, in some cases, scoped to a single organization rather than flipped
globally.

## Decision Drivers

- Turning a feature off must not require a build, a deploy, or a rollback.
- Rollout must support a percentage between fully off and fully on, not just a binary switch.
- Rollout must support overriding the global state for a specific organization.
- The set of toggles must be defined in code so it is reviewable, typed, and cannot drift
  into an open-ended admin-created list.
- A single request that fans out into chained internal calls must evaluate a toggle
  consistently across every hop.

## Considered Options

- Enum-backed code registry synced to a DB table at startup, with a percentage-based state
  model and trace-pinned evaluation (chosen).
- Admin-created toggles with free-text keys, stored and managed entirely through the API.
- Environment-variable toggles, changed via a Render redeploy.
- A third-party feature-flag SaaS.

## Decision Outcome

Chosen option: **an enum-backed code registry with percentage state, trace-pinned
evaluation, and a pluggable decision cache**, because it gives us redeploy-free control
without introducing an open-ended, admin-authored surface or an external dependency.

- Toggles are registered in code as constants of a `Feature` enum. A startup
  `ApplicationRunner` synchronizes the enum against the `feature_toggles` table: new
  constants are inserted default-off (percentage 0), and constants no longer present in the
  enum are deleted, cascading their organization overrides. The admin API can only change the
  state of an existing toggle — it can never create or delete one.
- State is a single percentage in the range 0–100: 0 means off, 100 means on, and any value
  in between means partial — resolved as a per-request coin flip weighted by that
  percentage. State exists both globally and per organization; when an organization override
  is present, it wins unconditionally over the global state.
- Decisions are pinned per trace id — the W3C `traceparent` header, falling back to
  `X-Trace-Id`, falling back to a generated id when neither is present — in a TTL- and
  size-bounded in-process cache implemented with Caffeine, accessed exclusively behind a
  `TraceDecisionCache` interface. Caffeine is adopted as a Boot-BOM-managed dependency for
  this purpose. **Render Key Value (managed Valkey) is the designated successor
  implementation for `TraceDecisionCache` once the service runs multiple instances** — the
  in-process cache does not share decisions across instances, which is acceptable for a
  single-instance deployment but would let concurrent instances disagree once the service
  scales out.
- Evaluation fails safe: a toggle with no row in `feature_toggles` (not yet synced, or
  removed from the enum but not yet redeployed) evaluates to off. An organization with no
  override falls back to the global state.
- Percentage state is deliberately per-request, not sticky bucketing — the same organization
  or caller can land on either side of a partial rollout on different requests. This is a
  deploy safety valve for de-risking a rollout and killing a regression quickly, not an
  experimentation or A/B-testing platform, and it must not be used as one.

### Consequences

- Good: turning a feature off in production is an admin API call, not a redeploy.
- Good: the registry of toggles is defined in code, reviewed in PRs, and cannot grow into an
  arbitrary admin-authored list.
- Good: trace-pinned evaluation keeps a single request and its chained internal hops
  consistent, even under partial rollout.
- Good: `TraceDecisionCache` is a seam, not a commitment to the in-process implementation —
  moving to Render Key Value later is a swapped implementation, not a redesign.
- Bad: percentage state is per-request, so it cannot express "this organization's users
  always see the new behavior" without an explicit organization override — sticky
  bucketing is out of scope by design.
- Bad: per ADR-0010, the production database is currently in-memory H2, so every restart
  resets `feature_toggles` to its synced, default-off state — any percentage or organization
  override in effect before the restart is lost and must be re-applied through the admin API
  afterward. This is safe for the toggle's role as a kill switch (the default a restart lands
  on is always "off") but means an in-flight gradual or per-organization rollout does not
  survive a restart until the service moves to hosted Postgres.

## Links

- ADR-0010: Adopt Spring Data JPA with H2 and Flyway (establishes the in-memory H2 caveat
  this ADR's Consequences section relies on)
- Spec: docs/superpowers/specs/2026-07-08-feature-service-design.md
