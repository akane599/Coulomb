// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.WindowDao
import io.github.akane599.coulomb.core.database.dao.covers
import io.github.akane599.coulomb.core.database.dao.firstHeavyWithin
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.PowerComponent
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/** What the loader needs from outside the database. */
public interface InsightPorts {
    /** Coulomb's own UID (SPEC 6.9). */
    public val ownUid: Int

    /** The battery's capacity in mAh, estimated or by design; null while neither is known. */
    public suspend fun capacityMah(): Double?

    /** When the app with [uid] was last updated, by the wall clock; null when not known. */
    public fun lastUpdateWallMs(uid: Int): Long?

    /** Each app's standby bucket and last opening (R16); empty where the phone gives neither. */
    public suspend fun standby(): List<AppStandby> = emptyList()
}

/**
 * Fills a [RuleContext] from stored data (SPEC 9: rules run over stored windows). It reads and
 * never writes. Each part states the stored figure it stands on; a part the phone has no data for
 * stays empty, and the rules reading it find nothing (ADR-0054 §4).
 */
public class RuleContextLoader(
    private val snapshots: SnapshotDao,
    private val windows: WindowDao,
    private val sessions: SessionDao,
    private val samples: SampleDao,
    private val ports: InsightPorts,
) {
    /** Each closed charge's temperature samples, by session; the runner keeps this loader for the process. */
    private val temperatures = ConcurrentHashMap<Long, List<TempSample>>()

    /** Each closed screen-off session's deep Doze answer, once known. */
    private val deepIdle = ConcurrentHashMap<Long, Boolean>()

    /**
     * The context as of [nowWallMs]. Screen-off sessions shorter than [minScreenOffMs] are left
     * out: a phone has hundreds of them a week, and no rule judges one shorter than its own floor.
     */
    public suspend fun load(
        nowWallMs: Long,
        minScreenOffMs: Long,
    ): RuleContext {
        val capacity = ports.capacityMah()?.takeIf { it > 0 }
        val discharges = sessions.overlapping(listOf(SessionKind.DISCHARGE), nowWallMs - SESSION_DAYS * DAY_MS)
        val recentDischarges = discharges.filter { it.closeReason != null && (it.endWall ?: 0) >= nowWallMs - DAY_MS }
        return RuleContext(
            nowWallMs = nowWallMs,
            day = day(capacity),
            tagHolds = recentDischarges.flatMap { tagHolds(it) },
            screenOffSessions = screenOffSessions(nowWallMs, minScreenOffMs, discharges, capacity),
            dischargeWindows = recentDischarges.mapNotNull { dischargeWindow(it) },
            chargeSessions = chargeSessions(nowWallMs),
            temperatures = listOfNotNull(hottest(nowWallMs)),
            health =
                sessions.observeHealthSummaries().first().mapNotNull { day ->
                    day.healthPct?.let { HealthPoint(day.epochDay * DAY_MS, it) }
                },
            trends = trends(nowWallMs),
            standby = ports.standby(),
        )
    }

    /** The day's hottest reading, with the app that used the most charge in the window around it (SPEC R12; audit BUG-061). */
    private suspend fun hottest(nowWallMs: Long): TempSample? {
        val sample = samples.hottest(nowWallMs - DAY_MS, nowWallMs + 1) ?: return null
        return TempSample(sample.wallMs, sample.tempDeciC / DECI, topUid = topAround(sample.bootSeq, sample.elapsedNs))
    }

    /** The app with the most modeled charge in the consecutive window that holds the moment; null without one. */
    private suspend fun topAround(
        bootSeq: Int,
        elapsedNs: Long,
    ): Int? {
        val window = windows.consecutiveAround(bootSeq, elapsedNs) ?: return null
        val from = snapshots.snapshot(window.fromSnapshotId)
        val to = snapshots.snapshot(window.toSnapshotId)
        if (from == null || to == null) return null
        return windows
            .chainUids(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
            .filter { isApp(it.uid) && it.component == ALL_COMPONENTS && it.mAh > 0 }
            .maxByOrNull { it.mAh }
            ?.uid
    }

    /** A chain of consecutive windows: those ending after [from] and no later than [to]. */
    private class Chain(
        val from: SnapshotEntity,
        val to: SnapshotEntity,
    ) {
        val span = Span(from.wallMs, to.wallMs)
    }

    private suspend fun stat(
        key: String,
        chain: Chain,
        screenOff: Boolean = false,
    ): Map<Int, Long> {
        val (from, to) = chain.from to chain.to
        val rows =
            if (screenOff) {
                windows.chainStatScreenOff(key, from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
            } else {
                windows.chainStat(key, from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
            }
        return rows.associate { it.uid to it.value }
    }

    /**
     * The day up to the latest heavy snapshot. Per-app counters are the windows' `window_stat` rows;
     * an app outside a window's kept apps is in the "other" row and is not seen here (ADR-0015).
     */
    private suspend fun day(capacity: Double?): DayUse? {
        val to = snapshots.latestHeavy() ?: return null
        val from = snapshots.firstHeavyWithin(DAY_MS, to)?.takeIf { before(it, to) } ?: return null
        val chain = Chain(from, to)
        val uids = windows.chainUids(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
        val modeled = windows.chainTotals(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs).modeledMah
        val modem =
            uids
                .filter {
                    it.uid == DEVICE && PowerComponent.ofStored(
                        it.component,
                    ) == PowerComponent.MOBILE_RADIO
                }.sumOf { it.mAh }
        val own = uids.filter { it.uid == ports.ownUid && it.component == ALL_COMPONENTS }.sumOf { it.mAh }
        val bins = SIGNAL_BINS.map { device(DeviceKeys.signalBin(it), chain) }
        return DayUse(
            span = chain.span,
            screenOffMs = device(DeviceKeys.SCREEN_OFF_REALTIME_MS, chain),
            apps = apps(chain),
            // Per-app scan timers overlap, so their sum overstates the radio's time. The busiest
            // single app is the most that is certain (ADR-0054 §7).
            wifiScanMs = busiestApp(UidKeys.WIFI_SCAN_MS, chain),
            bleScanMs = busiestApp(UidKeys.BLE_SCAN_MS, chain),
            signal = SignalUse(bins, modeled?.takeIf { it > 0 }?.let { modem / it }),
            // Without a modeled window there is no model's share to read, not a share of zero (audit DATA-014).
            self = capacity?.takeIf { modeled != null }?.let { SelfCost(own, it) },
        )
    }

    private suspend fun device(
        key: String,
        chain: Chain,
    ): Long = stat(key, chain)[DEVICE] ?: 0

    private suspend fun busiestApp(
        key: String,
        chain: Chain,
    ): Long = stat(key, chain).filterKeys { it >= 0 }.values.maxOrNull() ?: 0

    private suspend fun apps(chain: Chain): List<AppDayUse> {
        val wakelock = stat(UidKeys.PARTIAL_WAKELOCK_MS, chain)
        val alarms = stat(UidKeys.ALARM_WAKEUPS, chain)
        val jobs = stat(UidKeys.JOB_COUNT, chain)
        val abandoned = stat(UidKeys.JOB_ABANDONED, chain)
        val gnss = stat(UidKeys.GNSS_MS, chain, screenOff = true)
        val sensor = stat(UidKeys.SENSOR_MS, chain, screenOff = true)
        // Only installed apps: the user can act on those, not on the system's own UIDs (ADR-0054 §12).
        val uids =
            listOf(wakelock, alarms, jobs, gnss, sensor)
                .flatMap { it.keys }
                .filter(::isApp)
                .distinct()
                .sorted()
        return uids.map { uid ->
            AppDayUse(
                uid = uid,
                partialWakelockMs = wakelock[uid] ?: 0,
                alarmWakeups = alarms[uid] ?: 0,
                gnssScreenOffMs = gnss[uid] ?: 0,
                sensorScreenOffMs = sensor[uid] ?: 0,
                jobCount = jobs[uid] ?: 0,
                // A window keeps only counts above zero, and one stored before stop reasons were kept has none.
                abandonedJobs = abandoned[uid],
            )
        }
    }

    /** A closed discharge's partial wakelocks, from its session window; these count screen-off time on battery. */
    private suspend fun tagHolds(session: SessionEntity): List<TagHold> {
        val window = windows.forSession(session.id) ?: return emptyList()
        val span = Span(session.startWall, session.endWall ?: return emptyList())
        return windows
            .rows(
                window.id,
            ).wakes
            .filter { it.kind == WakeKind.PARTIAL }
            .map { TagHold(it.tag, it.uid, it.totalMs, span, window.id) }
    }

    private suspend fun dischargeWindow(session: SessionEntity): DischargeWindow? {
        val window = windows.forSession(session.id) ?: return null
        val measured = window.measuredMah?.takeIf { window.chargeSource == ChargeSource.CHARGE_COUNTER } ?: return null
        val unattributed = window.unattributedMah ?: return null
        val end = session.endWall ?: return null
        return DischargeWindow(window.id, Span(session.startWall, end), window.screenOnMs + window.screenOffMs, measured, unattributed)
    }

    /**
     * Closed screen-off sessions that lay wholly inside one discharge: on the charger the phone does
     * not sleep, so such a session says nothing about sleep or drain. The rate is the charge the
     * session's window measured over [capacity]; without a counter reading it is the level change.
     */
    private suspend fun screenOffSessions(
        nowWallMs: Long,
        minMs: Long,
        discharges: List<SessionEntity>,
        capacity: Double?,
    ): List<ScreenOffSession> {
        val closed = sessions.closedSince(SessionKind.SCREEN_OFF, nowWallMs - SESSION_DAYS * DAY_MS)
        val onBattery =
            closed.filter { s ->
                val end = s.endElapsedNs?.takeIf { (it - s.startElapsedNs) / NS_PER_MS >= minMs } ?: return@filter false
                discharges.any { d ->
                    d.bootSeq == s.bootSeq && d.startElapsedNs <= s.startElapsedNs &&
                        (d.endElapsedNs ?: Long.MAX_VALUE) >= end
                }
            }
        val judged = onBattery.takeLast(DEEP_IDLE_SESSIONS).mapTo(HashSet()) { it.id }
        deepIdle.keys.retainAll(judged)
        // One query for every session's window, not one each (audit PERF-001).
        val sessionWindows =
            onBattery
                .map { it.id }
                .chunked(MAX_IDS)
                .flatMap { windows.forSessions(it) }
                .associateBy { it.sessionId }
        return onBattery.map { s ->
            val ms = ((s.endElapsedNs ?: s.startElapsedNs) - s.startElapsedNs) / NS_PER_MS
            val window = sessionWindows[s.id]
            val drop = s.endLevel?.let { s.startLevel - it }
            val counted = window?.measuredMah?.takeIf { window.chargeSource == ChargeSource.CHARGE_COUNTER && capacity != null }
            val pct = counted?.let { it / capacity!! * PERCENT } ?: drop?.toDouble()
            ScreenOffSession(
                id = s.id,
                span = Span(s.startWall, s.endWall ?: s.startWall),
                durationMs = ms,
                awakeRatio = s.awakeRatio,
                ratePctPerHour = pct?.takeIf { ms > 0 }?.let { it / (ms / MS_PER_HOUR) },
                levelDropPct = drop,
                deepIdleReached = s.deepIdleReached ?: if (s.id in judged) deepIdleReached(s) else null,
                windowId = window?.id,
            )
        }
    }

    /**
     * Whether deep Doze began inside [session], from the device's `deep_doze_count` over the
     * session's chain of windows. Null without both end snapshots; "no" only when the windows join
     * end to end over the whole session and each read the device's counters, since a stretch
     * without them may hold the entry (ADR-0054 §4; audit BUG-058).
     */
    private suspend fun deepIdleReached(session: SessionEntity): Boolean? =
        // A closed session's answer, once there is one, does not change (audit PERF-001).
        deepIdle[session.id] ?: readDeepIdle(session)?.also { deepIdle[session.id] = it }

    private suspend fun readDeepIdle(session: SessionEntity): Boolean? {
        val from = session.startSnapshotId?.let { snapshots.snapshot(it) } ?: return null
        val to = session.endSnapshotId?.let { snapshots.snapshot(it) } ?: return null
        if (!before(from, to)) return null
        val chain = Chain(from, to)
        if (device(DeviceKeys.DEEP_DOZE_COUNT, chain) > 0) return true
        val links = windows.chainLinks(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
        return if (links.covers(from.id, to.id)) false else null
    }

    /**
     * Charges closed in the last day. The baseline is the session's charger's, except when this
     * session created that profile: a charge far below every known charger never matches one
     * (ADR-0043), so it is compared with the charger of the same plug type seen last before it.
     */
    private suspend fun chargeSessions(nowWallMs: Long): List<ChargeSession> {
        val closed = sessions.closedSince(SessionKind.CHARGE, nowWallMs - DAY_MS)
        // Only the day's charges stay kept.
        temperatures.keys.retainAll(closed.mapTo(HashSet()) { it.id })
        if (closed.isEmpty()) return emptyList()
        val details = sessions.chargeDetails(closed.map { it.id }).associateBy { it.sessionId }
        val profiles = sessions.chargerProfiles()
        return closed.map { s ->
            val detail = details[s.id]
            val own = profiles.firstOrNull { it.id == detail?.chargerProfileId }
            val usual = own?.let { usualCharger(it, s, profiles) }
            // A closed charge's samples do not change: read once, not at every heavy snapshot (audit PERF-001).
            val readings =
                temperatures.getOrPut(s.id) {
                    samples.samples(s.bootSeq, s.startElapsedNs, s.endElapsedNs ?: s.startElapsedNs).map {
                        TempSample(
                            s.startWall + (it.elapsedNs - s.startElapsedNs) / NS_PER_MS,
                            it.tempDeciC / DECI,
                            charging = it.plugType != 0,
                        )
                    }
                }
            ChargeSession(
                id = s.id,
                span = Span(s.startWall, s.endWall ?: s.startWall),
                samples = readings,
                avgW = detail?.avgW,
                baselineW = usual?.baselineW,
                chargerId = own?.id,
                baselineName = usual?.name?.takeIf { it.isNotEmpty() },
                minutesAtFull = s.minutesAt100 ?: 0,
                protectionOn = null,
            )
        }
    }

    private fun usualCharger(
        own: ChargerProfileEntity,
        session: SessionEntity,
        profiles: List<ChargerProfileEntity>,
    ): ChargerProfileEntity? =
        if (own.firstSeenWall < session.startWall) {
            own
        } else {
            profiles
                .filter {
                    it.id != own.id && it.plugType == own.plugType && it.lastSeenWall < session.startWall
                }.maxByOrNull { it.lastSeenWall }
        }

    /**
     * Each app's charge per local day, from the day windows, for the apps updated in the last days.
     * A day without a row for the app is left out, not counted as zero: the app may have fallen
     * under the window's cut.
     */
    private suspend fun trends(nowWallMs: Long): List<AppTrend> {
        // The newest days and their UID rows only, not every day window kept with all its records (audit PERF-001).
        val days = windows.latestOfKind(WindowKind.DAY, TREND_DAYS)
        val uids = if (days.isEmpty()) emptyMap() else windows.uidsOf(days.map { it.id }).groupBy { it.windowId }
        val perUid = HashMap<Int, MutableList<DailyMah>>()
        for (day in days) {
            val start = snapshots.snapshot(day.fromSnapshotId)?.wallMs ?: continue
            for (row in uids[day.id].orEmpty()) {
                if (isApp(row.uid) && row.component == ALL_COMPONENTS) perUid.getOrPut(row.uid) { ArrayList() } += DailyMah(start, row.mAh)
            }
        }
        return perUid.entries.sortedBy { it.key }.mapNotNull { (uid, daily) ->
            val updated = ports.lastUpdateWallMs(uid)?.takeIf { it >= nowWallMs - UPDATE_DAYS * DAY_MS } ?: return@mapNotNull null
            AppTrend(uid, updated, daily.sortedBy { it.dayStartWallMs })
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NS_PER_MS = 1_000_000L
        const val DECI = 10.0

        /** Screen-off sessions reach back this far: R04's 14 baseline days before the latest night. */
        const val SESSION_DAYS = 15

        /** Deep idle is worked out for this many of the latest sessions; R17 looks at three. */
        const val DEEP_IDLE_SESSIONS = 12

        /** Ids per IN list, under SQLite's variable limit. */
        const val MAX_IDS = 500

        /** R18's 14 baseline days, the days after an update, and the day of the update. */
        const val TREND_DAYS = 18
        const val UPDATE_DAYS = 4
        val SIGNAL_BINS = 0..4

        /** The component of a UID's whole modeled charge (`CounterMapper.ALL_COMPONENTS`). */
        const val ALL_COMPONENTS = "all"
        const val DEVICE = UidPowerEntity.DEVICE_UID

        /**
         * An app's UID in any user: its app id is at or above `Process.FIRST_APPLICATION_UID`. The
         * per-user range is `UserHandle.PER_USER_RANGE` (android-17.0.0_r1 L51, android-16.0.0_r4 L49).
         */
        fun isApp(uid: Int): Boolean = uid >= 0 && uid % PER_USER_RANGE >= FIRST_APPLICATION_UID

        const val PER_USER_RANGE = 100_000
        const val FIRST_APPLICATION_UID = 10_000

        fun before(
            a: SnapshotEntity,
            b: SnapshotEntity,
        ): Boolean = a.bootSeq < b.bootSeq || (a.bootSeq == b.bootSeq && a.elapsedNs < b.elapsedNs)
    }
}
