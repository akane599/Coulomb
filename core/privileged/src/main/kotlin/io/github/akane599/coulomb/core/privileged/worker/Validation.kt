// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.worker

/**
 * Argument validation for the few strings that cross into the worker (SPEC 4.3: command builders
 * never interpolate untrusted strings). Commands run as argv arrays without a shell, so these
 * checks stop option injection (a leading `-`), path traversal and oversized input.
 */
internal object Validation {
    // Java package name rules (JLS 6.1 identifiers, dot separated, at least two segments), which
    // PackageManager also enforces for application ids. No leading '-' is possible.
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private const val MAX_PACKAGE_NAME = 255

    // Settings key names: SettingsProvider accepts any string, but every AOSP and OEM battery key
    // seen on the P0 devices fits this shape.
    private val SETTINGS_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")

    /**
     * Battery-related settings keys (ADR-0009). Same pattern as the P0 capture filter in
     * `tools/fixtures/capture.sh`, so the worker never reads or lists anything else.
     */
    val BATTERY_SETTINGS_KEY: Regex =
        Regex("(batt|protect|charg|adaptive_?power|power_?sav|low_power|doze|device_idle|psm|thermal)", RegexOption.IGNORE_CASE)

    // One sysfs path segment: attribute and device names (e.g. "battery", "charge_full",
    // "wakeup12", "1-0036"). "." and ".." are rejected separately.
    private val SYSFS_SEGMENT = Regex("^[A-Za-z0-9_@:+-][A-Za-z0-9_.@:+-]{0,63}$")
    private const val MAX_SYSFS_DEPTH = 3

    fun isPackageName(value: String?): Boolean = value != null && value.length <= MAX_PACKAGE_NAME && PACKAGE_NAME.matches(value)

    fun isBatterySettingsKey(value: String?): Boolean =
        value != null && SETTINGS_KEY.matches(value) && BATTERY_SETTINGS_KEY.containsMatchIn(value)

    /** True when [canonicalPath] is [canonicalEntry] itself or lies inside it. */
    fun isContained(
        canonicalEntry: String,
        canonicalPath: String,
    ): Boolean = canonicalPath == canonicalEntry || canonicalPath.startsWith("$canonicalEntry/")

    /**
     * Splits a relative sysfs path into validated segments, or returns null. An empty string means
     * the root itself (allowed for listing only).
     */
    fun sysfsSegments(
        relative: String?,
        allowEmpty: Boolean,
    ): List<String>? {
        if (relative == null) return null
        if (relative.isEmpty()) return if (allowEmpty) emptyList() else null
        val segments = relative.split('/')
        val valid =
            segments.size <= MAX_SYSFS_DEPTH &&
                segments.all { it != "." && it != ".." && SYSFS_SEGMENT.matches(it) }
        return if (valid) segments else null
    }
}
