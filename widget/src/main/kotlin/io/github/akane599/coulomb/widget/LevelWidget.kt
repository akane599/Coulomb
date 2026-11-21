// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.formatsFor
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import io.github.akane599.coulomb.core.ui.story.StoryCopy
import io.github.akane599.coulomb.core.ui.story.lastReadingLine
import java.time.ZoneId

/** Level and verdict, 2×1 (SPEC 12.4). */
internal class LevelWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val store = context.widgetStore()
        store.ensure(WidgetKind.LEVEL)
        provideContent {
            val data by store.state.collectAsState()
            LevelContent(data.level)
        }
    }

    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) = provideContent { LevelContent(Samples.level) }
}

class LevelWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LevelWidget()
}

private val state: Map<PowerState, Int> =
    mapOf(
        PowerState.ON_BATTERY to R.string.widget_state_battery,
        PowerState.CHARGING to R.string.widget_state_charging,
        PowerState.FULL to R.string.widget_state_full,
        PowerState.PLUGGED to R.string.widget_state_plugged,
    )

internal fun PowerState.label(): Int = state.getValue(this)

/** The level and its state stacked need this height. */
private val StackedFrom = 76.dp

/** From this width the verdict sits beside the level instead of under it. */
private val BesideFrom = 250.dp
private val HeroLine = 34.dp
private val LabelLine = 18.dp
private val VerdictGap = 4.dp
private val BesideGap = 16.dp
private val StateGap = 8.dp
private val OldReadingSize = 12.dp

/** The width the level and its state take beside the verdict. */
private val LevelColumn = 96.dp

/**
 * The level with its state. Where the widget is tall enough the state sits under the level, and
 * the verdict follows, under them or beside them on a wide widget, when the whole sentence fits
 * ([WidgetFit]); it is never cut.
 */
@Composable
internal fun LevelContent(
    level: LevelFacts?,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val stacked = size.height >= StackedFrom
    WidgetSurface(OpenRequest.NOW, verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically) {
        if (level == null) {
            Empty(R.string.widget_level_empty, R.string.widget_level_empty_short)
            return@WidgetSurface
        }
        val formats = formatsFor(context)
        val percent = formats.percent(level.levelPct)
        val state = stateLine(context, level, formats, zone)
        val scale = context.resources.configuration.fontScale
        val inner = DpSize(size.width - WidgetPadding * 2, size.height - WidgetPadding * 2)
        if (!stacked) {
            OneLine(percent, state, oneLineTimes(context, level, formats, zone), inner, scale)
            return@WidgetSurface
        }
        // An old reading's verdict names its end, as Now's does (ADR-0069).
        val verdict =
            level.story?.let {
                if (level.asOfWallMs != null) {
                    lastReadingLine(context.resources, formats, it.span)
                } else {
                    StoryCopy(context.resources, formats, InsightCopy(context.resources, formats)).line(it).text
                }
            }
        if (size.width >= BesideFrom) {
            val width = inner.width - LevelColumn * scale - BesideGap
            val lines = verdict?.let { WidgetFit.lines(it, width.value, inner.height.value, scale) }
            Row(GlanceModifier.fillMaxWidth()) {
                Column {
                    Figure(percent, Provenance.MODELED, WidgetType.hero)
                    Text(state, style = WidgetType.label, maxLines = 1)
                }
                if (verdict != null && lines != null) {
                    Spacer(GlanceModifier.width(BesideGap))
                    Text(verdict, GlanceModifier.defaultWeight(), style = WidgetType.caption, maxLines = lines)
                }
            }
            return@WidgetSurface
        }
        Figure(percent, Provenance.MODELED, WidgetType.hero)
        Text(state, style = WidgetType.label, maxLines = 1)
        val room = inner.height - (HeroLine + LabelLine) * scale - VerdictGap
        val lines = verdict?.let { WidgetFit.lines(it, inner.width.value, room.value, scale) } ?: return@WidgetSurface
        Spacer(GlanceModifier.height(VerdictGap))
        Text(verdict, style = WidgetType.caption, maxLines = lines)
    }
}

/** The state in words, with the reading's time when it may not be now (ADR-0069). */
private fun stateLine(
    context: Context,
    level: LevelFacts,
    formats: Formats,
    zone: ZoneId,
): String {
    val at = level.asOfWallMs ?: return context.getString(level.state.label())
    // The time takes the state word's place, so it fits wherever the state did; with its day, since
    // the widget is not drawn again at midnight.
    return context.getString(R.string.widget_as_of, formats.dayClock(at, zone))
}

/**
 * An old reading's time for the one-line form, longest first: "As of Sun 2:30 PM", "Sun 2:30 PM".
 * Always with its day from [Formats.dayClock], as in the stacked form, since the widget is not drawn
 * again at midnight (ADR-0069). Empty for a current reading.
 */
private fun oneLineTimes(
    context: Context,
    level: LevelFacts,
    formats: Formats,
    zone: ZoneId,
): List<String> {
    val at = level.asOfWallMs ?: return emptyList()
    val day = formats.dayClock(at, zone)
    return listOf(context.getString(R.string.widget_as_of, day), day)
}

/**
 * The first of [lines] that fits beside the level, with the level's size: each line at hero size if
 * the height holds it, else at title size, before a shorter line. A fuller time beats a larger number.
 */
private fun chooseLine(
    percent: String,
    lines: List<String>,
    inner: DpSize,
    scale: Float,
): Pair<String?, Boolean> {
    fun fits(
        line: String,
        hero: Boolean,
    ) = WidgetFit.width(percent, if (hero) WidgetLine.HERO_SP else WidgetLine.TITLE_SP, scale) + WidgetLine.mark.value +
        StateGap.value + WidgetFit.width(line, WidgetLine.LABEL_SP, scale) <= inner.width.value
    val tall = inner.height >= WidgetLine.hero * scale
    for (line in lines) {
        if (tall && fits(line, hero = true)) return line to true
        if (fits(line, hero = false)) return line to false
    }
    return null to tall
}

/**
 * The level on one line with its state, or an old reading's time, where the width holds both. The
 * time takes the longest form that fits beside the level at hero or title size; with none, it goes
 * under the level where the height holds two lines. Where neither holds (the smallest size, or the
 * 2×1 at large text) the level is drawn muted with a small clock, so neither colour alone nor a bare
 * number says it is now, and TalkBack says its time; a tap opens Now, which shows it (ADR-0069).
 * Never cut (SPEC 12.4).
 */
@Composable
private fun OneLine(
    percent: String,
    state: String,
    times: List<String>,
    inner: DpSize,
    scale: Float,
) {
    val (line, large) = chooseLine(percent, times.ifEmpty { listOf(state) }, inner, scale)
    val under = times.firstOrNull { WidgetFit.width(it, WidgetLine.LABEL_SP, scale) <= inner.width.value }
    if (line == null && under != null && inner.height >= (WidgetLine.title + WidgetLine.label) * scale) {
        Column {
            Figure(percent, Provenance.MODELED, WidgetType.title)
            Text(under, style = WidgetType.label, maxLines = 1)
        }
        return
    }
    val style = if (large) WidgetType.hero else WidgetType.title
    val untimed = line == null && times.isNotEmpty()
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Figure(
            percent,
            Provenance.MODELED,
            if (untimed) style.copy(color = WidgetColors.textSecondary) else style,
            spokenNote = times.firstOrNull()?.takeIf { untimed },
        )
        if (untimed) {
            Spacer(GlanceModifier.width(StateGap / 2))
            Image(
                provider = ImageProvider(R.drawable.widget_old_reading),
                contentDescription = null,
                modifier = GlanceModifier.size(OldReadingSize),
                colorFilter = ColorFilter.tint(WidgetColors.textSecondary),
            )
        }
        if (line != null) {
            Spacer(GlanceModifier.defaultWeight())
            Text(line, style = WidgetType.label, maxLines = 1)
        }
    }
}
