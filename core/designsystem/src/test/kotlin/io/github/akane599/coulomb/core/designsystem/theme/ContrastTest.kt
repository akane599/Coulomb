// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.2 AA over the shipped tokens in every theme (SPEC 11.11, 18): 4.5:1 for text on each
 * surface and on its fill, 3:1 for the marks that carry meaning without text (1.4.11).
 */
class ContrastTest {
    private val themes = mapOf("light" to LightColors, "dark" to DarkColors, "black" to BlackColors)

    @Test
    fun textOnEverySurface() =
        each { c ->
            listOf(c.bg, c.surface, c.surfaceRaised).flatMap { surface ->
                listOf("textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary).map { (name, text) ->
                    Pair("$name on surface", ratio(text, surface)) to TEXT
                }
            }
        }

    @Test
    fun textOnEveryFill() =
        each { c ->
            listOf(
                Pair("onAccentFill", ratio(c.onAccentFill, c.accentFill)) to TEXT,
                Pair("onGood", ratio(c.onGood, c.good)) to TEXT,
                Pair("onCaution", ratio(c.onCaution, c.caution)) to TEXT,
                Pair("onCritical", ratio(c.onCritical, c.critical)) to TEXT,
            )
        }

    /**
     * The colours drawn as marks without text of their own: the accent (charts, the charge target),
     * Good and Critical icons, and the data hues. Caution appears only as the Watch chip's fill,
     * whose word carries the meaning (DESIGN.md, Status), so 1.4.11 does not ask it of the fill.
     */
    @Test
    fun meaningfulMarksOnTheBackground() =
        each { c ->
            val marks =
                listOf(
                    "accent" to c.accent,
                    "good" to c.good,
                    "critical" to c.critical,
                    "dataScreen" to c.dataScreen,
                    "dataCpu" to c.dataCpu,
                    "dataRadio" to c.dataRadio,
                    "dataWifi" to c.dataWifi,
                    "dataGnss" to c.dataGnss,
                    "dataBluetooth" to c.dataBluetooth,
                    "dataSensors" to c.dataSensors,
                )
            marks.flatMap { (name, mark) -> listOf(c.bg, c.surface).map { Pair("$name mark", ratio(mark, it)) to NON_TEXT } }
        }

    private fun each(checks: (CoulombColors) -> List<Pair<Pair<String, Double>, Double>>) {
        val failures =
            themes.flatMap { (theme, colors) ->
                checks(colors)
                    .filter { (it, min) ->
                        it.second < min
                    }.map { (it, min) -> "$theme ${it.first}: %.2f < $min".format(it.second) }
            }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + OFFSET) / (lo + OFFSET)
    }

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
        const val OFFSET = 0.05
    }
}
