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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.formatsFor
import java.time.ZoneId

/** Last night, 4×2 (SPEC 12.4). */
internal class NightWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val store = context.widgetStore()
        store.ensure(WidgetKind.NIGHT)
        provideContent {
            val data by store.state.collectAsState()
            NightContent(data.night, System.currentTimeMillis(), ZoneId.systemDefault())
        }
    }

    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) = provideContent { NightContent(Samples.night(System.currentTimeMillis()), System.currentTimeMillis(), ZoneId.systemDefault()) }
}

class NightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NightWidget()
}

private val HeadGap = 8.dp
private val FiguresGap = 8.dp
private val ColumnGap = 16.dp
private const val MS_PER_MINUTE = 60_000L

/**
 * The last screen-off stretch of an hour or more. Its rate is the hero (Derived); below the three
 * points a rate needs, the level lost stands in (Modeled), and on the charger the widget says so
 * (ADR-0056 §4). Under it, how long it lasted and how much of it the CPU slept, both Measured.
 */
@Composable
internal fun NightContent(
    night: NightSummary?,
    nowMs: Long,
    zone: ZoneId,
) {
    val context = LocalContext.current
    WidgetSurface(OpenRequest.NIGHT) {
        val formats = formatsFor(context)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(context.getString(R.string.widget_night_title), style = WidgetType.label, maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            if (night != null) {
                val ended = context.getString(R.string.widget_night_ended, formats.clock(night.endWallMs, zone, nowMs))
                Text(ended, style = WidgetType.captionQuiet, maxLines = 1)
            }
        }
        Spacer(GlanceModifier.height(HeadGap))
        if (night == null) {
            Empty(R.string.widget_night_empty)
            return@WidgetSurface
        }
        NightHero(night, formats)
        Spacer(GlanceModifier.height(FiguresGap))
        Row(GlanceModifier.fillMaxWidth()) {
            Small(context.getString(R.string.widget_night_duration), formats.duration(night.durationMs / MS_PER_MINUTE))
            night.deepSleepShare?.takeUnless { night.charged }?.let {
                Spacer(GlanceModifier.width(ColumnGap))
                Small(context.getString(R.string.widget_night_deep_sleep), formats.share(it))
            }
        }
    }
}

@Composable
private fun NightHero(
    night: NightSummary,
    formats: Formats,
) {
    val context = LocalContext.current
    val rate = night.ratePctPerHour
    when {
        night.charged -> {
            Text(context.getString(R.string.widget_night_charged), style = WidgetType.title, maxLines = 1)
        }

        rate != null -> {
            Figure(formats.rate(rate), Provenance.DERIVED, WidgetType.hero, unit = formats.rateUnit)
        }

        else -> {
            val lost = formats.percent((night.startLevel - night.endLevel).coerceAtLeast(0))
            Figure(context.getString(R.string.widget_night_lost, lost), Provenance.MODELED, WidgetType.title)
        }
    }
}

/** A heading over its Measured figure. */
@Composable
private fun Small(
    heading: String,
    value: String,
) {
    Column {
        Text(heading, style = WidgetType.label, maxLines = 1)
        Figure(value, Provenance.MEASURED, WidgetType.data)
    }
}
