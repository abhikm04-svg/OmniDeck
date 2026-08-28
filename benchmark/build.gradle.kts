// `:benchmark` — OD-213 (macrobenchmark harness) and OD-214 (Baseline Profile).
//
// Both live here because they are the same mechanism pointed at two ends: a
// Macrobenchmark drives the real Shell from a separate process and measures it, and a
// Baseline Profile is that same drive recorded as a list of methods to AOT-compile.
//
// Neither runs in the ordinary build — they need a device, and a build type that is
// minified and non-debuggable, because measuring a debug build measures the debugger:
//
//   ./gradlew :benchmark:connectedBenchmarkAndroidTest          # the numbers
//   ./gradlew -Pomnideck.baselineProfiles=true :app:generateBaselineProfile
//
// Budgets they exist to enforce (architecture.md §16): cold start p90 <= 1200 ms,
// module activation <= 400 ms.
//
// First measured run (POCO/air, Android 15, API 35, arm64, cpuLocked, 10 iterations),
// against the Shell plus one bundled module:
//
//   timeToInitialDisplayMs   p90 486   (CompilationMode.None — the pessimistic floor)
//   module.activateSumMs     p90   4   (kernel span: load + initialize + register)
//
// Both are recorded here rather than asserted in code on purpose: a budget belongs to
// a reference device (OD-317), and a threshold enforced against whatever phone is
// plugged in fails for the wrong reasons. What the harness guarantees today is that
// the numbers exist and are comparable run to run.

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    id("omnideck.quality")
}

// ---------------------------------------------------------------------------
// The Baseline Profile *producer* plugin is opt-in, and deliberately so.
//
// It models the recorded profile as the artifact of a generated variant, which hangs
// an adb-driven device run off this module's `assemble` — and therefore off
// `./gradlew build`. Left on, the repo-wide build fails with "No connected devices" on
// every CI runner and every machine without an emulator, for an output neither asked
// for. Filtering the dependency back off is not available: AGP contributes it as one
// opaque DefaultTaskDependency.
//
// Recording is rare and deliberate; consuming the result is neither. The recorded
// profile is committed to `app/src/main/baseline-prof.txt`, which AGP merges into every
// release build on its own — this plugin is not needed to *ship* a profile, only to
// record one.
// ---------------------------------------------------------------------------
val recordingProfiles =
    providers
        .gradleProperty("omnideck.baselineProfiles")
        .map(String::toBoolean)
        .getOrElse(false)

if (recordingProfiles) {
    // Records against a connected device or emulator, which is the producer plugin's
    // default when no Gradle Managed Device is configured. A managed device would be
    // more reproducible and is OD-317; adding one now would download
    // an AOSP image on every first build, for a profile recorded once a release.
    apply(plugin = "androidx.baselineprofile")
}

android {
    namespace = "com.omnideck.benchmark"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        // Macrobenchmark needs `am start` timing and a profileable process, neither of
        // which is reliable below 28 — higher than the Shell's own minSdk of 26, so
        // measurement is simply unavailable on the very oldest supported devices.
        minSdk = 28
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Must mirror :app's benchmark type. `matchingFallbacks` is what lets this
        // module resolve against the app when a build type has no exact counterpart.
        create("benchmark") {
            isDebuggable = true
            // Without this the APK ships unsigned and the device rejects it with
            // INSTALL_PARSE_FAILED_NO_CERTIFICATES. A test module inherits no signing
            // config from its target, and `release` — which this type copies from over
            // in :app — has none to inherit either.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"

    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.junit4)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}
