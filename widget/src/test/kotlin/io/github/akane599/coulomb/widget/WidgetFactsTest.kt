// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.os.BatteryManager
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.DischargeSummary
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Test

class WidgetFactsTest {
    private fun battery(
        plugged: Boolean,
        status: Int,
        level: Int = 74,
    ) = BatteryNow(level, null, plugged, status, 300, 4_000, screenOn = true, wallMs = 6 * 3_600_000L)

    private fun window(vararg mah: Double) =
        WindowDrains(
            fromWallMs = 0,
            toWallMs = 1,
            measuredMah = 1_000.0,
            measuredProvenance = Provenance.MEASURED,
            modeledMah = mah.sum(),
            apps = mah.mapIndexed { i, m -> AppDrain(10_000 + i, "App $i", false, null, m) },
            otherMah = 0.0,
        )

    @Test
    fun thePowerStateFollowsThePlugAndTheStatus() {
        assertThat(PowerState.of(battery(false, BatteryManager.BATTERY_STATUS_DISCHARGING))).isEqualTo(PowerState.ON_BATTERY)
        assertThat(PowerState.of(battery(true, BatteryManager.BATTERY_STATUS_CHARGING))).isEqualTo(PowerState.CHARGING)
        assertThat(PowerState.of(battery(true, BatteryManager.BATTERY_STATUS_FULL))).isEqualTo(PowerState.FULL)
        // A charge limit holds the level: plugged, not charging.
        assertThat(PowerState.of(battery(true, BatteryManager.BATTERY_STATUS_NOT_CHARGING))).isEqualTo(PowerState.PLUGGED)
    }

    @Test
    fun theLevelHasAStoryOnlyOnBattery() {
        val discharge =
            DischargeSummary(
                startWallMs = 0,
                startLevel = 100,
                levelPct = 74,
                screenOn = null,
                screenOff = null,
                deepSleep = null,
                timeLeft = null,
            )
        val onBattery = BatteryReading(battery(false, BatteryManager.BATTERY_STATUS_DISCHARGING), discharge, emptyList(), emptyList())
        val facts = WidgetFacts.level(onBattery, window(310.0))
        assertThat(facts.story?.span?.levelDropPct).isEqualTo(26)
        assertThat(facts.story?.span?.durationMs).isEqualTo(6 * 3_600_000L)

        val charging = onBattery.copy(battery = battery(true, BatteryManager.BATTERY_STATUS_CHARGING))
        assertThat(WidgetFacts.level(charging, null).story).isNull()
    }

    @Test
    fun theTopDrainsAreTheThreeLargestWithTheirShareOfTheWindow() {
        val facts = WidgetFacts.drains(window(50.0, 310.0, 90.0, 180.0))
        assertThat(facts?.rows?.map { it.label }).containsExactly("App 1", "App 3", "App 2").inOrder()
        // Of the measured 1,000 mAh, which is the window's total here.
        assertThat(facts?.rows?.first()?.share).isWithin(1e-9).of(0.31)
        assertThat(WidgetFacts.drains(null)).isNull()
        assertThat(WidgetFacts.drains(window())).isNull()
    }

    @Test
    fun aSentenceIsShownOnlyWhereAllOfItFits() {
        val verdict = "Since unplugged 6 h ago, the battery fell 26%; Maps used 31% of it."
        // 146 dp holds 22 letters a line: four lines with the loss at word breaks.
        assertThat(WidgetFit.lines(verdict, 146f, 70f, 1f)).isEqualTo(4)
        assertThat(WidgetFit.lines(verdict, 146f, 40f, 1f)).isNull()
        // Larger text needs more room than there is.
        assertThat(WidgetFit.lines(verdict, 146f, 70f, 2f)).isNull()
        assertThat(WidgetFit.lines(verdict, 0f, 70f, 1f)).isNull()
    }

    @Test
    fun rowsAreCountedByTheHeightThereIs() {
        assertThat(WidgetFit.rows(118f, 36f, 1f, 3)).isEqualTo(3)
        assertThat(WidgetFit.rows(68f, 36f, 1f, 3)).isEqualTo(1)
        assertThat(WidgetFit.rows(118f, 36f, 2f, 3)).isEqualTo(1)
        assertThat(WidgetFit.rows(400f, 36f, 1f, 3)).isEqualTo(3)
    }
}
