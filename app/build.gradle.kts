plugins {
    id("omnideck.android.application")
    id("omnideck.compose")
    id("omnideck.hilt")
}

// ---------------------------------------------------------------------------
// OD-214 — Baseline Profile.
//
// Shipping one needs nothing here: AGP merges `src/main/baseline-prof.txt` into the
// release build on its own, because androidx.profileinstaller is a dependency below.
// That is what a user who installs from Play gets, and it works on a build machine
// with no device attached.
//
// *Recording* one needs a device, so the plugin that drives it is opt-in — see the
// note in benchmark/build.gradle.kts for why leaving it on breaks `./gradlew build`:
//
//   ./gradlew -Pomnideck.baselineProfiles=true :app:generateBaselineProfile
// ---------------------------------------------------------------------------
if (providers.gradleProperty("omnideck.baselineProfiles").map(String::toBoolean).getOrElse(false)) {
    apply(plugin = "androidx.baselineprofile")
    dependencies.add("baselineProfile", project(":benchmark"))
}

android {
    namespace = "com.omnideck.shell"

    defaultConfig {
        applicationId = "com.omnideck.shell"
        versionCode = 1
        versionName = "0.1.0"
    }
}

// NOTE: no module is named here. Every directory under modules/ is discovered by
// settings.gradle.kts and wired in by the omnideck.android.application convention
// plugin. Adding a module must never require editing this file (goal G1, OD-212).
dependencies {
    implementation(projects.platform.kernel)
    implementation(projects.platform.omnideckSdk)
    implementation(projects.platform.designSystem)
    implementation(projects.platform.core)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.play.feature.delivery)
    // OD-309. In `:app` and not the kernel: this updates the *host*, which is a fact
    // about the application rather than a capability a module is granted.
    implementation(libs.play.app.update)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.startup)

    // The plug-and-play fitness test at unit level (OD-212): Robolectric gives it the
    // real merged assets, so it exercises the same discovery path the Shell uses on a
    // device rather than a hand-built fixture of it.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // The same capability fakes a module author gets. The Shell is a consumer of the
    // platform too, and testing it against hand-rolled doubles would let the two
    // drift apart.
    testImplementation(projects.platform.testing)

    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.testing)
    // OD-319. `WorkManagerInitializer` is removed from the manifest (see the node
    // below, and `WorkSchedulerImpl`'s own doc), which is exactly right for a Hilt
    // worker factory in the shipped app and exactly wrong for an instrumented test
    // that never installs one: the first test to reach a `WorkManager` call —
    // quarantine cancelling a module's scheduled work — found this by throwing
    // "WorkManager is not initialized properly", not by a task ever running wrong.
    androidTestImplementation(libs.androidx.work.testing)
    kspAndroidTest(libs.hilt.compiler)
}
