// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.collect.engine.VisibleScreen
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.datastore.ThemeChoice
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.navigation.Route
import io.github.akane599.coulomb.ui.CoulombRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Edge-to-edge host of [CoulombRoot]: onboarding, then the shell (SPEC 10, 14.1). Opening the app starts Full mode, the default
 * (SPEC 7.1), and asks for an app-open snapshot (SPEC 7.2). While it is in front, the collector
 * samples at the visible-screen rate (SPEC 7.3), which feeds Now's live current (SPEC 10.2).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var collector: Collector

    @Inject
    lateinit var settings: SettingsStore

    /** The screen a notification's tap asks for, until the shell has opened it. */
    private val open = MutableStateFlow<Route?>(null)

    /** SPEC 14.4 "Theme"; null until the stored choice is read. */
    private val theme = MutableStateFlow<ThemeChoice?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        collector.start(SnapshotTrigger.APP_OPENED)
        if (savedInstanceState == null) open.value = requested(intent)
        lifecycleScope.launch {
            settings.settings
                .map { it.theme }
                .distinctUntilChanged()
                .collect { theme.value = it }
        }
        holdFirstFrame()
        setContent {
            val choice by theme.collectAsStateWithLifecycle()
            val dark =
                when (choice) {
                    ThemeChoice.LIGHT -> false
                    ThemeChoice.DARK, ThemeChoice.BLACK -> true
                    ThemeChoice.SYSTEM, null -> isSystemInDarkTheme()
                }
            // The bars' icons follow the theme Coulomb draws, which may not be the system's.
            DisposableEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            CoulombTheme(darkTheme = dark, trueBlack = choice == ThemeChoice.BLACK) {
                val asked by open.collectAsStateWithLifecycle()
                CoulombRoot(asked = asked, onOpen = { open.value = null })
            }
        }
    }

    /**
     * Nothing is drawn until the stored theme is read, a few milliseconds: a first frame in the
     * system's theme followed by the chosen one would flash. Until then the system's starting
     * window stays, as the platform's guide for a splash screen that waits for data has it.
     */
    private fun holdFirstFrame() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (theme.value == null) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open.value = requested(intent)
    }

    /** The screen a notification, a widget or the tile asks for (SPEC 12); a plain launch asks for none. */
    private fun requested(intent: Intent?): Route? =
        if (intent?.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            // A long press on the tile (SPEC 12.5).
            Route.Settings
        } else {
            OPENS[intent?.getStringExtra(OpenRequest.EXTRA)]
        }

    override fun onResume() {
        super.onResume()
        collector.visibleScreen = VisibleScreen.COULOMB
    }

    override fun onPause() {
        collector.visibleScreen = VisibleScreen.NONE
        super.onPause()
    }

    private companion object {
        val OPENS =
            mapOf(
                OpenRequest.NOW to Route.Now,
                OpenRequest.DRAIN to Route.Drain,
                OpenRequest.NIGHT to Route.NightReport,
                OpenRequest.HEALTH to Route.Health,
                OpenRequest.PRIVILEGES to Route.Privileges,
            )
    }
}
