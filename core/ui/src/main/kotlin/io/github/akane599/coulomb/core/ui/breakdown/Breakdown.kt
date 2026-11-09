// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.breakdown

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombColors
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.stats.PowerComponent
import io.github.akane599.coulomb.core.ui.R

/** The component's data hue; those without one take the neutral Other (DESIGN.md, Data palette). */
public fun CoulombColors.hue(component: PowerComponent): Color =
    when (component) {
        PowerComponent.SCREEN, PowerComponent.AMBIENT_DISPLAY -> dataScreen
        PowerComponent.CPU -> dataCpu
        PowerComponent.MOBILE_RADIO, PowerComponent.PHONE -> dataRadio
        PowerComponent.WIFI -> dataWifi
        PowerComponent.GNSS -> dataGnss
        PowerComponent.BLUETOOTH -> dataBluetooth
        PowerComponent.SENSORS -> dataSensors
        else -> dataOther
    }

/** SPEC 6.5: friendly names, never the platform's identifiers. */
@get:StringRes
public val PowerComponent.label: Int
    get() =
        when (this) {
            PowerComponent.SCREEN -> R.string.component_screen
            PowerComponent.CPU -> R.string.component_cpu
            PowerComponent.MOBILE_RADIO -> R.string.component_mobile_radio
            PowerComponent.WIFI -> R.string.component_wifi
            PowerComponent.BLUETOOTH -> R.string.component_bluetooth
            PowerComponent.GNSS -> R.string.component_gnss
            PowerComponent.SENSORS -> R.string.component_sensors
            PowerComponent.CAMERA -> R.string.component_camera
            PowerComponent.AUDIO -> R.string.component_audio
            PowerComponent.VIDEO -> R.string.component_video
            PowerComponent.FLASHLIGHT -> R.string.component_flashlight
            PowerComponent.WAKELOCK -> R.string.component_wakelock
            PowerComponent.MEMORY -> R.string.component_memory
            PowerComponent.PHONE -> R.string.component_phone
            PowerComponent.AMBIENT_DISPLAY -> R.string.component_ambient_display
            PowerComponent.IDLE -> R.string.component_idle
            PowerComponent.SYSTEM_SERVICES -> R.string.component_system_services
            PowerComponent.CUSTOM -> R.string.component_custom
            PowerComponent.OTHER -> R.string.component_other
        }

/** The shape the reconcile bar and the component bars share (DESIGN.md, Breakdown). */
public object BarShape {
    /** The bar's box: the bar and room above and below it. */
    public val Box: Dp = 18.dp

    public val Height: Dp = 14.dp

    /** The radius of a segment's ends. */
    public val End: Dp = 2.dp

    /** The space between two segments. */
    public val Gap: Dp = 2.dp
}

/**
 * A bar's segments: each hued component in the given order, then every neutral one as one segment,
 * then [rest], the energy with no component, as a hollow one (a null colour). Only segments with
 * energy, since the bar draws each one with a gap between.
 */
public fun <T> barSegments(
    parts: List<T>,
    component: (T) -> PowerComponent,
    mah: (T) -> Double,
    hue: (PowerComponent) -> Color,
    neutral: Color,
    rest: Double? = null,
): List<Pair<Color?, Double>> {
    val (hued, unhued) = parts.filter { mah(it) > 0 }.partition { hue(component(it)) != neutral }
    return hued.map { hue(component(it)) to mah(it) } +
        listOfNotNull(unhued.sumOf(mah).takeIf { it > 0 }?.let { neutral to it }) +
        listOfNotNull(rest?.takeIf { it > 0 }?.let { null to it })
}

/**
 * A 14 dp bar of [segments] (each above zero) from the start edge, 2 dp apart with 2 dp ends,
 * mirrored in RTL. A segment with no colour is a 1 dp `text-secondary` outline, as its swatch.
 * Decoration: the caller gives it [chartSemantics].
 */
@Composable
public fun StackedBar(
    segments: List<Pair<Color?, Double>>,
    total: Double,
    modifier: Modifier = Modifier,
) {
    val outline = CoulombTheme.colors.textSecondary
    Canvas(modifier) {
        val height = BarShape.Height.toPx()
        val top = (size.height - height) / 2
        val gap = BarShape.Gap.toPx()
        val corner = CornerRadius(BarShape.End.toPx())
        val room = size.width - gap * (segments.size - 1).coerceAtLeast(0)
        val stroke = Outline.toPx()
        var start = 0f
        segments.forEach { (color, mah) ->
            val width = (room * (mah / total)).toFloat()
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - width else start
            if (color != null) {
                drawRoundRect(color, Offset(x, top), Size(width, height), corner)
            } else {
                // Inside its box, so it is no wider than a filled segment of the same energy.
                val inset = stroke / 2
                drawRoundRect(
                    outline,
                    Offset(x + inset, top + inset),
                    Size((width - stroke).coerceAtLeast(0f), height - stroke),
                    corner,
                    style = Stroke(stroke),
                )
            }
            start += width + gap
        }
    }
}

/**
 * A component's 10 dp swatch in [hue]; null draws it hollow, a 1 dp `text-secondary` outline, for
 * the energy that has no component (DESIGN.md, Breakdown).
 */
@Composable
public fun Swatch(
    hue: Color?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SwatchCorner)
    val fill =
        if (hue != null) {
            Modifier.background(hue, shape)
        } else {
            Modifier.border(Outline, CoulombTheme.colors.textSecondary, shape)
        }
    Box(modifier.size(SwatchSize).then(fill))
}

/**
 * A chart's semantics (SPEC 11.8): one summary of its figures, and the "View as table" action
 * ([viewTable] is its label), which opens the same figures as a table.
 */
public fun Modifier.chartSemantics(
    description: String,
    viewTable: String,
    onViewTable: () -> Unit,
): Modifier =
    clearAndSetSemantics {
        contentDescription = description
        customActions =
            listOf(
                CustomAccessibilityAction(viewTable) {
                    onViewTable()
                    true
                },
            )
    }

private val SwatchSize = 10.dp
private val SwatchCorner = 2.dp
private val Outline = 1.dp
