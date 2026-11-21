// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.cmd

/** An app-op mode by the name `appops` prints and accepts (AppOpsManager.MODE_NAMES). */
public enum class AppOpMode(
    public val cliName: String,
) {
    ALLOW("allow"),
    IGNORE("ignore"),
    DENY("deny"),
    DEFAULT("default"),
    FOREGROUND("foreground"),
    ;

    public companion object {
        public fun of(cliName: String): AppOpMode? = entries.firstOrNull { it.cliName == cliName }
    }
}

/**
 * What `appops get <package> <op>` says about one op. A UID-wide mode other than the op's default
 * wins over the package's own mode, and a package with no record has the op's default mode
 * (AppOpsService.checkOperationUnchecked, frameworks/base
 * services/core/java/com/android/server/appop/AppOpsService.java, android-16.0.0_r4 L3039-3090,
 * android-17.0.0_r1 L3079-3130).
 */
public data class AppOpReading(
    /** The UID's mode; printed only when it is not the op's default. */
    val uidMode: AppOpMode?,
    /** The package's record, when it has one. */
    val packageMode: AppOpMode?,
    /** The op's default, which the command prints only when nothing is recorded. */
    val defaultMode: AppOpMode?,
) {
    /** The mode Android acts on; null only for a reading with no line at all. */
    val effective: AppOpMode? get() = uidMode ?: packageMode ?: defaultMode
}

/** An app standby bucket by `UsageStatsManager.STANDBY_BUCKET_*`'s value and `am set-standby-bucket`'s word. */
public enum class StandbyBucket(
    public val value: Int,
    public val cliName: String,
) {
    /** Never set by a command: `set-standby-bucket` refuses values below [ACTIVE]. */
    EXEMPTED(5, "exempted"),
    ACTIVE(10, "active"),
    WORKING_SET(20, "working_set"),
    FREQUENT(30, "frequent"),
    RARE(40, "rare"),
    RESTRICTED(45, "restricted"),
    NEVER(50, "never"),
    ;

    public companion object {
        public fun of(value: Int): StandbyBucket? = entries.firstOrNull { it.value == value }
    }
}

/** The lists `cmd deviceidle whitelist` prints: packages Doze leaves alone. */
public data class DozeAllowlist(
    /** System apps exempt except in idle (`system-excidle`). */
    val systemExceptIdle: Set<String>,
    /** System apps exempt always (`system`). */
    val system: Set<String>,
    /** Apps the user, or a command, exempted (`user`): the only list a command changes. */
    val user: Set<String>,
)
