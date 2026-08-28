package com.omnideck.build

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
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
        val dynamic = onDemandModuleNames().filter { it in discovered }.toSet()
        val bundled = discovered.filterNot { it in dynamic }

        // An on-demand module's assets live inside its split, so its discovery
        // descriptor would only reach the device *after* the download it exists to
        // trigger — the module would never appear in the Catalog to be installed at
        // all. The base APK therefore carries the descriptors of the modules it
        // delivers on demand, resolved from each module project as a build artifact.
        //
        // Note what is not happening: no module is named here either. The list comes
        // from discovery, exactly as the bundled one does, so goal G1 holds in both
        // delivery modes.
        val onDemandDescriptors = configurations.create(ON_DEMAND_DESCRIPTORS_CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Discovery descriptors for the modules delivered on demand (OD-301)."
        }
        dynamic.forEach { moduleName ->
            dependencies.add(
                ON_DEMAND_DESCRIPTORS_CONFIGURATION,
                dependencies.project(
                    mapOf(
                        "path" to ":modules:$moduleName",
                        "configuration" to MODULE_DESCRIPTOR_CONFIGURATION,
                    ),
                ),
            )
        }

        // Play reads `dist:title` to name a download in its own confirmation dialog,
        // *before* the split exists — so the string has to live in the base APK, and
        // the module that owns it cannot supply it. Generated here from the same
        // discovered list, so the Shell still names no module (goal G1).
        val moduleTitlesTask = tasks.register(
            "generateOmniModuleTitles",
            GenerateModuleTitlesTask::class.java,
        ) {
            splitNames.set(dynamic.sorted())
            outputDir.set(layout.buildDirectory.dir("generated/omnideck/res"))
        }

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)

            defaultConfig {
                targetSdk = libs.version("targetSdk").toInt()
                testInstrumentationRunner = "com.omnideck.shell.OmniDeckTestRunner"
                vectorDrawables.useSupportLibrary = true
            }

            dynamicFeatures += dynamic.map { ":modules:$it" }.toSet()

            sourceSets.getByName("main").assets.srcDir(onDemandDescriptors)
            sourceSets.getByName("main").res.srcDir(moduleTitlesTask)

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

                // OD-213/214. Macrobenchmark and Baseline Profile generation both run
                // against a build that is shaped like release — minified, not
                // debuggable — because measuring a debug build measures the debugger,
                // and a profile recorded against unminified code names methods R8 has
                // since renamed. It is signed with the debug key so it installs on a
                // developer's device without release credentials.
                create("benchmark") {
                    initWith(getByName("release"))
                    signingConfig = signingConfigs.getByName("debug")
                    matchingFallbacks += listOf("release")
                    isDebuggable = false
                    versionNameSuffix = "-benchmark"
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

        // OD-202. The aggregating half of the module processor: it reads the factory
        // objects each bundled module's own KSP pass emitted and writes the static
        // registry the Shell uses instead of Class.forName (architecture.md §7.2).
        //
        // Deferred until KSP is applied, because `omnideck.hilt` — not this plugin —
        // is what brings KSP in, and it is applied afterwards by :app's build file.
        pluginManager.withPlugin("com.google.devtools.ksp") {
            dependencies.add("ksp", project(":tools:module-processor"))
            extensions.configure(com.google.devtools.ksp.gradle.KspExtension::class.java) {
                arg("omnideck.aggregateModules", "true")
            }
        }

        logger.lifecycle(
            "OmniDeck: wiring ${bundled.size} bundled module(s) $bundled" +
                if (dynamic.isEmpty()) "" else " and ${dynamic.size} on-demand module(s) $dynamic",
        )
    }
}

/**
 * Writes the `dist:title` strings for the on-demand modules into the base APK.
 *
 * The title is derived from the split name rather than from the module's
 * `ModuleManifest.displayName`, because the manifest is Kotlin inside the split and
 * this file has to exist before the split does. That is the same gap the Catalog has
 * for an uninstalled module, and it closes the same way: from the server-side Module
 * Registry in Phase 4, which serves display names for code the device does not have.
 */
abstract class GenerateModuleTitlesTask : DefaultTask() {

    @get:Input abstract val splitNames: ListProperty<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val entries = splitNames.get().joinToString(separator = "\n") { splitName ->
            """    <string name="${moduleTitleResource(splitName)}">${splitName.humanise()}</string>"""
        }
        outputDir.get().asFile.resolve("values").apply { mkdirs() }
            .resolve("omnideck_module_titles.xml")
            .writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <!-- Generated by omnideck.android.application (OD-301) — DO NOT EDIT. -->
                <resources>
                $entries
                </resources>

                """.trimIndent(),
            )
    }

    /** `expense_reports` -> `Expense reports`. */
    private fun String.humanise() = replace('_', ' ').replaceFirstChar(Char::titlecase)
}
