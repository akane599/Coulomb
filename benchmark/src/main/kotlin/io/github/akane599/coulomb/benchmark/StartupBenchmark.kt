// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to verdict with cached data (SPEC 1.3, 16.5). Now reports the app fully drawn once
 * it shows the battery, so `timeToFullDisplayMs` is the time to verdict; `timeToInitialDisplayMs`
 * is the first frame.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Once per install: onboarding in basic mode (no privileged access on a test device), then Now
     * with a stored sample, so every measured start finds cached data.
     */
    @Before
    fun onboardAndCache() {
        device.executeShellCommand("monkey -p $targetPackage -c android.intent.category.LAUNCHER 1")
        if (device.wait(Until.hasObject(By.text("Start in basic mode")), UI_WAIT_MS) == true) {
            tap("Start in basic mode")
            tap("Continue")
            tap("Show my battery")
        }
        checkNotNull(device.wait(Until.findObject(By.res(VERDICT)), DATA_WAIT_MS)) { "Now did not show the battery" }
        device.pressHome()
    }

    @Test
    fun coldStartToVerdict() =
        rule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(StartupTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            // Keeps the trace open until Now reports fully drawn.
            checkNotNull(device.wait(Until.findObject(By.res(VERDICT)), UI_WAIT_MS)) { "no verdict" }
        }

    private fun tap(text: String) {
        checkNotNull(device.wait(Until.findObject(By.text(text)), UI_WAIT_MS)) { "\"$text\" not shown" }.click()
    }

    private val targetPackage: String =
        requireNotNull(InstrumentationRegistry.getArguments().getString("targetPackage")) {
            "targetPackage instrumentation argument missing; it comes from coulomb.applicationId"
        }

    private companion object {
        /** `NOW_VERDICT_TAG` in `:feature:now`, a resource id through `testTagsAsResourceId`. */
        const val VERDICT = "now-verdict"
        const val ITERATIONS = 10
        const val UI_WAIT_MS = 5_000L
        const val DATA_WAIT_MS = 30_000L
    }
}
