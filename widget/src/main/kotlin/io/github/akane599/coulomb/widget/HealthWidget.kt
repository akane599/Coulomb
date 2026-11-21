// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.formatsFor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Health, 2×2 (SPEC 12.4). */
internal class HealthWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val store = context.widgetStore()
        store.ensure(WidgetKind.HEALTH)
        provideContent {
            val data by store.state.collectAsState()
            HealthContent(data.health)
        }
    }

    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) = provideContent { HealthContent(Samples.health) }
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}

private val MarkGap = 4.dp
private const val CAPTION_SP = 12f
private val LineGap = 4.dp
private val HeadGap = 8.dp

/**
 * The capacity estimate as the hero, then health against the design capacity and the cycle count
 * as two ruled lines. The estimate and the health are Estimated (SPEC 6.3); the platform's cycle
 * count is Measured, Coulomb's own count Derived.
 */
@Composable
internal fun HealthContent(health: HealthFacts?) {
    val context = LocalContext.current
    val size = LocalSize.current
    WidgetSurface(OpenRequest.HEALTH) {
        if (health == null) {
            Text(context.getString(R.string.widget_health_title), style = WidgetType.label, maxLines = 1)
            Spacer(GlanceModifier.height(HeadGap))
            Empty(R.string.widget_health_empty, R.string.widget_health_empty_short, used = WidgetLine.label + HeadGap)
            return@WidgetSurface
        }
        val formats = formatsFor(context)
        val scale = context.resources.configuration.fontScale
        val capacity = formats.mah(health.capacityMah)
        val inner = (size.width - WidgetPadding * 2).value
        // The figure at the largest size its line holds; where even Title has no room for the unit, the heading carries it.
        val unitWidth = MarkGap.value + WidgetFit.width(capacity.unit, WidgetLine.DATA_SP, scale)

        fun fits(
            sp: Float,
            withUnit: Boolean,
        ) = WidgetFit.width(capacity.number, sp, scale) + WidgetLine.mark.value + (if (withUnit) unitWidth else 0f) <= inner
        val wide = fits(WidgetLine.HERO_SP, withUnit = true)
        val unitBeside = wide || fits(WidgetLine.TITLE_SP, withUnit = true)
        val heading = context.getString(R.string.widget_health_title)
        val withUnit = context.getString(R.string.widget_health_title_unit, heading, capacity.unit)
        // Where neither line holds the unit, the range under the figure still names it.
        val named = !unitBeside && WidgetFit.width(withUnit, WidgetLine.LABEL_SP, scale) <= inner
        Text(if (named) withUnit else heading, style = WidgetType.label, maxLines = 1)
        Figure(
            capacity.number,
            Provenance.ESTIMATED,
            if (wide) WidgetType.hero else WidgetType.title,
            unit = capacity.unit.takeIf { unitBeside },
            spokenUnit = capacity.unit,
        )
        // What the height still holds, in order: the estimate's range first, then health, then cycles.
        var room = size.height - WidgetPadding * 2 - (WidgetLine.label + if (wide) WidgetLine.hero else WidgetLine.title) * scale
        val full = formats.range(formats.mah(health.lowMah).number, formats.mah(health.highMah).joined())
        val half = maxOf(health.capacityMah - health.lowMah, health.highMah - health.capacityMah)
        val short = context.getString(R.string.widget_health_plus_minus, formats.mah(half).joined())
        val range = listOf(full, short).firstOrNull { WidgetFit.width(it, CAPTION_SP, scale) <= inner }
        if (range != null && room >= WidgetLine.caption * scale) {
            room -= WidgetLine.caption * scale
            val spoken = context.getString(R.string.widget_health_range_spoken, full)
            Text(range, GlanceModifier.semantics { contentDescription = spoken }, style = WidgetType.captionQuiet, maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        val line = LineGap + WidgetLine.data * scale
        health.healthPct?.takeIf { room >= line }?.let {
            room -= line
            Line(
                context.getString(R.string.widget_health_health),
                formats.percent(it.roundToInt()),
                Provenance.ESTIMATED,
            )
        }
        health.cycles?.takeIf { room >= line }?.let {
            Spacer(GlanceModifier.height(LineGap))
            Cycles(it, health.cyclesMeasured, formats, context)
        }
    }
}

/** The platform's cycle count as it is, Measured; Coulomb's own to a decimal, Derived. */
@Composable
private fun Cycles(
    cycles: Double,
    measured: Boolean,
    formats: Formats,
    context: Context,
) {
    Line(
        context.getString(R.string.widget_health_cycles),
        if (measured) formats.integer(cycles.roundToLong()) else formats.decimal(cycles),
        if (measured) Provenance.MEASURED else Provenance.DERIVED,
    )
}

@Composable
private fun Line(
    heading: String,
    value: String,
    provenance: Provenance,
) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(heading, GlanceModifier.defaultWeight(), style = WidgetType.label, maxLines = 1)
        Figure(value, provenance, WidgetType.data)
    }
}
