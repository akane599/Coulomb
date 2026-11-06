// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.model.SnapshotTrigger

/** What the collector does for one trigger. */
internal enum class Action {
    /** Nothing: debounced. */
    NONE,

    /** A battery sample only (ADR-0015: a level change is not a snapshot). */
    SAMPLE,

    /** A light snapshot row: S1, S2 and device state. */
    LIGHT,

    /** A heavy snapshot: light data, S5 (or S6) and the history read (SPEC 7.2, ADR-0018). */
    HEAVY,
}

/** What the policy knows when a trigger arrives. */
internal data class PolicyInput(
    val trigger: SnapshotTrigger,
    val nowElapsedNs: Long,
    /** The last heavy snapshot of this process, in the current boot; null when none was taken. */
    val lastHeavyElapsedNs: Long?,
    /** A dump can run now. */
    val heavyAvailable: Boolean,
    /** Nothing was collected yet in this process, so changes since may have been missed. */
    val firstOfProcess: Boolean,
    /** A heavy snapshot is owed: one was wanted while dumps were unavailable. */
    val heavyOwed: Boolean,
)

internal data class Decision(
    val action: Action,
    /** Whether a heavy snapshot is still owed after this one. */
    val heavyOwed: Boolean,
)

/**
 * SPEC 7.2 triggers. Pure. A heavy snapshot that cannot run now becomes a light one and is owed:
 * the next trigger once dumps work takes it. A process start's first trigger is heavy, so the
 * history read shows what changed while Coulomb was not running.
 */
internal object TriggerPolicy {
    /** App open, refresh and unlock are debounced to one heavy snapshot per minute (SPEC 7.2). */
    const val DEBOUNCE_NS: Long = 60_000_000_000L

    fun decide(input: PolicyInput): Decision {
        val wanted =
            when (input.trigger) {
                SnapshotTrigger.LEVEL_CHANGED -> if (input.firstOfProcess || input.heavyOwed) Action.HEAVY else Action.SAMPLE

                SnapshotTrigger.PACKAGE_CHANGED -> if (input.firstOfProcess || input.heavyOwed) Action.HEAVY else Action.LIGHT

                SnapshotTrigger.APP_OPENED,
                SnapshotTrigger.PULL_TO_REFRESH,
                SnapshotTrigger.USER_PRESENT,
                -> if (recent(input) && !input.heavyOwed) Action.NONE else Action.HEAVY

                SnapshotTrigger.SCREEN_OFF,
                SnapshotTrigger.SCREEN_ON,
                SnapshotTrigger.POWER_CONNECTED,
                SnapshotTrigger.POWER_DISCONNECTED,
                SnapshotTrigger.SCREEN_ON_PERIODIC,
                SnapshotTrigger.BOOT_COMPLETED,
                SnapshotTrigger.PACKAGE_REPLACED,
                SnapshotTrigger.LITE_PERIODIC,
                SnapshotTrigger.SERVICE_STARTED,
                -> Action.HEAVY
            }
        return when {
            wanted != Action.HEAVY -> Decision(wanted, input.heavyOwed)
            input.heavyAvailable -> Decision(Action.HEAVY, heavyOwed = false)
            else -> Decision(Action.LIGHT, heavyOwed = true)
        }
    }

    private fun recent(input: PolicyInput): Boolean {
        val last = input.lastHeavyElapsedNs ?: return false
        return input.nowElapsedNs - last in 0 until DEBOUNCE_NS
    }
}
