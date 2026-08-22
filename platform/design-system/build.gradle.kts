plugins {
    id("omnideck.android.library")
    id("omnideck.compose")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.omnideck.designsystem"

    testOptions.unitTests.isIncludeAndroidResources = true
}

// ---------------------------------------------------------------------------
// OD-113 — screenshot tests.
//
// Roborazzi renders Compose under Robolectric, so the visual gate runs on any CI
// machine without a device. Images live next to the tests and are compared on every
// run; `recordRoborazziDebug` regenerates them after a deliberate visual change.
//
// Verifying a component in light, dark and dynamic colour across three window sizes
// is the Phase 1 exit criterion: a contrast or layout regression in a shared
// component reaches every module at once, and is exactly what no module's own tests
// would catch.
// ---------------------------------------------------------------------------
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

// Coverage: @Composable-annotated code is excluded from the denominator because Kover
// does not credit code run through Compose's runtime under Robolectric. The exclusion
// and the full reasoning live in the root gradle.properties
// (omnideck.coverage.excludeAnnotatedBy.platform.design-system). What actually verifies
// this code is the screenshot gate above.

description = "Shared Material 3 theme and components. Goal G4 — one look and feel across every module."

dependencies {
    api(libs.compose.material3)
    api(libs.compose.material3.adaptive)
    api(libs.compose.material3.adaptive.layout)
    api(libs.compose.material3.windowsize)
    api(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
