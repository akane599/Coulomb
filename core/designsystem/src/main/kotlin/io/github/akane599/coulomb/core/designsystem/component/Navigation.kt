// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

// DESIGN.md (Shell, Navigation, nav-pill-selected): an 80 dp bar, a 64 x 32 dp pill, 4 dp to the label.
private val BarHeight = 80.dp
private val RailWidth = 80.dp
private val RailItemHeight = 64.dp
private val PillWidth = 64.dp
private val PillHeight = 32.dp
private val Hairline = 1.dp

/**
 * The bottom navigation bar of DESIGN.md for compact widths: `surface` with a hairline above, at
 * least 80 dp plus the gesture area. It grows when large text wraps a label. Material 3 1.5's
 * `NavigationBarItem` draws a 56 dp pill, so Coulomb draws its own 64 dp one.
 *
 * @param content four [CoulombNavigationBarItem]s.
 */
@Composable
public fun CoulombNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = CoulombTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(thickness = Hairline, color = colors.hairline)
        Row(
            modifier =
                Modifier
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .heightIn(min = BarHeight)
                    .selectableGroup(),
            content = content,
        )
    }
}

/** One destination in [CoulombNavigationBar]. [selectedIcon] is the symbol's fill variant. */
@Composable
public fun RowScope.CoulombNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    @DrawableRes selectedIcon: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    NavigationItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        selectedIcon = selectedIcon,
        label = label,
        modifier = modifier.weight(1f).heightIn(min = BarHeight),
    )
}

/**
 * The navigation rail for medium and expanded widths (SPEC 10.10): the bar's items stacked on
 * `surface`, 80 dp wide, with a hairline on the content side. The first item lines up with the
 * 64 dp top app bar beside it.
 *
 * @param content four [CoulombNavigationRailItem]s.
 */
@Composable
public fun CoulombNavigationRail(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CoulombTheme.colors
    Row(modifier.fillMaxHeight().background(colors.surface)) {
        Column(
            modifier =
                Modifier
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    ).width(RailWidth)
                    .selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        VerticalDivider(thickness = Hairline, color = colors.hairline)
    }
}

/** One destination in [CoulombNavigationRail]. [selectedIcon] is the symbol's fill variant. */
@Composable
public fun CoulombNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    @DrawableRes selectedIcon: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    NavigationItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        selectedIcon = selectedIcon,
        label = label,
        modifier = modifier.fillMaxWidth().heightIn(min = RailItemHeight),
    )
}

/**
 * The pill over its label. The current destination's pill is `accent-fill` with `on-accent-fill`
 * ink and its label turns `text-primary`; the label stays Caption nav, so its weight never changes
 * (DESIGN.md, Navigation). The whole item is the touch target; the ripple stays inside the pill.
 */
@Composable
private fun NavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    @DrawableRes selectedIcon: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val pill by animateColorAsState(
        targetValue = if (selected) colors.accentFill else colors.accentFill.copy(alpha = 0f),
        animationSpec = CoulombMotion.effects(),
        label = "pill",
    )
    Column(
        modifier =
            modifier
                .selectable(
                    selected = selected,
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ).padding(vertical = CoulombSpacing.Space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(PillWidth, PillHeight)
                    .clip(CircleShape)
                    .background(pill)
                    .indication(interactions, ripple(color = colors.textPrimary)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (selected) selectedIcon else icon),
                contentDescription = null,
                tint = if (selected) colors.onAccentFill else colors.textSecondary,
            )
        }
        Spacer(Modifier.height(CoulombSpacing.Space4))
        Text(
            text = label,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            textAlign = TextAlign.Center,
            style = CoulombTheme.typography.captionNav,
        )
    }
}
