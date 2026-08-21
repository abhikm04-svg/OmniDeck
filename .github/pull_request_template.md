## What & why

<!-- The problem this solves, not just what changed. Link the ticket (OD-###) if one exists. -->

## Ticket

OD-###

## Checklist

- [ ] `./gradlew qualityCheck` passes locally (Detekt, Spotless, `checkArchitecture`)
- [ ] `./gradlew test` passes locally
- [ ] `./gradlew apiCheck` passes, and the `.api` dump is included in this PR, if
      `platform/omnideck-sdk*` changed
- [ ] New/changed behaviour has tests
- [ ] Telemetry, error/empty/loading states, and accessibility considered where user-visible
- [ ] Docs updated (module guide / ADR / runbook) if this changes a guardrail or contract
- [ ] No hard-coded user-facing strings

## Screenshots / recordings

<!-- For UI changes. -->

## Architecture impact

<!-- Does this touch a Phase 0 guardrail (dependency rules, DI, storage isolation, signing)?
     If yes, link the ADR. If a new one is needed, link the draft. -->
