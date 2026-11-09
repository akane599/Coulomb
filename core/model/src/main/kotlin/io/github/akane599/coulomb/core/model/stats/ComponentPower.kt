// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.stats

/**
 * The fixed columns S4 is mapped to (ADR-0035 §4), in deci-coulombs (dC; 36 dC = 1 mAh).
 *
 * The first sixteen split a consumer's energy by component and add up to its total. The last four
 * split it by process state; they add up to the part of it the platform divides by state, which
 * leaves out screen, phone, ambient display and [OTHER].
 */
public enum class PowerColumn(
    public val isProcessState: Boolean = false,
) {
    SCREEN,
    CPU,
    BLUETOOTH,
    CAMERA,
    AUDIO,
    VIDEO,
    FLASHLIGHT,
    MOBILE_RADIO,
    SENSORS,
    GNSS,
    WIFI,
    WAKELOCK,
    PHONE,
    AMBIENT_DISPLAY,

    /** Custom components, ids 1000-9999, summed. */
    CUSTOM,

    /** Ids 7, 13 and 16 (not produced on Android 16 or 17) and any id Coulomb does not know. */
    OTHER,
    FOREGROUND(isProcessState = true),
    BACKGROUND(isProcessState = true),
    FOREGROUND_SERVICE(isProcessState = true),
    CACHED(isProcessState = true),
    ;

    public companion object {
        public val components: List<PowerColumn> = entries.filterNot { it.isProcessState }
        public val processStates: List<PowerColumn> = entries.filter { it.isProcessState }
    }
}

/**
 * One consumer's energy in [PowerColumn]s, in dC: cumulative since the statistics reset when it
 * comes from a dump, a change when it comes from a window. Immutable.
 */
public class ComponentPower private constructor(
    private val values: LongArray,
) {
    public operator fun get(column: PowerColumn): Long = values[column.ordinal]

    /** The component columns summed: the consumer's total, to S4's rounding. */
    public val componentSum: Long get() = PowerColumn.components.sumOf { values[it.ordinal] }

    /** The process-state columns summed. */
    public val processStateSum: Long get() = PowerColumn.processStates.sumOf { values[it.ordinal] }

    public operator fun plus(other: ComponentPower): ComponentPower = ComponentPower(LongArray(SIZE) { values[it] + other.values[it] })

    public operator fun minus(other: ComponentPower): ComponentPower = ComponentPower(LongArray(SIZE) { values[it] - other.values[it] })

    public fun toMap(): Map<PowerColumn, Long> = PowerColumn.entries.associateWith { values[it.ordinal] }

    override fun equals(other: Any?): Boolean = other is ComponentPower && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String =
        PowerColumn.entries
            .filter { values[it.ordinal] != 0L }
            .joinToString(prefix = "ComponentPower(", postfix = ")") { "$it=${values[it.ordinal]}" }

    /** Accumulates columns; [build] copies, so a builder can go on after it. */
    public class Builder {
        private val values = LongArray(SIZE)

        public fun add(
            column: PowerColumn,
            deciCoulombs: Long,
        ): Builder = apply { values[column.ordinal] += deciCoulombs }

        public fun build(): ComponentPower = ComponentPower(values.copyOf())
    }

    /** [column] in mAh. */
    public fun mah(column: PowerColumn): Double = get(column) / DC_PER_MAH

    public companion object {
        /**
         * dC = round(mAh × 36) (`frameworks/base/core/java/android/os/BatteryConsumer.java` L761-764 at
         * android-16.0.0_r4 and android-17.0.0_r1).
         */
        public const val DC_PER_MAH: Double = 36.0

        private val SIZE = PowerColumn.entries.size

        public val ZERO: ComponentPower = ComponentPower(LongArray(SIZE))

        public fun of(columns: Map<PowerColumn, Long>): ComponentPower =
            ComponentPower(LongArray(SIZE) { columns[PowerColumn.entries[it]] ?: 0L })
    }
}

/**
 * `dumpsys batterystats --usage --proto` (S4), mapped and checked (ADR-0035 §4).
 *
 * Usable only when [failures] is empty and the parse was complete; a window never uses a dump that
 * failed a check. The session's start is checked against S5 by the collector, which has both.
 */
public data class UsageStatsDump(
    /** The statistics session's start, wall clock; S5's `start_clock_time_ms` when all is well. */
    val sessionStartMillis: Long?,
    /** The wall clock at the query. */
    val sessionEndMillis: Long?,
    /** The device consumer; null when the dump has none, which [UsageCheck.NO_DEVICE] records. */
    val device: ComponentPower?,
    /** One entry per UID consumer with at least one component entry, by UID. */
    val uids: Map<Int, ComponentPower>,
    /** How many consumers failed each check. */
    val failures: Map<UsageCheck, Int>,
)

/** The checks of ADR-0035 §4 that S4 must pass before a window may use it. */
public enum class UsageCheck {
    /** A UID's component totals do not add up to its consumer total, within the rounding. */
    UID_SUM,

    /** Ids 17 (re-attributed) or 18 (base) carry energy. */
    NO_ENERGY_ID,

    /** A total, an energy or a duration is negative. */
    NEGATIVE,

    /** A component's process-state slices add up to more than its total, beyond the rounding. */
    SLICES_ABOVE_TOTAL,

    /** The device consumer is missing. */
    NO_DEVICE,

    /** Two consumers have the same UID. */
    DUPLICATE_UID,

    /** The session start differs from S5's in the same snapshot (checked by the collector). */
    SESSION_START,
}
