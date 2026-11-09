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
    /** The charge target (SPEC 14.4, ADR-0046 §4): 80% suggested, 50–100%. */
    val chargeTargetPct: Int = DEFAULT_CHARGE_TARGET,
    /** Onboarding finished (ADR-0047 §1). */
    val onboarded: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_CHARGE_TARGET: Int = 80

        /** SPEC 14.4's range for the charge target. */
        public val CHARGE_TARGETS: IntRange = 50..100
    }
}

/** Reads and writes [UserSettings] in Proto DataStore (SPEC 8.1). */
@Singleton
public class SettingsStore
    @Inject
    internal constructor(
        private val store: DataStore<Settings>,
    ) {
        public val settings: Flow<UserSettings> =
            store.data
                .map {
                    UserSettings(
                        showSystemApps = it.show_system_apps,
                        scaleToMeasured = it.scale_to_measured,
                        componentBreakdown = it.component_breakdown,
                        // 0 is unset; a value outside the range (an older or edited file) falls back too.
                        chargeTargetPct =
                            it.charge_target_pct.takeIf { pct -> pct in UserSettings.CHARGE_TARGETS }
                                ?: UserSettings.DEFAULT_CHARGE_TARGET,
                        onboarded = it.onboarded,
                    )
                }.distinctUntilChanged()

        public suspend fun showSystemApps(show: Boolean) {
            store.updateData { it.copy(show_system_apps = show) }
        }

        public suspend fun scaleToMeasured(scale: Boolean) {
            store.updateData { it.copy(scale_to_measured = scale) }
        }

        public suspend fun componentBreakdown(on: Boolean) {
            store.updateData { it.copy(component_breakdown = on) }
        }

        /** Onboarding is done: the app opens on Now from here on (ADR-0047 §1). */
        public suspend fun onboarded() {
            store.updateData { it.copy(onboarded = true) }
        }

        /** Stores [pct], held to [UserSettings.CHARGE_TARGETS]. */
        public suspend fun chargeTarget(pct: Int) {
            val held = pct.coerceIn(UserSettings.CHARGE_TARGETS)
            store.updateData { it.copy(charge_target_pct = held) }
        }
    }
