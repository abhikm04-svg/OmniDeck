package com.omnideck.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
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
            // Emitted by the kernel's telemetry span around load + initialize +
            // register, so the number is the platform's own work rather than an
            // approximation timed from outside.
            //
            // FrameTimingMetric was here too and has been removed. A screen
            // transition gives it a handful of frames to work with, and on the
            // devices this has been run on it intermittently found none at all —
            // failing the whole run on "no renderthread slices" and taking the
            // activation number down with it, on a different iteration each time.
            // Jank belongs to a benchmark that scrolls; this one measures a span.
            TraceSectionMetric(ACTIVATION_SECTION, targetPackageOnly = true),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.DEFAULT,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            // The Shell is a single Activity (ADR-003) and restores wherever the user
            // last was, so from the second iteration on it resumes *inside* the module
            // and the grid is not on screen. Walk back to it.
            var remaining = MAX_BACK
            while (remaining-- > 0 && !device.hasObject(By.descContains(TILE_MARKER))) {
                device.pressBack()
                device.waitForIdle()
            }
            check(device.wait(Until.hasObject(By.textContains(HOME_TITLE)), TIMEOUT_MS)) {
                "The Shell did not get back to its home grid within $TIMEOUT_MS ms."
            }
        },
    ) {
        // checkNotNull, not `?.`: a lookup that silently misses leaves the measured
        // window with nothing in it, and the run reports "no renderthread slices"
        // rather than the real problem. Ask for the tile and fail naming it.
        val tile = checkNotNull(device.wait(Until.findObject(By.descContains(TILE_MARKER)), TIMEOUT_MS)) {
            "No installed module tile on the home grid. This benchmark names no module " +
                "(goal G1) and taps the first installed one, so an empty grid means the " +
                "build contains no bundled module — not that activation is slow."
        }
        tile.click()
        // The grid disappearing *is* the module's first frame. Waiting for idle
        // instead would close the measured window before the module had drawn, which
        // is the difference between measuring activation and measuring a tap.
        device.wait(Until.gone(By.descContains(TILE_MARKER)), TIMEOUT_MS)
        // UiAutomator reports the grid gone the moment the hierarchy changes, which
        // can be a frame before RenderThread has drawn what replaced it. Without this
        // the trace occasionally closes with no frame in it at all, and the run fails
        // on "no renderthread slices" rather than on anything about activation.
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

        /** Deep enough to unwind any module's own back stack, shallow enough to end. */
        const val MAX_BACK = 5
    }
}
