// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

/** The Coulomb screen in front, which sets how often samples are taken (SPEC 7.3). */
public enum class VisibleScreen {
    NONE,

    /** Any Coulomb screen but Charging. */
    COULOMB,
    CHARGING,
}

/** SPEC 7.3 sampling rates. Pure. */
public object SamplerPolicy {
    /** SPEC 7.3: a sampling wakelock is capped at 200 ms. */
    public const val WAKE_LOCK_MS: Long = 200

    private const val CHARGING_SCREEN_MS = 1_000L
    private const val FAST_MS = 5_000L
    private const val SLOW_MS = 30_000L

    /** Milliseconds between samples, or null for none: unplugged with the screen off, never. */
    public fun intervalMs(
        plugged: Boolean,
        screenOn: Boolean,
        screen: VisibleScreen,
    ): Long? =
        when {
            plugged && screenOn && screen == VisibleScreen.CHARGING -> CHARGING_SCREEN_MS
            plugged && screenOn -> FAST_MS
            plugged -> SLOW_MS
            screenOn && screen != VisibleScreen.NONE -> FAST_MS
            screenOn -> SLOW_MS
            else -> null
        }
}
