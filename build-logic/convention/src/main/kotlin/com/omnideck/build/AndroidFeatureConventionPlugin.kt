package com.omnideck.build

import com.android.build.api.dsl.DynamicFeatureExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `omnideck.android.feature` — Play Feature Delivery dynamic feature module (Phase 3, OD-301).
 *
 * Never applied by hand. `omnideck.android.library` delegates here for any module
 * listed in `omnideck.dynamicModules`, which is what makes a module's delivery
 * mechanism a build switch rather than a source change (ADR-001).
 *
 * Everything else about the module is deliberately identical to the library form —
 * same quality gates, same SDK and design-system dependencies, same entry-point
 * validation, same coroutines dependency. A module whose behaviour changed with its
 * delivery mechanism would make the switch untestable, which is the failure this
 * symmetry exists to prevent.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.dynamic-feature")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("omnideck.quality")
        pluginManager.apply("omnideck.module")

        extensions.configure(DynamicFeatureExtension::class.java) {
            configureAndroidCommon(this)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        configureKotlinAndroid()
        configureTestDependencies()

        // Play Feature Delivery's inverted dependency. A split has no application id,
        // no resources and no base classes of its own: AGP reads all three from the
        // base APK through this edge, and `dynamicFeatures` in the Shell does not
        // create it. Without it the module fails at processReleaseMainManifest with
        // "Failed to calculate the value of property 'applicationId' > Collection is
        // empty" — the base metadata it went looking for and did not find.
        //
        // This is the one edge CheckArchitectureTask.dynamicFeature exempts. It is
        // declared here rather than in the module's build file so that flipping
        // delivery still costs no source change (ADR-001), and so that the module has
        // no *declared* Shell dependency of its own to build and test standalone with.
        dependencies.add("implementation", project(":app"))

        // Parity with omnideck.android.library. Without it a module that used
        // coroutines directly would compile as a library and fail to compile as a
        // split — a difference visible only to whoever flipped the property.
        dependencies.add("implementation", libs.lib("kotlinx-coroutines-core"))
    }
}
