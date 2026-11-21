// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.app.Application
import android.os.BatteryManager
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.DischargeSummary
import io.github.akane599.coulomb.core.data.StateRate
import io.github.akane599.coulomb.core.ui.format.formatsFor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the Quick Settings tile says (SPEC 12.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TileWordsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun words(
        plugged: Boolean,
        on: StateRate?,
        off: StateRate?,
    ): TileWords {
        val status = if (plugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING
        val battery = BatteryNow(62, null, plugged, status, 300, 4_000, screenOn = true, wallMs = 1)
        val discharge = DischargeSummary(0, 80, 62, on, off, deepSleep = null, timeLeft = null)
        return TileWords.of(BatteryReading(battery, discharge, emptyList(), emptyList()), formatsFor(context), context.resources)
    }

    private fun String.plain() = map { if (it.code == NBSP) ' ' else it }.joinToString("")

    @Test
    fun onBatteryTheTileSaysTheScreenOnRate() {
        val words = words(plugged = false, on = StateRate(8.42, 2.0, 1.0), off = StateRate(0.7, 6.0, 1.0))
        assertThat(words.level).isEqualTo("62%")
        assertThat(words.detail.plain()).isEqualTo("8.4 %/h · screen on")
    }

    @Test
    fun beforeAScreenOnRateItSaysTheScreenOffOne() {
        // Five minutes of screen-on time is not a rate yet (SPEC 6.2).
        val words = words(plugged = false, on = StateRate(8.42, 0.08, 1.0), off = StateRate(0.7, 6.0, 1.0))
        assertThat(words.detail.plain()).isEqualTo("0.7 %/h · screen off")
    }

    @Test
    fun withoutARateOrOnTheChargerItSaysTheState() {
        assertThat(words(plugged = false, on = null, off = null).detail).isEqualTo("On battery")
        val charging = words(plugged = true, on = StateRate(8.42, 2.0, 1.0), off = null)
        assertThat(charging.detail).isEqualTo("Charging")
        assertThat(charging.state).isEqualTo(PowerState.CHARGING)
    }

    private companion object {
        const val NBSP = 0x00A0
    }
}
