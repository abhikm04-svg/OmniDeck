package com.omnideck.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * `omnideck.tooling` — the module scaffolder (OD-211, architecture.md §17).
 *
 * ```
 * ./gradlew newModule -Pid=fitness -Powner=health-squad
 * ```
 *
 * This is an adoption lever, not a convenience (risk R10). The measured target is a
 * new engineer shipping a working module in three days using only the docs and this
 * command; every minute spent working out which of eleven files a module needs is a
 * minute arguing for a shortcut around the platform instead. The generated module
 * compiles, passes its own tests and appears on the home grid with no further edits —
 * and, critically, with no edit to the Shell.
 */
class ModuleScaffoldPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        check(this == rootProject) { "omnideck.tooling belongs on the root project" }

        tasks.register("newModule", NewModuleTask::class.java) {
            templateDir.set(layout.projectDirectory.dir("tools/module-template"))
            modulesRoot.set(layout.projectDirectory.dir("modules"))
            // Read as providers so the task stays configuration-cache clean: nothing
            // here touches project state at execution time.
            shortId.set(providers.gradleProperty("id").orElse(""))
            owner.set(providers.gradleProperty("owner").orElse("unassigned"))
            title.set(providers.gradleProperty("title").orElse(""))
        }
        Unit
    }
}

abstract class NewModuleTask : DefaultTask() {

    @get:InputDirectory abstract val templateDir: DirectoryProperty

    @get:OutputDirectory abstract val modulesRoot: DirectoryProperty

    @get:Input abstract val shortId: Property<String>

    @get:Input abstract val owner: Property<String>

    @get:Input abstract val title: Property<String>

    init {
        group = "omnideck"
        description = "Scaffolds a compliant feature module: ./gradlew newModule -Pid=<shortId>"
        // The output depends on a property, not on the inputs' content, and creating
        // a module twice must fail loudly rather than be skipped as up to date.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun scaffold() {
        val id = shortId.get().trim()
        validate(id)

        val destination = modulesRoot.get().asFile.resolve(id)
        if (destination.exists()) {
            throw GradleException("modules/$id already exists. Pick another id, or delete it first.")
        }

        val tokens = mapOf(
            TOKEN_SHORT_ID to id,
            TOKEN_MODULE_ID to "$NAMESPACE_PREFIX.$id",
            TOKEN_CLASS to id.split('_').joinToString("") { it.replaceFirstChar(Char::titlecase) },
            TOKEN_TITLE to title.get().ifBlank { id.replace('_', ' ').replaceFirstChar(Char::titlecase) },
            TOKEN_OWNER to owner.get(),
        )

        val template = templateDir.get().asFile
        var written = 0
        template.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(template).invariantSeparatorsPath
            if (relative in SKIPPED) return@forEach

            val outputPath = tokens.entries
                .fold(relative) { path, (token, value) -> path.replace(token, value.pathSafe()) }
                .removeSuffix(TEMPLATE_SUFFIX)

            destination.resolve(outputPath).apply {
                parentFile.mkdirs()
                writeText(source.readText().substitute(tokens))
            }
            written++
        }

        logger.lifecycle(
            """

            Created modules/$id ($written files).

              1. ./gradlew :modules:$id:test        — the generated tests pass as they are
              2. ./gradlew :app:installDebug        — the tile is already on the home grid

            No Shell file needs to change, now or later. If you find one that does, that
            is a gap in the SDK contract — raise it rather than editing the Shell.
            """.trimIndent(),
        )
    }

    /**
     * The id becomes a package name, a Play split name, a route host and a storage
     * directory, and is immutable once shipped. Rejecting a bad one here is far
     * cheaper than discovering it at the third of those.
     */
    private fun validate(id: String) {
        if (id.isBlank()) {
            throw GradleException("Usage: ./gradlew newModule -Pid=<shortId> [-Powner=<team>] [-Ptitle=<name>]")
        }
        if (!ID_PATTERN.matches(id)) {
            throw GradleException(
                "'$id' is not a valid module id. Use lowercase letters, digits and underscores, " +
                    "starting with a letter — it becomes a package name and a Play split name.",
            )
        }
        if (id in RESERVED) {
            throw GradleException("'$id' is reserved by the platform. Pick another id.")
        }
    }

    private fun String.substitute(tokens: Map<String, String>): String =
        tokens.entries.fold(this) { text, (token, value) -> text.replace(token, value) }

    /** `__PKG__` expands to a directory chain, not a dotted name. */
    private fun String.pathSafe(): String = replace('.', '/')

    private companion object {
        const val NAMESPACE_PREFIX = "com.omnideck"
        const val TEMPLATE_SUFFIX = ".template"

        const val TOKEN_MODULE_ID = "__MODULE_ID__"
        const val TOKEN_SHORT_ID = "__SHORT_ID__"
        const val TOKEN_CLASS = "__CLASS__"
        const val TOKEN_TITLE = "__TITLE__"
        const val TOKEN_OWNER = "__OWNER__"

        /** Documentation for the template itself; not part of a scaffolded module. */
        val SKIPPED = setOf("README.md")

        val ID_PATTERN = Regex("[a-z][a-z0-9_]*")

        /** `shell` owns the Shell's own routes; the rest would collide with a Gradle path. */
        val RESERVED = setOf("shell", "app", "platform", "tools", "build")
    }
}
