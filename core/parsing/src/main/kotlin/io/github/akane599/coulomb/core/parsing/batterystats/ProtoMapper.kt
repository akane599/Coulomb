// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.squareup.wire.FieldEncoding
import com.squareup.wire.Message
import com.squareup.wire.ProtoReader
import io.github.akane599.coulomb.core.model.stats.BinTime
import io.github.akane599.coulomb.core.model.stats.BluetoothStat
import io.github.akane599.coulomb.core.model.stats.DeviceMisc
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.Discharge
import io.github.akane599.coulomb.core.model.stats.JobStopCount
import io.github.akane599.coulomb.core.model.stats.JobTimer
import io.github.akane599.coulomb.core.model.stats.NamedCount
import io.github.akane599.coulomb.core.model.stats.NamedTimer
import io.github.akane599.coulomb.core.model.stats.NetworkStat
import io.github.akane599.coulomb.core.model.stats.PowerSummary
import io.github.akane599.coulomb.core.model.stats.ProcessStat
import io.github.akane599.coulomb.core.model.stats.ProcessStateTime
import io.github.akane599.coulomb.core.model.stats.SensorStat
import io.github.akane599.coulomb.core.model.stats.SipperKind
import io.github.akane599.coulomb.core.model.stats.SipperPower
import io.github.akane599.coulomb.core.model.stats.SyncTimer
import io.github.akane599.coulomb.core.model.stats.TimerStat
import io.github.akane599.coulomb.core.model.stats.UidProcessState
import io.github.akane599.coulomb.core.model.stats.UidStats
import io.github.akane599.coulomb.core.model.stats.WakelockStat
import io.github.akane599.coulomb.core.model.stats.WifiStat
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.aosp.job.InternalStopReasonEnum
import io.github.akane599.coulomb.core.parsing.aosp.os.SystemProto
import io.github.akane599.coulomb.core.parsing.aosp.os.TimerProto
import io.github.akane599.coulomb.core.parsing.aosp.os.UidProto
import okio.Buffer
import okio.ByteString

/**
 * Wire classes to the source-neutral model (ADR-0013).
 *
 * `ProtoOutputStream` never writes a zero, false, empty string or zero enum value, and drops a
 * nested message that ends up empty unless it is a repeated entry
 * (core/java/android/util/proto/ProtoOutputStream.java, android-16.0.0_r4 and android-17.0.0_r1:
 * writeInt32Impl L1062-1067, writeInt64Impl L1143-1147, writeDoubleImpl L905-909, writeBoolImpl
 * L1769-1773, writeStringImpl L1841-1845, writeEnumImpl L1952-1956, endObjectImpl L2088-2135).
 * So an absent field is its zero (root's entry has no uid, a top-state entry has no state), and
 * so is an absent message the writer always starts (`cpu`, `network`, `wifi`, `battery`, `misc`,
 * ...) or starts for an existing timer: an all-zero counter and a missing one both read as 0.
 * Only `bluetooth_misc` and `power_use_item` stay null when absent: the writer may skip them, and
 * it drops an all-zero `power_use_item`, so a UID with 0 mAh reads as null, never 0.0. A timer's
 * absent `max_duration_ms` is null too: the writer leaves out a zero maximum and an untracked one.
 * Entries with no time (wakelocks, jobs, syncs, sensors, processes) and zero stop-reason counts are
 * dropped, as the checkin writer drops them, so [BatteryStatsCheckinParser] gives the same lists.
 * An enum value the schema does not know (an OEM addition such as Samsung's job stop reason 100)
 * also decodes as absent; it is told apart from zero by the unknown field it leaves behind, and
 * counted.
 */
internal class ProtoMapper(
    private val ctx: ParseContext,
) {
    /** Maps one UID; [offset] is where its message starts, for issues. */
    fun uid(
        p: UidProto,
        offset: Long,
    ): UidStats {
        unknown("UidProto", p)
        return UidStats(
            uid = p.uid.orZero(),
            packages = p.packages.mapNotNull { it.name },
            powerMah =
                p.power_use_item?.let {
                    unknown("UidProto.PowerUseItem", it)
                    it.computed_power_mah.orZero()
                },
            cpuUserMs = (p.cpu?.user_duration_ms).orZero(),
            cpuSystemMs = (p.cpu?.system_duration_ms).orZero(),
            partialWakelocks = p.wakelocks.mapNotNull { wakelock(it, offset) },
            aggregatedPartialWakelockMs = (p.aggregated_wakelock?.partial_duration_ms).orZero(),
            aggregatedBackgroundPartialWakelockMs = (p.aggregated_wakelock?.background_partial_duration_ms).orZero(),
            wakeupAlarms = p.wakeup_alarm.map { NamedCount(it.name.orEmpty(), it.count.orZeroLong()) },
            jobs =
                p.jobs.mapNotNull {
                    unknown("UidProto.Job", it)
                    timer(it.total).takeIf(::hasTime)?.let { total -> JobTimer(it.name.orEmpty(), total, duration(it.background)) }
                },
            jobStopReasons = p.job_completion.flatMap(::stopCounts),
            syncs =
                p.syncs.mapNotNull {
                    timer(it.total).takeIf(::hasTime)?.let { total -> SyncTimer(it.name.orEmpty(), total, duration(it.background)) }
                },
            sensors =
                p.sensors.mapNotNull {
                    timer(it.apportioned).takeIf(::hasTime)?.let { total -> SensorStat(it.id.orZero(), total, duration(it.background)) }
                },
            network = network(p.network ?: UidProto.Network()),
            wifi = wifi(p.wifi ?: UidProto.Wifi()),
            bluetooth = p.bluetooth_misc?.let(::bluetooth),
            processStates =
                p.states.map {
                    unknown("UidProto.StateTime", it)
                    ProcessStateTime(processState(it), it.duration_ms.orZero())
                },
            foregroundActivityMs = duration(p.foreground_activity),
            foregroundServiceMs = duration(p.foreground_service),
            audioMs = duration(p.audio),
            videoMs = duration(p.video),
            cameraMs = duration(p.camera),
            flashlightMs = duration(p.flashlight),
            vibratorMs = duration(p.vibrator),
            processes = p.process.map(::process).filter(::hasData),
        )
    }

    /**
     * The partial timer's `duration_ms` is pooled; `total_duration_ms` is the actual time held, on
     * the same time base (see `CheckinUid.wakelock`, ADR-0039). The writer leaves it out at -1
     * (`BatteryStats.dumpTimer`, android-16.0.0_r4 L3837-3838, android-17.0.0_r1 L3858-3859), and a
     * zero is not written either, so absent reads as zero. The actual time is never below the pooled
     * one, give or take the pooled time's rounding; a row that says otherwise keeps the pooled time
     * and records an issue, as in checkin.
     */
    private fun wakelock(
        w: UidProto.Wakelock,
        offset: Long,
    ): WakelockStat? {
        unknown("UidProto.Wakelock", w)
        // An empty partial timer is not written; such a wakelock has no partial time to show.
        val partial = w.partial ?: return null
        val pooled = timer(partial)
        val actual = partial.total_duration_ms.orZero()
        if (!actualCovers(actual, pooled.totalMs)) {
            ctx.issue(offset, "UidProto.Wakelock", "partial wakelock without its actual time")
            return WakelockStat(w.name.orEmpty(), pooled, duration(w.background_partial))
        }
        return WakelockStat(w.name.orEmpty(), pooled.copy(totalMs = actual), duration(w.background_partial))
    }

    fun system(p: SystemProto): DeviceStats {
        unknown("SystemProto", p)
        val battery = (p.battery ?: SystemProto.Battery()).also { unknown("SystemProto.Battery", it) }
        return DeviceStats(
            startClockTimeWallMs = battery.start_clock_time_ms.orZero(),
            startCount = battery.start_count.orZero(),
            totalRealtimeMs = battery.total_realtime_ms.orZero(),
            totalUptimeMs = battery.total_uptime_ms.orZero(),
            batteryRealtimeMs = battery.battery_realtime_ms.orZero(),
            batteryUptimeMs = battery.battery_uptime_ms.orZero(),
            screenOffRealtimeMs = battery.screen_off_realtime_ms.orZero(),
            screenOffUptimeMs = battery.screen_off_uptime_ms.orZero(),
            screenDozeMs = battery.screen_doze_duration_ms.orZero(),
            estimatedCapacityMah = battery.estimated_battery_capacity_mah.orZero(),
            minLearnedCapacityUah = battery.min_learned_battery_capacity_uah.orZero(),
            maxLearnedCapacityUah = battery.max_learned_battery_capacity_uah.orZero(),
            discharge = discharge(p.battery_discharge ?: SystemProto.BatteryDischarge()),
            misc = misc(p.misc ?: SystemProto.Misc()),
            kernelWakelocks = p.kernel_wakelock.map { NamedTimer(it.name.orEmpty(), timer(it.total)) },
            wakeupReasons = p.wakeup_reason.map { NamedTimer(it.name.orEmpty(), timer(it.total)) },
            phoneSignalStrength =
                p.phone_signal_strength.map {
                    unknown("SystemProto.PhoneSignalStrength", it)
                    bin(it.name?.value ?: unknownVarint(it, NAME_TAG)?.toInt() ?: 0, timer(it.total))
                },
            screenBrightness =
                p.screen_brightness.map {
                    unknown("SystemProto.ScreenBrightness", it)
                    bin(it.name?.value ?: unknownVarint(it, NAME_TAG)?.toInt() ?: 0, timer(it.total))
                },
            signalScanningMs = duration(p.signal_scanning),
            powerUse =
                p.power_use_item.map {
                    unknown("SystemProto.PowerUseItem", it)
                    SipperPower(
                        kind = sipper(it.name),
                        uid = it.uid.orZero(),
                        mah = it.computed_power_mah.orZero(),
                        screenMah = it.screen_power_mah.orZero(),
                        smearMah = it.proportional_smear_mah.orZero(),
                        hidden = it.should_hide == true,
                    )
                },
            powerSummary =
                (p.power_use_summary ?: SystemProto.PowerUseSummary()).let {
                    PowerSummary(
                        capacityMah = it.battery_capacity_mah.orZero(),
                        computedMah = it.computed_power_mah.orZero(),
                        minDrainedMah = it.min_drained_power_mah.orZero(),
                        maxDrainedMah = it.max_drained_power_mah.orZero(),
                    )
                },
        )
    }

    private fun wifi(w: UidProto.Wifi): WifiStat =
        WifiStat(
            runningMs = w.running_duration_ms.orZero(),
            fullLockMs = w.full_wifi_lock_duration_ms.orZero(),
            scan = timer(w.apportioned_scan),
            backgroundScanMs = duration(w.background_scan),
        )

    private fun bluetooth(b: UidProto.BluetoothMisc): BluetoothStat =
        BluetoothStat(
            bleScan = timer(b.apportioned_ble_scan),
            backgroundBleScanMs = duration(b.background_ble_scan),
            unoptimizedBleScanMs = duration(b.unoptimized_ble_scan),
            bleScanResults = b.ble_scan_result_count.orZeroLong(),
        )

    private fun process(p: UidProto.Process): ProcessStat =
        ProcessStat(
            name = p.name.orEmpty(),
            userMs = p.user_duration_ms.orZero(),
            systemMs = p.system_duration_ms.orZero(),
            foregroundMs = p.foreground_duration_ms.orZero(),
            starts = p.start_count.orZeroLong(),
            anrs = p.anr_count.orZeroLong(),
            crashes = p.crash_count.orZeroLong(),
        )

    private fun stopCounts(completion: UidProto.JobCompletion): List<JobStopCount> =
        completion.reason_count.mapNotNull {
            unknown("UidProto.JobCompletion.ReasonCount", it)
            // Every code is listed for every job; most counts are zero.
            it.count?.takeIf { n -> n != 0 }?.let { n -> JobStopCount(completion.name.orEmpty(), stopReason(it), n.toLong()) }
        }

    private fun discharge(d: SystemProto.BatteryDischarge): Discharge {
        unknown("SystemProto.BatteryDischarge", d)
        return Discharge(
            lowerBoundSinceChargePct = d.lower_bound_since_charge.orZero(),
            upperBoundSinceChargePct = d.upper_bound_since_charge.orZero(),
            screenOnSinceChargePct = d.screen_on_since_charge.orZero(),
            screenOffSinceChargePct = d.screen_off_since_charge.orZero(),
            screenDozeSinceChargePct = d.screen_doze_since_charge.orZero(),
            totalMah = d.total_mah.orZero(),
            screenOffMah = d.total_mah_screen_off.orZero(),
            screenDozeMah = d.total_mah_screen_doze.orZero(),
            lightDozeMah = d.total_mah_light_doze.orZero(),
            deepDozeMah = d.total_mah_deep_doze.orZero(),
        )
    }

    private fun misc(m: SystemProto.Misc): DeviceMisc {
        unknown("SystemProto.Misc", m)
        return DeviceMisc(
            screenOnMs = m.screen_on_duration_ms.orZero(),
            phoneOnMs = m.phone_on_duration_ms.orZero(),
            partialWakelockTotalMs = m.partial_wakelock_total_duration_ms.orZero(),
            mobileRadioActiveMs = m.mobile_radio_active_duration_ms.orZero(),
            mobileRadioActiveCount = m.mobile_radio_active_count.orZeroLong(),
            interactiveMs = m.interactive_duration_ms.orZero(),
            batterySaverMs = m.battery_saver_mode_enabled_duration_ms.orZero(),
            deepDozeEnabledMs = m.deep_doze_enabled_duration_ms.orZero(),
            deepDozeCount = m.deep_doze_count.orZeroLong(),
            deepDozeIdlingMs = m.deep_doze_idling_duration_ms.orZero(),
            deepDozeIdlingCount = m.deep_doze_idling_count.orZeroLong(),
            longestDeepDozeMs = m.longest_deep_doze_duration_ms.orZero(),
            lightDozeEnabledMs = m.light_doze_enabled_duration_ms.orZero(),
            lightDozeCount = m.light_doze_count.orZeroLong(),
            lightDozeIdlingMs = m.light_doze_idling_duration_ms.orZero(),
            lightDozeIdlingCount = m.light_doze_idling_count.orZeroLong(),
            longestLightDozeMs = m.longest_light_doze_duration_ms.orZero(),
        )
    }

    private fun network(n: UidProto.Network): NetworkStat {
        unknown("UidProto.Network", n)
        return NetworkStat(
            mobileRxBytes = n.mobile_bytes_rx.orZero(),
            mobileTxBytes = n.mobile_bytes_tx.orZero(),
            wifiRxBytes = n.wifi_bytes_rx.orZero(),
            wifiTxBytes = n.wifi_bytes_tx.orZero(),
            bluetoothRxBytes = n.bt_bytes_rx.orZero(),
            bluetoothTxBytes = n.bt_bytes_tx.orZero(),
            mobileActiveMs = n.mobile_active_duration_ms.orZero(),
            mobileActiveCount = n.mobile_active_count.orZeroLong(),
            mobileWakeups = n.mobile_wakeup_count.orZeroLong(),
            wifiWakeups = n.wifi_wakeup_count.orZeroLong(),
        )
    }

    /** An absent timer reads as zero. `max_duration_ms` is -1 when the timer does not track it. */
    private fun timer(t: TimerProto?): TimerStat {
        if (t == null) return TimerStat(0, 0)
        unknown("TimerProto", t)
        return TimerStat(totalMs = t.duration_ms.orZero(), count = t.count.orZero(), maxMs = t.max_duration_ms?.takeIf { it >= 0 })
    }

    /** An absent timer has no time: the writer skips empty ones. */
    private fun duration(t: TimerProto?): Long = t?.duration_ms.orZero()

    private fun hasTime(t: TimerStat): Boolean = t.totalMs != 0L

    private fun hasData(p: ProcessStat): Boolean =
        listOf(p.userMs, p.systemMs, p.foregroundMs, p.starts, p.anrs, p.crashes).any { it != null && it != 0L }

    private fun bin(
        bin: Int,
        timer: TimerStat,
    ): BinTime = BinTime(bin, timer.totalMs, timer.count)

    private fun stopReason(r: UidProto.JobCompletion.ReasonCount): String =
        r.name?.name
            ?: unknownVarint(r, NAME_TAG)?.let { JobStopReasons.name(it.toInt()) }
            ?: InternalStopReasonEnum.INTERNAL_STOP_REASON_CANCELLED.name

    private fun processState(s: UidProto.StateTime): UidProcessState =
        when (s.state) {
            UidProto.StateTime.State.PROCESS_STATE_TOP -> UidProcessState.TOP

            UidProto.StateTime.State.PROCESS_STATE_FOREGROUND_SERVICE -> UidProcessState.FOREGROUND_SERVICE

            UidProto.StateTime.State.PROCESS_STATE_FOREGROUND -> UidProcessState.FOREGROUND

            UidProto.StateTime.State.PROCESS_STATE_BACKGROUND -> UidProcessState.BACKGROUND

            UidProto.StateTime.State.PROCESS_STATE_TOP_SLEEPING -> UidProcessState.TOP_SLEEPING

            UidProto.StateTime.State.PROCESS_STATE_HEAVY_WEIGHT -> UidProcessState.HEAVY_WEIGHT

            UidProto.StateTime.State.PROCESS_STATE_CACHED -> UidProcessState.CACHED

            // Absent is the zero value, TOP, unless an unknown value took its place.
            null -> if (unknownVarint(s, STATE_TAG) == null) UidProcessState.TOP else UidProcessState.UNKNOWN
        }

    private fun sipper(s: SystemProto.PowerUseItem.Sipper?): SipperKind =
        when (s) {
            SystemProto.PowerUseItem.Sipper.IDLE -> SipperKind.IDLE
            SystemProto.PowerUseItem.Sipper.CELL -> SipperKind.CELL
            SystemProto.PowerUseItem.Sipper.PHONE -> SipperKind.PHONE
            SystemProto.PowerUseItem.Sipper.WIFI -> SipperKind.WIFI
            SystemProto.PowerUseItem.Sipper.BLUETOOTH -> SipperKind.BLUETOOTH
            SystemProto.PowerUseItem.Sipper.FLASHLIGHT -> SipperKind.FLASHLIGHT
            SystemProto.PowerUseItem.Sipper.SCREEN -> SipperKind.SCREEN
            SystemProto.PowerUseItem.Sipper.USER -> SipperKind.USER
            SystemProto.PowerUseItem.Sipper.UNACCOUNTED -> SipperKind.UNACCOUNTED
            SystemProto.PowerUseItem.Sipper.OVERCOUNTED -> SipperKind.OVERCOUNTED
            SystemProto.PowerUseItem.Sipper.CAMERA -> SipperKind.CAMERA
            SystemProto.PowerUseItem.Sipper.MEMORY -> SipperKind.MEMORY
            SystemProto.PowerUseItem.Sipper.AMBIENT_DISPLAY -> SipperKind.AMBIENT_DISPLAY
            SystemProto.PowerUseItem.Sipper.UNKNOWN_SIPPER, null -> SipperKind.UNKNOWN
        }

    /** Counts each unknown field of [message] as `name.tag`; tags only, never values. */
    private fun unknown(
        name: String,
        message: Message<*, *>,
    ) {
        val fields = message.unknownFields
        if (fields.size == 0) return
        forEachField(fields) { tag, _ -> ctx.unknown("$name.$tag") }
    }

    /** The raw value of an unknown varint field, e.g. an enum value the schema does not know. */
    private fun unknownVarint(
        message: Message<*, *>,
        tag: Int,
    ): Long? {
        val fields = message.unknownFields
        if (fields.size == 0) return null
        var value: Long? = null
        forEachField(fields) { t, v -> if (t == tag && v != null) value = v }
        return value
    }

    /** Calls [onField] with each field's tag and, for varints, its value. */
    private inline fun forEachField(
        fields: ByteString,
        onField: (Int, Long?) -> Unit,
    ) {
        val reader = ProtoReader(Buffer().write(fields))
        val token = reader.beginMessage()
        while (true) {
            val tag = reader.nextTag()
            if (tag == -1) break
            if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
                onField(tag, reader.readVarint64())
            } else {
                onField(tag, null)
                reader.skip()
            }
        }
        reader.endMessageAndGetUnknownFields(token)
    }

    private companion object {
        /** The `name` field of ReasonCount, PhoneSignalStrength and ScreenBrightness. */
        const val NAME_TAG = 1

        /** The `state` field of UidProto.StateTime. */
        const val STATE_TAG = 1
    }
}

private fun Long?.orZero(): Long = this ?: 0L

/**
 * A partial wakelock's actual time is whole milliseconds and never below its pooled time, which is
 * rounded from microseconds (`BatteryStats.roundUsToMs`), so the two may differ by 1 ms the wrong way.
 */
internal fun actualCovers(
    actualMs: Long,
    pooledMs: Long,
): Boolean = actualMs >= 0 && actualMs + 1 >= pooledMs

private fun Int?.orZero(): Int = this ?: 0

private fun Int?.orZeroLong(): Long = this?.toLong() ?: 0L

private fun Double?.orZero(): Double = this ?: 0.0
