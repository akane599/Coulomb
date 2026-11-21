// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

/** The bands under the Timeline's level and current (SPEC 10.6), in their order on screen. */
public enum class TimelineTrack {
    SCREEN,
    CHARGING,
    DOZE,
    AWAKE,
    TOP_APP,
    WAKELOCKS,
    MOBILE_RADIO,
    WIFI,
    GPS,
    SIGNAL,
    TEMPERATURE,
}

/**
 * One stretch of one state on a track, as the platform's battery history recorded it (S7). Measured.
 * [value] is [ON] for a track that is only on or off; Doze's is [DOZE_LIGHT] or [DOZE_DEEP]; the
 * signal's is its strength, 0 (none) to 4. [uid] and [tag] name the top app (its package), a
 * wakelock's first holder, or why the CPU woke, where the history says.
 */
public data class TrackSpan(
    val fromWallMs: Long,
    val toWallMs: Long,
    val value: Int = ON,
    val uid: Int? = null,
    val tag: String? = null,
) {
    val durationMs: Long get() = toWallMs - fromWallMs

    public companion object {
        public const val ON: Int = 1
        public const val DOZE_LIGHT: Int = 1
        public const val DOZE_DEEP: Int = 2
        public const val SIGNAL_MAX: Int = 4
    }
}

/** The battery's temperature at a time, in tenths of a degree Celsius. Measured. */
public data class TemperaturePoint(
    val wallMs: Long,
    val deciC: Int,
)

/**
 * A span of the Timeline (SPEC 10.6). The level, the tracks and the temperature come from the
 * platform's history and stop where it has been read ([knownUntilWallMs]); the current comes from
 * Coulomb's own samples, which exist only while it sampled. A stretch in [unknown] has no history:
 * the phone was off, the history was lost, or it is not read yet. Such a stretch is not "off".
 */
public data class Timeline(
    val fromWallMs: Long,
    val toWallMs: Long,
    val level: List<LevelPoint>,
    val current: List<CurrentPoint>,
    val temperature: List<TemperaturePoint>,
    /** Every track has an entry; [TimelineTrack.TEMPERATURE]'s is empty, its data is [temperature]. */
    val tracks: Map<TimelineTrack, List<TrackSpan>>,
    val unknown: List<TrackSpan>,
    /** Where the stored history ends; null when the span holds none. */
    val knownUntilWallMs: Long?,
) {
    val empty: Boolean get() = level.isEmpty() && current.isEmpty() && tracks.values.all { it.isEmpty() }
}
