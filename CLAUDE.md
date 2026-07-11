# CLAUDE.md — Zarlania API

AI entry point for this repo. **This is a live, public service: merges to `master`
deploy to production at <https://api.zarlania.com>.** Work carefully.

## Non-negotiables

- **Never commit secrets.** No credentials, tokens, or keys in any commit. Secrets live
  only in Render environment variables and local `.env` (git-ignored).
- **ADRs are law.** Code may not contradict an accepted ADR without a new ADR that
  supersedes it. See `docs/adrs/0001-record-architecture-decisions.md`.
- **Every change ties to a GitHub issue.** Branch `type/<issue#>-slug`; PR title
  references `#<issue>`.
- **Every behavior change ships behind a feature toggle.** New or changed user-observable
  behavior must be gated (see ADR-0016). Reuse an existing `Feature` toggle when one covers
  the change — check `Feature` descriptions first — and add a new constant only when none
  fits. Remove a toggle's constant once its feature is permanent. Carve-outs: pure refactors,
  docs/ADRs, build/CI/tooling, the toggle machinery itself, and additive scaffolding
  migrations.

## Stack

Java 25 / Spring Boot 4.1.x / Maven (wrapper `./mvnw`) / multi-stage Temurin Docker.
For the version-adoption rationale see ADR-0006 (`./scripts/adr show 0006`).

## Code quality & structure

Keep this codebase clean as it grows from scaffolding into real features. These are
judgment-level rules; formatting, style, and the ≥ 80% coverage floor are already enforced by
the build — don't restate them, and never silence them (see below).

- Practice **DRY** and **SOLID**. Prefer small, single-responsibility units; refactor
  duplication instead of copying it.
- **Tests prove behavior.** Write the test first; assert observable behavior through the
  public surface, not mock interactions or internals. The ≥ 80% coverage gate measures
  quantity — it does not prove a test is meaningful.
- **Fail fast; prefer immutability.** Validate configuration and external input at the
  boundary and throw early (as `CorsProperties` does). Prefer immutable value types and
  constructor injection over field injection / mutable state.
- **Don't silence the gates to go green.** Fix the root cause rather than adding
  `@SuppressWarnings`, Checkstyle/SpotBugs excludes, skipped tests, or a lowered coverage
  threshold. A genuine tool bug gets a documented exception — an issue plus a compensating
  test — e.g. the FindSecBugs CORS detector NPE on `WebConfig` (issue #23).
- **Keep dependencies lean.** Prefer the framework/stdlib before reaching for a new library.
  A new major dependency (or other architecturally significant choice) is an ADR, not a
  casual add.
- **Organize by feature/domain first, then by layer within the domain.** Group related code
  together. A domain like *users* owns its package (`com.zarlania.api.users`) and splits its
  layers into sub-packages — `controller`, `service`, `repository`, `entity`, `dto`,
  `exception` (singular) — rather than a repo-wide flat `controllers/` listing every
  controller and a flat `services/` listing every service. Cross-cutting / infrastructure
  code lives in its own package (e.g. config under a `config` package), not scattered at the
  application root. The **DTO carries the canonical domain name** (e.g. `User`) because DTOs
  cross boundaries and are referenced far more than entities; the JPA entity is suffixed
  (`UserEntity`).
- This is intent, not a rigid tree: use judgment, follow the established structure once it
  exists, and don't over-engineer (YAGNI).

## Releases (every merge ships)

Every merge to `master` cuts exactly one SemVer release. The version lives in `pom.xml`
`<version>` and is bumped **inside the PR** (never after merge — that would double-deploy).

When opening a PR:

1. Choose the bump from the change: breaking = `major`, feature = `minor`, fix/chore =
   `patch`. Apply the matching `release:<kind>` label (no label = `patch`).
2. Run `./scripts/bump-version bump <kind>` to set `pom.xml` to the next version.
3. CI's "Release version bump" check verifies the pom matches the label vs. the latest
   release tag; on merge, `release.yml` tags `v<version>` and cuts the GitHub Release.

## Working with ADRs (save tokens)

To find an ADR or check whether a decision exists, **use the `adr-search` skill / the
CLI — do not scan `docs/adrs/` by hand**:

- `./scripts/adr list` / `./scripts/adr find "<query>"` / `./scripts/adr show <id>`

To create one, use the `adr-create` skill. For tags, use `adr-tags`. Run
`./scripts/adr check` after any ADR change.

## Working with reference docs

`docs/reference/` holds **living explanatory docs** — how the system behaves — distinct from
ADRs (immutable decisions) and `docs/superpowers/` (implementation-time specs/plans). They
are editable and updated in place; see ADR-0013.

Use the CLI — **do not hand-scan `docs/reference/`**:

- `./scripts/ref list` / `./scripts/ref find "<query>"` / `./scripts/ref show <id>`
- `./scripts/ref new --title "<title>" --tags <t1,t2>` to author one; `./scripts/ref check`
  after any change.

Decide when to consult or author a reference doc as the situation warrants. No content lives
in CLAUDE.md.

When a change alters documented behavior, update the relevant reference doc (or author a new
one) **as part of that change** — reference docs are living and must not drift from the code.

Reference docs document **behavior and rules**, not API endpoint contracts. The public
springdoc OpenAPI (`/v3/api-docs`, see ADR-0003) is the source of truth for endpoints —
do not duplicate endpoint shapes, request/response bodies, or status codes in a reference doc.

## Specs and plans are implementation-time only — not law

`docs/superpowers/` holds specs and plans. They guide a change **while it is being built**:
during implementation, and during the spec review of that same change, follow the spec/plan
relevant to the work in progress so nothing is missed. This is the normal flow
(brainstorm → spec → plan → implement → review the work against that plan's spec) and this
rule does not change it.

Once a change is merged to `master`, its spec and plan become **historical record only** —
not law, and not a standard to code against. The authoritative sources are the **ADRs and
the actual code**. Concretely:

- When implementing or reviewing change B, do **not** flag it for diverging from an earlier
  change A's spec or plan — those are frozen history. Judge B against the ADRs, the code,
  and B's own spec/plan.
- Dismiss any review comment that asks to edit a spec or plan file to match the code. Once
  merged they are not living documents. If a decision actually changed, that is a **new
  ADR**, not a spec edit.

## AI prompt scratch files (`docs/ai-prompts/`)

`docs/ai-prompts/` is the user's private scratchpad: `.md` files the **user** writes to
draft prompts outside the terminal, then hands to the Claude CLI to read on demand. The
directory is **git-ignored** (see `.gitignore`) and exempt from all linters/hooks (see
`.pre-commit-config.yaml`) — it never gets committed.

Treat these files as **input you read only when the user explicitly points you at one**.
They are **not** documentation, decision records, specs, or law — they say nothing about how
the code should function. Concretely:

- **Never** consult `docs/ai-prompts/` when investigating the codebase, answering questions
  about how things work, or reviewing/implementing changes. The authoritative sources remain
  the ADRs and the code.
- **Never** reference these files from code, ADRs, README, or any committed file, and never
  cite them as a reason for a change.
- Read one only when the user names it (e.g. "use `docs/ai-prompts/foo.md`"); otherwise
  ignore the directory entirely.
