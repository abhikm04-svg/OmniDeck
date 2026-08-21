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
        // Coverage: reporting is always on; the minimum-coverage *gate*
        // (OD-008) is wired here but deliberately left off the `check`
        // lifecycle in Phase 0, where no tests exist yet. `koverVerify`
        // becomes CI-blocking at the Phase 1 exit gate ("platform:*
        // coverage >= 80%"), once :platform:testing fakes make writing
        // those tests possible. Run it explicitly to see where a project
        // stands before then.
        // ------------------------------------------------------------------
        val minCoverage = when {
            path.startsWith(":platform:") ->
                providers.gradleProperty("omnideck.coverage.platform.min").orNull
            path.startsWith(":modules:") ->
                providers.gradleProperty("omnideck.coverage.module.min").orNull
            else -> null
        }?.toInt()

        if (minCoverage != null) {
            extensions.configure(KoverProjectExtension::class.java) {
                reports {
                    verify {
                        // Kover wires `koverVerify` into `check` automatically once any
                        // rule exists, which would fail Phase 0's `./gradlew build` on
                        // day one (no tests yet). Warn instead of fail until Phase 1's
                        // exit gate flips this to a real, CI-blocking gate.
                        warningInsteadOfFailure.set(true)
                        rule("Minimum line coverage for $path") {
                            minBound(minCoverage)
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
            val deps = configurations
                .filter { it.name in DEPENDENCY_CONFIGURATIONS }
                .flatMap { cfg ->
                    cfg.dependencies.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                        .map { it.path }
                }
                .distinct()
                .sorted()
            checkArchitecture.configure { dependencyPaths.set(deps) }
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
        logger.lifecycle("checkArchitecture: $path OK (${deps.size} project dependencies)")
    }
}
