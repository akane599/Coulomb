// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import kotlinx.coroutines.launch

private val UnderlineOffset = 3.dp
private val Dot = 1.dp
private val DotGap = 2.dp

/**
 * A glossary term (SPEC 9.3: technical terms carry a glossary tooltip; lab `.term`): [text] with a
 * dotted 1 dp underline 3 dp below the baseline. A tap shows [definition] until the next tap
 * elsewhere. TalkBack reads it as a button whose action is [actionLabel]. Compose extends the touch
 * area of a target smaller than 48 dp to 48 dp (`NodeCoordinator.hitNear`), so the label keeps its
 * line height.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun CoulombTerm(
    text: String,
    definition: String,
    actionLabel: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(containerColor = colors.textPrimary, contentColor = colors.bg) {
                Text(definition, style = CoulombTheme.typography.caption)
            }
        },
        state = tooltip,
        modifier = modifier,
    ) {
        Text(
            text = text,
            modifier =
                Modifier
                    .clickable(onClickLabel = actionLabel, role = Role.Button) { scope.launch { tooltip.show() } }
                    .drawBehind {
                        val lines = layout ?: return@drawBehind
                        val dash = PathEffect.dashPathEffect(floatArrayOf(Dot.toPx(), DotGap.toPx()))
                        for (line in 0 until lines.lineCount) {
                            val y = lines.getLineBaseline(line) + UnderlineOffset.toPx()
                            drawLine(
                                color = colors.textSecondary,
                                start = Offset(lines.getLineLeft(line), y),
                                end = Offset(lines.getLineRight(line), y),
                                strokeWidth = Dot.toPx(),
                                pathEffect = dash,
                            )
                        }
                    },
            color = color,
            style = style,
            onTextLayout = { layout = it },
        )
    }
}
