# OmniDeck — Task Status

Status vocabulary: **Completed** · **Blocked** · **Deferred** · **To be implemented**

Last updated: 2026-08-29 (PR #14)

---

## Phase 0 — Foundations

- OD-001 Toolchain bootstrap and a verified `assembleDebug` - Completed
- OD-002 Repo init, branch protection, CODEOWNERS, PR/issue templates, commit conventions - Completed
- OD-003 Gradle composite build `build-logic` + version catalog - Completed
- OD-004 Convention plugins: application, library, compose, hilt, test, quality - Completed
- OD-005 Skeleton `:app` — single Activity, Compose, M3 theme, Hilt - Completed
- OD-006 Static analysis: Detekt + ktlint/Spotless + Android Lint baseline - Completed
- OD-007 CI: GitHub Actions — build, unit test, lint on every PR - Completed
- OD-008 Kover coverage reporting + thresholds wired into `quality` plugin - Completed
- OD-009 Custom Lint rules v1: module→module ban, `Log.` ban, raw-permission ban - Completed
- OD-010 ADR process + `docs/adr/` seeded with ADR-001…009 - Completed
- OD-011 Play Console account, app created, Play App Signing, upload key in KMS - Blocked
- OD-012 Debug/release signing configs, `network_security_config`, `allowBackup=false` - Completed
- OD-013 Remote build cache node + configuration cache enabled and verified - Completed

## Phase 1 — SDK & Platform Kernel

- OD-101 `:platform:omnideck-sdk` — OmniModule, ModuleManifest, PlatformServices, Route, SemVer - Completed
- OD-102 Capability interfaces (all 15) - Completed
- OD-103 `binary-compatibility-validator` + checked-in `.api` dump + CI `apiCheck` gate - Completed
- OD-104 `kotlinx.serialization` manifest schema + JSON Schema export - Completed
- OD-105 `:platform:testing` — an in-memory fake for every capability - Completed
- OD-106 `:platform:core` — Result, dispatchers, clock, coroutine utilities - Completed
- OD-110 Design tokens: colour, type, spacing, shape, motion - Completed
- OD-111 Component library: buttons, fields, cards, sheets, dialogs, module tile, states - Completed
- OD-112 Adaptive layout scaffolds + foldable support - Completed
- OD-113 Roborazzi screenshot tests + CI visual-diff gate - Completed
- OD-114 Accessibility pass: semantics, touch targets, contrast, TalkBack - Completed
- OD-120 `NetworkService` — OkHttp core, derived per-module clients, NetworkMonitor - Completed
- OD-121 `StorageService` — namespaced Room/DataStore/files, quota + LRU eviction - Completed
- OD-122 `SecureStore` — Tink + Keystore, per-module key derivation, StrongBox - Completed
- OD-123 `TelemetryService` — event/span/metric API, sink abstraction, PII redaction - Completed
- OD-124 `FeatureFlagService` — Remote Config + typed accessor codegen + debug overrides - Blocked
- OD-125 `EventBus` — typed, lifecycle-scoped, versioned event envelopes - Completed
- OD-126 `CapabilityRegistry` — registration, resolution, absence, grant enforcement - Completed
- OD-127 `PermissionBroker` — rationale UI, denial handling, settings deep link, audit - Completed
- OD-128 `WorkScheduler` — module-tagged WorkManager wrapper with atomic cancellation - Completed
- OD-129 `ModuleScopedServicesFactory` — the per-module isolation boundary - Completed
- OD-130 Hilt `@EntryPoint` bridging + `PlatformServices` assembly - Completed

## Phase 2 — Shell MVP & First Module

- OD-201 Shell app scaffold: MainActivity, NavHost, OmniDeckApplication, SplitCompat - Completed
- OD-202 `ModuleRegistry` + KSP-generated `GeneratedModuleRegistry` for bundled modules - Completed
- OD-203 `ModuleLifecycleManager` — the full state machine - Completed
- OD-204 `Router` + `DestinationRegistry` — URI routes, pattern matching, deep links - Completed
- OD-205 `navigateForResult` with process-death-safe correlation ids - Completed
- OD-206 Home screen — module grid, tile states - Completed
- OD-207 Settings + Privacy Centre shells - Completed
- OD-208 Global error/quarantine surfaces + UncaughtExceptionHandler attribution - Completed
- OD-209 Module #1: Notes — full offline-first module - Completed
- OD-210 `SyncEngine` — outbox, backoff, conflict policy - Completed
- OD-211 `tools/module-template` + `./gradlew newModule` scaffolder - Completed
- OD-212 Instrumented plug-and-play fitness test: scaffold → build → load → render - Completed
- OD-213 Macrobenchmark harness + cold-start and activation baselines recorded - Completed
- OD-214 Baseline Profile generation wired into the release build - Completed

## Phase 3 — Dynamic Delivery & Catalog

- OD-301 Convert Notes to a dynamic feature; `FeatureSplitProvider` - Completed
- OD-302 Install progress UX: download %, user confirmation, metered prompt, cancel, retry - Completed
- OD-303 `SplitCompat` verification across API 26→36; no-restart code availability - Blocked
- OD-304 R8 keep-rule generation + release-build load test - Completed
- OD-305 Catalog screen — categories, search, detail, disclosure, install CTA - Completed
- OD-306 Catalog client: fetch, ETag caching, offline snapshot, hard-TTL refresh - Blocked
- OD-307 `deferredUninstall` + module removal UX + `purge()` fan-out - Blocked
- OD-308 Compatibility gate: `sdkRange` + `minHostVersionCode` evaluation - Completed
- OD-309 Play In-App Updates (flexible + immediate) - Completed
- OD-310 Kill switch client: server flag → quarantine, work cancellation, UI state - Blocked
- OD-311 Module #2: Finance — second independent module - Completed
- OD-312 App size CI budget check (base ≤ 25 MB, per-split ≤ 8 MB) - Completed
- OD-313 First Play Internal Testing submission with on-demand splits - Blocked
- OD-314 App shortcuts + notification deep links routing through Router - Completed
- OD-315 Give Notes a sync target and demonstrate the round trip on a device - Deferred
- OD-316 Instrumented persistence test across process death - Completed
- OD-317 Define the reference device, and re-record the §16 budgets on it - Blocked
- OD-318 Record `startupWithBaselineProfile` on hardware permitting the broadcast - Blocked
- OD-319 Settle what "killing the module process" means, then demonstrate it - Completed
- OD-320 Apply M1's real acceptance test to Finance via the PR diff - Completed

## Phase 4 — Identity, Entitlement & Security

- OD-401 `AuthService` — AppAuth OIDC + PKCE in Custom Tabs, session StateFlow - To be implemented
- OD-402 Token storage in SecureStore; single-flight refresh; rotation + reuse detection - To be implemented
- OD-403 Biometric unlock + step-up re-auth for high-assurance actions - To be implemented
- OD-404 Onboarding + sign-in/sign-up/forgot flows; guest mode - To be implemented
- OD-405 `SessionChanged` event fan-out; module reaction contract + tests - To be implemented
- OD-406 Entitlement client: signed snapshot, TTL, offline grace, gated catalog - To be implemented
- OD-407 Play Billing integration + server-side receipt verification - To be implemented
- OD-408 Purchase flows: one-off, subscription, bundle, restore, grace/hold - To be implemented
- OD-409 Play Integrity API — request, verdict validation, degraded-mode policy - To be implemented
- OD-410 Certificate pinning + backup pins + drilled rotation runbook - To be implemented
- OD-411 `ConsentService` + consent-gated telemetry + purpose management UI - To be implemented
- OD-412 Account deletion: in-app + web URL + purge fan-out + erasure + receipt - To be implemented
- OD-413 R8 hardening: full mode, string obfuscation, mapping upload, anti-tamper - To be implemented
- OD-414 Threat-model workshop + security review of §12 controls - To be implemented
- OD-415 MobSF + OWASP dependency-check + SBOM in CI, failing on CVSS ≥ 7 - To be implemented

## Phase 5 — Satellite Federation

- OD-501 Publish `omnideck-sdk` to an internal Maven repository - Deferred
- OD-502 AIDL contract `IOmniSatellite` + parcelables + protocol version negotiation - Deferred
- OD-503 `SatelliteProvider` — discovery, signature pinning, bind, handshake - Deferred
- OD-504 Custom signature/knownSigner permission + caller uid verification - Deferred
- OD-505 Token exchange (RFC 8693): audience-scoped 5-min assertions - Deferred
- OD-506 Large-payload transfer via ParcelFileDescriptor / ContentProvider - Deferred
- OD-507 Launch/return protocol with persisted correlation ids - Deferred
- OD-508 Satellite #1: Scanner — camera-heavy, own Play listing - Deferred
- OD-509 Cross-version compatibility test matrix in CI - Deferred
- OD-510 Not-installed / stale-version / revoked-signature UX paths - Deferred
- OD-511 Satellite CI/CD lane + its own Play listing and staged rollout - Deferred

## Phase 6 — Observability, Performance & Scale-Out

- OD-601 OpenTelemetry SDK on device; `traceparent` propagation - Deferred
- OD-602 Module-attributed crash/ANR reporting; per-module Crashlytics keys - To be implemented
- OD-603 Event schema registry + typed Kotlin codegen - To be implemented
- OD-604 Dashboards: DAU, retention, install funnel, activation latency, error budget - To be implemented
- OD-605 Alerting + on-call rotation + severity matrix + escalation policy - To be implemented
- OD-606 Runbooks: quarantine, pin rotation, key rotation, rollback, DSAR, takedown - To be implemented
- OD-607 Automated per-module Baseline Profiles merged into the bundle - To be implemented
- OD-608 Startup optimisation pass: androidx.startup, lazy init, DEX layout - To be implemented
- OD-609 Memory profiling, LeakCanary in QA builds, onTrimMemory suspension - To be implemented
- OD-610 Process isolation for `processIsolation = true` modules + DeathRecipient - Deferred
- OD-611 Modules #3 and #4 (Fitness, Documents) — new-team onboarding trial - To be implemented
- OD-612 Web module provider + one content surface (help centre) - Deferred
- OD-613 Localisation infrastructure + 3 pilot locales + pseudolocale testing - To be implemented
- OD-614 Affected-module CI + build-time budget alerting - To be implemented

## Phase 7 — Compliance, Hardening & Beta

- OD-701 Data Safety generator: aggregate dataCategories → declaration draft - To be implemented
- OD-702 Privacy policy, per-module disclosures, ToS, in-app legal centre - To be implemented
- OD-703 Play listing: store assets, screenshots, feature graphic, content rating - To be implemented
- OD-704 Permission declaration forms for any sensitive permission - To be implemented
- OD-705 Target-API compliance check against the current Play requirement - To be implemented
- OD-706 Large-screen + foldable certification pass on Test Lab - To be implemented
- OD-707 Accessibility audit (external) + remediation - To be implemented
- OD-708 External penetration test + remediation cycle - To be implemented
- OD-709 Load/soak: 100k-user catalog simulation; 72 h device soak - To be implemented
- OD-710 Chaos drills: backend down, kill switch, forced update, expired pin - To be implemented
- OD-711 Closed testing (≥ 100 testers) → feedback triage → fixes - To be implemented
- OD-712 Open testing / public beta (≥ 500 testers) - To be implemented
- OD-713 Pre-launch report triage; Vitals baseline on real traffic - To be implemented
- OD-714 Documentation freeze: module author guide, changelog, ops handbook - To be implemented
- OD-715 DR plan + backup/restore drill for backend data stores - To be implemented
- OD-716 Stand up the public web origin: privacy policy, ToS, account-deletion page - Completed
- OD-321 Publish `assetlinks.json` and restore the App Link filter with autoVerify - Deferred

## Phase 8 — GA Launch & Operations

- OD-801 Production release candidate; full regression across the device matrix - To be implemented
- OD-802 Staged rollout 1% → 100% with Vitals halt criteria - To be implemented
- OD-803 Launch monitoring war room; halt/rollback authority rehearsed - To be implemented
- OD-804 Support tooling: in-app feedback, diagnostic bundle export, support console - To be implemented
- OD-805 Post-launch defect SLA + hotfix lane - To be implemented
- OD-806 Release-train cadence formalised + train calendar published - To be implemented
- OD-807 Platform-team operating model: review board, RFC process, office hours - To be implemented
- OD-808 Post-launch review: metrics vs. targets, retro, Year-2 roadmap - To be implemented

## Backend Workstream (parallel)

- BE-101 Registry + BFF - Deferred
- BE-201 Identity + Entitlement - Deferred
- BE-301 Config + Telemetry - Deferred
- BE-401 Notification + Audit - Deferred
- BE-501 Hardening + SRE - Deferred
