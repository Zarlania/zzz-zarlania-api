# Zarlania API

Backend service for Zarlania, live at <https://api.zarlania.com>. Java 25, Spring Boot,
and Maven, packaged as a container and deployed on Render.

This README is for working in the repo after cloning it: how to set it up, the commands
you'll use day to day, and how the codebase is organized. The project's binding rules live
in its ADRs (below), not here.

## The first thing to know

**ADRs are law.** Architecturally significant decisions are recorded as Architecture
Decision Records under `docs/adrs/`. Code may not contradict an accepted ADR — changing a
decision means writing a new ADR that supersedes the old one. They're Markdown files you can
read directly, and the `./scripts/adr` CLI helps you list, search, and validate them.
`./scripts/adr show 0001` walks through the ADR process itself.

Two other standing rules: every change ties to a GitHub issue, and secrets never get
committed (enforced by gitleaks in pre-commit and CI).

## Prerequisites

- **JDK 25** (e.g. Eclipse Temurin 25) — to build and run the app.
- **Docker** — for the local container run.
- **Python 3** — for the dev tooling (pre-commit, the ADR CLI).

## First-time setup

```bash
./scripts/setup-dev      # creates .venv, installs dev deps, installs the pre-commit hooks
```

## Everyday commands

```bash
./scripts/check                 # fast local checks (mirror of pre-commit)
./scripts/check --full          # the above, plus a full ./mvnw verify
./mvnw verify                   # full quality gate: format, lint, static analysis, security, coverage, tests
./mvnw spring-boot:run          # run the app locally on :8080
docker compose up --build       # run the app in its container locally on :8080
./scripts/adr <cmd>             # work with ADRs (see below)
./scripts/bump-version bump <patch|minor|major>   # set the release version in pom.xml
```

## Running locally

`./mvnw spring-boot:run` and `docker compose up --build` both serve on
<http://localhost:8080>.

No environment variables are required to boot. Local config is env-driven: `docker compose`
sets `ZARLANIA_CORS_ALLOWED_ORIGINS` to localhost origins so a local frontend can call the
API, whereas `./mvnw spring-boot:run` uses the production-origin defaults. Override
`ZARLANIA_CORS_ALLOWED_ORIGINS` (and `PORT`) via your environment as needed.

## Finding the API

The HTTP API describes itself — consult the live OpenAPI spec rather than a list maintained
here:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

Useful operational URLs that are *not* part of the API docs:

- `/actuator/health` — aggregate health (Render's health check), plus
  `/actuator/health/liveness` and `/actuator/health/readiness`
- `/actuator/info` — build info and the running version

(Only `health` and `info` are exposed over HTTP, and health details are never public.)

## How the repo is organized

- `src/` — the Spring Boot application.
- `docs/adrs/` — Architecture Decision Records: the binding decisions. Use `./scripts/adr`.
- `docs/reference/` — living explanatory docs (how the system behaves). Use `./scripts/ref`.
- `docs/superpowers/` — implementation specs and plans (see note below).
- `docs/ai-prompts/` — git-ignored personal scratchpad for AI prompts (see note below).
- `scripts/` — dev, ADR, reference, and release tooling (`setup-dev`, `check`, `adr`, `ref`, `bump-version`).
- `.github/` — CI workflows, issue/PR templates, CODEOWNERS.
- `pom.xml`, `Dockerfile`, `docker-compose.yml`, `render.yaml` — build, container, and deploy config.

## Working with ADRs

```bash
./scripts/adr list             # list ADRs
./scripts/adr find "<query>"   # search ADRs
./scripts/adr show <id>        # show one ADR (e.g. ./scripts/adr show 0001)
./scripts/adr check            # validate ADRs (no drift, valid tags, fresh index)
```

Recording a new decision is itself an ADR change — `./scripts/adr new` (or the `adr-create`
skill) scaffolds one from the template.

## Working with reference docs

```bash
./scripts/ref list             # list reference docs
./scripts/ref find "<query>"   # search reference docs
./scripts/ref show <id>        # show one doc (e.g. ./scripts/ref show 000001)
./scripts/ref check            # validate reference docs (no drift, valid tags, fresh index)
```

Reference docs are **living explanations** of how the system behaves — edited in place,
unlike the immutable ADRs. Author one with `./scripts/ref new --title "<title>" --tags
<tag1,tag2>`. See `docs/reference/` and ADR-0013.

## Making a change

1. Start from a GitHub issue.
2. Branch `type/<issue#>-slug` (e.g. `feat/42-users-endpoint`).
3. Open a PR whose title references the issue (e.g. `feat: add users endpoint (#42)`).
4. Pick the release bump and apply the matching `release:major|minor|patch` label (no label
   = patch), then run `./scripts/bump-version bump <kind>` so `pom.xml` is bumped inside the
   PR. Every merge to `master` cuts one SemVer release and deploys once
   (`./scripts/adr find release` for the rationale).
5. CI must be green before merge.
6. Gate new or changed behavior behind a feature toggle (ADR-0016): reuse an existing
   `Feature` if one fits, otherwise add a constant. Non-behavioral changes (refactors,
   docs, tooling) are exempt.

## About `docs/superpowers/`

`docs/superpowers/` holds the specs and plans that guided each piece of implementation. They
are **point-in-time** artifacts — written before or while a change was built, and they may
intentionally diverge from what shipped once the implementer learned more. They are not a
coding standard and are not a reason for code to look a certain way: the authoritative
sources are the ADRs and the code itself. Treat them as historical context, not rules.

## About `docs/reference/`

`docs/reference/` holds **living reference docs** — numbered, editable explanations of how
the system actually behaves (e.g. the user/organization association rules). Unlike the ADRs,
which are immutable decisions, reference docs are updated in place as the system evolves;
unlike `docs/superpowers/`, they are not point-in-time implementation artifacts. They explain
behaviour, they do not decide it — the binding decisions remain the ADRs. Manage them with
`./scripts/ref` (see ADR-0013).

## About `docs/ai-prompts/`

`docs/ai-prompts/` is a personal scratchpad for drafting AI prompts outside the terminal and
feeding them to the Claude CLI on demand. It is **git-ignored** and exempt from all linters,
so nothing in it is ever committed. These files are not documentation, decision records, or
specs — nothing in the repo references them, and they say nothing about how the code works.
