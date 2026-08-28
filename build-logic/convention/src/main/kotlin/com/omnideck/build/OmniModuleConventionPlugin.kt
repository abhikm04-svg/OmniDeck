package com.omnideck.build

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

/**
 * `omnideck.module` — applied to every feature module.
 *
 * Gives a module author a ~10-line build file (architecture.md §17) by supplying:
 *  - SDK, design-system and test-harness dependencies
 *  - the R8 keep rule for the reflectively-loaded `ModuleEntryPoint` (§7.2). Without
 *    it the class is stripped and on-demand loading fails *in release builds only* —
 *    the nastiest failure mode in this architecture, so it is automated, not documented.
 *  - a generated `assets/omnideck/modules/<id>.properties` descriptor used for runtime
 *    discovery by the Shell (see ModuleDescriptorSource in :platform:kernel)
 *  - the module coverage floor
 */
class OmniModuleConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("omnideck.compose")
        // Applied here rather than in each module's build file so that every module
        // gets the entry-point validation of OD-202 whether or not it happens to use
        // another code generator of its own.
        pluginManager.apply("com.google.devtools.ksp")

        dependencies.apply {
            add("implementation", project(":platform:omnideck-sdk"))
            add("implementation", project(":platform:design-system"))
            add("implementation", project(":platform:core"))
            // A module must build and test with NO Shell and NO kernel.
            add("testImplementation", project(":platform:testing"))
            add("androidTestImplementation", project(":platform:testing"))

            // Checks ModuleEntryPoint's shape at compile time and emits the factory
            // the Shell's registry aggregates (architecture.md §7.2).
            add("ksp", project(":tools:module-processor"))
        }

        val generatedRoot = layout.buildDirectory.dir("generated/omnideck")

        val keepRulesTask = tasks.register(
            "generateOmniModuleKeepRules",
            GenerateKeepRulesTask::class.java,
        ) {
            outputDir.set(generatedRoot.map { it.dir("proguard") })
        }

        val descriptorTask = tasks.register(
            "generateOmniModuleDescriptor",
            GenerateDescriptorTask::class.java,
        ) {
            gradlePath.set(this@with.path)
            outputDir.set(generatedRoot.map { it.dir("assets") })
        }

        // Only used on the on-demand path; registered unconditionally so the task
        // graph does not change shape with a Gradle property (OD-301).
        val distManifestTask = tasks.register(
            "generateOmniModuleDistManifest",
            GenerateDistManifestTask::class.java,
        ) {
            splitName.set(this@with.name)
            outputDir.set(generatedRoot.map { it.dir("manifest") })
        }

        // OD-301. The descriptor is published as a build artifact as well as (for a
        // bundled module) packaged into the module's own assets, because an on-demand
        // module's assets ship inside its split — they arrive *after* the download the
        // descriptor is supposed to trigger. The Shell resolves this configuration for
        // the modules it delivers on demand and packages their descriptors into the
        // base APK, so discovery still precedes acquisition.
        configurations.create(MODULE_DESCRIPTOR_CONFIGURATION) {
            isCanBeConsumed = true
            isCanBeResolved = false
            description = "The runtime discovery descriptor for this module (architecture.md §7.2)."
        }
        artifacts.add(MODULE_DESCRIPTOR_CONFIGURATION, descriptorTask.flatMap { it.outputDir }) {
            type = "directory"
            builtBy(descriptorTask)
        }

        // The namespace is declared by the module's own build file, so it is only
        // readable after evaluation.
        afterEvaluate {
            val android = extensions.findByName("android") as? CommonExtension<*, *, *, *, *, *>
                ?: error("omnideck.module requires an Android plugin on ${this@with.path}")
            val namespace = requireNotNull(android.namespace) {
                "${this@with.path} must declare `android.namespace` — it is the module id."
            }
            val onDemand = android is DynamicFeatureExtension

            keepRulesTask.configure { moduleId.set(namespace) }
            descriptorTask.configure {
                moduleId.set(namespace)
                // What the kernel reads to choose a ModuleProvider. Hardcoding BUNDLED
                // here — as this did before Phase 3 — meant a module flipped onto splits
                // was still handed to the bundled provider, which reports it installed
                // and then fails to find a class that is not on the device yet.
                delivery.set(if (onDemand) FEATURE_SPLIT_DELIVERY else BUNDLED_DELIVERY)
            }

            if (onDemand) {
                verifySplitName(namespace)
                applyDistManifest(android, distManifestTask)
            } else {
                // Packaged with the module only when the module is packaged with the
                // base. For a split it would be both invisible before install and a
                // duplicate asset path across base and split afterwards.
                android.sourceSets.getByName("main").assets.srcDir(descriptorTask)
            }
            tasks.named("preBuild").configure {
                dependsOn(keepRulesTask, descriptorTask)
                if (onDemand) dependsOn(distManifestTask)
            }

            val keepFile = generatedRoot.get().dir("proguard").file("omnideck-module.pro").asFile
            when (android) {
                is LibraryExtension -> android.defaultConfig.consumerProguardFiles(keepFile)
                is DynamicFeatureExtension -> android.defaultConfig.proguardFiles(keepFile)
                else -> Unit
            }
        }
    }

    /**
     * Play derives a split's name from the Gradle project name, while the kernel
     * derives the split it asks Play for from the module id (`ModuleId.splitName`).
     * Those are two independent derivations of the same string, and a mismatch is
     * invisible until Play answers `MODULE_UNAVAILABLE` on a real device — so it is
     * checked here, at the moment a module is put on the on-demand path.
     */
    private fun Project.verifySplitName(namespace: String) {
        val fromModuleId = namespace.substringAfterLast('.')
        check(fromModuleId == name) {
            "$path is delivered on demand, so its directory name must match the last segment of " +
                "its namespace: Play will name the split '$name', but the kernel will ask for " +
                "'$fromModuleId' (from namespace '$namespace'). Rename the directory to " +
                "'$fromModuleId', or change the namespace to end in '$name'."
        }
    }

    /**
     * Gives the split a `<dist:delivery><dist:on-demand/></dist:delivery>` manifest.
     *
     * There is no Gradle DSL for this — delivery is manifest-only — and **the default
     * is install-time**. A dynamic feature with no `<dist:module>` block ships with the
     * base APK, so `SplitInstallManager` reports it already installed and the entire
     * acquisition path is silently never exercised: the build looks right, the app
     * looks right, and nothing is on demand. Generating it is what makes
     * `-Pomnideck.dynamicModules=` mean what it says.
     *
     * A module that has its own `AndroidManifest.xml` is a hard failure rather than a
     * silent overwrite. AGP's source set holds exactly one manifest, so pointing it
     * here would drop whatever the author declared — a lost `<queries>` or
     * `<provider>` that fails at runtime, on a device, with nothing pointing back to
     * this line.
     */
    private fun Project.applyDistManifest(
        android: CommonExtension<*, *, *, *, *, *>,
        task: TaskProvider<GenerateDistManifestTask>,
    ) {
        val authored = file("src/main/AndroidManifest.xml")
        check(!authored.exists()) {
            "$path is delivered on demand and also declares its own AndroidManifest.xml. " +
                "omnideck.module generates that manifest to mark the split on-demand, and an " +
                "Android source set holds only one — so yours would be dropped. Add the " +
                "<dist:module> block below to your manifest and remove this module from " +
                "omnideck.dynamicModules, or move the declarations out of the manifest.\n" +
                distManifestFor(name)
        }
        android.sourceSets.getByName("main").manifest
            .srcFile(task.get().outputDir.get().file("AndroidManifest.xml").asFile)
    }

    private companion object {
        /** Written verbatim into the descriptor and parsed back as `DeliveryKind`. */
        const val BUNDLED_DELIVERY = "BUNDLED"
        const val FEATURE_SPLIT_DELIVERY = "FEATURE_SPLIT"
    }
}

/**
 * The resource name the base APK must define for a split's Play-facing title.
 *
 * Shared by the module plugin, which references it, and the application plugin, which
 * generates it — `dist:title` has to resolve in the *base* module, because Play reads
 * it to name the download in its own confirmation dialog before the split exists.
 */
internal fun moduleTitleResource(splitName: String) = "omnideck_module_title_$splitName"

internal fun distManifestFor(splitName: String): String =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- Generated by omnideck.module (OD-301) — DO NOT EDIT. -->
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
        xmlns:dist="http://schemas.android.com/apk/distribution">

        <dist:module
            dist:instant="false"
            dist:title="@string/${moduleTitleResource(splitName)}">
            <!--
              Without this the split defaults to install-time delivery and ships with
              the base APK, which makes every on-demand code path unreachable.
            -->
            <dist:delivery>
                <dist:on-demand />
            </dist:delivery>
            <dist:fusing dist:include="true" />
        </dist:module>
    </manifest>

    """.trimIndent()

abstract class GenerateDistManifestTask : DefaultTask() {
    @get:Input abstract val splitName: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        outputDir.get().asFile.apply { mkdirs() }
            .resolve("AndroidManifest.xml")
            .writeText(distManifestFor(splitName.get()))
    }
}

abstract class GenerateKeepRulesTask : DefaultTask() {
    @get:Input abstract val moduleId: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val id = moduleId.get()
        outputDir.get().asFile.apply { mkdirs() }
            .resolve("omnideck-module.pro")
            .writeText(
                """
                # Generated by omnideck.module — DO NOT EDIT.
                # The Shell loads this class reflectively after a split install
                # (architecture.md §7.2, OD-304).
                -keep class $id.ModuleEntryPoint { public <init>(); }
                -keepnames class $id.ModuleEntryPoint

                """.trimIndent(),
            )
    }
}

abstract class GenerateDescriptorTask : DefaultTask() {
    @get:Input abstract val moduleId: Property<String>

    @get:Input abstract val gradlePath: Property<String>

    /**
     * The `DeliveryKind` name the kernel reads to pick a `ModuleProvider` (OD-301).
     * It is derived from how this project was configured, never declared by hand:
     * a descriptor that disagreed with the build would send the Shell to the wrong
     * provider, which fails as a missing class rather than as a wrong setting.
     */
    @get:Input abstract val delivery: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val id = moduleId.get()
        // Namespaced filename so asset merging across many modules never collides.
        outputDir.get().asFile.resolve("omnideck/modules").apply { mkdirs() }
            .resolve("$id.properties")
            .writeText(
                """
                # Generated by omnideck.module — DO NOT EDIT.
                id=$id
                entryPoint=$id.ModuleEntryPoint
                delivery=${delivery.get()}
                gradlePath=${gradlePath.get()}

                """.trimIndent(),
            )
    }
}
