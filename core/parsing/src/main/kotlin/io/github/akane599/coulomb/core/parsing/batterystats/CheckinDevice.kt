// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.model.stats.BinTime
import io.github.akane599.coulomb.core.model.stats.DeviceMisc
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.Discharge
import io.github.akane599.coulomb.core.model.stats.NamedTimer
import io.github.akane599.coulomb.core.model.stats.PowerSummary
import io.github.akane599.coulomb.core.model.stats.SipperKind
import io.github.akane599.coulomb.core.model.stats.SipperPower
import io.github.akane599.coulomb.core.model.stats.TimerStat
import io.github.akane599.coulomb.core.parsing.ParseContext

/** Collects the device-wide checkin lines into a [DeviceStats] in the proto parser's shape (ADR-0013). */
internal class CheckinDevice(
    private val ctx: ParseContext,
) {
    private var stats = DeviceStats()
    private val kernelWakelocks = ArrayList<NamedTimer>()
    private val wakeupReasons = ArrayList<NamedTimer>()
    private val powerUse = ArrayList<SipperPower>()
    private var brightness: List<Long?> = emptyList()
    private var signalTimes: List<Long?> = emptyList()
    private var signalCounts: List<Long?> = emptyList()

    /** True once the `bt` line, which every since-charged dump starts with, was read. */
    var sawBattery = false
        private set

    /** Reads [row] when it is a section this class maps; false otherwise. */
    fun accept(row: CheckinRow): Boolean {
        when (row.type) {
            "bt" -> battery(row)
            "dc" -> discharge(row)
            "m" -> misc(row)
            "kwl" -> kernelWakelock(row)
            "wr" -> wakeupReason(row)
            "br" -> brightness = bins(row)
            "sgt" -> signalTimes = bins(row)
            "sgc" -> signalCounts = bins(row)
            "sst" -> stats = stats.copy(signalScanningMs = row.long(0).also { row.expect(1) })
            "pws" -> powerSummary(row)
            "pwi" -> powerItem(row)
            else -> return false
        }
        return true
    }

    fun build(): DeviceStats =
        stats.copy(
            kernelWakelocks = kernelWakelocks.toList(),
            wakeupReasons = wakeupReasons.toList(),
            // Checkin prints brightness times without counts (BatteryStats.java android-17.0.0_r1 L4396-4401).
            screenBrightness = brightness.mapIndexedNotNull { bin, ms -> ms?.let { BinTime(bin, it, null) } },
            phoneSignalStrength = signalTimes.mapIndexedNotNull { bin, ms -> ms?.let { BinTime(bin, it, signalCounts.getOrNull(bin)) } },
            powerUse = powerUse.toList(),
        )

    private fun battery(row: CheckinRow) {
        row.expect(BtCol.entries.size)
        sawBattery = true
        stats =
            stats.copy(
                // "N/A" only outside since-charged dumps, which Coulomb never reads.
                startCount = row.text(BtCol.START_COUNT)?.toLongOrNull(),
                batteryRealtimeMs = row.long(BtCol.BATTERY_REALTIME),
                batteryUptimeMs = row.long(BtCol.BATTERY_UPTIME),
                totalRealtimeMs = row.long(BtCol.TOTAL_REALTIME),
                totalUptimeMs = row.long(BtCol.TOTAL_UPTIME),
                startClockTimeWallMs = row.long(BtCol.START_CLOCK_TIME),
                screenOffRealtimeMs = row.long(BtCol.SCREEN_OFF_REALTIME),
                screenOffUptimeMs = row.long(BtCol.SCREEN_OFF_UPTIME),
                estimatedCapacityMah = row.long(BtCol.ESTIMATED_CAPACITY_MAH),
                minLearnedCapacityUah = row.long(BtCol.MIN_LEARNED_CAPACITY_UAH),
                maxLearnedCapacityUah = row.long(BtCol.MAX_LEARNED_CAPACITY_UAH),
                screenDozeMs = row.long(BtCol.SCREEN_DOZE),
            )
    }

    private fun discharge(row: CheckinRow) {
        row.expect(DischargeCol.entries.size)
        stats =
            stats.copy(
                discharge =
                    Discharge(
                        lowerBoundSinceChargePct = row.long(DischargeCol.LOWER_BOUND_PCT)?.toInt(),
                        upperBoundSinceChargePct = row.long(DischargeCol.UPPER_BOUND_PCT)?.toInt(),
                        screenOnSinceChargePct = row.long(DischargeCol.SCREEN_ON_PCT)?.toInt(),
                        screenOffSinceChargePct = row.long(DischargeCol.SCREEN_OFF_PCT)?.toInt(),
                        screenDozeSinceChargePct = row.long(DischargeCol.SCREEN_DOZE_PCT)?.toInt(),
                        totalMah = row.long(DischargeCol.TOTAL_MAH),
                        screenOffMah = row.long(DischargeCol.SCREEN_OFF_MAH),
                        screenDozeMah = row.long(DischargeCol.SCREEN_DOZE_MAH),
                        lightDozeMah = row.long(DischargeCol.LIGHT_DOZE_MAH),
                        deepDozeMah = row.long(DischargeCol.DEEP_DOZE_MAH),
                    ),
            )
    }

    private fun misc(row: CheckinRow) {
        row.expect(MiscCol.entries.size)
        stats =
            stats.copy(
                misc =
                    DeviceMisc(
                        screenOnMs = row.long(MiscCol.SCREEN_ON),
                        phoneOnMs = row.long(MiscCol.PHONE_ON),
                        partialWakelockTotalMs = row.long(MiscCol.PARTIAL_WAKELOCK_TOTAL),
                        mobileRadioActiveMs = row.long(MiscCol.MOBILE_RADIO_ACTIVE),
                        mobileRadioActiveCount = row.long(MiscCol.MOBILE_RADIO_ACTIVE_COUNT),
                        interactiveMs = row.long(MiscCol.INTERACTIVE),
                        batterySaverMs = row.long(MiscCol.BATTERY_SAVER),
                        deepDozeEnabledMs = row.long(MiscCol.DEEP_DOZE_ENABLED),
                        deepDozeCount = row.long(MiscCol.DEEP_DOZE_COUNT),
                        deepDozeIdlingMs = row.long(MiscCol.DEEP_DOZE_IDLING),
                        deepDozeIdlingCount = row.long(MiscCol.DEEP_DOZE_IDLING_COUNT),
                        longestDeepDozeMs = row.long(MiscCol.LONGEST_DEEP_DOZE),
                        lightDozeEnabledMs = row.long(MiscCol.LIGHT_DOZE_ENABLED),
                        lightDozeCount = row.long(MiscCol.LIGHT_DOZE_COUNT),
                        lightDozeIdlingMs = row.long(MiscCol.LIGHT_DOZE_IDLING),
                        lightDozeIdlingCount = row.long(MiscCol.LIGHT_DOZE_IDLING_COUNT),
                        longestLightDozeMs = row.long(MiscCol.LONGEST_LIGHT_DOZE),
                    ),
            )
    }

    private fun kernelWakelock(row: CheckinRow) {
        row.expect(KernelWakelockCol.entries.size)
        val name = row.text(KernelWakelockCol.NAME) ?: return
        val total = row.long(KernelWakelockCol.TOTAL) ?: return
        val count = row.long(KernelWakelockCol.COUNT) ?: return
        // -1: the kernel does not track a maximum.
        kernelWakelocks += NamedTimer(name, TimerStat(total, count, row.long(KernelWakelockCol.MAX)?.takeIf { it >= 0 }))
    }

    private fun wakeupReason(row: CheckinRow) {
        row.expect(WakeupReasonCol.entries.size)
        val name = row.text(WakeupReasonCol.NAME) ?: return
        val total = row.long(WakeupReasonCol.TOTAL) ?: return
        val count = row.long(WakeupReasonCol.COUNT) ?: return
        wakeupReasons += NamedTimer(name, TimerStat(total, count))
    }

    /** One value per bin; the bin count is the platform's, so extra columns are bins, not unknown. */
    private fun bins(row: CheckinRow): List<Long?> = List(row.size) { row.long(it) }

    private fun powerSummary(row: CheckinRow) {
        row.expect(PowerSummaryCol.entries.size)
        stats =
            stats.copy(
                powerSummary =
                    PowerSummary(
                        capacityMah = row.double(PowerSummaryCol.CAPACITY_MAH),
                        computedMah = row.double(PowerSummaryCol.COMPUTED_MAH),
                        minDrainedMah = row.double(PowerSummaryCol.MIN_DRAINED_MAH),
                        maxDrainedMah = row.double(PowerSummaryCol.MAX_DRAINED_MAH),
                    ),
            )
    }

    private fun powerItem(row: CheckinRow) {
        row.expect(PowerItemCol.entries.size)
        val label = row.text(PowerItemCol.LABEL) ?: return
        val mah = row.double(PowerItemCol.MAH) ?: return
        val kind = SIPPERS[label] ?: SipperKind.UNKNOWN.also { if (label != NO_LABEL) ctx.unknown("pwi.$label") }
        powerUse +=
            SipperPower(
                kind = kind,
                uid = row.uid,
                mah = mah,
                screenMah = row.double(PowerItemCol.SCREEN_MAH),
                smearMah = row.double(PowerItemCol.SMEAR_MAH),
                hidden = row.text(PowerItemCol.HIDDEN) == "1",
            )
    }

    private companion object {
        /** A component without a checkin label. */
        const val NO_LABEL = "???"

        /**
         * CHECKIN_POWER_COMPONENT_LABELS (BatteryStats.java android-17.0.0_r1 L4234-4252) to the
         * kinds the proto writer gives the same components (L8737-8782). Components the proto
         * writes as UNKNOWN_SIPPER (cpu, audio, video, sensors, gnss) stay UNKNOWN here, so both
         * sources give the same list.
         */
        val SIPPERS =
            mapOf(
                "scrn" to SipperKind.SCREEN,
                "cpu" to SipperKind.UNKNOWN,
                "blue" to SipperKind.BLUETOOTH,
                "camera" to SipperKind.CAMERA,
                "audio" to SipperKind.UNKNOWN,
                "video" to SipperKind.UNKNOWN,
                "flashlight" to SipperKind.FLASHLIGHT,
                "cell" to SipperKind.CELL,
                "sensors" to SipperKind.UNKNOWN,
                "gnss" to SipperKind.UNKNOWN,
                "wifi" to SipperKind.WIFI,
                "memory" to SipperKind.MEMORY,
                "phone" to SipperKind.PHONE,
                "ambi" to SipperKind.AMBIENT_DISPLAY,
                "idle" to SipperKind.IDLE,
            )
    }
}
