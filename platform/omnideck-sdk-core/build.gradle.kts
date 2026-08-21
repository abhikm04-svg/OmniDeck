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

// ---------------------------------------------------------------------------
// OD-104 — one manifest definition, shared with the backend.
//
// The Registry validates uploaded manifests against this schema. Generating it from
// ModuleManifest's own serializer is what stops the two drifting: a field added here
// cannot silently disagree with what the server accepts.
//
// The output is committed so the backend consumes a file rather than depending on
// this module. Regenerate after any manifest change:
//   ./gradlew :platform:omnideck-sdk-core:exportManifestSchema
// ---------------------------------------------------------------------------
val manifestSchemaFile = layout.projectDirectory.file("schema/module-manifest.schema.json")

tasks.register<JavaExec>("exportManifestSchema") {
    group = "documentation"
    description = "Regenerates the module manifest JSON Schema shared with the backend Registry."
    mainClass.set("com.omnideck.sdk.schema.ManifestSchema")
    classpath = sourceSets.main.get().runtimeClasspath
    args(manifestSchemaFile.asFile.absolutePath)
    outputs.file(manifestSchemaFile)
}
