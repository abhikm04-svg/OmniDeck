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
