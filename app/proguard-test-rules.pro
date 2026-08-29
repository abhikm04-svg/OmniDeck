# R8 rules for the *instrumented test* APK, not the app (OD-304).
#
# Applied only when androidTest is built against a minified build type — the
# `-Pomnideck.testBuildType=benchmark` run that exists to prove a module still loads
# after R8 has been over it. AGP minifies the test APK alongside the app, so the test
# APK's own dependencies have to satisfy R8 too.
#
# Nothing here relaxes anything about the *app*: these rules are not in
# proguard-rules.pro and never reach a shipped artifact.

# Truth pulls in error-prone's annotations, which reference the javax.lang.model
# types from the compiler API. Those exist at compile time and not on Android, and
# nothing at runtime reads them — an annotation R8 cannot resolve is a warning, not a
# missing dependency.
-dontwarn javax.lang.model.element.Modifier

# OmniDeckTestRunner hands Hilt's test Application to the framework *by name*, so the
# only static reference R8 can see is `HiltTestApplication::class.java.name` — which
# needs the class object and not the class body. R8 shrank it away accordingly, and
# every instrumented test on a minified build died before the first one ran:
#
#   NoClassDefFoundError: Failed resolution of:
#   Ldagger/hilt/android/testing/HiltTestApplication;
#
# This is the same reflective-load hazard `omnideck.module` generates a keep rule for
# on each module's ModuleEntryPoint — the test harness had no equivalent, because
# until OD-304 nothing ever minified the test APK.
# Keeping HiltTestApplication alone is not enough, and fails identically: it extends
# `Hilt_HiltTestApplication`, which Hilt *generates* into the androidTest sources and
# which nothing references by name at all. Resolving the subclass then fails on the
# missing superclass, and the error still names the subclass — so the rule looks
# correct and the crash does not move.
#
# NOTE: these are necessary and still not sufficient. The minified instrumented-test
# APK does not currently start — the process dies in `OmniDeckTestRunner` before the
# first test, on Hilt's test Application. OD-304's actual question, "does a module
# load once R8 has been over it", was answered another way: by launching the minified
# app itself and watching a module discover, activate and render (see CLAUDE.md).
# Making the *test harness* survive minification is a separate problem from the one
# the ticket is about, and it is not solved here.
-keep class dagger.hilt.android.testing.** { *; }
-keep class dagger.hilt.android.internal.testing.** { *; }
-keep class **.Hilt_* { *; }
