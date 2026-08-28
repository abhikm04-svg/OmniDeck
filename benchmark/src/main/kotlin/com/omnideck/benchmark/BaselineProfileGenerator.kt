package com.omnideck.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the Baseline Profile shipped in the release bundle (OD-214).
 *
 * A Baseline Profile is a list of the methods worth AOT-compiling at install time.
 * Without one, everything on the startup path is interpreted or JIT-compiled on first
 * run, which is most of the difference between a cold start that feels instant and
 * one that does not — typically 20-30% on the first launches after install.
 *
 * It matters more here than in an ordinary app: a module's code is reached
 * reflectively or through a split, so it is exactly the code an install-time profile
 * would otherwise miss. Per-module profiles merged into the bundle are OD-607; this
 * covers the Shell's own path plus whatever the first module does on activation.
 *
 * ```
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * The result is written into `:app` and committed, so a build machine with no device
 * still produces a profiled release.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    // includeInStartupProfile also emits a *startup* profile, for dex layout. Nothing
    // consumes it today: with the Baseline Profile plugin off — the default here —
    // `mergeReleaseStartupProfile` reads no source set and writes nothing, and only
    // `baseline-prof.txt` reaches the release build. It is recorded anyway because it
    // is free, and because a startup profile from a *single* interaction comes out
    // byte-identical to the baseline profile, which is worth knowing before anyone
    // wires it up expecting a layout hint from it.
    @Test
    fun startupAndFirstModule() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // The grid, not just the Activity: discovery, the generated registry and the
        // first composition are all on the path a user waits for.
        device.wait(Until.hasObject(By.textContains(HOME_TITLE)), TIMEOUT_MS)

        // Activating a module pulls its entry point, its initialize and its first
        // screen into the profile. Whichever module is present — naming one would
        // make the profile wrong for every other build.
        // checkNotNull, not `?.`: a missed lookup here would record a profile that
        // silently omits every module method, and the only symptom would be a slower
        // first activation in production.
        checkNotNull(device.wait(Until.findObject(By.descContains(TILE_MARKER)), TIMEOUT_MS)) {
            "No installed module tile on the home grid — the recorded profile would " +
                "cover the Shell only."
        }.click()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.omnideck.shell"
        const val HOME_TITLE = "OmniDeck"
        const val TILE_MARKER = "Installed"
        const val TIMEOUT_MS = 10_000L
    }
}
