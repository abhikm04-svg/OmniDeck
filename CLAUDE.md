# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OmniDeck is an Android "super-app": one host app (`:app`, the **Shell**) that loads feature
modules delivered via Play Feature Delivery, plus the SDK contract and kernel services behind
them. The defining property is **G1 — adding a module requires zero changes to Shell source**:
`settings.gradle.kts` discovers directories under `modules/` and the application convention
plugin wires them in. `:app/build.gradle.kts` names no module, and must never start to.

Work is executed phase by phase against two internal specs at the repo root: `architecture.md`
(baseline, ADRs, contract, security model) and `implementation_plan.md` (9 phases, `OD-###`
tickets, hard exit gates). Source comments cite them by section (`architecture.md §6.3`,
`ADR-004`, `OD-129`) — read the cited section before changing the code around it.
`implementation_plan.md` §16 (Small-Team / Solo Variant) is the track actually being followed.

## Commands

Requires **JDK 21** and Android SDK platform 36 (`compileSdk` 36, `minSdk` 26).

```bash
./gradlew :app:assembleDebug            # build the Shell
./gradlew build                         # compile + test + static analysis + lint
./gradlew qualityCheck                  # Detekt + Spotless + checkArchitecture (no test compile)
./gradlew apiCheck                      # SDK ABI vs the checked-in .api dumps
./gradlew apiDump                       # regenerate them after an intentional API change
./gradlew checkArchitecture             # layering fitness function only
./gradlew spotlessApply                 # fix formatting
./gradlew koverVerify                   # coverage floors
./gradlew lint                          # Android Lint incl. the custom OmniDeck rules
```

Single test — Android modules use the variant task; `:platform:omnideck-sdk-core` and
`:tools:lint-rules` are plain JVM modules and use `test`:

```bash
./gradlew :platform:kernel:testDebugUnitTest --tests "*RouterImplTest"
./gradlew :platform:core:testDebugUnitTest --tests "com.omnideck.core.OutcomeTest"
./gradlew :tools:lint-rules:test --tests "*RawLogDetectorTest"
```

Instrumented tests need a device/emulator — `SecureStoreImpl` is only covered there, because the
Android Keystore has no JVM or Robolectric implementation:

```bash
./gradlew :platform:kernel:connectedDebugAndroidTest
```

On Windows, `clean` fails with "Unable to delete directory" while the daemon and Lint hold jars
open — run `./gradlew --stop` first.

## Guardrails (these fail the build, not review)

- **`checkArchitecture`** (`QualityConventionPlugin.kt`) reads each project's Gradle dependency
  graph and enforces `architecture.md` §5.1: modules may not depend on other modules, on
  `:platform:kernel`, or on `:app`; nothing may depend on `:app`; `:platform:omnideck-sdk` may
  depend only on sdk-core and `:platform:core`.
- **sdk-core purity** — `:platform:omnideck-sdk-core` is checked by external *coordinate* too:
  no `androidx`, `com.android`, Dagger, Compose, Coil, OkHttp or Retrofit. It stays pure Kotlin
  so the backend can share its manifest types and a KMP move stays a move. The Android half
  (`:platform:omnideck-sdk`) legitimately carries Compose/Room/OkHttp/WorkManager.
- **`apiCheck`** — a change to either SDK's public ABI must include the regenerated `api/*.api`
  dump **in the same commit** (ADR-004), and say so in the commit body.
- **Custom Lint rules** (`tools/lint-rules/`) — `OmniDeckRawLog` bans `android.util.Log`
  (logging goes through `TelemetryService` for module attribution and PII redaction);
  `OmniDeckRawPermission` bans direct permission calls (go through `PermissionBroker`).
- **Coverage floors** — 80% for `:platform:*`, 70% for `:modules:*` (`gradle.properties`). A
  project with no `src/test` or `src/androidTest` warns instead of failing, and inherits the
  floor the moment a test source set appears. Exclusions are narrow, live in the root
  `gradle.properties` keyed by project path (`omnideck.coverage.excludeClasses.platform.kernel`),
  and each is documented next to its reason there. They are *not* declared in the project's own
  build file: both shapes of that tried before failed silently — a `kover { }` block is honoured
  by the report tasks and ignored by the gate, and a project extension read in `afterEvaluate`
  resolved on a developer machine but came back empty on CI for one project and not another.
  A mistyped key is fail-safe (the lines return to the denominator and the gate goes red).
- **A green `koverVerify` proves nothing unless it actually ran.** `koverCachedVerify` reports
  `UP-TO-DATE` and reuses a previous verdict even after the exclusions change, because the
  filter configuration is not one of its inputs. That produced two false greens here. Confirm a
  coverage claim with `./gradlew :platform:kernel:koverXmlReport :platform:kernel:koverCachedVerify --rerun`
  and read the counter out of `report.xml`; never trust an up-to-date pass.
- **Warnings are errors** for Kotlin/Java everywhere; Android Lint runs `warningsAsErrors` with
  `checkDependencies`. Escape hatch for local iteration only: `-Pomnideck.warningsAsErrors=false`.
- **Configuration cache is on with `problems=fail`** — Gradle code that reads project state at
  execution time breaks the build. Use task properties and task *paths*, as the existing
  convention plugins do.

Changing a guardrail needs an ADR (`docs/adr/`), not a PR description.

## Architecture

### Layering

```
:app  (Shell)  ──> :platform:kernel ──api──> :platform:omnideck-sdk ──> :platform:omnideck-sdk-core
                                                     ^                          (pure Kotlin)
modules/<id>  ───────────────────────────────────────┘  + :platform:design-system, :platform:core
                                             (tests only: :platform:testing)
```

A module depends on the SDK, the design system and `:platform:core` — never on the kernel,
another module, or the Shell.

### The module contract

`OmniModule` (`platform/omnideck-sdk/.../OmniModule.kt`) is the *entire* integration surface.
Implement it once per module in a class named exactly `ModuleEntryPoint`, in the module's own
namespace, with a public no-arg constructor. The `omnideck.module` convention plugin then
generates:

- the R8 keep rule for that class (it is loaded reflectively; without the rule, on-demand
  loading fails **in release builds only**), and
- `assets/omnideck/modules/<id>.properties`, the runtime discovery descriptor the kernel reads.

`ModuleManifest` is `@Serializable` and shared with the server-side Catalog; its `init` block
enforces that `entryRoute.host == id.shortId` and that `requiredCapabilities` is non-empty.

### Runtime flow

`ModuleLifecycleManager` (`platform/kernel/.../lifecycle/`) is the state machine of
`architecture.md` §7.1: discover → compatibility gate (`sdkRange`, `minHostVersionCode`) →
capability gate (are required capabilities present?) → `install` via a `ModuleProvider` →
`initialize` → register destinations and capabilities. Repeated init failures or a server kill
switch (the feature flag `module.<id>.enabled`) move a module to QUARANTINED with its scheduled
work cancelled. `ModuleProvider` abstracts the three delivery kinds (bundled/Play split,
satellite APK, web surface) behind one interface — deliberately with no provider that executes
code fetched from outside Play.

Navigation is URI-based: `omnideck://<shortId>/<path>`. `ShellNavHost` renders whatever the
`DestinationRegistry` resolves for the current route rather than pre-declaring routes, which is
what lets a module installed *after* the Shell was built become navigable.

### The isolation boundary

`ModuleScopedServicesFactory` (`platform/kernel/.../services/`) builds a **separate**
`PlatformServices` instance per module: telemetry tagged with the module id, storage paths
namespaced under `modules/<id>/`, per-module Keystore aliases, `X-OmniDeck-Module` on HTTP, and
call-time checks against the manifest's declared capabilities (`CapabilityNotGrantedException`).
A module never receives a raw kernel reference, so it cannot fabricate another module's
identity. Treat anything here as security-critical (`architecture.md` §12.2).

### Build logic

Build files stay thin because `build-logic/` (a composite build) supplies the conventions:
`omnideck.android.application` (Shell + module auto-wiring), `omnideck.android.library`,
`omnideck.android.feature`, `omnideck.module` (feature modules), `omnideck.compose`,
`omnideck.hilt`, `omnideck.jvm.library`, `omnideck.quality`. Shared Android/Kotlin/test config
lives in `Extensions.kt`. Convention plugins take AGP/Kotlin/KSP as `compileOnly`; versions come
from the consuming build's root `plugins { ... apply false }` block and `libs.versions.toml`.

Flipping a module to on-demand delivery is a Gradle property, not a code change:
`-Pomnideck.dynamicModules=<id>,<id>` moves it from `implementation` to `dynamicFeatures`.

### Testing

`:platform:testing` holds an in-memory fake for **every** capability, and each fake doubles as an
assertion surface (it records what the module did). A feature module must build and test
standalone against these with no Shell and no kernel — if it needs `:app` or another module to
work, the contract is missing something; raise it as an SDK issue rather than adding the
dependency. Kernel unit tests use Robolectric; JUnit4 + Truth + Turbine + MockK +
coroutines-test are applied to every project by the convention plugins.

## Conventions

- Branches: `<ticket>-short-description` off `main`, e.g. `od-009-lint-rules`; squash-merge.
- Commits: Conventional Commits with the ticket — `feat(kernel): add X [OD-127]` — body explains
  the *why*. Scope is the area: `sdk`, `kernel`, `shell`, `design-system`, `build-logic`, module id.
- Kotlin sources live under `src/main/kotlin`, not `src/main/java`.
- ktlint via Spotless, 120 columns; the `function-naming` and `filename` rules are disabled
  (Composables are PascalCase).
- Detekt config is `config/detekt/detekt.yml`; per-project `detekt-baseline.xml` /
  `lint-baseline.xml` are picked up automatically when present.
- Doc comments explain *why* a constraint exists and cite the spec section. Match that when
  touching platform code — it is how the guardrails stay legible.
