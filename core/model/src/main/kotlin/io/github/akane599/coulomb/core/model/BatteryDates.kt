// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

import java.time.LocalDate

/** Who reported a battery date (ADR-0044 §4). */
public enum class BatteryDateSource {
    /** S3: `BatteryManager`'s manufacturing or first-usage date. */
    SYSTEM,

    /** S21: the OEM's own field in `dumpsys battery` (ADR-0042 §3). */
    OEM,
}

/** A calendar date the battery reported, with its source. */
public data class BatteryDate(
    val date: LocalDate,
    val source: BatteryDateSource,
)

/** The battery's manufacture and first-use dates where it reports them (SPEC 10.8 item 3). */
public data class BatteryDates(
    val manufactured: BatteryDate?,
    val firstUsed: BatteryDate?,
) {
    public companion object {
        public val NONE: BatteryDates = BatteryDates(null, null)

        /** No phone battery in use was made before this; earlier values are placeholders. */
        public val EARLIEST: LocalDate = LocalDate.of(2010, 1, 1)
    }
}
