// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.now

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.Note
import kotlin.math.roundToInt

/**
 * S24 (T0): the screen's refresh rate and whether brightness is automatic, the context of the
 * screen-on rate. Read when Now resumes, not stored (ADR-0068): the brightness setting's number
 * maps to the slider through a curve the platform does not publish, so only its mode is shown.
 */
@Immutable
internal data class ScreenContext(
    val refreshHz: Int?,
    val autoBrightness: Boolean?,
) {
    companion object {
        fun read(context: Context): ScreenContext {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode =
                runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull()
            return ScreenContext(
                refreshHz = display?.refreshRate?.takeIf { it > 0 }?.roundToInt(),
                autoBrightness = mode?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC },
            )
        }
    }
}

/** Read on each resume, so a change made in Settings shows on return. */
@Composable
internal fun rememberScreenContext(): ScreenContext? {
    val context = LocalContext.current
    var value by remember { mutableStateOf<ScreenContext?>(null) }
    LifecycleResumeEffect(context) {
        value = ScreenContext.read(context)
        onPauseOrDispose {}
    }
    return value
}

/** "Screen now: 120 Hz, automatic brightness." under the session strip. */
@Composable
internal fun ScreenContextNote(
    screen: ScreenContext,
    formats: Formats,
) {
    val hz = screen.refreshHz?.let { stringResource(R.string.now_screen_hz, formats.integer(it.toLong())) }
    val brightness =
        screen.autoBrightness?.let {
            stringResource(
                if (it) R.string.now_screen_auto_brightness else R.string.now_screen_manual_brightness,
            )
        }
    val parts = listOfNotNull(hz, brightness)
    if (parts.isEmpty()) return
    Note(stringResource(R.string.now_screen_context, parts.joinToString(", ")))
}
