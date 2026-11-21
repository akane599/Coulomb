// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.R
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter

private val AppBarHeight = 64.dp

/**
 * The small top app bar of DESIGN.md (Shell, Navigation): 64 dp on `bg`, the title on the left in
 * Title, then up to three 48 dp icon buttons in `text-secondary`. A long title wraps to more lines
 * and the bar grows; it never truncates (SPEC 11.3). The bar pads itself for the status bar and the
 * side cutouts.
 *
 * @param navigationIcon a [CoulombBackButton] on screens below a destination; the title then starts
 *   beside it instead of at the gutter.
 * @param actions [CoulombIconButton]s, at most three.
 */
@Composable
public fun CoulombTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = CoulombTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = AppBarHeight)
                .padding(
                    start = if (navigationIcon == null) currentGutter() else CoulombSpacing.Space4,
                    end = CoulombSpacing.Space4,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
            navigationIcon?.invoke()
            Text(
                text = title,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(
                            start = if (navigationIcon == null) 0.dp else CoulombSpacing.Space4,
                            top = CoulombSpacing.Space8,
                            bottom = CoulombSpacing.Space8,
                        ).semantics { heading() },
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
            actions()
        }
    }
}

/**
 * A 48 dp icon button in `text-secondary`, wherever it stands: Material's own default is black,
 * which a dark ground swallows. A long press or a pointer resting on it shows [contentDescription],
 * since an icon alone names nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CoulombIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip(containerColor = colors.textPrimary, contentColor = colors.bg) {
                Text(contentDescription, style = CoulombTheme.typography.caption)
            }
        },
        state = rememberTooltipState(),
    ) {
        // The caller's modifier stays on the button: its semantics and the icon's are one node.
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(painter = painterResource(icon), contentDescription = contentDescription, tint = colors.textSecondary)
        }
    }
}

/** The back button for [CoulombTopAppBar]'s navigation icon. */
@Composable
public fun CoulombBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoulombIconButton(
        icon = CoulombIcons.ArrowBack,
        contentDescription = stringResource(R.string.designsystem_back),
        onClick = onClick,
        modifier = modifier,
    )
}
