plugins {
    id("omnideck.android.library")
    id("omnideck.compose")
    id("omnideck.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.omnideck.kernel"
}

// Coverage: SecureStoreImpl's Keystore code cannot run in a unit test and is NOT
// excluded from the denominator — the kernel clears the 80% floor with those lines
// counted against it. See the note in the root gradle.properties for why excluding
// them was abandoned. The code itself is verified on a device:
//   ./gradlew :platform:kernel:connectedDebugAndroidTest

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
