plugins {
    id("omnideck.android.library")
    id("omnideck.module")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.omnideck.finance"
}

// Compose, the design system, the SDK and the test harness all arrive with
// `omnideck.module`. What is left is this module's own choices: serialization for
// the stored records, and the two test tools its DataStore and Compose layers need.
dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Compose testing in the *unit* source set, not just androidTest. The convention
    // wires it for instrumented tests only, which leaves a module's screens coverable
    // only on a device — and a module whose UI can only be tested with an emulator
    // attached is one whose UI stops being tested. Robolectric renders them offline.
    testImplementation(libs.compose.ui.test.junit4)
}

description =
    """
    Module #2 — Finance (OD-311).

    Exists to be the *second* module: a contract validated by one implementation is a
    contract shaped around that implementation. It deliberately takes a different path
    through the SDK than Notes — a preferences DataStore rather than Room, billing
    rather than sync, and an optional capability whose absence costs one screen rather
    than the module.

    Adding it touched no file under app/ or platform:kernel, which is what OD-320
    checks.
    """.trimIndent()
