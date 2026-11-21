// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.designsystem.theme.DarkColors
import io.github.akane599.coulomb.core.designsystem.theme.LightColors
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The initial layout is plain XML, so its ground is a colour resource: it must stay DESIGN.md's `bg`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WidgetTokensTest {
    private fun ground() = RuntimeEnvironment.getApplication().getColor(R.color.widget_bg)

    @Test
    fun theInitialGroundIsTheLightBackground() {
        assertThat(ground()).isEqualTo(LightColors.bg.toArgb())
    }

    @Test
    @Config(qualifiers = "night")
    fun theInitialGroundIsTheDarkBackgroundAtNight() {
        assertThat(ground()).isEqualTo(DarkColors.bg.toArgb())
    }
}
