// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.REDUCED_FADE_MS
import io.github.akane599.coulomb.core.designsystem.theme.crossfadeTo

/**
 * The window selector of DESIGN.md (SPEC 10.3, ADR-0023 §4): [presets] form a radio group of
 * connected choices, and [more] sits after it, outside the group, because it opens a sheet. The
 * chosen window is the region's one copper selection: `accent-fill`, a leading check and a fully
 * round shape, morphing from the connected one. Choices wrap with 8 dp between lines rather than
 * scroll off the edge.
 *
 * @param selected the chosen preset's index, or -1 when the window came from the sheet.
 * @param more the sheet button's label: "More", or the sheet window's name while one is chosen.
 * @param moreChosen true while a window from the sheet is the chosen one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun CoulombWindowSelector(
    presets: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    more: String,
    moreChosen: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The 4 dp above and below each 40 dp choice belong to its touch target, so lines sit 8 dp apart.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(ChoiceGap)) {
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ChoiceGap)) {
            presets.forEachIndexed { i, label ->
                WindowChoice(label, i == selected, Corners(start = i == 0, end = false), Role.RadioButton, { onSelect(i) })
            }
        }
        WindowChoice(more, moreChosen, Corners(start = presets.isEmpty(), end = true), Role.Button, onMore, CoulombIcons.ExpandMore)
    }
}

/** Which ends of a connected choice are the group's outer, fully round ends. */
private data class Corners(
    val start: Boolean,
    val end: Boolean,
) {
    /** The corners [fraction] of the way from connected to fully round, for a [heightDp] tall choice. */
    fun shape(
        heightDp: Float,
        fraction: Float,
    ): Shape {
        val round = heightDp / 2
        val startDp = lerp(if (start) round else InnerCorner.value, round, fraction)
        val endDp = lerp(if (end) round else InnerCorner.value, round, fraction)
        return RoundedCornerShape(topStart = startDp.dp, topEnd = endDp.dp, bottomEnd = endDp.dp, bottomStart = startDp.dp)
    }
}

@Composable
private fun WindowChoice(
    label: String,
    chosen: Boolean,
    corners: Corners,
    role: Role,
    onClick: () -> Unit,
    @DrawableRes trailingIcon: Int? = null,
) {
    val colors = CoulombTheme.colors
    val density = LocalDensity.current
    val progress = selectionProgress(chosen)
    val interactions = remember { MutableInteractionSource() }
    // Read here, so the label and icons follow the fill; only this choice recomposes while it moves.
    val p = progress.value.coerceIn(0f, 1f)
    val ink = lerp(colors.textPrimary, colors.onAccentFill, p)
    Box(
        Modifier
            .selectable(selected = chosen, interactionSource = interactions, indication = null, role = role, onClick = onClick)
            .padding(vertical = TargetSlack),
    ) {
        Row(
            Modifier
                .heightIn(min = ChoiceHeight)
                .drawBehind {
                    val shape = corners.shape(size.height / density.density, progress.value.coerceIn(0f, 1f))
                    val fill = lerp(colors.surfaceRaised, colors.accentFill, progress.value.coerceIn(0f, 1f))
                    drawOutline(shape.createOutline(size, layoutDirection, this), fill)
                }.clip(corners.shape(ChoiceHeight.value, if (chosen) 1f else 0f))
                .indication(interactions, ripple())
                .padding(horizontal = CoulombSpacing.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckLead(chosen, ink)
            Text(label, style = CoulombTheme.typography.label, color = ink, softWrap = false)
            if (trailingIcon != null) {
                Spacer(Modifier.width(IconGap))
                Icon(painterResource(trailingIcon), contentDescription = null, modifier = Modifier.size(IconSize), tint = ink)
            }
        }
    }
}

/** The leading check of a chosen choice or tab: it slides in, or only fades under reduced motion. */
@Composable
private fun CheckLead(
    chosen: Boolean,
    tint: Color,
) {
    val reduced = CoulombTheme.reducedMotion
    AnimatedVisibility(
        visible = chosen,
        enter =
            if (reduced) {
                fadeIn(tween(REDUCED_FADE_MS))
            } else {
                expandHorizontally(CoulombMotion.spatial()) +
                    fadeIn(CoulombMotion.effects())
            },
        exit =
            if (reduced) {
                fadeOut(tween(REDUCED_FADE_MS))
            } else {
                shrinkHorizontally(CoulombMotion.spatial()) +
                    fadeOut(CoulombMotion.effects())
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(CoulombIcons.Check), contentDescription = null, modifier = Modifier.size(IconSize), tint = tint)
            Spacer(Modifier.width(IconGap))
        }
    }
}

/** 0 when not selected and 1 when selected, on the spatial spring; a 150 ms crossfade under reduced motion (SPEC 11.6). */
@Composable
private fun selectionProgress(selected: Boolean): Animatable<Float, *> {
    val reduced = CoulombTheme.reducedMotion
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) {
        val target = if (selected) 1f else 0f
        if (progress.value == target) return@LaunchedEffect
        if (reduced) progress.crossfadeTo(target) else progress.animateTo(target, CoulombMotion.spatial())
    }
    return progress
}

/**
 * Drain's view tabs (DESIGN.md, View tabs): equal segments in a hairline outline that switch views
 * of the same data. The selected tab is `surface-raised` with a check, in ink; never copper, since
 * the window above is the region's copper selection. A long label widens only its own segment, and
 * the tabs stack when the row is narrower than 14 em of their label size, or than their labels, so
 * they follow the text size.
 */
@Composable
public fun CoulombViewTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = CoulombTheme.typography.label
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        // Each segment's own width with the check, so selecting a tab never flips the layout.
        val chrome = with(density) { (TabSidePadding * 2 + IconSize + IconGap + HairlineWidth * 2).roundToPx() }
        val natural = tabs.map { measurer.measure(it, style, softWrap = false).size.width + chrome }
        val stacked = width < with(density) { (style.fontSize * STACK_EM).toPx() } || natural.sum() > width
        val tab: @Composable (Int) -> Unit = { i -> ViewTab(tabs[i], i == selected, { onSelect(i) }, tabShape(i, tabs.size, stacked)) }
        if (stacked) {
            Column(Modifier.selectableGroup().fillMaxWidth()) {
                tabs.indices.forEach { i ->
                    // Each segment overlaps the one above by its hairline, so joins stay 1 dp.
                    Box(Modifier.offset(y = -HairlineWidth * i)) { tab(i) }
                }
            }
        } else {
            SegmentRow(natural, Modifier.selectableGroup()) { tabs.indices.forEach { tab(it) } }
        }
    }
}

/** Lays segments side by side: equal shares, but never narrower than [natural]; joins overlap by the hairline. */
@Composable
private fun SegmentRow(
    natural: List<Int>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hairline = with(LocalDensity.current) { HairlineWidth.roundToPx() }
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val widths = segmentWidths(natural, width + hairline * (natural.size - 1))
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i])) }
        layout(width, placeables.maxOf { it.height }) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, 0)
                x += it.width - hairline
            }
        }
    }
}

/** Equal shares of [width], except that a segment never narrows below its own label. */
internal fun segmentWidths(
    natural: List<Int>,
    width: Int,
): List<Int> {
    val result = IntArray(natural.size)
    var free = width
    var open = natural.indices.toList()
    while (open.isNotEmpty()) {
        val share = free / open.size
        val wide = open.filter { natural[it] > share }
        if (wide.isEmpty()) {
            val spare = free - share * open.size
            open.forEachIndexed { k, i -> result[i] = share + if (k < spare) 1 else 0 }
            break
        }
        wide.forEach {
            result[it] = natural[it]
            free -= natural[it]
        }
        open = open - wide.toSet()
    }
    return result.toList()
}

@Composable
private fun ViewTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
) {
    val colors = CoulombTheme.colors
    val progress = selectionProgress(selected)
    val interactions = remember { MutableInteractionSource() }
    Box(
        Modifier
            .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
            .padding(vertical = TargetSlack),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ChoiceHeight)
                .drawBehind {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val p = progress.value.coerceIn(0f, 1f)
                    if (p > 0f) drawOutline(outline, colors.surfaceRaised.copy(alpha = p))
                    drawOutline(outline, colors.hairline, style = Stroke(HairlineWidth.toPx()))
                }.clip(shape)
                .indication(interactions, ripple())
                .padding(horizontal = TabSidePadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckLead(selected, colors.textPrimary)
            Text(label, style = CoulombTheme.typography.label, color = colors.textPrimary, softWrap = false)
        }
    }
}

/**
 * Fully round outer ends and square inner joins (DESIGN.md, Connected groups); stacked, the first
 * and last segments round their outer corners at 12 dp.
 */
private fun tabShape(
    index: Int,
    count: Int,
    stacked: Boolean,
): Shape {
    val first = index == 0
    val last = index == count - 1
    return when {
        stacked -> {
            RoundedCornerShape(
                topStart = if (first) CoulombRadius.Row else 0.dp,
                topEnd = if (first) CoulombRadius.Row else 0.dp,
                bottomEnd = if (last) CoulombRadius.Row else 0.dp,
                bottomStart = if (last) CoulombRadius.Row else 0.dp,
            )
        }

        else -> {
            RoundedCornerShape(
                topStartPercent = if (first) HALF else 0,
                bottomStartPercent = if (first) HALF else 0,
                topEndPercent = if (last) HALF else 0,
                bottomEndPercent = if (last) HALF else 0,
            )
        }
    }
}

/**
 * A switch row of DESIGN.md: Body text and a neutral switch, never copper. The whole row toggles,
 * so its touch target is the row's 56 dp.
 */
@Composable
public fun CoulombSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    Row(
        modifier
            .heightIn(min = SwitchRowHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space16),
    ) {
        Text(text, Modifier.weight(1f), style = CoulombTheme.typography.body, color = colors.textPrimary)
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = colors.textPrimary,
                    checkedThumbColor = colors.bg,
                    checkedBorderColor = colors.textPrimary,
                    uncheckedTrackColor = colors.surfaceRaised,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedBorderColor = colors.textSecondary,
                ),
        )
    }
}

private const val STACK_EM = 14f
private const val HALF = 50
private val InnerCorner: Dp = CoulombRadius.Chip
private val ChoiceHeight = 40.dp
private val ChoiceGap = 2.dp
private val TargetSlack = 4.dp
private val TabSidePadding = 8.dp
private val IconSize = 18.dp
private val IconGap = 6.dp
private val HairlineWidth = 1.dp
private val SwitchRowHeight = 56.dp
