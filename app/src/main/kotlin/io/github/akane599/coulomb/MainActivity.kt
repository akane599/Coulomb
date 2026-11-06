// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.AndroidEntryPoint
import io.github.akane599.coulomb.core.collect.android.CollectorService
import io.github.akane599.coulomb.core.model.SnapshotTrigger

/**
 * Edge-to-edge host. Navigation and screens arrive in P4 (SPEC 10, 17). Opening the app starts
 * Full mode, the default (SPEC 7.1), and asks for an app-open snapshot (SPEC 7.2).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CollectorService.start(this, SnapshotTrigger.APP_OPENED)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShellPlaceholder()
                }
            }
        }
    }
}

@Composable
private fun ShellPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    }
}
