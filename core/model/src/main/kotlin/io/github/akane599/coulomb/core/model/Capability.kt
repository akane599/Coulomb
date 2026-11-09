// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/**
 * One row of the Capability Matrix (SPEC 4.6, entity `Capability` in 8). Probes produce these;
 * features read them to show, degrade or hide themselves.
 */
public data class Capability(
    val id: CapabilityId,
    val state: CapabilityState,
    /** Lowest tier at which this capability was observed to work, or would work. */
    val tier: AccessTier,
    /** Short machine-readable reason, also shown in the Evidence layer (e.g. "service not registered"). */
    val reason: String?,
    val fixAction: FixAction?,
    val probedAtWallMs: Long,
    val fingerprint: String,
    /** Probe details for the exported report (flag lists, key names, file names). Never personal data. */
    val detail: Map<String, String> = emptyMap(),
)

/** Stable ids; the string form is the primary key and the report label. */
public enum class CapabilityId(
    public val key: String,
) {
    BATTERY_CHANGED("s1.battery_changed"),
    CURRENT_NOW("s2.current_now"),
    CHARGE_COUNTER("s2.charge_counter"),
    HEALTH_PROPERTIES("s3.health_properties"),
    BATTERYSTATS_USAGE("s4.batterystats_usage"),
    BATTERYSTATS_FLAGS("s5.batterystats_flags"),
    BATTERYSTATS_PROTO("s5.batterystats_proto"),
    BATTERYSTATS_CHECKIN("s6.batterystats_checkin"),
    BATTERYSTATS_HISTORY("s7.batterystats_history"),
    KERNEL_WAKELOCKS("s8.kernel_wakelocks"),
    WAKEUP_SOURCES("s9.wakeup_sources"),
    ALARM_DUMP("s10.alarm"),
    DEVICEIDLE_DUMP("s11.deviceidle"),
    POWER_DUMP("s12.power"),
    JOBSCHEDULER_DUMP("s13.jobscheduler"),
    USAGE_STATS("s14.usage_stats"),
    STANDBY_BUCKETS("s14.standby_buckets"),
    POWER_MONITORS("s16.power_monitors"),
    UID_HEALTH_STATS("s17.uid_health_stats"),
    THERMAL_DUMP("s19.thermalservice"),
    BATTERY_DUMP("s21.battery"),
    PROTECTION_SETTINGS("s22.protection_settings"),
    POWER_SUPPLY_SYSFS("s23.power_supply"),
    SUSPEND_STATS("s25.suspend_stats"),
    WAKEUP_REASONS("s26.wakeup_reasons"),
    KERNEL_LOG("s27.kernel_log"),
    PROMOTED_NOTIFICATIONS("live_update_promotion"),
    WORKER_BIND("worker_bind"),
}

/** What the user can do to make an unavailable capability available. Stored by name: add, never rename. */
public enum class FixAction {
    CONNECT_SHIZUKU,
    START_SHIZUKU,
    GRANT_SHIZUKU_PERMISSION,
    RUN_BOOTSTRAP,
    GRANT_USAGE_ACCESS,
    GRANT_NOTIFICATIONS,
    NEEDS_ROOT,

    /** Hardware or OS does not expose it; nothing to do. */
    NONE,
}
