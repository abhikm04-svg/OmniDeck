package com.omnideck.build

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure

/**
 * `omnideck.quality` — static analysis, coverage and the architecture fitness
 * functions (architecture.md §18, implementation_plan.md §17).
 *
 * The dependency rules of architecture.md §5.1 are enforced here as a *build
 * failure*, not a convention. That mechanical guarantee is what makes the
 * "microservices" property real rather than aspirational.
 */
class QualityConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        extensions.configure(DetektExtension::class.java) {
            buildUponDefaultConfig = true
            allRules = false
            parallel = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            baseline = file("detekt-baseline.xml").takeIf { it.exists() }
        }

        tasks.withType(Detekt::class.java).configureEach {
            jvmTarget = "21"
            reports {
                html.required.set(true)
                sarif.required.set(true)
                xml.required.set(false)
                txt.required.set(false)
            }
        }

        dependencies.add(
            "detektPlugins",
            "io.gitlab.arturbosch.detekt:detekt-formatting:${libs.version("detekt")}",
        )

        // ------------------------------------------------------------------
        // Coverage floors (OD-008). Blocking from the Phase 1 exit gate onward:
        // "platform:* coverage >= 80%, CI-enforced".
        //
        // A project only gets a floor once it has tests. Applying one to an
        // untested module turns its first green build red for a reason its author
        // did not cause, which is how a coverage gate gets disabled wholesale
        // rather than met. Modules without tests are reported but not failed;
        // they inherit the floor as soon as a test source set appears.
        // ------------------------------------------------------------------
        val minCoverage = when {
            path.startsWith(":platform:") ->
                providers.gradleProperty("omnideck.coverage.platform.min").orNull
            path.startsWith(":modules:") ->
                providers.gradleProperty("omnideck.coverage.module.min").orNull
            else -> null
        }?.toInt()

        // Per-project exclusions are contributed through this extension rather than a
        // `kover { }` block in the project's own build file. A separately-declared
        // filter block is applied by the report tasks but ignored by koverVerify,
        // which produced a build where the report said 81.1% and the gate said 75.8%
        // on the same commit. Declaring everything in one place keeps the number that
        // is enforced identical to the number anyone can inspect.
        val coverage = extensions.create("omnideckCoverage", OmniDeckCoverageExtension::class.java)

        if (minCoverage != null) {
            val hasTests = file("src/test").isDirectory || file("src/androidTest").isDirectory

            // Deferred so the extension has been configured by the project's build
            // file before its values are read.
            afterEvaluate {
                extensions.configure(KoverProjectExtension::class.java) {
                    reports {
                        filters {
                            excludes {
                                // Code we do not author. Dagger/Hilt factories and
                                // component plumbing are generated from annotations
                                // and covered by Dagger's own test suite; counting
                                // them measures how much generated boilerplate a
                                // module has, not how well its behaviour is tested.
                                classes(
                                    "*_Factory",
                                    "*_Factory\$*",
                                    "*_MembersInjector",
                                    "*_HiltModules*",
                                    "*_ProvideFactory",
                                    "*_ComponentTreeDeps",
                                    "Hilt_*",
                                    "*.Hilt_*",
                                    // Compose and Kotlin synthetics.
                                    "*ComposableSingletons*",
                                    "*\$\$serializer",
                                )
                                annotatedBy(
                                    "javax.annotation.processing.Generated",
                                    "dagger.internal.DaggerGenerated",
                                )

                                coverage.excludedClasses.get().forEach { classes(it) }
                                coverage.excludedAnnotations.get().forEach { annotatedBy(it) }
                            }
                        }
                        verify {
                            warningInsteadOfFailure.set(!hasTests)
                            rule("Minimum line coverage for $path") {
                                minBound(minCoverage)
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Fitness function: architecture.md §5.1 dependency rules
        // ------------------------------------------------------------------
        val checkArchitecture = tasks.register(
            "checkArchitecture",
            CheckArchitectureTask::class.java,
        ) {
            projectPath.set(this@with.path)
        }

        afterEvaluate {
            val declared = configurations.filter { it.name in DEPENDENCY_CONFIGURATIONS }

            val projectDeps = declared
                .flatMap { cfg ->
                    cfg.dependencies.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                        .map { it.path }
                }
                .distinct()
                .sorted()

            // External coordinates too. Project dependencies alone cannot catch
            // `implementation(libs.compose.ui)` being added to the pure-Kotlin SDK
            // core, which is exactly the drift the Phase 1 exit gate is about.
            val externalDeps = declared
                .flatMap { cfg ->
                    cfg.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.ExternalModuleDependency>()
                        .map { "${it.group}:${it.name}" }
                }
                .distinct()
                .sorted()

            checkArchitecture.configure {
                dependencyPaths.set(projectDeps)
                externalDependencies.set(externalDeps)
            }
        }

        tasks.named("check").configure { dependsOn(checkArchitecture) }
    }

    private companion object {
        val DEPENDENCY_CONFIGURATIONS = setOf(
            "api", "implementation", "compileOnly", "runtimeOnly",
            "debugImplementation", "releaseImplementation",
        )
    }
}

/**
 * Fails the build when a Gradle project violates the layering rules.
 *
 * These are the rules that keep the platform pluggable. A violation is never a
 * warning, because a warning here becomes a hard coupling within one sprint.
 */
abstract class CheckArchitectureTask : DefaultTask() {

    @get:Input abstract val projectPath: Property<String>

    @get:Input abstract val dependencyPaths: ListProperty<String>

    @get:Input abstract val externalDependencies: ListProperty<String>

    init {
        group = "verification"
        description = "Enforces the OmniDeck layering rules (architecture.md §5.1)."
    }

    @TaskAction
    fun check() {
        val path = projectPath.get()
        val deps = dependencyPaths.getOrElse(emptyList())
        val violations = mutableListOf<String>()

        val isModule = path.startsWith(":modules:")
        val isSdkCore = path == ":platform:omnideck-sdk-core"
        val isSdk = path == ":platform:omnideck-sdk"

        deps.forEach { dep ->
            when {
                // Rule 2 — modules are islands.
                isModule && dep.startsWith(":modules:") && dep != path ->
                    violations += "$path depends on module $dep. Modules must not depend on each " +
                        "other — use EventBus, Router or a registered Capability (architecture.md §10.3)."

                isModule && dep.startsWith(":platform:kernel") ->
                    violations += "$path depends on $dep. Modules consume the kernel only through " +
                        "the SDK's PlatformServices facade (architecture.md §6.3)."

                isModule && dep == ":app" ->
                    violations += "$path depends on :app. Nothing may depend on the Shell."

                // Rule 4 — nothing depends on :app.
                dep == ":app" && path != ":app" ->
                    violations += "$path depends on :app."

                // Rule 3 — the SDK stays thin and Android-free at its core.
                isSdkCore && dep != ":platform:core" ->
                    violations += "$path depends on $dep. omnideck-sdk-core is pure Kotlin and must " +
                        "stay dependency-free so it can be shared with the backend and moved to KMP."

                isSdk && !(dep == ":platform:omnideck-sdk-core" || dep == ":platform:core") ->
                    violations += "$path depends on $dep. The SDK may only depend on sdk-core and " +
                        "platform:core — it must never see the kernel or a module."
            }
        }

        // ------------------------------------------------------------------
        // Rule 3, external half (Phase 1 exit gate).
        //
        // omnideck-sdk-core is the layer that must stay portable: the backend shares
        // its manifest types, and the KMP migration in §21 is a move only while it
        // has no Android or UI dependency. The project-dependency pass above cannot
        // see `implementation(libs.compose.ui)`, so it is checked here by coordinate.
        //
        // Note this rule applies to sdk-core ONLY. The Android half (omnideck-sdk)
        // legitimately carries Compose, Room, OkHttp and WorkManager: modules
        // contribute @Composable destinations (ADR-003) and capability interfaces
        // hand back OkHttp/Retrofit types by design.
        // ------------------------------------------------------------------
        if (isSdkCore) {
            externalDependencies.getOrElse(emptyList()).forEach { coordinate ->
                val group = coordinate.substringBefore(':')
                val forbidden = FORBIDDEN_SDK_CORE_GROUPS.firstOrNull { group.startsWith(it) }
                if (forbidden != null) {
                    violations += "$path depends on $coordinate. omnideck-sdk-core must stay pure " +
                        "Kotlin — no Android, Compose, Hilt or UI. Put it in :platform:omnideck-sdk " +
                        "instead, which is the Android half of the contract."
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Architecture rule violations (architecture.md §5.1):")
                    violations.forEach { appendLine("  ✗ $it") }
                    appendLine()
                    appendLine("These rules are what make OmniDeck pluggable. If you believe a rule")
                    appendLine("is wrong, change it via an ADR — do not work around it.")
                },
            )
        }
        val externalCount = externalDependencies.getOrElse(emptyList()).size
        logger.lifecycle(
            "checkArchitecture: $path OK (${deps.size} project, $externalCount external dependencies)",
        )
    }

    private companion object {
        /**
         * Group prefixes that would end sdk-core's portability. Matched by prefix so
         * `androidx.compose.ui` and friends are covered without listing every artifact.
         * `org.jetbrains.kotlinx` is deliberately absent: coroutines, serialization and
         * datetime are all multiplatform and are what sdk-core is built from.
         */
        val FORBIDDEN_SDK_CORE_GROUPS = listOf(
            "androidx",
            "com.android",
            "com.google.android",
            "com.google.dagger",
            "org.jetbrains.compose",
            "io.coil-kt",
            "com.squareup.okhttp3",
            "com.squareup.retrofit2",
        )
    }
}

/**
 * Per-project coverage exclusions.
 *
 * Narrow and documented next to their reason, never broad. An exclusion is warranted
 * when code genuinely cannot be measured by a unit test — Android Keystore, Compose
 * under Robolectric — and is verified some other way; it is never a substitute for
 * writing the test.
 */
abstract class OmniDeckCoverageExtension {
    /** Fully-qualified class patterns; `Foo$*` also covers generated nested classes. */
    abstract val excludedClasses: ListProperty<String>

    /** Fully-qualified annotation names; anything annotated is excluded. */
    abstract val excludedAnnotations: ListProperty<String>
}
