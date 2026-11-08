// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import androidx.datastore.core.DataStore
import io.github.akane599.coulomb.core.datastore.proto.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The settings of SPEC 14.4 that are built so far, with their defaults. */
public data class UserSettings(
    /** "Show system apps in Drain": Off. */
    val showSystemApps: Boolean = false,
    /** "Scale attribution to measured": Off. */
    val scaleToMeasured: Boolean = false,
    /** "Component breakdown" (ADR-0035 §2): Off. */
    val componentBreakdown: Boolean = false,
)

/** Reads and writes [UserSettings] in Proto DataStore (SPEC 8.1). */
@Singleton
public class SettingsStore
    @Inject
    internal constructor(
        private val store: DataStore<Settings>,
    ) {
        public val settings: Flow<UserSettings> =
            store.data
                .map { UserSettings(it.show_system_apps, it.scale_to_measured, it.component_breakdown) }
                .distinctUntilChanged()

        public suspend fun showSystemApps(show: Boolean) {
            store.updateData { it.copy(show_system_apps = show) }
        }

        public suspend fun scaleToMeasured(scale: Boolean) {
            store.updateData { it.copy(scale_to_measured = scale) }
        }

        public suspend fun componentBreakdown(on: Boolean) {
            store.updateData { it.copy(component_breakdown = on) }
        }
    }
