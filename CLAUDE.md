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
./gradlew newModule -Pid=<shortId>      # scaffold a module (OD-211; -Powner, -Ptitle optional)
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

Instrumented tests need a device/emulator. `SecureStoreImpl` is only covered there (the Android
Keystore has no JVM or Robolectric implementation), and so is the device half of the
plug-and-play fitness test — its unit-level half runs in `:app:testDebugUnitTest` on every build:

```bash
./gradlew :platform:kernel:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest              # OD-212, scaffold -> load -> render
```

**Gradle Managed Devices** (`configureManagedDevices`, `build-logic/.../Extensions.kt`) are the
reproducible alternative to whatever phone is plugged in — AGP owns the whole lifecycle (image,
boot, install, teardown) from a device definition in source control, so there is no OEM install
policy or broadcast policy to fight (see "What is not verified here" below for what that cost on
a physical unit). Same tests, no device attached:

```bash
./gradlew :platform:kernel:pixel6Api34DebugAndroidTest     # reference device (OD-317)
./gradlew :platform:kernel:apiFloorPixel2DebugAndroidTest  # minSdk floor, API 26 (OD-303)
./gradlew :app:pixel6Api34DebugAndroidTest
./gradlew :app:omnideckSweepGroupDebugAndroidTest           # both devices, one invocation
```

First run downloads a system image per device (`aosp-atd` for `pixel6Api34`, plain `aosp` for
`apiFloorPixel2` — no ATD image exists that far back); every run after that reuses it. `minSdk`
26 needs `android.experimental.testOptions.managedDevices.allowOldApiLevelDevices=true`
(`gradle.properties`) — AGP declines API ≤26 GMDs by default, for image-staleness reasons that do
not apply to a device that never talks to a network of its own. Verified end to end in this repo
2026-08-29: all ten `SecureStoreImplTest` cases (the Keystore-backed suite this section opens
with) and all of `PlugAndPlayInstrumentedTest` pass on `pixel6Api34`, cold, from an empty image
cache, in about three minutes.

Performance work also needs a device, and is deliberately outside the ordinary build:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest            # OD-213 budgets
./gradlew -Pomnideck.baselineProfiles=true :app:generateBaselineProfile  # OD-214
```

The Baseline Profile *plugin* is opt-in because it hangs an adb run off `assemble`, which would
make `./gradlew build` need a device. Shipping a profile does not need it: the recorded
`app/src/main/baseline-prof.txt` is merged by AGP on every release build, which is why it lives
in `src/main` rather than in the plugin's `src/release/generated/` output directory — that path
is only a source set while the opt-in plugin is applied, and a profile silently dropped from the
release is the exact failure this arrangement exists to prevent. Verify a change with
`./gradlew :app:assembleRelease` and check that
`app/build/intermediates/merged_art_profile/release/**/baseline-prof.txt` still contains the
`com/omnideck` rules — currently 962 of them, 167 from inside the module, which is the evidence
that module activation is on the profiled path and not just the Shell's own startup.

**Startup profiles do not work this way**, and a committed `startup-prof.txt` is dead weight: with
the plugin off, `mergeReleaseStartupProfile` runs, reads no source set and writes nothing. Only
the plugin registers a startup profile, and only into its own generated directory. Recording one
still costs nothing — `BaselineProfileGenerator` passes `includeInStartupProfile = true` so the
artifact exists the day the profile workflow moves to the plugin's generated-sources model
(OD-607) — but note before relying on it that a single-interaction recording emits a startup
profile byte-identical to the baseline profile. A startup profile is meant to be the *subset*
reached during startup, and one that is the whole thing tells AGP's dex layout nothing.

**On OEM builds that block broadcasts to stopped packages** — observed on Xiaomi/HyperOS, where
`am broadcast` to a force-stopped package returns `result=0` even with
`--include-stopped-packages` — macrobenchmark cannot reach `androidx.profileinstaller`. Two
consequences, neither a defect in this repo:

```bash
# skip the shader-cache drop, which is otherwise attempted on every compilation mode
./gradlew :benchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dropShaders.enable=false
```

and `StartupBenchmark.startupWithBaselineProfile` cannot run there at all — it uses
`BaselineProfileMode.Require`, which exists precisely so the test cannot silently measure an
uncompiled app. Do not weaken it to `Require`'s softer siblings to make one phone go green. The
same OEM throttles repeated ADB installs, so an occasional `INSTALL_FAILED_USER_RESTRICTED` on a
connected-test run is the phone, not the build.

An emulator does run it, and is how to confirm that the committed profile is actually in the APK
and installable by ART — but macrobenchmark refuses an emulator until told not to:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.omnideck.benchmark.StartupBenchmark \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Read the pass/fail there, not the milliseconds. `suppressErrors` silences the reason the number
is unusable rather than removing it: the emulator reports `cpuLocked: false`, and on the same
build where a physical device measured a 486 ms uncompiled p90 it has measured 1292-1431 ms.
Taken as a budget that would fail Phase 2 for the host machine's scheduler. The first run after
boot is worse still — a cold emulator ramps 2826 -> 902 ms across ten iterations, so a p90 from
it can rank a profiled build *below* an unprofiled one.

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
- **`:tools:module-processor`** (KSP, ADR-010) checks every `ModuleEntryPoint` at compile time —
  public, concrete, no-arg constructor, implements `OmniModule` — and generates the factory the
  Shell's `GeneratedModuleRegistry` aggregates. Each check replaces a failure that previously
  appeared only at load time, on a device, in release builds.
- **`ShellIsolationFitnessTest`** (in `:app`) fails if any production source under `app/src/main`
  or `platform/kernel/src/main` names a module — the id, `omnideck://<shortId>`, or the short id
  as a bare string. This is the Phase 2 exit gate ("OD-209 required no Shell change") enforced
  mechanically rather than by reading a diff. Test fixtures are out of scope by design.
- **Coverage floors** — 80% for `:platform:*`, 70% for `:modules:*` (`gradle.properties`). A
  project with no `src/test` or `src/androidTest` warns instead of failing, and inherits the
  floor the moment a test source set appears. Exclusions are narrow, live in the root
  `gradle.properties` keyed by project path, and each is documented next to its reason there —
  they are *not* declared in the project's own build file, where both shapes tried before failed
  silently. Prefer covering code to excluding it: Kover's filters proved to reach `koverVerify`
  and `koverXmlReport` inconsistently on Linux, so an exclusion buys a number you then cannot
  reconcile with the published report. The kernel's untestable Keystore lines are counted against
  it rather than filtered out, for exactly that reason. A mistyped key is fail-safe.
- **`koverVerify`'s verdict and the published number are cross-checked** by
  `scripts/verify-coverage.py`, which parses each `report.xml` and enforces the same floors
  independently. They are not the same thing: across three CI runs a passing `koverVerify` sat
  beside a `report.xml` reading 75.8% against an 80% floor, with `koverXmlReport` and
  `koverHtmlReport` disagreeing from one invocation and every task executing fresh. Reports and
  gate now run in a single Gradle invocation, and a divergence fails the build rather than
  passing quietly. If you change the exclusions, check the script's output, not just a green tick.
- **A green `koverVerify` proves nothing unless it actually ran.** `koverCachedVerify` reports
  `UP-TO-DATE` and reuses a previous verdict even after the exclusions change, because the
  filter configuration is not one of its inputs. That produced two false greens here. Confirm a
  coverage claim with `./gradlew koverXmlReport koverVerify` followed by
  `python scripts/verify-coverage.py`; never trust an up-to-date pass.
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

### Deep links: two layers, only one needs a domain

Navigation is URI-based, and there are two independent doors into it. Keeping them
straight matters, because one is free and shipped and the other is blocked on money.

- **`omnideck://<shortId>/<path>` — the custom scheme.** Needs no domain, no
  verification, no infrastructure. It carries every internal link, notification tap and
  app shortcut, and it is what the Router contract is built on. **Nothing in the module
  system depends on App Links.**
- **`https://<origin>/go/<shortId>/<path>` — the App Link mirror.** Requires a domain we
  control: `.well-known/assetlinks.json` *is* the proof of ownership, and there is no
  substitute for it. Buys links that are clickable in an inbox or a chat app, plus a
  verified claim no other app can take.

**There is currently no https intent filter, deliberately.** One existed for
`omnideck.app`, which is registered to someone else; an intent filter is live behaviour
regardless of `autoVerify`, so the Shell was offering itself in the chooser for a third
party's URLs. It is withdrawn (OD-716). `ExternalRoutes.fromWeb`, the reserved `/go/`
prefix and their tests are all kept and passing — the design is decided (ADR-011), only
the origin is missing. The manifest carries the exact snippet to restore.

Do **not** restore it against a free subdomain to "get App Links working". A shared link
is permanent: publishing under an origin we intend to leave means either breaking every
shared URL on migration or serving redirects from it forever. That is ADR-011's accepted
debt, and the amendment rejects a temporary origin rather than merely deferring it. App
Links pay off once users share URLs, which pre-launch is nobody.

Two tickets, and they are not the same shape:

- **OD-716** — stand up a public web origin for the privacy policy and account-deletion
  page. **Blocks Play submission** independently of deep linking, and a *free* origin is
  fine because these are pages, not identity: a policy page can move and only a link
  rots. The pages live in **`sites/`** and deploy to Cloudflare Pages — live at
  `https://omnideck-sites.pages.dev` since 2026-08-29.
- **OD-321** — publish `assetlinks.json` and restore the filter. Needs a **permanent**
  origin. Re-scoped from Phase 3 to the Phase 7 launch gate on 2026-08-29.

`sites/` is static HTML and is **not** part of the Gradle build — no convention plugin
sees it. It deploys as one Cloudflare Pages project whose *root directory* is set to
`sites`, with no build command, which is what keeps the rest of the repository out of the
deployment. The runbook is `docs/web-origin.md`: dashboard settings, the `{{PLACEHOLDER}}`
tokens that must all be replaced before submission, and how to check a deploy.

Two rules govern what may go in `sites/`, and both fail quietly rather than loudly:

- **Everything in `sites/` is published**, whether or not it is a page. Pages serves the
  deployed directory as-is — there is no present-but-private file. The runbook itself was
  readable at `/README.md` for one deploy, which is why it now lives in `docs/`. Only
  Cloudflare's own control files are exempt (`_headers`, `_redirects`, `_routes.json`,
  `_worker.js` are read as config and never served).
- **Never add `.well-known/assetlinks.json` or `/go/` pages there** while the origin is a
  `*.pages.dev` subdomain. Policy pages may move; shared links may not.

`scripts/assetlinks.py` does both halves of OD-321's mechanics:
`--host <origin> generate --fingerprint <SHA256>` emits the file (the **Play App
Signing** certificate's SHA-256 from Play Console, not the upload key — Play re-signs),
and `--host <origin> verify` must exit 0 before the filter goes back. It checks what a
browser spot-check hides: HTTPS with no redirect (Android does not follow them here),
the `handle_all_urls` relation, the applicationId, and the fingerprint. It is not a CI
gate on purpose — it asserts an origin we do not control — but it does run against any
host, so the flow can be rehearsed on a throwaway origin without publishing links anyone
can share.

`--host` is **required and has no default**. It used to default to `omnideck.app`, so a
bare `verify` sent live requests to a domain registered to someone else and `generate`
printed "Publish this at https://omnideck.app/..." as an instruction — worst precisely
at the moment someone does OD-321, having just bought a *different* domain. The
fingerprint is a required flag for the same reason: hardcoding it would let it drift
silently from whatever key Play actually holds, and a stale fingerprint breaks App Links
in a way that only shows up in production.

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
`omnideck.hilt`, `omnideck.jvm.library`, `omnideck.quality`, `omnideck.tooling` (the root-only
`newModule` scaffolder, OD-211). Shared Android/Kotlin/test config
lives in `Extensions.kt`. Convention plugins take AGP/Kotlin/KSP as `compileOnly`; versions come
from the consuming build's root `plugins { ... apply false }` block and `libs.versions.toml`.

Flipping a module to on-demand delivery is a Gradle property, not a code change:
`-Pomnideck.dynamicModules=<name>,<name>`, naming Gradle project directories under `modules/`.
Everything that follows from it is derived (OD-301), and all of it matters — each piece below
was a way the flip silently did nothing or silently broke:

- `omnideck.android.library` **delegates to `omnideck.android.feature`** for a listed module, so
  the module's own build file does not change. The two plugins are kept deliberately identical
  apart from the Android plugin they apply.
- The module's descriptor records `delivery=FEATURE_SPLIT`, which is what makes the kernel pick
  `FeatureSplitProvider`. Before Phase 3 it always said `BUNDLED`, so a flipped module was handed
  to the bundled provider — which reports every module installed and then fails to find a class
  that is not on the device.
- The descriptor is **published as a Gradle artifact and packaged into the base APK**, because a
  dynamic feature's assets ship inside its split: left there, the descriptor would arrive only
  after the download it exists to trigger, so the module could never be advertised at all.
- A `<dist:module>` manifest with `<dist:on-demand/>` is generated for the split. There is no
  Gradle DSL for delivery, and **the default is install-time** — a split without it ships with
  the base APK and the entire acquisition path is silently never exercised.
- `dist:title` must resolve in the *base* module (Play reads it before the split exists), so the
  application plugin generates one string resource per on-demand module.
- `checkArchitecture` exempts one edge for a dynamic feature: AGP compiles a split against
  `:app`, and that dependency is the mechanism, not a design choice. Every other rule still
  applies. See the note on `CheckArchitectureTask.dynamicFeature` for what it costs.
- The module's descriptor directory stays registered as an asset source folder in **both**
  modes, and `generateOmniModuleDescriptor` empties it for a split rather than the plugin
  deregistering it. AGP's asset merge is incremental over the files in its source folders, so
  a folder that simply stops being one reports no removals and the merged output keeps the
  descriptor the previous mode wrote. That is what makes the property safe to flip in a build
  directory that already exists — otherwise `bundleRelease` fails with "Modules 'base' and
  '<id>' contain entry ... with different content" going one way, and the base APK silently
  loses every descriptor coming back. CI flips it back on every run (the "switch goes back" step).

Verify the whole flip with `./gradlew :app:bundleRelease -Pomnideck.dynamicModules=<name>` —
release rather than debug, because the generated R8 keep rule for the reflectively-loaded
`ModuleEntryPoint` is the one thing here that can only fail in a minified build. CI does this on
every run (the `on-demand-delivery` job).

### Testing

`:platform:testing` holds an in-memory fake for **every** capability, and each fake doubles as an
assertion surface (it records what the module did). A feature module must build and test
standalone against these with no Shell and no kernel — if it needs `:app` or another module to
work, the contract is missing something; raise it as an SDK issue rather than adding the
dependency. Kernel unit tests use Robolectric; JUnit4 + Truth + Turbine + MockK +
coroutines-test are applied to every project by the convention plugins.

Instrumented tests are compiled for **one** build type, and AGP defaults it to `debug` — so
they never see a minified app unless told to (OD-304). The keep rule for a module's
reflectively-loaded `ModuleEntryPoint` only matters there:

```bash
./gradlew :app:connectedBenchmarkAndroidTest -Pomnideck.testBuildType=benchmark
```

`benchmark` is release's shape signed with the debug key. R8 also processes the *test* APK in
that mode, which is what `app/proguard-test-rules.pro` exists for — test-only rules, deliberately
not in `proguard-rules.pro` where they could be mistaken for something the shipped app needs.

## What is not verified here, and why

**2026-08-29 — the device half moved to a Gradle Managed Device.** The HyperOS phone's two
failures below (`INSTALL_FAILED_USER_RESTRICTED` on a Gradle session install, and a refused
`profileinstaller` broadcast) are properties of that phone, not of "a device" — a GMD has
neither, because AGP owns the whole install path itself. Confirmed on `pixel6Api34`: all 10
`SecureStoreImplTest` cases, all of `PlugAndPlayInstrumentedTest`, all 10 kernel tests on
`apiFloorPixel2` (API 26, the actual `minSdk` floor OD-303 asks about), and a new
`QuarantineContainmentInstrumentedTest` (OD-319, below) all pass. What a GMD does **not** fix is
listed below it — it is still a software emulator, and it still has no Play Store.

- **Play is no longer a gap** (OD-011, OD-313, OD-303 — closed 2026-09-04). The app exists as
  `com.omnideck.shell`, Play App Signing is enrolled (upload key held locally, app signing key
  held by Google), and version 1 (0.1.0) is live on the Internal Testing track as a signed AAB
  built with `-Pomnideck.dynamicModules=notes,finance`. Verified against a real Play client on a
  real device, which nothing before this had ever done:
  - **On-demand delivery is real.** Play reports **1.67 MB** for a new install against a 3.33 MB
    base module — it is excluding both splits and serving one ABI/density. A bundle that had
    silently shipped its modules install-time (the failure `<dist:on-demand/>` exists to prevent)
    would report a larger number, so this figure *is* the evidence.
  - **OD-303's actual claim holds:** the split downloaded and the module rendered **without an
    app restart**. That is what `SplitCompat.install` in `attachBaseContext` buys, and its
    absence is the documented #1 split-install bug. Confirmed on one device at one API level;
    the API 26 floor is covered separately by `apiFloorPixel2` for module *loading*, not for
    split *install*, which needs a Play Store the GMD images do not have.
  - Pre-flight checks worth repeating on any future bundle, since each failure is invisible in
    the console: `jarsigner -verify` says `jar verified`; the AAB contains `base/`, `notes/`,
    `finance/` rather than `base/` alone; each split manifest greps for `on-demand` and not
    `install-time`; and `base/resources.pb` carries `omnideck_module_title_*` for every split,
    which is what Play reads to name a download before that split exists.

  Still unexercised against real Play, so do not record these as met: **OD-302's
  `REQUIRES_USER_CONFIRMATION` branch** never fired — the splits are 50–76 KB, far below the size
  or metered-network thresholds Play requires confirmation for, so that path remains fake-only
  and is awkward to trigger deliberately at this size. **OD-307** (`deferredUninstall`, purge
  fan-out) and **OD-309** (both In-App Update flows, which need a second version code on the
  track) are simply untested. **The M2 exit gate is closer but still not met** — it also wants
  uninstall/reinstall data purge, a server-driven kill switch (OD-310, no backend), and the SDK
  frozen and tagged `sdk-1.0.0`, which has not happened.
- **No backend** (OD-306, OD-310), still — a GMD is a client-side fix and this is a
  server-side gap. The Catalog serves what the device discovered, not a served catalog, and the
  kill switch reads a local flag rather than a pushed one. `ModuleManifest` is already
  `@Serializable` and `FeatureFlagService` is already the interface modules use, so both are a
  swap behind the capability boundary rather than a redesign — but until BE-101 exists there is
  nothing to swap to.
- **A GMD is still a software emulator** for macrobenchmark's purposes: `pixel6Api34` reports
  `cpuLocked: false`, the same as the local emulator this section used to warn about, and for
  the same reason — no benchmark image offers a locked clock. `startupWithBaselineProfile` now
  *runs* there (OD-318 was never able to say even that on the HyperOS phone), and reports
  `timeToInitialDisplayMs` median 306 ms against 291 ms with no compilation (10 iterations
  each) — but a 5% gap between "baseline-profiled" and "nothing compiled" is noise, not a
  result, on an unlocked clock, and this is not a number to hold the Shell to. OD-317's other
  half — a *trustworthy* number — and OD-318 stay open until either a physical reference device
  or Firebase Test Lab (`architecture.md` §18's actual named mechanism for this) is available.
  What OD-317 asked for that a GMD *can* settle — "define the reference device" — is settled:
  it is `pixel6Api34`, in source control, not a phone nobody can point to.
- **The minified instrumented-test APK does not start.** With `-Pomnideck.testBuildType=benchmark`
  the app minifies correctly but the *test* process dies in `OmniDeckTestRunner` on Hilt's test
  Application, which R8 strips along with the `Hilt_*` superclass it generates. `proguard-test-rules.pro`
  keeps both and it is still not enough. **OD-304 was answered without it**: the minified APK was
  installed and launched directly, and both modules discover, activate and render — including a
  module's `Degraded` banner, so `ModuleInitResult` propagates through a build R8 has been over.
  That is the gate bullet "release-build module loading verified — not just debug". A GMD does
  **not** reopen this: `-Pomnideck.testBuildType=benchmark` on `pixel6Api34` hits the exact same
  crash (`NoClassDefFoundError: HiltTestApplication`) as it always has, because `connectedAndroidTest`
  runs the *instrumented test* APK, which is what R8 strips — not the app APK, which is fine.
  Confirmed by this repo's own CI: it was tried as an `on-device` job step and removed once it
  reproduced the crash rather than kept as a step that cannot pass.
- **OD-319 — "kill the module process" restated as containment, and demonstrated.** Bundled and
  split modules still share the Shell process; there is nothing to kill until `processIsolation`
  modules (§12.6, Phase 6) or a satellite (Phase 5) exist. `QuarantineContainmentInstrumentedTest`
  drives the real path instead — flips the same `FeatureFlagService` kill switch production code
  reads, waits for the already-running `watchKillSwitches()` collector to react — and asserts the
  tile goes non-interactive with the reason shown, the Shell stays up, and every other module's
  tile is untouched. Writing it surfaced two real bugs, both fixed: `ModuleLifecycleManager.quarantine`
  calls into `WorkManager` to cancel scheduled work, which was never initialized in an instrumented
  test (`androidTestImplementation(libs.androidx.work.testing)` +
  `WorkManagerTestInitHelper.initializeTestWorkManager` in `@Before`, matching the manifest's
  deliberate removal of `WorkManagerInitializer`); and `ModuleTile`'s `clearAndSetSemantics`
  silently dropped the `Card`'s own disabled-state semantics, so a quarantined tile was already
  unclickable but TalkBack had no way to know that — fixed by setting `disabled()` explicitly
  inside the same block (`ModuleTile.kt`).
- **OD-315 — Notes has no sync target, still, and this was deliberately not attempted here.**
  `notes.sync.endpoint` (`NotesComponent.SYNC_ENDPOINT_FLAG`) is unset, so Notes stays
  `Degraded` by design; `NotesSync`, `NotesSyncTransport`, `RoomOutbox` and `NotesSyncScheduler`
  all exist and are unit-tested (`NotesSyncTransportTest` already covers the wire protocol
  against a `MockWebServer`), but nothing has driven them from a device. This is a genuinely
  separate ~5-day ticket (`implementation_plan.md` OD-315), not a device-tooling gap, and it
  should not be rushed alongside one. The decision the ticket asked for first: **amend the M1
  bullet to mean the `MockWebServer` fixture**, not stand up a Firestore-backed stub — nothing
  in this repo owns a real backend yet (OD-306/OD-310, above), and building one just to satisfy
  this bullet would be exactly the "swap to nowhere" those two already describe. The on-device
  demonstration itself is specified, not built: an `:app` instrumented test would need to (1)
  set `notes.sync.endpoint` to `http://127.0.0.1:<port>` *before* the module first activates —
  `NotesComponent.build()` reads the flag once, unlike the kill switch's live `Flow` — with a
  `MockWebServer` the test starts (it runs in-process on the device, so no host/emulator network
  aliasing is needed); (2) drive `NotesListScreen`/`NoteEditorScreen` by their existing content
  descriptions (`"New note"`, `"Waiting to sync"`) rather than reach into Room, which `:app`'s
  test has no compile-time access to; (3) force `NotesSyncScheduler`'s `WorkManager` job to run
  synchronously the same way `QuarantineContainmentInstrumentedTest` now initializes test
  WorkManager, rather than wait on its own backoff; (4) assert the outbox drain, a 409 conflict,
  and a delete/tombstone the same way `NotesSyncTransportTest` already asserts them at unit
  level, but by watching `"Waiting to sync"` appear and disappear rather than an in-process
  transport call.

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
