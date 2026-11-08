// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.common.ZoneSource
import io.github.akane599.coulomb.core.database.dao.ChainStep
import io.github.akane599.coulomb.core.database.dao.ChainTotals
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.WindowDao
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import kotlin.math.abs

/** What the stored rows say about the reference windows (ADR-0032 §3); [DrainReader.inputs] observes them. */
internal class DrainInputs(
    val stored: List<WindowResultEntity>,
    val references: List<ReferenceEntity>,
    val discharge: SessionEntity?,
    val meta: List<UidMetaEntity>,
)

/**
 * Reads a [DrainReport] for a [DrainSpan] (ADR-0036). A span is two heavy snapshots and, where one
 * is stored, the window between them. Its measured total, screen-off shares and, without a stored
 * window, its apps come from the chain of consecutive windows between the two snapshots.
 */
internal class DrainReader
    @Inject
    constructor(
        private val windows: WindowDao,
        private val snapshots: SnapshotDao,
        private val sessions: SessionDao,
        private val devices: DeviceDao,
        private val zone: ZoneSource,
    ) {
        /**
         * What a report is read from. A new consecutive window is the signal that a heavy snapshot
         * landed, so every span ending at the latest one is read again (ADR-0036 §6).
         */
        fun inputs(): Flow<DrainInputs> =
            combine(
                windows.observeRecent(WindowKind.REFERENCE, RECENT_REFERENCES),
                snapshots.observeReferences(),
                sessions.observeRecent(SessionKind.DISCHARGE, 1),
                devices.observeUidMeta(),
                windows.observeRecent(WindowKind.CONSECUTIVE, 1),
            ) { stored, references, discharge, meta, _ -> DrainInputs(stored, references, discharge.firstOrNull(), meta) }

        suspend fun read(
            span: DrainSpan,
            inputs: DrainInputs,
        ): DrainReport? {
            val bounds = bounds(span, inputs) ?: return null
            val (from, to, exact) = bounds
            val chain = Chain(from, to)
            val totals = chain.totals()
            val detail = exact?.takeIf { !it.detailPruned }
            val rows = detail?.let { windows.rows(it.id) }
            val measured = measured(exact, totals, chain, from, to)
            val uids =
                rows?.uids ?: chain.uids().map {
                    WindowUidEntity(
                        windowId = 0,
                        uid = it.uid,
                        component = it.component,
                        processState = UidPowerEntity.ANY_STATE,
                        process = WindowUidEntity.WHOLE_UID,
                        mAh = it.mAh,
                        provenance = Provenance.MODELED,
                    )
                }
            val modeled = exact?.modeledMah ?: totals.modeledMah
            val drains =
                WindowDrainsBuilder.build(
                    fromWallMs = from.wallMs,
                    toWallMs = to.wallMs,
                    measuredMah = measured?.mah,
                    measuredProvenance = measured?.provenance,
                    modeledMah = modeled,
                    rows = uids,
                    meta = inputs.meta,
                    levelEstimate = measured?.estimate,
                )
            val names = inputs.meta.associateBy { it.uid }
            val bases = bases(rows, chain)
            return DrainReport(
                span = span,
                drains = drains,
                startLevel = from.levelPct,
                endLevel = to.levelPct,
                durationMs = exact?.let { it.screenOnMs + it.screenOffMs } ?: (totals.screenOnMs + totals.screenOffMs),
                screenOffMs = exact?.screenOffMs ?: totals.screenOffMs,
                onBatteryMs = bases.onBatteryMs,
                screenOffOnBatteryMs = bases.screenOffOnBatteryMs,
                measuredMs = measured?.ms ?: 0,
                lowerBound = detail == null,
                chargedInSpan = totals.pluggedWindows > 0,
                wakes = wakes(rows, chain, names),
                wakeCoverage = if (detail != null) WakeCoverage.ofWindow(detail) else WakeCoverage.ofChain(chain.steps()),
                alarms = alarms(rows, chain, names),
                components = components(uids),
            )
        }

        private class Bases(
            val onBatteryMs: Long,
            val screenOffOnBatteryMs: Long,
        )

        /**
         * Batterystats' own time on battery and with the screen off on battery: the changes of the bt
         * line's battery realtime (`mOnBatteryTimeBase`) and screen-off realtime
         * (`mOnBatteryScreenOffTimeBase`) that each window stores as device rows (CounterMapper).
         * They come from where the apps and wake records come from, the stored window's detail or its
         * chain, so a window that met a charger, or one with no plug state at its start, counts
         * only its time on battery, and a window without S5's device section counts none (ADR-0036
         * §35). BatteryStatsImpl 16 L13946-13958, 17 L13543-13555; the bt line: BatteryStats 16
         * L4288, 17 L4309-4318.
         */
        private suspend fun bases(
            rows: WindowRows?,
            chain: Chain,
        ): Bases {
            suspend fun device(key: String): Long =
                rows?.stats?.filter { it.uid == UidPowerEntity.DEVICE_UID && it.key == key }?.sumOf { it.value }
                    ?: chain.stat(key).filter { it.uid == UidPowerEntity.DEVICE_UID }.sumOf { it.value }
            return Bases(device(DeviceKeys.BATTERY_REALTIME_MS), device(DeviceKeys.SCREEN_OFF_REALTIME_MS))
        }

        /** The device rows, which hold each component's total with the apps' share in it (CounterMapper). */
        private fun components(rows: List<WindowUidEntity>): List<ComponentDrain> =
            rows
                .filter { it.uid == UidPowerEntity.DEVICE_UID }
                .groupBy { PowerComponent.ofStored(it.component) }
                .map { (component, parts) -> ComponentDrain(component, parts.sumOf { it.mAh }) }
                .filter { it.mah > 0 }
                .sortedByDescending { it.mah }

        private data class Bounds(
            val from: SnapshotEntity,
            val to: SnapshotEntity,
            val exact: WindowResultEntity?,
        )

        private suspend fun bounds(
            span: DrainSpan,
            inputs: DrainInputs,
        ): Bounds? =
            when (span) {
                DrainSpan.SinceUnplugged -> {
                    sinceUnplugged(inputs)?.let { of(it) }
                }

                DrainSpan.Today -> {
                    today(inputs)
                }

                DrainSpan.Last24Hours -> {
                    snapshots.latestHeavy()?.let { to -> from(to.wallMs - DAY_MS, to) }
                }

                DrainSpan.LastNight -> {
                    lastNight()
                }

                is DrainSpan.Days -> {
                    val z = zone.zone()
                    val start =
                        span.first
                            .atStartOfDay(z)
                            .toInstant()
                            .toEpochMilli()
                    val end =
                        span.last
                            .plusDays(1)
                            .atStartOfDay(z)
                            .toInstant()
                            .toEpochMilli()
                    val to = snapshots.firstHeavyFrom(end) ?: snapshots.latestHeavy()
                    to?.let { from(start, it) }
                }
            }

        /** While unplugged, the reference window from the unplug; plugged in, the last discharge's window. */
        private suspend fun sinceUnplugged(inputs: DrainInputs): WindowResultEntity? {
            val session = inputs.discharge ?: return null
            if (session.closeReason != null) return windows.forSession(session.id)
            val start = inputs.references.firstOrNull { it.autoKind == AutoReference.SINCE_UNPLUGGED }?.snapshotId ?: return null
            return inputs.stored.firstOrNull { it.fromSnapshotId == start }
        }

        /** The reference window from today's first heavy snapshot; before one is stored, the chain up to the latest. */
        private suspend fun today(inputs: DrainInputs): Bounds? {
            val start = inputs.references.firstOrNull { it.autoKind == AutoReference.TODAY }?.snapshotId ?: return null
            val stored = inputs.stored.firstOrNull { it.fromSnapshotId == start }
            if (stored != null) return of(stored)
            val from = snapshots.snapshot(start)
            val to = snapshots.latestHeavy()
            return if (from != null && to != null && before(from, to)) Bounds(from, to, null) else null
        }

        private suspend fun lastNight(): Bounds? {
            val latest = snapshots.latestHeavy() ?: return null
            val night = sessions.longestClosed(SessionKind.SCREEN_OFF, latest.wallMs - DAY_MS, NIGHT_MIN_NS) ?: return null
            val from = night.startSnapshotId?.let { snapshots.snapshot(it) } ?: return null
            val to = night.endSnapshotId?.let { snapshots.snapshot(it) } ?: return null
            return Bounds(from, to, windows.forSession(night.id)).takeIf { before(from, to) }
        }

        private suspend fun of(window: WindowResultEntity): Bounds? {
            val from = snapshots.snapshot(window.fromSnapshotId) ?: return null
            val to = snapshots.snapshot(window.toSnapshotId) ?: return null
            return Bounds(from, to, window)
        }

        /** From the first heavy snapshot at or after [startWall] to [to]; null when none comes before [to]. */
        private suspend fun from(
            startWall: Long,
            to: SnapshotEntity,
        ): Bounds? {
            val from = snapshots.firstHeavyFrom(startWall) ?: return null
            return Bounds(from, to, null).takeIf { before(from, to) }
        }

        private class Measured(
            val mah: Double,
            val provenance: Provenance,
            /** The time of the windows [mah] sums. */
            val ms: Long,
            val estimate: LevelEstimate? = null,
        )

        /**
         * The stored window's own drain when every window in its chain started on battery; otherwise the
         * drain of the chain's discharge windows alone, since a charge would cancel drain out. Measured
         * when every summed window was read by the charge counter, else Estimated (ADR-0018), with its
         * range (ADR-0036 §17).
         *
         * A stored window read from the level, as one across a reboot is, gives way to its chain when
         * the chain covers it and measured every window: then only the windows the counter could not
         * read are level-based, and the range is a step for each stretch of them, not for the span.
         */
        private suspend fun measured(
            exact: WindowResultEntity?,
            totals: ChainTotals,
            chain: Chain,
            from: SnapshotEntity,
            to: SnapshotEntity,
        ): Measured? {
            val onBatteryOnly = totals.pluggedWindows == 0 && totals.unknownWindows == 0
            val stored = exact?.takeIf { totals.windows == 0 || onBatteryOnly } ?: return chainMeasured(totals, chain)
            val mah = stored.measuredMah ?: return null
            val ms = stored.screenOnMs + stored.screenOffMs
            return when {
                stored.chargeSource == ChargeSource.CHARGE_COUNTER -> Measured(mah, Provenance.MEASURED, ms)
                coveredByChain(stored, totals) -> chainMeasured(totals, chain)
                else -> Measured(mah, Provenance.ESTIMATED, ms, LevelEstimate.ofWindow(mah, from.levelPct, to.levelPct))
            }
        }

        /** The chain's discharge windows summed: Measured when the counter read each one it could. */
        private suspend fun chainMeasured(
            totals: ChainTotals,
            chain: Chain,
        ): Measured? {
            val mah = totals.dischargeMah ?: return null
            val measuredWindows = totals.dischargeWindows - totals.unmeasuredWindows
            return if (totals.counterWindows == measuredWindows) {
                Measured(mah, Provenance.MEASURED, totals.measuredMs)
            } else {
                Measured(mah, Provenance.ESTIMATED, totals.measuredMs, LevelEstimate.ofChain(chain.steps()))
            }
        }

        /** The chain spans [stored] within a minute and measured every window, so it can stand in. */
        private fun coveredByChain(
            stored: WindowResultEntity,
            totals: ChainTotals,
        ): Boolean =
            totals.windows > 0 &&
                totals.unmeasuredWindows == 0 &&
                abs(totals.screenOnMs + totals.screenOffMs - stored.screenOnMs - stored.screenOffMs) <= COVER_SLACK_MS

        private suspend fun wakes(
            rows: WindowRows?,
            chain: Chain,
            names: Map<Int, UidMetaEntity>,
        ): List<WakeEntry> {
            val summed = chain.wakes()
            val split = summed.associateBy { WakeKey(it.kind, it.uid, it.tag) }
            val base =
                rows?.wakes?.map { WakeTotal(WakeKey(it.kind, it.uid, it.tag), it.totalMs, it.count.toLong(), it.wakeups) }
                    ?: summed.map { WakeTotal(WakeKey(it.kind, it.uid, it.tag), it.totalMs, it.count, it.wakeups) }
            return base
                .map { total ->
                    val name = total.key.uid?.let(names::get)
                    val known = split[total.key]?.let { it.screenOffMs + it.screenOnMs } ?: 0
                    WakeEntry(
                        kind = total.key.kind,
                        uid = total.key.uid,
                        label = name?.label,
                        iconPackage = name?.iconCacheKey?.takeIf { it.isNotEmpty() },
                        tag = total.key.tag,
                        totalMs = total.totalMs,
                        count = total.count,
                        // Counted only with the screen off, a record's share is whole by construction.
                        // A root record also counts on the charger, where batterystats keeps no screen
                        // time, so a window there would read as screen-off: only on-battery kinds keep one.
                        screenOffShare =
                            split[total.key]
                                ?.takeIf { known > 0 && total.key.kind.basis == WakeBasis.ON_BATTERY }
                                ?.let { it.screenOffMs.toDouble() / known },
                        wakeups = total.wakeups,
                    )
                }.sortedByDescending { it.totalMs }
        }

        private suspend fun alarms(
            rows: WindowRows?,
            chain: Chain,
            names: Map<Int, UidMetaEntity>,
        ): List<AlarmEntry> {
            val perUid =
                rows?.stats?.filter { it.key == UidKeys.ALARM_WAKEUPS }?.map { it.uid to it.value }
                    ?: chain.stat(UidKeys.ALARM_WAKEUPS).map { it.uid to it.value }
            // The UIDs a window keeps no rows for share one, small apps (ADR-0015), with their alarms.
            return perUid
                .filter { (uid, wakeups) -> (uid >= 0 || uid == WindowUidEntity.OTHER_UID) && wakeups > 0 }
                .map { (uid, wakeups) ->
                    val name = names[uid]
                    AlarmEntry(uid, name?.label, name?.iconCacheKey?.takeIf { it.isNotEmpty() }, wakeups)
                }.sortedByDescending { it.wakeups }
        }

        private data class WakeKey(
            val kind: WakeKind,
            val uid: Int?,
            val tag: String,
        )

        private class WakeTotal(
            val key: WakeKey,
            val totalMs: Long,
            val count: Long,
            val wakeups: Long?,
        )

        /** The consecutive windows from [from] to [to]. */
        private inner class Chain(
            private val from: SnapshotEntity,
            private val to: SnapshotEntity,
        ) {
            suspend fun totals() = windows.chainTotals(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)

            private var steps: List<ChainStep>? = null

            /** Read once: the range and the wake coverage both need them. */
            suspend fun steps(): List<ChainStep> =
                steps ?: windows.chainSteps(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs).also { steps = it }

            suspend fun uids() = windows.chainUids(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)

            suspend fun wakes() = windows.chainWakes(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)

            suspend fun stat(key: String) = windows.chainStat(key, from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
        }

        internal companion object {
            const val DAY_MS: Long = 24 * 3_600_000L

            /** One per reference is kept (ADR-0032 §3); a little slack for a write in progress. */
            const val RECENT_REFERENCES = 4

            /** SPEC 10.7 lists screen-off sessions of an hour or more. */
            const val NIGHT_MIN_NS: Long = 3_600_000_000_000L

            /** A chain covers a stored window when their times differ by at most a minute. */
            const val COVER_SLACK_MS: Long = 60_000L

            /** [a] comes strictly before [b] in boot and elapsed order (SPEC 7.8). */
            fun before(
                a: SnapshotEntity,
                b: SnapshotEntity,
            ): Boolean = a.bootSeq < b.bootSeq || (a.bootSeq == b.bootSeq && a.elapsedNs < b.elapsedNs)
        }
    }
