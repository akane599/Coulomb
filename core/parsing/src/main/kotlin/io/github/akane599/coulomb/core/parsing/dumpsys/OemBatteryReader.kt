// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import io.github.akane599.coulomb.core.model.dumpsys.OemBattery
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthValue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The OEM's battery health and first-use date from [BatteryServiceParser]'s extras (S21, ADR-0042
 * §3). AOSP prints neither, so only keys seen on a device are read, each verified in a redacted
 * fixture; any other OEM gets [OemBattery.NONE] and the screen shows the system's values alone.
 * A value outside the plausible range is dropped, never clamped.
 */
public object OemBatteryReader {
    public fun read(extras: Map<String, String>): OemBattery {
        val health =
            HEALTH_KEYS.mapNotNull { key ->
                extras[key]
                    ?.let(::unbracketed)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..MAX_PERCENT }
                    ?.let { OemHealthValue(key, it) }
            }
        val firstUse = FIRST_USE_KEYS.firstNotNullOfOrNull { key -> extras[key]?.let(::unbracketed)?.let(::date) }
        return OemBattery(health, firstUse)
    }

    /** Samsung prints some values in one pair of brackets: `[100]`. */
    private fun unbracketed(value: String): String = value.trim().removeSurrounding("[", "]").trim()

    private fun date(text: String): LocalDate? {
        if (!DATE.matches(text)) return null
        val date =
            try {
                LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE)
            } catch (_: DateTimeParseException) {
                return null
            }
        return date.takeIf { it.year in MIN_YEAR..MAX_YEAR }
    }

    /**
     * Samsung (One UI 9, `fixtures/sm-s938b/S938BXXSDDZJ1/battery.txt`): `mSavedBatteryAsoc` and
     * `mSavedBatteryBsoh`, both percentages there (100 and 96 on a weeks-old battery). Which one
     * Samsung's own Settings shows is not known yet (`docs/questions.md`), so both are kept.
     */
    private val HEALTH_KEYS = listOf("mSavedBatteryAsoc", "mSavedBatteryBsoh")

    /** Samsung, the same fixture: `battery FirstUseDate: [20251030]`, as yyyyMMdd. */
    private val FIRST_USE_KEYS = listOf("battery FirstUseDate")

    private val DATE = Regex("[0-9]{8}")
    private const val MAX_PERCENT = 150
    private const val MIN_YEAR = 2010
    private const val MAX_YEAR = 2100
}
