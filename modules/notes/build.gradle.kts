plugins {
    id("omnideck.android.library")
    id("omnideck.module")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.omnideck.notes"
}

// Room, OkHttp, Compose, KSP, the design system and the test harness all arrive with
// `omnideck.module`. What is left is this module's own choices: one code generator and
// the three test tools its persistence, HTTP and Compose layers need.
dependencies {
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}

description =
    """
    Module #1 — Notes (OD-209).

    Written against the SDK and the :platform:testing fakes only. It contains no
    reference to :app or :platform:kernel, and adding it to the product required no
    edit to either — which is the Phase 2 exit gate, and the reason it exists.
    """.trimIndent()
