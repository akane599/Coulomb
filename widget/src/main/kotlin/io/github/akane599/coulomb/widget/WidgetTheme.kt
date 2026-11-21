// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.akane599.coulomb.core.designsystem.theme.DarkColors
import io.github.akane599.coulomb.core.designsystem.theme.LightColors
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.R as UiR

/** DESIGN.md's tokens for a widget: the launcher picks day or night. */
internal object WidgetColors {
    val bg = ColorProvider(LightColors.bg, DarkColors.bg)
    val textPrimary = ColorProvider(LightColors.textPrimary, DarkColors.textPrimary)
    val textSecondary = ColorProvider(LightColors.textSecondary, DarkColors.textSecondary)
    val hairline = ColorProvider(LightColors.hairline, DarkColors.hairline)
}

/**
 * DESIGN.md's type roles at their sizes. A launcher draws a widget with the system face, and its
 * weights are 400, 500 and 700: the 600 roles take 700 (ADR-0058).
 */
internal object WidgetType {
    /** Headline: the widget's one hero figure. */
    val hero = TextStyle(color = WidgetColors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)

    /** Title: a hero where 28 sp has no room. */
    val title = TextStyle(color = WidgetColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

    /** Data: row values. */
    val data = TextStyle(color = WidgetColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)

    /** Data, for a unit beside a hero. */
    val unit = TextStyle(color = WidgetColors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Normal)

    /** Label: headings. */
    val label = TextStyle(color = WidgetColors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)

    /** Caption: the verdict, details, empty states. */
    val caption = TextStyle(color = WidgetColors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val captionQuiet = TextStyle(color = WidgetColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Normal)
}

internal val WidgetPadding = 12.dp

/** The widget's ground: `bg`, the system's widget corners, and one tap target that opens [open] in the app. */
@Composable
internal fun WidgetSurface(
    open: String,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(WidgetColors.bg)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(actionStartActivity(context.openIntent(open)))
                .padding(WidgetPadding),
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/** One of DESIGN.md's four provenance marks, 12 dp in `text-secondary`. The figure's row names it. */
@Composable
internal fun Mark(provenance: Provenance) {
    val shape =
        when (provenance) {
            Provenance.MEASURED -> R.drawable.widget_mark_measured
            Provenance.MODELED -> R.drawable.widget_mark_modeled
            Provenance.ESTIMATED -> R.drawable.widget_mark_estimated
            Provenance.DERIVED -> R.drawable.widget_mark_derived
        }
    Image(
        provider = ImageProvider(shape),
        contentDescription = null,
        modifier = GlanceModifier.size(MarkSize),
        colorFilter = ColorFilter.tint(WidgetColors.textSecondary),
    )
}

private val MarkSize = 12.dp
private val MarkGap = 4.dp

/** A number, its unit and its mark on one line that TalkBack reads as one: "4,712 mAh, Estimated". */
@Composable
internal fun Figure(
    number: String,
    provenance: Provenance,
    style: TextStyle,
    modifier: GlanceModifier = GlanceModifier,
    unit: String? = null,
) {
    val context = LocalContext.current
    val name =
        context.getString(
            when (provenance) {
                Provenance.MEASURED -> UiR.string.provenance_measured
                Provenance.MODELED -> UiR.string.provenance_modeled
                Provenance.ESTIMATED -> UiR.string.provenance_estimated
                Provenance.DERIVED -> UiR.string.provenance_derived
            },
        )
    val spoken = context.getString(R.string.widget_figure_spoken, listOfNotNull(number, unit).joinToString(" "), name)
    Row(modifier.semantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = style, maxLines = 1)
        if (unit != null) {
            Spacer(GlanceModifier.width(MarkGap))
            Text(unit, style = WidgetType.unit, maxLines = 1)
        }
        Spacer(GlanceModifier.width(MarkGap))
        Mark(provenance)
    }
}

/** What a widget says while it has nothing to show. */
@Composable
internal fun Empty(text: Int) {
    Text(LocalContext.current.getString(text), style = WidgetType.captionQuiet)
}
