// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/**
 * Everything Coulomb knows about its privileged access, as one value (SPEC 4.2). The UI and the
 * collector read only this; nothing else looks at Shizuku directly.
 */
public data class PrivilegeState(
    val manager: ManagerStatus = ManagerStatus.UNKNOWN,
    /** Present once a Shizuku-compatible binder (Shizuku, a fork or Sui) has been received. */
    val server: ServerInfo? = null,
    val permission: ManagerPermission = ManagerPermission.UNKNOWN,
    val worker: WorkerStatus = WorkerStatus.IDLE,
    /** Why the worker is unavailable for this session; set only when [worker] is UNAVAILABLE. */
    val workerFailure: WorkerFailure? = null,
    val grants: GrantStatus = GrantStatus(),
    /** The single SPEC 4.5 row to show, or null when nothing needs the user's attention. */
    val issue: PrivilegeIssue? = null,
    /**
     * The manager binder this state describes: one higher for each binder received, 0 before the
     * first. Worker results carry the number they ran under, so a mode switch between two reads
     * shows (ADR-0029 §1).
     */
    val connection: Long = 0L,
) {
    /** Highest tier usable right now (SPEC 4.1). */
    val tier: AccessTier
        get() =
            when {
                workerUsable && server?.isRoot == true -> AccessTier.T3_ROOT
                workerUsable -> AccessTier.T2_PRIVILEGED_WORKER
                grants.durableCore -> AccessTier.T1_DURABLE_GRANTS
                else -> AccessTier.T0_UNPRIVILEGED
            }

    /** The worker can be bound on demand (it may be idle and unbound right now). */
    val workerUsable: Boolean
        get() =
            manager == ManagerStatus.RUNNING &&
                permission == ManagerPermission.GRANTED &&
                worker != WorkerStatus.UNAVAILABLE
}

public enum class ManagerStatus {
    /** Detection has not finished yet. */
    UNKNOWN,

    /** No package defines the Shizuku API permission and Sui is absent. */
    NOT_INSTALLED,

    /** A manager is installed but no binder arrived within 2 s (SPEC 4.5). */
    NOT_RUNNING,

    /** A binder is alive. */
    RUNNING,
}

/** Coulomb's authorization inside the Shizuku-compatible manager. */
public enum class ManagerPermission {
    UNKNOWN,
    NOT_REQUESTED,

    /** A request is open and no result has arrived yet. */
    PENDING,
    GRANTED,
    DENIED,
}

public enum class WorkerStatus {
    /** Not bound; binds on the next privileged call. */
    IDLE,
    BINDING,
    BOUND,

    /** Gave up for this session; calls use the binder or T1 fallback (SPEC 4.3). */
    UNAVAILABLE,
}

public enum class WorkerFailure {
    /** No `onServiceConnected` within 5 s (SPEC 4.3; Android 17 with official Shizuku 13.6.0). */
    BIND_TIMEOUT,

    /** The server implements Shizuku API below 13, which cannot start Coulomb's worker. */
    SERVER_TOO_OLD,

    /** The worker reported a different version twice in a row after a rebind. */
    VERSION_MISMATCH,

    /** The worker binder died repeatedly during this session. */
    WORKER_DIED,
}

/**
 * Server details stored for diagnostics only (SPEC 4.2): Coulomb never branches on a fork's name.
 */
public data class ServerInfo(
    val apiVersion: Int,
    val uid: Int,
    val seLinuxContext: String?,
    val isSui: Boolean,
    /** Package that defines the Shizuku API permission; null for Sui. */
    val managerPackage: String?,
    val managerVersionName: String?,
) {
    val isRoot: Boolean get() = uid == ROOT_UID
    val isShell: Boolean get() = uid == SHELL_UID

    public companion object {
        public const val ROOT_UID: Int = 0
        public const val SHELL_UID: Int = 2000
    }
}

/** T1 durable grants (SPEC 4.4 as amended by ADR-0010), verified with `checkSelfPermission` and `AppOpsManager`. */
public data class GrantStatus(
    val batteryStats: Boolean = false,
    val dump: Boolean = false,
    /** The GET_USAGE_STATS op allows access (MODE_ALLOWED, or MODE_DEFAULT with the permission held). */
    val usageStats: Boolean = false,
    /** The PACKAGE_USAGE_STATS permission itself, which `DumpUtils` requires on top of the op. */
    val usageStatsPermission: Boolean = false,
    /** Needed by the batterystats `--proto` and `-c` dumps when Coulomb dumps in its own process. */
    val interactAcrossUsers: Boolean = false,
    /** Only requested when the Action Center is on. */
    val writeSecureSettings: Boolean = false,
) {
    /** The grants the bootstrap always asks for. */
    val durableCore: Boolean get() = batteryStats && dump && usageStats && usageStatsPermission && interactAcrossUsers
    val anyCore: Boolean get() = batteryStats || dump || usageStats || usageStatsPermission || interactAcrossUsers

    /**
     * `DumpUtils.checkDumpAndUsageStatsPermission`: DUMP, the PACKAGE_USAGE_STATS permission and the
     * op at MODE_ALLOWED or MODE_DEFAULT (core/java/com/android/internal/util/DumpUtils.java,
     * android-17.0.0_r1 L98-108, L119-162; same checks on android-16.0.0_r4).
     */
    val canDumpBatteryStats: Boolean get() = dump && usageStatsPermission && usageStats
}

/** The user-facing rows of SPEC 4.5. Copy lives in `:core:ui`. */
public enum class PrivilegeIssue {
    /** "Basic mode" banner with what deeper access adds. */
    MANAGER_NOT_INSTALLED,

    /** "Shizuku is stopped". */
    MANAGER_STOPPED,

    /** Inline explanation, retry, ADB fallback. */
    PERMISSION_DENIED,

    /** "Your Shizuku version can't authorize apps on Android 17". */
    AUTHORIZATION_HANG,

    /** The manager is older than Shizuku API 13; grants via ADB keep Coulomb working. */
    SERVER_TOO_OLD,

    /** "Re-enable deep stats": grants were verified before and are gone now (OS update). */
    GRANTS_REVOKED,
}
