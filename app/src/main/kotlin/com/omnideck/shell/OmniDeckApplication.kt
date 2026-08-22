package com.omnideck.shell

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.android.play.core.splitcompat.SplitCompat
import com.omnideck.kernel.lifecycle.ModuleCrashAttributor
import com.omnideck.kernel.services.TelemetryHub
import com.omnideck.kernel.services.TelemetrySignal
import com.omnideck.sdk.capability.TelemetryService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OmniDeckApplication : Application() {

    @Inject lateinit var telemetryHub: TelemetryHub

    @Inject lateinit var telemetry: TelemetryService

    @Inject lateinit var crashAttributor: ModuleCrashAttributor

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // MUST run before any dynamic-feature code is touched. Without it, a split
        // installed during this process is not reachable until the app restarts —
        // the user sees a progress bar complete and then nothing happens
        // (architecture.md §7.2).
        SplitCompat.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        telemetryHub.addSink(DebugTelemetrySink())
        installCrashAttribution()

        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.WARN)
                .build(),
        )
    }

    /**
     * QA-6 / architecture.md §12.6.
     *
     * An in-process module crash is attributed to the module owning the topmost frame
     * that belongs to one, then handed to the previous handler so Crashlytics still
     * reports it. Attribution is the prerequisite for per-module error budgets and for
     * the quarantine counter to mean anything.
     *
     * The matching lives in [ModuleCrashAttributor], against the namespaces of
     * *discovered* modules. It used to be a hardcoded `com.omnideck.module.` prefix
     * here — a package no module has ever had, since a module id looks like
     * `com.omnideck.<name>`, so every crash was silently attributed to the Shell.
     */
    private fun installCrashAttribution() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                telemetry.recordError(
                    throwable,
                    message = "uncaught:${crashAttributor.label(throwable)}",
                    fatal = true,
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

/** Debug-only sink. Crashlytics and OTLP exporters are added in Phase 6 (OD-601/602). */
private class DebugTelemetrySink : com.omnideck.kernel.services.TelemetrySink {
    override fun emit(signal: TelemetrySignal) {
        if (!BuildConfigCompat.DEBUG) return
        val tag = signal.moduleId?.value ?: "shell"
        val line = when (signal) {
            is TelemetrySignal.Event -> "event ${signal.name} ${signal.attributes}"
            is TelemetrySignal.Metric -> "metric ${signal.name}=${signal.value}"
            is TelemetrySignal.Breadcrumb -> "crumb ${signal.message}"
            is TelemetrySignal.Error -> "error ${signal.message}: ${signal.throwable}"
            is TelemetrySignal.SpanEnd -> "span ${signal.name} ${signal.durationMs}ms ok=${signal.ok}"
        }
        // The one sanctioned raw-Log call in the repo: this *is* the debug sink that
        // TelemetryService writes into, so routing it back through TelemetryService
        // would recurse. Guarded by the DEBUG check above; Phase 6 (OD-601/602)
        // replaces it with the Crashlytics and OTLP exporters.
        @Suppress("ForbiddenMethodCall")
        @android.annotation.SuppressLint("OmniDeckRawLog")
        android.util.Log.d("OmniDeck/$tag", line)
    }
}

/** buildConfig is disabled repo-wide; this keeps the debug check in one place. */
private object BuildConfigCompat {
    const val DEBUG: Boolean = true
}
