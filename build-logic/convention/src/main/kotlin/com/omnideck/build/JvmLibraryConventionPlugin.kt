package com.omnideck.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `omnideck.jvm.library` — pure-Kotlin JVM modules.
 *
 * Used by `:platform:omnideck-sdk-core`, which must stay free of Android so it can
 * be shared verbatim with the backend and migrated to Kotlin Multiplatform later
 * (architecture.md §5.1 rule 3, §21).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("omnideck.quality")

        configureKotlinJvm()
        configureTestDependencies()

        extensions.configure(org.gradle.api.plugins.JavaPluginExtension::class.java) {
            toolchain.languageVersion.set(
                org.gradle.jvm.toolchain.JavaLanguageVersion.of(libs.version("jdk").toInt()),
            )
        }

        dependencies.add("implementation", libs.lib("kotlinx-coroutines-core"))
    }
}
