package com.omnideck.build

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Version catalog accessor — precompiled plugins cannot use the generated `libs` accessor. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No library '$alias' in libs.versions.toml") }

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalArgumentException("No version '$alias' in libs.versions.toml") }
        .requiredVersion

/**
 * Everything every OmniDeck Android module shares. Applied by the application,
 * library and feature convention plugins so no build file repeats it.
 */
internal fun Project.configureAndroidCommon(ext: CommonExtension<*, *, *, *, *, *>) = with(ext) {
    compileSdk = libs.version("compileSdk").toInt()

    defaultConfig {
        minSdk = libs.version("minSdk").toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/DEPENDENCIES",
            "META-INF/*.kotlin_module",
        )
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Custom OmniDeck rules (raw Log, raw permission calls) — OD-009. The
        // module->module dependency ban itself is enforced separately by
        // `checkArchitecture` (QualityConventionPlugin), which sees the Gradle
        // dependency graph directly.
        baseline = file("lint-baseline.xml").takeIf { it.exists() }
        sarifReport = true

        // "A newer version is available" checks. Versions are pinned deliberately in
        // libs.versions.toml and updated via Renovate PRs that CI gates (ADR-008);
        // with warningsAsErrors on, these would otherwise break the build every time
        // an upstream release happens — a red build nobody caused and nobody can fix
        // in the PR that hit it.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    this@configureAndroidCommon.dependencies.add(
        "lintChecks",
        this@configureAndroidCommon.project(":tools:lint-rules"),
    )

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        buildConfig = false
    }
}

internal fun Project.configureKotlinAndroid() {
    extensions.configure(KotlinAndroidProjectExtension::class.java) {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(providers.gradleProperty("omnideck.warningsAsErrors").map(String::toBoolean).getOrElse(true))
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
                "-Xconsistent-data-class-copy-visibility",
            )
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure(KotlinJvmProjectExtension::class.java) {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }
}

/** Unit-test stack applied uniformly (implementation_plan.md §17). */
internal fun Project.configureTestDependencies() = dependencies {
    add("testImplementation", libs.lib("junit4"))
    add("testImplementation", libs.lib("kotlinx-coroutines-test"))
    add("testImplementation", libs.lib("turbine"))
    add("testImplementation", libs.lib("truth"))
    add("testImplementation", libs.lib("mockk"))
}
