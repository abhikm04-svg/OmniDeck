plugins {
    id("omnideck.android.application")
    id("omnideck.compose")
    id("omnideck.hilt")
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
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.startup)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.testing)
}
