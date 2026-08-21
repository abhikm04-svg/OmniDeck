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

// ---------------------------------------------------------------------------
// Coverage exclusion, deliberate and narrow.
//
// Kover does not credit code run through Compose's runtime under Robolectric. The
// evidence is unambiguous: plain property initializers in Tokens.kt report 0% despite
// every screenshot depending on them, while the module's ordinary Kotlin (TileState,
// WindowWidthClass, Spacing) reports 100% from the same test run.
//
// Excluded by annotation rather than by file, so the pure functions that share a file
// with a composable — the adaptive layout rules — stay measured. Leaving composables
// in would make the number measure how much of the module is UI rather than how well
// it is tested, and the honest response to that is not to lower the floor for everyone.
//
// What actually verifies this code is the screenshot gate:
//   ./gradlew :platform:design-system:verifyRoborazziDebug
// ---------------------------------------------------------------------------
omnideckCoverage {
    excludedAnnotations.add("androidx.compose.runtime.Composable")
}

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
