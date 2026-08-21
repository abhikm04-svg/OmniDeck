# OmniDeck

A single Android host app ("Shell") that other teams plug modules into — on-demand feature
delivery, a shared platform SDK, and (later) independently-released satellite apps.

- **`architecture.md`** — the baseline architecture (v1.0): system design, ADRs, the module
  contract, security model, quality attributes.
- **`implementation_plan.md`** — the phased delivery plan, ticket-by-ticket, with exit gates.
- **`CONTRIBUTING.md`** — branching, commit conventions, PR requirements, ADR process.
- **`docs/adr/`** — Architecture Decision Records.

## Building

```powershell
./gradlew :app:assembleDebug
```

Requires JDK 21 and Android SDK Platform 36 (see `implementation_plan.md` §4 for the full
toolchain bootstrap).

## Repository layout

```
app/                   the Shell (host app)
platform/              the SDK, kernel services, design system, test fakes
modules/               feature modules (auto-discovered — see settings.gradle.kts)
tools/lint-rules/      custom Android Lint checks enforcing platform guardrails
build-logic/           Gradle convention plugins
docs/adr/              Architecture Decision Records
```
