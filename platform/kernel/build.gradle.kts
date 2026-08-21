plugins {
    id("omnideck.android.library")
    id("omnideck.compose")
    id("omnideck.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.omnideck.kernel"
}

// ---------------------------------------------------------------------------
// Coverage exclusion, deliberate and narrow.
//
// SecureStoreImpl talks to the Android Keystore, which has no JVM provider and is not
// implemented by Robolectric: its crypto cannot execute in a unit test at all. It IS
// tested — src/androidTest/.../SecureStoreImplTest.kt covers the encrypt/decrypt round
// trip, per-module key isolation, alias sanitisation and the absence of plaintext on
// disk — but instrumented runs need a device, so Kover never sees that coverage.
//
// The trailing wildcard is load-bearing: each suspend function compiles to its own
// SecureStoreImpl$put$2 continuation class, and an exact-name filter leaves four of
// them counted as uncovered.
//
// Run the real thing with:  ./gradlew :platform:kernel:connectedDebugAndroidTest
// ---------------------------------------------------------------------------
omnideckCoverage {
    excludedClasses.addAll(
        "com.omnideck.kernel.services.SecureStoreImpl",
        "com.omnideck.kernel.services.SecureStoreImpl\$*",
    )
}

description =
    """
    Implementation of every platform capability, plus the module loader and lifecycle
    state machine.

    Scaffold deviation from architecture.md §17: the kernel is one Gradle module with
    internal packages named after the documented submodules (loader, lifecycle,
    registry, router, events, services). Split it into :platform:kernel:* when either
    (a) more than one team commits here regularly, or (b) its clean build exceeds
    ~90 s. Splitting early costs build time for no benefit; splitting late is painful.
    """.trimIndent()

dependencies {
    api(projects.platform.omnideckSdk)
    implementation(projects.platform.core)
    implementation(projects.platform.designSystem)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)

    // Play Feature Delivery — the primary module delivery channel (ADR-001).
    implementation(libs.play.feature.delivery)

    testImplementation(projects.platform.testing)
    testImplementation(libs.robolectric)
    // ApplicationProvider, for the Robolectric-backed service tests.
    testImplementation(libs.androidx.test.core)
    // WorkManagerTestInitHelper. The Shell strips WorkManagerInitializer from the
    // manifest and initialises WorkManager itself, so tests have to stand it up too —
    // and calling WorkManager.initialize directly leaks "already initialized" across
    // Robolectric's per-test Application.
    testImplementation(libs.androidx.work.testing)

    // Instrumented tests. SecureStoreImpl talks to the Android Keystore, which has no
    // JVM or Robolectric implementation — a real device or emulator is the only place
    // its crypto can actually run.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
