// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.analytics.WakeDictionary
import io.github.akane599.coulomb.core.analytics.WakeMeaning
import io.github.akane599.coulomb.core.analytics.WakeSubsystem
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

/** One kernel wakelock, wakeup source or wakeup reason, as its detail page shows it (SPEC 10.5). */
public data class WakeSourceDetail(
    val kind: WakeKind,
    /** The record's name, as the kernel or batterystats prints it. */
    val tag: String,
    val subsystem: WakeSubsystem,
    /** Null when no source pins the name down ([WakeDictionary]). */
    val meaning: WakeMeaning?,
    val span: DrainSpan,
    /** The record in [span]; null when the span has none. */
    val entry: WakeEntry?,
    /** How much of [span] the record's source covered: a missing record is zero only under [Coverage.WHOLE]. */
    val coverage: Coverage,
    /** SPEC 10.5's per-session counts: the latest nights, newest first. */
    val nights: List<WakeNight>,
    /** Which apps followed this wake; null for a kind the history does not record ([WakeCorrelation]). */
    val apps: WakeApps?,
)

/** The record in one screen-off session of the Night Report. */
public data class WakeNight(
    val night: NightSummary,
    /** Null when the session has no such record: none happened, or under less than whole [coverage], none was collected. */
    val entry: WakeEntry?,
    val coverage: Coverage,
)

/** The apps that took the first wakelock after the phone woke for one reason, over a stretch of history. */
public data class WakeApps(
    val fromWallMs: Long,
    val toWallMs: Long,
    /** How much of the stretch the stored history covers. Zero: nothing was collected, and [wakes] says nothing. */
    val knownMs: Long,
    /** How many times the history shows the CPU begin to run for this reason in [knownMs]. */
    val wakes: Int,
    /** In how many of [wakes] no wakelock was taken within [WakeSourceRepository.FOLLOW_MS] while the CPU ran. */
    val unclaimed: Int,
    /** In how many of [wakes] one was taken and the history does not name its holder. */
    val unnamed: Int,
    /** Most wakes first. */
    val apps: List<WakeApp>,
)

public data class WakeApp(
    val uid: Int,
    val label: String?,
    /** In how many wakes this app held the first wakelock taken. */
    val wakes: Int,
)

/** The source of a kind's records in a [WakeCoverage]. */
public fun WakeCoverage.of(kind: WakeKind): Coverage =
    when (kind) {
        WakeKind.PARTIAL, WakeKind.KERNEL, WakeKind.WAKEUP_REASON -> batteryStats
        WakeKind.WAKEUP_SOURCE -> wakeupSources
        WakeKind.RESUME_CAUSE, WakeKind.SUSPEND_ABORT -> kernelLog
    }

/**
 * Which apps correlate in time with a wakeup reason (SPEC 10.5), from the history's own records.
 * Pure.
 *
 * The history names why the CPU began to run (`WAKE_REASON`, [TimelineTrack.AWAKE]'s tag) and the
 * holder of the first wakelock taken while none was held ([TimelineTrack.WAKELOCKS]). So for each
 * stretch the CPU ran for this reason, the app counted is the holder of the first wakelock that
 * began in the stretch, no later than [followMs] after it began: a stretch can last as long as
 * the screen is on, and a wakelock taken minutes in did not follow the wake. That is a
 * correlation in time, not a cause: the kernel woke for the reason, and that app was the first to
 * ask the phone to stay awake.
 *
 * A stretch counts only when the history shows it begin: one cut by the start of the read, or
 * one that begins where a gap in the history ends, began at a time nobody recorded. Kernel
 * wakelocks and wakeup sources have no history records, so nothing ties an app to them.
 */
internal object WakeCorrelation {
    fun apps(
        timeline: Timeline,
        reason: String,
        labels: Map<Int, String>,
        followMs: Long = WakeSourceRepository.FOLLOW_MS,
    ): WakeApps {
        val gapEnds = timeline.unknown.mapTo(HashSet()) { it.toWallMs }
        val awake =
            timeline.tracks[TimelineTrack.AWAKE].orEmpty().filter {
                it.tag == reason && it.fromWallMs > timeline.fromWallMs && it.fromWallMs !in gapEnds
            }
        val locks = timeline.tracks[TimelineTrack.WAKELOCKS].orEmpty().sortedBy { it.fromWallMs }
        val starts = LongArray(locks.size) { locks[it].fromWallMs }
        val counts = HashMap<Int, Int>()
        var unclaimed = 0
        var unnamed = 0
        for (stretch in awake) {
            // The first wakelock that begins in the stretch; one held from before it is not this wake's.
            val first =
                locks.getOrNull(starts.lowerBound(stretch.fromWallMs))?.takeIf {
                    it.fromWallMs < stretch.toWallMs && it.fromWallMs - stretch.fromWallMs <= followMs
                }
            val uid = first?.uid
            when {
                first == null -> unclaimed++

                // The first one is the track's; a later named one in the stretch was not first.
                uid == null -> unnamed++

                else -> counts[uid] = (counts[uid] ?: 0) + 1
            }
        }
        val apps =
            counts
                .map { (uid, wakes) -> WakeApp(uid, labels[uid], wakes) }
                .sortedWith(compareByDescending<WakeApp> { it.wakes }.thenBy { it.uid })
        val knownMs = (timeline.toWallMs - timeline.fromWallMs - timeline.unknown.sumOf { it.durationMs }).coerceAtLeast(0)
        return WakeApps(timeline.fromWallMs, timeline.toWallMs, knownMs, awake.size, unclaimed, unnamed, apps)
    }

    /** The index of the first value at or after [value]. */
    private fun LongArray.lowerBound(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }
}

/**
 * The read model of the wakeup source detail (SPEC 10.5): the record in Drain's window, in the
 * latest nights, and for a wakeup reason the apps that followed it in the last day of history.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
public class WakeSourceRepository
    @Inject
    internal constructor(
        private val drains: DrainRepository,
        private val nights: NightRepository,
        private val timelines: TimelineRepository,
        private val dispatchers: AppDispatchers,
    ) {
        /** Emits again when [span]'s report does; null while the span has no report. */
        public fun detail(
            kind: WakeKind,
            tag: String,
            span: DrainSpan,
        ): Flow<WakeSourceDetail?> {
            // A report comes with every snapshot; the nights change only when one ends.
            var listed: List<Long>? = null
            var rows = emptyList<WakeNight>()
            return drains
                .report(span)
                .mapLatest { report ->
                    report ?: return@mapLatest null
                    val latest = nights.nights.first().take(NIGHTS)
                    if (latest.map { it.id } != listed) {
                        rows = nights(latest, kind, tag)
                        listed = latest.map { it.id }
                    }
                    WakeSourceDetail(
                        kind = kind,
                        tag = tag,
                        subsystem = WakeDictionary.subsystem(tag),
                        meaning = WakeDictionary.meaning(kind, tag),
                        span = span,
                        entry = report.find(kind, tag),
                        coverage = report.wakeCoverage.of(kind),
                        nights = rows,
                        apps = if (kind == WakeKind.WAKEUP_REASON) apps(tag, report.drains.toWallMs) else null,
                    )
                }.flowOn(dispatchers.io)
        }

        /** Newest first, as the Night Report lists them. A night whose records are gone is "not collected", not left out. */
        private suspend fun nights(
            latest: List<NightSummary>,
            kind: WakeKind,
            tag: String,
        ): List<WakeNight> =
            latest.map { night ->
                val report = drains.report(DrainSpan.Night(night.id)).first()
                WakeNight(night, report?.find(kind, tag), report?.wakeCoverage?.of(kind) ?: Coverage.NONE)
            }

        /** The day of history that ends where the span does. */
        private suspend fun apps(
            reason: String,
            toWallMs: Long,
        ): WakeApps = WakeCorrelation.apps(timelines.read(toWallMs - HISTORY_MS, toWallMs), reason, timelines.labels.first())

        private fun DrainReport.find(
            kind: WakeKind,
            tag: String,
        ): WakeEntry? = wakes.firstOrNull { it.kind == kind && it.tag == tag && it.uid == null }

        public companion object {
            /** How many nights the page lists. */
            public const val NIGHTS: Int = 7

            /** The stretch of history the apps are read from: a day reads in about 0.1 s (ADR-0056). */
            public const val HISTORY_MS: Long = 24 * 3_600_000L

            /** How long after the CPU began to run a wakelock still counts as following the wake (ADR-0059 §4). */
            public const val FOLLOW_MS: Long = 10_000L
        }
    }
