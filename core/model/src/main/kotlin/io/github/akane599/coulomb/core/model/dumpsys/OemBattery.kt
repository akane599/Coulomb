// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.dumpsys

import java.time.LocalDate

/**
 * One health figure an OEM prints in `dumpsys battery` (S21), named by its key and kept as
 * reported (ADR-0042 §3). Android defines no such key, so Coulomb does not say what the OEM means
 * by it beyond a percentage; the screen names the key as the source.
 */
public data class OemHealthValue(
    val key: String,
    val percent: Int,
)

/** The OEM facts Coulomb reads from S21's extras (ADR-0042 §3); empty where the OEM prints none. */
public data class OemBattery(
    val health: List<OemHealthValue>,
    val firstUseDate: LocalDate?,
) {
    public companion object {
        public val NONE: OemBattery = OemBattery(emptyList(), null)
    }
}

/**
 * [OemHealthValue]s in `health_summary.oemHealth` as `key=percent` pairs joined by `;` (ADR-0042
 * §3). Keys come from a fixed list without `=` or `;`, so the text round-trips.
 */
public object OemHealthCodec {
    public fun encode(values: List<OemHealthValue>): String? =
        values.takeIf { it.isNotEmpty() }?.joinToString(PAIRS) { "${it.key}$KEY_VALUE${it.percent}" }

    /** The stored values; a malformed pair is dropped, not guessed at. */
    public fun decode(text: String?): List<OemHealthValue> =
        text
            ?.split(PAIRS)
            .orEmpty()
            .mapNotNull { pair ->
                val key = pair.substringBefore(KEY_VALUE, "")
                val percent = pair.substringAfter(KEY_VALUE, "").toIntOrNull()
                if (key.isEmpty() || percent == null) null else OemHealthValue(key, percent)
            }

    private const val PAIRS = ";"
    private const val KEY_VALUE = "="
}
