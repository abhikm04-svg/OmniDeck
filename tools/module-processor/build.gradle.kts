// `:tools:module-processor` — OD-202, architecture.md §7.2.
//
// Turns "the Shell finds modules by class name at runtime" into "the Shell holds a
// compile-time map of constructors". Reflection stays as the fallback for splits that
// arrive after the base APK was built (Phase 3), but a bundled module is now on the
// startup-critical path with no Class.forName in it — and a module whose entry point
// has the wrong shape fails to compile instead of failing to load on a user's device.

plugins {
    id("omnideck.jvm.library")
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
}
