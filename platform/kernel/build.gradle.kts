plugins {
    id("omnideck.android.library")
    id("omnideck.compose")
    id("omnideck.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.omnideck.kernel"
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
}
