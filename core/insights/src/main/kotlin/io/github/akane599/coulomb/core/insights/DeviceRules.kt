// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.RuleId

/** The rules about the device as a whole (SPEC 9.2). */
internal object DeviceRules {
    private const val LOW_BINS = 2

    /**
     * R07: more than 30% of the time in the two lowest signal bins, with the modem taking more than
     * 15% of the drain. Both must hold: the lowest bin also counts time without service, which costs
     * little.
     */
    val weakSignalTax =
        rule(RuleId.WEAK_SIGNAL_TAX, setOf(RuleInput.DAY_USE)) {
            val day = ctx.day ?: return@rule emptyList()
            val signal = day.signal ?: return@rule emptyList()
            val modem = signal.modemShare ?: return@rule emptyList()
            val total = signal.binMs.sum()
            if (total / MS_PER_HOUR < limit("minHours")) return@rule emptyList()
            val low =
                signal.binMs
                    .take(LOW_BINS)
                    .sum()
                    .toDouble() / total
            if (low <= limit("lowSignalShare") || modem <= limit("modemShare")) return@rule emptyList()
            val values = mapOf("modemShare" to modem * PERCENT, "modemThreshold" to limit("modemShare") * PERCENT)
            listOf(found(null, day.span.evidence(low * PERCENT, limit("lowSignalShare") * PERCENT, EvidenceUnit.PERCENT, values)))
        }

    /** R08: Wi-Fi or Bluetooth scanning for more than 20 min in a day. The busier radio is the finding. */
    val scanHeavy =
        rule(RuleId.SCAN_HEAVY, setOf(RuleInput.DAY_USE), listOf(ActionId.OPEN_SCANNING_SETTINGS)) {
            val day = ctx.day ?: return@rule emptyList()
            val minutes = limit("scanMinutesPerDay")
            val (radio, ms) = if (day.bleScanMs > day.wifiScanMs) "bluetooth" to day.bleScanMs else "wifi" to day.wifiScanMs
            if (ms / MS_PER_MINUTE <= minutes) return@rule emptyList()
            val values = mapOf("wifi" to day.wifiScanMs / MS_PER_MINUTE, "bluetooth" to day.bleScanMs / MS_PER_MINUTE)
            listOf(found(null, day.span.evidence(ms / MS_PER_MINUTE, minutes, EvidenceUnit.MINUTES, values, mapOf("radio" to radio))))
        }

    /**
     * R19: more than 20% of the measured charge is unattributed, in the latest window of at least
     * 2 h. A window that measured very little is left out: its share is mostly rounding (ADR-0054).
     */
    val unattributedGap =
        rule(RuleId.UNATTRIBUTED_GAP, setOf(RuleInput.DISCHARGE_WINDOWS)) {
            val share = limit("unattributedShare")
            val window =
                ctx.dischargeWindows.lastOrNull {
                    it.durationMs / MS_PER_HOUR >= limit("minWindowHours") && it.measuredMah >= limit("minMeasuredMah")
                } ?: return@rule emptyList()
            val gap = window.unattributedMah / window.measuredMah
            if (gap <= share) return@rule emptyList()
            val values = mapOf("measuredMah" to window.measuredMah, "unattributedMah" to window.unattributedMah)
            listOf(found(null, window.span.evidence(gap * PERCENT, share * PERCENT, EvidenceUnit.PERCENT, values), window.windowId))
        }

    /**
     * R20: Coulomb's own share is above 0.3% of the battery per day (SPEC 6.9). The day's figure is
     * scaled to 24 h, and a span much shorter than a day is not judged.
     */
    val selfCost =
        rule(RuleId.SELF_COST, setOf(RuleInput.DAY_USE), listOf(ActionId.OPEN_SELF_DIAGNOSTIC, ActionId.SWITCH_TO_LITE)) {
            val day = ctx.day ?: return@rule emptyList()
            val self = day.self?.takeIf { it.capacityMah > 0 } ?: return@rule emptyList()
            if (day.durationMs / MS_PER_HOUR < limit("minHours")) return@rule emptyList()
            val perDay = self.ownMah / self.capacityMah * PERCENT * (MS_PER_DAY / day.durationMs)
            if (perDay <= limit("sharePctPerDay")) return@rule emptyList()
            listOf(found(null, day.span.evidence(perDay, limit("sharePctPerDay"), EvidenceUnit.PERCENT, mapOf("ownMah" to self.ownMah))))
        }
}
