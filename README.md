# OmniDeck

A single Android host app ("Shell") that other teams plug feature modules into — on-demand
delivery through Play Feature Delivery, a shared platform SDK, and (later) independently
released satellite apps.

The goal is that adding a module requires **zero changes to Shell source**: drop a directory
under `modules/`, and settings/convention plugins discover and wire it automatically. That
property is enforced mechanically, not by convention — see [Guardrails](#guardrails).

## Status

Walking skeleton. The Shell launches, discovers whatever is under `modules/`, and renders it:
the first feature module (Notes) is offline-first, persists to its own namespaced database and
queues its changes for a sync service that does not exist yet, which it says so rather than
pretending otherwise. Settings and a Privacy Centre are in place; dynamic delivery and the
Catalog are not.

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
| `./gradlew newModule -Pid=<id>` | Scaffolds a compliant feature module |

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
benchmark/             macrobenchmarks and Baseline Profile generation (device only)
tools/lint-rules/      custom Lint checks
tools/module-processor/ KSP: validates each entry point, generates the module registry
tools/module-template/ source for `./gradlew newModule`
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
- **A module's entry point is checked at compile time.** Wrong name, wrong visibility, a
  constructor argument or a missing interface fails the module's own build instead of failing to
  load on a device in release only.
- **No Shell source may name a module.** A unit test scans `app/` and `platform/kernel/`
  production sources for any module id and fails if it finds one, which is the "adding a module
  changes no Shell file" property checked mechanically rather than by reading a diff.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for branching, commit conventions and PR requirements.

## A note on references

Source comments cite an internal architecture baseline and delivery plan by section
(`architecture.md §5.1`, `implementation_plan.md §17`, `ADR-004`, ticket ids like `OD-101`).
Those documents are not published here. The comments are still useful as intent — they explain
*why* a given constraint exists — but the documents themselves live in internal storage.
