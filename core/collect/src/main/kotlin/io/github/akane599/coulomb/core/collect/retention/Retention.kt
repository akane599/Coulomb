// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.retention

import io.github.akane599.coulomb.core.collect.engine.BlobStore
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.BootEntity
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WindowKind

/** A retention cut: rows keyed before `(bootSeq, elapsedNs)` go (ADR-0015). */
internal data class Cut(
    val bootSeq: Int,
    val elapsedNs: Long,
)

/**
 * SPEC 7.7 retention, as ADR-0018 schedules it, in the actor: [run] once a day while charging with
 * the screen off, [pruneCounters] at most hourly. Each "N days ago" becomes a `(bootSeq, elapsedNs)`
 * key counted back in elapsed time, so a wall-clock change cannot prune the wrong rows.
 */
internal class Retention(
    private val db: CoulombDatabase,
    private val blobs: BlobStore,
) {
    data class Report(
        val samplesCompacted: Int,
        val blobsDeleted: Int,
        val vacuumed: Boolean,
    )

    /** Compaction, expiry and `VACUUM` (SPEC 7.7: only while charging and idle; the caller checks). */
    suspend fun run(
        boot: BootEntity,
        nowElapsedNs: Long,
    ): Report {
        val cut = cutter(boot, nowElapsedNs)
        val samples = db.sampleDao()
        val compacted = cut(SAMPLES_MS)?.let { samples.compactSamples(it.bootSeq, it.elapsedNs, MINUTE_S) } ?: 0
        cut(AGGREGATES_MS)?.let { samples.compactAggregates(it.bootSeq, it.elapsedNs, MINUTE_S, HOUR_S) }
        cut(HISTORY_MS)?.let { db.historyDao().deleteBefore(it.bootSeq, it.elapsedNs) }
        cut(DETAIL_MS)?.let { pruneDetail(it) }
        val deleted = pruneBlobs(cut(BLOBS_MS))
        return Report(compacted, deleted, vacuumIfWorthIt())
    }

    /**
     * Deletes the counters no window can start at any more (ADR-0015: kept 2 h). Cheap, so it runs
     * on battery too: a day of heavy snapshots would otherwise keep every counter until the charger.
     * Returns the snapshots pruned.
     */
    suspend fun pruneCounters(
        boot: BootEntity,
        nowElapsedNs: Long,
    ): Int = cutter(boot, nowElapsedNs)(COUNTERS_MS)?.let { db.snapshotDao().pruneCounters(it.bootSeq, it.elapsedNs) } ?: 0

    /**
     * Cuts for one run. The boots and their last-seen times are read only when a cut reaches back
     * past the current boot: the hourly 2 h counter cut almost never does.
     */
    private fun cutter(
        boot: BootEntity,
        nowElapsedNs: Long,
    ): suspend (Long) -> Cut? {
        var earlier: Pair<List<BootEntity>, Map<Int, Long>>? = null
        return { agoMs ->
            val loaded =
                if (nowElapsedNs >= agoMs * NS_PER_MS) {
                    emptyList<BootEntity>() to emptyMap()
                } else {
                    earlier ?: run {
                        val devices = db.deviceDao()
                        (devices.boots() to devices.bootsLastSeen().associate { it.bootSeq to it.lastElapsedNs }).also { earlier = it }
                    }
                }
            cutKey(loaded.first, loaded.second, boot, nowElapsedNs, agoMs)
        }
    }

    /** ADR-0015: consecutive, reference and charge-session detail goes after 30 days; day and discharge detail stays. */
    private suspend fun pruneDetail(cut: Cut) {
        val windows = db.windowDao()
        val ids =
            windows.detailBefore(listOf(WindowKind.CONSECUTIVE, WindowKind.REFERENCE), cut.bootSeq, cut.elapsedNs) +
                windows.sessionDetailBefore(listOf(SessionKind.CHARGE), cut.bootSeq, cut.elapsedNs)
        if (ids.isNotEmpty()) windows.pruneDetail(ids)
    }

    /** Blobs older than 30 days, then the oldest past the folder cap, then files no row names. */
    private suspend fun pruneBlobs(cut: Cut?): Int {
        val snapshots = db.snapshotDao()
        val expired = cut?.let { snapshots.pruneBlobs(it.bootSeq, it.elapsedNs) }.orEmpty()
        blobs.delete(expired)
        val named = snapshots.blobPaths().toSet()
        val stored = blobs.list()
        val orphans = stored.map { it.first }.filter { it !in named }
        blobs.delete(orphans)
        var total = stored.filter { it.first in named }.sumOf { it.second }
        val overCap = mutableListOf<String>()
        for ((name, size) in stored.filter { it.first in named }) {
            if (total <= BLOB_CAP_BYTES) break
            overCap += name
            total -= size
        }
        if (overCap.isNotEmpty()) {
            snapshots.clearBlobPaths(overCap)
            blobs.delete(overCap)
        }
        return expired.size + orphans.size + overCap.size
    }

    /** ADR-0018: `VACUUM` only when more than 10% of the pages are free. The caller runs it while charging. */
    private fun vacuumIfWorthIt(): Boolean {
        val sqlite = db.openHelper.writableDatabase
        val pages = sqlite.query("PRAGMA page_count").use { if (it.moveToFirst()) it.getLong(0) else 0 }
        val free = sqlite.query("PRAGMA freelist_count").use { if (it.moveToFirst()) it.getLong(0) else 0 }
        if (pages == 0L || free * FREE_SHARE_DENOMINATOR <= pages) return false
        sqlite.execSQL("VACUUM")
        return true
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        const val SAMPLES_MS: Long = 14 * DAY_MS
        const val AGGREGATES_MS: Long = 180 * DAY_MS
        const val HISTORY_MS: Long = 30 * DAY_MS
        const val DETAIL_MS: Long = 30 * DAY_MS
        const val COUNTERS_MS: Long = 2 * HOUR_MS
        const val BLOBS_MS: Long = 30 * DAY_MS
        const val BLOB_CAP_BYTES: Long = 128L * 1024 * 1024
        private const val MINUTE_S = 60
        private const val HOUR_S = 3_600
        private const val NS_PER_MS = 1_000_000L

        /** More than one page in ten free. */
        private const val FREE_SHARE_DENOMINATOR = 10

        /**
         * The key [agoMs] before now, counted back in elapsed time: the current boot ran for
         * [nowElapsedNs], each earlier boot for at least its [lastSeen] elapsed time (or its first
         * sighting). The time a boot ran unseen, and boots never stored, count as nothing, so the
         * cut can only fall earlier than the true instant: rows are kept too long, never pruned
         * early. Null when it falls before every stored boot: there is nothing to cut.
         */
        fun cutKey(
            boots: List<BootEntity>,
            lastSeen: Map<Int, Long>,
            current: BootEntity,
            nowElapsedNs: Long,
            agoMs: Long,
        ): Cut? {
            var left = agoMs * NS_PER_MS
            if (nowElapsedNs >= left) return Cut(current.bootSeq, nowElapsedNs - left)
            left -= nowElapsedNs
            for (boot in boots.filter { it.bootSeq < current.bootSeq }.sortedByDescending { it.bootSeq }) {
                val ran = maxOf(lastSeen[boot.bootSeq] ?: 0, boot.firstElapsedNs)
                if (ran >= left) return Cut(boot.bootSeq, ran - left)
                left -= ran
            }
            return null
        }
    }
}
