// `:tools:lint-rules` — custom Android Lint checks (OD-009, architecture.md §5.1, §14.1).
//
// Mechanises the two rules a Gradle dependency graph cannot see (the module->module
// dependency ban itself is already a build failure via `checkArchitecture` in
// omnideck.quality): no raw `Log.*` calls, and no bypassing `PermissionBroker` by
// calling the platform permission APIs directly.
//
// `lintChecks(project(":tools:lint-rules"))` wires this jar into every Android
// module via `configureAndroidCommon` (build-logic/convention/.../Extensions.kt).

plugins {
    id("omnideck.jvm.library")
}

dependencies {
    // compileOnly for main: Lint supplies these at analysis time, and packaging them
    // into the checks jar would conflict with the Lint runtime that loads it.
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    // Tests run the detectors out-of-process, so they need the API on the classpath
    // for real, not just at compile time.
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.tests)
}

tasks.jar {
    manifest {
        attributes("Lint-Registry-v2" to "com.omnideck.lint.OmniDeckIssueRegistry")
    }
}
