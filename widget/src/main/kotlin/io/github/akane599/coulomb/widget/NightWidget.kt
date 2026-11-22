// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
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
            NightContent(data.night, ZoneId.systemDefault())
        }
    }

    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) = provideContent { NightContent(Samples.night(System.currentTimeMillis()), ZoneId.systemDefault()) }
}

class NightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NightWidget()
}

private val HeadGap = 8.dp
private val FiguresGap = 8.dp
private val TightGap = 4.dp
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
    zone: ZoneId,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val scale = context.resources.configuration.fontScale
    // What the height holds, largest first (SPEC 12.4: nothing is cut).
    val room = size.height - WidgetPadding * 2
    val head = WidgetLine.label * scale + HeadGap
    val hero = WidgetLine.hero * scale
    val fit =
        when {
            room >= head + hero + FiguresGap + (WidgetLine.label + WidgetLine.data) * scale -> NightFit.FULL
            room >= head + hero + TightGap + WidgetLine.data * scale -> NightFit.DURATION
            room >= head + hero -> NightFit.HERO
            else -> NightFit.BARE
        }
    WidgetSurface(OpenRequest.NIGHT, verticalAlignment = if (fit == NightFit.BARE) Alignment.CenterVertically else Alignment.Top) {
        val formats = formatsFor(context)
        if (fit != NightFit.BARE || night == null) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.widget_night_title), style = WidgetType.label, maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                if (night != null) {
                    // Always with its weekday: the widget is drawn once and may be read days later.
                    val ended = context.getString(R.string.widget_night_ended, formats.dayClock(night.endWallMs, zone))
                    Text(ended, style = WidgetType.captionQuiet, maxLines = 1)
                }
            }
            Spacer(GlanceModifier.height(HeadGap))
        }
        if (night == null) {
            Empty(R.string.widget_night_empty, R.string.widget_night_empty_short, used = WidgetLine.label + HeadGap)
            return@WidgetSurface
        }
        if (fit == NightFit.BARE) {
            BareNight(night, formats, zone, large = room >= hero, innerWidth = size.width - WidgetPadding * 2, scale = scale)
            return@WidgetSurface
        }
        NightHero(night, formats, large = true)
        if (fit == NightFit.HERO) return@WidgetSurface
        Spacer(GlanceModifier.height(if (fit == NightFit.DURATION) TightGap else FiguresGap))
        val duration = formats.duration(night.durationMs / MS_PER_MINUTE)
        if (fit == NightFit.DURATION) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.widget_night_duration),
                    GlanceModifier.defaultWeight(),
                    style = WidgetType.label,
                    maxLines = 1,
                )
                Figure(duration, Provenance.MEASURED, WidgetType.data)
            }
            return@WidgetSurface
        }
        Row(GlanceModifier.fillMaxWidth()) {
            Small(context.getString(R.string.widget_night_duration), duration)
            night.deepSleepShare?.takeUnless { night.charged }?.let {
                Spacer(GlanceModifier.width(ColumnGap))
                Small(context.getString(R.string.widget_night_deep_sleep), formats.share(it))
            }
        }
    }
}

/** How much of the night a height holds. */
private enum class NightFit {
    /** The head, the hero and both figures under their headings. */
    FULL,

    /** The head, the hero and the duration on one line. */
    DURATION,

    /** The head and the hero. */
    HERO,

    /** The hero alone. */
    BARE,
}

@Composable
private fun NightHero(
    night: NightSummary,
    formats: Formats,
    large: Boolean,
    /** The night's day, said after the figure where it is not drawn. */
    spokenNote: String? = null,
) {
    val context = LocalContext.current
    val rate = night.ratePctPerHour
    when {
        night.charged -> {
            Text(context.getString(R.string.widget_night_charged), style = WidgetType.title, maxLines = 1)
        }

        rate != null -> {
            Figure(
                formats.rate(rate),
                Provenance.DERIVED,
                if (large) WidgetType.hero else WidgetType.title,
                unit = formats.rateUnit,
                spokenNote = spokenNote,
            )
        }

        else -> {
            val lost = formats.percent((night.startLevel - night.endLevel).coerceAtLeast(0))
            Figure(context.getString(R.string.widget_night_lost, lost), Provenance.MODELED, WidgetType.title, spokenNote = spokenNote)
        }
    }
}

/**
 * The night where the head does not fit (the smallest size at large text): the figure with the
 * night's day beside it where the width holds both, and always said after it, since the widget may
 * be read days later (gate run 8). Never cut (SPEC 12.4).
 */
@Composable
private fun BareNight(
    night: NightSummary,
    formats: Formats,
    zone: ZoneId,
    large: Boolean,
    innerWidth: Dp,
    scale: Float,
) {
    val context = LocalContext.current
    val day = formats.dayClock(night.endWallMs, zone)
    val ended = context.getString(R.string.widget_night_ended, day)
    val figure =
        night.ratePctPerHour?.takeUnless { night.charged }?.let { formats.rate(it) + formats.rateUnit }
            ?: context.getString(R.string.widget_night_charged)
    val figureSp = if (large) WidgetLine.HERO_SP else WidgetLine.TITLE_SP
    val fits =
        WidgetFit.width(figure, figureSp, scale) + WidgetLine.mark.value + BareGap.value +
            WidgetFit.width(day, WidgetLine.LABEL_SP, scale) <= innerWidth.value
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NightHero(night, formats, large, spokenNote = ended)
        if (fits) {
            Spacer(GlanceModifier.defaultWeight())
            Text(day, style = WidgetType.captionQuiet, maxLines = 1)
        }
    }
}

private val BareGap = 8.dp

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
