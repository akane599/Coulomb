// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.dumpsys

/**
 * The parts of `dumpsys power` (S12) Coulomb reads: the power manager's state, the wakelocks held
 * now and battery saver (frameworks/base services/core/java/com/android/server/power/
 * PowerManagerService.java `dumpInternal`, android-16.0.0_r4 L4948-5196, android-17.0.0_r1
 * L5168-5419). A value is null when its line was missing or unreadable.
 */
public data class PowerDump(
    /** `Awake`, `Asleep`, `Dreaming` or `Dozing` (`PowerManagerInternal.wakefulnessToString`). */
    val wakefulness: String?,
    val powered: Boolean?,
    /** `BatteryManager.BATTERY_PLUGGED_*`, 0 when unplugged. */
    val plugType: Int?,
    val batteryLevel: Int?,
    val deviceIdle: Boolean?,
    val lightDeviceIdle: Boolean?,
    /**
     * Held now, in the platform's order. Null when the list could not be trusted: tags are chosen
     * by apps and may hold line breaks, so the list is kept only when its lines match the count the
     * platform prints.
     */
    val wakeLocks: List<HeldWakeLock>?,
    /** False when the build has no battery saver; null when the dump did not say. */
    val batterySaverSupported: Boolean?,
    val batterySaver: BatterySaverState?,
)

/**
 * One `PowerManagerService.WakeLock.toString` line (android-16.0.0_r4 L5842-5876, android-17.0.0_r1
 * L6085-6127).
 */
public data class HeldWakeLock(
    /** Null for a level the platform prints as `???` or one Coulomb does not know. */
    val level: WakeLockLevel?,
    /** As the app set it, so it may hold personal text. */
    val tag: String,
    val uid: Int,
    val pid: Int?,
    /**
     * Uptime milliseconds since the platform noted the acquisition, so time in deep sleep is not
     * counted; null when it has not noted it yet.
     */
    val heldUptimeMs: Long?,
    /** Held longer than the platform's long-wakelock threshold. */
    val long: Boolean,
    /** Ignored by the platform, for example while the app is frozen or restricted. */
    val disabled: Boolean,
    val acquireCausesWakeup: Boolean,
    val onAfterRelease: Boolean,
    val systemWakelock: Boolean,
    /** The first UID of the wakelock's work source, the one the work is done for; null without one. */
    val workSourceUid: Int?,
)

/** `PowerManagerInternal.getLockLevelString` (android-16.0.0_r4 L98-112, android-17.0.0_r1 L105-119). */
public enum class WakeLockLevel(
    public val platformName: String,
) {
    FULL("FULL_WAKE_LOCK"),
    SCREEN_BRIGHT("SCREEN_BRIGHT_WAKE_LOCK"),
    SCREEN_DIM("SCREEN_DIM_WAKE_LOCK"),
    PARTIAL("PARTIAL_WAKE_LOCK"),
    PROXIMITY_SCREEN_OFF("PROXIMITY_SCREEN_OFF_WAKE_LOCK"),
    DOZE("DOZE_WAKE_LOCK"),
    DRAW("DRAW_WAKE_LOCK"),
    SCREEN_TIMEOUT_OVERRIDE("SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK"),
    PARTIAL_SLEEP("PARTIAL_SLEEP_WAKE_LOCK"),
}

/**
 * `BatterySaverStateMachine.dump` (services/core/java/com/android/server/power/batterysaver/
 * BatterySaverStateMachine.java L1014-1085, both tags).
 */
public data class BatterySaverState(
    val enabled: Boolean?,
    val fullEnabled: Boolean?,
    val adaptiveEnabled: Boolean?,
    /** The user's switch, `Settings.Global.LOW_POWER_MODE`. */
    val settingEnabled: Boolean?,
    /** `PowerManager.POWER_SAVE_MODE_TRIGGER_*`: 0 at a battery level, 1 dynamic. */
    val automaticTrigger: Int?,
    /** The level that turns it on with the percentage trigger; 0 when that is off. */
    val triggerLevel: Int?,
)
