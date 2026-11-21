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

    /** SPEC 14.4 "Screen-on sampling": the default while a Coulomb screen shows, on battery. */
    public const val VISIBLE_MS: Long = 5_000

    private const val CHARGING_SCREEN_MS = 1_000L
    private const val FAST_MS = 5_000L
    private const val SLOW_MS = 30_000L

    /**
     * Milliseconds between samples, or null for none: unplugged with the screen off, never.
     * [visibleMs] is the user's "Screen-on sampling" (SPEC 14.4, 2–60 s): the rate on battery while
     * a Coulomb screen shows. The rate behind another app is never faster than 30 s nor than the
     * user's own; the charging rates are fixed (ADR-0064 §4).
     */
    public fun intervalMs(
        plugged: Boolean,
        screenOn: Boolean,
        screen: VisibleScreen,
        visibleMs: Long = VISIBLE_MS,
    ): Long? =
        when {
            plugged && screenOn && screen == VisibleScreen.CHARGING -> CHARGING_SCREEN_MS
            plugged && screenOn -> FAST_MS
            plugged -> SLOW_MS
            screenOn && screen != VisibleScreen.NONE -> visibleMs
            screenOn -> maxOf(SLOW_MS, visibleMs)
            else -> null
        }
}
