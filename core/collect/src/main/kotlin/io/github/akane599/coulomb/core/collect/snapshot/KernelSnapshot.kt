// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.database.dao.KernelRows
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.KmsgSpan
import io.github.akane599.coulomb.core.database.entity.WakeupSourceStatEntity
import io.github.akane599.coulomb.core.model.PrivilegeMode

/** What a snapshot stores of its kernel reads, and the mode it was read in. */
internal data class KernelOutcome(
    /** S25, S26, S9 and S27 when the kernel reads were due; empty otherwise. */
    val statuses: Map<String, SourceStatus>,
    val rows: KernelRows?,
    val mode: PrivilegeMode?,
)

/** Joins a snapshot's two kernel reads under the connection rules of ADR-0029 §1. Pure. */
internal object KernelSnapshot {
    /**
     * [files] and [log] are null when the kernel reads were not due: the snapshot is light, or the
     * server is not root, which [liveMode] then records. Otherwise each is set, as read or with the
     * status that says why it was not.
     *
     * Kernel data counts only when it was read as root, all on one connection: a result from another
     * uid, or reads split across two connections, keep nothing. One kernel boot per row, so a log
     * read of another boot than the files' keeps nothing either.
     */
    fun combine(
        files: KernelFilesRead?,
        log: KernelLogResult?,
        liveMode: PrivilegeMode?,
    ): KernelOutcome {
        if (files == null || log == null) return KernelOutcome(emptyMap(), null, liveMode)
        val (f, l) = kept(files, log)
        val mode = (files.origin ?: log.origin)?.let { PrivilegeMode.ofUid(it.uid) } ?: liveMode
        return KernelOutcome(f.statuses + (KernelReader.S27 to l.status), rows(f, l), mode)
    }

    /** What of the two reads counts: root only, one connection, one kernel boot. */
    private fun kept(
        files: KernelFilesRead,
        log: KernelLogResult,
    ): Pair<KernelFilesRead, KernelLogResult> {
        val f = if (files.origin?.isRoot == false) files.discarded() else files
        val l = if (log.origin?.isRoot == false) log.discarded() else log
        val connections = listOfNotNull(f.origin, l.origin).map { it.connection }.distinct()
        if (connections.size > 1) return f.discarded() to l.discarded()
        val filesBoot = f.storedBoot
        val logBoot = l.read?.bootKey
        return f to if (filesBoot != null && logBoot != null && filesBoot != logBoot) l.discarded() else l
    }

    /** One kernel read row, keyed by the files' boot when they kept a value, else by the log's. */
    private fun rows(
        f: KernelFilesRead,
        l: KernelLogResult,
    ): KernelRows? {
        val boot = f.storedBoot ?: l.read?.bootKey ?: return null
        val span = l.read?.let { KmsgSpan(it.afterSeq, it.firstSeq, it.lastSeq, it.gaps) }
        val read = KernelReadEntity.of(0, boot, f.suspendStats, f.lastWakeup, span)
        val sources = f.wakeupSources.orEmpty().map { WakeupSourceStatEntity.of(0, it) }
        return KernelRows(read, sources, l.read?.records.orEmpty())
    }
}
