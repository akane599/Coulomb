// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.collect.android.CollectorService
import io.github.akane599.coulomb.core.collect.engine.VisibleScreen
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.ui.CoulombApp
import javax.inject.Inject

/**
 * Edge-to-edge host of [CoulombApp] (SPEC 10). Opening the app starts Full mode, the default
 * (SPEC 7.1), and asks for an app-open snapshot (SPEC 7.2). While it is in front, the collector
 * samples at the visible-screen rate (SPEC 7.3), which feeds Now's live current (SPEC 10.2).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var collector: Collector

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CollectorService.start(this, SnapshotTrigger.APP_OPENED)
        setContent {
            CoulombTheme {
                CoulombApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        collector.visibleScreen = VisibleScreen.COULOMB
    }

    override fun onPause() {
        collector.visibleScreen = VisibleScreen.NONE
        super.onPause()
    }
}
