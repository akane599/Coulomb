// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import io.github.akane599.coulomb.R
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import kotlinx.serialization.Serializable

/**
 * Every screen the shell can show (SPEC 10). Keys are serializable so each back stack survives
 * process death. The app owns them; feature screens take callbacks instead of routes (SPEC 15.2).
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Now : Route

    @Serializable
    data object Drain : Route

    /** Compare two periods, below Drain (ADR-0038 §7). */
    @Serializable
    data object DrainCompare : Route

    /** One app over Drain's window (SPEC 10.4), below Drain or Compare. */
    @Serializable
    data class AppDetail(
        val uid: Int,
    ) : Route

    @Serializable
    data object Timeline : Route

    @Serializable
    data object Health : Route

    @Serializable
    data object ActionCenter : Route

    @Serializable
    data object Privileges : Route

    @Serializable
    data object Settings : Route
}

/** The four destinations of SPEC 10 in bar order, each the root of its own back stack. */
enum class Destination(
    val root: Route,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    Now(Route.Now, R.string.destination_now, CoulombIcons.Battery, CoulombIcons.BatteryFilled),
    Drain(Route.Drain, R.string.destination_drain, CoulombIcons.DataUsage, CoulombIcons.DataUsageFilled),
    Timeline(Route.Timeline, R.string.destination_timeline, CoulombIcons.Timeline, CoulombIcons.TimelineFilled),
    Health(Route.Health, R.string.destination_health, CoulombIcons.EcgHeart, CoulombIcons.EcgHeartFilled),
}
