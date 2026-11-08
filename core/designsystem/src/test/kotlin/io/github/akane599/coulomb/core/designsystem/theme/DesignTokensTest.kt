// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.reflect.KProperty1

/**
 * SPEC 11.12 step 5: every token in `DESIGN.md` exists in code with the same value, and code has
 * no token `DESIGN.md` lacks. Colours are converted here independently of `tools/design/tokens.py`:
 * through XYZ with the CSS Color 4 matrices rather than the script's direct OKLab matrices.
 */
class DesignTokensTest {
    private val design = Frontmatter.parse(File(System.getProperty("coulomb.designMd")!!).readText())

    @Test
    fun everyColourTokenMatchesDesignMd() {
        val modes = mapOf("light" to LightColors, "dark" to DarkColors)
        val colors = design.getValue("colors")
        val byMode = colors.keys.groupBy({ it.substringAfterLast('-') }, { it.substringBeforeLast('-') })
        assertThat(byMode.keys).containsAtLeast("light", "dark")
        assertThat(byMode.keys - setOf("light", "dark", "black")).isEmpty()
        for (mode in listOf("light", "dark")) {
            assertWithMessage("$mode tokens").that(byMode.getValue(mode)).containsExactlyElementsIn(COLORS.keys)
        }
        for ((key, value) in colors) {
            val token = key.substringBeforeLast('-')
            val mode = key.substringAfterLast('-')
            val palette = modes[mode] ?: BlackColors
            assertWithMessage(key).that(hex(COLORS.getValue(token).get(palette))).isEqualTo(hex(oklch(value as String)))
        }
        // True black is dark except where DESIGN.md names a black value.
        val black = byMode["black"].orEmpty().toSet()
        for ((token, property) in COLORS) {
            if (token !in black) assertWithMessage("$token-black").that(property.get(BlackColors)).isEqualTo(property.get(DarkColors))
        }
    }

    @Test
    fun everyTypeRoleMatchesDesignMd() {
        val roles = design.getValue("typography")
        assertThat(roles.keys).containsExactlyElementsIn(TYPE.keys)
        val type = CoulombTypography()
        for ((name, spec) in roles) {
            @Suppress("UNCHECKED_CAST")
            val props = spec as Map<String, String>
            val style = TYPE.getValue(name).get(type)
            assertWithMessage("$name fontFamily").that(style.fontFamily).isEqualTo(family(props.getValue("fontFamily")))
            assertWithMessage("$name fontSize").that(style.fontSize).isEqualTo(px(props.getValue("fontSize")).sp)
            assertWithMessage("$name lineHeight").that(style.lineHeight).isEqualTo(px(props.getValue("lineHeight")).sp)
            assertWithMessage("$name fontWeight").that(style.fontWeight).isEqualTo(FontWeight(props.getValue("fontWeight").toInt()))
            val tracking = props["letterSpacing"]?.removeSuffix("em")?.toFloat() ?: 0f
            assertWithMessage("$name letterSpacing").that(style.letterSpacing).isEqualTo(tracking.em)
            assertWithMessage("$name figures").that(style.fontFeatureSettings).isEqualTo("tnum,lnum")
        }
    }

    @Test
    fun radiiAndSpacingMatchDesignMd() {
        assertThat(design.getValue("rounded").keys).containsExactlyElementsIn(RADII.keys)
        for ((name, value) in design.getValue("rounded")) {
            assertWithMessage(name).that(RADII.getValue(name)).isEqualTo(px(value as String).dp)
        }
        assertThat(design.getValue("spacing").keys).containsExactlyElementsIn(SPACING.keys)
        for ((name, value) in design.getValue("spacing")) {
            assertWithMessage(name).that(SPACING.getValue(name)).isEqualTo(px(value as String).dp)
        }
    }

    private fun family(name: String): FontFamily =
        when (name.substringBefore(',').trim().trim('"', '\'')) {
            "Hanken Grotesk" -> HankenGrotesk
            "Spline Sans Mono" -> SplineSansMono
            else -> error("unknown font family $name")
        }

    private fun px(value: String): Float = value.removeSuffix("px").toFloat()

    private fun hex(color: Color) = "%08X".format(color.toArgb())

    private companion object {
        val COLORS: Map<String, KProperty1<CoulombColors, Color>> =
            mapOf(
                "bg" to CoulombColors::bg,
                "surface" to CoulombColors::surface,
                "surface-raised" to CoulombColors::surfaceRaised,
                "hairline" to CoulombColors::hairline,
                "text-primary" to CoulombColors::textPrimary,
                "text-secondary" to CoulombColors::textSecondary,
                "accent" to CoulombColors::accent,
                "good" to CoulombColors::good,
                "caution" to CoulombColors::caution,
                "critical" to CoulombColors::critical,
                "accent-fill" to CoulombColors::accentFill,
                "on-accent-fill" to CoulombColors::onAccentFill,
                "on-good" to CoulombColors::onGood,
                "on-caution" to CoulombColors::onCaution,
                "on-critical" to CoulombColors::onCritical,
                "data-screen" to CoulombColors::dataScreen,
                "data-cpu" to CoulombColors::dataCpu,
                "data-radio" to CoulombColors::dataRadio,
                "data-wifi" to CoulombColors::dataWifi,
                "data-gnss" to CoulombColors::dataGnss,
                "data-bluetooth" to CoulombColors::dataBluetooth,
                "data-sensors" to CoulombColors::dataSensors,
                "data-other" to CoulombColors::dataOther,
            )

        val TYPE: Map<String, KProperty1<CoulombTypography, TextStyle>> =
            mapOf(
                "display" to CoulombTypography::display,
                "display-unit" to CoulombTypography::displayUnit,
                "headline" to CoulombTypography::headline,
                "title" to CoulombTypography::title,
                "body" to CoulombTypography::body,
                "body-name" to CoulombTypography::bodyName,
                "body-subhead" to CoulombTypography::bodySubhead,
                "data" to CoulombTypography::data,
                "data-lead" to CoulombTypography::dataLead,
                "label" to CoulombTypography::label,
                "label-button" to CoulombTypography::labelButton,
                "caption" to CoulombTypography::caption,
                "caption-emphasis" to CoulombTypography::captionEmphasis,
                "caption-nav" to CoulombTypography::captionNav,
                "mono" to CoulombTypography::mono,
            )

        val RADII: Map<String, Dp> =
            mapOf("chip" to CoulombRadius.Chip, "row" to CoulombRadius.Row, "sheet" to CoulombRadius.Sheet, "large" to CoulombRadius.Large)

        val SPACING: Map<String, Dp> =
            mapOf(
                "space-4" to CoulombSpacing.Space4,
                "space-8" to CoulombSpacing.Space8,
                "space-12" to CoulombSpacing.Space12,
                "space-16" to CoulombSpacing.Space16,
                "space-24" to CoulombSpacing.Space24,
                "space-32" to CoulombSpacing.Space32,
                "space-48" to CoulombSpacing.Space48,
                "space-64" to CoulombSpacing.Space64,
                "gutter-compact" to CoulombSpacing.GutterCompact,
                "gutter-medium" to CoulombSpacing.GutterMedium,
                "touch-target" to CoulombSpacing.TouchTarget,
            )

        private val OKLAB_TO_LMS =
            arrayOf(
                doubleArrayOf(1.0, 0.3963377773761749, 0.2158037573099136),
                doubleArrayOf(1.0, -0.1055613458156586, -0.0638541728258133),
                doubleArrayOf(1.0, -0.0894841775298119, -1.2914855480194092),
            )
        private val LMS_TO_XYZ =
            arrayOf(
                doubleArrayOf(1.2268798758459243, -0.5578149944602171, 0.2813910456659647),
                doubleArrayOf(-0.0405757452148008, 1.1122868032803170, -0.0717110580655164),
                doubleArrayOf(-0.0763729366746601, -0.4214933324022432, 1.5869240198367816),
            )
        private val XYZ_TO_LINEAR_SRGB =
            arrayOf(
                doubleArrayOf(3.2409699419045226, -1.537383177570094, -0.4986107602930034),
                doubleArrayOf(-0.9692436362808796, 1.8759675015077202, 0.04155505740717559),
                doubleArrayOf(0.05563007969699366, -0.20397695888897652, 1.0569715142428786),
            )

        private fun Array<DoubleArray>.times(v: DoubleArray) = DoubleArray(3) { i -> (0..2).sumOf { this[i][it] * v[it] } }

        /** CSS Color 4 `oklch()` to 8-bit sRGB, clipped per channel, rounded half up. */
        fun oklch(css: String): Color {
            val (l, c, h) = Regex("""oklch\(([\d.]+) ([\d.]+) ([\d.]+)\)""").matchEntire(css)!!.destructured
            val hue = Math.toRadians(h.toDouble())
            val lab = doubleArrayOf(l.toDouble(), c.toDouble() * cos(hue), c.toDouble() * sin(hue))
            val lms = OKLAB_TO_LMS.times(lab).map { it * it * it }.toDoubleArray()
            val linear = XYZ_TO_LINEAR_SRGB.times(LMS_TO_XYZ.times(lms))
            val (r, g, b) =
                linear.map { v ->
                    val x = v.coerceIn(0.0, 1.0)
                    val encoded = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
                    floor(encoded * 255 + 0.5).toInt()
                }
            return Color(red = r, green = g, blue = b)
        }
    }
}

/** The subset of YAML that DESIGN.md's frontmatter uses: nested maps of scalars, two-space indents. */
private object Frontmatter {
    fun parse(text: String): Map<String, Map<String, Any>> {
        val lines = text.lines()
        require(lines.first() == "---") { "DESIGN.md has no frontmatter" }
        val end = lines.drop(1).indexOf("---") + 1
        require(end > 0) { "DESIGN.md's frontmatter is not closed" }
        val groups = linkedMapOf<String, LinkedHashMap<String, Any>>()
        var group: LinkedHashMap<String, Any>? = null
        var entry: LinkedHashMap<String, String>? = null
        for (line in lines.subList(1, end).map { it.substringBefore(" #").trimEnd() }.filter(::isContent)) {
            val key = line.trim().substringBefore(':')
            val value =
                line
                    .trim()
                    .substringAfter(':', "")
                    .trim()
                    .trim('"', '\'')
            when (line.length - line.trimStart().length) {
                0 -> group = if (value.isEmpty()) linkedMapOf<String, Any>().also { groups[key] = it } else null
                2 -> entry = group?.let { put(it, key, value) }
                4 -> entry?.set(key, value)
            }
        }
        return groups
    }

    private fun isContent(line: String) = line.isNotBlank() && !line.trimStart().startsWith("#")

    /** Puts a scalar or, for an empty value, a new nested map, which it returns. */
    private fun put(
        group: LinkedHashMap<String, Any>,
        key: String,
        value: String,
    ): LinkedHashMap<String, String>? {
        if (value.isNotEmpty()) {
            group[key] = value
            return null
        }
        return linkedMapOf<String, String>().also { group[key] = it }
    }
}
