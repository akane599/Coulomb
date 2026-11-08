// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.ComponentDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.PowerComponent
import io.github.akane599.coulomb.core.designsystem.theme.CoulombColors
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.FigureText
import io.github.akane599.coulomb.core.ui.ProvenanceMark
import io.github.akane599.coulomb.core.ui.affixed
import io.github.akane599.coulomb.core.ui.format.Formats

/**
 * The Components lens (SPEC 10.3): the device's modeled power by component, as one stacked bar in
 * the data hues and a ranked list with a swatch on each row. Components with no hue of their own
 * share the neutral Other hue and sit together at the bar's end (DESIGN.md, Data palette).
 */
@Composable
internal fun ComponentsLens(
    report: DrainReport,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val components = report.components
    Column(Modifier.padding(top = CoulombSpacing.Space16)) {
        if (components.isEmpty()) {
            Text(stringResource(R.string.drain_components_empty), color = colors.textSecondary, style = type.body)
            return@Column
        }
        Text(stringResource(R.string.drain_components_caption), color = colors.textSecondary, style = type.caption)
        if (components.any { it.component == PowerComponent.OTHER }) {
            Text(stringResource(R.string.drain_components_coarse), color = colors.textSecondary, style = type.caption)
        }
        val total = components.sumOf { it.mah }
        val names = components.map { stringResource(it.component.label) }
        val description =
            stringResource(
                R.string.drain_components_chart,
                components.indices.joinToString(", ") { "${names[it]} ${formats.share(components[it].mah / total)}" },
            )
        val segments = barSegments(components, { colors.hue(it) }, colors.dataOther)
        StackedBar(
            segments,
            total,
            Modifier
                .padding(top = CoulombSpacing.Space16)
                .fillMaxWidth()
                .height(BarBox)
                .clearAndSetSemantics { contentDescription = description },
        )
        Column(Modifier.padding(top = CoulombSpacing.Space16)) {
            components.forEachIndexed { index, part ->
                ComponentRow(part, names[index], part.mah / total, index == 0, colors.hue(part.component), formats)
            }
        }
    }
}

/** The bar's segments: each hued component in rank order, then every neutral one as one segment. */
internal fun barSegments(
    components: List<ComponentDrain>,
    hue: (PowerComponent) -> Color,
    neutral: Color,
): List<Pair<Color, Double>> {
    val (hued, rest) = components.partition { hue(it.component) != neutral }
    return hued.map { hue(it.component) to it.mah } +
        listOfNotNull(rest.sumOf { it.mah }.takeIf { it > 0 }?.let { neutral to it })
}

/** A 14 dp bar of [segments] from the start edge, 2 dp apart with 2 dp ends, mirrored in RTL. */
@Composable
private fun StackedBar(
    segments: List<Pair<Color, Double>>,
    total: Double,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val height = BarHeight.toPx()
        val top = (size.height - height) / 2
        val gap = SegmentGap.toPx()
        val corner = CornerRadius(BarEnd.toPx())
        val drawn = segments.filter { it.second > 0 }
        val room = size.width - gap * (drawn.size - 1).coerceAtLeast(0)
        var start = 0f
        drawn.forEach { (color, mah) ->
            val width = (room * (mah / total)).toFloat()
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - width else start
            drawRoundRect(color, Offset(x, top), Size(width, height), corner)
            start += width + gap
        }
    }
}

/**
 * One component: a 10 dp swatch in its hue, the name, the mAh with the Modeled mark and its share
 * under it (DESIGN.md, Breakdown and Rows). Hairlines divide rows; TalkBack reads one item.
 */
@Composable
private fun ComponentRow(
    part: ComponentDrain,
    name: String,
    share: Double,
    first: Boolean,
    hue: Color,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Hairline.toPx()
                if (first) drawLine(colors.hairline, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                drawLine(colors.hairline, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }.semantics(mergeDescendants = true) {}
            .heightIn(min = RowHeight)
            .padding(vertical = CoulombSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space12),
    ) {
        Box(Modifier.size(Swatch).background(hue, RoundedCornerShape(SwatchCorner)))
        Text(name, Modifier.weight(1f), color = colors.textPrimary, style = type.bodyName)
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FigureText(
                    formats.mah(part.mah).affixed(),
                    numberStyle = type.data,
                    unitStyle = type.data.copy(fontWeight = type.body.fontWeight),
                )
                ProvenanceMark(Provenance.MODELED)
            }
            Text(formats.share(share), color = colors.textSecondary, style = type.caption, textAlign = TextAlign.End)
        }
    }
}

/** The component's data hue; those without one take the neutral Other (DESIGN.md, Data palette). */
internal fun CoulombColors.hue(component: PowerComponent): Color =
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
internal val PowerComponent.label: Int
    get() =
        when (this) {
            PowerComponent.SCREEN -> R.string.drain_component_screen
            PowerComponent.CPU -> R.string.drain_component_cpu
            PowerComponent.MOBILE_RADIO -> R.string.drain_component_mobile_radio
            PowerComponent.WIFI -> R.string.drain_component_wifi
            PowerComponent.BLUETOOTH -> R.string.drain_component_bluetooth
            PowerComponent.GNSS -> R.string.drain_component_gnss
            PowerComponent.SENSORS -> R.string.drain_component_sensors
            PowerComponent.CAMERA -> R.string.drain_component_camera
            PowerComponent.AUDIO -> R.string.drain_component_audio
            PowerComponent.VIDEO -> R.string.drain_component_video
            PowerComponent.FLASHLIGHT -> R.string.drain_component_flashlight
            PowerComponent.WAKELOCK -> R.string.drain_component_wakelock
            PowerComponent.MEMORY -> R.string.drain_component_memory
            PowerComponent.PHONE -> R.string.drain_component_phone
            PowerComponent.AMBIENT_DISPLAY -> R.string.drain_component_ambient_display
            PowerComponent.IDLE -> R.string.drain_component_idle
            PowerComponent.SYSTEM_SERVICES -> R.string.drain_component_system_services
            PowerComponent.CUSTOM -> R.string.drain_component_custom
            PowerComponent.OTHER -> R.string.drain_component_other
        }

private val BarBox = 18.dp
private val BarHeight = 14.dp
private val BarEnd = 2.dp
private val SegmentGap = 2.dp
private val Swatch = 10.dp
private val SwatchCorner = 2.dp
private val Hairline = 1.dp
private val RowHeight = 56.dp
