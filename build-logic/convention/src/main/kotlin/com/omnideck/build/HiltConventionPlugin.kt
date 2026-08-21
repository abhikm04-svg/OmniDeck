package com.omnideck.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `omnideck.hilt` — ADR-002.
 *
 * Applied to `:app` and `:platform:kernel` only. Feature modules do NOT use Hilt
 * directly; they receive `PlatformServices` through the SDK, which keeps the DI
 * framework an implementation detail of the platform and leaves the door open to
 * replacing it without touching a single module.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        dependencies.apply {
            add("implementation", libs.lib("hilt-android"))
            add("ksp", libs.lib("hilt-compiler"))
            add("kspTest", libs.lib("hilt-compiler"))
            add("testImplementation", libs.lib("hilt-testing"))
        }
    }
}
