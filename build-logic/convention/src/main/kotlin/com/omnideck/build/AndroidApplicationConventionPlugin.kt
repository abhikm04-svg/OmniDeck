package com.omnideck.build

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `omnideck.android.application` — the Shell (`:app`).
 *
 * Also performs **module auto-wiring**: every directory discovered under `modules/`
 * by settings.gradle.kts is attached here automatically, either as a bundled project
 * dependency or as a Play Feature Delivery dynamic feature. This is what makes
 * architecture.md G1 ("add a module without touching Shell source") literally true —
 * `:app/build.gradle.kts` names no module.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("omnideck.quality")

        val discovered = System.getProperty("omnideck.modules")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()

        // Phase 3 (OD-301) flips modules onto the on-demand path simply by listing
        // them here — no source change in the Shell, in the module, or in the SDK.
        val dynamic = providers.gradleProperty("omnideck.dynamicModules")
            .getOrElse("")
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()

        val bundled = discovered.filterNot { it in dynamic }

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)

            defaultConfig {
                targetSdk = libs.version("targetSdk").toInt()
                testInstrumentationRunner = "com.omnideck.shell.OmniDeckTestRunner"
                vectorDrawables.useSupportLibrary = true
            }

            dynamicFeatures += dynamic.map { ":modules:$it" }.toSet()

            signingConfigs {
                // Release signing comes from CI secrets / a KMS-backed keystore.
                // Never from a file in the repository (architecture.md §12.3).
                val storePath = System.getenv("OMNIDECK_KEYSTORE_PATH")
                if (storePath != null) {
                    create("release") {
                        storeFile = file(storePath)
                        storePassword = System.getenv("OMNIDECK_KEYSTORE_PASSWORD")
                        keyAlias = System.getenv("OMNIDECK_KEY_ALIAS")
                        keyPassword = System.getenv("OMNIDECK_KEY_PASSWORD")
                        enableV1Signing = false
                        enableV2Signing = true
                        enableV3Signing = true
                        enableV4Signing = true
                    }
                }
            }

            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                    isMinifyEnabled = false
                }
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    isDebuggable = false
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    signingConfigs.findByName("release")?.let { signingConfig = it }
                }
            }

            bundle {
                language.enableSplit = true
                density.enableSplit = true
                abi.enableSplit = true
            }

            androidResources {
                @Suppress("UnstableApiUsage")
                generateLocaleConfig = true
            }
        }

        configureKotlinAndroid()
        configureTestDependencies()

        dependencies.apply {
            bundled.forEach { add("implementation", project(":modules:$it")) }
        }

        logger.lifecycle(
            "OmniDeck: wiring ${bundled.size} bundled module(s) $bundled" +
                if (dynamic.isEmpty()) "" else " and ${dynamic.size} on-demand module(s) $dynamic",
        )
    }
}
