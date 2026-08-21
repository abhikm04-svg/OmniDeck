plugins {
    id("omnideck.android.library")
}

android {
    namespace = "com.omnideck.testing"
}

description =
    """
    The module test harness (OD-105).

    A feature module must be buildable and testable with NO Shell and NO kernel —
    that is the difference between a platform teams adopt and a platform teams route
    around (risk R10). Everything a module can reach through PlatformServices has an
    in-memory fake here.
    """.trimIndent()

dependencies {
    api(projects.platform.omnideckSdk)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.truth)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
}
