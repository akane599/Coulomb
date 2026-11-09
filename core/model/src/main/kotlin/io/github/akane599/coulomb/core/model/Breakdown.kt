// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/**
 * Whether a consecutive window is broken down by S4, or the first rule of ADR-0035 §5 it failed, in
 * the order the rules are checked. [code] is what the database stores: frozen, append only.
 */
public enum class Breakdown(
    public val code: Int,
) {
    BROKEN_DOWN(0),

    /** The Component breakdown setting was off at either end. */
    S4_OFF(1),

    /** An end's S4 read was skipped, failed, partial or slept. */
    END_NOT_OK(2),

    /** The window has no modeled total from S5. */
    NO_MODELED_TOTAL(3),

    /** The window was not wholly on battery, or the statistics were reset inside it. */
    NOT_ON_BATTERY(4),

    /** A UID or device column went down, or a UID went missing. */
    WENT_DOWN(5),

    /** S4's sums disagree with S5's totals beyond the margin. */
    DISAGREES(6),
    ;

    public companion object {
        public fun ofCode(code: Int): Breakdown = entries.firstOrNull { it.code == code } ?: error("Unknown breakdown code $code")
    }
}
