package com.omnideck.sdk.schema

import com.omnideck.sdk.ModuleManifest
import java.io.File

/**
 * The manifest schema the backend Registry validates uploads against (OD-104).
 *
 * Generated from `ModuleManifest`'s own serializer, so there is one definition rather
 * than two that drift. Regenerate with:
 *
 * ```
 * ./gradlew :platform:omnideck-sdk-core:exportManifestSchema
 * ```
 */
object ManifestSchema {

    const val ID = "https://schema.omnideck.app/module-manifest.schema.json"

    /** The schema as pretty-printed JSON. */
    fun render(): String = JsonSchema.render(ModuleManifest.serializer().descriptor, ID)

    /**
     * Writes the schema to [target], creating parent directories.
     *
     * `main` so it can be driven by a Gradle `JavaExec` task; the backend consumes the
     * committed output rather than depending on this module.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "module-manifest.schema.json")
        target.parentFile?.mkdirs()
        target.writeText(render())
        println("Wrote manifest JSON Schema to ${target.absolutePath}")
    }
}
