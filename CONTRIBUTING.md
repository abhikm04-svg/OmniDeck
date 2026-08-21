# Contributing to OmniDeck

Companion to `architecture.md` and `implementation_plan.md`. Read those first — this file only
covers day-to-day mechanics.

## Branching & merges

Trunk-based development (implementation_plan.md §2): short-lived branches off `main`, merged via
PR, behind a feature flag if user-visible. No long-lived feature branches.

- Branch names: `<ticket>-short-description`, e.g. `od-009-lint-rules`.
- One PR = one reviewable change. Split unrelated work into separate PRs.
- Squash-merge to `main` so the commit history reads as one entry per PR.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/), referencing the plan's ticket ID
where one exists:

```
<type>(<scope>): <subject> [OD-###]

<body — the *why*, not the *what*>
```

**Types:** `feat` `fix` `refactor` `docs` `test` `build` `ci` `chore` `perf`

**Scope** is the top-level area touched: `sdk`, `kernel`, `shell`, `design-system`, `build-logic`,
a module id, etc.

```
feat(kernel): add PermissionBroker rationale UI [OD-127]

Denial handling was previously a dead end — this adds the settings deep link
required by architecture.md §12 before any module can request a runtime permission.
```

A commit that changes `platform/omnideck-sdk`'s public API must say so in the body and include
the regenerated `.api` dump in the same commit (ADR-004) — never a follow-up commit.

## Pull requests

Use the PR template. Every PR must pass, before requesting review:

- `./gradlew qualityCheck` (Detekt, Spotless/ktlint, `checkArchitecture` fitness function)
- `./gradlew test`
- `./gradlew apiCheck` if `platform/omnideck-sdk*` changed

CI re-runs all of this; it is not optional pre-work, it is a faster local feedback loop.

## Architecture changes

A change to a Phase 0–established guardrail (dependency rules, DI approach, storage isolation,
signing, etc.) needs an ADR, not just a PR description. Copy `docs/adr/template.md`, fill it in,
get it reviewed alongside the code change. See the RACI table in implementation_plan.md §3 for who
signs off on what.

## New modules

Scaffold with `./gradlew newModule -Pid=<moduleId>` (from Phase 2 onward, OD-211). A module must
build and test standalone against `:platform:testing` fakes — if it needs to reach into `:app` or
another module to work, the contract is missing something; raise that as an SDK issue rather than
adding the dependency.
