package com.omnideck.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Module activation: tile tap to the module's first frame (OD-213).
 *
 * The budget is 400 ms for an already-installed module (architecture.md §16). It is
 * the number that decides whether a super-app feels like one app or like a launcher
 * for several slow ones, and it is the first thing that regresses as a module's
 * `initialize` grows.
 *
 * No module is named. The benchmark taps the first tile on the grid, so it measures
 * whatever the build contains and keeps working as modules come and go.
 */
// TraceSectionMetric is still experimental. It is the only way to measure the
// kernel's own activation span rather than a wall-clock approximation timed from
// outside the process, which is what the 400 ms budget is actually about.
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ModuleActivationBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun activateFirstModule() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            // Emitted by the kernel's telemetry span around load + initialize +
            // register, so the number is the platform's own work rather than an
            // approximation timed from outside.
            TraceSectionMetric(ACTIVATION_SECTION, targetPackageOnly = true),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.DEFAULT,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.textContains(HOME_TITLE)), TIMEOUT_MS)
        },
    ) {
        val tile = device.wait(Until.findObject(By.descContains(TILE_MARKER)), TIMEOUT_MS)
        tile?.click()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.omnideck.shell"
        const val HOME_TITLE = "OmniDeck"

        /** Every ModuleTile's content description ends with its accessibility state. */
        const val TILE_MARKER = "Installed"

        const val ACTIVATION_SECTION = "module.activate"
        const val ITERATIONS = 10
        const val TIMEOUT_MS = 10_000L
    }
}
