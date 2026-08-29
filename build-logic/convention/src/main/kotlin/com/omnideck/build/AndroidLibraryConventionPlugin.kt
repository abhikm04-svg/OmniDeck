package com.omnideck.build

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `omnideck.android.library` — the baseline for every Android library in the repo
 * (platform services, design system, testing harness).
 *
 * It is also where a feature module's delivery mechanism is decided (OD-301). A
 * module listed in `omnideck.dynamicModules` is handed to
 * `omnideck.android.feature` instead, so turning Notes into an on-demand Play split
 * is `-Pomnideck.dynamicModules=notes` and nothing else — no edit to the module's
 * build file, none to the Shell's. That equivalence is ADR-001's central claim, and
 * making the switch a property is what lets CI prove it on every run rather than
 * leaving it as an assertion in a document.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        if (target.isOnDemandModule()) {
            target.pluginManager.apply("omnideck.android.feature")
            return
        }
        applyLibrary(target)
    }

    private fun applyLibrary(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("omnideck.quality")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            // Declared only when present. Naming a file that does not exist makes R8
            // print "Supplied consumer proguard configuration does not exist" for every
            // library on every release build — six lines of noise that train people to
            // ignore R8's output, which is where the real keep-rule problems appear.
            file("consumer-rules.pro").takeIf(java.io.File::exists)
                ?.let { defaultConfig.consumerProguardFiles(it) }
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            // Libraries never ship a debug variant to consumers.
            testFixtures.enable = false

            buildTypes {
                release {
                    isMinifyEnabled = false
                }
            }
        }

        configureKotlinAndroid()
        configureTestDependencies()

        dependencies.add("implementation", libs.lib("kotlinx-coroutines-core"))
    }
}
