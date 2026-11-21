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
import androidx.glance.text.Text
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.model.Provenance
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

/** "4,712 mAh" with its mark at 28 sp needs this width; narrower, it is set in Title. */
private val HeroFrom = 176.dp
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
        Text(context.getString(R.string.widget_health_title), style = WidgetType.label, maxLines = 1)
        if (health == null) {
            Spacer(GlanceModifier.height(HeadGap))
            Empty(R.string.widget_health_empty)
            return@WidgetSurface
        }
        val formats = formatsFor(context)
        val scale = context.resources.configuration.fontScale
        val capacity = formats.mah(health.capacityMah)
        val hero = if (size.width >= HeroFrom * scale) WidgetType.hero else WidgetType.title
        Figure(capacity.number, Provenance.ESTIMATED, hero, unit = capacity.unit)
        Spacer(GlanceModifier.defaultWeight())
        health.healthPct?.let {
            Line(
                context.getString(R.string.widget_health_health),
                formats.percent(it.roundToInt()),
                Provenance.ESTIMATED,
            )
        }
        health.cycles?.let {
            Spacer(GlanceModifier.height(LineGap))
            val count = if (health.cyclesMeasured) formats.integer(it.roundToLong()) else formats.decimal(it)
            Line(
                context.getString(R.string.widget_health_cycles),
                count,
                if (health.cyclesMeasured) Provenance.MEASURED else Provenance.DERIVED,
            )
        }
    }
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
