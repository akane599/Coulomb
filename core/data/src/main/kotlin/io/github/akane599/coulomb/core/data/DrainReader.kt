// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.common.ZoneSource
import io.github.akane599.coulomb.core.database.dao.ChainComponent
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
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.stats.ComponentPower
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.PowerColumn
import io.github.akane599.coulomb.core.model.stats.PowerComponent
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.abs

/**
 * What the stored rows say about the reference windows (ADR-0032 §3); [DrainReader.inputs] observes
 * them. Equal inputs read the same reports, so the flow drops repeats.
 */
internal data class DrainInputs(
    val stored: List<WindowResultEntity>,
    val references: List<ReferenceEntity>,
    val discharge: SessionEntity?,
    val meta: List<UidMetaEntity>,
    /** The newest consecutive window: a new one means a heavy snapshot landed. */
    val latestWindowId: Long? = null,
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
         * landed, so every span ending at the latest one is read again (ADR-0036 §6). Room re-runs
         * the snapshot joins on every write to `snapshot`, light ones included; those leave the
         * inputs equal, so they are dropped here rather than read again (optimize gate, 2026-11-09).
         */
        fun inputs(): Flow<DrainInputs> =
            combine(
                windows.observeRecent(WindowKind.REFERENCE, RECENT_REFERENCES),
                snapshots.observeReferences(),
                sessions.observeRecent(SessionKind.DISCHARGE, 1),
                devices.observeUidMeta(),
                windows.observeRecent(WindowKind.CONSECUTIVE, 1),
            ) { stored, references, discharge, meta, latest ->
                DrainInputs(stored, references, discharge.firstOrNull(), meta, latest.firstOrNull()?.id)
            }.distinctUntilChanged()

        suspend fun read(
            span: DrainSpan,
            inputs: DrainInputs,
        ): DrainReport? = bounds(span, inputs)?.let { report(span, it, inputs) }

        /**
         * [uid] over [span] (ADR-0041): Drain's report for the span, the app's parts of it, its
         * counters over the same windows, and its last 14 local days up to [nowWallMs].
         */
        suspend fun readApp(
            span: DrainSpan,
            uid: Int,
            inputs: DrainInputs,
            nowWallMs: Long,
        ): AppDetailReport? {
            val bounds = bounds(span, inputs) ?: return null
            val report = report(span, bounds, inputs)
            val app = report.drains.apps.firstOrNull { it.uid == uid }
            val total = app?.mah ?: 0.0
            val meta = inputs.meta.firstOrNull { it.uid == uid }
            return AppDetailReport(
                uid = uid,
                label = app?.label ?: meta?.label?.takeIf { it.isNotBlank() },
                iconPackage = app?.iconPackage ?: meta?.iconCacheKey?.takeIf { it.isNotEmpty() },
                report = report,
                app = app,
                components = report.breakdown?.of(uid, total),
                processStates = ProcessStates.of(report.breakdown?.apps?.get(uid), total),
                wakes = report.wakes.filter { it.uid == uid },
                // The collector stores no zero counters, so for an app with a row, where S5 was read at
                // both ends of every window, a missing counter is 0, not unrecorded (ADR-0041 amendment).
                counters =
                    counters(bounds, uid).let {
                        if (app != null &&
                            report.wakeCoverage.batteryStats == Coverage.WHOLE
                        ) {
                            it.zeroFilled()
                        } else {
                            it
                        }
                    },
                trend = trend(uid, nowWallMs),
            )
        }

        /** [uid]'s counters over the span: the stored window's records, or the chain's (ADR-0041 §1). */
        private suspend fun counters(
            bounds: Bounds,
            uid: Int,
        ): AppCounters {
            val detail = bounds.exact?.takeIf { !it.detailPruned }
            val stats = detail?.let { windows.rows(it.id).stats.filter { stat -> stat.uid == uid } }
            val chain = Chain(bounds.from, bounds.to)

            suspend fun value(key: String): Long? =
                if (stats != null) {
                    stats.filter { it.key == key }.takeIf { it.isNotEmpty() }?.sumOf { it.value }
                } else {
                    chain.stat(key).firstOrNull { it.uid == uid }?.value
                }
            return AppCounters(
                alarmWakeups = value(UidKeys.ALARM_WAKEUPS),
                jobCount = value(UidKeys.JOB_COUNT),
                jobRuntimeMs = value(UidKeys.JOB_RUNTIME_MS),
                mobileRxBytes = value(UidKeys.MOBILE_RX_BYTES),
                mobileTxBytes = value(UidKeys.MOBILE_TX_BYTES),
                wifiRxBytes = value(UidKeys.WIFI_RX_BYTES),
                wifiTxBytes = value(UidKeys.WIFI_TX_BYTES),
                foregroundActivityMs = value(UidKeys.FOREGROUND_ACTIVITY_MS),
                foregroundServiceMs = value(UidKeys.FOREGROUND_SERVICE_MS),
            )
        }

        /**
         * [uid]'s mAh on each of the last [TREND_DAYS] local days (ADR-0041 §4): its rows in the
         * consecutive windows, the whole-UID row where a window has one, each window shared among
         * the days it overlaps by wall time ([dayShares]). A screen-off window lasts the whole
         * night, so the evening's part stays on its own day. A day no window overlaps has none.
         */
        private suspend fun trend(
            uid: Int,
            nowWallMs: Long,
        ): List<DayUse> {
            val z = zone.zone()
            val today = Instant.ofEpochMilli(nowWallMs).atZone(z).toLocalDate()
            val first = today.minusDays(TREND_DAYS - 1)
            val fromMs = first.atStartOfDay(z).toInstant().toEpochMilli()
            val toMs =
                today
                    .plusDays(1)
                    .atStartOfDay(z)
                    .toInstant()
                    .toEpochMilli()
            val withData = HashSet<LocalDate>()
            windows.consecutiveSpans(fromMs, toMs).forEach { withData += dayShares(it.fromWallMs, it.toWallMs, z, first, today).keys }
            val perDay = HashMap<LocalDate, Double>()
            windows.consecutiveUse(uid, fromMs, toMs).groupBy { it.windowId }.values.forEach { rows ->
                val whole = rows.filter { it.component == WindowDrainsBuilder.ALL_COMPONENTS }
                val mah = whole.ifEmpty { rows }.sumOf { it.mAh }
                val window = rows.first()
                dayShares(window.fromWallMs, window.toWallMs, z, first, today).forEach { (day, share) ->
                    perDay.merge(day, mah * share, Double::plus)
                }
            }
            return (0 until TREND_DAYS).map { offset ->
                val day = first.plusDays(offset)
                DayUse(day, if (day in withData) perDay[day] ?: 0.0 else null)
            }
        }

        private suspend fun report(
            span: DrainSpan,
            bounds: Bounds,
            inputs: DrainInputs,
        ): DrainReport {
            val (from, to, exact) = bounds
            val chain = Chain(from, to)
            val totals = chain.totals()
            val detail = exact?.takeIf { !it.detailPruned }
            val rows = detail?.let { windows.rows(it.id) }
            val measured = measured(exact, totals, chain, from, to)
            val uids =
                rows?.uids ?: chain.uids().map {
                    WindowUid(
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
            val s4 = S4.of(chain.components(), totals, modeled)
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
                components = s4.device?.let { deviceComponents(it, s4.apps, names) } ?: components(uids),
                componentsNotBrokenDownMah = s4.notBrokenDownMah,
                breakdown = s4.apps.takeIf { it.isNotEmpty() }?.let(::SpanBreakdown),
                breakdownOff = totals.offWindows > 0,
            )
        }

        /**
         * What S4 gives a span (ADR-0035 §7): the covered windows' device row, used for the
         * Components lens only when those windows hold at least half of the span's modeled total,
         * and each app's row over them.
         */
        private class S4(
            val device: ComponentPower?,
            val notBrokenDownMah: Double?,
            val apps: Map<Int, ComponentPower>,
        ) {
            companion object {
                fun of(
                    rows: List<ChainComponent>,
                    totals: ChainTotals,
                    spanModeledMah: Double?,
                ): S4 {
                    val power = rows.associate { it.uid to it.power.toPower() }
                    val covered = totals.coveredModeledMah
                    val device =
                        power[UidPowerEntity.DEVICE_UID]?.takeIf {
                            covered != null && spanModeledMah != null && spanModeledMah > 0 && covered >= spanModeledMah * MIN_COVERED
                        }
                    val rest =
                        if (device != null && spanModeledMah != null &&
                            covered != null
                        ) {
                            (spanModeledMah - covered).coerceAtLeast(0.0)
                        } else {
                            null
                        }
                    return S4(device, rest, power - UidPowerEntity.DEVICE_UID)
                }
            }
        }

        /**
         * S4's device row by component, custom left out: S5's modeled total covers ids 0-18 only, and
         * S5's app totals carry custom energy, so it shows in app chips instead. Each lists the apps
         * that used the most of it.
         */
        private fun deviceComponents(
            device: ComponentPower,
            apps: Map<Int, ComponentPower>,
            names: Map<Int, UidMetaEntity>,
        ): List<ComponentDrain> =
            PowerColumn.components
                .filter { it != PowerColumn.CUSTOM && device[it] > 0 }
                .map { column ->
                    val top =
                        apps.entries
                            .filter { (uid, power) -> uid >= 0 && power[column] > 0 }
                            .sortedByDescending { it.value[column] }
                            .take(TOP_APPS)
                            .map { (uid, power) -> ComponentApp(uid, names[uid]?.label, power.mah(column)) }
                    ComponentDrain(PowerComponent.of(column), device.mah(column), top)
                }.sortedByDescending { it.mah }

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
        private fun components(rows: List<WindowUid>): List<ComponentDrain> =
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

            suspend fun components() = windows.chainComponents(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)

            suspend fun wakes() = windows.chainWakes(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)

            suspend fun stat(key: String) = windows.chainStat(key, from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
        }

        internal companion object {
            const val DAY_MS: Long = 24 * 3_600_000L

            /** One per reference is kept (ADR-0032 §3); a little slack for a write in progress. */
            const val RECENT_REFERENCES = 4

            /** SPEC 10.7 lists screen-off sessions of an hour or more. */
            const val NIGHT_MIN_NS: Long = 3_600_000_000_000L

            /** ADR-0035 §7: below this share of the span's modeled total, the lens keeps S5's device rows. */
            const val MIN_COVERED = 0.5

            /** SPEC 10.4: App detail's trend covers 14 days. */
            const val TREND_DAYS = 14L

            /** SPEC 10.3: each component lists its top apps. */
            const val TOP_APPS = 3

            /** A chain covers a stored window when their times differ by at most a minute. */
            const val COVER_SLACK_MS: Long = 60_000L

            /**
             * The share of the window `[fromWallMs, toWallMs)` in each local day from [first] to
             * [last], by wall time in [zone] (ADR-0041 §4). Days outside them get nothing. A window
             * whose wall clock did not move forward, as after a time change, counts whole for the
             * day it ends.
             */
            fun dayShares(
                fromWallMs: Long,
                toWallMs: Long,
                zone: ZoneId,
                first: LocalDate,
                last: LocalDate,
            ): Map<LocalDate, Double> {
                fun day(wallMs: Long) = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()
                if (toWallMs <= fromWallMs) {
                    val end = day(toWallMs)
                    return if (end in first..last) mapOf(end to 1.0) else emptyMap()
                }
                val length = (toWallMs - fromWallMs).toDouble()
                val shares = LinkedHashMap<LocalDate, Double>()
                var day = maxOf(day(fromWallMs), first)
                val end = minOf(day(toWallMs - 1), last)
                while (day <= end) {
                    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val next =
                        day
                            .plusDays(1)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli()
                    val overlap = minOf(toWallMs, next) - maxOf(fromWallMs, start)
                    if (overlap > 0) shares[day] = overlap / length
                    day = day.plusDays(1)
                }
                return shares
            }

            /** [a] comes strictly before [b] in boot and elapsed order (SPEC 7.8). */
            fun before(
                a: SnapshotEntity,
                b: SnapshotEntity,
            ): Boolean = a.bootSeq < b.bootSeq || (a.bootSeq == b.bootSeq && a.elapsedNs < b.elapsedNs)
        }
    }
