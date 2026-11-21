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
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.formatsFor
import io.github.akane599.coulomb.core.ui.R as UiR

/** Top drains, 4×2 (SPEC 12.4). */
internal class DrainsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val store = context.widgetStore()
        store.ensure(WidgetKind.DRAINS)
        provideContent {
            val data by store.state.collectAsState()
            DrainsContent(data.drains)
        }
    }

    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) = provideContent { DrainsContent(Samples.drains) }
}

class DrainsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DrainsWidget()
}

private val HeadingLine = 18.dp

/** A row: 8 dp above, the name's line, 4 dp, the bar. */
private val RowHeight = 36.dp
private val RowGap = 8.dp
private val TightGap = 6.dp
private val BarGap = 4.dp
private val BarHeight = 4.dp
private val ValueGap = 8.dp

/**
 * The window's name, then as many of the three largest apps as the height holds, each with its
 * share and DESIGN.md's share bar: 4 dp, `hairline` track, `text-secondary` fill. The shares are
 * Derived; one mark on the heading covers them.
 */
@Composable
internal fun DrainsContent(drains: DrainFacts?) {
    val context = LocalContext.current
    val size = LocalSize.current
    WidgetSurface(OpenRequest.DRAIN) {
        val scale = context.resources.configuration.fontScale
        // What the rows are shares of, with the window where the line holds both.
        val long = context.getString(R.string.widget_drains_title)
        val inner = (size.width - WidgetPadding * 2 - WidgetLine.mark).value
        val title =
            if (WidgetFit.width(long, WidgetLine.LABEL_SP, scale) <=
                inner
            ) {
                long
            } else {
                context.getString(R.string.widget_drains_title_short)
            }
        if (drains == null) {
            // The mark is for figures: a heading over none carries no mark.
            Text(title, style = WidgetType.label, maxLines = 1)
            Spacer(GlanceModifier.height(RowGap))
            Empty(R.string.widget_drains_empty, R.string.widget_drains_empty_short, used = HeadingLine + RowGap)
            return@WidgetSurface
        }
        val spoken = context.getString(R.string.widget_figure_spoken, title, context.getString(UiR.string.provenance_derived))
        Row(GlanceModifier.semantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = WidgetType.label, maxLines = 1)
            Spacer(GlanceModifier.width(BarGap))
            Mark(Provenance.DERIVED)
        }
        val formats = formatsFor(context)
        val room = (size.height - WidgetPadding * 2 - HeadingLine * scale).value
        val loose = WidgetFit.rows(room, RowHeight.value, scale, WidgetFacts.TOP_DRAINS)
        // Where a tighter gap lets one more app in, the gap gives way.
        val tight = WidgetFit.rows(room, (RowHeight - RowGap + TightGap).value, scale, WidgetFacts.TOP_DRAINS)
        val gap = if (tight > loose) TightGap else RowGap
        // One child a row: Glance keeps at most ten children of a column.
        drains.rows.take(maxOf(loose, tight, 1)).forEach { row ->
            DrainLine(formats.appName(row.label, row.uid), formats.share(row.share), row.share, gap)
        }
    }
}

@Composable
private fun DrainLine(
    name: String,
    share: String,
    fraction: Double,
    gap: Dp,
) {
    Column(GlanceModifier.fillMaxWidth()) {
        Spacer(GlanceModifier.height(gap))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, GlanceModifier.defaultWeight(), style = WidgetType.data, maxLines = 1)
            Spacer(GlanceModifier.width(ValueGap))
            Text(share, style = WidgetType.data, maxLines = 1)
        }
        Spacer(GlanceModifier.height(BarGap))
        LinearProgressIndicator(
            progress = fraction.toFloat().coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(BarHeight),
            color = WidgetColors.textSecondary,
            backgroundColor = WidgetColors.hairline,
        )
    }
}
