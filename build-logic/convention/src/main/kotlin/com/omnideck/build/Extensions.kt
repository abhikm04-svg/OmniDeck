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

        // "Something newer exists" checks. These compare against whatever the machine
        // running lint happens to know about, so with warningsAsErrors on they make the
        // build non-reproducible: identical source goes red purely because a runner has
        // a newer SDK or an upstream release happened. OldTargetApi is the sharpest
        // case — CI runners ship a newer platform than a typical dev box, so it fails
        // in CI while passing locally on the same commit.
        //
        // Dependency versions are pinned deliberately in libs.versions.toml and moved
        // by reviewed update PRs; the target-API bump is a scheduled release task
        // verified against the Play Console requirement at the train, not a per-PR gate.
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            "OldTargetApi",
        )
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

    configureManagedDevices(this)

    buildFeatures {
        buildConfig = false
    }
}

/**
 * Gradle Managed Devices — AGP-provisioned, source-controlled emulators, added to
 * close the gap OD-317/OD-318/OD-303/OD-307 were carried on: a physical phone is not
 * a reproducible reference and, on the HyperOS unit this repo had been verified
 * against, actively refuses two things CI needs (a Gradle *session* install, and the
 * `profileinstaller` broadcast a Baseline Profile check depends on — see CLAUDE.md,
 * "What is not verified here"). Neither problem exists on a GMD: AGP owns the whole
 * lifecycle, so there is no OEM install path or broadcast policy to fight.
 *
 * Two devices, not one, because Phase 3's actual ask (OD-303) is a *sweep* — API
 * 26 (`minSdk`, ADR decision D-3) through the compile/target ceiling — not a single
 * data point:
 *
 *  - **`pixel6Api34`** is the OmniDeck reference device. `architecture.md` QA-1..QA-3
 *    name a physical "Pixel 6a"; no such system image exists for a GMD, so a Pixel 6
 *    device profile at API 34 — the closest Google-published pairing — stands in for
 *    it. Every budget recorded from here on (§16) cites this id, not whatever phone
 *    happened to be plugged in. `aosp-atd` is an Automated Test Device image: built
 *    for unattended CI use (faster boot, no Play Store licence prompt), which is all
 *    OmniDeck's connected tests and macrobenchmarks ever need from it.
 *  - **`apiFloorPixel2`** anchors the low end of the sweep at `minSdk`. No ATD image
 *    exists this far back, so it uses a plain `aosp` image instead.
 *
 * Grouped as `omnideckSweep` so both run from one invocation:
 * `./gradlew :app:omnideckSweepGroupConnectedCheck`. Either device alone:
 * `./gradlew :app:pixel6Api34DebugAndroidTest` (swap the device id for the other).
 */
internal fun Project.configureManagedDevices(ext: CommonExtension<*, *, *, *, *, *>) = with(ext) {
    testOptions {
        managedDevices {
            // Called directly on the container, not as a `localDevices { ... }`
            // block: that sugar is a Kotlin DSL extension (`org.gradle.kotlin.dsl`)
            // that ordinary `.gradle.kts` scripts get for free but a precompiled
            // script plugin does not, and the compiler's fallback candidate for the
            // unresolved call is a stdlib `DeepRecursiveFunction` overload whose
            // error has nothing to do with the real problem.
            //
            // `localDevices`, not `devices` — `devices` is the polymorphic container
            // AGP itself populates (it is where a *registered* device ends up, local
            // or otherwise); `localDevices` is the typed `ManagedVirtualDevice`
            // container a build actually declares into.
            if (localDevices.findByName("pixel6Api34") == null) {
                localDevices.create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
            if (localDevices.findByName("apiFloorPixel2") == null) {
                localDevices.create("apiFloorPixel2") {
                    device = "Pixel 2"
                    apiLevel = 26
                    systemImageSource = "aosp"
                }
            }
            if (groups.findByName("omnideckSweep") == null) {
                groups.create("omnideckSweep") {
                    targetDevices.add(allDevices.getByName("pixel6Api34"))
                    targetDevices.add(allDevices.getByName("apiFloorPixel2"))
                }
            }
        }
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

// ---------------------------------------------------------------------------
// On-demand delivery (Phase 3, OD-301).
//
// ADR-001's claim is that a module's delivery mechanism is a *deployment* decision,
// not a design one. That is only true if flipping it costs no source change, so the
// switch is a Gradle property read here and nowhere else, and everything downstream —
// which plugin a module gets, where its discovery descriptor is packaged, what
// DeliveryKind the kernel reads at runtime — is derived from it.
// ---------------------------------------------------------------------------

/** `-Pomnideck.dynamicModules=notes,finance`. Gradle project names, not module ids. */
internal const val DYNAMIC_MODULES_PROPERTY = "omnideck.dynamicModules"

/**
 * `-Pomnideck.testBuildType=benchmark`. Which build type androidTest is compiled for
 * (OD-304) — AGP allows exactly one, and defaults it to the unminified debug build.
 */
internal const val TEST_BUILD_TYPE_PROPERTY = "omnideck.testBuildType"

/**
 * The Gradle configuration a feature module publishes its discovery descriptor on,
 * and the Shell resolves for the on-demand ones.
 *
 * Consumed by name rather than by attribute matching, deliberately. An Android
 * project exposes dozens of consumable variants, and resolving one custom attribute
 * against them relies on Gradle's disambiguation preferring the exact match — which
 * is true but is a subtlety to discover from a "cannot choose between variants"
 * error at the moment someone flips a module on demand. Naming the configuration has
 * no matching algorithm to be wrong about.
 */
internal const val MODULE_DESCRIPTOR_CONFIGURATION = "omnideckModuleDescriptor"

/** The Shell's resolvable end of [MODULE_DESCRIPTOR_CONFIGURATION]. */
internal const val ON_DEMAND_DESCRIPTORS_CONFIGURATION = "omnideckOnDemandModuleDescriptors"

/** Project names listed for on-demand delivery. Empty in an ordinary build. */
internal fun Project.onDemandModuleNames(): Set<String> =
    providers.gradleProperty(DYNAMIC_MODULES_PROPERTY)
        .getOrElse("")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

/**
 * True when *this* project is a feature module the build has been told to deliver
 * on demand. Scoped to `:modules:` so a platform library can never be turned into a
 * Play split by a stray property value.
 */
internal fun Project.isOnDemandModule(): Boolean =
    path.startsWith(":modules:") && name in onDemandModuleNames()
