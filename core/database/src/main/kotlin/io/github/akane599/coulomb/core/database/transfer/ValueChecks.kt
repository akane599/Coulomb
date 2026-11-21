// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.akane599.coulomb.core.database.entity.CorruptChunkException
import io.github.akane599.coulomb.core.database.entity.HistoryChunkCodec
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.database.entity.KernelCodes
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction
import io.github.akane599.coulomb.core.model.HistoryGapReason
import io.github.akane599.coulomb.core.model.InsightSeverity
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.ReferenceOrigin
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage

/**
 * The columns Room stores as an enum's name, as `table.column`, with the names each may hold.
 * Room's generated converter throws on any other text, so an imported row with one would end the
 * screen that reads it. A new enum column is added here or, when it is stored as a code, to
 * [CodedColumns] or [ValueChecks]; `TransferTest` holds the three against the entities.
 */
public object NamedColumns {
    public val NAMES: Map<String, List<String>> =
        mapOf(
            "capability.state" to CapabilityState.entries.map { it.name },
            "capability.tier" to AccessTier.entries.map { it.name },
            "capability.fixAction" to FixAction.entries.map { it.name },
            "history_gap.reason" to HistoryGapReason.entries.map { it.name },
            "insight.severity" to InsightSeverity.entries.map { it.name },
            "insight.state" to InsightState.entries.map { it.name },
            "action_log.tier" to AccessTier.entries.map { it.name },
            "action_log.origin" to ActionOrigin.entries.map { it.name },
            "uid_power.provenance" to Provenance.entries.map { it.name },
            "wake_stat.kind" to WakeKind.entries.map { it.name },
            "window_wake.kind" to WakeKind.entries.map { it.name },
            "reference.origin" to ReferenceOrigin.entries.map { it.name },
            "reference.autoKind" to AutoReference.entries.map { it.name },
        )
}

/**
 * The checks of an imported bundle's values, after the load and Room's own validation
 * (ADR-0062 §11): everything a reader of the archive would throw on instead of showing. They run
 * on this build's schema, so the lists here are the ones the readers use.
 *
 * What stays unchecked is free text that only its own parser reads (a kernel read's gaps and
 * causes, an insight's evidence): a reader of an archive must take a failure there as an
 * unreadable row, not as a crash.
 */
internal object ValueChecks {
    /** The integer columns that store a code of their own, each with what makes a code known. */
    val SELF_CODED: Map<String, (Long) -> Boolean> =
        mapOf(
            "snapshot.privilegeMode" to { code -> PrivilegeMode.entries.any { it.code.toLong() == code } },
            "window_result.breakdown" to { code -> Breakdown.entries.any { it.code.toLong() == code } },
            "window_result.kernelCoverage" to ::coverage,
        )

    /** The ids that point at a row of another table without a foreign key, as child to parent. */
    private val LOOKUPS: List<Pair<String, String>> =
        listOf(
            "window_stat.keyId" to "stat_key.id",
            "window_uid.componentId" to "stat_key.id",
            "window_uid.processStateId" to "stat_key.id",
            "window_wake.tagId" to "wake_tag.id",
            "kernel_log_event.tagId" to "history_tag.id",
            "history_gap.startBootSeq" to "boot.bootSeq",
            "history_gap.endBootSeq" to "boot.bootSeq",
        )

    /** Throws [BundleRejectedException] for the first value no reader could take. */
    fun run(db: SupportSQLiteDatabase) {
        for ((key, names) in NamedColumns.NAMES) {
            distinct(db, key) { cursor -> if (cursor.getString(0) !in names) damaged("$key: an unknown name") }
        }
        for ((key, known) in SELF_CODED) {
            distinct(db, key) { cursor -> if (!known(cursor.getLong(0))) damaged("$key: an unknown code") }
        }
        for ((child, parent) in LOOKUPS) lookup(db, child, parent)
        kernelEvents(db)
        chunks(db)
    }

    /** Every distinct value of `table.column` that is not null. The names are this file's own, never the bundle's. */
    private fun distinct(
        db: SupportSQLiteDatabase,
        key: String,
        check: (Cursor) -> Unit,
    ) {
        val (table, column) = key.split(".")
        db.query("SELECT DISTINCT `$column` FROM `$table` WHERE `$column` IS NOT NULL").use { cursor ->
            while (cursor.moveToNext()) check(cursor)
        }
    }

    private fun lookup(
        db: SupportSQLiteDatabase,
        child: String,
        parent: String,
    ) {
        val (table, column) = child.split(".")
        val (parentTable, id) = parent.split(".")
        val sql = "SELECT 1 FROM `$table` WHERE `$column` IS NOT NULL AND `$column` NOT IN (SELECT `$id` FROM `$parentTable`) LIMIT 1"
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) damaged("$child: a row that is not there") }
    }

    /** A kernel log event is read back by its kind, and each kind needs its own fields. */
    private fun kernelEvents(db: SupportSQLiteDatabase) {
        val sql = "SELECT e.kind, e.irq, t.tag, e.errno FROM kernel_log_event e LEFT JOIN history_tag t ON t.id = e.tagId"
        db.query(sql).use { cursor ->
            val errno = cursor.getColumnIndexOrThrow("errno")
            while (cursor.moveToNext()) {
                val stored =
                    KernelCodes.StoredEvent(
                        kind = cursor.getInt(0),
                        irq = if (cursor.isNull(1)) null else cursor.getInt(1),
                        tag = if (cursor.isNull(2)) null else cursor.getString(2),
                        errno = if (cursor.isNull(errno)) null else cursor.getInt(errno),
                    )
                try {
                    KernelCodes.decodeEvent(stored)
                } catch (e: IllegalStateException) {
                    refuse(BundleProblem.DAMAGED, "kernel_log_event: ${e.message}", e)
                }
            }
        }
    }

    /** Every history chunk decodes whole, to kinds and changes this build knows, in boots that are there. */
    private fun chunks(db: SupportSQLiteDatabase) {
        val boots = HashSet<Int>()
        db.query("SELECT bootSeq FROM boot").use { cursor -> while (cursor.moveToNext()) boots += cursor.getInt(0) }
        db.query("SELECT firstId, count, data FROM history_chunk ORDER BY firstId").use { cursor ->
            while (cursor.moveToNext()) {
                val events =
                    try {
                        HistoryChunkCodec.decode(cursor.getInt(1), cursor.getBlob(2))
                    } catch (e: CorruptChunkException) {
                        refuse(BundleProblem.DAMAGED, "history_chunk ${cursor.getLong(0)}: ${e.message}", e)
                    }
                val whole = events.all { HistoryCodes.known(it.typeCode, it.changeCode) && it.bootSeq in boots }
                if (!whole) damaged("history_chunk ${cursor.getLong(0)}: an unknown code or boot")
            }
        }
    }

    private fun coverage(code: Long): Boolean =
        try {
            if (code in 0..Int.MAX_VALUE) KernelCoverage.ofCode(code.toInt())
            code in 0..Int.MAX_VALUE
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

    private fun damaged(what: String): Nothing = refuse(BundleProblem.DAMAGED, what)
}
