// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.story

import io.github.akane599.coulomb.core.model.insight.Insight

/** What a Drain Story is about (SPEC 9.3). */
public sealed interface StorySubject {
    /** The open discharge: since the charger was unplugged. */
    public data object SinceUnplugged : StorySubject

    /** A stretch with the screen off, such as a night. */
    public data object ScreenOff : StorySubject

    /** One app in a window. A null [label] has no name yet. */
    public data class App(
        val uid: Int,
        val label: String?,
    ) : StorySubject
}

/** The stretch a story covers and what the battery did in it. */
public data class StorySpan(
    val fromWallMs: Long,
    val toWallMs: Long,
    val durationMs: Long,
    /** Level points lost; null when the level is not known at both ends. */
    val levelDropPct: Int?,
    /** Null when the stretch is too short or too flat for a rate (SPEC 6.7). */
    val ratePctPerHour: Double?,
)

/** One cause sentence: a contributor and its share of the stretch's drain, 0 to 1. */
public sealed interface StoryCause {
    public val share: Double

    /**
     * An app, with what it did when that is worth saying: how often it woke the phone, or how long
     * it held the phone awake. An app and its wakelocks are one cause (SPEC 9.3).
     */
    public data class App(
        val uid: Int,
        val label: String?,
        override val share: Double,
        val wakeups: Long?,
        val heldAwakeMs: Long?,
    ) : StoryCause

    /** What the power model charged to no app: the phone idling and its radios on standby. */
    public data class Device(
        override val share: Double,
    ) : StoryCause

    /** Measured drain the power model could not place (SPEC 6.5). */
    public data class Unattributed(
        override val share: Double,
    ) : StoryCause
}

/**
 * A Drain Story before it has words (SPEC 9.3): one verdict over [span], up to three [causes],
 * largest first, how well the phone slept where that is known, and at most one [anomaly].
 */
public data class Story(
    val subject: StorySubject,
    val span: StorySpan,
    /** For an app's story: its drain in mAh and its share of the stretch's total. */
    val appMah: Double?,
    val appShare: Double?,
    val causes: List<StoryCause>,
    /** The share of screen-off time spent in deep sleep, 0 to 1; null when unknown. */
    val deepSleepShare: Double?,
    val anomaly: Insight?,
)
