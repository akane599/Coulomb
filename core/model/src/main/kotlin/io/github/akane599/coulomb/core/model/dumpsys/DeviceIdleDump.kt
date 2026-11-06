// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.dumpsys

/**
 * `dumpsys deviceidle` (S11), as `DeviceIdleController.dump` prints it (frameworks/base
 * apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java, android-16.0.0_r4
 * L5228-5495, android-17.0.0_r1 L5314-5579). Booleans are null when their line was missing; the
 * motion lines are printed only while the motion sensor is used.
 */
public data class DeviceIdleDump(
    /**
     * The active Doze constants (`Constants.dump`, android-16.0.0_r4 L1767-1912, android-17.0.0_r1
     * L1788-1933), keys in dump order, values as printed: durations like `+1h0m0s0ms`, factors like
     * `2.0`. These keys are the ones `device_idle_constants` accepts on this build (SPEC 13.3).
     */
    val settings: Map<String, String>,
    val deepState: DeepDozeState?,
    val lightState: LightDozeState?,
    val lightEnabled: Boolean?,
    val deepEnabled: Boolean?,
    /** `cmd deviceidle force-idle` is in force. */
    val forceIdle: Boolean?,
    val screenOn: Boolean?,
    val screenLocked: Boolean?,
    val charging: Boolean?,
    val networkConnected: Boolean?,
    val useMotionSensor: Boolean?,
    val motionActive: Boolean?,
    val notMoving: Boolean?,
    /** Recent state changes, oldest first; the platform keeps the last 100. */
    val idlingHistory: List<DozeEvent>,
    val allowlist: DozeAllowlist,
)

/** `DeviceIdleController.stateToString` (android-16.0.0_r4 L453-465, android-17.0.0_r1 L462-474). */
public enum class DeepDozeState { ACTIVE, INACTIVE, IDLE_PENDING, SENSING, LOCATING, IDLE, IDLE_MAINTENANCE, QUICK_DOZE_DELAY }

/** `DeviceIdleController.lightStateToString` (android-16.0.0_r4 L488-498, android-17.0.0_r1 L497-507). */
public enum class LightDozeState { ACTIVE, INACTIVE, IDLE, WAITING_FOR_NETWORK, IDLE_MAINTENANCE, OVERRIDE }

public enum class DozeEventKind { NORMAL, LIGHT_IDLE, LIGHT_MAINTENANCE, DEEP_IDLE, DEEP_MAINTENANCE }

/** One `Idling history` entry. */
public data class DozeEvent(
    val kind: DozeEventKind,
    /** Elapsed-realtime milliseconds before the dump; null when the platform printed no time. */
    val msBeforeDump: Long?,
    /** Why the device left idle, such as `unlocked` or `charging`. */
    val reason: String?,
)

/**
 * Who Doze lets run (the platform calls it the power-save whitelist). The "except idle" entries
 * are exempt from app standby and light Doze restrictions but not from deep idle.
 */
public data class DozeAllowlist(
    val systemAppsExceptIdle: List<String>,
    val systemApps: List<String>,
    /** System apps the user took off the list. */
    val removedSystemApps: List<String>,
    val userApps: List<String>,
    val appIdsExceptIdle: List<Int>,
    val userAppIds: List<Int>,
    val allAppIds: List<Int>,
    /** App ids allowed for a short time, for example after a high-priority message. */
    val tempAppIds: List<Int>,
)
