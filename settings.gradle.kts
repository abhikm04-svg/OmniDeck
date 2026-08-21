@file:Suppress("UnstableApiUsage")

import org.gradle.caching.http.HttpBuildCache

pluginManagement {
    // build-logic is a composite build: it supplies every `omnideck.*` convention plugin.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "omnideck"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ---------------------------------------------------------------------------
// Build cache (OD-013). Local cache is always on (org.gradle.caching=true in
// gradle.properties). Remote cache node is opt-in via env vars so a machine
// without one configured still builds correctly — point OMNIDECK_CACHE_URL at
// a Gradle Build Cache Node (self-hosted) or a Develocity remote cache once
// one exists (implementation_plan.md §19 costs this at $0 self-hosted).
// ---------------------------------------------------------------------------
buildCache {
    local {
        isEnabled = true
    }
    System.getenv("OMNIDECK_CACHE_URL")?.let { cacheUrl ->
        remote<HttpBuildCache> {
            url = uri(cacheUrl)
            isPush = System.getenv("CI") == "true"
            val cacheUsername = System.getenv("OMNIDECK_CACHE_USERNAME")
            val cachePassword = System.getenv("OMNIDECK_CACHE_PASSWORD")
            if (cacheUsername != null && cachePassword != null) {
                credentials {
                    username = cacheUsername
                    password = cachePassword
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Host shell + platform
// ---------------------------------------------------------------------------
include(":app")

include(":platform:omnideck-sdk-core")
include(":platform:omnideck-sdk")
include(":platform:core")
include(":platform:design-system")
include(":platform:kernel")
include(":platform:testing")

// ---------------------------------------------------------------------------
// Developer tooling
// ---------------------------------------------------------------------------
include(":tools:lint-rules")

// ---------------------------------------------------------------------------
// Module auto-discovery  (architecture.md G1 — "add a module without touching
// Shell source"). Any directory under modules/ containing a build.gradle.kts is
// included automatically and wired into :app by the omnideck.android.application
// convention plugin. Adding a module = creating a directory. Nothing else.
//
// Verified continuously by the plug-and-play fitness test (OD-212).
// ---------------------------------------------------------------------------
val discoveredModules: List<String> =
    java.io
        .File(settingsDir, "modules")
        .listFiles { f -> f.isDirectory && java.io.File(f, "build.gradle.kts").exists() }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

discoveredModules.forEach { include(":modules:$it") }

// Handed to the application convention plugin, which turns each into a project
// dependency (bundled) or a dynamicFeatures entry (on-demand, from Phase 3).
System.setProperty("omnideck.modules", discoveredModules.joinToString(","))

logger.lifecycle("OmniDeck: discovered ${discoveredModules.size} module(s): $discoveredModules")

// ---------------------------------------------------------------------------
// Satellites build their own App Bundles against the *published* SDK artifact,
// not project dependencies — that is what keeps them genuinely decoupled.
// Enabled in Phase 5 (OD-501).
// ---------------------------------------------------------------------------
// include(":satellites:scanner")
