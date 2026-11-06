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
 * `ProtoOutputStream` never writes a zero, false, empty string or zero enum value
 * (core/java/android/util/proto/ProtoOutputStream.java, android-16.0.0_r4 and android-17.0.0_r1:
 * writeInt32Impl L1062-1067, writeInt64Impl L1143-1147, writeDoubleImpl L905-909, writeBoolImpl
 * L1769-1773, writeStringImpl L1841-1845, writeEnumImpl L1952-1956). So inside a message that is
 * present, an absent field is that zero: root's entry has no uid, a top-state entry has no state.
 * A message that is absent stays null in the model. An enum value the schema does not know (an OEM
 * addition such as Samsung's job stop reason 100) also decodes as absent; it is told apart from
 * zero by the unknown field it leaves behind, and counted.
 */
internal class ProtoMapper(
    private val ctx: ParseContext,
) {
    fun uid(p: UidProto): UidStats {
        unknown("UidProto", p)
        return UidStats(
            uid = p.uid.orZero(),
            packages = p.packages.mapNotNull { it.name },
            powerMah =
                p.power_use_item?.let {
                    unknown("UidProto.PowerUseItem", it)
                    it.computed_power_mah.orZero()
                },
            cpuUserMs = p.cpu?.let { it.user_duration_ms.orZero() },
            cpuSystemMs = p.cpu?.let { it.system_duration_ms.orZero() },
            partialWakelocks =
                p.wakelocks.mapNotNull { w ->
                    unknown("UidProto.Wakelock", w)
                    w.partial?.let { WakelockStat(w.name.orEmpty(), timer(it), w.background_partial?.let(::duration)) }
                },
            aggregatedPartialWakelockMs = p.aggregated_wakelock?.let { it.partial_duration_ms.orZero() },
            aggregatedBackgroundPartialWakelockMs = p.aggregated_wakelock?.let { it.background_partial_duration_ms.orZero() },
            wakeupAlarms = p.wakeup_alarm.map { NamedCount(it.name.orEmpty(), it.count.orZeroLong()) },
            jobs =
                p.jobs.map {
                    unknown("UidProto.Job", it)
                    JobTimer(it.name.orEmpty(), timer(it.total), it.background?.let(::duration))
                },
            jobStopReasons = p.job_completion.flatMap(::stopCounts),
            syncs = p.syncs.map { SyncTimer(it.name.orEmpty(), timer(it.total), it.background?.let(::duration)) },
            sensors = p.sensors.map { SensorStat(it.id.orZero(), timer(it.apportioned), it.background?.let(::duration)) },
            network = p.network?.let(::network),
            wifi = p.wifi?.let(::wifi),
            bluetooth = p.bluetooth_misc?.let(::bluetooth),
            processStates =
                p.states.map {
                    unknown("UidProto.StateTime", it)
                    ProcessStateTime(processState(it), it.duration_ms.orZero())
                },
            foregroundActivityMs = p.foreground_activity?.let(::duration),
            foregroundServiceMs = p.foreground_service?.let(::duration),
            audioMs = p.audio?.let(::duration),
            videoMs = p.video?.let(::duration),
            cameraMs = p.camera?.let(::duration),
            flashlightMs = p.flashlight?.let(::duration),
            vibratorMs = p.vibrator?.let(::duration),
            processes = p.process.map(::process),
        )
    }

    fun system(p: SystemProto): DeviceStats {
        unknown("SystemProto", p)
        val battery = p.battery?.also { unknown("SystemProto.Battery", it) }
        return DeviceStats(
            startClockTimeWallMs = battery.field { it.start_clock_time_ms },
            startCount = battery.field { it.start_count },
            totalRealtimeMs = battery.field { it.total_realtime_ms },
            totalUptimeMs = battery.field { it.total_uptime_ms },
            batteryRealtimeMs = battery.field { it.battery_realtime_ms },
            batteryUptimeMs = battery.field { it.battery_uptime_ms },
            screenOffRealtimeMs = battery.field { it.screen_off_realtime_ms },
            screenOffUptimeMs = battery.field { it.screen_off_uptime_ms },
            screenDozeMs = battery.field { it.screen_doze_duration_ms },
            estimatedCapacityMah = battery.field { it.estimated_battery_capacity_mah },
            minLearnedCapacityUah = battery.field { it.min_learned_battery_capacity_uah },
            maxLearnedCapacityUah = battery.field { it.max_learned_battery_capacity_uah },
            discharge = p.battery_discharge?.let(::discharge),
            misc = p.misc?.let(::misc),
            kernelWakelocks = p.kernel_wakelock.map { NamedTimer(it.name.orEmpty(), timer(it.total)) },
            wakeupReasons = p.wakeup_reason.map { NamedTimer(it.name.orEmpty(), timer(it.total)) },
            phoneSignalStrength =
                p.phone_signal_strength.map {
                    unknown("SystemProto.PhoneSignalStrength", it)
                    BinTime(it.name?.value ?: unknownVarint(it, NAME_TAG)?.toInt() ?: 0, timer(it.total))
                },
            screenBrightness =
                p.screen_brightness.map {
                    unknown("SystemProto.ScreenBrightness", it)
                    BinTime(it.name?.value ?: unknownVarint(it, NAME_TAG)?.toInt() ?: 0, timer(it.total))
                },
            signalScanning = p.signal_scanning?.let(::timer),
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
                p.power_use_summary?.let {
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
            scan = w.apportioned_scan?.let(::timer),
            backgroundScanMs = w.background_scan?.let(::duration),
        )

    private fun bluetooth(b: UidProto.BluetoothMisc): BluetoothStat =
        BluetoothStat(
            bleScan = b.apportioned_ble_scan?.let(::timer),
            backgroundBleScanMs = b.background_ble_scan?.let(::duration),
            unoptimizedBleScanMs = b.unoptimized_ble_scan?.let(::duration),
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
        completion.reason_count.map {
            unknown("UidProto.JobCompletion.ReasonCount", it)
            JobStopCount(completion.name.orEmpty(), stopReason(it), it.count.orZeroLong())
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

    private fun duration(t: TimerProto): Long = t.duration_ms.orZero()

    private fun stopReason(r: UidProto.JobCompletion.ReasonCount): String =
        r.name?.name
            ?: unknownVarint(r, NAME_TAG)?.let { "$OEM_PREFIX$it" }
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

        /** Prefix for a job stop reason the AOSP enum does not define. */
        const val OEM_PREFIX = "OEM_"
    }
}

private fun Long?.orZero(): Long = this ?: 0L

/** A field of a message that may be absent: null when the message is, its zero when only the field is. */
private inline fun SystemProto.Battery?.field(get: (SystemProto.Battery) -> Long?): Long? = this?.let { get(it) ?: 0L }

private fun Int?.orZero(): Int = this ?: 0

private fun Int?.orZeroLong(): Long = this?.toLong() ?: 0L

private fun Double?.orZero(): Double = this ?: 0.0
