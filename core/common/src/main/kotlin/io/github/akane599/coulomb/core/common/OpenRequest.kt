// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.common

/**
 * What a tap outside the app asks it to open (SPEC 12): a notification, a widget or the tile puts
 * one of these under [EXTRA] in the launch intent, and the app's activity opens that screen.
 */
public object OpenRequest {
    public const val EXTRA: String = "io.github.akane599.coulomb.OPEN"
    public const val NOW: String = "now"
    public const val DRAIN: String = "drain"
    public const val NIGHT: String = "night"
    public const val HEALTH: String = "health"
    public const val PRIVILEGES: String = "privileges"
}
