package com.omnideck.build

import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `omnideck.android.feature` — Play Feature Delivery dynamic feature module (Phase 3, OD-301).
 *
 * A module opts into on-demand delivery by applying this instead of
 * `omnideck.android.library`, and by being listed in `omnideck.dynamicModules`.
 * Nothing else about the module changes — that equivalence is the point of ADR-001.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.dynamic-feature")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("omnideck.quality")
        pluginManager.apply("omnideck.module")

        extensions.configure(
            com.android.build.api.dsl.DynamicFeatureExtension::class.java,
        ) {
            configureAndroidCommon(this)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        configureKotlinAndroid()
        configureTestDependencies()
    }
}
