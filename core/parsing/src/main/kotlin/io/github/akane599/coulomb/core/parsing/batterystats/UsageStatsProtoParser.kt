// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import io.github.akane599.coulomb.core.model.stats.UsageCheck
import io.github.akane599.coulomb.core.model.stats.UsageStatsDump
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.BatteryConsumerData
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.BatteryConsumerData.PowerComponentUsage
import io.github.akane599.coulomb.core.parsing.aosp.os.BatteryUsageStatsAtomsProto.UidBatteryConsumer
import io.github.akane599.coulomb.core.parsing.io.MeteredSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * `dumpsys batterystats --usage --proto` (S4) into a [UsageStatsDump] (ADR-0035 §4).
 *
 * The dump is small (a few entries per component and consumer), and a window may use it only whole,
 * so it is decoded in one go: a broken stream, or one cut inside a field, gives no dump. A stream cut
 * between fields decodes; the collector reads the process's whole output, and the window checks of
 * ADR-0035 §5 catch a UID that goes missing. Never throws on bad input.
 *
 * The writer (`PowerComponents.writeStatsProtoImpl`, frameworks/base/core/java/android/os/
 * PowerComponents.java L244-279 at android-17.0.0_r1, same code in android-16.0.0_r4) emits, per
 * component, its total and then one unlabelled entry per non-zero screen × power slot, and the same
 * for each (component, process state) slice. Only the first entry of each is read. It never writes
 * a zero (ProtoOutputStream skips default values), so an absent field is 0: an absent `uid` is root
 * and an absent `component` is the screen.
 */
public object UsageStatsProtoParser {
    public const val SOURCE: String = "batterystats.usage.proto"

    /** `BatteryConsumer.POWER_COMPONENT_COUNT`: ids 0-18 (BatteryConsumer.java L78-111, both tags). */
    private const val COMPONENT_COUNT = 19
    private const val REATTRIBUTED = 17
    private const val BASE = 18
    private const val FIRST_CUSTOM = 1000
    private const val LAST_CUSTOM = 9999

    // PowerComponentUsageSlice.ProcessState and its field number (batteryusagestats.proto L68-79).
    private const val PROCESS_STATE_TAG = 2
    private const val FOREGROUND = 1
    private const val BACKGROUND = 2
    private const val FOREGROUND_SERVICE = 3
    private const val CACHED = 4

    public fun parse(input: InputStream): Parsed<UsageStatsDump?> {
        val meter = MeteredSource(input)
        val ctx = ParseContext(SOURCE)
        val message = decode(meter, ctx) ?: return Parsed(null, ctx.report(meter.offset, complete = false))
        val dump = Mapper(ctx, meter.offset).map(message)
        return Parsed(dump, ctx.report(meter.offset, complete = true))
    }

    @Suppress("TooGenericExceptionCaught") // The parser boundary: bad input must never throw (SPEC 5.1).
    private fun decode(
        meter: MeteredSource,
        ctx: ParseContext,
    ): BatteryUsageStatsAtomsProto? =
        try {
            BatteryUsageStatsAtomsProto.ADAPTER.decode(meter.source)
        } catch (e: EOFException) {
            failed(ctx, meter, "ended early", e)
        } catch (e: IOException) {
            failed(ctx, meter, "malformed", e)
        } catch (e: RuntimeException) {
            failed(ctx, meter, "malformed", e)
        }

    private fun failed(
        ctx: ParseContext,
        meter: MeteredSource,
        what: String,
        e: Exception,
    ): BatteryUsageStatsAtomsProto? {
        ctx.issue(meter.offset, SOURCE, "$what: ${e.javaClass.simpleName}")
        return null
    }

    private class Mapper(
        private val ctx: ParseContext,
        private val offset: Long,
    ) {
        private val failures = LinkedHashMap<UsageCheck, Int>()
        private val seen = HashSet<Int>()
        private var unknownIdReported = false

        /** The layout's component ids: 0-18 and every custom id the dump names. */
        private var layoutIds = COMPONENT_COUNT

        fun map(m: BatteryUsageStatsAtomsProto): UsageStatsDump {
            ctx.unknownFields("BatteryUsageStatsAtomsProto", m)
            layoutIds = COMPONENT_COUNT + customIds(m).size
            // The device total is the measured discharge, not a sum of its components (ADR-0035 §4).
            val device = m.device_battery_consumer?.let { consumer(it, "device", checkSum = false) }
            if (device == null) fail(UsageCheck.NO_DEVICE)
            val uids = LinkedHashMap<Int, ComponentPower>()
            m.uid_battery_consumers.forEach { uid(it, uids) }
            return UsageStatsDump(
                sessionStartMillis = m.session_start_millis,
                sessionEndMillis = m.session_end_millis,
                device = device,
                uids = uids,
                failures = failures.toMap(),
            )
        }

        /** Checks one UID consumer and adds it to [uids] when it has a component entry (ADR-0035 §4). */
        private fun uid(
            u: UidBatteryConsumer,
            uids: MutableMap<Int, ComponentPower>,
        ) {
            ctx.unknownFields("UidBatteryConsumer", u)
            val uid = u.uid ?: 0
            if (!seen.add(uid)) {
                fail(UsageCheck.DUPLICATE_UID)
                return
            }
            val data = u.battery_consumer_data ?: return
            val power = consumer(data, "UidBatteryConsumer", checkSum = true)
            if (data.power_components.isNotEmpty()) uids[uid] = power
        }

        private fun customIds(m: BatteryUsageStatsAtomsProto): Set<Int> {
            val ids = HashSet<Int>()
            val consumers = listOfNotNull(m.device_battery_consumer) + m.uid_battery_consumers.mapNotNull { it.battery_consumer_data }
            for (c in consumers) {
                c.power_components.forEach { ids += it.component ?: 0 }
                c.slices.forEach { s -> s.power_component?.let { ids += it.component ?: 0 } }
            }
            m.component_models.forEach { ids += it.component ?: 0 }
            return ids.filterTo(HashSet()) { it in FIRST_CUSTOM..LAST_CUSTOM }
        }

        /** Checks one consumer and maps its first entries to columns. */
        private fun consumer(
            data: BatteryConsumerData,
            name: String,
            checkSum: Boolean,
        ): ComponentPower {
            ctx.unknownFields("$name.BatteryConsumerData", data)
            val totals = LinkedHashMap<Int, PowerComponentUsage>()
            for (usage in data.power_components) {
                ctx.unknownFields("PowerComponentUsage", usage)
                totals.putIfAbsent(usage.component ?: 0, usage)
            }
            // (component, process state) -> the first slice: the total the writer computed. The
            // writer never writes a slice without its usage.
            val slices = LinkedHashMap<Pair<Int, Int>, PowerComponentUsage>()
            for (slice in data.slices) {
                ctx.unknownFields("PowerComponentUsageSlice", slice, enumTag = PROCESS_STATE_TAG)
                val usage = slice.power_component ?: PowerComponentUsage()
                ctx.unknownFields("PowerComponentUsage", usage)
                val state = slice.process_state?.value ?: unknownVarint(slice, PROCESS_STATE_TAG)?.toInt() ?: 0
                if (slice.power_component != null) slices.putIfAbsent((usage.component ?: 0) to state, usage)
            }
            validate(data, totals, slices, checkSum)
            val columns = ComponentPower.Builder()
            for ((id, usage) in totals) componentColumn(id)?.let { columns.add(it, usage.power()) }
            for ((key, usage) in slices) stateColumn(key.second)?.let { columns.add(it, usage.power()) }
            return columns.build()
        }

        /** ADR-0035 §4's checks, made on every entry before anything is dropped. */
        private fun validate(
            data: BatteryConsumerData,
            totals: Map<Int, PowerComponentUsage>,
            slices: Map<Pair<Int, Int>, PowerComponentUsage>,
            checkSum: Boolean,
        ) {
            val total = data.total_consumed_power_deci_coulombs ?: 0L
            val all = data.power_components + data.slices.mapNotNull { it.power_component }
            if (total < 0 || all.any { it.power() < 0 || (it.duration_millis ?: 0L) < 0 }) fail(UsageCheck.NEGATIVE)
            if (all.any { (it.component == REATTRIBUTED || it.component == BASE) && it.power() != 0L }) fail(UsageCheck.NO_ENERGY_ID)
            // Each entry is rounded by itself, and so is the total: n + 1 roundings of at most 0.5 dC.
            if (checkSum && !withinRounding(totals.values.sumOf { it.power() } - total, layoutIds)) fail(UsageCheck.UID_SUM)
            val slicesAbove =
                slices.entries.groupBy({ it.key.first }, { it.value.power() }).any { (id, parts) ->
                    val excess = parts.sum() - (totals[id]?.power() ?: 0L)
                    excess > 0 && !withinRounding(excess, parts.size)
                }
            if (slicesAbove) fail(UsageCheck.SLICES_ABOVE_TOTAL)
        }

        private fun componentColumn(id: Int): PowerColumn? =
            when (id) {
                in NAMED.indices -> {
                    NAMED[id]
                }

                in FIRST_CUSTOM..LAST_CUSTOM -> {
                    PowerColumn.CUSTOM
                }

                else -> {
                    ctx.unknown("PowerComponentUsage.component.$id")
                    if (!unknownIdReported) {
                        unknownIdReported = true
                        ctx.issue(offset, SOURCE, "unknown component id $id, counted as other")
                    }
                    PowerColumn.OTHER
                }
            }

        private fun stateColumn(state: Int): PowerColumn? =
            when (state) {
                FOREGROUND -> {
                    PowerColumn.FOREGROUND
                }

                BACKGROUND -> {
                    PowerColumn.BACKGROUND
                }

                FOREGROUND_SERVICE -> {
                    PowerColumn.FOREGROUND_SERVICE
                }

                CACHED -> {
                    PowerColumn.CACHED
                }

                else -> {
                    // The writer never labels a slice UNSPECIFIED; a newer state stays out of the
                    // state columns and lands in "Not split" (ADR-0035 §7).
                    ctx.unknown("PowerComponentUsageSlice.process_state.$state")
                    null
                }
            }

        private fun fail(check: UsageCheck) {
            failures[check] = (failures[check] ?: 0) + 1
        }
    }

    /**
     * Columns of ids 0-18, by id (BatteryConsumer.java L78-111). System services (7), memory (13)
     * and idle (16) are named but not produced on 16 or 17 (MultiStatePowerAttributor L70-213).
     * Re-attributed (17) and base (18) carry no energy; a dump where they do has already failed
     * [UsageCheck.NO_ENERGY_ID].
     */
    private val NAMED: List<PowerColumn?> =
        listOf(
            PowerColumn.SCREEN,
            PowerColumn.CPU,
            PowerColumn.BLUETOOTH,
            PowerColumn.CAMERA,
            PowerColumn.AUDIO,
            PowerColumn.VIDEO,
            PowerColumn.FLASHLIGHT,
            PowerColumn.OTHER,
            PowerColumn.MOBILE_RADIO,
            PowerColumn.SENSORS,
            PowerColumn.GNSS,
            PowerColumn.WIFI,
            PowerColumn.WAKELOCK,
            PowerColumn.OTHER,
            PowerColumn.PHONE,
            PowerColumn.AMBIENT_DISPLAY,
            PowerColumn.OTHER,
            null,
            null,
        ).also { check(it.size == COMPONENT_COUNT) }

    private fun PowerComponentUsage.power(): Long = power_deci_coulombs ?: 0L

    /** |[difference]| ≤ 0.5 dC × ([parts] + 1), in whole numbers. */
    internal fun withinRounding(
        difference: Long,
        parts: Int,
    ): Boolean = 2 * kotlin.math.abs(difference) <= parts + 1L
}
