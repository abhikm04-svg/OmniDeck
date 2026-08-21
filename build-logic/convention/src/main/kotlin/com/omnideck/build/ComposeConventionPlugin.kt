package com.omnideck.build

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** `omnideck.compose` — Compose + Material 3 wiring, identical everywhere. */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val android = extensions.findByName("android") as? CommonExtension<*, *, *, *, *, *>
            ?: error("omnideck.compose must be applied after an Android plugin (${target.path})")
        android.buildFeatures.compose = true

        val bom = libs.lib("compose-bom")
        dependencies.apply {
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
            add("implementation", libs.lib("compose-ui"))
            add("implementation", libs.lib("compose-ui-graphics"))
            add("implementation", libs.lib("compose-ui-tooling-preview"))
            add("implementation", libs.lib("compose-material3"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("debugImplementation", libs.lib("compose-ui-tooling"))
            add("androidTestImplementation", libs.lib("compose-ui-test-junit4"))
            add("debugImplementation", libs.lib("compose-ui-test-manifest"))
        }

        // Compose compiler diagnostics — strong-skipping/stability reports feed the
        // performance budget work in Phase 6 (OD-608).
        extensions.configure(
            org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension::class.java,
        ) {
            val enableMetrics = providers.gradleProperty("omnideck.compose.metrics")
                .map(String::toBoolean).getOrElse(false)
            if (enableMetrics) {
                val dir = layout.buildDirectory.dir("compose-metrics")
                metricsDestination.set(dir)
                reportsDestination.set(dir)
            }
        }
    }
}
