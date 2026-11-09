// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.privilege

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.R
import kotlinx.coroutines.launch

/**
 * The SPEC 4.4 grants as copy-ready `adb shell` lines (SPEC 14.1): one sentence on when to run
 * them, the commands in Mono on `surface`, one paragraph each so a wrapped command still reads as
 * one, selectable, and "Copy commands". The commands stay left to right in a right-to-left layout.
 * Android 13 and later confirm the copy themselves, so Coulomb adds no message.
 */
@Composable
public fun AdbCommands(
    commands: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.adb_clip_label)
    val text = commands.joinToString("\n")
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.adb_body), color = colors.textSecondary, style = type.body)
        SelectionContainer(
            Modifier
                .padding(top = CoulombSpacing.Space12)
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(CoulombRadius.Row)),
        ) {
            Column(Modifier.padding(CoulombSpacing.Space16), verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8)) {
                commands.forEach { command ->
                    Text(command, color = colors.textPrimary, style = type.mono.copy(textDirection = TextDirection.Ltr))
                }
            }
        }
        CoulombTextButton(
            text = stringResource(R.string.adb_copy),
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) } },
            modifier = Modifier.padding(top = CoulombSpacing.Space4),
            trailingIcon = CoulombIcons.ContentCopy,
            edge = ButtonEdge.Start,
        )
    }
}

/** "Show ADB commands", which opens the copy-ready block in place. */
@Composable
public fun AdbCommandsToggle(
    commands: List<String>,
    modifier: Modifier = Modifier,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        CoulombTextButton(
            stringResource(if (shown) R.string.privilege_hide_adb else R.string.privilege_show_adb),
            { shown = !shown },
            edge = ButtonEdge.Start,
        )
        if (shown) AdbCommands(commands, Modifier.padding(top = CoulombSpacing.Space8))
    }
}
