// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.ui.format.Formats
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** The header's share: never 0% for some use, never 100% for less than all of it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ShareFormatTest {
    private val formats =
        Formats(
            resources = RuntimeEnvironment.getApplication().resources,
            locale = Locale.US,
            clockPattern = "h:mm a",
            secondsPattern = "h:mm:ss a",
        )

    @Test
    fun theEndsAreKeptApartFromTheirNeighbours() {
        assertThat(formats.share(0.0)).isEqualTo("0%")
        assertThat(formats.share(0.001)).isEqualTo("\u2066<1%\u2069")
        assertThat(formats.share(0.334)).isEqualTo("33%")
        assertThat(formats.share(0.996)).isEqualTo("\u2066>99%\u2069")
        assertThat(formats.share(1.0)).isEqualTo("100%")
    }
}
