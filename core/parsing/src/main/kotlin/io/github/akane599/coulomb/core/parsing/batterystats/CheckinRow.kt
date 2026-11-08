// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.parsing.ParseContext

/**
 * The data columns of one checkin line, after `9,<uid>,<category>,<type>`.
 *
 * A missing column reads as null. A column that should be a number but is not reads as null and
 * becomes a [ParseContext.issue] at the line's offset; values never go into the message.
 */
internal class CheckinRow(
    val offset: Long,
    val uid: Int,
    /** `l` (since charged) or `i` (information). */
    val category: String,
    val type: String,
    private val fields: List<String>,
    private val ctx: ParseContext,
) {
    val size: Int get() = fields.size - HEADER

    fun text(col: Int): String? = fields.getOrNull(HEADER + col)

    fun long(col: Int): Long? {
        val s = text(col) ?: return null
        return s.toLongOrNull() ?: null.also { bad(col) }
    }

    fun double(col: Int): Double? {
        val s = text(col) ?: return null
        return s.toDoubleOrNull()?.takeIf { it.isFinite() } ?: null.also { bad(col) }
    }

    fun text(col: Enum<*>): String? = text(col.ordinal)

    fun long(col: Enum<*>): Long? = long(col.ordinal)

    fun double(col: Enum<*>): Double? = double(col.ordinal)

    /**
     * Checks the column count against the writer's [expected]. A short line is an issue; each extra
     * column is counted as unknown under `<type>.<1-based column>`.
     */
    fun expect(expected: Int) {
        if (size < expected) {
            ctx.issue(offset, type, "$size of $expected columns")
        } else {
            for (col in expected until size) ctx.unknown("$type.${col + 1}")
        }
    }

    /** Records an issue at this line; [message] never holds a value. */
    fun issue(message: String) {
        ctx.issue(offset, type, message)
    }

    private fun bad(col: Int) {
        ctx.issue(offset, type, "column ${col + 1} is not a number")
    }

    companion object {
        /** `9`, uid, category, type. */
        const val HEADER = 4
    }
}

/** Rounds microseconds to milliseconds like `BatteryStats.roundUsToMs` (android-17.0.0_r1 L3609-3611). */
internal fun Long.usToMs(): Long = (this + US_HALF_MS) / US_PER_MS

private const val US_PER_MS = 1000L
private const val US_HALF_MS = 500L
