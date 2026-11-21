// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
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
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.formatsFor
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import io.github.akane599.coulomb.core.ui.story.StoryCopy

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

/** The width the level and its state take beside the verdict. */
private val LevelColumn = 96.dp

/**
 * The level with its state. Where the widget is tall enough the state sits under the level, and
 * the verdict follows, under them or beside them on a wide widget, when the whole sentence fits
 * ([WidgetFit]); it is never cut.
 */
@Composable
internal fun LevelContent(level: LevelFacts?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val stacked = size.height >= StackedFrom
    WidgetSurface(OpenRequest.NOW, verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically) {
        if (level == null) {
            Empty(R.string.widget_level_empty)
            return@WidgetSurface
        }
        val formats = formatsFor(context)
        val percent = formats.percent(level.levelPct)
        val state = context.getString(level.state.label())
        if (!stacked) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Figure(percent, Provenance.MODELED, WidgetType.hero)
                Spacer(GlanceModifier.defaultWeight())
                Text(state, style = WidgetType.label, maxLines = 1)
            }
            return@WidgetSurface
        }
        val verdict = level.story?.let { StoryCopy(context.resources, formats, InsightCopy(context.resources, formats)).line(it).text }
        val scale = context.resources.configuration.fontScale
        val inner = DpSize(size.width - WidgetPadding * 2, size.height - WidgetPadding * 2)
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
