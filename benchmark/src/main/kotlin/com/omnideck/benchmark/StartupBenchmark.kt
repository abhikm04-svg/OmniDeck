package com.omnideck.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start measurement (OD-213).
 *
 * The budget is p90 <= 1200 ms on the reference device (architecture.md §16), and the
 * point of measuring it in Phase 2 rather than Phase 6 is that a startup budget only
 * works if it starts at zero: by the time an app is slow, no single change is
 * responsible and no single change can fix it.
 *
 * Two compilation modes are measured deliberately. [CompilationMode.None] is the
 * pessimistic floor; [BaselineProfileMode.Require] is what a user who installed from
 * Play actually gets, and the gap between them is the value of OD-214.
 *
 * ```
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutCompilation() = measureStartup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() =
        measureStartup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun measureStartup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Waiting for the grid, not just for the Activity: the Shell's first frame is
        // a splash held until module discovery finishes, so stopping at
        // startActivityAndWait would measure the splash and call it a fast start.
        device.wait(Until.hasObject(By.textContains(HOME_TITLE)), FRAME_TIMEOUT_MS)
    }

    private companion object {
        /** The debug applicationIdSuffix is deliberately absent: benchmarks run the release-like build. */
        const val TARGET_PACKAGE = "com.omnideck.shell"
        const val HOME_TITLE = "OmniDeck"
        const val ITERATIONS = 10
        const val FRAME_TIMEOUT_MS = 10_000L
    }
}
