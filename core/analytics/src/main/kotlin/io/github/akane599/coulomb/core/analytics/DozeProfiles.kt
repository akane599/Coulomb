// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

/** SPEC 13.3's two built profiles. Stock and Imported are values found on the phone, not built. */
public enum class DozeProfile { BALANCED, AGGRESSIVE }

/** One key a profile changes, in milliseconds. */
public data class DozeChange(
    val key: String,
    val fromMs: Long,
    val toMs: Long,
)

/** What applying a profile would write. With no [changes] there is nothing to apply on this phone. */
public data class DozePlan(
    val profile: DozeProfile,
    /** `device_idle_constants` as it would be written: the baseline's pairs, in their order, with [changes] applied. */
    val value: String,
    val changes: List<DozeChange>,
)

/**
 * Builds a Doze profile for one phone (SPEC 13.3; ADR-0060 §15-17). Pure.
 *
 * `device_idle_constants` overrides the device's values key by key
 * (UserSettingDeviceConfigMediator.SettingsOverridesIndividualMediator, frameworks/base
 * services/core/java/com/android/server/utils/UserSettingDeviceConfigMediator.java L158-193,
 * android-16.0.0_r4 and android-17.0.0_r1). So a profile is the string already there, with only
 * the keys it changes rewritten: dropping a pair would hand that key back to another default.
 *
 * A profile never makes the phone doze later than it does now. A key that delays idle takes the
 * smaller of the phone's value and the profile's target, an idle duration the larger. A key the
 * phone's `dumpsys deviceidle` does not list is left out. Which keys delay deep idle is
 * DeviceIdleController.becomeInactiveIfAppropriateLocked() and stepIdleStateLocked()
 * (apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java L3661-3728 and
 * L3894-4055 at 16, L3724-3791 and L3957-4118 at 17).
 */
public object DozeProfiles {
    /** Milliseconds with the screen off before the phone counts as inactive (deep). */
    public const val INACTIVE_TO: String = "inactive_to"

    /** Milliseconds inactive before deep idle is pending. */
    public const val IDLE_AFTER_INACTIVE_TO: String = "idle_after_inactive_to"
    private const val LIGHT_AFTER_INACTIVE_TO = "light_after_inactive_to"
    private const val MOTION_INACTIVE_TO = "motion_inactive_to"
    private const val IDLE_TO = "idle_to"
    private const val MAX_IDLE_TO = "max_idle_to"
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /** Waits before idle: a profile only shortens them. */
    private val delays = setOf(LIGHT_AFTER_INACTIVE_TO, INACTIVE_TO, IDLE_AFTER_INACTIVE_TO, MOTION_INACTIVE_TO)

    /** ADR-0060 §17: SPEC 13.3's intents as keys. Deep idle after about 30 and about 10 minutes. */
    private val targets: Map<DozeProfile, Map<String, Long>> =
        mapOf(
            DozeProfile.BALANCED to mapOf(INACTIVE_TO to 15 * MINUTE_MS, IDLE_AFTER_INACTIVE_TO to 15 * MINUTE_MS),
            DozeProfile.AGGRESSIVE to
                mapOf(
                    LIGHT_AFTER_INACTIVE_TO to MINUTE_MS,
                    INACTIVE_TO to 5 * MINUTE_MS,
                    IDLE_AFTER_INACTIVE_TO to 5 * MINUTE_MS,
                    MOTION_INACTIVE_TO to 5 * MINUTE_MS,
                    IDLE_TO to 2 * HOUR_MS,
                ),
        )

    private val PAIR = Regex("^([A-Za-z_][A-Za-z0-9_.]*)=([A-Za-z0-9_.+-]+)$")

    /**
     * The pairs of a `device_idle_constants` value in their order; empty for unset or empty. Null
     * when the value is not a list of `key=value` pairs: Coulomb then cannot rewrite it without
     * risking what it holds, and offers no profile.
     */
    public fun pairs(value: String?): Map<String, String>? {
        if (value.isNullOrEmpty()) return emptyMap()
        val pairs = LinkedHashMap<String, String>()
        for (part in value.split(',')) {
            val match = PAIR.matchEntire(part) ?: return null
            // A key named twice: the system keeps the last, and so does the map.
            pairs[match.groupValues[1]] = match.groupValues[2]
        }
        return pairs
    }

    /**
     * [profile] on a phone whose setting is [baseline] and whose effective values, with that
     * baseline in force, are [effectiveMs] (the duration keys of `dumpsys deviceidle`). Null when
     * [baseline] cannot be read as pairs.
     */
    public fun plan(
        profile: DozeProfile,
        baseline: String?,
        effectiveMs: Map<String, Long>,
    ): DozePlan? {
        val pairs = LinkedHashMap(pairs(baseline) ?: return null)
        val changes =
            targets.getValue(profile).mapNotNull { (key, target) ->
                val now = effectiveMs[key] ?: return@mapNotNull null
                val to = if (key in delays) minOf(now, target) else longer(now, target, effectiveMs[MAX_IDLE_TO])
                if (to == now) null else DozeChange(key, now, to)
            }
        changes.forEach { pairs[it.key] = it.toMs.toString() }
        return DozePlan(profile, pairs.entries.joinToString(",") { "${it.key}=${it.value}" }, changes)
    }

    /** An idle period is never shortened, and never set past the phone's own longest. */
    private fun longer(
        now: Long,
        target: Long,
        max: Long?,
    ): Long = maxOf(now, if (max == null) target else minOf(target, max))

    /**
     * How long after the screen goes off the phone can first be in deep idle, lying still and
     * unplugged: the two waits every path to it sums. Sensing and locating can add to it. Null
     * when the phone lists neither key.
     */
    public fun idleAfterMs(effectiveMs: Map<String, Long>): Long? {
        val inactive = effectiveMs[INACTIVE_TO] ?: return null
        return inactive + (effectiveMs[IDLE_AFTER_INACTIVE_TO] ?: return null)
    }

    /** Whether every change of [plan] is in force in [effectiveMs], read after the write. */
    public fun inForce(
        plan: DozePlan,
        effectiveMs: Map<String, Long>,
    ): Boolean = plan.changes.all { effectiveMs[it.key] == it.toMs }
}
