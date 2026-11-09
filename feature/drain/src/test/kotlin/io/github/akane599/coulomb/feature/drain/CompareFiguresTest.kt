// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.ui.format.Formats
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Compare's rates: an exact zero reads apart from a trace, and a change that rounds to zero takes no sign. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CompareFiguresTest {
    private val formats =
        Formats(
            resources = RuntimeEnvironment.getApplication().resources,
            locale = Locale.US,
            clockPattern = "h:mm a",
            secondsPattern = "h:mm:ss a",
        )

    @Test
    fun anExactZeroIsWhole() {
        assertThat(formats.amount(0.0)).isEqualTo("0")
        assertThat(formats.amount(0.04)).isEqualTo("0.0")
        assertThat(formats.amount(4.5)).isEqualTo("4.5")
        assertThat(formats.amount(36.4)).isEqualTo("36")
    }

    @Test
    fun aChangeThatRoundsToZeroIsUnsigned() {
        assertThat(formats.roundsToZero(0.0)).isTrue()
        assertThat(formats.roundsToZero(-0.04)).isTrue()
        assertThat(formats.roundsToZero(0.06)).isFalse()
        assertThat(formats.signedAmount(-0.04)).isEqualTo("0.0")
        assertThat(formats.signedAmount(0.0)).isEqualTo("0")
        assertThat(formats.signedAmount(2.0)).contains("+2.0")
    }
}
