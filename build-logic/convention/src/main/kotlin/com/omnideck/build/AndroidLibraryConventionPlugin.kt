package com.omnideck.build

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `omnideck.android.library` — the baseline for every Android library in the repo
 * (platform services, design system, testing harness).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
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
