// Root build file. Declares plugins on the build classpath (`apply false`) so the
// convention plugins in build-logic can apply them by id, and configures the
// repo-wide quality aggregation.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf(
                "ktlint_standard_function-naming" to "disabled", // @Composable are PascalCase
                "ktlint_standard_filename" to "disabled",
                "max_line_length" to "120",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("**/*.md", "**/.gitignore")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// Repo-wide convenience aggregates used by CI (see .github/workflows/ci.yml)
// ---------------------------------------------------------------------------
tasks.register("qualityCheck") {
    group = "verification"
    description = "Static analysis + formatting + architecture fitness, without compiling tests."

    // Only real projects carry these tasks. `:platform` and `:tools` are container
    // projects with no build file — including them asks for a task that never exists.
    val analysable = subprojects.filter { it.buildFile.exists() }

    // Task *paths* keep this lazy and configuration-cache friendly.
    dependsOn("spotlessCheck")
    dependsOn(analysable.map { "${it.path}:detekt" })
    dependsOn(analysable.map { "${it.path}:checkArchitecture" })
}
