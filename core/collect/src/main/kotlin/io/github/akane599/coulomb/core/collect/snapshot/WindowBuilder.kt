// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.analytics.Attribution
import io.github.akane599.coulomb.core.analytics.CounterWindow
import io.github.akane599.coulomb.core.analytics.Counters
import io.github.akane599.coulomb.core.analytics.StatsEpoch
import io.github.akane599.coulomb.core.database.dao.SnapshotRows
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.database.entity.WindowWakeEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlin.math.abs
import kotlin.math.roundToLong

/** One end of a window: the snapshot and its counters. */
internal data class WindowEnd(
    val snapshot: SnapshotEntity,
    val rows: SnapshotRows,
)

/**
 * Builds a stored window over a chain of heavy snapshots (SPEC 6.2, 6.5; ADR-0015, ADR-0018). Pure.
 *
 * The chain runs from the window's start to its end and holds every snapshot in between whose
 * counters are still stored; [Attribution.window] splits the counters at each reset. Measured
 * drain is the charge counter's change when it is reliable and both ends are in one boot, else
 * the level change times the capacity (Estimated). Modeled drain is the power model's per-UID
 * total change. Detail rows keep the UIDs above [MIN_UID_MAH] (at most [MAX_UIDS]); the rest are
 * summed into [WindowUidEntity.OTHER_UID].
 */
internal object WindowBuilder {
    const val MIN_UID_MAH: Double = 0.1
    const val MAX_UIDS: Int = 30
    const val MIN_WAKE_MS: Long = 1_000
    const val MAX_WAKES_PER_KIND: Int = 30

    private const val NS_PER_MS = 1_000_000L
    private const val UAH_PER_MAH = 1_000.0
    private const val PERCENT = 100.0
    private const val COUNTER_STEP_MAH = 1.0
    private const val LEVEL_QUALITY = 0.5
    private const val PARTIAL_PENALTY = 0.5

    fun build(
        kind: WindowKind,
        sessionId: Long?,
        chain: List<WindowEnd>,
        counterReliable: Boolean,
        capacityMah: Double?,
        withDetail: Boolean,
    ): Pair<WindowResultEntity, WindowRows> {
        require(chain.size >= 2) { "a window needs two snapshots" }
        val from = chain.first()
        val to = chain.last()
        val power = window(chain) { rows -> rows.uidPower.associate { PowerKey(it.uid, it.component, it.processState) to it.mAh } }
        val device = window(chain) { rows -> rows.deviceStats.associate { it.key to it.value.toDouble() } }
        val modeled =
            power.values.values
                .sum()
                .takeIf { from.rows.uidPower.isNotEmpty() && to.rows.uidPower.isNotEmpty() }
        val (measured, source) = measured(from.snapshot, to.snapshot, counterReliable, capacityMah)
        val screenOn = device.values[DeviceKeys.SCREEN_ON_MS]?.roundToLong() ?: 0
        val realtime =
            device.values[DeviceKeys.TOTAL_REALTIME_MS]?.roundToLong()
                ?: ((to.snapshot.elapsedNs - from.snapshot.elapsedNs) / NS_PER_MS).takeIf { from.snapshot.bootSeq == to.snapshot.bootSeq }
                ?: 0
        val window =
            WindowResultEntity(
                kind = kind,
                sessionId = sessionId,
                fromSnapshotId = from.snapshot.id,
                toSnapshotId = to.snapshot.id,
                measuredMah = measured,
                chargeSource = source,
                modeledMah = modeled,
                unattributedMah = Attribution.reconcile(measured, modeled).unattributedMah,
                screenOnMs = screenOn,
                screenOffMs = (realtime - screenOn).coerceAtLeast(0),
                awakeMsScreenOff = device.values[DeviceKeys.SCREEN_OFF_UPTIME_MS]?.roundToLong(),
                quality = quality(measured, source, from.snapshot, to.snapshot, power.partial || device.partial),
                detailPruned = !withDetail,
            )
        return window to if (withDetail) detail(chain, power) else WindowRows()
    }

    private data class PowerKey(
        val uid: Int,
        val component: String,
        val processState: String,
    )

    private data class WakeKey(
        val uid: Int?,
        val tag: String,
        val kind: WakeKind,
    )

    private data class StatKey(
        val uid: Int,
        val key: String,
    )

    private fun <K> window(
        chain: List<WindowEnd>,
        values: (SnapshotRows) -> Map<K, Double>,
    ): CounterWindow<K> = Attribution.window(chain.map { counters(it, values(it.rows)) })

    private fun <K> counters(
        end: WindowEnd,
        values: Map<K, Double>,
    ) = Counters(
        bootSeq = end.snapshot.bootSeq,
        elapsedMs = end.snapshot.elapsedNs / NS_PER_MS,
        wallMs = end.snapshot.wallMs,
        epoch = StatsEpoch(end.snapshot.statsStartWall, end.snapshot.statsRealtimeMs),
        values = values,
    )

    /** Drain as positive mAh (negative while charging) and its source; null when neither is known. */
    private fun measured(
        a: SnapshotEntity,
        b: SnapshotEntity,
        counterReliable: Boolean,
        capacityMah: Double?,
    ): Pair<Double?, ChargeSource?> {
        val qa = a.chargeUah?.takeIf { it > 0 }
        val qb = b.chargeUah?.takeIf { it > 0 }
        val la = a.levelPct
        val lb = b.levelPct
        return when {
            counterReliable && qa != null && qb != null -> {
                (qa - qb) / UAH_PER_MAH to ChargeSource.CHARGE_COUNTER
            }

            capacityMah != null && capacityMah > 0 && la != null && lb != null -> {
                (la - lb) / PERCENT * capacityMah to
                    ChargeSource.LEVEL_DELTA
            }

            else -> {
                null to null
            }
        }
    }

    /** As the drain rate's (ADR-0017): source times resolution, halved for a partial window. */
    private fun quality(
        measured: Double?,
        source: ChargeSource?,
        a: SnapshotEntity,
        b: SnapshotEntity,
        partial: Boolean,
    ): Double {
        val base =
            when (source) {
                ChargeSource.CHARGE_COUNTER -> {
                    resolution(abs(measured ?: 0.0), COUNTER_STEP_MAH)
                }

                ChargeSource.LEVEL_DELTA, ChargeSource.INTEGRATED_CURRENT -> {
                    val levels = abs((b.levelPct ?: 0) - (a.levelPct ?: 0)).toDouble()
                    LEVEL_QUALITY * resolution(levels, 1.0)
                }

                null -> {
                    0.0
                }
            }
        return if (partial) base * PARTIAL_PENALTY else base
    }

    private fun resolution(
        change: Double,
        step: Double,
    ): Double = if (change <= 0) 0.0 else (1 - step / change).coerceIn(0.0, 1.0)

    private fun detail(
        chain: List<WindowEnd>,
        power: CounterWindow<PowerKey>,
    ): WindowRows {
        val perUid =
            power.values.entries
                .filter { it.key.uid >= 0 }
                .groupBy({ it.key.uid }, { it.value })
                .mapValues { it.value.sum() }
        val kept =
            perUid.entries
                .filter { it.value >= MIN_UID_MAH }
                .sortedByDescending { it.value }
                .take(MAX_UIDS)
                .map { it.key }
                .toSet()
        val uids = HashMap<PowerKey, Double>()
        for ((key, mah) in power.values) {
            val target = if (key.uid < 0 || key.uid in kept) key else key.copy(uid = WindowUidEntity.OTHER_UID)
            uids.merge(target, mah, Double::plus)
        }
        val uidRows =
            uids.map { (key, mah) ->
                WindowUidEntity(0, key.uid, key.component, key.processState, WindowUidEntity.WHOLE_UID, mah, Provenance.MODELED)
            }
        return WindowRows(uids = uidRows, wakes = wakes(chain), stats = stats(chain, kept))
    }

    private fun wakes(chain: List<WindowEnd>): List<WindowWakeEntity> {
        val totals = window(chain) { rows -> rows.wakeStats.associate { WakeKey(it.uid, it.tag, it.kind) to it.totalMs.toDouble() } }
        val counts = window(chain) { rows -> rows.wakeStats.associate { WakeKey(it.uid, it.tag, it.kind) to it.count.toDouble() } }
        return totals.values.entries
            .filter { it.value >= MIN_WAKE_MS }
            .groupBy { it.key.kind }
            .flatMap { (_, entries) -> entries.sortedByDescending { it.value }.take(MAX_WAKES_PER_KIND) }
            .map { (key, ms) ->
                WindowWakeEntity(
                    0,
                    0,
                    key.uid,
                    key.tag,
                    key.kind,
                    ms.roundToLong(),
                    (counts.values[key] ?: 0.0).roundToLong().toInt(),
                    screenOffMs = null,
                )
            }
    }

    private fun stats(
        chain: List<WindowEnd>,
        kept: Set<Int>,
    ): List<WindowStatEntity> {
        val window = window(chain, ::statValues)
        val merged = HashMap<StatKey, Double>()
        for ((key, value) in window.values) {
            val uid = if (key.uid < 0 || key.uid in kept) key.uid else WindowUidEntity.OTHER_UID
            merged.merge(key.copy(uid = uid), value, Double::plus)
        }
        return merged.map { (key, value) -> WindowStatEntity(0, key.uid, key.key, value.roundToLong(), Provenance.MEASURED) }
    }

    /** Every counter that is not power or a wakelock, keyed by UID (the device as [UidPowerEntity.DEVICE_UID]). */
    private fun statValues(rows: SnapshotRows): Map<StatKey, Double> {
        val values = HashMap<StatKey, Double>()

        fun put(
            uid: Int,
            key: String,
            value: Number?,
        ) {
            value?.toDouble()?.takeIf { it > 0 }?.let { values[StatKey(uid, key)] = it }
        }
        rows.alarmStats.forEach { put(it.uid, UidKeys.ALARM_WAKEUPS, it.wakeups) }
        rows.jobStats.forEach {
            put(it.uid, UidKeys.JOB_COUNT, it.jobs)
            put(it.uid, UidKeys.JOB_RUNTIME_MS, it.runtimeMs)
        }
        rows.netStats.forEach {
            put(it.uid, UidKeys.MOBILE_RX_BYTES, it.mobileRxB)
            put(it.uid, UidKeys.MOBILE_TX_BYTES, it.mobileTxB)
            put(it.uid, UidKeys.WIFI_RX_BYTES, it.wifiRxB)
            put(it.uid, UidKeys.WIFI_TX_BYTES, it.wifiTxB)
            put(it.uid, UidKeys.RADIO_ACTIVE_MS, it.radioActiveMs)
        }
        rows.uidStats.forEach { put(it.uid, it.key, it.value) }
        rows.deviceStats.forEach { put(UidPowerEntity.DEVICE_UID, it.key, it.value) }
        return values
    }
}
