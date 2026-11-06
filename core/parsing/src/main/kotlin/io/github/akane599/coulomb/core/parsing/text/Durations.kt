// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.text

/**
 * Durations as `TimeUtils.formatDuration` prints them without a field width (frameworks/base
 * core/java/android/util/TimeUtils.java L250-321 and L362-385, android-16.0.0_r4 and
 * android-17.0.0_r1, the same file): `0`, or a sign and the fields from the first non-zero one
 * down to milliseconds, unpadded, such as `+5s0ms`, `+1h0m0s0ms` or `-11h45m51s810ms`.
 */
internal object Durations {
    private val FORMAT = Regex("([+-])(?:([0-9]{1,10})d)?(?:([0-9]{1,10})h)?(?:([0-9]{1,10})m(?!s))?(?:([0-9]{1,10})s)?([0-9]{1,10})ms")
    private val FIELD_MS = longArrayOf(86_400_000L, 3_600_000L, 60_000L, 1_000L, 1L)

    /** Milliseconds, or null when [text] is not such a duration. */
    fun parseMs(text: String): Long? {
        if (text == "0") return 0L
        val m = FORMAT.matchEntire(text) ?: return null
        val fields = m.groupValues.drop(2)
        var total = 0L
        for ((i, field) in fields.withIndex()) {
            if (field.isNotEmpty()) total += field.toLong() * FIELD_MS[i]
        }
        return if (m.groupValues[1] == "-") -total else total
    }
}
