package com.omnideck.shell

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.android.play.core.splitcompat.SplitCompat
import com.omnideck.kernel.services.TelemetryHub
import com.omnideck.kernel.services.TelemetrySignal
import com.omnideck.sdk.capability.TelemetryService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OmniDeckApplication : Application() {

    @Inject lateinit var telemetryHub: TelemetryHub

    @Inject lateinit var telemetry: TelemetryService

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
     * An in-process module crash is attributed to whichever module owns the topmost
     * frame in the stack, then handed to the previous handler so Crashlytics still
     * reports it. Attribution is the prerequisite for per-module error budgets and
     * for the quarantine counter to mean anything.
     */
    private fun installCrashAttribution() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val owner = throwable.stackTrace
                    .firstOrNull { it.className.startsWith(MODULE_PACKAGE_PREFIX) }
                    ?.className
                telemetry.recordError(
                    throwable,
                    message = "uncaught:${owner ?: "shell"}",
                    fatal = true,
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val MODULE_PACKAGE_PREFIX = "com.omnideck.module."
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
