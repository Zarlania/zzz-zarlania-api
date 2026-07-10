---
id: '000003'
title: Feature toggle behavior and lifecycle
description: How feature toggles are registered, evaluated, and fail safe — lifecycle, percentage/organization-override rules, and trace-pinned decisions.
tags:
- architecture
- domain-model
created: '2026-07-09'
updated: '2026-07-10'
related:
- ADR-0014
- ADR-0015
- ADR-0010
- com.zarlania.api.features
---
# Feature toggle behavior and lifecycle

<!-- ref-meta:start -->
| Field | Value |
| --- | --- |
| ID | 000003 |
| Title | Feature toggle behavior and lifecycle |
| Description | How feature toggles are registered, evaluated, and fail safe — lifecycle, percentage/organization-override rules, and trace-pinned decisions. |
| Tags | architecture, domain-model |
| Created | 2026-07-09 |
| Updated | 2026-07-10 |
| Related | ADR-0014, ADR-0015, ADR-0010, com.zarlania.api.features |
<!-- ref-meta:end -->

## Overview

Feature toggles let production behavior be turned off, ramped up, or scoped to a single
organization without a redeploy. This doc explains how a toggle moves through its lifecycle,
how a decision is evaluated for a given request and organization, and how the system fails
safe when state is missing or unknown. It describes behavior, not the decision to build it
that way — the design decisions live in ADR-0014 (registry, percentage state, trace-pinned
evaluation) and ADR-0015 (admin surface placement); the code lives in
`com.zarlania.api.features`.

## Scope

Covers: the lifecycle of a toggle from code to deletion, the rules that decide the outcome of
an evaluation, and the fail-safe behavior when a toggle or organization is unrecognized. This
doc deliberately does **not** describe endpoint shapes, request/response bodies, or status
codes for the admin API — those are owned by the public OpenAPI document (ADR-0003), viewable
at `/v3/api-docs` or the admin documentation group described in ADR-0015.

## Rules / constraints

### Lifecycle

- A toggle is born in code: adding a constant to the `Feature` enum is the only way a toggle
  comes into existence. There is no way to create a toggle through the admin API.
- Each toggle carries a human-readable description alongside its kebab-case name, and both are
  code-owned — they live on the `Feature` enum constant, not in any admin-writable state.
- On the next deploy, a startup synchronizer inserts a row for any enum constant that does not
  yet have one, default-off (percentage 0, no organization overrides), including the enum's
  description. If the description text for an existing toggle's enum constant has changed
  since the last deploy, the synchronizer updates the stored description to match — it is the
  only writer of the description; nothing else in the system sets or edits it.
- Operators can see a toggle's description through the admin API, but only ever read it: there
  is no route to create, edit, or clear a description independently of the code. Like the
  toggle's existence itself, its description is born in code and changes only by changing the
  code.
- Once deployed, the toggle can be flipped — globally or per organization — only through the
  admin API. The admin API changes state; it never creates or deletes a toggle.
- When the gated code path becomes permanent (the toggle is no longer needed), the enum
  constant is deleted from the code. On the next deploy, the synchronizer deletes the toggle's
  row and cascades the deletion of any organization overrides tied to it — nothing lingers
  once the constant is gone.

### Evaluation rules

- An organization-specific override, when present, wins unconditionally over the global
  state for that toggle — there is no blending or averaging between the two.
- A percentage of 0 always evaluates to off and a percentage of 100 always evaluates to on;
  both are deterministic regardless of caller or trace.
- A percentage strictly between 0 and 100 is resolved as a per-request coin flip weighted by
  that percentage — the same caller or organization can land on either side on different
  requests. This makes partial state a deploy safety valve, not a mechanism for consistent
  per-user or per-organization experimentation.
- A decision is pinned to the request's trace id for a bounded time-to-live, so that a single
  inbound request and every internal call it fans out to observe the same outcome for the
  same toggle, even under partial rollout.
- If a caller (or a retried/chained request) reuses the same trace id, it keeps whatever
  decision was pinned for that id until the pin's TTL lapses — it does not get a fresh coin
  flip just because time has passed within the TTL window.
- Calls made with no trace context available (for example, application startup or a
  scheduled job with no inbound request to inherit a trace id from) get a fresh evaluation
  every time — there is nothing to pin the decision to.

### Fail-safe rules

- A toggle that has no row in the toggle table — because the code was just deployed and the
  synchronizer hasn't run yet, or a stale enum constant reference exists — evaluates to
  **off**. Absence never means "on."
- An organization with no override for a toggle falls back to that toggle's global state; an
  unrecognized or nonexistent organization id is treated the same way — fall back to global.
- Because the production database is currently in-memory H2 (see ADR-0010), every process
  restart wipes the toggle table; the next startup's synchronizer resets every toggle to
  default-off. Any percentage or organization override that was in effect before the restart
  is gone and must be re-applied through the admin API afterward. This is intentional and
  safe for the toggle's role as a kill switch, but it means state does not survive a restart
  until the service moves to hosted Postgres.

### Gating code

- To gate a code path, inject `FeatureToggleService` and call `isEnabled(Feature.X)` for a
  global decision, or `isEnabled(Feature.X, orgId)` for an organization-aware decision.
- Call it once per decision point in the request. Because partial state is a per-request coin
  flip, calling it more than once for what should be a single logical decision can produce
  inconsistent results within the same request — call it once and reuse the result.

### The canary toggle

- `FEATURE_SERVICE_CANARY` is a permanent fixture of the registry, not a toggle for a real
  feature. It gates no code path. Its only purpose is to give operators a toggle that always
  exists to exercise and smoke-test the feature-toggle mechanism itself in production — flip
  it and confirm the admin API, synchronizer, and evaluation path all behave as expected.
  It is never removed.

## Related

- ADR-0014 — feature-toggle architecture: code registry, percentage state, trace-pinned
  evaluation.
- ADR-0015 — admin API surface placement and public OpenAPI exclusion.
- ADR-0010 — persistence foundation (Spring Data JPA, H2, Flyway); establishes the in-memory
  H2 caveat behind the restart-resets-state rule above.
- `com.zarlania.api.features` — the feature-toggle domain (registry, evaluation service,
  admin service, synchronizer, entities, repositories).
