# OmniDeck

A single Android host app ("Shell") that other teams plug feature modules into — on-demand
delivery through Play Feature Delivery, a shared platform SDK, and (later) independently
released satellite apps.

The goal is that adding a module requires **zero changes to Shell source**: drop a directory
under `modules/`, and settings/convention plugins discover and wire it automatically. That
property is enforced mechanically, not by convention — see [Guardrails](#guardrails).

## Status

Early. The platform skeleton, SDK contract and kernel services exist; the first real feature
module does not yet. The Shell currently renders an empty state.

## Building

```bash
./gradlew :app:assembleDebug
```

Requires **JDK 21** and **Android SDK platform 36**. `minSdk` is 26.

Useful targets:

| Command | What it does |
|---|---|
| `./gradlew build` | Everything: compile, test, static analysis, lint |
| `./gradlew qualityCheck` | Detekt, Spotless and the architecture fitness function |
| `./gradlew apiCheck` | Fails if the SDK's public ABI drifts from its checked-in dump |
| `./gradlew checkArchitecture` | Layering rules only |

## Repository layout

```
app/                   the Shell (host app)
platform/
  omnideck-sdk-core/   the contract, pure Kotlin — no Android
  omnideck-sdk/        the contract, Android half — all a module depends on
  kernel/              capability implementations, module loader, lifecycle
  design-system/       tokens and shared components
  testing/             in-memory fakes so modules test with no Shell
modules/               feature modules (auto-discovered)
tools/lint-rules/      custom Lint checks
build-logic/           Gradle convention plugins
```

## Guardrails

These are build failures, not review comments. A rule that only lives in a document erodes
within a sprint.

- **Modules are islands.** A module may depend on the SDK, the design system and
  `platform:core` — never on another module, the kernel, or `:app`. Violations fail
  `checkArchitecture` with an explanation.
- **The SDK core stays Android-free**, so it can be shared with backend services and moved to
  Kotlin Multiplatform later.
- **The SDK's public ABI is checked in** as an `.api` dump. Changing it without regenerating
  the dump in the same commit fails `apiCheck`.
- **No raw `android.util.Log`** — logging goes through `TelemetryService` so it carries module
  attribution and PII redaction. Enforced by a custom Lint rule.
- **No direct permission calls** — modules request through `PermissionBroker`, which centralises
  rationale, denial handling and audit events. Also Lint-enforced.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for branching, commit conventions and PR requirements.

## A note on references

Source comments cite an internal architecture baseline and delivery plan by section
(`architecture.md §5.1`, `implementation_plan.md §17`, `ADR-004`, ticket ids like `OD-101`).
Those documents are not published here. The comments are still useful as intent — they explain
*why* a given constraint exists — but the documents themselves live in internal storage.
