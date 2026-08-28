plugins {
    id("omnideck.android.library")
    id("omnideck.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.binary.compatibility)
}

android {
    namespace = "com.omnideck.sdk"
}

description =
    """
    The OmniDeck contract, Android half. This is the ONLY artifact a module author
    depends on directly.

    Rule (architecture.md §5.1): this module may depend on omnideck-sdk-core and
    platform:core, and on nothing else in the repo. It must never see the kernel,
    the Shell, or a feature module. `checkArchitecture` enforces it.
    """.trimIndent()

dependencies {
    api(projects.platform.omnideckSdkCore)

    // SyncEngine (OD-210) takes a Clock rather than reading the wall clock: backoff,
    // TTLs and conflict timestamps are exactly the boundaries a static clock makes
    // untestable. `api` because the type appears in SyncEngine's constructor, so a
    // module must be able to name it.
    api(projects.platform.core)

    api(libs.androidx.core.ktx)
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.androidx.work.runtime)
    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.kotlinx.serialization.json)
}
