---
id: '0016'
name: Gate every behavior change behind a feature toggle
description: Mandates a feature toggle for any new or changed user-observable behavior,
  reusing an existing toggle before adding a constant, and removing the constant once
  the feature is permanent.
status: accepted
date_proposed: '2026-07-10'
date_accepted: '2026-07-10'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- governance
- process
---
# ADR-0016: Gate every behavior change behind a feature toggle

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0016 |
| Name | Gate every behavior change behind a feature toggle |
| Description | Mandates a feature toggle for any new or changed user-observable behavior, reusing an existing toggle before adding a constant, and removing the constant once the feature is permanent. |
| Status | accepted |
| Date proposed | 2026-07-10 |
| Date accepted | 2026-07-10 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | governance, process |
<!-- adr-meta:end -->

## Context and Problem Statement

Every merge to `master` deploys to production. ADR-0014 introduced the `features` domain — an
enum-backed toggle registry with percentage state and trace-pinned evaluation — so behavior can
now ship dark and be flipped, ramped, or killed without a redeploy. That capability only reduces
risk if it is actually used: without a rule requiring it, a contributor can ship new or changed
user-observable behavior straight to every user with no way to turn it off short of a rollback
or a fresh deploy. We need a standing policy on when a toggle is required, how to avoid the
registry growing an unbounded, overlapping set of constants, and when a toggle's job is done.

## Decision Drivers

- A regression in newly shipped behavior must be killable in production without a rollback or a
  redeploy, which only holds if the behavior was gated in the first place.
- The `Feature` registry (ADR-0014) must not accumulate near-duplicate constants for the same
  underlying change — each constant carries a `description()` precisely so an existing toggle
  can be found and reused before a new one is added.
- Gating cannot be asked of changes that carry no user-observable risk — pure refactors,
  documentation, and build/tooling work have nothing to flip off, and requiring a toggle for
  them would be pure overhead with no safety benefit.
- The registry must not grow unbounded in the other direction either: a toggle that has done
  its job and become permanent must be removed, not left in place indefinitely.

## Considered Options

- Mandate a toggle for every new or changed user-observable behavior, with an explicit
  reuse-first check against existing `Feature` descriptions and a removal obligation once the
  feature is permanent (chosen).
- Leave toggle usage to case-by-case judgment on each PR, with no standing rule.
- Require a toggle for every code change, including pure refactors and non-behavioral work.

## Decision Outcome

Chosen option: **mandate a toggle for user-observable behavior changes, reuse before adding a
new constant, and remove the constant once the feature is permanent**, because it makes the
kill-switch capability ADR-0014 built actually load-bearing, without taxing changes that carry
no behavioral risk or leaving the registry to grow without bound in either direction.

Any change that introduces or alters user-observable behavior must be gated behind a feature
toggle. Before adding a new `Feature` constant, check whether an existing toggle already covers
the change — its `description()` exists precisely to make this check possible — and reuse it
when it fits; add a new constant only when none does.

Once a gated feature is permanent (the toggle will never again be turned off), its `Feature`
constant must be removed. This is not optional cleanup: it is how the registry stays a small,
reviewable set of live toggles rather than an accumulating log of every feature ever shipped.
The mechanics of removal — the synchronizer deleting the toggle's row and cascading its
organization overrides on the next deploy — are described in ADR-0014 and reference doc 000003;
this ADR adds only the obligation to do it, not the mechanism.

The following carry no user-observable behavior and require no toggle:

- Pure refactors and other changes with no behavior change.
- Documentation and ADRs.
- Build, CI, and tooling changes.
- The feature-toggle machinery itself (`com.zarlania.api.features` and its admin surface).
- Additive database migrations that merely scaffold a feature still gated behind a toggle (the
  schema exists before the behavior is reachable).

### Consequences

- Good: production behavior added or changed in a merge can be turned off without a rollback or
  a redeploy, closing the gap ADR-0014's mechanism opened but did not by itself guarantee is
  used.
- Good: the reuse-first check against `description()` keeps the `Feature` registry from
  accumulating redundant constants for overlapping changes.
- Good: the removal obligation keeps the registry bounded — it reflects toggles still in play,
  not a permanent audit log of every feature ever gated.
- Good: the carve-outs mean non-behavioral work (refactors, docs, build/tooling) is not taxed
  with a toggle it has no use for.
- Bad: this is a process rule enforced by review, not by tooling — nothing mechanically stops a
  PR from shipping ungated behavior or leaving a stale constant behind; it depends on reviewers
  applying it consistently.
- Bad: judgment calls remain at the margins (e.g., whether a given change is "user-observable"
  enough to require gating) and this ADR does not attempt to enumerate every case.

## Links

- ADR-0014: Feature toggles: code registry, percentage state, trace-pinned evaluation
  (mechanics this ADR relies on but does not restate)
