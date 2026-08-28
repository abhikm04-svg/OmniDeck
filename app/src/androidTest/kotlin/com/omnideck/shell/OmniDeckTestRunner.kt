package com.omnideck.shell

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner for the Shell.
 *
 * `OmniDeckApplication` is `@HiltAndroidApp`, which means the real Application builds
 * the production object graph at process start. An instrumented test needs Hilt's
 * test application instead so it can install test bindings — without this the graph
 * is fixed before any test rule runs.
 *
 * Named in `omnideck.android.application`, so every Shell instrumented test gets it
 * with no per-module configuration.
 */
class OmniDeckTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
