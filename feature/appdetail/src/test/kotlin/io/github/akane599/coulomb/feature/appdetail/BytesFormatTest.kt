// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.ui.format.Figure
import io.github.akane599.coulomb.core.ui.format.Formats
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Network figures in decimal units, as Android's settings show them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BytesFormatTest {
    private val formats =
        Formats(
            resources = RuntimeEnvironment.getApplication().resources,
            locale = Locale.US,
            clockPattern = "h:mm a",
            secondsPattern = "h:mm:ss a",
        )

    @Test
    fun eachUnitIsWholeFromTen() {
        assertThat(formats.bytes(0)).isEqualTo(Figure("0", "B"))
        assertThat(formats.bytes(999)).isEqualTo(Figure("999", "B"))
        assertThat(formats.bytes(312_000_000)).isEqualTo(Figure("312", "MB"))
        assertThat(formats.bytes(48_400_000)).isEqualTo(Figure("48", "MB"))
    }

    @Test
    fun underTenTakesOneDecimal() {
        assertThat(formats.bytes(1_000)).isEqualTo(Figure("1.0", "kB"))
        assertThat(formats.bytes(4_500_000_000)).isEqualTo(Figure("4.5", "GB"))
    }

    @Test
    fun roundingNeverPrintsAThousandOrTenPointZero() {
        assertThat(formats.bytes(999_600)).isEqualTo(Figure("1.0", "MB"))
        assertThat(formats.bytes(9_960_000)).isEqualTo(Figure("10", "MB"))
        assertThat(formats.bytes(2_500_000_000_000)).isEqualTo(Figure("2,500", "GB"))
    }
}
