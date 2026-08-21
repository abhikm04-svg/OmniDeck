plugins {
    id("omnideck.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.binary.compatibility)
}

description =
    """
    The OmniDeck contract, pure-Kotlin half.

    Deliberately free of Android so that:
      - the backend Module Registry can share these exact types and this exact JSON schema
      - a Kotlin Multiplatform migration is a move, not a rewrite (architecture.md §21)

    ADR-004: every change to the public ABI must be accompanied by a regenerated
    api/omnideck-sdk-core.api in the SAME commit. Run `./gradlew apiDump`.
    """.trimIndent()

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
}
