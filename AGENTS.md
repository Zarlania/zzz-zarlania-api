# AGENTS.md — Zarlania API

Shared instructions for AI agents working in this repo (Codex, CodeRabbit, and the
Claude CLI). For implementation conventions, **CLAUDE.md and the accepted ADRs are the
authoritative, binding sources** — this file governs how changes are *reviewed*.

## Review posture: assume the author missed something

Code here is **written by AI and reviewed by AI**. Treat every change as the work of an
author who may not have had the whole codebase in context. Assume — until you verify
otherwise — that the change may:

- have skipped or contradicted an accepted ADR or a CLAUDE.md convention,
- have missed an existing utility, pattern, or abstraction it should have reused (DRY),
- not have accounted for an edge case, boundary, or failure mode,
- have introduced a subtler issue the author didn't think to check.

You are the **independent second pair of eyes**. Re-derive whether the change is correct
and consistent with the codebase yourself — do not assume the author already checked.
**When in doubt, leave the comment.** A false positive is cheaper than a missed defect;
we will tune the granularity down in later passes. This is a live, public service —
merges to `master` deploy to production.

## Be thorough — scrutinize every dimension

Flag concerns across all of these; don't stop at the first category:

- **Correctness & bugs** — edge cases, null/empty inputs, error handling, off-by-one,
  concurrency, incorrect assumptions about inputs or state.
- **Security** — validate external input at the boundary; injection; authz/authn gaps;
  never-committed secrets; don't log PII (log surrogate IDs; sanitize untrusted values).
- **Maintainability** — enforce DRY & SOLID and the feature-first structure in CLAUDE.md;
  flag duplication, misplaced code, poor names, mutable state, field injection, and
  validation that isn't fail-fast at the boundary.
- **Scalability & performance** — N+1 queries, unbounded results, resource leaks,
  needlessly expensive or blocking work on hot paths.
- **Tests** — do they prove *observable behavior* through the public surface, or just
  exercise mocks/internals? The ≥ 80% coverage gate measures quantity, not meaning —
  flag meaningful gaps (untested edge cases, invariants) even when coverage is green.
- **Conventions & ADRs** — a change may not contradict an accepted ADR without a
  superseding ADR; flag contradictions. If a change alters documented behavior, its
  reference doc must be updated in the same change — flag drift.
- **Gate integrity** — flag any newly added `@SuppressWarnings`, Checkstyle/SpotBugs
  excludes, skipped tests, or lowered coverage used to go green instead of fixing the
  root cause.
- **Release discipline** — every merge ships; flag a missing/mismatched `pom.xml`
  version bump vs. the PR's `release:<kind>` label.

## Consult the authoritative sources

- **CLAUDE.md** and **accepted ADRs** are law. Query ADRs with `./scripts/adr list|find
  "<q>"|show <id>` and reference docs with `./scripts/ref list|find "<q>"|show <id>` —
  don't hand-scan `docs/`.
- Judge a change against the **ADRs and the current code**, not against any merged
  change's spec/plan — `docs/superpowers/` is frozen historical record.
- Ignore `docs/ai-prompts/` entirely; it is a private scratchpad, not documentation.

## Calibrating granularity

Err toward more comments, not fewer — surface anything questionable so a second set of
eyes can confirm it. Keep severity legible: prefix minor style/preference remarks as
**nit:** so they're easy to triage separately from correctness and security findings.
