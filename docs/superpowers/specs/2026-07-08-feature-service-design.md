# Feature service: code-registered feature toggles — design

- **Date:** 2026-07-08
- **Status:** Approved (pending implementation)
- **Scope:** A `features` domain providing code-registered feature toggles with
  off/on/partial (percentage) states, per-organization overrides, trace-pinned
  evaluation, and an unauthenticated admin API hidden from the public OpenAPI group.

## Summary

Add a `features` domain that lets code wrap new behavior in feature toggles which are
**created in code** (an enum registry), **default off**, and **flipped at runtime** via
admin HTTP endpoints — no redeploy needed to enable a feature after it ships or to kill
it when it regresses. A toggle's state is a single percentage (`0` = off, `100` = on,
in between = partial), settable globally and overridable per organization. Evaluation
decisions are pinned to a request's trace id, so every check within one request — or a
chained hop carrying the trace header — sees the same answer.

## Goals

- Toggles are born and die with code: adding an enum constant creates the toggle
  (default off, synced to the DB at startup); removing the constant removes it.
- Admin API can set a toggle globally or per organization to off / on / partial
  (any percentage 0–100), taking effect on the next request — the kill-switch path.
- Partial rollout is a **per-request coin flip**: each request independently has an
  n% chance of the new path. Within one trace, repeated checks return the same value.
- Admin endpoints live under `/api/admin/**` and are excluded from the public OpenAPI
  document and Swagger UI.

## Non-goals (deferred)

- **Authentication on the admin API** — the endpoints are open, consistent with every
  endpoint in the app today. Hiding them from public docs is defense-in-depth
  (obscurity), not security. Real auth is a future repo-wide story.
- **Per-identity docs visibility** — "hide admin docs depending on who you are"
  requires authentication; the separate admin docs group is designed so a role rule
  can gate it later.
- **Sticky bucketing / A-B testing semantics** — a given org or user is *not*
  guaranteed a consistent experience across requests at partial percentages. These
  toggles are deploy safety valves, not an experimentation platform.
- **Shared/external decision cache** — the trace-decision cache is in-process
  (Caffeine). Render Key Value (managed Valkey) is the recorded successor when the
  service goes multi-instance; the `TraceDecisionCache` interface is the seam.
  (Render free-tier Key Value is 25 MB, non-persistent — a true cache.)
- **Toggle CRUD over HTTP** — no POST/DELETE of toggles via API; the enum is the
  registry.
- **Audit history of toggle changes** — only `updated_at` records that something
  changed.

## Design

### Code registry: the `Feature` enum

`com.zarlania.api.features.Feature` (domain root, like `organizations`'
`MembershipRole`) lists every toggle. Adding a constant is creating a toggle; the
constant name is the toggle's API name. Ships with one permanent constant,
`FEATURE_SERVICE_CANARY`, a no-op toggle used to smoke-test the mechanism in
production and as a stable constant for tests.

### Startup sync

An `ApplicationRunner` in the features service layer (runs during boot, before the
app is considered started, so a failure aborts startup) reconciles the enum with the
DB:

- Inserts a `feature_toggles` row at percentage `0` for each constant with no row —
  **created default off**.
- Deletes rows whose name matches no constant (cascade removes their org overrides).

Code and DB cannot drift. Sync failure fails startup (fail fast).

**Persistence caveat:** production currently runs H2 in-memory (ADR-0010 POC state),
so *all* toggle settings reset to default-off on every restart or idle spin-down of
the Render free-tier instance. This is fail-safe for a kill switch (a restart can only
turn features off, never surprise-enable them) but means percentages and overrides
must be re-applied after a restart until the Postgres swap happens.

### Data model (Flyway `V4__create_feature_toggles.sql`)

House conventions: UUID surrogate PKs, named constraints, and `created_at` /
`updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL` on **every** table (repo-wide rule).

- `feature_toggles`: `id UUID` PK, `name VARCHAR(100)` unique, `percentage INT NOT
  NULL` with `CHECK (percentage BETWEEN 0 AND 100)`, timestamps.
- `feature_toggle_org_overrides`: `id UUID` PK, `toggle_id UUID` FK →
  `feature_toggles(id)` `ON DELETE CASCADE`, `organization_id UUID` FK →
  `organizations(id)`, `percentage INT NOT NULL` (same CHECK), unique
  `(toggle_id, organization_id)`, timestamps.

Integrity lives at the DB per ADR-0011; the features domain never imports
organizations code.

### Trace id (`TraceIdFilter`, in the existing `web` package)

Cross-cutting servlet filter, ordered early:

1. Take the trace id from the W3C `traceparent` header (trace-id field), else
   `X-Trace-Id`, else generate a random UUID.
2. Sanitize before any logging (existing `LogSanitizer` conventions).
3. Store in a request attribute and the MDC (trace id appears in logs for free).
4. Echo `X-Trace-Id` on the response.

A client (or a future internal hop) that carries the header back in shares the
original request's toggle decisions.

### Evaluation (`FeatureToggleService`)

`isEnabled(Feature f)` and `isEnabled(Feature f, UUID orgId)`:

1. **Cache lookup** by `(traceId, feature, orgId)` — hit returns the pinned decision.
2. **Miss:** effective percentage = the org's override row if `orgId` was given and
   one exists, else the global row. Override precedence is unconditional (org at 10%
   stays 10% even if global is 100). An `orgId` with no override — including an id
   that matches no organization — falls back to global; evaluation never throws.
3. `0` → `false`; `100` → `true`; otherwise coin flip: `true` iff a random double
   `< percentage / 100`. Randomness comes from `SecureRandom` behind an injectable
   seam (a `DoubleSupplier`), which makes tests deterministic and avoids the
   FindSecBugs PREDICTABLE_RANDOM finding without suppressions.
4. Cache the decision under the trace id and return it.

Called with no trace id in scope (startup, future background jobs): evaluate fresh,
skip the cache.

### Trace-decision cache

`TraceDecisionCache` interface (features domain) with a Caffeine-backed
implementation: `expireAfterWrite` TTL (default 10 minutes) and `maximumSize`
(default 10,000), both from a validated `@ConfigurationProperties` class
(`zarlania.features.cache.*`) that fails startup on non-positive values, like
`CorsProperties`. Bounding matters because the key is client-controllable
(unique-trace-id spray must evict, not grow). At these bounds the cache costs a few
MB against the 512 MB Render instance. Caffeine is a new (small, zero-transitive-dep)
library, recorded in the feature ADR; Render Key Value is the designated swap-in at
multi-instance time.

### Admin API (`/api/admin/feature-toggles`, features `controller` package)

| Method & path | Body | Effect |
| --- | --- | --- |
| `GET /api/admin/feature-toggles` | — | List all toggles: name, global percentage, org overrides |
| `GET /api/admin/feature-toggles/{name}` | — | One toggle, same shape |
| `PUT /api/admin/feature-toggles/{name}` | `{"percentage": n}` | Set global state (off=0, on=100, partial=1–99) |
| `PUT /api/admin/feature-toggles/{name}/organizations/{orgId}` | `{"percentage": n}` | Create/replace the org override |
| `DELETE /api/admin/feature-toggles/{name}/organizations/{orgId}` | — | Remove the override; org falls back to global |

The DTO carries the canonical name `FeatureToggle` (per CLAUDE.md); the JPA entity is
`FeatureToggleEntity`. Endpoint shapes are documented by the generated OpenAPI, not
duplicated in reference docs.

### OpenAPI visibility

The root `/v3/api-docs` document keeps its URL (ADR-0003) and is filtered by a
springdoc `OpenApiCustomizer` that strips every `/api/admin/**` path. Swagger UI
continues to read the root document, so no group selector appears in production.

A machine-readable admin document exists only when `zarlania.docs.expose-admin=true`
(default `false`): the property conditionally registers springdoc `GroupedOpenApi`
beans — `admin` (`/v3/api-docs/admin`, admin paths only) and `public`
(`/v3/api-docs/public`, admin excluded). In production the property stays off and no
admin contract is published anywhere; developers enable it locally to browse the
admin docs. Rationale, verified empirically in this repo: springdoc auto-lists every
registered group in `/v3/api-docs/swagger-config` and has no supported way to hide a
group from the Swagger UI selector (springdoc-openapi issue #2023), so an always-on
but unlisted admin group is not achievable — conditional registration is the
standard workaround. A plain (non-global) `OpenApiCustomizer` was verified to filter
only the root document, leaving group documents untouched.

This also keeps toggle names (unreleased-feature roadmap) out of the public document.
Recorded as a repo-wide convention in its own ADR (refining, not contradicting,
ADR-0003: the admin surface stays machine-documentable, just not published by
default).

### Error handling

- Unknown toggle name or organization → 404 via domain exceptions
  (`FeatureToggleNotFoundException` in `features/exception`; organization existence
  checked for the override endpoints), rendered as RFC 7807 `ProblemDetail` by the
  existing global handler pattern.
- Percentage outside 0–100 → 400 via bean validation, backstopped by the DB CHECK.
- Cache/config misconfiguration → startup failure (fail fast).

## Accompanying documents (same change)

1. **ADR — feature-toggle service:** enum registry, percentage state model,
   trace-pinned decisions, Caffeine in-process cache now with Render Key Value as the
   recorded multi-instance successor, Caffeine dependency adoption.
2. **ADR — admin API surface convention:** `/api/admin/**` is excluded from the
   public OpenAPI group and Swagger UI pending real authentication.
3. **Reference doc** (via `./scripts/ref new`): how feature toggles behave and how to
   add/use/remove one (enum → deploy → flip via API → delete constant). Behavior and
   rules only; endpoint contracts stay in OpenAPI.

## Testing

Per the repo's test layering (controllers = e2e, services = integration + unit,
repositories = integration-only):

- **Controller (e2e):** list/get/put/delete flows through the full stack; validation
  400s (percentage −1, 101, missing); 404s for unknown toggle and unknown org;
  problem+json shape; public `/v3/api-docs` contains no `/api/admin/**` path;
  `/v3/api-docs/admin` is 404 by default and serves the admin paths when
  `zarlania.docs.expose-admin=true`.
- **Service (unit):** override-beats-global precedence; 0 and 100 never draw
  randomness; partial true/false branches via the injected `DoubleSupplier`; trace
  memoization returns the first answer even after state changes; no-trace-context
  evaluation skips the cache.
- **Service (integration):** startup sync inserts missing rows at 0 and deletes
  orphans (cascading overrides); override CRUD round-trips against the real schema.
- **Filter (e2e):** inbound `traceparent` honored; `X-Trace-Id` fallback; id
  generated when absent; echoed on the response.
- Coverage ≥ 80% is enforced by the existing build gate.
