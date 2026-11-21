// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.format

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** SPEC 14.4 "Units": one stored temperature, in tenths of a degree Celsius, printed in the user's unit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TemperatureTest {
    private fun formats(fahrenheit: Boolean) =
        Formats(
            resources = RuntimeEnvironment.getApplication().resources,
            locale = Locale.US,
            clockPattern = "h:mm a",
            secondsPattern = "h:mm:ss a",
            fahrenheit = fahrenheit,
        )

    @Test
    fun aReadingKeepsItsTenth() {
        assertThat(formats(false).temperature(338.0).joined()).isEqualTo("33.8 °C")
        assertThat(formats(true).temperature(338.0).joined()).isEqualTo("92.8 °F")
        assertThat(formats(true).temperature(0.0).joined()).isEqualTo("32.0 °F")
    }

    @Test
    fun aThresholdIsWholeWhereItIsWholeInTheUnit() {
        assertThat(formats(false).threshold(380).joined()).isEqualTo("38 °C")
        assertThat(formats(false).threshold(425).joined()).isEqualTo("42.5 °C")
        assertThat(formats(true).threshold(380).joined()).isEqualTo("100.4 °F")
        assertThat(formats(true).threshold(400).joined()).isEqualTo("104 °F")
    }

    @Test
    fun aChangeHasNoOffset() {
        assertThat(formats(false).temperatureChange(41.0).joined()).isEqualTo("4.1 °C")
        assertThat(formats(true).temperatureChange(41.0).joined()).isEqualTo("7.4 °F")
        assertThat(formats(true).temperatureChange(0.0).joined()).isEqualTo("0.0 °F")
    }
}
